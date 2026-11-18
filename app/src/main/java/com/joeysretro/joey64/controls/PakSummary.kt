package com.joeysretro.joey64.controls

/**
 * The Paks row's value in a game's options and the pause menu (approved paksmenu mockup 2026-11-17, joey64-notes
 * docs/img/paksmenu.png, Joey: "Approve as drawn"): the pak player 1 gets ("Rumble Pak"), or each player's when the
 * players with a controller differ ("P1 Rumble · P2 Transfer").
 */
object PakSummary {
    /** [paks]: each player's pak as the game gets it (Auto resolved), null for a player with no controller. */
    fun label(paks: List<Pak?>): String {
        val on = paks.withIndex().filter { it.value != null }
        if (on.isEmpty()) return Pak.None.label
        if (on.all { it.value == on[0].value }) return on[0].value!!.label
        return on.joinToString(" · ") { (i, p) -> "P${i + 1} ${short(p!!)}" }
    }

    private fun short(p: Pak) = when (p) {
        Pak.Auto -> "Auto"; Pak.Controller -> "Controller"; Pak.Rumble -> "Rumble"; Pak.Transfer -> "Transfer"; Pak.None -> "None"
    }

    /**
     * What a player's Transfer Pak loads at a game's start: its Game Boy game and save, "" when it has none. The game
     * screen keeps each player's at start; a change in game asks to restart (mockup screen 7), since the Game Boy game
     * loads only then.
     */
    fun transferKey(p: PlayerSetup): String {
        val game = p.gbGame
        if (p.device == DeviceChoice.None || p.pak != Pak.Transfer || game == null) return ""
        return game.uri + "|" + (p.gbSave?.uri ?: "")
    }
}
