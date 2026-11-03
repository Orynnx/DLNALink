package com.orynnx.dlnalink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareMediaTest {
    @Test fun directLinksPreserveQueriesAndAppendOrder() {
        assertEquals(listOf("https://media.example/a.mp4?token=abc&v=2", "http://media.example/b.m3u8"),
            extractSharedMediaLinks("Watch https://media.example/a.mp4?token=abc&v=2 and http://media.example/b.m3u8"))
    }
    @Test fun normalPagesAreRejected() {
        assertTrue(extractSharedMediaLinks("https://example.com/watch?v=1", "text/plain").isEmpty())
    }
    @Test fun explicitMediaMimeAllowsExtensionlessStream() {
        assertEquals(listOf("https://example.com/live?id=1"), extractSharedMediaLinks("https://example.com/live?id=1", "application/dash+xml"))
        assertEquals(listOf("https://example.com/live"), extractSharedMediaLinks("https://example.com/live", "audio/mpeg"))
    }
    @Test fun rejectsNonHttpAndMalformedHosts() {
        assertTrue(extractSharedMediaLinks("file:///tmp/a.mp4 https:///a.mp4 javascript:alert(1)", "video/mp4").isEmpty())
    }
    @Test fun recognizesFormatsAndDeduplicates() {
        val formats = listOf("m3u8", "mpd", "mp4", "mkv", "webm", "mov", "m4v", "ts", "mp3", "aac", "wav", "ogg", "flac", "m4a")
        val urls = formats.map { "https://example.com/media.$it" }
        assertEquals(urls, extractSharedMediaLinks((urls + urls.first()).joinToString(" ")))
    }
    @Test fun signedQueryPunctuationIsNotRewritten() {
        val link = "https://example.com/a.mp4?signature=abc.)"
        assertEquals(listOf(link), extractSharedMediaLinks(link))
    }
}
