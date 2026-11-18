package com.joeysretro.joey64.controls

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Paks row's value and the Transfer Pak's start key (approved paksmenu mockup 2026-11-17). */
class PakSummaryTest {
    @Test fun onePakForEveryoneIsItsName() {
        assertEquals("Rumble Pak", PakSummary.label(listOf(Pak.Rumble, null, null, null)))
        assertEquals("Controller Pak", PakSummary.label(listOf(Pak.Controller, Pak.Controller, null, null)))
    }

    @Test fun differentPaksArePerPlayer() {
        assertEquals("P1 Rumble · P2 Transfer", PakSummary.label(listOf(Pak.Rumble, Pak.Transfer, null, null)))
        assertEquals("P1 Rumble · P3 None", PakSummary.label(listOf(Pak.Rumble, null, Pak.None, null)))
    }

    @Test fun noControllersIsNone() {
        assertEquals("None", PakSummary.label(listOf(null, null, null, null)))
    }

    @Test fun transferKeyOnlyForATransferPakWithAGame() {
        val gb = GameBoyFiles.Found("content://gb/blue", "Pokemon Blue.gb")
        val sav = GameBoyFiles.Found("content://gb/blue.sav", "Pokemon Blue.sav")
        val p = ControlsSet.defaults().players[0]
        assertEquals("", PakSummary.transferKey(p.copy(pak = Pak.Rumble, gbGame = gb)))
        assertEquals("", PakSummary.transferKey(p.copy(pak = Pak.Transfer)))
        assertEquals("", PakSummary.transferKey(p.copy(pak = Pak.Transfer, gbGame = gb, device = DeviceChoice.None)))
        assertEquals("content://gb/blue|", PakSummary.transferKey(p.copy(pak = Pak.Transfer, gbGame = gb)))
        assertEquals("content://gb/blue|content://gb/blue.sav", PakSummary.transferKey(p.copy(pak = Pak.Transfer, gbGame = gb, gbSave = sav)))
    }
}
