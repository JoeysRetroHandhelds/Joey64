package com.joeysretro.joey64

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * joey64-data's files the app keeps (the cheat index, Joey64's covers' index, the wanted states list, the drivers'
 * ranking) are looked at again whenever the kept copy is [RECHECK_MS] old, so a change on the `release` branch reaches
 * players the next time they open Joey64 (Joey 2026-11-16, option 1: "we force it"; it was once a week). Each look
 * asks only whether the file changed: the kept copy's ETag (or Last-Modified, from a feed without ETags) goes with it,
 * and an unchanged file is a 304 with no body; the kept copy's time is then moved on, so the next look waits again.
 * The tag sits beside the kept file as `<name>.tag`; a kept file without one (from before) is fetched once whole.
 */
object DataFeed {
    /** How old a kept copy is before the next look: one look per opening of the launcher, not one per screen. */
    const val RECHECK_MS = 15 * 60_000L

    sealed interface Got {
        /** The file changed (or there was no kept copy): its bytes, to keep, then [keep]. */
        class New(val bytes: ByteArray, internal val tag: String?) : Got
        /** The kept copy is still the file; its time was moved on. */
        object Same : Got
    }

    /** Whether [kept] is due a look ([now] in ms). A kept file dated in the future (a clock moved back) is due. */
    fun due(kept: File, now: Long = System.currentTimeMillis()): Boolean {
        if (!kept.exists()) return true
        val age = now - kept.lastModified()
        return age < 0 || age >= RECHECK_MS
    }

    /** The validator header to send for a kept tag: ETag as If-None-Match, "lm:<date>" as If-Modified-Since. */
    fun condition(tag: String?): Pair<String, String>? {
        val t = tag?.trim().orEmpty()
        if (t.isEmpty()) return null
        return if (t.startsWith("lm:")) "If-Modified-Since" to t.removePrefix("lm:") else "If-None-Match" to t
    }

    /** What to keep as the tag of a reply: its ETag, else its Last-Modified as "lm:<date>", else none. */
    fun tagOf(etag: String?, lastModified: String?): String? =
        etag?.trim()?.takeIf { it.isNotEmpty() } ?: lastModified?.trim()?.takeIf { it.isNotEmpty() }?.let { "lm:$it" }

    fun tagFile(kept: File) = File(kept.parentFile, kept.name + ".tag")

    /**
     * [url], or Same when [kept] (with its tag) is still it. 404 throws FileNotFoundException, other failures IOException.
     * Network: call off the main thread.
     */
    fun get(url: String, kept: File?, max: Long, what: String): Got {
        val tag = kept?.takeIf { it.isFile }?.let { k -> tagFile(k).takeIf { it.isFile }?.let { t -> try { t.readText() } catch (e: IOException) { null } } }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.useCaches = false
        c.setRequestProperty("User-Agent", "Joey64/${BuildConfig.VERSION_NAME}")
        condition(tag)?.let { (k, v) -> c.setRequestProperty(k, v) }
        try {
            val code = c.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED && kept != null && kept.isFile) {
                kept.setLastModified(System.currentTimeMillis())
                return Got.Same
            }
            if (code == 404) throw FileNotFoundException(url)
            if (code != 200) throw IOException("HTTP $code from $url")
            val bytes = c.inputStream.use { Capped.bytes(it, max, what) }
            return Got.New(bytes, tagOf(c.getHeaderField("ETag"), c.getHeaderField("Last-Modified")))
        } finally {
            c.disconnect()
        }
    }

    /** After [kept] was written from [got]: its tag beside it (or none, so the next look fetches it whole). */
    fun keep(kept: File, got: Got.New) {
        val t = tagFile(kept)
        try { if (got.tag != null) t.writeText(got.tag) else t.delete() } catch (e: IOException) { t.delete() }
    }

    /** [kept] is gone or replaced by something not from the feed (a 404's empty stand-in): its tag goes too. */
    fun forget(kept: File) { tagFile(kept).delete() }
}
