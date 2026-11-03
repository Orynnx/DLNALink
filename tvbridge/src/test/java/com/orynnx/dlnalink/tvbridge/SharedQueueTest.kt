package com.orynnx.dlnalink.tvbridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SharedQueueTest {
    private fun body(id: String, uri: String = "http://192.0.2.1/media") = JSONObject()
        .put("uri", uri).put("title", "Media").put("mime", "video/mp4")
        .put("owner", "phone-a").put("clientItemId", id)

    @Test fun dedupeRefreshesUriWithoutReorderingOrExposingIt() {
        val queue = SharedQueue(null)
        val first = queue.enqueue(body("a"))
        queue.enqueue(body("b"))
        val repeated = queue.enqueue(body("a", "http://192.0.2.2/renewed"))
        assertEquals(first.id, repeated.id)
        assertEquals(2, queue.items.size)
        assertEquals(first.id, queue.items.first().id)
        assertEquals("http://192.0.2.2/renewed", first.uri)
        assertFalse(queue.snapshot().getJSONArray("items").getJSONObject(0).has("uri"))
        assertEquals(3L, queue.revision)
    }

    @Test fun persistsMetadataAndPrivateUriWithoutPlaybackClaims() {
        val original = SharedQueue(null)
        original.currentId = original.enqueue(body("a")).id
        val restored = SharedQueue(original.snapshot(true).toString())
        assertEquals(original.currentId, restored.currentId)
        assertEquals(original.items.first().uri, restored.items.first().uri)
        assertFalse(restored.snapshot().getBoolean("playbackConfirmed"))
        assertEquals("unknown", restored.control)
    }

    @Test fun rejectsOverflowAndNonHttpSources() {
        val queue = SharedQueue(null)
        repeat(100) { queue.enqueue(body(it.toString())) }
        assertTrue(runCatching { queue.enqueue(body("overflow")) }.isFailure)
        assertEquals(100, queue.items.size)
        listOf("file:///sdcard/video.mp4", "http://user:password@example.org/video", "https://example.org/\n").forEach {
            assertTrue(runCatching { SharedQueue.validateUri(it) }.isFailure)
        }
    }

    @Test fun serializedConcurrentAppendsHaveNoLostUpdates() {
        val queue = SharedQueue(null)
        val lock = Any()
        val threads = (0 until 20).map { index -> Thread {
            synchronized(lock) { queue.enqueue(body(index.toString())) }
        }.also { it.start() } }
        threads.forEach { it.join() }
        assertEquals(20, queue.items.size)
        assertEquals(20L, queue.revision)
        assertEquals(20, queue.items.map { it.id }.toSet().size)
    }
}
