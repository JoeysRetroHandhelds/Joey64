package com.joeysretro.joey64.reports

import com.joeysretro.joey64.netplay.NetInfo
import com.joeysretro.joey64.netplay.NetPlayer
import org.json.JSONObject

/*
 * A netplay room's figures for the play session's report (Joey 2026-11-14: "send reports needs to have more
 * information on net play and crashes as now we can't diagnose what happened yesterday in my sessions"). The game
 * screen feeds it every room check (250 ms); [json] is what the session carries as "netplay". No names, codes or
 * addresses: player numbers, counts, times and pings only.
 */
class NetStats {
    private var hosting = false
    private var me = 0
    private var playersMost = 0
    private var delay = -1
    private var delayMost = 0
    private var pingSum = 0L
    private var pingN = 0
    private var pingMost = 0
    private var waits = 0
    private var waitMs = 0L
    private var pausedMs = 0L
    private var outOfStep = 0
    private var leftMask = 0
    private var lastAt = -1L
    private var wasWaiting = false
    private var wasOut = false
    private var inRoomMs = 0L
    private var seen = false

    /** How the room ended for this player, when it did (see [endOf]); null while it's on or for a plain quit. */
    var end: String? = null

    /** One room check at [now] (ms). [players]: the room's list, this player's own entry included. */
    fun sample(n: NetInfo, players: List<NetPlayer>, now: Long) {
        if (!n.inRoom) { lastAt = -1; return }
        seen = true
        val dt = if (lastAt >= 0) (now - lastAt).coerceIn(0, 2_000) else 0
        lastAt = now
        inRoomMs += dt
        hosting = n.hosting
        if (n.me != 0) me = n.me
        playersMost = maxOf(playersMost, Integer.bitCount(n.joined), players.size)
        if (n.delay > 0) { delay = n.delay; delayMost = maxOf(delayMost, n.delay) }
        for (p in players) if (p.player != me && p.pingMs in 0..10_000) {
            pingSum += p.pingMs; pingN++; pingMost = maxOf(pingMost, p.pingMs)
        }
        val waiting = n.pausedBy == 0 && n.waitingFor != 0 && n.waitingFor != me
        if (waiting) { waitMs += dt; if (!wasWaiting) waits++ }
        wasWaiting = waiting
        if (n.pausedBy != 0) pausedMs += dt
        if (n.outOfStep && !wasOut) outOfStep++
        wasOut = n.outOfStep
        leftMask = leftMask or n.left
    }

    /** The session's "netplay" part, or null when it never was in a room. */
    fun json(): JSONObject? = if (!seen) null else JSONObject().apply {
        put("role", if (hosting) "host" else "join")
        if (me in 1..4) put("me", me)
        if (playersMost in 1..4) put("players", playersMost)
        if (delay > 0) { put("delay", delay); put("delayMax", delayMost) }
        if (pingN > 0) { put("pingAvg", (pingSum / pingN).toInt()); put("pingMax", pingMost) }
        put("seconds", inRoomMs / 1000)
        put("waits", waits)
        put("waitSeconds", Math.round(waitMs / 100.0) / 10.0)
        put("pausedSeconds", Math.round(pausedMs / 100.0) / 10.0)
        put("outOfStep", outOfStep)
        put("left", Integer.bitCount(leftMask))
        end?.let { put("end", it) }
    }

    companion object {
        /** The core's shutdown codes for a room (MainActivity, GameStartFailed.netEnded) as the report says them. */
        fun endOf(code: Int): String? = when (code) {
            3 -> "lost"
            4 -> "unreachable"
            5 -> "full"
            6 -> "mismatch"
            8 -> "hostclosed"
            else -> null
        }
        val ENDS = listOf("lost", "unreachable", "full", "mismatch", "hostclosed", "outofstep", "left")
    }
}
