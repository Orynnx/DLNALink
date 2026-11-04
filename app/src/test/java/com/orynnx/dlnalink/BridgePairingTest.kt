package com.orynnx.dlnalink

import org.junit.Assert.*
import org.junit.Test

class BridgePairingTest {
    private val key = "a".repeat(64)
    private fun code(host: String = "192.168.50.10") = "dlnalink://pair?v=1&host=$host&port=46901&key=$key"
    @Test fun acceptsOnlySupportedPrivateLanPairing() {
        assertEquals(BridgePairing("192.168.50.10", key), parseBridgePairing(code()))
        for (host in listOf("127.0.0.1", "8.8.8.8", "192.168.1.999", "example.com", "192.168.01.2")) assertNull(parseBridgePairing(code(host)))
    }
    @Test fun rejectsMalformedOrRedirectedPairing() {
        for (value in listOf(code().replace("v=1", "v=2"), code() + "&key=$key", code() + "#fragment", code().replace("46901", "80"), "https://example.com/", "dlnalink://pair")) assertNull(parseBridgePairing(value))
    }
}
