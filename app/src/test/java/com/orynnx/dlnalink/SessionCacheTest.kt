package com.orynnx.dlnalink

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

class SessionCacheTest {
    @Test fun cachedCopiesSurviveSourceDeletionAndAppend() {
        val root = Files.createTempDirectory("session-copy-test").toFile()
        try {
            val cache = SessionCache(root.resolve(UUID.randomUUID().toString()))
            val source = root.resolve("original").apply { writeText("original media bytes") }
            val firstId = UUID.randomUUID().toString()
            val first = cache.copy(firstId, source.inputStream()) { }
            assertTrue(source.delete())
            val second = cache.copy(UUID.randomUUID().toString(), "next media".byteInputStream()) { }
            assertEquals("original media bytes", first.readText())
            assertEquals("next media", second.readText())
            try { cache.file("../original"); fail("Traversal accepted") } catch (_: IllegalArgumentException) { }
        } finally { root.deleteRecursively() }
    }
    @Test fun quotaAndReserveBoundaries() {
        assertTrue(cacheFits(SESSION_LIMIT - 1, 1, DISK_RESERVE + 1))
        assertFalse(cacheFits(SESSION_LIMIT, 1, Long.MAX_VALUE))
        assertFalse(cacheFits(0, 2, DISK_RESERVE + 1))
        assertFalse(cacheFits(Long.MAX_VALUE, 1, Long.MAX_VALUE))
    }

    @Test fun interruptedCopyNeverPromotesPartialFile() {
        val root = Files.createTempDirectory("session-cache-test").toFile()
        try {
            val cache = SessionCache(root.resolve(UUID.randomUUID().toString()))
            val id = UUID.randomUUID().toString()
            try {
                cache.copy(id, byteArrayOf(1, 2, 3).inputStream()) { error("Stopped") }
                fail("Expected interrupted copy")
            } catch (_: IllegalStateException) { }
            assertFalse(cache.file(id).exists())
            assertEquals(0, cache.directory.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }
}
