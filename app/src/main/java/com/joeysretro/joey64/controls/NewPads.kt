package com.joeysretro.joey64.controls

import android.view.InputDevice

/**
 * When the new controller card shows, and what its answer saves (approved newpad3 mockup 2026-11-17). Shared by the launcher
 * ([com.joeysretro.joey64.launcher.screens.NewControllerHost]) and the game screen (MainActivity), which both read and
 * write `files/controls/` ([ControlsStore]).
 */
object NewPads {
    /**
     * The card for [d], or null when nothing is asked: not a controller, this device's own, Don't ask, a controller seen before
     * (unless Ask every time and it connected just now, [event]), or a phone's first controller (no built-in controls here:
     * it plays as the built-in player, as before).
     */
    fun cardFor(store: ControlsStore, d: InputDevice, event: Boolean): NewPadCard? {
        if (!PadDevices.isPad(d) || PadDevices.isBuiltIn(d)) return null
        val s = store.connecting()
        if (s.ask == AskWhen.Never) return null
        val seen = store.padSeen(d.descriptor)
        if (seen && !(s.ask == AskWhen.Every && event)) return null
        val all = store.all()
        if (PadDevices.builtIn() == null && all.players.any { it.device == DeviceChoice.BuiltIn }) return null
        return NewPadCard(all, DeviceChoice.Pad(d.descriptor, d.name ?: "Controller"), seen,
            PadDevices.layouts(d).firstOrNull()?.name, s)
    }

    /**
     * Saves the card's answer: [player] 0-3 gives it that player (Auto map's setup, the pak kept: a stick setting from the
     * built-in controls, such as a stickless device's D-pad, isn't the controller's), -1 is Not now (seen, so Ask once
     * doesn't ask again). Returns All games after a pick, or null.
     */
    fun answer(store: ControlsStore, shown: NewPadCard, d: InputDevice?, player: Int): ControlsSet? {
        if (player < 0) { store.markPadSeen(shown.pad.descriptor); return null }
        // The rows at the card's foot may have changed while it was up (saved at once): pick by them.
        val card = NewPadCard(shown.set, shown.pad, shown.seen, shown.layout, store.connecting())
        val old = card.set.players[player]
        val fresh = PadDevices.autoSetup(d, card.pad).copy(pak = old.pak, gbGame = old.gbGame, gbSave = old.gbSave)
        val out = card.pick(player, fresh)
        store.saveAll(out)
        return out
    }
}
