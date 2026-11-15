package com.joeysretro.joey64

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Auto's speed check (approved drvspeed mockup 2026-11-15) and the reports' driver list. */
class GpuSpeedTest {
    private class MapStore : GpuTrial.Store {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
        override fun keys(): Set<String> = m.keys.toSet()
    }

    private val fps = 60.0
    /** [ms] a frame over [sec] seconds at [speed] of full speed. */
    private fun play(ms: Double, sec: Int, speed: Double = 1.0): GpuSpeed.Measure {
        val frames = (sec * fps * speed).toLong()
        return GpuSpeed.Measure(ms * frames, frames, sec * 1000L, frames)
    }

    @Test fun fastGameNeverTries() {
        val sp = GpuSpeed(MapStore(), "1", "Adreno (TM) 740", 6)
        assertEquals(GpuSpeed.Start.None, sp.start("g", 0, null))
        sp.measure("g", 0, play(8.0, 200))
        assertEquals(GpuSpeed.Verdict.None, sp.judge("g", 0, 0, fps, emptySet()))
    }

    @Test fun slowGameTriesNextKeepsFaster() {
        val st = MapStore()
        val sp = GpuSpeed(st, "1", "gpu", 6)
        sp.start("g", 0, null)
        sp.measure("g", 0, play(16.0, 120))
        assertEquals(GpuSpeed.Verdict.None, sp.judge("g", 0, 0, fps, emptySet()))   // under 3 minutes
        sp.measure("g", 0, play(16.0, 70, 0.9))
        assertEquals(GpuSpeed.Verdict.Next(2), sp.judge("g", 0, 0, fps, setOf(1)))  // level 1 failed the trial: skipped
        // Another game's start isn't the try
        assertEquals(GpuSpeed.Start.None, sp.start("other", 0, null))
        val run = sp.start("g", 0, null) as GpuSpeed.Start.Run
        assertEquals(2, run.t.level)
        sp.begin(); sp.drew()
        sp.measure("g", 2, play(12.0, 190))
        assertEquals(GpuSpeed.Verdict.Kept(2, 0, false), sp.judge("g", 2, 0, fps, emptySet()))
        assertNull(sp.current())
        assertEquals(GpuSpeed.FASTER_KEPT, sp.tried()[2])
        val sum = sp.summary()
        assertEquals(12.0, sum[2]!!, 0.01)
        assertTrue(sum[0]!! > 15.0)
    }

    @Test fun notFasterGoesBack() {
        val sp = GpuSpeed(MapStore(), "1", "gpu", 3)
        sp.measure("g", 0, play(16.5, 200))
        assertEquals(GpuSpeed.Verdict.Next(1), sp.judge("g", 0, 0, fps, emptySet()))
        sp.start("g", 0, null); sp.begin(); sp.drew()
        sp.measure("g", 1, play(16.0, 200))
        assertEquals(GpuSpeed.Verdict.Back(1, 0), sp.judge("g", 1, 0, fps, emptySet()))
        // The rest of the chain gets its turn
        assertEquals(GpuSpeed.Verdict.Next(2), sp.judge("g", 0, 0, fps, emptySet()))
    }

    @Test fun tryThatDiesIsGone() {
        val sp = GpuSpeed(MapStore(), "1", "gpu", 3)
        sp.measure("g", 0, play(17.0, 200))
        sp.judge("g", 0, 0, fps, emptySet())
        sp.start("g", 0, null); sp.begin()          // the emulator stopped before a picture
        assertEquals(GpuSpeed.Start.Gone(1, GpuTrial.STOPPED), sp.start("g", 0, null))
        assertEquals(GpuSpeed.FAILED, sp.tried()[1])
        assertEquals(GpuSpeed.Start.None, sp.start("g", 0, null))
        // A quit before the load ends doesn't count
        sp.judge("g", 0, 0, fps, emptySet())
        sp.start("g", 0, null); sp.begin(); sp.cancel()
        assertTrue(sp.start("g", 0, null) is GpuSpeed.Start.Run)
    }

    @Test fun tryThatDiesAfterDrawingIsGone() {
        val sp = GpuSpeed(MapStore(), "1", "gpu", 3)
        sp.measure("g", 0, play(17.0, 200)); sp.judge("g", 0, 0, fps, emptySet())
        sp.start("g", 0, null); sp.begin(); sp.drew(); sp.ended()      // a clean session: still the try
        assertTrue(sp.start("g", 0, null) is GpuSpeed.Start.Run)
        sp.begin(); sp.drew(); sp.ended(); sp.again()                   // back from the background, then died
        assertEquals(GpuSpeed.Start.Gone(1, GpuTrial.STOPPED), sp.start("g", 0, null))
        assertEquals(GpuSpeed.FAILED, sp.tried()[1])
    }

    @Test fun measuresPerResolution() {
        val st = MapStore()
        GpuSpeed(st, "1", "gpu", 3, 4).measure("g", 0, play(10.0, 100))
        assertNull(GpuSpeed(st, "1", "gpu", 3, 2).measure("g", 0))
        assertNull(GpuSpeed(st, "1", "gpu", 3).summary()[0])
        assertEquals(10.0, GpuSpeed(st, "1", "gpu", 3, 4).summary()[0]!!, 0.01)
    }

    @Test fun updateStartsTrialOnTheKeptDriver() {
        val st = MapStore()
        GpuTrial(st, "1", "gpu", 4).apply { decide(); keep(2) }
        GpuTrial(st, "2", "gpu", 4).apply { carry(2) }.decide().let { assertEquals(2, it.level); assertTrue(!it.good) }
        // A first install (nothing good before) starts at the chain's first
        GpuTrial(MapStore(), "1", "gpu", 4).apply { carry(2) }.decide().let { assertEquals(0, it.level) }
    }

    @Test fun failedChecksSayGoneOnce() {
        val sp = GpuSpeed(MapStore(), "1", "gpu", 3)
        sp.measure("g", 0, play(17.0, 200)); sp.judge("g", 0, 0, fps, emptySet())
        sp.start("g", 0, null)
        sp.failed(1, true)
        assertEquals(GpuSpeed.Start.Gone(1, ""), sp.start("g", 0, null))
        assertEquals(GpuSpeed.Start.None, sp.start("g", 0, null))
        assertNull(sp.summary()[1])
    }

    @Test fun listPickRunsOnAnyGameKeptUnlessSlower() {
        val sp = GpuSpeed(MapStore(), "1", "gpu", 4)
        val run = sp.start("g", 0, 2) as GpuSpeed.Start.Run
        assertTrue(run.t.fromList)
        assertTrue(sp.start("other", 0, 2) is GpuSpeed.Start.Run)
        sp.begin(); sp.drew()
        sp.measure("other", 2, play(10.0, 200))
        // Nothing on the old driver to compare with: the reports' pick stays
        assertEquals(GpuSpeed.Verdict.Kept(2, 0, true), sp.judge("other", 2, 0, fps, emptySet()))
        // Never asked again once tried
        assertEquals(GpuSpeed.Start.None, sp.start("g", 2, 2))
    }

    @Test fun listPickSlowerHereGoesBack() {
        val sp = GpuSpeed(MapStore(), "1", "gpu", 4)
        sp.measure("g", 0, play(8.0, 100))
        sp.start("g", 0, 1); sp.begin(); sp.drew()
        sp.measure("g", 1, play(9.0, 200))
        assertEquals(GpuSpeed.Verdict.Back(1, 0), sp.judge("g", 1, 0, fps, emptySet()))
    }

    @Test fun newInstallStartsAfresh() {
        val st = MapStore()
        GpuSpeed(st, "1", "gpu", 3).measure("g", 0, play(10.0, 10))
        GpuSpeed(st, "2", "gpu", 3).start("g", 0, null)
        assertTrue(st.keys().none { it.contains("|1|") })
    }

    @Test fun orderKeyAndPick() {
        assertEquals("adreno 750", GpuOrder.key("Turnip Adreno (TM) 750"))
        assertEquals("adreno 750", GpuOrder.key("Adreno (TM) 750"))
        val o = GpuOrder.parse("""{"version":1,"gpus":{"Adreno (TM) 750":["armsx2","nope","turnip"]}}""")
        assertEquals(listOf("armsx2", "turnip"), o["adreno 750"])
        assertTrue(GpuOrder.parse("""{"version":2,"gpus":{}}""").isEmpty())
        val chain = listOf(GpuRules.Driver.TURNIP, GpuRules.Driver.FAUX123, GpuRules.Driver.ARMSX2)
        assertEquals(2, GpuOrder.pick(listOf("armsx2", "turnip"), chain, 0))
        assertNull(GpuOrder.pick(listOf("turnip", "armsx2"), chain, 0))
        assertNull(GpuOrder.pick(listOf("armsx2"), chain, 2))
        assertEquals(2, GpuOrder.pick(listOf("armsx2"), chain, 0))   // the good one not on the list: the list's pick
        assertNull(GpuOrder.pick(null, chain, 0))
    }
}
