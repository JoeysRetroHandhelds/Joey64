package com.joeysretro.joey64.achievements

import android.content.Context
import com.joeysretro.joey64.BuildConfig
import com.joeysretro.joey64.JLog
import java.io.File

/**
 * Whether RetroAchievements Hardcore is on for the game being played, for what it locks (RetroAchievements' hardcore
 * compliance requirements): loading states (Continue, slots, undo), rewind, slow motion, frame advance, cheats and the
 * GameShark button, and Settings › Video's Widescreen and 60 fps (hacks).
 *
 * Not reachable in a release yet: Hardcore is wanted only in a debug build with files/dev_hardcore present (adb shell
 * run-as com.joeysretro.joey64 touch files/dev_hardcore). Part B (Joey's mockups) gives it a Settings row and its
 * pop-ups. A play wanted in Hardcore still plays in Casual when it starts from a state (Continue, a slot, carrying on
 * after a crash) or in a netplay room. Debug builds turn it on or off mid-game with
 * adb shell am broadcast -a com.joeysretro.joey64.HARDCORE --ez on true|false (on mid-game restarts the game).
 */
object RaHardcore {
    private const val TAG = "RaHardcore"

    /** This play's mode. Outside a game: false. */
    @Volatile private var play = false

    fun on(): Boolean = play

    /** Whether the user wants Hardcore (for now only the debug switch file). */
    fun wanted(context: Context): Boolean = BuildConfig.DEBUG && File(context.filesDir, "dev_hardcore").exists()

    /** A game starting, before its cheats and before it loads: the mode it plays in. */
    fun startPlay(context: Context, fromState: Boolean, netplay: Boolean) {
        val want = wanted(context)
        play = want && !fromState && !netplay
        RaBridge.setHardcore(play)
        if (want && !play) JLog.i(TAG, "Hardcore: this play is Casual (${if (netplay) "a netplay room" else "it starts from a state"})")
        else JLog.i(TAG, "Hardcore: ${if (play) "on" else "off"} for this play")
    }

    /**
     * Mid-game (debug broadcast for now). On restarts the game, as RetroAchievements requires: the host resets the
     * core at its next frame (rcheevos' reset event) and drops cheats. Off goes on at once.
     */
    fun setMidGame(on: Boolean) {
        if (on == play) return
        play = on
        RaBridge.setHardcore(on)
        JLog.i(TAG, "Hardcore turned ${if (on) "on mid-game: the game restarts" else "off mid-game"}")
    }

    fun gameEnded() { play = false }
}
