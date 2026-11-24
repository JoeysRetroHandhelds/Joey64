package com.joeysretro.joey64.reports

import android.content.Context
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.update.AppUpdates
import java.io.File
import java.io.Writer
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.GZIPOutputStream

/**
 * Whole session logs for reports (Joey 2026-11-24, option 2: "reports should carry everything the log does"; the excerpt
 * in the report starts mid-game, so the device, firmware, display and options lines never came). A session that sends
 * a log excerpt also gets a log id: its process's app log and system log, cleaned as the excerpt is (paths, player names,
 * public addresses out), gzipped into files/reports/logs/<id>.wait.gz. Once the server takes the report, the file
 * becomes <id>.gz and is posted to POST /v1/log?id=<id> (server/reports/src/logs.js); a report the server dropped drops
 * its logs too. Files left more than [KEEP_DAYS] days go.
 */
object SessionLogs {
    private const val TAG = "Joey64Reports"
    /** The most of a log sent, raw: past it the start ([HEAD_KEEP]) and the end are kept. */
    internal const val MAX_RAW = 32L shl 20
    internal const val HEAD_KEEP = 4L shl 20
    /** Logs posted in one go (the server's per-address limit is 10 posts a minute, with the report's own). */
    private const val MAX_UPLOADS = 6
    private const val KEEP_DAYS = 8
    private const val WAIT = ".wait.gz"

    fun dir(ctx: Context) = File(ReportFiles.dir(ctx), "logs").apply { mkdirs() }

    /** A new id: 32 lower-case hex characters (the server's logId). */
    internal fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    /**
     * Process [pid]'s logs, cleaned of [names] and gzipped, waiting for their report; null when the app log is gone.
     */
    fun prepare(ctx: Context, pid: Int, names: List<String>): String? {
        val logs = com.joeysretro.joey64.LogFiles.dir(ctx)
        val app = logs.listFiles { f -> f.name.endsWith("-$pid-app.log") }?.maxByOrNull { it.lastModified() } ?: return null
        val sys = logs.listFiles { f -> f.name.endsWith("-$pid-system.log") }?.maxByOrNull { it.lastModified() }
        val id = newId()
        val part = File(dir(ctx), "$id.part")
        return try {
            GZIPOutputStream(part.outputStream().buffered(), 64 * 1024).bufferedWriter().use { w ->
                writeCleaned(app, w, names)
                if (sys != null) { w.write("\n===== system log (this process) =====\n"); writeCleaned(sys, w, names) }
            }
            if (!part.renameTo(File(dir(ctx), id + WAIT))) { part.delete(); return null }
            id
        } catch (e: Exception) { part.delete(); JLog.w(TAG, "reports: whole log not kept ($e)"); null }
    }

    /** [f] cleaned a block of lines at a time; over [MAX_RAW] the start and the end, with a line saying what's left out. */
    private fun writeCleaned(f: File, w: Writer, names: List<String>) {
        val size = f.length()
        f.bufferedReader().use { r ->
            var read = 0L
            var skipped = false
            val block = StringBuilder()
            fun flush() { if (block.isNotEmpty()) { w.write(cleanBlock(block.toString(), names)); block.setLength(0) } }
            while (true) {
                if (!skipped && size > MAX_RAW && read >= HEAD_KEEP) {
                    flush()
                    val skip = size - MAX_RAW
                    var left = skip
                    while (left > 0) { val n = r.skip(left); if (n <= 0) break; left -= n }
                    r.readLine() // the rest of a cut line
                    w.write("\n... ${skip shr 20} MB of the log left out here ...\n")
                    skipped = true
                }
                val line = r.readLine() ?: break
                read += line.length + 1
                block.append(line).append('\n')
                if (block.length >= 256 * 1024) flush()
            }
            flush()
        }
    }

    /** As the excerpt: control characters out, then paths, player names and public addresses. */
    internal fun cleanBlock(s: String, names: List<String>): String =
        ReportSender.cleanText(s.replace("\r", "").replace(Regex("[\\u0000-\\u0008\\u000b-\\u001f\\u007f]"), " "), names)

    /** The report holding [id]'s session was taken (true) or dropped (false). */
    fun reportDone(ctx: Context, id: String, taken: Boolean) {
        val f = File(dir(ctx), id + WAIT)
        if (!f.exists()) return
        if (!taken || !f.renameTo(File(dir(ctx), "$id.gz"))) f.delete()
    }

    /** Posts the logs whose reports were taken, oldest first; stops at the first that can't go now. Old files go. */
    fun uploadReady(ctx: Context, base: String) {
        val all = dir(ctx).listFiles().orEmpty()
        val old = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        all.filter { it.lastModified() < old }.forEach { it.delete() }
        val ready = all.filter { it.exists() && it.name.endsWith(".gz") && !it.name.endsWith(WAIT) }.sortedBy { it.lastModified() }
        for (f in ready.take(MAX_UPLOADS)) {
            val id = f.name.removeSuffix(".gz")
            val code = post(ctx, base, id, f)
            JLog.i(TAG, "reports: whole log ${f.length() / 1024} KB: $code")
            when (code) {
                204, 400, 404, 409, 410, 413 -> f.delete()
                else -> return
            }
        }
    }

    private fun post(ctx: Context, base: String, id: String, f: File): Int {
        val c = URL("$base/v1/log?id=$id").openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.doOutput = true
            c.setFixedLengthStreamingMode(f.length())
            c.setRequestProperty("Content-Type", "application/gzip")
            c.setRequestProperty("User-Agent", "Joey64/${AppUpdates.installedVersion(ctx)}")
            c.outputStream.use { o -> f.inputStream().use { it.copyTo(o) } }
            c.responseCode
        } catch (e: java.io.IOException) {
            JLog.i(TAG, "reports: whole log not sent ($e), trying again later")
            -1
        } finally { c.disconnect() }
    }
}
