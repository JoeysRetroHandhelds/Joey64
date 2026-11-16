package com.joeysretro.joey64.video

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * Reads whether the screen runs at 100 Hz or more into [VideoKey.highRefresh] (Frame pacing's default follows it): the
 * fastest of the screen's modes at its current size, not the rate it happens to run at now, which on screens that
 * change their rate (60 Hz while idle, 120 Hz in motion) gave a different default from one start to the next (review
 * 2026-11-15).
 */
object ScreenRefresh {
    fun update(ctx: Context) {
        val d = ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val now = d?.mode
        val hz = d?.supportedModes?.filter { now == null || (it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight) }
            ?.maxOfOrNull { it.refreshRate } ?: d?.refreshRate ?: 0f
        VideoKey.highRefresh = hz >= 100f
    }
}
