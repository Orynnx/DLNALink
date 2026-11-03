package com.orynnx.dlnalink

import org.junit.Assert.*
import org.junit.Test

class MediaRangeTest {
    @Test fun supportsSeekingAndSuffixes() {
        assertEquals(20L..99L, mediaRange("bytes=20-", 100))
        assertEquals(10L..19L, mediaRange("bytes=10-19", 100))
        assertEquals(90L..99L, mediaRange("bytes=-10", 100))
        assertEquals(90L..99L, mediaRange("bytes=90-999", 100))
    }
    @Test fun rejectsInvalidOrUnsupportedRanges() {
        for (range in listOf("bytes=100-", "bytes=20-10", "bytes=-0", "bytes=0-2,5-9", "bytes=-")) assertNull(mediaRange(range, 100))
        assertNull(mediaRange("bytes=0-", -1))
    }
}
