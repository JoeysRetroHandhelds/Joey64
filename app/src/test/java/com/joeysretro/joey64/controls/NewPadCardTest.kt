package com.joeysretro.joey64.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The new controller card's rows and picks (approved newpad3 mockup 2026-11-17). */
class NewPadCardTest {
    private val pad = DeviceChoice.Pad("desc-8bitdo", "8BitDo 64 BT")
    private val xbox = DeviceChoice.Pad("desc-xbox", "Xbox Wireless Controller")
    private val defaults = ControlsSet.defaults() // Player 1 built in, 2-4 None
    private fun card(set: ControlsSet = defaults, seen: Boolean = false, s: ConnectSettings = ConnectSettings(), p: DeviceChoice.Pad = pad) =
        NewPadCard(set, p, seen, "8BitDo 64", s)
    private fun fresh(set: ControlsSet = defaults) = set.players[0].copy(device = DeviceChoice.None)

    @Test fun aNewControllerFocusesPlayer1AndTheBuiltInControlsMove() {
        val c = card()
        assertEquals("New controller: 8BitDo 64 BT", c.title)
        assertEquals(0, c.focus)
        assertEquals(listOf(0, 1, 2, 3), c.rows.map { it.player })
        assertEquals("You play on it · the built-in controls move to Player 2", c.rows[0].detail)
        assertEquals("Built-in controls", c.rows[0].who)
        assertEquals("Plays alongside the built-in controls", c.rows[1].detail)
        assertEquals("Free", c.rows[1].who)
        assertEquals(1, c.movesTo(0))
    }

    @Test fun turnOffLeavesTheBuiltInControlsWithNoPlayer() {
        val c = card(s = ConnectSettings(builtIn = BuiltInTaken.Off))
        assertEquals("You play on it · the built-in controls turn off", c.rows[0].detail)
        assertNull(c.rows[1].detail)
        assertEquals(-1, c.movesTo(0))
        val out = c.pick(0, fresh())
        assertEquals(pad, out.players[0].device)
        assertTrue(out.players.none { it.device == DeviceChoice.BuiltIn })
    }

    @Test fun pickingPlayer1MovesTheBuiltInSetupToPlayer2() {
        val builtInSetup = defaults.players[0]
        val out = card().pick(0, fresh())
        assertEquals(pad, out.players[0].device)
        assertEquals(builtInSetup, out.players[1])
        assertEquals(DeviceChoice.None, out.players[2].device)
    }

    @Test fun aSecondControllerFocusesTheNextFreePlayer() {
        val set = card().pick(0, fresh()) // P1 8BitDo, P2 built in
        val c = card(set, p = xbox)
        assertEquals(2, c.focus)
        assertEquals(listOf(2, 0, 1, 3), c.rows.map { it.player })
        assertEquals("The next free player", c.rows[0].detail)
        assertEquals("Takes it from 8BitDo 64 BT, which moves to Player 3", c.rows[1].detail)
    }

    @Test fun aControllerSeenBeforeFocusesItsLastPlayer() {
        val set = defaults.with(2, defaults.players[2].copy(device = pad))
        val c = card(set, seen = true, s = ConnectSettings(ask = AskWhen.Every))
        assertEquals("8BitDo 64 BT connected", c.title)
        assertEquals(2, c.current)
        assertEquals(2, c.focus)
        assertEquals("Last time", c.rows[0].detail)
        assertEquals("Leave it unused this time", c.notNowDetail)
        assertEquals(set, c.pick(2, fresh()))
    }

    @Test fun pickingAnotherPlayerSwapsTheTwoSetups() {
        val set = defaults.with(2, defaults.players[2].copy(device = pad, deadZone = 30))
        val c = card(set, seen = true)
        assertEquals(2, c.movesTo(0))
        val out = c.pick(0, fresh())
        assertEquals(pad, out.players[0].device)
        assertEquals(30, out.players[0].deadZone)
        assertEquals(DeviceChoice.BuiltIn, out.players[2].device)
    }

    @Test fun aSwapOntoTheBuiltInPlayerWithTurnOffLeavesItsOldPlayerFree() {
        val set = defaults.with(2, defaults.players[2].copy(device = pad))
        val out = card(set, seen = true, s = ConnectSettings(builtIn = BuiltInTaken.Off)).pick(0, fresh())
        assertEquals(pad, out.players[0].device)
        assertEquals(DeviceChoice.None, out.players[2].device)
    }

    @Test fun settingsRoundTrip() {
        val s = ConnectSettings(AskWhen.Never, BuiltInTaken.Off)
        assertEquals(s, ConnectSettings.fromJson(s.toJson()))
        assertEquals(ConnectSettings(), ConnectSettings.fromJson(org.json.JSONObject()))
    }
}
