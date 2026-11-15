package com.joeysretro.joey64

/**
 * Auto looks for a faster driver (approved drvspeed mockup 2026-11-15, scratchpad build_drvspeed.py, "Approve as drawn";
 * Joey: "We are picking first driver that loads but what if another driver is faster?"). Works on [GpuTrial]'s chain
 * levels once the trial has a good one, per install and system GPU like the trial:
 *  - a game that ran below full speed, or with its frames' work over 90% of the frame, for [PLAY_MS] of play on the
 *    driver in use gets the next untried level of the chain on its next start ([judge] gives [Verdict.Next]; [start]
 *    gives [Start.Run], pop-up 1);
 *  - after [PLAY_MS] of that game on it, the two drivers' mean work a frame on that game is compared: [FASTER] less
 *    keeps it for the device ([Verdict.Kept], pop-up 2a; the caller moves the trial's good level), else back
 *    ([Verdict.Back], 2b). The rest of the chain gets its turn the same way, one game start at a time;
 *  - a tried driver that doesn't load, shows no picture or stops the emulator is failed ([failed], card 3 or, next
 *    start, [Start.Gone]);
 *  - the players' reports' list ([GpuOrder]) can put a driver ahead of the one in use: [start] runs it on any game
 *    (pop-up 4) and [judge] keeps it unless it's [FASTER] slower on a game measured on both.
 * Measurements: the host's work a frame (acquire, emulation, present: not the speed cap's or audio's waits), taken
 * only over stretches of normal play ([measure]).
 */
class GpuSpeed(private val store: GpuTrial.Store, install: String, systemGpu: String, private val levels: Int) {
    private val key = "speed|$install|$systemGpu"
    private val mPrefix = "spdm|$install|$systemGpu|"

    /** A try of [level] on [md5] ([ANY]: any game, from the reports' list); [begun] before the game loads, [drew] once a picture showed. */
    data class Try(val md5: String, val level: Int, val fromList: Boolean, val begun: Boolean = false, val drew: Boolean = false)

    /** One game on one level: the frames' work (ms), how many, the play (ms) and the emulated frames in it. */
    data class Measure(val workMs: Double, val frames: Long, val playMs: Long, val runFrames: Long) {
        val mean get() = if (frames > 0) workMs / frames else 0.0
        fun speed(fps: Double) = if (playMs > 0 && fps > 0) runFrames / (playMs / 1000.0) / fps else 0.0
        operator fun plus(o: Measure) = Measure(workMs + o.workMs, frames + o.frames, playMs + o.playMs, runFrames + o.runFrames)
    }

    /** [note]: a level whose try failed since the last start, for [Start.Gone]. */
    private data class State(val now: Try?, val tried: Map<Int, String>, val note: Int? = null)

    private fun read(): State {
        var now: Try? = null; val tried = LinkedHashMap<Int, String>(); var note: Int? = null
        for (part in store.get(key).orEmpty().split(';')) {
            val name = part.substringBefore('='); val v = part.substringAfter('=', "")
            when (name) {
                "try" -> v.split(':').takeIf { it.size == 5 }?.let { p ->
                    p[1].toIntOrNull()?.let { now = Try(p[0], it, p[2] == "1", p[3] == "1", p[4] == "1") } }
                "tried" -> for (e in v.split(',')) e.split(':').takeIf { it.size == 2 }?.let { p -> p[0].toIntOrNull()?.let { tried[it] = p[1] } }
                "note" -> note = v.toIntOrNull()
            }
        }
        return State(now, tried, note)
    }

    private fun write(s: State) {
        val parts = mutableListOf<String>()
        s.now?.let { parts += "try=${it.md5}:${it.level}:${b(it.fromList)}:${b(it.begun)}:${b(it.drew)}" }
        if (s.tried.isNotEmpty()) parts += "tried=" + s.tried.entries.joinToString(",") { "${it.key}:${it.value}" }
        s.note?.let { parts += "note=$it" }
        store.put(key, parts.joinToString(";").ifEmpty { null })
    }

    private fun b(v: Boolean) = if (v) "1" else "0"

    /** What each tried level did: [FASTER_KEPT], [SLOWER] or [FAILED]. */
    fun tried(): Map<Int, String> = read().tried

    /** The try under way, if any. */
    fun current(): Try? = read().now

    sealed interface Start {
        /** This start runs [t]'s level. */
        data class Run(val t: Try) : Start
        /** [level]'s try failed since the last start (it didn't load, or the emulator stopped): back on the good level. */
        data class Gone(val level: Int, val why: String) : Start
        data object None : Start
    }

    /**
     * At a start of [md5] with the trial's good level [good]: a try for this game (or any game) runs; a try that began
     * and never drew died ([GpuTrial.STOPPED]); [listLevel] (the reports' list's pick, ahead of [good]) starts a try.
     */
    fun start(md5: String?, good: Int, listLevel: Int?): Start {
        // Earlier installs' and system drivers' records never apply again (the key changed): drop them.
        for (k in store.keys()) if ((k.startsWith("speed|") && k != key) || (k.startsWith("spdm|") && !k.startsWith(mPrefix))) store.put(k, null)
        var s = read()
        s.now?.let { t ->
            if (t.level == good || t.level >= levels) { s = s.copy(now = null); write(s) }
            else if (t.begun && !t.drew) {
                write(State(null, s.tried + (t.level to FAILED), null))
                return Start.Gone(t.level, GpuTrial.STOPPED)
            }
        }
        s.note?.let { n -> write(s.copy(note = null)); return Start.Gone(n, "") }
        val t = s.now
        if (t != null && (t.md5 == ANY || t.md5 == md5)) return Start.Run(t)
        if (t == null && listLevel != null && listLevel != good && listLevel in 0 until levels && listLevel !in s.tried) {
            val n = Try(ANY, listLevel, true)
            write(s.copy(now = n))
            return Start.Run(n)
        }
        return Start.None
    }

    /** Just before the game loads with the try's level: it counts as stopped until [drew]. */
    fun begin() { val s = read(); s.now?.let { write(s.copy(now = it.copy(begun = true))) } }

    /** The game didn't load (not the driver's doing): the start doesn't count. */
    fun cancel() { val s = read(); s.now?.let { write(s.copy(now = it.copy(begun = false))) } }

    /** The try's driver showed a picture. */
    fun drew() { val s = read(); s.now?.let { if (!it.drew) write(s.copy(now = it.copy(drew = true))) } }

    /** The try's [level] can't work here; the next start is back on the good level. [note]: say so at that start (no card was shown). */
    fun failed(level: Int, note: Boolean) {
        val s = read()
        write(State(if (s.now?.level == level) null else s.now, s.tried + (level to FAILED), if (note) level else s.note))
    }

    private fun measures(md5: String): Map<Int, Measure> = store.get(mPrefix + md5).orEmpty().split(',').mapNotNull { e ->
        val p = e.split(':'); if (p.size != 5) return@mapNotNull null
        val l = p[0].toIntOrNull() ?: return@mapNotNull null
        l to Measure(p[1].toDoubleOrNull() ?: return@mapNotNull null, p[2].toLongOrNull() ?: 0, p[3].toLongOrNull() ?: 0, p[4].toLongOrNull() ?: 0)
    }.toMap()

    fun measure(md5: String, level: Int): Measure? = measures(md5)[level]

    /** Adds a stretch of normal play of [md5] on [level]. */
    fun measure(md5: String, level: Int, m: Measure) {
        if (m.frames <= 0 || m.playMs <= 0) return
        val all = measures(md5).toMutableMap()
        all[level] = (all[level] ?: Measure(0.0, 0, 0, 0)) + m
        store.put(mPrefix + md5, all.entries.joinToString(",") { (l, v) -> "$l:${"%.1f".format(java.util.Locale.ROOT, v.workMs)}:${v.frames}:${v.playMs}:${v.runFrames}" })
    }

    sealed interface Verdict {
        data object None : Verdict
        /** The game is slow on the good level: [level] is tried at its next start. */
        data class Next(val level: Int) : Verdict
        /** [level] was faster than [from]: it's the good level now (the caller moves the trial). */
        data class Kept(val level: Int, val from: Int, val fromList: Boolean) : Verdict
        /** [level] wasn't faster: the next start is back on [to]. */
        data class Back(val level: Int, val to: Int) : Verdict
    }

    /**
     * After a stretch of [md5] on [level] (the good level [good], or the try's), at [fps] (the game's frame rate):
     * what happens next. [skip]: levels the trial already failed, never tried.
     */
    fun judge(md5: String, level: Int, good: Int, fps: Double, skip: Set<Int>): Verdict {
        val s = read()
        val m = measures(md5)
        val t = s.now
        if (t != null && t.level == level && level != good) {
            val mt = m[level] ?: return Verdict.None
            if (mt.playMs < PLAY_MS) return Verdict.None
            val mc = m[good]?.takeIf { it.playMs >= COMPARE_MS && it.frames > 0 }
            val keep = when {
                mc == null -> t.fromList     // the reports' pick with nothing here to compare it to: it worked, keep it
                t.fromList -> mt.mean <= mc.mean / FASTER
                else -> mt.mean <= mc.mean * FASTER || (mc.speed(fps) < SLOW && mt.speed(fps) >= mc.speed(fps) + 0.03)
            }
            return if (keep) {
                write(State(null, s.tried + (level to FASTER_KEPT) + (good to SLOWER), null))
                Verdict.Kept(level, good, t.fromList)
            } else {
                write(State(null, s.tried + (level to SLOWER), s.note))
                Verdict.Back(level, good)
            }
        }
        if (t == null && level == good) {
            val mc = m[good] ?: return Verdict.None
            if (mc.playMs < PLAY_MS) return Verdict.None
            val budget = if (fps > 0) 1000.0 / fps else 16.7
            if (mc.speed(fps) >= SLOW && mc.mean <= budget * BUSY) return Verdict.None
            val next = (0 until levels).firstOrNull { it != good && it !in s.tried && it !in skip } ?: return Verdict.None
            write(s.copy(now = Try(md5, next, false)))
            return Verdict.Next(next)
        }
        return Verdict.None
    }

    /** Each level measured here, its mean work a frame over every game (ms), or null for a failed one; for Settings. */
    fun summary(): Map<Int, Double?> {
        val sum = HashMap<Int, Measure>()
        for (k in store.keys()) if (k.startsWith(mPrefix)) for ((l, v) in measures(k.removePrefix(mPrefix))) sum[l] = (sum[l] ?: Measure(0.0, 0, 0, 0)) + v
        val out = LinkedHashMap<Int, Double?>()
        for ((l, v) in sum) if (v.frames > 0) out[l] = v.mean
        for ((l, why) in read().tried) if (why == FAILED) out[l] = null
        return out
    }

    companion object {
        const val ANY = "*"
        /** Play on one game before it's judged (Joey's mockup: 3 minutes). */
        const val PLAY_MS = 180_000L
        /** The driver in use needs this much of the same game to be compared with. */
        const val COMPARE_MS = 60_000L
        /** Kept when its work a frame is at most this share of the other's (5% faster). */
        const val FASTER = 0.95
        /** Below this share of full speed, or with work over [BUSY] of the frame, a game is slow. */
        const val SLOW = 0.97
        const val BUSY = 0.9
        const val FASTER_KEPT = "faster"
        const val SLOWER = "slower"
        const val FAILED = "failed"
    }
}
