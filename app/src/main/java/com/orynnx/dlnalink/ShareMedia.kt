package com.orynnx.dlnalink

import java.net.URI

/** Extract direct playable links, never treating an ordinary shared page as media. */
fun extractSharedMediaLinks(text: String, mime: String? = null): List<String> {
    val type = mime?.substringBefore(';')?.trim()?.lowercase()
    val explicit = type?.let { it.startsWith("video/") || it.startsWith("audio/") ||
        it in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "application/dash+xml") } == true
    val extensions = setOf("m3u8", "mpd", "mp4", "mkv", "webm", "mov", "m4v", "ts", "mp3", "aac", "wav", "ogg", "flac", "m4a")
    return Regex("https?://[^\\s<>\\\"]+", RegexOption.IGNORE_CASE).findAll(text).mapNotNull { match ->
        // Query punctuation can be part of a signature; never rewrite it.
        val link = if ('?' in match.value) match.value else match.value.trimEnd('.', ',', ';', '!', ')', ']', '}')
        try {
            val uri = URI(link)
            if (uri.scheme.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) null
            else if (explicit || uri.path.orEmpty().substringAfterLast('.').lowercase() in extensions) link else null
        } catch (_: Exception) { null }
    }.distinct().toList()
}
