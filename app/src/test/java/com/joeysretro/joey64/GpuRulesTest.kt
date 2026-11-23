package com.joeysretro.joey64

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GpuRules must give the four GPUs we run exactly what the old checks in MainActivity gave them (README order item 15). */
class GpuRulesTest {
    private val adreno = 0x5143
    private val arm = 0x13B5

    private fun env(f: GpuRules.Family) = GpuRules.settings(f).associate { it.key to it.value }

    @Test fun thorAndPortal740() {
        val f = GpuRules.family(adreno, "Adreno (TM) 740", null)
        assertEquals(740, f.model)
        assertEquals(GpuRules.Driver.ARMSX2, GpuRules.autoDriver(f).value)
        // other 7xx keep Turnip Gen8 until a finding covers them
        assertEquals(GpuRules.Driver.TURNIP, GpuRules.autoDriver(GpuRules.family(adreno, "Adreno (TM) 750", null)).value)
        // with Turnip loaded the name is Turnip's
        val t = GpuRules.family(adreno, "Turnip Adreno (TM) 740", null)
        assertEquals(mapOf("PARALLEL_RDP_SINGLE_THREADED_COMMAND" to "1", "N64_RSP_BJIT" to "1", "N64_SMOOTHER" to "1", "mupen64plus-parallel-rdp-synchronous" to "True", "N64_RDP_NATIVE_FIRST" to "1", "N64_RDP_PREBUILD" to "0", "N64_GPU_TURBO" to "3"), env(t))
        assertEquals(8, GpuRules.upscaleTop(t).value)
        assertEquals("parallel", GpuRules.autoRenderer(true, true, true, t).value)
        assertEquals("gliden64", GpuRules.autoRenderer(true, true, false, f).value)
    }

    @Test fun odin3830() {
        val f = GpuRules.family(adreno, "Adreno (TM) 830", "Adreno830v2")
        assertEquals(GpuRules.Driver.SYSTEM, GpuRules.autoDriver(f).value)
        assertEquals(mapOf("PARALLEL_RDP_SINGLE_THREADED_COMMAND" to "1", "N64_RSP_BJIT" to "1", "N64_SMOOTHER" to "1", "mupen64plus-parallel-rdp-synchronous" to "True", "N64_RDP_NATIVE_FIRST" to "1", "N64_RDP_PREBUILD" to "0", "N64_GPU_TURBO" to "3"), env(f))
        assertEquals(listOf("N64_KGSL_PRIO"), GpuRules.beforeDriver("Odin3").map { it.key })
        assertTrue(GpuRules.beforeDriver("Retroid Pocket 5").isEmpty())
    }

    @Test fun odin3StockDriverAt8x() {
        val f = GpuRules.family(adreno, "Adreno (TM) 830", "Adreno830v2")
        // 8x: system driver, and parallel-RDP on it
        assertEquals(GpuRules.Driver.SYSTEM, GpuRules.autoDriver(f, 8).value)
        assertEquals("parallel", GpuRules.autoRenderer(true, true, false, f, upscale = 8).value)
        // 4x: system driver + parallel-RDP too (Joey 2026-11-09)
        assertEquals(GpuRules.Driver.SYSTEM, GpuRules.autoDriver(f, 4).value)
        assertEquals("parallel", GpuRules.autoRenderer(true, true, false, f, upscale = 4).value)
        // 1x and 2x: the system driver too (2026-11-10), and parallel-RDP on it, never GLideN64
        assertEquals(GpuRules.Driver.SYSTEM, GpuRules.autoDriver(f, 2).value)
        assertEquals(GpuRules.Driver.SYSTEM, GpuRules.autoDriver(f, 1).value)
        assertEquals("parallel", GpuRules.autoRenderer(true, true, false, f, upscale = 2).value)
        assertEquals("parallel", GpuRules.autoRenderer(true, true, false, f).value)
        // Turnip (custom driver) on Adreno tops out at 4x; stock stays 8x
        assertEquals(4, GpuRules.upscaleTop(f, customDriver = true).value)
        assertEquals(8, GpuRules.upscaleTop(f).value)
        // a7xx at 8x keeps its Turnip, ARMSX2 on the 740 (it caps to 4x anyway)
        val t = GpuRules.family(adreno, "Adreno (TM) 740", null)
        assertEquals(GpuRules.Driver.ARMSX2, GpuRules.autoDriver(t, 8).value)
        assertEquals("gliden64", GpuRules.autoRenderer(true, true, false, t, upscale = 8).value)
        // Duo stays 2x with Turnip; Mali untouched
        assertEquals(2, GpuRules.upscaleTop(GpuRules.family(adreno, "Turnip Adreno (TM) 722", null), customDriver = true).value)
    }

    @Test fun duo722() {
        val f = GpuRules.family(adreno, "Turnip Adreno (TM) 722", null)
        assertEquals(GpuRules.Driver.ARMSX2, GpuRules.autoDriver(GpuRules.family(adreno, "Adreno (TM) 722", null)).value)
        assertEquals(mapOf("PARALLEL_RDP_SINGLE_THREADED_COMMAND" to "1", "N64_RSP_BJIT" to "1", "mupen64plus-parallel-rdp-synchronous" to "True", "N64_RDP_NATIVE_FIRST" to "1", "N64_RDP_PREBUILD" to "0", "N64_GPU_TURBO" to "3"), env(f))
        assertEquals(2, GpuRules.upscaleTop(f).value)
    }

    @Test fun maliG615() {
        val f = GpuRules.family(arm, "Mali-G615 MC6", null)
        assertEquals(615, f.model)
        assertEquals(GpuRules.Driver.MALISX2, GpuRules.autoDriver(f).value)
        assertEquals(GpuRules.Driver.SYSTEM, GpuRules.autoDriver(GpuRules.family(arm, "Mali-G52 MC2", null)).value)
    }

    @Test fun testedGpus() {
        for (n in listOf("Adreno (TM) 740", "Turnip Adreno (TM) 722", "Adreno (TM) 830"))
            assertTrue(n, GpuRules.tested(GpuRules.family(adreno, n, null)))
        assertTrue(GpuRules.tested(GpuRules.family(arm, "Mali-G615 MC6", null)))
        assertTrue(GpuRules.tested(GpuRules.family(arm, "Mali-G52 MC2", null)))
        for (n in listOf("Adreno (TM) 750", "Adreno (TM) 650", "Adreno (TM) A32"))
            assertFalse(n, GpuRules.tested(GpuRules.family(adreno, n, null)))
        assertFalse(GpuRules.tested(GpuRules.family(arm, "Mali-G57 MC2", null)))
        assertFalse(GpuRules.tested(GpuRules.family(0x10DE, "Tegra", null)))
    }

    @Test fun gameDriverRules() {
        val f740 = GpuRules.family(adreno, "Adreno (TM) 740", null)
        val rules = mapOf("abc123" to listOf(Triple("adreno722", GpuRules.Driver.FAUX123, "x"), Triple("adreno740", GpuRules.Driver.TURNIP, "draws it right")))
        assertEquals(GpuRules.Driver.TURNIP, GpuRules.gameDriver("ABC123", f740, rules)?.value)
        assertEquals("draws it right", GpuRules.gameDriver("abc123", f740, rules)?.rule)
        assertEquals(null, GpuRules.gameDriver("abc123", GpuRules.family(adreno, "Adreno (TM) 830", null), rules))
        assertEquals(null, GpuRules.gameDriver("other", f740, rules))
        assertEquals(null, GpuRules.gameDriver(null, f740, rules))
        assertEquals("mali615", GpuRules.familyKey(GpuRules.family(arm, "Mali-G615 MC6", null)))
    }

    @Test fun carryOverOnlyWhatTheTrialFound() {
        val f740 = GpuRules.family(adreno, "Adreno (TM) 740", null)
        // an install from before driver_first that ran the old first pick moves to the new best
        assertEquals(null, GpuRules.carryOver(GpuRules.Driver.TURNIP, null, f740))
        // one the trial or the speed check moved keeps it
        assertEquals(GpuRules.Driver.R2, GpuRules.carryOver(GpuRules.Driver.R2, null, f740))
        assertEquals(GpuRules.Driver.TURNIP, GpuRules.carryOver(GpuRules.Driver.TURNIP, "armsx2", f740))
        assertEquals(null, GpuRules.carryOver(GpuRules.Driver.ARMSX2, "armsx2", f740))
        assertEquals(null, GpuRules.carryOver(GpuRules.Driver.FAUX123, null, GpuRules.family(adreno, "Adreno (TM) 722", null)))
        assertEquals(null, GpuRules.carryOver(GpuRules.Driver.SYSTEM, null, GpuRules.family(arm, "Mali-G615 MC6", null)))
        assertEquals(null, GpuRules.carryOver(null, null, f740))
    }

    @Test fun flipMaliG52() {
        val f = GpuRules.family(arm, "Mali-G52 MC2", null)
        assertEquals(GpuRules.Vendor.MALI, f.vendor)
        assertEquals(GpuRules.Driver.SYSTEM, GpuRules.autoDriver(f).value)
        assertEquals(mapOf("N64_EARLY_ACQUIRE" to "30", "PARALLEL_RDP_SINGLE_THREADED_COMMAND" to "1", "N64_RSP_BJIT" to "1", "N64_RDP_RING_LAZY" to "0",
            "N64_DRC_THREAD" to "1", "N64_DRC_JCACHE" to "0"), env(f))
        assertEquals("parallel", GpuRules.autoRenderer(true, true, false, f).value)
        assertEquals(8, GpuRules.upscaleTop(f).value)
    }

    @Test fun rk3566LittleCoresGetParallel() {
        // the RK3566 (four Cortex-A55s): GLideN64 from 2026-11-09, parallel-RDP since 2026-11-22 (RG353P measured level)
        val rk = (0..3).joinToString("\n\n") { "processor\t: $it\nCPU implementer\t: 0x41\nCPU part\t: 0xd05\n" } + "\nHardware\t: Rockchip RK3566"
        val f = GpuRules.family(arm, "Mali-G52", null)
        assertTrue(GpuRules.littleCoresOnly(rk))
        assertEquals("parallel", GpuRules.autoRenderer(true, true, false, f, GpuRules.littleCoresOnly(rk)).value)
        // a dev driver stays the user's choice
        assertEquals("parallel", GpuRules.autoRenderer(true, true, true, f, true).value)
        // the Flip (T618: 6x A55 + 2x A75) keeps parallel-RDP; unreadable cpuinfo changes nothing
        val flip = (0..5).joinToString("\n") { "CPU part\t: 0xd05" } + "\n" + (6..7).joinToString("\n") { "CPU part\t: 0xd0a" }
        assertEquals(false, GpuRules.littleCoresOnly(flip))
        assertEquals(false, GpuRules.littleCoresOnly(null))
        assertEquals(false, GpuRules.littleCoresOnly(""))
        assertEquals("parallel", GpuRules.autoRenderer(true, true, false, f, GpuRules.littleCoresOnly(flip)).value)
    }

    @Test fun lastImpactGetsGliden() {
        // SM64 Last Impact by its MD5 (either case); plain SM64 and an unknown game have no per-game renderer
        assertEquals("gliden64", GpuRules.gameRenderer("70059d4d7ba7ac0f0741b44aec8b44dc")?.value)
        assertEquals("gliden64", GpuRules.gameRenderer("70059D4D7BA7AC0F0741B44AEC8B44DC")?.value)
        assertEquals(null, GpuRules.gameRenderer("20b854b239203baf6c961b850a4a51a2"))
        assertEquals(null, GpuRules.gameRenderer(null))
    }

    @Test fun newFamilies() {
        // a marketing name gets its model, so Turnip
        assertEquals(613, GpuRules.family(adreno, "Adreno (TM) A12", null).model)
        // kgsl fills in a name without a number; the name's number wins over kgsl's
        assertEquals(650, GpuRules.family(adreno, "Adreno (TM) A1", "Adreno650v2").model)
        assertEquals(740, GpuRules.family(adreno, "Adreno (TM) 740", "Adreno999v1").model)
        assertEquals(GpuRules.Driver.SYSTEM, GpuRules.autoDriver(GpuRules.family(adreno, "Adreno (TM) 530", null)).value)
        val other = GpuRules.family(0x1010, "PowerVR B-Series BXM-8-256", null)
        assertEquals(GpuRules.Vendor.OTHER, other.vendor)
        assertEquals(mapOf("N64_RSP_BJIT" to "1", "N64_RDP_RING_LAZY" to "0", "N64_DRC_JCACHE" to "0"), env(other))
        assertEquals("gliden64", GpuRules.autoRenderer(true, false, true, other).value)
    }

    private class MapStore : GpuTrial.Store {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
        override fun keys(): Set<String> = m.keys.toSet()
    }

    @Test
    fun oldRecordsAreDropped() {
        val st = MapStore()
        GpuTrial(st, "1", "Mali-G52 MC2").apply { decide(); begin(); drew() }
        GpuTrial(st, "2", "Mali-G52 MC2").apply { decide(); begin() } // a new install: a new record, the old one goes
        assertEquals(setOf("trial|2|Mali-G52 MC2"), st.keys())
    }

    @Test fun trialStepsDownAfterAStartThatDied() {
        val st = MapStore()
        fun t() = GpuTrial(st, "1", "Adreno (TM) 999", 3)
        assertEquals(0, t().decide().level); t().begin()            // dies
        assertEquals(1, t().decide().level); t().begin(); t().cancel() // the game didn't load: doesn't count
        assertEquals(1, t().decide().level); t().begin()
        val p = t().decide()
        assertEquals(2, p.level)
        assertEquals(listOf(0 to GpuTrial.STOPPED, 1 to GpuTrial.STOPPED), p.history)
        t().begin(); t().drew()
        assertEquals(2, t().decide().level)
        assertTrue(t().decide().good)
        repeat(3) { t().begin() }                                   // good stays good
        assertEquals(2, t().decide().level)
        // a new install tries Auto again, its history gone
        assertEquals(GpuTrial.Pick(0, "trial at level 0, 0 failed start(s) so far"), GpuTrial(st, "2", "Adreno (TM) 999", 3).decide())
    }

    @Test fun trialGoodOnFirstFrame() {
        val st = MapStore()
        val t = GpuTrial(st, "1", "Mali-G52 MC2")
        t.decide(); t.begin(); t.drew()
        t.begin(); t.begin(); t.begin()
        assertEquals(0, GpuTrial(st, "1", "Mali-G52 MC2").decide().level)
    }

    @Test fun trialFailMovesOnWithItsReason() {
        val st = MapStore()
        fun t() = GpuTrial(st, "1", "Adreno (TM) 650", 2)
        t().decide()
        assertTrue(t().fail("no 8- or 16-bit storage"))             // before the game: no start spent
        assertEquals(1, t().decide().level)
        t().begin()
        assertTrue(t().fail(GpuTrial.NO_PICTURE))                   // the watch: black
        val p = t().decide()
        assertEquals(2, p.level)                                    // GLideN64
        assertEquals(listOf(0 to "no 8- or 16-bit storage", 1 to GpuTrial.NO_PICTURE), p.history)
        assertFalse(t().fail(GpuTrial.NO_PICTURE))                  // nothing below GLideN64
        assertFalse(p.told); t().told(); assertTrue(t().decide().told)
    }

    @Test fun trialStopsAtTheLowestLevel() {
        val st = MapStore()
        fun t() = GpuTrial(st, "1", "x", 4)
        repeat(10) { t().decide(); t().begin() }
        assertEquals(4, t().decide().level)
        assertEquals(4, t().decide().history.size)
    }

    @Test fun chains() {
        val names = { f: GpuRules.Family, sdk: Int -> GpuRules.chain(f, sdk).value }
        // best measured first, the old first pick right after it
        val a740 = names(GpuRules.family(adreno, "Adreno (TM) 740", null), 35)
        assertEquals(listOf(GpuRules.Driver.ARMSX2, GpuRules.Driver.TURNIP), a740.take(2))
        assertEquals(a740.size, a740.distinct().size)
        val a722 = names(GpuRules.family(adreno, "Adreno (TM) 722", null), 34)
        assertEquals(listOf(GpuRules.Driver.ARMSX2, GpuRules.Driver.FAUX123), a722.take(2))
        assertEquals(a722.size, a722.distinct().size)
        val g615 = names(GpuRules.family(0x13B5, "Mali-G615 MC6", null), 34)
        assertEquals(listOf(GpuRules.Driver.MALISX2, GpuRules.Driver.SYSTEM), g615.take(2))
        assertEquals(GpuRules.Driver.SYSTEM, names(GpuRules.family(adreno, "Adreno (TM) 830", null), 35).first())
        assertEquals(GpuRules.Driver.SYSTEM, names(GpuRules.family(0x13B5, "Mali-G52 MC2", null), 30).first())
        // the 650: ARMSX2 then TurnipZ (TurnipZ draws specks at 1x, 2026-11-22), the phone's own last; no repeats
        val a650 = names(GpuRules.family(adreno, "Adreno (TM) 650", null), 33)
        assertEquals(listOf(GpuRules.Driver.ARMSX2, GpuRules.Driver.TURNIPZ), a650.take(2))
        // below Android 11 TurnipZ (and Mr Purple) are left out: ARMSX2 still first
        assertEquals(GpuRules.Driver.ARMSX2, names(GpuRules.family(adreno, "Adreno (TM) 650", null), 29).first())
        assertEquals(GpuRules.Driver.SYSTEM, a650.last())
        assertEquals(a650.size, a650.distinct().size)
        // a driver above the Android version is left out
        assertFalse(GpuRules.Driver.APEX in names(GpuRules.family(adreno, "Adreno (TM) 740", null), 32))
        assertTrue(GpuRules.Driver.APEX in names(GpuRules.family(adreno, "Adreno (TM) 740", null), 33))
        assertFalse(GpuRules.Driver.PANVK in names(GpuRules.family(0x13B5, "Mali-G615", null), 34))
        assertEquals(GpuRules.Driver.ARMSX2, GpuRules.autoDriver(GpuRules.family(adreno, "Adreno (TM) 650", null)).value)
        // the 610-640: the system driver first (TurnipZ lost the GPU mid-game on the 613), TurnipZ next
        val a613 = names(GpuRules.family(adreno, "Adreno (TM) 613", null), 33)
        assertEquals(listOf(GpuRules.Driver.SYSTEM, GpuRules.Driver.TURNIPZ), a613.take(2))
        assertEquals(a613.size, a613.distinct().size)
        // PowerVR: the phone's own driver is tried with parallel-RDP during a trial
        val other = GpuRules.family(0x1010, "PowerVR B-Series BXM-8-256", null)
        assertEquals(listOf(GpuRules.Driver.SYSTEM), names(other, 35))
        assertEquals("parallel", GpuRules.autoRenderer(true, true, false, other, tryStock = true).value)
        assertEquals("gliden64", GpuRules.autoRenderer(true, true, false, other).value)
        // every bundled driver has its own folder or file, every id is unique
        val ids = GpuRules.Driver.entries.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        val assets = GpuRules.Driver.entries.mapNotNull { it.asset }
        assertEquals(assets.size, assets.distinct().size)
    }

    private fun frame(w: Int, h: Int, lit: Int): ByteArray {
        val b = ByteArray(8 + w * h * 4)
        b[0] = w.toByte(); b[1] = (w shr 8).toByte(); b[4] = h.toByte(); b[5] = (h shr 8).toByte()
        // [lit] bright pixels in a block in the middle
        var n = 0
        for (y in h / 2 until h) for (x in w / 2 until w) if (n < lit) { b[8 + (y * w + x) * 4 + 1] = 200.toByte(); n++ }
        return b
    }

    @Test fun failedPicks() {
        assertTrue(GpuRules.failedPicks(null, "1").isEmpty())
        val p = GpuRules.withFailedPick(null, "1", "k11", GpuRules.LOAD)
        assertEquals(mapOf("k11" to "load"), GpuRules.failedPicks(p, "1"))
        val p2 = GpuRules.withFailedPick(p, "1", "apex", GpuRules.PRDP)
        assertEquals(mapOf("k11" to "load", "apex" to "prdp"), GpuRules.failedPicks(p2, "1"))
        assertTrue(GpuRules.failedPicks(p2, "2").isEmpty())                          // an update tries them again
        assertEquals(mapOf("r2" to "load"), GpuRules.failedPicks(GpuRules.withFailedPick(p2, "2", "r2", GpuRules.LOAD), "2"))
        assertEquals("Didn't load", GpuRules.failedLabel(GpuRules.LOAD))
        assertNull(GpuRules.failedLabel(null))
    }

    @Test fun pictureTest() {
        assertFalse(GpuRules.lit(null))
        assertFalse(GpuRules.lit(ByteArray(4)))
        assertFalse(GpuRules.lit(frame(320, 240, 0)))               // black
        assertTrue(GpuRules.lit(frame(320, 240, 320 * 120)))        // half the screen
        assertTrue(GpuRules.lit(frame(320, 240, 320 * 10)))         // a logo's worth
        val short = frame(320, 240, 100).copyOf(100)                // cut off: not a picture
        assertFalse(GpuRules.lit(short))
        // faint scattered dots all over (Turnip R2 on the 830): black
        val noise = frame(320, 240, 0); val rnd = java.util.Random(1)
        for (y in 0 until 240) for (x in 0 until 320) if (rnd.nextInt(5) == 0) noise[8 + (y * 320 + x) * 4 + 1] = 60.toByte()
        assertFalse(GpuRules.lit(noise))
        // a small bright logo, 32 x 24, in the middle of black: a picture
        val logo = frame(320, 240, 0)
        for (y in 108 until 132) for (x in 144 until 176) logo[8 + (y * 320 + x) * 4] = 220.toByte()
        assertTrue(GpuRules.lit(logo))
    }

    @Test fun trialCards() {
        val names = listOf("Turnip Gen8", "Balemuni Apex", "Mr Purple T30")
        assertNull(com.joeysretro.joey64.launcher.ingame.GpuTrialCard.tryCard(names, emptyList(), 0))     // a first try: no card
        val c = com.joeysretro.joey64.launcher.ingame.GpuTrialCard.tryCard(names, listOf(0 to "no picture"), 1)!!
        assertEquals("Turnip Gen8", c.failed); assertEquals("Balemuni Apex", c.next); assertFalse(c.either)
        assertEquals(listOf("Turnip Gen8: no picture", "Balemuni Apex: starting", "Mr Purple T30", "GLideN64, if none of them can"), c.steps.map { it.text })
        val c2 = com.joeysretro.joey64.launcher.ingame.GpuTrialCard.tryCard(names, listOf(0 to "no picture", 1 to "the emulator stopped"), 2)!!
        assertTrue(c2.either); assertEquals("Balemuni Apex", c2.failed)
        assertNull(com.joeysretro.joey64.launcher.ingame.GpuTrialCard.tryCard(names, listOf(0 to "a", 1 to "b", 2 to "c"), 3)) // GLideN64: the end card
        val end = com.joeysretro.joey64.launcher.ingame.GpuTrialCard.steps(names, listOf(0 to "a", 1 to "b", 2 to "c"), 3)
        assertEquals(com.joeysretro.joey64.launcher.ingame.GpuStep(com.joeysretro.joey64.launcher.ingame.GpuStep.Mark.Good, "GLideN64"), end.last())
    }
}
