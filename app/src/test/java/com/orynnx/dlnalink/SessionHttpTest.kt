package com.orynnx.dlnalink

import java.net.HttpURLConnection
import java.net.URI
import java.net.Socket
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SessionHttpTest {
    private fun connection(url: String, method: String = "GET", range: String? = null): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 2000; readTimeout = 2000; requestMethod = method
            range?.let { setRequestProperty("Range", it) }
        }

    @Test fun queuedFilesKeepIndependentUrlsAndRangesUntilRemoved() {
        val root = Files.createTempDirectory("queue-http-test").toFile()
        try {
            val first = root.resolve("first").apply { writeBytes(ByteArray(100) { it.toByte() }) }
            val second = root.resolve("second").apply { writeText("second media") }
            val url = MediaHostService.hostFile("first", first, "127.0.0.1", "video/mp4")
            val other = MediaHostService.hostFile("second", second, "127.0.0.1", "video/mp4")
            assertEquals(url, MediaHostService.hostFile("first", first, "127.0.0.1", "video/mp4"))
            val range = connection(url, range = "bytes=10-19")
            try {
                assertEquals(206, range.responseCode)
                assertEquals("bytes 10-19/100", range.getHeaderField("Content-Range"))
                assertArrayEquals(ByteArray(10) { (it + 10).toByte() }, range.inputStream.use { it.readBytes() })
            } finally { range.disconnect() }
            val full = connection(other)
            try { assertEquals("second media", full.inputStream.bufferedReader().use { it.readText() }) }
            finally { full.disconnect() }
            MediaHostService.remove("first")
            val removed = connection(url)
            try { assertEquals(404, removed.responseCode) } finally { removed.disconnect() }
            val invalid = connection(other, range = "bytes=99999-")
            try { assertEquals(416, invalid.responseCode) } finally { invalid.disconnect() }
        } finally { MediaHostService.closeServer(); root.deleteRecursively() }
    }

    @Test fun headHasNoMediaBodyAndClosesConnection() {
        val root = Files.createTempDirectory("queue-head-test").toFile()
        try {
            val file = root.resolve("video").apply { writeBytes(ByteArray(16000) { 42 }) }
            val url = URI(MediaHostService.hostFile("head", file, "127.0.0.1", "video/mp4"))
            val bytes = Socket(url.host, url.port).use { socket ->
                socket.soTimeout = 2000
                socket.getOutputStream().write("HEAD ${url.rawPath} HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                socket.getInputStream().readBytes()
            }
            val text = bytes.toString(Charsets.ISO_8859_1)
            val end = text.indexOf("\r\n\r\n")
            assertTrue(end > 0)
            assertEquals(0, bytes.size - end - 4)
            assertTrue(text.contains("Content-Length: 16000", ignoreCase = true))
            assertTrue(text.contains("Connection: close", ignoreCase = true))
        } finally { MediaHostService.closeServer(); root.deleteRecursively() }
    }
}
