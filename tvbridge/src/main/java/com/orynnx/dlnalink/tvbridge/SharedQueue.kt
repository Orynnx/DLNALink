package com.orynnx.dlnalink.tvbridge

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** All access is serialized by the server, including persistence and dispatch. */
internal class SharedQueue(saved: String?) {
    data class Item(val id: String, var uri: String, var title: String, var mime: String,
                    val owner: String, val clientItemId: String, var status: String = "queued")
    val items = mutableListOf<Item>()
    var revision = 0L
    var currentId: String? = null
    var control = "idle"
    var lastError: String? = null

    init {
        runCatching {
            val root = JSONObject(saved ?: "{}")
            revision = root.optLong("revision", 0)
            val list = root.optJSONArray("items") ?: JSONArray()
            for (i in 0 until minOf(list.length(), 100)) {
                val o = list.getJSONObject(i)
                validateUri(o.getString("uri"))
                items.add(Item(o.getString("id"), o.getString("uri"), o.getString("title").take(256),
                    o.getString("mime"), o.getString("owner"), o.getString("clientItemId"), o.optString("status", "queued")))
            }
            // A service restart does not establish whether VLC still plays.
            currentId = root.optString("currentId").takeIf { id -> items.any { it.id == id } }
            control = "unknown"
        }.onFailure { items.clear(); currentId = null; revision = 0 }
    }

    fun enqueue(body: JSONObject): Item {
        val uri = body.getString("uri").also(::validateUri)
        fun bounded(key: String, max: Int, fallback: String = ""): String {
            val value = if (body.has(key)) body.getString(key) else fallback
            require(value.length <= max && value.none { it.isISOControl() }) { "invalid_$key" }
            return value
        }
        val owner = bounded("owner", 128, "legacy")
        val client = bounded("clientItemId", 128, UUID.randomUUID().toString())
        require(owner.isNotBlank() && client.isNotBlank()) { "owner_and_clientItemId_required" }
        val title = bounded("title", 256, "Media")
        val mime = bounded("mime", 128, "video/*")
        require(mime.matches(Regex("[A-Za-z0-9.+*-]+/[A-Za-z0-9.+*-]+"))) { "invalid_mime" }
        val prior = items.firstOrNull { it.owner == owner && it.clientItemId == client }
        if (prior != null) {
            prior.uri = uri; prior.title = title; prior.mime = mime
            if (prior.status == "unavailable" || prior.status == "error") prior.status = "queued"
            revision++
            return prior
        }
        require(items.size < 100) { "queue_limit_100" }
        return Item(UUID.randomUUID().toString(), uri, title, mime, owner, client).also { items.add(it); revision++ }
    }

    fun snapshot(privateUris: Boolean = false): JSONObject = JSONObject().put("revision", revision)
        .put("items", JSONArray().also { array -> items.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("title", item.title).put("mime", item.mime)
                .put("owner", item.owner).put("clientItemId", item.clientItemId).put("status", item.status)
                .also { if (privateUris) it.put("uri", item.uri) })
        } }).put("currentId", currentId ?: JSONObject.NULL).put("control", control)
        .put("lastError", lastError ?: JSONObject.NULL).put("playbackConfirmed", false)

    companion object {
        fun validateUri(value: String) {
            val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("invalid_uri") }
            require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.port in -1..65535 && value.none { it.isISOControl() }) {
                "absolute_http_or_https_uri_required"
            }
        }
    }
}
