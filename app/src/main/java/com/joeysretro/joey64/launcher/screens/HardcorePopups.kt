package com.joeysretro.joey64.launcher.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.initialFocus

/** How a game picked to start from a state starts with Hardcore wanted: in Casual from the state, or fresh in Hardcore. */
enum class HardcoreStart { Casual, Fresh }

/**
 * "Play from this state?" (approved part B mockup 2026-11-16, screen 5): Hardcore is wanted and the user picked a state to
 * start from (Saves › Play from here, or a slot in Play's "Pick where to start"). Hardcore doesn't load states, so it
 * asks: Play from here in Casual (focused; this play only), Start fresh in Hardcore, Cancel (null, B too).
 * [state]: "Slot 3 · Today, 2:02 pm". It takes the place of the popup it came from.
 */
@Composable
fun PlayFromStateCard(state: String, onAnswer: (HardcoreStart?) -> Unit) {
    JoeyDialog(onDismiss = { onAnswer(null) }, hints = listOf(Hint("A", "Select"), Hint("B", "Cancel")), width = 470.dp) {
        Column {
            PopupTitle("Play from this state?", "Hardcore doesn't load states. $state")
            JoeyRow("Play from here in Casual", Modifier.initialFocus(), detail = "This play only; Hardcore stays on for the next") {
                onAnswer(HardcoreStart.Casual)
            }
            JoeyRow("Start fresh in Hardcore", detail = "From power-on; in-game saves still load") { onAnswer(HardcoreStart.Fresh) }
            JoeyRow("Cancel") { onAnswer(null) }
        }
    }
}
