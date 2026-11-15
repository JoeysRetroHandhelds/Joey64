package com.joeysretro.joey64.video

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/** Reads the screen's current refresh rate into [VideoKey.highRefresh] (Frame pacing's default follows it). */
object ScreenRefresh {
    fun update(ctx: Context) {
        val hz = ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 0f
        VideoKey.highRefresh = hz >= 100f
    }
}
