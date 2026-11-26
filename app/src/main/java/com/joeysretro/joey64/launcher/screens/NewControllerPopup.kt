package com.joeysretro.joey64.launcher.screens

import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.controls.AskWhen
import com.joeysretro.joey64.controls.BuiltInTaken
import com.joeysretro.joey64.controls.ConnectSettings
import com.joeysretro.joey64.controls.ControlsStore
import com.joeysretro.joey64.controls.NewPadCard
import com.joeysretro.joey64.controls.NewPads
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/**
 * The new controller card (approved newpad3 mockup 2026-11-17, joey64-notes docs/img/newpad3.png, "Approve as drawn"),
 * in the launcher and over the paused game: Players 1-4 with who has each and what a pick does, the focused one first,
 * Not now, and Controls › Connecting's two rows at the foot, changed with ◀ ▶ (saved for the device at once; the rows
 * above follow). B is Not now.
 */
@Composable
fun NewControllerCard(card: NewPadCard, onSettings: (ConnectSettings) -> Unit, onPick: (Int) -> Unit) {
    var settings by remember { mutableStateOf(card.settings) }
    val view = remember(settings) { NewPadCard(card.set, card.pad, card.seen, card.layout, settings) }
    fun change(s: ConnectSettings) { settings = s; onSettings(s) }
    JoeyDialog(onDismiss = { onPick(-1) }, hints = listOf(Hint("A", "Select"), Hint("◀▶", "Change"), Hint("B", "Not now")), width = 540.dp) {
        Column {
            PopupTitle(view.title, if (card.seen) "Which player is it this time?"
                else (card.layout?.let { "Auto map knows it: $it." } ?: "Its buttons are set by where they sit.") + " Which player is it?")
            val c = JoeyTheme.colors
            view.rows.forEachIndexed { i, r ->
                JoeyRow(r.label, if (i == 0) Modifier.initialFocus() else Modifier, detail = r.detail,
                    trailing = { _ -> Text(r.who, style = JoeyTheme.type.note, color = c.textDim) }) { onPick(r.player) }
            }
            JoeyRow("Not now", detail = view.notNowDetail) { onPick(-1) }
            SectionLabel("For every controller")
            CycleRow("When a controller connects", null, settings.ask.label) { step ->
                change(settings.copy(ask = AskWhen.entries[(settings.ask.ordinal + step + AskWhen.entries.size) % AskWhen.entries.size]))
            }
            CycleRow("Built-in controls", "When a controller takes their player", settings.builtIn.label) { step ->
                change(settings.copy(builtIn = BuiltInTaken.entries[(settings.builtIn.ordinal + step + BuiltInTaken.entries.size) % BuiltInTaken.entries.size]))
            }
        }
    }
}

/** A row whose value ◀ ▶ step through (A steps forward). */
@Composable
private fun CycleRow(label: String, detail: String?, value: String, onStep: (Int) -> Unit) {
    JoeyRow(label, Modifier.onPreviewKeyEvent { e ->
        val n = e.nativeKeyEvent
        val step = when (n.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
        if (step == 0) return@onPreviewKeyEvent false
        if (n.action == KeyEvent.ACTION_DOWN) onStep(step)
        true
    }, value = "◀ $value ▶", detail = detail, sideways = true) { onStep(1) }
}

/**
 * The launcher's side (approved newpad3 mockup, screens 3, 5 and 6): a controller that connects while the launcher is up,
 * or one never seen that was already connected when it opened, gets the card, one at a time, once no other card is open.
 * Not while [enabled] is false (first run, the Controls page: its editor holds All games and would write over the answer).
 */
@Composable
internal fun NewControllerHost(enabled: Boolean) {
    val context = LocalContext.current
    val store = remember { ControlsStore(context) }
    val cardsOpen = com.joeysretro.joey64.launcher.components.LocalPopupHost.current?.entries?.size ?: 0
    // Device ids waiting, with whether each connected while the launcher was up.
    var queue by remember { mutableStateOf(listOf<Pair<Int, Boolean>>()) }
    var shown by remember { mutableStateOf<Pair<Int, NewPadCard>?>(null) }
    fun offer(id: Int, event: Boolean) {
        if (queue.none { it.first == id } && shown?.first != id) queue = queue + (id to event)
    }
    DisposableEffect(Unit) {
        val im = context.getSystemService(InputManager::class.java)
        val l = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(id: Int) = offer(id, true)
            override fun onInputDeviceRemoved(id: Int) {
                queue = queue.filter { it.first != id }
                if (shown?.first == id) shown = null
            }
            override fun onInputDeviceChanged(id: Int) {}
        }
        im?.registerInputDeviceListener(l, Handler(Looper.getMainLooper()))
        for (id in InputDevice.getDeviceIds()) offer(id, false)
        onDispose { im?.unregisterInputDeviceListener(l) }
    }
    LaunchedEffect(queue, cardsOpen, enabled, shown) {
        if (shown != null || !enabled || cardsOpen > 0) return@LaunchedEffect
        while (queue.isNotEmpty()) {
            val (id, event) = queue.first()
            // A controller that just connected settles first: AYN's input service adds a short-lived copy of it (another
            // descriptor, gone ~80 ms later) before the one that stays, and that copy got a "New controller" card
            // (Odin 3 Portal, 2026-11-26). Its removal takes it off the queue and restarts this.
            if (event) kotlinx.coroutines.delay(NewPads.SETTLE_MS)
            queue = queue.drop(1)
            val d = InputDevice.getDevice(id) ?: continue
            val card = NewPads.cardFor(store, d, event) ?: continue
            JLog.i(TAG, "controls: ${card.title}, asking which player (focus Player ${card.focus + 1})")
            shown = id to card
            break
        }
    }
    val (id, card) = shown ?: return
    androidx.compose.runtime.key(id) {
        NewControllerCard(card, { store.saveConnecting(it) }) { p ->
            NewPads.answer(store, card, InputDevice.getDevice(id), p)
            JLog.i(TAG, "controls: ${card.pad.name} " + if (p < 0) "not now" else "is player ${p + 1}")
            shown = null
        }
    }
}

private const val TAG = "Joey64Controls"
