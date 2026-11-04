package com.orynnx.dlnalink

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Explicitly paired LAN bridge. Never logs credentials, media URIs or response bodies. */
class BridgeClient {
    data class QueueResponse(val code: Int, val snapshot: JSONObject?)
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun endpoint(address: String, path: String): HttpUrl = HttpUrl.Builder()
        .scheme("http")
        .host(address.trim().removePrefix("[").removeSuffix("]"))
        .port(46901)
        .addPathSegment(path)
        .build()

    suspend fun test(address: String, token: String): Boolean = request(address, token, null, null)

    suspend fun play(address: String, token: String, uri: String, mime: String?): Boolean =
        request(address, token, uri, mime)

    suspend fun queue(address: String, token: String, body: JSONObject? = null): QueueResponse =
        withContext(Dispatchers.IO) {
            if (address.isBlank() || token.isBlank()) return@withContext QueueResponse(0, null)
            try {
                val builder = Request.Builder().url(endpoint(address, "queue"))
                    .header("Authorization", "Bearer $token")
                if (body != null) builder.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                client.newCall(builder.build()).execute().use { response ->
                    QueueResponse(response.code, if (response.isSuccessful) response.body?.string()?.let { JSONObject(it) } else null)
                }
            } catch (_: Exception) { QueueResponse(0, null) }
        }

    private suspend fun request(address: String, token: String, uri: String?, mime: String?): Boolean =
        withContext(Dispatchers.IO) {
            if (address.isBlank() || token.isBlank()) return@withContext false
            try {
                val builder = Request.Builder()
                    .url(endpoint(address, if (uri == null) "status" else "play"))
                    .header("Authorization", "Bearer $token")
                if (uri != null) {
                    val parsed = android.net.Uri.parse(uri)
                    if (parsed.scheme !in listOf("http", "https") || parsed.host.isNullOrEmpty()) {
                        return@withContext false
                    }
                    val type = mime?.takeIf { it != "text/plain" && it != "*/*" }
                        ?: when (parsed.lastPathSegment?.substringAfterLast('.')?.lowercase()) {
                            "m3u8" -> "application/vnd.apple.mpegurl"
                            "mp4" -> "video/mp4"
                            "mkv" -> "video/x-matroska"
                            "webm" -> "video/webm"
                            else -> "video/*"
                        }
                    val body = JSONObject().put("uri", uri).put("mime", type).toString()
                    builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                }
                client.newCall(builder.build()).execute().use { it.isSuccessful }
            } catch (_: java.io.IOException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            }
        }
}
