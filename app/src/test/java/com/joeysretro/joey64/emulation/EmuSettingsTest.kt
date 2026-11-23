package com.joeysretro.joey64.emulation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EmuSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun apply(vararg kv: Pair<EmuKey, String>, needsPak: Boolean = false) = EmuApply.of(EmuSettings(mapOf(*kv)), needsPak)

    // Hardcore plays run at Normal (approved hardcore-overclock A and A, 2026-11-17): 2x and 4x are set aside, Auto stays Auto.
    @Test fun hardcoreRunsAtNormal() {
        for (oc in listOf("2x", "4x")) {
            val e = EmuSettings(mapOf(EmuKey.Overclock to oc))
            assertTrue(EmuApply.hardcoreDrops(e))
            assertEquals(mapOf("mupen64plus-CountPerOp" to "0"), EmuApply.of(e, false, hardcore = true).coreOptions)
        }
        assertEquals("1", EmuApply.of(EmuSettings(mapOf(EmuKey.Overclock to "4x")), false).coreOptions["mupen64plus-CountPerOp"])
        assertFalse(EmuApply.hardcoreDrops(EmuSettings(mapOf(EmuKey.Overclock to "normal"))))
        assertFalse(EmuApply.hardcoreDrops(EmuSettings(emptyMap())))
        assertTrue(EmuApply.of(EmuSettings(emptyMap()), false, hardcore = true).coreOptions.isEmpty())
    }

    @Test fun autoSetsNothing() {
        val a = apply()
        assertTrue(a.coreOptions.isEmpty())
        assertTrue(a.env.isEmpty())
        assertFalse(a.overclockSet)
    }

    @Test fun overclockSteps() {
        assertEquals(mapOf("mupen64plus-CountPerOp" to "0"), apply(EmuKey.Overclock to "normal").coreOptions)
        assertEquals(mapOf("mupen64plus-CountPerOp" to "1"), apply(EmuKey.Overclock to "2x").coreOptions)
        assertEquals(mapOf("mupen64plus-CountPerOp" to "1", "mupen64plus-CountPerOpDenomPot" to "1"), apply(EmuKey.Overclock to "4x").coreOptions)
        assertTrue(apply(EmuKey.Overclock to "normal").overclockSet)
    }

    @Test fun cpuCore() {
        assertEquals("dynamic_recompiler", apply(EmuKey.CpuCore to "recompiler").coreOptions["mupen64plus-cpucore"])
        assertEquals("cached_interpreter", apply(EmuKey.CpuCore to "interpreter").coreOptions["mupen64plus-cpucore"])
    }

    @Test fun expansionPakOffNeverForAGameThatNeedsIt() {
        assertEquals("True", apply(EmuKey.ExpansionPak to "off").coreOptions["mupen64plus-ForceDisableExtraMem"])
        assertTrue(apply(EmuKey.ExpansionPak to "off", needsPak = true).coreOptions.isEmpty())
        assertTrue(apply(EmuKey.ExpansionPak to "on").coreOptions.isEmpty())
        assertTrue(ExpansionPak.needed("NDOE"))
        assertTrue(ExpansionPak.needed("NZSJ"))
        assertTrue(ExpansionPak.needed("NPDP"))
        assertFalse(ExpansionPak.needed("NSME"))
        assertFalse(ExpansionPak.needed(null))
    }

    @Test fun thermalGoesToTheEnvironment() {
        assertEquals("1", apply(EmuKey.Thermal to "on").env["N64_THERMAL_GOV"])
        assertTrue(apply(EmuKey.Thermal to "off").env.isEmpty())
    }

    // Run-ahead (approved runahead mockup 2026-11-17): Off by default, 1-4 frames to the host's N64_RUNAHEAD, Hardcore too.
    @Test fun runAheadGoesToTheEnvironment() {
        assertEquals("off", EmuKey.RunAhead.default)
        assertNull(apply().env["N64_RUNAHEAD"])
        assertNull(apply(EmuKey.RunAhead to "off").env["N64_RUNAHEAD"])
        for (n in 1..4) assertEquals("$n", apply(EmuKey.RunAhead to "$n").env["N64_RUNAHEAD"])
        assertNull(apply(EmuKey.RunAhead to "9").env["N64_RUNAHEAD"])
        assertEquals("2", EmuApply.of(EmuSettings(mapOf(EmuKey.RunAhead to "2")), false, hardcore = true).env["N64_RUNAHEAD"])
        assertEquals(listOf("off", "1", "2", "3", "4"), EmuChoices.of(EmuKey.RunAhead).map { it.value })
        assertEquals("2 frames", EmuChoices.label(EmuKey.RunAhead, "2"))
        assertTrue(EmuKey.RunAhead in EmuKey.AT_START)
    }

    // Frame skip (approved frameskip mockup 2026-11-21): Off by default, the core's N64_FRAMESKIP 2 = when behind,
    // 1 = every other frame, Hardcore too.
    @Test fun frameSkipGoesToTheEnvironment() {
        assertEquals("off", EmuKey.FrameSkip.default)
        assertNull(apply().env["N64_FRAMESKIP"])
        assertNull(apply(EmuKey.FrameSkip to "off").env["N64_FRAMESKIP"])
        assertEquals("2", apply(EmuKey.FrameSkip to "behind").env["N64_FRAMESKIP"])
        assertEquals("1", apply(EmuKey.FrameSkip to "every_other").env["N64_FRAMESKIP"])
        assertNull(apply(EmuKey.FrameSkip to "9").env["N64_FRAMESKIP"])
        assertEquals("2", EmuApply.of(EmuSettings(mapOf(EmuKey.FrameSkip to "behind")), false, hardcore = true).env["N64_FRAMESKIP"])
        assertEquals(listOf("off", "behind", "every_other"), EmuChoices.of(EmuKey.FrameSkip).map { it.value })
        assertEquals("When behind", EmuChoices.label(EmuKey.FrameSkip, "behind"))
        assertTrue(EmuKey.FrameSkip in EmuKey.AT_START)
    }

    @Test fun allGamesHasNoExpansionPak() {
        val e = EmuSettings.resolve(mapOf(EmuKey.ExpansionPak to "off", EmuKey.Overclock to "2x"), null)
        assertEquals("on", e[EmuKey.ExpansionPak])
        assertEquals("2x", e[EmuKey.Overclock])
        assertEquals("off", EmuSettings.resolve(emptyMap(), mapOf(EmuKey.ExpansionPak to "off"))[EmuKey.ExpansionPak])
    }

    @Test fun storeKeepsAGamesOwn() {
        val s = EmuStore(File(tmp.root, "emulation"), null)
        s.saveAll(mapOf(EmuKey.Overclock to "2x", EmuKey.ExpansionPak to "off"))
        assertEquals(mapOf(EmuKey.Overclock to "2x"), s.all())
        s.saveGame("Banjo-Kazooie (USA)", mapOf(EmuKey.CpuCore to "interpreter"))
        assertEquals("interpreter", s.forGame("Banjo-Kazooie (USA)")[EmuKey.CpuCore])
        assertEquals("2x", s.forGame("Banjo-Kazooie (USA)")[EmuKey.Overclock])
        assertEquals(listOf("Banjo-Kazooie (USA)"), s.gamesWithOwn())
        s.saveGame("Banjo-Kazooie (USA)", emptyMap())
        assertTrue(s.gamesWithOwn().isEmpty())
    }

    @Test fun thermalMovesOverFromVideo() {
        val video = File(tmp.root, "video").apply { File(this, "games").mkdirs() }
        File(video, "all.json").writeText("""{"version":1,"thermal":"on","resolution":"2"}""")
        File(video, "games/Banjo.json").writeText("""{"version":1,"thermal":"off"}""")
        File(video, "games/Tooie.json").writeText("""{"version":1,"thermal":"on","aspect":"16:9"}""")
        val s = EmuStore(File(tmp.root, "emulation"), video)
        assertEquals("on", s.all()[EmuKey.Thermal])
        assertEquals("off", s.game("Banjo")!![EmuKey.Thermal])
        assertEquals("on", s.game("Tooie")!![EmuKey.Thermal])
        // Gone from Video; a game's file that held only it goes too, the rest stay.
        val all = JSONObject(File(video, "all.json").readText())
        assertFalse(all.has("thermal")); assertEquals("2", all.getString("resolution"))
        assertFalse(File(video, "games/Banjo.json").exists())
        assertEquals("16:9", JSONObject(File(video, "games/Tooie.json").readText()).getString("aspect"))
        // Opening it again changes nothing.
        EmuStore(File(tmp.root, "emulation"), video)
        assertEquals("on", s.all()[EmuKey.Thermal])
    }

    @Test fun autoPicks() {
        assertEquals("recompiler", EmuAuto.cpuCore("NGUJ"))
        assertEquals("recompiler", EmuAuto.cpuCore("NSME"))
        // Every game at its original rate (2026-11-14): Auto is Normal for the four that were faster too.
        for (g in listOf("NGEE", "NKTE", "NFUE", "NYSE")) assertEquals("normal", EmuAuto.overclock(g, true))
        assertEquals("normal", EmuAuto.overclock("NKTE", false))
        assertEquals("normal", EmuAuto.overclock("NSME", true))
        assertNull(EmuAuto.overclock("NKTE", null))
    }

    /** The popups' Auto lines copy the host's lists: they must stay the same. */
    @Test fun autoListsMatchTheHost() {
        val cpp = File("src/main/cpp/libretro_host.cpp").readText()
        fun list(name: String) = Regex("""$name\[\]\s*=\s*\{([^}]*)\}""").find(cpp)!!.groupValues[1]
            .split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }.toSet()
        // The host picks the recompiler for every game: no interpreter list there, none here.
        assertFalse(cpp.contains("kInterpreterGames"))
        assertTrue(EmuAuto.INTERPRETER_GAMES.isEmpty())
        // No game gets a faster CPU on its own (original frame rates): no list there, none here.
        assertFalse(cpp.contains("kSmootherGames"))
        assertTrue(EmuAuto.SMOOTHER_GAMES.isEmpty())
    }
}
