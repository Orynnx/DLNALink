package com.orynnx.dlnalink

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class QueuedMedia(val id: String, val title: String, val mime: String?, val sizeBytes: Long, val playbackUri: String)
data class SessionState(
    val items: List<QueuedMedia> = emptyList(), val currentId: String? = null,
    val busy: Boolean = false, val error: String? = null, val active: Boolean = false,
    val cachedBytes: Long = 0, val status: String = "Session stopped"
)

object CastSession {
    private val mutable = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = mutable.asStateFlow()
    val sessionVersion: Long get() = generation.get()
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private val lifecycleGuard = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loaded = false
    private val stopPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private var cache: SessionCache? = null
    private val localIds = mutableSetOf<String>()
    private fun prefs(context: Context) = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)

    suspend fun restore(context: Context) = withContext(Dispatchers.IO) { mutex.withLock { load(context.applicationContext) } }

    private fun load(context: Context) {
        synchronized(lifecycleGuard) {
        cleanupStopped(context)
        if (loaded) return
        loaded = true
        try {
            val raw = context.getSharedPreferences("CastSession", Context.MODE_PRIVATE).getString("queue", null) ?: return
            val json = JSONObject(raw)
            val directoryId = json.getString("directory")
            require(Regex("[0-9a-f-]{36}").matches(directoryId))
            cache = SessionCache(File(File(context.filesDir, "session-cache"), directoryId))
            cache!!.directory.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
            val list = json.getJSONArray("items")
            val items = (0 until list.length()).mapNotNull { index ->
                val item = list.getJSONObject(index)
                val id = item.getString("id")
                val local = item.getBoolean("local")
                if (local && !cache!!.file(id).isFile) null else {
                    if (local) localIds.add(id)
                    QueuedMedia(id, item.getString("title"), item.optString("mime").takeIf { it.isNotEmpty() },
                        item.getLong("size"), if (local) "" else item.getString("uri"))
                }
            }
            mutable.value = SessionState(items = items, cachedBytes = cache!!.bytes(), status = "Queue restored; select Play")
        } catch (_: Exception) {
            mutable.value = SessionState(error = "Saved queue unavailable", status = "Saved queue unavailable")
        }
        }
    }

    private fun cleanupStopped(context: Context) {
        synchronized(lifecycleGuard) {
        if (!stopPending.getAndSet(false)) return
        MediaHostService.closeServer()
        File(context.filesDir, "session-cache").listFiles()?.filter {
            it.isDirectory && Regex("[0-9a-f-]{36}").matches(it.name)
        }?.forEach { it.deleteRecursively() }
        cache = null
        localIds.clear()
        loaded = true
        context.stopService(Intent(context, MediaHostService::class.java))
        context.getSharedPreferences("CastSession", Context.MODE_PRIVATE).edit().remove("queue").commit()
        }
    }

    private fun persist(context: Context) {
        val directory = cache?.directory ?: return
        val items = JSONArray()
        state.value.items.forEach { item -> items.put(JSONObject().put("id", item.id).put("title", item.title)
            .put("mime", item.mime ?: "").put("size", item.sizeBytes).put("local", item.id in localIds)
            .put("uri", if (item.id in localIds) "" else item.playbackUri)) }
        check(context.getSharedPreferences("CastSession", Context.MODE_PRIVATE).edit().putString("queue",
            JSONObject().put("directory", directory.name).put("items", items).toString()).commit()) { "Cannot save queue" }
    }

    private fun start(context: Context) {
        context.startForegroundService(Intent(context, MediaHostService::class.java).setAction("start"))
        mutable.value = state.value.copy(active = true)
    }

    private fun hosted(item: QueuedMedia): QueuedMedia {
        if (item.id !in localIds) return item
        val address = NetworkInterface.getNetworkInterfaces().toList().filter {
            it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("wifi") || it.name.startsWith("eth"))
        }.flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress ?: error("Connect to Wi-Fi first")
        return item.copy(playbackUri = MediaHostService.hostFile(item.id, cache!!.file(item.id), address, item.mime))
    }

    suspend fun enqueue(context: Context, uri: Uri, mime: String?): QueuedMedia {
        val requestedEpoch = generation.get()
        return scope.async {
        mutex.withLock {
            val ctx = context.applicationContext
            load(ctx)
            val epoch = requestedEpoch
            var copied: File? = null
            val before = state.value
            var addedId: String? = null
            try {
                synchronized(lifecycleGuard) {
                    check(epoch == generation.get()) { "Session stopped" }
                    start(ctx)
                    mutable.value = state.value.copy(busy = true, error = null, status = "Preparing media copy")
                }
                if (cache == null) cache = SessionCache(File(File(ctx.filesDir, "session-cache"), UUID.randomUUID().toString()))
                val id = UUID.randomUUID().toString()
                addedId = id
                val remote = uri.scheme in listOf("http", "https")
                require(remote || uri.scheme in listOf("content", "file")) { "Unsupported media URI" }
                if (remote) require(!uri.host.isNullOrBlank()) { "Invalid media URL" }
                var title = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "Shared media"
                if (!remote) ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) title = it.getString(0) ?: title
                }
                val type = mime?.takeUnless { it == "text/plain" || it == "*/*" }
                    ?: (if (!remote) ctx.contentResolver.getType(uri) else null)
                    ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(title.substringAfterLast('.', "").lowercase())
                    ?: "application/octet-stream"
                val coroutine = currentCoroutineContext()
                if (!remote) copied = cache!!.copy(id, ctx.contentResolver.openInputStream(uri) ?: error("Cannot open media")) {
                    coroutine.ensureActive()
                    check(epoch == generation.get()) { "Session stopped" }
                }
                val item = QueuedMedia(id, title, type, copied?.length() ?: 0, if (remote) uri.toString() else "")
                synchronized(lifecycleGuard) {
                    check(epoch == generation.get()) { "Session stopped" }
                    if (!remote) localIds.add(id)
                    mutable.value = state.value.copy(items = state.value.items + item, cachedBytes = cache!!.bytes(), busy = false, status = "Added to queue")
                    persist(ctx)
                }
                if (prefs(ctx).getBoolean("bridgeEnabled", false)) {
                    val published = try {
                        val source = hosted(item)
                        val queuedId = SharedTvQueue.enqueue(ctx, source)
                        synchronized(lifecycleGuard) {
                            if (epoch == generation.get()) mutable.value = state.value.copy(items = state.value.items.map { if (it.id == id) source else it })
                        }
                        queuedId != null
                    } catch (_: Exception) { false }
                    synchronized(lifecycleGuard) {
                        if (epoch == generation.get()) mutable.value = state.value.copy(
                            error = if (published) null else "Saved on phone; could not publish to TV. Check connection and source access",
                            status = if (published) "Added to TV queue; playback unchanged" else "Saved on phone; TV queue unavailable")
                    }
                }
                item
            } catch (e: Exception) {
                copied?.delete()
                addedId?.let { localIds.remove(it) }
                synchronized(lifecycleGuard) {
                    if (epoch == generation.get()) mutable.value = before.copy(busy = false, error = "Could not add media; check storage and source access", status = "Add failed")
                }
                throw e
            }
        }
    }.await()
    }

    suspend fun play(context: Context, id: String): Boolean {
        val requestedEpoch = generation.get()
        return withContext(Dispatchers.IO) { mutex.withLock {
        val ctx = context.applicationContext
        load(ctx)
        val epoch = requestedEpoch
        var item = state.value.items.find { it.id == id } ?: return@withLock false
        try {
            synchronized(lifecycleGuard) {
                check(epoch == generation.get()) { "Session stopped" }
                start(ctx)
                mutable.value = state.value.copy(busy = true, error = null, status = "Sending playback request")
            }
            item = hosted(item)
            if (epoch != generation.get()) { MediaHostService.closeServer(); return@withLock false }
            val preferences = prefs(ctx)
            val bridge = preferences.getBoolean("bridgeEnabled", false)
            val accepted = if (bridge) {
                val sharedId = SharedTvQueue.enqueue(ctx, item)
                if (sharedId == null) false else {
                    val shared = SharedTvQueue.state.value
                    SharedTvQueue.command(ctx, if (shared.currentId == sharedId && shared.control == "pause_requested") "resume" else "play", sharedId)
                }
            }
            else {
                val target = preferences.getString("sessionDlna", null)?.let { JSONObject(it) }
                if (target == null) false else MainViewModel().castToDevice(DlnaDevice(target.getString("uid"), target.getString("name"), "", "", "", "", target.getString("control")), item.playbackUri)
            }
            synchronized(lifecycleGuard) {
            if (epoch != generation.get()) return@withLock false
            mutable.value = state.value.copy(items = state.value.items.map { if (it.id == id) item else it },
                currentId = if (accepted) id else state.value.currentId, busy = false,
                error = if (accepted) null else "Playback request failed; check selected target",
                status = if (accepted) "Playback request accepted; TV playback not confirmed" else "Playback request failed")
            }
            accepted
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            synchronized(lifecycleGuard) {
                if (epoch == generation.get()) mutable.value = state.value.copy(busy = false, error = "Playback request failed", status = "Playback request failed")
            }
            false
        }
    } }
    }

    suspend fun next(context: Context): Boolean {
        val requestedEpoch = generation.get()
        restore(context)
        if (requestedEpoch != generation.get()) return false
        if (prefs(context).getBoolean("bridgeEnabled", false)) return SharedTvQueue.command(context, "next")
        val snapshot = state.value
        val index = snapshot.items.indexOfFirst { it.id == snapshot.currentId }
        val next = snapshot.items.getOrNull(index + 1) ?: run {
            synchronized(lifecycleGuard) {
                if (requestedEpoch == generation.get()) mutable.value = state.value.copy(status = "End of queue; choose another item to replay")
            }
            return false
        }
        return play(context, next.id)
    }

    suspend fun remove(context: Context, id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        load(context.applicationContext)
        synchronized(lifecycleGuard) {
        MediaHostService.remove(id)
        if (localIds.remove(id)) cache?.file(id)?.delete()
        mutable.value = state.value.copy(items = state.value.items.filterNot { it.id == id },
            currentId = state.value.currentId.takeUnless { it == id }, cachedBytes = cache?.bytes() ?: 0)
        persist(context)
        }
    } }

    fun rememberDlna(context: Context, device: DlnaDevice) {
        if (device.controlUrl != null) prefs(context).edit().putString("sessionDlna", JSONObject()
            .put("uid", device.uid).put("name", device.friendlyName).put("control", device.controlUrl).toString()).apply()
    }

    fun stop(context: Context) {
        synchronized(lifecycleGuard) {
        generation.incrementAndGet()
        stopPending.set(true)
        MediaHostService.closeServer()
        mutable.value = SessionState(status = "Session stopped")
        context.stopService(Intent(context, MediaHostService::class.java))
        }
        scope.launch { mutex.withLock {
            cleanupStopped(context.applicationContext)
        } }
    }
}
