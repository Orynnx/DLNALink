package com.orynnx.dlnalink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import fi.iki.elonen.NanoHTTPD
import java.util.UUID
import java.io.File
import android.os.PowerManager
import kotlinx.coroutines.*

/** Hosts only the explicitly opened item; never exposes a directory. */
class MediaHostService : Service() {
    private val actions = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("media-host", "Local media casting", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, MediaHostService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val play = PendingIntent.getService(this, 2, Intent(this, MediaHostService::class.java).setAction("play"), PendingIntent.FLAG_IMMUTABLE)
        val next = PendingIntent.getService(this, 3, Intent(this, MediaHostService::class.java).setAction("next"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "media-host")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Sharing media with your TV")
            .setContentText("Keep Wi-Fi connected. Stop sharing when finished.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Play", play).build())
            .addAction(Notification.Action.Builder(null, "Next", next).build())
            .addAction(Notification.Action.Builder(null, "Stop sharing", stop).build())
            .setOngoing(true).build())
        if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DLNALink:session").apply { acquire() }
        when (intent?.action) {
            "stop" -> CastSession.stop(this)
            "play" -> actions.launch {
                CastSession.restore(applicationContext)
                val state = CastSession.state.value
                (state.currentId ?: state.items.firstOrNull()?.id)?.let { CastSession.play(applicationContext, it) }
            }
            "next" -> actions.launch { CastSession.next(applicationContext) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { actions.cancel(); wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null; super.onDestroy() }

    companion object {
        private var server: NanoHTTPD? = null
        private data class Hosted(val file: File, val mime: String?)
        private val hosted = java.util.concurrent.ConcurrentHashMap<String, Hosted>()
        private val paths = mutableMapOf<String, String>()
        private var boundAddress: String? = null

        @Synchronized fun closeServer() { server?.stop(); server = null; boundAddress = null; hosted.clear(); paths.clear() }
        @Synchronized fun remove(id: String) { paths.remove(id)?.let { hosted.remove(it) } }

        @Synchronized fun hostFile(id: String, file: File, address: String, mime: String?): String {
            require(file.isFile) { "Cached media unavailable" }
            check(server == null || boundAddress == address) { "Network changed; stop sharing before reconnecting" }
            if (server == null) {
                val next = object : NanoHTTPD(address, 0) {
                    override fun serve(session: IHTTPSession): Response {
                        val media = hosted[session.uri] ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
                        if (session.method != Method.GET && session.method != Method.HEAD)
                            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "GET or HEAD required")
                        val size = media.file.length()
                        val range = session.headers["range"]
                        val bounds = mediaRange(range, size)
                        if (range != null && bounds == null) return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Unsupported range").apply { addHeader("Content-Range", "bytes */$size") }
                        return try {
                            // NanoHTTPD 2.3.1 still writes its data stream for HEAD.
                            // Keep the resource length but give it no body to write.
                            val input = if (session.method == Method.HEAD) java.io.ByteArrayInputStream(ByteArray(0)) else media.file.inputStream()
                            try {
                                var remaining = bounds?.first ?: 0L
                                while (remaining > 0) {
                                    val skipped = input.skip(remaining)
                                    if (skipped > 0) remaining -= skipped else if (input.read() >= 0) remaining-- else error("Range unavailable")
                                }
                                newFixedLengthResponse(if (bounds == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT,
                                    media.mime ?: "application/octet-stream", input, bounds?.let { it.last - it.first + 1 } ?: size).apply {
                                    setRequestMethod(session.method)
                                    setKeepAlive(false)
                                    addHeader("Connection", "close")
                                    addHeader("Accept-Ranges", "bytes")
                                    addHeader("Cache-Control", "no-store")
                                    if (bounds != null) addHeader("Content-Range", "bytes ${bounds.first}-${bounds.last}/$size")
                                }
                            } catch (e: Exception) { input.close(); throw e }
                        } catch (_: Exception) { newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Media unavailable") }
                    }
                }
                next.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = next
                boundAddress = address
            }
            val path = paths.getOrPut(id) { "/media/${UUID.randomUUID()}" }
            hosted[path] = Hosted(file, mime)
            return "http://$address:${server!!.listeningPort}$path"
        }

    }
}

internal fun mediaRange(header: String?, size: Long): LongRange? {
    if (header == null || size <= 0) return null
    val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header) ?: return null
    val left = match.groupValues[1]
    val right = match.groupValues[2]
    if (left.isEmpty()) {
        val count = right.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return maxOf(0, size - count)..(size - 1)
    }
    val start = left.toLongOrNull()?.takeIf { it < size } ?: return null
    val end = if (right.isEmpty()) size - 1 else right.toLongOrNull() ?: return null
    if (end < start) return null
    return start..minOf(end, size - 1)
}
