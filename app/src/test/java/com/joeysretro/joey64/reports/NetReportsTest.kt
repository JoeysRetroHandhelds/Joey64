package com.joeysretro.joey64.reports

import com.joeysretro.joey64.netplay.NetInfo
import com.joeysretro.joey64.netplay.NetPlayer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reports' netplay figures, session endings and log excerpts (Joey 2026-11-14). */
class NetReportsTest {
    private fun room(waitingFor: Int = 0, pausedBy: Int = 0, out: Boolean = false, left: Int = 0, delay: Int = 4) =
        NetInfo(true, false, 0b11, out, left, pausedBy, waitingFor, me = 2, delay = delay)

    @Test fun netStatsCountsWaitsPingsAndEnds() {
        val s = NetStats()
        assertNull(s.json())
        s.sample(NetInfo.OFF, emptyList(), 0)
        assertNull(s.json())
        val players = listOf(NetPlayer(1, "Joey", 40), NetPlayer(2, "Me", 0))
        var t = 1000L
        s.sample(room(), players, t)
        repeat(8) { t += 250; s.sample(room(waitingFor = 1), players, t) } // one wait of 2 s
        t += 250; s.sample(room(delay = 7), listOf(NetPlayer(1, "Joey", 120)), t)
        t += 250; s.sample(room(waitingFor = 1, out = true), players, t) // a second wait, out of step once
        t += 250; s.sample(room(out = true, left = 1), players, t)
        s.end = NetStats.endOf(8)
        val j = s.json()!!
        assertEquals("join", j.getString("role"))
        assertEquals(2, j.getInt("me"))
        assertEquals(2, j.getInt("players"))
        assertEquals(7, j.getInt("delayMax"))
        assertEquals(120, j.getInt("pingMax"))
        assertEquals(2, j.getInt("waits"))
        assertEquals(2.3, j.getDouble("waitSeconds"), 0.01)
        assertEquals(1, j.getInt("outOfStep"))
        assertEquals(1, j.getInt("left"))
        assertEquals("hostclosed", j.getString("end"))
        // Its own ping and names never go in.
        assertFalse(j.toString().contains("Joey"))
        assertEquals(46, j.getInt("pingAvg")) // player 1: 40 eleven times and 120 once
    }

    @Test fun sessionCarriesEndingsNetplayAndLog() {
        val base = JSONObject().put("md5", "9feee5da895fb2e115bb15d1c4a9f906").put("seconds", 100)
        for (e in ReportJson.ENDED) assertEquals(e, ReportJson.session(JSONObject(base.toString()).put("ended", e), null)!!.getString("ended"))
        assertEquals("quit", ReportJson.session(JSONObject(base.toString()).put("ended", "exploded"), null)!!.getString("ended"))
        val o = JSONObject(base.toString()).put("ended", "crash")
            .put("netplay", JSONObject().put("role", "host").put("waits", 3).put("waitSeconds", 1.25).put("name", "Joey").put("end", "boom"))
            .put("maskNames", org.json.JSONArray(listOf("Joey"))).put("log", "a\r\n\u0001b")
        val s = ReportJson.session(o, null)!!
        val n = s.getJSONObject("netplay")
        assertEquals("host", n.getString("role"))
        assertEquals(3, n.getInt("waits"))
        assertFalse(n.has("name"))
        assertFalse(n.has("end"))
        assertFalse(s.has("maskNames"))
        assertEquals("a\n b", s.getString("log"))
        assertNull(ReportJson.session(JSONObject(base.toString()).put("netplay", JSONObject().put("role", "x")), null)!!.optJSONObject("netplay"))
    }

    @Test fun excerptKeepsTheRoomLinesAndHidesNames() {
        val lines = (1..400).map { if (it == 10) "I playtogether: Joey Smith (Odin2) joined as player 2" else if (it == 20) "I netplay: connected to 8.8.8.8" else "line $it" } +
            "E core: crash in /storage/emulated/0/ROMs/Joey Smith/mk.z64"
        val x = ReportSender.excerpt(lines, listOf("Joey Smith"))
        assertTrue(x.startsWith("I playtogether: <player> (Odin2) joined as player 2\nI netplay: connected to <public address>\n...\nline 152"))
        assertFalse(x.contains("Joey Smith"))
        assertFalse(x.contains("8.8.8.8"))
        assertTrue(x.endsWith("E core: crash in <path>"))
        assertTrue(x.length <= ReportJson.MAX_LOG)
    }

    @Test fun excerptKeepsEarlierDipsAndFitsWithThemFirst() {
        // 600 long lines: the tail alone is far over 16,000 characters, so the head must survive the cut.
        val pad = "x".repeat(120)
        val lines = (1..600).map { i ->
            when (i) {
                40 -> "I joey64host: stats: 59.0 emu fps, run 30.1 ms"
                41 -> "I joey64host: hitch: 1 stutters"
                42 -> "I joey64host: slow frames: 12.0 s 45.0 ms"
                43 -> "I joey64host: clocks: cpu 1843/3187, gpu 401/680"
                else -> "line $i $pad"
            }
        }
        val x = ReportSender.excerpt(lines, emptyList())
        assertTrue(x.startsWith("I joey64host: stats: 59.0 emu fps, run 30.1 ms\nI joey64host: slow frames: 12.0 s 45.0 ms\nI joey64host: clocks: cpu 1843/3187, gpu 401/680\n...\nline "))
        assertFalse(x.contains("hitch: 1 stutters"))
        assertTrue(x.endsWith("line 600 $pad"))
        assertTrue(x.length <= ReportJson.MAX_LOG)
        // The tail is cut at a line start.
        assertTrue(x.substringAfter("...\n").startsWith("line "))
    }

    @Test fun whichSessionsSendTheirLog() {
        val quit = JSONObject().put("ended", "quit")
        assertFalse(ReportJson.wantsLog(quit, testing = false))
        assertTrue(ReportJson.wantsLog(quit, testing = true))
        assertTrue(ReportJson.wantsLog(JSONObject().put("ended", "crash"), testing = false))
        assertTrue(ReportJson.wantsLog(JSONObject(quit.toString()).put("netplay", JSONObject()), testing = false))
        assertFalse(ReportJson.wantsLog(JSONObject(quit.toString()).put("slowFrames", 9), testing = false))
        assertTrue(ReportJson.wantsLog(JSONObject(quit.toString()).put("slowFrames", 10), testing = false))
    }

    @Test fun whatsSentSaysEveryGameOnlyInTestBuilds() {
        val pub = com.joeysretro.joey64.launcher.screens.whatsSent("0.2.5")
        val test = com.joeysretro.joey64.launcher.screens.whatsSent("0.2.5.1")
        assertFalse(pub.contains("every game"))
        assertTrue(test.contains("This test build sends that log for every game"))
    }

    @Test fun excerptHidesWholeNamesOnly() {
        val x = ReportSender.excerpt(listOf("I Joey64: Joey joined", "I room: Joey's turn, joey64x"), listOf("Joey"))
        assertEquals("I Joey64: <player> joined\nI room: <player>'s turn, joey64x", x)
    }

    @Test fun androidReasonsToEndings() {
        assertEquals("crash", ReportSender.endedOf(android.app.ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("anr", ReportSender.endedOf(android.app.ApplicationExitInfo.REASON_ANR))
        assertEquals("killed", ReportSender.endedOf(android.app.ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("closed", ReportSender.endedOf(android.app.ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals("signal", ReportSender.endedOf(android.app.ApplicationExitInfo.REASON_SIGNALED))
        assertEquals("gone", ReportSender.endedOf(android.app.ApplicationExitInfo.REASON_UNKNOWN))
        for (e in listOf("crash", "anr", "killed", "closed", "signal", "gone")) assertTrue(e in ReportJson.ENDED)
    }
}
