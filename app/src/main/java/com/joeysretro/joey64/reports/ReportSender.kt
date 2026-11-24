package com.joeysretro.joey64.reports

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.data.LibraryDb
import com.joeysretro.joey64.launcher.update.AppUpdates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Sends what's waiting, at most once a day (UTC), when the library comes to the front and Send reports
 * is on. Each report is one device part and its events: sessions are grouped by the GPU and driver
 * they ran on (Settings can change the driver), covers and crashes go with the first. Anything the
 * server didn't take for a reason that may pass (offline, busy, 429) waits for the next time the library
 * opens. Only a report the server took counts as the day's: nothing to send doesn't use it up.
 */
object ReportSender {
    private const val TAG = "Joey64Reports"
    const val SERVER = "https://joey64-reports.joeysretrohandhelds.workers.dev"
    /**
     * Reports a day the server takes from one install (server/reports/src/store.js, PER_INSTALL_DAY): one send may post
     * this many, more than one for a GPU and driver when its sessions don't fit in one (Joey 2026-11-18, "Yes, build
     * both": one report a day held about 3 sessions with their logs).
     */
    internal const val MAX_REPORTS = 6
    private const val MAX_COVERS = 150

    private val sending = java.util.concurrent.atomic.AtomicBoolean(false)

    fun today(): String = LocalDate.now(ZoneOffset.UTC).toString()

    /** What Send now says after a press (approved feedback1 screen 7). */
    /** DayLimit: the server took its day's share (429 "enough for today", "full for today", "enough from this address
     *  today"); the rest wait for the next UTC day. Send now said "no internet" for it (Thor 2026-11-17, report 57). */
    /** SlowDown: the server's per-address limit (429 "slow down", 10 posts a minute, wrangler.toml PER_IP). */
    enum class Outcome { Sent, NothingToSend, Failed, DayLimit, SlowDown }

    /** A 429 body that means a limit for the rest of the server's day (src/index.js), not "slow down". */
    internal fun isDayLimit(code: Int, body: String?): Boolean =
        code == 429 && body != null && body.trim().let { it.endsWith("today") }

    /** A 429 for posting too often from one address: it passes within a minute. */
    internal fun isSlowDown(code: Int, body: String?): Boolean = code == 429 && body?.trim() == "slow down"

    /** [force]: send now even if a report went today (Send reports › Send now, and debug builds). */
    suspend fun maybeSend(ctx: Context, force: Boolean = false): Outcome? = withContext(Dispatchers.IO) {
        // Send reports off with a one-off asked for (ReportPrefs.once): that play's sessions go at once, nothing else,
        // and what's kept is cleared after, as Off does.
        val once = !ReportPrefs.on(ctx) && ReportPrefs.once(ctx)
        if (!ReportPrefs.on(ctx) && !once) return@withContext null
        if (!force && !once && ReportPrefs.lastSentDay(ctx) == today()) {
            // The day's report went: whole logs that couldn't go then still go now.
            if (sending.compareAndSet(false, true)) try { SessionLogs.uploadReady(ctx, base(ctx)) } catch (e: Exception) { JLog.w(TAG, "reports: $e") } finally { sending.set(false) }
            return@withContext null
        }
        if (!sending.compareAndSet(false, true)) return@withContext null
        try {
            send(ctx, once).also { if (once && it == Outcome.Sent) { ReportPrefs.setOnce(ctx, false); ReportFiles.clear(ctx) } }
        } catch (e: Exception) { JLog.w(TAG, "reports: $e"); Outcome.Failed } finally { sending.set(false) }
    }

    /** The GPU as the system's Vulkan driver names it, for a report sent before any game has run here: the game screen
     *  writes report_gpu, so a first report (covers only) went with none (report 24, an Odin 2, 2026-11-14). No driver
     *  has run yet, so the driver stays empty. The game screen probes the same way in this process at every start. */
    private fun systemGpu(): String = try {
        if (com.joeysretro.joey64.MainActivity.gameRunning) "" else
            com.joeysretro.joey64.NativeBridge.probeVulkan(false).split('|', limit = 6).getOrNull(4).orEmpty().replace("(TM) ", "")
    } catch (e: Throwable) { JLog.w(TAG, "reports: no GPU name ($e)"); "" }

    private fun send(ctx: Context, once: Boolean = false): Outcome {
        var anyEvents = false
        var failed = false
        var dayLimit = false
        var slowDown = false
        val exits = exits(ctx)
        sweepOpen(ctx, exits)
        val pending = ReportFiles.pending(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        val sessions = pending.mapNotNull { f -> try { f to JSONObject(f.readText()) } catch (e: Exception) { f.delete(); null } }
        // A session that ended badly, played in a netplay room or had dips takes its process's log excerpt with it (Joey
        // 2026-11-14, dips 2026-11-15), and in a testing build every session does (Joey 2026-11-12 night: "can it be any
        // session, at least for the beginning?"); read now while LogFiles still has it ("<time>-<pid>.json" names the process).
        val testing = com.joeysretro.joey64.launcher.update.Releases.isTesting(com.joeysretro.joey64.BuildConfig.VERSION_NAME)
        for ((f, o) in sessions) if (!o.has("log") && ReportJson.wantsLog(o, testing)) {
            val pid = f.nameWithoutExtension.substringAfter('-').toIntOrNull() ?: continue
            val names = o.optJSONArray("maskNames")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            logExcerpt(ctx, pid, names)?.let { o.put("log", it); try { ReportFiles.write(f, o.toString()) } catch (e: Exception) { } }
        }
        // And the whole log (Joey 2026-11-24): the same sessions get an id and their process's logs, cleaned and gzipped,
        // posted after the report is taken. One id a process: its sessions share the log.
        val logIds = HashMap<Int, String?>()
        for ((f, o) in sessions) if (!o.has("logId") && ReportJson.wantsLog(o, testing)) {
            val pid = f.nameWithoutExtension.substringAfter('-').toIntOrNull() ?: continue
            val names = o.optJSONArray("maskNames")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val id = logIds.getOrPut(pid) { SessionLogs.prepare(ctx, pid, names) } ?: continue
            o.put("logId", id)
            try { ReportFiles.write(f, o.toString()) } catch (e: Exception) { }
        }
        val db = LibraryDb.get(ctx)
        val headers = db.headersOf(sessions.mapNotNull { ReportJson.md5(it.second.optString("md5")) }.toSet())

        val sentCovers = ReportFiles.coversSent(ctx).takeIf { it.exists() }?.readLines()?.toHashSet() ?: hashSetOf()
        val covers = db.coverReports().map { it to "${it.md5} ${it.kind} ${it.cover ?: ""}" }.filter { it.second !in sentCovers }.take(MAX_COVERS)
        val coverEvents = covers.mapNotNull { (c, key) -> ReportJson.cover(c.md5, c.kind, c.header, c.title, c.cover)?.let { it to key } }

        // Oldest first: when the report is too big for all of them, the newest wait (and stay after exitsAfter).
        val crashes = exits.filter { it.crash && it.timestamp > ReportPrefs.exitsAfter(ctx) }.sortedBy { it.timestamp }
            .map { it to ReportJson.crash(it.md5, it.where, it.summary) }

        // Sessions by (gpu, driver), newest group first; covers and crashes ride with the first report.
        val groups = sessions.groupBy { it.second.optString("gpu") to it.second.optString("driver") }
            .entries.sortedByDescending { e -> e.value.maxOf { it.first.name } }
        val devicePrefs = ctx.getSharedPreferences("device", Context.MODE_PRIVATE)
        val plan = mutableListOf<Pair<Pair<String, String>, List<Pair<File, JSONObject>>>>()
        if (groups.isEmpty()) plan += (devicePrefs.getString("report_gpu", "").orEmpty().ifEmpty { systemGpu() } to devicePrefs.getString("report_driver", "").orEmpty()) to emptyList()
        else for (g in groups) plan += g.key to g.value

        val app = AppUpdates.installedVersion(ctx)
        val core = core(ctx)
        val install = ReportPrefs.install(ctx)
        var anySent = false
        var maxExit = 0L
        // Each report: one GPU and driver's sessions. What doesn't fit goes in the next report straight after (same GPU and
        // driver), up to the server's [MAX_REPORTS] a day; the rest wait for the next day.
        val queue = ArrayDeque(plan)
        var posted = 0
        // Covers go after the sessions; those that don't fit ride in the next report with room, or one of their own.
        var coversLeft = if (once) emptyList() else coverEvents
        while ((queue.isNotEmpty() || coversLeft.isNotEmpty()) && posted < MAX_REPORTS) {
            val i = posted
            val (gpuDriver, group) = queue.removeFirstOrNull() ?: (plan.first().first to emptyList())
            val crashesHere = if (i == 0 && !once) crashes else emptyList()
            val coversHere = coversLeft
            val sessionEvents = group.mapNotNull { (f, o) -> ReportJson.session(o, headers[o.optString("md5")])?.let { f to it } }
                .take(ReportJson.MAX_EVENTS - crashesHere.size - coversHere.size)
            // Crashes, then sessions oldest first, then covers: what doesn't fit waits for the next report, from the end.
            val events = crashesHere.map { it.second } + sessionEvents.map { it.second } + coversHere.map { it.first }
            if (events.isEmpty()) continue
            anyEvents = true
            val screen = mainScreen(ctx)
            val device = ReportJson.Device(Build.MANUFACTURER, Build.MODEL, if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null,
                gpuDriver.first.ifEmpty { null }, gpuDriver.second.ifEmpty { null }, Build.VERSION.SDK_INT,
                screen?.first, screen?.second, screen?.third)
            val keep = fitting(ReportJson.report(install, app, core, device, emptyList()).toString(), events)
            val sentCrashes = crashesHere.take(keep)
            val sentSessions = sessionEvents.take((keep - crashesHere.size).coerceAtLeast(0))
            val sentCovers = coversHere.take((keep - crashesHere.size - sessionEvents.size).coerceAtLeast(0))
            val body = ReportJson.report(install, app, core, device, events.take(keep)).toString()
            val code = post(ctx, body)
            posted++
            JLog.i(TAG, "reports: sent ${sentSessions.size} sessions, ${sentCovers.size} covers" + (if (i == 0) ", ${sentCrashes.size} crashes" else "") +
                (if (keep < events.size) " (${events.size - keep} more wait: over ${MAX_BODY / 1024} KB)" else "") + ": $code")
            if (code == 204 || code == 400 || code == 413) {
                // 400: the server can't read it, and it won't later either: dropped, not sent every day. 413 (too big even
                // after the cut above) the same, so one report can't hold up the ones after it for good (code audit 3, M22).
                if (code != 204) JLog.e(TAG, "reports: the server refused the report ($code): ${lastError ?: ""}")
                sentSessions.forEach { it.first.delete() }
                for ((_, ev) in sentSessions) ev.optString("logId").takeIf { it.isNotEmpty() }?.let { SessionLogs.reportDone(ctx, it, code == 204) }
                ReportFiles.coversSent(ctx).appendText(sentCovers.joinToString("") { it.second + "\n" })
                coversLeft = coversLeft.drop(sentCovers.size)
                // A report that took no covers though some are left (one too big alone): they wait, or this loops.
                if (sentCovers.isEmpty() && sentSessions.isEmpty()) coversLeft = emptyList()
                if (i == 0) {
                    // Every crash went: past every exit so far. Some wait: past the newest that went, so the rest go next.
                    maxExit = if (sentCrashes.size == crashesHere.size) exits.maxOfOrNull { it.timestamp } ?: 0L
                    else sentCrashes.lastOrNull()?.first?.timestamp ?: 0L
                }
                anySent = anySent || code == 204
                // This GPU and driver's sessions that didn't fit: the next report, now.
                val sentFiles = sentSessions.map { it.first }.toSet()
                val left = group.filter { it.first !in sentFiles && it.first.exists() }
                if (code == 204 && sentSessions.isNotEmpty() && left.isNotEmpty()) queue.addFirst(gpuDriver to left)
            } else {
                failed = true
                if (isDayLimit(code, lastError)) { dayLimit = true; JLog.i(TAG, "reports: the server's limit for today (${lastError?.trim()}): the rest go tomorrow") }
                else if (isSlowDown(code, lastError)) { slowDown = true; JLog.i(TAG, "reports: sending too often from this address: try again in a minute") }
                break
            }
        }
        if (maxExit > 0) ReportPrefs.setExitsAfter(ctx, maxOf(maxExit, ReportPrefs.exitsAfter(ctx).takeIf { it != Long.MAX_VALUE } ?: 0L))
        // Only a report the server took uses up the day. A check with nothing to send marked it too, so a game finished
        // later that day waited for the next one: a tester's first report never came the day they played (2026-11-11).
        if (anySent) ReportPrefs.setLastSentDay(ctx, today())
        if (!dayLimit) try { SessionLogs.uploadReady(ctx, base(ctx)) } catch (e: Exception) { JLog.w(TAG, "reports: whole logs: $e") }
        return when { dayLimit -> Outcome.DayLimit; slowDown -> Outcome.SlowDown; failed -> Outcome.Failed; anySent -> Outcome.Sent; !anyEvents -> Outcome.NothingToSend; else -> Outcome.Sent }
    }

    /** The server takes at most 64 KB a report (server/reports/src/validate.js, MAX_BYTES); a report stays under this. */
    const val MAX_BODY = 60 * 1024

    /**
     * How many of [events], from the start, fit in one report whose body without events is [empty]. At least one, so a
     * single event too big even alone goes once and is dropped (413) instead of holding the queue (code audit 3, M22:
     * a report over 64 KB got 413 every day, and nothing from that install was sent again).
     */
    internal fun fitting(empty: String, events: List<JSONObject>, max: Int = MAX_BODY): Int {
        var total = empty.toByteArray().size
        for ((n, e) in events.withIndex()) {
            total += e.toString().toByteArray().size + 1 // and the comma before the next
            if (total > max) return maxOf(n, 1)
        }
        return events.size
    }

    private var lastError: String? = null

    /** The base address: the server, or in debug builds files/dev_reports_base (a local wrangler dev through adb reverse). */
    internal fun base(ctx: Context): String {
        if (com.joeysretro.joey64.BuildConfig.DEBUG) File(ctx.filesDir, "dev_reports_base").takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.trimEnd('/') }
        return SERVER
    }

    private fun post(ctx: Context, body: String): Int {
        lastError = null
        val c = URL(base(ctx) + "/v1/report").openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000; c.readTimeout = 15_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("User-Agent", "Joey64/${AppUpdates.installedVersion(ctx)}")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            if (code >= 400) lastError = c.errorStream?.bufferedReader()?.use { it.readText().take(200) }
            code
        } catch (e: java.io.IOException) {
            JLog.i(TAG, "reports: not sent ($e), trying again when the library next opens")
            -1
        } finally { c.disconnect() }
    }

    /** The bundled core as About shows it: "dd 0ac9d17". */
    private fun core(ctx: Context): String? {
        val lines = try { ctx.assets.open("core-version.txt").bufferedReader().readLines() } catch (e: Exception) { return null }
        fun field(k: String) = lines.firstOrNull { it.startsWith("$k:") }?.substringAfter(':')?.trim().orEmpty()
        return listOf(field("branch"), field("commit").substringBefore(' ').take(7)).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
    }

    /** One of our processes' ends as Android recorded it, and the session it was playing (by pid). */
    private data class Exit(val pid: Int, val timestamp: Long, val crash: Boolean, var where: String, val summary: String, var md5: String? = null,
                            val ended: String = "gone")

    private fun exits(ctx: Context): List<Exit> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val list = try { ctx.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(null, 0, 16) } catch (e: Exception) { return emptyList() }
        return list.map { x ->
            val crash = x.reason == ApplicationExitInfo.REASON_CRASH || x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || x.reason == ApplicationExitInfo.REASON_ANR
            Exit(x.pid, x.timestamp, crash, if (x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) "core" else "launcher", if (crash) summary(ctx, x) else "",
                ended = endedOf(x.reason))
        }
    }

    /** A session's ending (ReportJson.ENDED) from Android's reason its process ended. */
    internal fun endedOf(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_PERMISSION_CHANGE,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED, 14 /* REASON_FREEZER, Android 14 */ -> "killed"
        ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> "closed"
        ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_EXIT_SELF -> "signal"
        else -> "gone"
    }

    /** Lines of a log excerpt: the last ones, and before them the room's own lines from earlier in the process. */
    private const val LOG_TAIL = 400
    private const val LOG_NET = 150
    /** Earlier dip lines kept (slow frames, with their stats and clocks lines: 20 dips' worth). */
    private const val LOG_DIPS = 60
    /** The most the earlier lines take of the excerpt's [ReportJson.MAX_RAW_LOG] characters; the tail gets the rest. */
    private const val HEAD_MAX = 12_000

    /**
     * The excerpt of process [pid]'s session log (LogFiles' "-<pid>-app.log") for a problem or netplay session: its last
     * [LOG_TAIL] lines, after up to [LOG_NET] earlier netplay lines and [LOG_DIPS] earlier dip lines. Paths, public addresses and the room's player names
     * ([names]) are taken out; null when the log is gone.
     */
    private fun logExcerpt(ctx: Context, pid: Int, names: List<String>): String? {
        val f = com.joeysretro.joey64.LogFiles.dir(ctx).listFiles { x -> x.name.endsWith("-$pid-app.log") }?.maxByOrNull { it.lastModified() } ?: return null
        val lines = try { f.readLines() } catch (e: Exception) { return null }
        return excerpt(lines, names)
    }

    /** [logExcerpt] without the file: pure, unit tested. */
    internal fun excerpt(lines: List<String>, names: List<String>): String {
        val tailFrom = maxOf(0, lines.size - LOG_TAIL)
        val early = lines.subList(0, tailFrom)
        // Before the tail: the room's lines, and each earlier dip (its "slow frames:" line with the stats line before it
        // and the clocks line after it: where the time went, and the clocks then).
        val keep = sortedSetOf<Int>()
        early.indices.filter { early[it].contains("netplay", true) || early[it].contains("playtogether", true) }.takeLast(LOG_NET).forEach { keep += it }
        val dips = sortedSetOf<Int>()
        for (i in early.indices) if (early[i].contains("slow frames:")) {
            dips += i
            ((i - 1) downTo maxOf(0, i - 4)).firstOrNull { early[it].contains("stats:") }?.let { dips += it }
            if (i + 1 < early.size && early[i + 1].contains("clocks:")) dips += i + 1
        }
        dips.toList().takeLast(LOG_DIPS).forEach { keep += it }
        val head = clean(keep.map { early[it] }.joinToString("\n"), names).let { h ->
            if (h.length <= HEAD_MAX) h else h.substring(h.indexOf('\n', h.length - HEAD_MAX).let { if (it < 0) h.length - HEAD_MAX else it + 1 })
        }
        // The tail gets what the head leaves; cut at a line start, so the earlier lines are never the part that's lost.
        val room = ReportJson.MAX_RAW_LOG - (if (head.isEmpty()) 0 else head.length + 5)
        val tail = clean(lines.subList(tailFrom, lines.size).joinToString("\n"), names).let { t ->
            if (t.length <= room) t else t.substring(t.indexOf('\n', t.length - room).let { if (it < 0) t.length - room else it + 1 })
        }
        return ReportJson.logText(if (head.isEmpty()) tail else "$head\n...\n$tail")
    }

    private fun clean(s: String, names: List<String>): String = cleanText(s, names)

    /** Names (whole ones only), paths and public addresses out of a part of an excerpt or a whole log (SessionLogs). */
    internal fun cleanText(s: String, names: List<String>): String {
        var text = s
        // Whole names only (review 2026-11-15: a plain replace made "Joey64" "<player>64" for a player called Joey).
        for (n in names.map { it.trim() }.filter { it.length >= 2 }.sortedByDescending { it.length })
            text = text.replace(Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(n) + "(?![\\p{L}\\p{N}_])"), "<player>")
        return com.joeysretro.joey64.LogMask.addresses(scrub(text))
    }

    private fun summary(ctx: Context, x: ApplicationExitInfo): String {
        val head = when (x.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash, signal ${x.status}"
            ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
            else -> "crash (Java)"
        } + (x.description?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        val detail = when (x.reason) {
            // The ANR trace is text: the main thread's stack is near the top.
            ApplicationExitInfo.REASON_ANR -> try { x.traceInputStream?.bufferedReader()?.useLines { it.take(40).joinToString("\n") } } catch (e: Exception) { null }
            // A Java crash has no trace in the exit record: LogFiles kept the stack when it happened.
            ApplicationExitInfo.REASON_CRASH -> File(ReportFiles.dir(ctx), "java-crash-${x.pid}.txt").takeIf { it.exists() }?.let { f -> f.readText().also { f.delete() } }
            // A native crash's tombstone (Android 12+): the signal and the crashing thread's backtrace.
            ApplicationExitInfo.REASON_CRASH_NATIVE -> try {
                x.traceInputStream?.use { s -> s.readNBytesCompat(8 shl 20) }?.let { Tombstone.summary(it) }
            } catch (e: Exception) { null }
            else -> null
        }
        // A native crash's tombstone starts with the signal itself: "native crash" is enough above it.
        val top = if (x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && detail != null)
            "native crash" + (x.description?.takeIf { it.isNotBlank() && it != "crash" }?.let { ": $it" } ?: "") else head
        return scrub(top + (detail?.let { "\n" + it } ?: ""))
    }

    /** At most [max] bytes of the stream (InputStream.readNBytes is Android 13+). */
    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Paths and content addresses out of crash text (a file name in an exception's message can name the user's folders).
     * A path runs to the end of its line, a quote or ": " (folder names have spaces: "/storage/.../John Smith/Zelda.z64"
     * left " Smith/Zelda.z64" behind), and SAF document ids ("primary:ROMs/...", an SD card's "1A2B-3C4D:...") go too
     * (code audit 3, p7 L3). Content URIs have no spaces (they're percent-encoded).
     */
    fun scrub(s: String): String = s
        .replace(Regex("""content://\S+"""), "<path>")
        .replace(Regex("""file://$PATH_REST"""), "<path>")
        .replace(Regex("""/(storage|sdcard|mnt|data/user|data/data)/$PATH_REST"""), "<path>")
        .replace(Regex("""\b(primary|[0-9A-F]{4}-[0-9A-F]{4}):[^\s"']$PATH_REST"""), "<path>")

    /** The rest of a path: up to the end of its line, a quote or ": ". */
    private const val PATH_REST = """(?:(?!: )[^\n"'])*"""

    /**
     * Sessions left in open/ by a process that is gone: they didn't end with a quit. Each is sent ended as Android
     * recorded its process's end ([endedOf]: crash, anr, killed, closed, signal; gone with no record), and a crash's
     * event gets the game's MD5.
     */
    private fun sweepOpen(ctx: Context, exits: List<Exit>) {
        val alive = try {
            ctx.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty().map { it.pid }.toSet()
        } catch (e: Exception) { setOf(android.os.Process.myPid()) }
        for (f in ReportFiles.open(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty()) {
            val pid = f.nameWithoutExtension.toIntOrNull() ?: run { f.delete(); continue }
            if (pid in alive) continue
            val o = try { JSONObject(f.readText()) } catch (e: Exception) { f.delete(); continue }
            val exit = exits.firstOrNull { it.pid == pid }
            if (exit?.crash == true) {
                exit.md5 = o.optString("md5")
                // A Java crash or ANR while playing: the game screen's side, not the library's.
                if (exit.where == "launcher") exit.where = "host"
            }
            // Why the game screen never closed, as Android recorded it (it was "crash" or "quit" before 2026-11-14).
            o.put("ended", exit?.ended ?: "gone")
            if (SessionTracker.keep(o.optLong("seconds"), o.optLong("frames", Long.MAX_VALUE))) ReportFiles.write(File(ReportFiles.pending(ctx), "${f.lastModified()}-$pid.json"), o.toString())
            f.delete()
        }
    }

    /** LogFiles' uncaught-exception handler: the stack, kept for the next report when Send reports is on. */
    fun keepJavaCrash(ctx: Context, stack: String) {
        if (!ReportPrefs.on(ctx)) return
        try { ReportFiles.write(File(ReportFiles.dir(ctx), "java-crash-${android.os.Process.myPid()}.txt"), stack.take(2000)) } catch (e: Exception) { }
    }

    /**
     * The main screen as layouts see it: its size in pixels, long side first, and its density in dpi
     * (the Thor's main screen, not the second one). Null when Android won't say.
     */
    @Suppress("DEPRECATION")
    private fun mainScreen(ctx: Context): Triple<Int, Int, Int>? = try {
        val dm = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)
        val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val m = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
        Triple(maxOf(m.widthPixels, m.heightPixels), minOf(m.widthPixels, m.heightPixels), m.densityDpi)
    } catch (e: RuntimeException) { null }
}
