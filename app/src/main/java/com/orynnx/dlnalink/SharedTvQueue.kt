package com.orynnx.dlnalink

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

data class SharedQueueItem(val id: String, val title: String, val mime: String?, val owner: String, val clientItemId: String, val status: String)
data class SharedQueueState(val revision: Long = 0, val items: List<SharedQueueItem> = emptyList(),
    val currentId: String? = null, val control: String = "unknown", val lastError: String? = null, val connected: Boolean = false)

object SharedTvQueue {
    private val mutable = MutableStateFlow(SharedQueueState())
    val state: StateFlow<SharedQueueState> = mutable.asStateFlow()
    private val mutex = Mutex()
    private val client = BridgeClient()
    private fun prefs(context: Context) = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)

    private suspend fun request(context: Context, body: JSONObject? = null): BridgeClient.QueueResponse {
        val preferences = prefs(context)
        return client.queue(preferences.getString("bridgeAddress", "") ?: "", preferences.getString("bridgeToken", "") ?: "", body)
    }

    private fun accept(json: JSONObject) {
        val list = json.getJSONArray("items")
        val items = (0 until list.length()).map { index ->
            val item = list.getJSONObject(index)
            SharedQueueItem(item.getString("id"), item.getString("title"), item.optString("mime").takeUnless { it.isBlank() || it == "null" },
                item.getString("owner"), item.getString("clientItemId"), item.optString("status", "queued"))
        }
        mutable.value = SharedQueueState(json.getLong("revision"), items,
            json.optString("currentId").takeUnless { it.isBlank() || it == "null" }, json.optString("control", "unknown"),
            if (json.isNull("lastError") || !json.has("lastError")) null else "TV command failed; check source availability", true)
    }

    private suspend fun refreshLocked(context: Context) {
        val result = request(context)
        try {
            if (result.snapshot != null) accept(result.snapshot)
            else mutable.value = state.value.copy(connected = false, lastError = "TV queue unavailable; check pairing and connection")
        } catch (_: Exception) {
            mutable.value = state.value.copy(connected = false, lastError = "TV queue response unavailable")
        }
    }

    suspend fun refresh(context: Context) = mutex.withLock { refreshLocked(context) }

    suspend fun command(context: Context, action: String, id: String? = null, toIndex: Int? = null, expectedRevision: Long? = null): Boolean = mutex.withLock {
        val body = JSONObject().put("action", action)
        id?.let { body.put("id", it) }
        toIndex?.let { body.put("toIndex", it) }
        if (action == "move") body.put("revision", expectedRevision ?: state.value.revision)
        val result = request(context, body)
        if (result.code == 409) {
            refreshLocked(context)
            mutable.value = state.value.copy(lastError = "Queue changed; review order and move again")
            return@withLock false
        }
        try {
            if (result.snapshot != null) { accept(result.snapshot); true }
            else { mutable.value = state.value.copy(lastError = "TV command unavailable; check pairing and connection", connected = false); false }
        } catch (_: Exception) { mutable.value = state.value.copy(lastError = "TV queue response unavailable"); false }
    }

    suspend fun enqueue(context: Context, item: QueuedMedia): String? = mutex.withLock {
        val preferences = context.getSharedPreferences("SharedTvQueue", Context.MODE_PRIVATE)
        val owner = preferences.getString("owner", null) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString("owner", it).commit())
        }
        val result = request(context, JSONObject().put("action", "enqueue").put("uri", item.playbackUri)
            .put("title", item.title).put("mime", item.mime ?: "video/*").put("owner", owner).put("clientItemId", item.id))
        try {
            if (result.snapshot != null) { accept(result.snapshot); result.snapshot.getString("queuedId") }
            else { mutable.value = state.value.copy(connected = false, lastError = "Could not publish media to TV; check pairing and source access"); null }
        } catch (_: Exception) { mutable.value = state.value.copy(lastError = "TV queue response unavailable"); null }
    }
}
