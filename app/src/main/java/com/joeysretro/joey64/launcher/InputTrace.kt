package com.joeysretro.joey64.launcher

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.joeysretro.joey64.JLog

/**
 * What reached the library before a game started (queue "Thor lid: opening it acts like A on the library", Joey
 * 2026-11-17: hard to make happen on purpose, so the log has to catch it). Every key edge, touch down / up, screen on /
 * off, window focus, resume / pause and controller added / removed / changed goes into a small ring; when a game starts
 * from the library the ring's last [BEFORE_START_MS] are logged ("input before <game>: ..."), so the press that started
 * it is named whichever way it came (a pad's A, a key-up from before the sleep, a pad reconnecting, the lid's screen
 * touched). For [LIVE_MS] after a resume, a window focus or the screen coming on, each entry is also logged as it
 * comes. Nothing else is kept, and nothing leaves the session log.
 */
object InputTrace {
    private const val TAG = "Joey64Input"
    private const val RING = 48
    private const val BEFORE_START_MS = 15_000L
    private const val LIVE_MS = 10_000L

    private val times = LongArray(RING)
    private val lines = arrayOfNulls<String>(RING)
    private var next = 0
    private var liveUntil = 0L

    @Synchronized private fun add(line: String) {
        val now = SystemClock.uptimeMillis()
        times[next] = now; lines[next] = line
        next = (next + 1) % RING
        if (now < liveUntil) JLog.i(TAG, line)
    }

    /** A lifecycle or screen change: logged at once, and the next [LIVE_MS] of input with it. */
    @Synchronized fun mark(what: String) {
        liveUntil = SystemClock.uptimeMillis() + LIVE_MS
        add(what)
    }

    private fun dev(d: InputDevice?): String {
        if (d == null) return "no device"
        val ext = if (android.os.Build.VERSION.SDK_INT >= 29 && d.isExternal) " ext" else ""
        // the name kept out of format(): a '%' in it would be read as a format
        return "'${d.name}' id ${d.id} " + "%04x:%04x src 0x%x".format(d.vendorId, d.productId, d.sources) + ext
    }

    fun key(e: KeyEvent, where: String = "library") {
        val act = when (e.action) { KeyEvent.ACTION_DOWN -> "down"; KeyEvent.ACTION_UP -> "up"; else -> "multiple" }
        // held: how long since its press (a key-up whose press came before the sleep shows a long one)
        val held = e.eventTime - e.downTime
        // a typing key (a letter, digit or symbol) is never named: it may be part of a password (release audit 2026-11-22)
        val typing = e.isPrintingKey && !KeyEvent.isGamepadButton(e.keyCode)
        val name = if (typing) "a typing key" else KeyEvent.keyCodeToString(e.keyCode)
        val scan = if (typing) "-" else "${e.scanCode}"
        add("$where key $act $name rep ${e.repeatCount} scan $scan held ${held} ms" +
            "${if (e.isCanceled) " canceled" else ""} flags 0x${Integer.toHexString(e.flags)}, ${dev(e.device)}")
    }

    fun touch(e: MotionEvent) {
        val a = e.actionMasked
        if (a != MotionEvent.ACTION_DOWN && a != MotionEvent.ACTION_UP && a != MotionEvent.ACTION_CANCEL &&
            a != MotionEvent.ACTION_POINTER_DOWN) return
        val act = when (a) { MotionEvent.ACTION_DOWN -> "down"; MotionEvent.ACTION_UP -> "up"
            MotionEvent.ACTION_CANCEL -> "cancel"; else -> "pointer down" }
        add("touch $act at ${e.x.toInt()},${e.y.toInt()} pointers ${e.pointerCount},${dev(e.device)}")
    }

    /** A game is starting from the library: what came in the [BEFORE_START_MS] before it. */
    @Synchronized fun beforeStart(game: String) {
        val now = SystemClock.uptimeMillis()
        // one line each: a log line holds 2048 characters, and the newest entries (the press that started it) come last
        val recent = (0 until RING).map { (next + it) % RING }.filter { lines[it] != null && now - times[it] <= BEFORE_START_MS }
        JLog.i(TAG, "input before $game: ${if (recent.isEmpty()) "nothing" else "${recent.size} entries"} in the last ${BEFORE_START_MS / 1000} s")
        for (i in recent) JLog.i(TAG, "  ${now - times[i]} ms before $game: ${lines[i]}")
    }
}
