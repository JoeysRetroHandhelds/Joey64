package com.joeysretro.joey64.launcher.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.controls.Control
import com.joeysretro.joey64.launcher.controls.ControlBus
import com.joeysretro.joey64.saves.GameScreenshots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Saves | Screenshots (approved paksmenu mockup 2026-11-17, screen 2, "Approve as drawn"): a game's Saves page has a tab
 * row, and Screenshots, which was its own row in the game's options, is the second tab. Both pages are as they were.
 * L2 / R2 or the tab row change the tab, as on Settings; a game with no screenshots has the tab greyed "None" (it
 * doesn't open). [content] gets the hints to report (the page's own with L2 R2 Tab before B) and the tab row to draw.
 */
@Composable
fun SavesTabsHost(g: SavesGame, on: Int, onTab: (Int) -> Unit, onHints: (List<Hint>) -> Unit,
                  content: @Composable (hints: (List<Hint>) -> Unit, tabs: @Composable () -> Unit) -> Unit) {
    val context = LocalContext.current
    val shots by produceState<Int?>(null, g.saveName) {
        value = withContext(Dispatchers.IO) { GameScreenshots(context.applicationContext, g.saveName).list().size }
    }
    val count = shots ?: 0
    // Read through state, not captured: a reference to a local fun (`::go`) was memoized with the first
    // composition's count (0 before the screenshots were read), so L2 / R2 never opened Screenshots.
    val countNow by rememberUpdatedState(count)
    val onNow by rememberUpdatedState(on)
    val onTabNow by rememberUpdatedState(onTab)
    val go: (Int) -> Unit = { tab -> if (tab != onNow && (tab == 0 || countNow > 0)) onTabNow(tab) }
    // A pager, not a handler: ControlBus offers a press only to the newest handler, which is the page's own (pushed
    // after this one), while pagers are offered L2 / R2 first.
    DisposableEffect(Unit) {
        val pager: (Control) -> Boolean = { ctl ->
            when (ctl) {
                Control.PagePrev, Control.PageNext -> { go(1 - onNow); true }
                else -> false
            }
        }
        ControlBus.addPager(pager)
        onDispose { ControlBus.removePager(pager) }
    }
    val tab = Hint("L2 R2", "Tab")
    val hints: (List<Hint>) -> Unit = { h ->
        onHints(if (h.lastOrNull()?.key == "B") h.dropLast(1) + tab + h.last() else h + tab)
    }
    androidx.compose.runtime.CompositionLocalProvider(com.joeysretro.joey64.launcher.components.LocalTabsTakePageKeys provides true) {
        content(hints) {
            SettingsTabs(listOf("Saves", if (shots == null || count > 0) "Screenshots" else "Screenshots · None"), on,
                counts = listOf(null, count.takeIf { it > 0 })) { go(it) }
        }
    }
}
