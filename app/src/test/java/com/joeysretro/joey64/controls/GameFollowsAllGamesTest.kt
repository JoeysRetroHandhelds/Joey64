package com.joeysretro.joey64.controls

import com.joeysretro.joey64.touch.TouchStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A change on All games reaches every game; a game keeps only the rows it changed (Joey 2026-11-17: Stick feel's Range
 * and Precise on All games didn't reach games with their own controls, they showed as changes in Game settings).
 */
class GameFollowsAllGamesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun feel(s: ControlsSet, range: Int, curve: StickCurve) =
        s.with(0, s.players[0].copy(feel = s.players[0].feel.copy(range = range, curve = curve)))

    @Test fun allGamesChangesReachAGameWithItsOwnControls() {
        val store = ControlsStore(tmp.root)
        val all = ControlsSet.defaults()
        store.saveAll(all)
        // The game changes its Z button only.
        val ed = ControlsEditor(store, "game", { it })
        ed.set(0, ed.shown.players[0].with(N64Button.Z, PadInput.Key(99)))
        // Then All games gets Range 90 and Precise.
        store.saveAll(feel(all, 90, StickCurve.Precise))
        val g = store.forGame("game").players[0]
        assertEquals(90, g.feel.range)
        assertEquals(StickCurve.Precise, g.feel.curve)
        assertEquals(PadInput.Key(99), g.buttons[N64Button.Z])
        assertEquals(1, ControlsEditor(store, "game", { it }).changeCount("game"))
    }

    @Test fun aGamesOwnRowStaysItsOwn() {
        val store = ControlsStore(tmp.root)
        store.saveAll(ControlsSet.defaults())
        val ed = ControlsEditor(store, "game", { it })
        ed.set(0, ed.shown.players[0].copy(feel = ed.shown.players[0].feel.copy(curve = StickCurve.Quick)))
        store.saveAll(feel(ControlsSet.defaults(), 80, StickCurve.Precise))
        val g = store.forGame("game").players[0]
        assertEquals(StickCurve.Quick, g.feel.curve)
        assertEquals(80, g.feel.range)
    }

    @Test fun aGameBackToAllGamesLosesItsFile() {
        val store = ControlsStore(tmp.root)
        store.saveAll(ControlsSet.defaults())
        val ed = ControlsEditor(store, "game", { it })
        ed.set(0, ed.shown.players[0].with(N64Button.Z, PadInput.Key(99)))
        assertTrue(store.gamesWithOwn().contains("game"))
        ed.resetRow(0, "Z")
        assertFalse(store.gamesWithOwn().contains("game"))
    }

    @Test fun aFileFromBeforeKeepsOnlyItsNonDefaultChanges() {
        val store = ControlsStore(tmp.root)
        val d = ControlsSet.defaults()
        // An old whole copy: made when All games was the default, with Z changed; no "own".
        val old = d.with(0, d.players[0].with(N64Button.Z, PadInput.Key(99)))
        File(tmp.root, "games").mkdirs()
        File(File(tmp.root, "games"), "game.json").writeText(old.toJson().toString())
        store.saveAll(feel(d, 90, StickCurve.Precise))
        val g = store.forGame("game").players[0]
        assertEquals(StickCurve.Precise, g.feel.curve)
        assertEquals(90, g.feel.range)
        assertEquals(PadInput.Key(99), g.buttons[N64Button.Z])
    }

    @Test fun touchControlsFollowAllGamesToo() {
        val store = TouchStore(File(tmp.root, "touch"))
        val base = com.joeysretro.joey64.touch.TouchSettings()
        store.saveAll(base)
        store.saveGame("game", base.copy(opacity = 30))
        store.saveAll(base.copy(sensitivity = 150))
        val g = store.forGame("game")
        assertEquals(30, g.opacity)
        assertEquals(150, g.sensitivity)
        store.saveGame("game", store.forGame("game").copy(opacity = store.all().opacity))
        assertNull(store.game("game"))
        assertTrue(JSONObject(base.toJson().toString()).length() > 0)
    }
}
