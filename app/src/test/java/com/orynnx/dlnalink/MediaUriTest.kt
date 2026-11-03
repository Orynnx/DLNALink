package com.orynnx.dlnalink

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaUriTest {
    @Test fun preservesSignedQueryWhileEscapingSoapXml() {
        assertEquals("https://example.test/master.m3u8?token=a%2Bb&amp;x=2", escapeMediaUri("https://example.test/master.m3u8?token=a%2Bb&x=2"))
    }

    @Test fun escapesXmlSpecialCharacters() {
        assertEquals("&lt;&gt;&quot;&apos;&amp;", escapeMediaUri("<>\"'&"))
    }
}
