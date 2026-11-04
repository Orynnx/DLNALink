package com.orynnx.dlnalink

import java.net.URI

data class BridgePairing(val address: String, val token: String)

/** Only our versioned LAN pairing format; never fetch arbitrary scanned URLs. */
fun parseBridgePairing(value: String): BridgePairing? = try {
    require(value.length <= 2048)
    val uri = URI(value)
    require(uri.scheme == "dlnalink" && uri.host == "pair" && uri.rawPath.isNullOrEmpty() && uri.rawFragment == null && uri.userInfo == null)
    val fields = uri.rawQuery.split('&').map { it.split('=', limit = 2).also { pair -> require(pair.size == 2) } }
    require(fields.size == 4 && fields.map { it[0] }.toSet() == setOf("v", "host", "port", "key"))
    val data = fields.associate { it[0] to it[1] }
    require(data["v"] == "1" && data["port"] == "46901")
    val host = data.getValue("host")
    val parts = host.split('.').map { it.toInt() }
    require(parts.size == 4 && parts.all { it in 0..255 } && host == parts.joinToString("."))
    require(parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) || (parts[0] == 192 && parts[1] == 168))
    val key = data.getValue("key")
    require(Regex("[0-9a-f]{64}").matches(key))
    BridgePairing(host, key)
} catch (_: Exception) { null }
