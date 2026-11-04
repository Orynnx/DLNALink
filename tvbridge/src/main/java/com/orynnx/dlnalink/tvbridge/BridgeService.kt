package com.orynnx.dlnalink.tvbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.ComponentName
import android.view.KeyEvent
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.security.MessageDigest
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.net.HttpURLConnection

class BridgeService : Service() {
    companion object { const val PORT = 46901 }
    private var server: NanoHTTPD? = null
    private var badge: android.view.View? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastDispatch = "none"

    override fun onCreate() {
        super.onCreate()
        if (!getSharedPreferences("bridge-control", MODE_PRIVATE).getBoolean("enabled", true)) {
            stopSelf()
            return
        }
        if (android.provider.Settings.canDrawOverlays(this)) {
            val window = getSystemService(android.view.WindowManager::class.java)
            badge = android.widget.TextView(this).apply {
                text = "TV Bridge"
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(0x88000000.toInt())
                setPadding(8, 4, 8, 4)
            }
            runCatching { window.addView(badge, android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.END }) }
        }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("bridge", "TV bridge", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, BridgeActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "bridge")
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("DLNALink TV Bridge")
            .setContentText("Listening on LAN port $PORT — tap to open").setContentIntent(open).setOngoing(true).build())
        val secret = Pairing.token(this)
        val lanAddress = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("eth")) }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
        if (lanAddress == null) {
            notifications.notify(1, Notification.Builder(this, "bridge")
                .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("TV Bridge needs a LAN connection")
                .setContentText("Connect Wi-Fi or Ethernet, then reopen bridge.").setContentIntent(open).build())
            stopSelf()
            return
        }
        val queuePrefs = getSharedPreferences("shared-queue", MODE_PRIVATE)
        val queue = SharedQueue(queuePrefs.getString("state", null))
        val queueLock = Any()
        var controlGeneration = 0L // Accessed only while holding queueLock.
        fun persist() { check(queuePrefs.edit().putString("state", queue.snapshot(true).toString()).commit()) }
        fun probe(value: String): Boolean {
            // Never drain the media body. A bounded range request works with phone sources
            // that do not implement HEAD; redirects are left to VLC, not followed here.
            val connection = URI(value).toURL().openConnection() as HttpURLConnection
            return try {
                connection.connectTimeout = 2000; connection.readTimeout = 2000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Range", "bytes=0-0")
                connection.responseCode in 200..399
            } catch (_: Exception) { false } finally { connection.disconnect() }
        }
        fun play(item: SharedQueue.Item) {
            val generation = ++controlGeneration
            queue.currentId = item.id
            if (!probe(item.uri)) {
                item.status = "unavailable"; queue.control = "error"; queue.lastError = "source_unavailable"
                queue.revision++; return
            }
            val intent = Intent(Intent.ACTION_VIEW).setPackage("org.videolan.vlc")
                .setDataAndType(Uri.parse(item.uri), item.mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(packageManager) == null) {
                item.status = "error"; queue.control = "error"; queue.lastError = "vlc_unavailable"
                queue.revision++; return
            }
            item.status = "command_requested"; queue.control = "play_requested"; queue.lastError = null
            queue.revision++
            main.post {
                synchronized(queueLock) {
                    if (generation != controlGeneration) return@synchronized
                    try { startActivity(intent); lastDispatch = "intent_sent_playback_unconfirmed" }
                    catch (_: Exception) {
                        item.status = "error"; queue.control = "error"; queue.lastError = "launch_failed"
                        queue.revision++; runCatching { persist() }
                        lastDispatch = "launch_failed"
                    }
                }
            }
        }
        server = object : NanoHTTPD(lanAddress, PORT) {
            override fun serve(session: IHTTPSession): Response {
                fun response(status: Response.Status, message: JSONObject): Response =
                    newFixedLengthResponse(status, "application/json", message.toString()).apply {
                        addHeader("Cache-Control", "no-store")
                    }
                fun error(status: Response.Status, message: String) = response(status, JSONObject().put("error", message))
                if (session.uri == "/health" && session.method == Method.GET) {
                    return response(Response.Status.OK, JSONObject().put("service", "dlnalink-tvbridge").put("version", 1))
                }
                if (session.uri !in setOf("/play", "/status", "/queue")) return error(Response.Status.NOT_FOUND, "not_found")
                val provided = session.headers["authorization"] ?: ""
                if (!MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), "Bearer $secret".toByteArray(Charsets.UTF_8))) {
                    return error(Response.Status.UNAUTHORIZED, "unauthorized")
                }
                if (session.uri == "/status") {
                    if (session.method != Method.GET) return error(Response.Status.METHOD_NOT_ALLOWED, "GET_required")
                    return response(Response.Status.OK, JSONObject().put("last_dispatch", lastDispatch).put("playback_confirmed", false))
                }
                if (session.uri == "/queue" && session.method == Method.GET) {
                    return synchronized(queueLock) { response(Response.Status.OK, queue.snapshot()) }
                }
                if (session.method != Method.POST) return error(Response.Status.METHOD_NOT_ALLOWED, "POST_required")
                if (session.headers.containsKey("transfer-encoding")) return error(Response.Status.BAD_REQUEST, "chunked_body_not_supported")
                val length = session.headers["content-length"]?.toIntOrNull()
                    ?: return error(Response.Status.BAD_REQUEST, "content_length_required")
                if (length !in 1..16384) return error(Response.Status.BAD_REQUEST, "body_limit_16384_bytes")
                val payload = try {
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = session.inputStream.read(body, offset, length - offset)
                        if (count <= 0) return error(Response.Status.BAD_REQUEST, "incomplete_body")
                        offset += count
                    }
                    JSONObject(String(body, Charsets.UTF_8))
                } catch (_: Exception) {
                    return error(Response.Status.BAD_REQUEST, "invalid_body")
                }
                return synchronized(queueLock) {
                    try {
                        var queuedId: String? = null
                        when (if (session.uri == "/play") "legacy" else payload.getString("action")) {
                            "enqueue", "legacy" -> {
                                val item = queue.enqueue(payload); queuedId = item.id
                                if (session.uri == "/play") play(item)
                            }
                            "remove" -> {
                                val item = queue.items.firstOrNull { it.id == payload.getString("id") }
                                    ?: return@synchronized error(Response.Status.NOT_FOUND, "item_not_found")
                                queue.items.remove(item)
                                if (queue.currentId == item.id) queue.currentId = null
                                queue.revision++
                            }
                            "move" -> {
                                if (payload.getLong("revision") != queue.revision)
                                    return@synchronized error(Response.Status.CONFLICT, "stale_revision")
                                val item = queue.items.firstOrNull { it.id == payload.getString("id") }
                                    ?: return@synchronized error(Response.Status.NOT_FOUND, "item_not_found")
                                val target = payload.getInt("toIndex")
                                require(target in queue.items.indices)
                                queue.items.remove(item); queue.items.add(target, item); queue.revision++
                            }
                            "clear" -> { queue.items.clear(); queue.currentId = null; queue.revision++ }
                            "play", "next" -> {
                                val item = if (payload.getString("action") == "play")
                                    queue.items.firstOrNull { it.id == payload.getString("id") }
                                else queue.items.getOrNull(queue.items.indexOfFirst { it.id == queue.currentId } + 1)
                                if (item == null) return@synchronized error(Response.Status.NOT_FOUND, "item_not_found")
                                play(item)
                            }
                            "pause", "resume", "stop" -> {
                                val action = payload.getString("action")
                                val key = when (action) {
                                    "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                                    "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY
                                    else -> KeyEvent.KEYCODE_MEDIA_STOP
                                }
                                val component = ComponentName("org.videolan.vlc", "androidx.media.session.MediaButtonReceiver")
                                val generation = ++controlGeneration
                                main.post {
                                    synchronized(queueLock) {
                                        if (generation != controlGeneration) return@synchronized
                                        try {
                                            for (event in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                                                sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(component)
                                                    .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(event, key)))
                                            }
                                        } catch (_: Exception) {
                                            queue.control = "error"; queue.lastError = "control_failed"
                                            queue.revision++; runCatching { persist() }
                                        }
                                    }
                                }
                                queue.control = "${action}_requested"; queue.lastError = null
                                queue.items.firstOrNull { it.id == queue.currentId }?.status = "command_requested"
                                queue.revision++
                            }
                            else -> return@synchronized error(Response.Status.BAD_REQUEST, "unknown_action")
                        }
                        persist()
                        response(Response.Status.OK, queue.snapshot().also { if (queuedId != null) it.put("queuedId", queuedId) })
                    } catch (_: IllegalArgumentException) { error(Response.Status.BAD_REQUEST, "invalid_queue_request") }
                    catch (_: org.json.JSONException) { error(Response.Status.BAD_REQUEST, "invalid_queue_request") }
                    catch (_: Exception) { error(Response.Status.INTERNAL_ERROR, "queue_operation_failed") }
                }
            }
        }
        try {
            server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        } catch (_: Exception) {
            notifications.notify(1, Notification.Builder(this, "bridge")
                .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("TV Bridge could not listen")
                .setContentText("Port $PORT unavailable. Close conflicting app and reopen bridge.")
                .setContentIntent(open).build())
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        if (getSharedPreferences("bridge-control", MODE_PRIVATE).getBoolean("enabled", true)) START_STICKY else START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        badge?.let { runCatching { getSystemService(android.view.WindowManager::class.java).removeView(it) } }
        server?.stop()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
