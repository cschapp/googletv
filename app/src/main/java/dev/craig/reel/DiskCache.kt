package dev.craig.reel

import java.io.File
import java.security.MessageDigest

/**
 * Tiny on-disk cache of HTTP response bodies, keyed by request. One file per entry under the
 * app's cache dir; the file's modification time is the entry's age. Android may clear the
 * directory under storage pressure, which is fine: everything here can be fetched again.
 */
class DiskCache(parent: File) {

    private val dir = File(parent, "http").apply { mkdirs() }

    class Entry(val body: String, val ageMs: Long)

    fun get(key: String): Entry? {
        val f = file(key)
        if (!f.isFile) return null
        return try {
            Entry(f.readText(), System.currentTimeMillis() - f.lastModified())
        } catch (e: Exception) {
            null
        }
    }

    fun put(key: String, body: String) {
        try {
            val f = file(key)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
            // Caching is best effort; a full disk must not break the request.
        }
    }

    fun remove(key: String) {
        file(key).delete()
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Delete entries older than [maxAgeMs]; call off the main thread. */
    fun prune(maxAgeMs: Long) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    /** Rough size, for the settings label. */
    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun file(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
