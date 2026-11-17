package com.joeysretro.joey64.achievements

import android.content.Context
import com.joeysretro.joey64.BuildConfig
import com.joeysretro.joey64.JLog
import java.io.File

/**
 * Whether RetroAchievements Hardcore is on for the game being played, for what it locks (RetroAchievements' hardcore
 * compliance requirements): loading states (slots, undo), rewind, slow motion, frame advance, cheats, macros and the
 * GameShark button, and Settings › Video's 60 fps codes. Widescreen codes stay (Joey 2026-11-16, option A).
 *
 * Part B (approved mockup 2026-11-16, scratchpad build_hcb.py, "Approve, A and A"): Settings › RetroAchievements'
 * Hardcore row ([RaOptions.hardcore], On for a new sign-in), the pause menu's Hardcore row (this play only), the start
 * banner, Continue starting fresh, "Play from this state?" for a picked state, and the pop-ups.
 *
 * [available]: only once RetroAchievements approves Joey64 ([APPROVED]); until then debug builds with files/dev_hardcore
 * (adb shell run-as com.joeysretro.joey64 touch files/dev_hardcore). A play wanted in Hardcore plays Casual when the user
 * picked a state to start from (Play from here in Casual), or in a netplay room; Continue and carrying on after a crash
 * start fresh instead (MainActivity `hardcoreFresh`). Debug builds also turn it on or off mid-game with
 * adb shell am broadcast -a com.joeysretro.joey64.HARDCORE --ez on true|false (on mid-game restarts the game).
 */
object RaHardcore {
    private const val TAG = "RaHardcore"

    /** RetroAchievements has approved Joey64 for Hardcore: the Settings row is unlocked for everyone. */
    const val APPROVED = false

    /** This play's mode. Outside a game: false. */
    @Volatile private var play = false

    fun on(): Boolean = play

    /** Hardcore can be picked: approved, or a debug build with the dev switch file. */
    fun available(context: Context): Boolean = APPROVED || (BuildConfig.DEBUG && File(context.filesDir, "dev_hardcore").exists())

    /** Whether the user wants Hardcore: [available], and Settings › RetroAchievements' row On. */
    fun wanted(context: Context): Boolean = available(context) && RaSettings.current(context).hardcore

    /**
     * A game starting, before its cheats and before it loads: the mode it plays in. [casualState]: it starts from a
     * state the user picked to play in Casual (or a start from a state that didn't ask: a frontend's).
     */
    fun startPlay(context: Context, casualState: Boolean, netplay: Boolean) {
        val want = wanted(context)
        play = want && !casualState && !netplay
        RaBridge.setHardcore(play)
        if (want && !play) JLog.i(TAG, "Hardcore: this play is Casual (${if (netplay) "a netplay room" else "it starts from a picked state"})")
        else JLog.i(TAG, "Hardcore: ${if (play) "on" else "off"} for this play")
    }

    /**
     * Mid-game (the pause menu's Hardcore row, approved option A; or the debug broadcast). On restarts the game, as
     * RetroAchievements requires: the host resets the core at its next frame (rcheevos' reset event) and drops cheats
     * but widescreen. Off goes on at once, for the rest of this play.
     */
    fun setMidGame(on: Boolean) {
        if (on == play) return
        play = on
        RaBridge.setHardcore(on)
        JLog.i(TAG, "Hardcore turned ${if (on) "on mid-game: the game restarts" else "off mid-game"}")
    }

    fun gameEnded() { play = false }
}
