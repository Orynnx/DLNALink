package com.orynnx.dlnalink

import java.io.File
import java.io.InputStream

internal const val SESSION_LIMIT = 2L * 1024 * 1024 * 1024
internal const val DISK_RESERVE = 128L * 1024 * 1024

internal fun cacheFits(used: Long, incoming: Long, available: Long): Boolean =
    incoming >= 0 && used in 0..SESSION_LIMIT && incoming <= SESSION_LIMIT - used &&
        available >= DISK_RESERVE && incoming <= available - DISK_RESERVE

/** Owns only UUID-named copies below one private session directory. */
internal class SessionCache(val directory: File) {
    init { check(directory.isDirectory || directory.mkdirs()) { "Cannot create session cache" } }
    fun file(id: String): File {
        require(Regex("[0-9a-f-]{36}").matches(id))
        return File(directory, id)
    }
    fun bytes(): Long = directory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0
    fun copy(id: String, input: InputStream, checkActive: () -> Unit): File {
        val temporary = File(directory, "$id.part")
        val destination = file(id)
        val used = directory.parentFile!!.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        var copied = 0L
        try {
            input.use { source -> temporary.outputStream().use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    checkActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    check(cacheFits(used + copied, count.toLong(), directory.usableSpace)) {
                        "Session cache limit or free-space reserve reached"
                    }
                    output.write(buffer, 0, count)
                    copied += count
                }
            } }
            checkActive()
            check(temporary.renameTo(destination)) { "Cannot finalize media copy" }
            return destination
        } finally { temporary.delete() }
    }
}
