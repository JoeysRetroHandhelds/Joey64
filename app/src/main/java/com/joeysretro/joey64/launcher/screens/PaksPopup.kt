package com.joeysretro.joey64.launcher.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.controls.ControlsEditor
import com.joeysretro.joey64.controls.ControlsStore
import com.joeysretro.joey64.controls.DeviceChoice
import com.joeysretro.joey64.controls.Pak
import com.joeysretro.joey64.controls.PakDb
import com.joeysretro.joey64.controls.PakSummary
import com.joeysretro.joey64.controls.PadDevices
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.data.LibraryDb
import com.joeysretro.joey64.states.GameStates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "Joey64Paks"

/** What the Paks card needs to know about a game: its Auto pak, whether it has no Rumble Pak support, its Transfer Pak games. */
private data class PakFacts(val md5: String?, val auto: Pak?, val cpOnly: Boolean, val tpakGroup: String?)

private fun pakFacts(context: Context, saveName: String): PakFacts = try {
    val md5 = LibraryDb.get(context).gameWithFile(saveName, GameStates::saveNameOf)?.md5
    PakFacts(md5, PakDb.auto(PakDb.load(context), md5), PakDb.controllerOnly(PakDb.loadControllerOnly(context), md5),
        com.joeysretro.joey64.controls.TransferPakDb.groupOf(com.joeysretro.joey64.controls.TransferPakDb.load(context), md5))
} catch (e: Exception) { JLog.e(TAG, "paks: can't look up $saveName: $e"); PakFacts(null, null, false, null) }

/** The Paks row's value for [saveName] (PakSummary.label), read off the main thread. */
internal fun paksLabel(context: Context, saveName: String): String {
    val set = ControlsStore(context).forGame(saveName)
    val md5 = pakFacts(context, saveName).md5
    val rumble = PakDb.load(context); val cpOnly = PakDb.loadControllerOnly(context)
    return PakSummary.label(set.players.map { p -> if (p.device == DeviceChoice.None) null else PakDb.resolve(p.pak, rumble, cpOnly, md5) })
}

private sealed interface PaksCard {
    data class Choice(val player: Int) : PaksCard
    data class Transfer(val player: Int, val row: String = "game") : PaksCard
    data class GbGame(val player: Int) : PaksCard
    data class GbSave(val player: Int) : PaksCard
    data class GbFolders(val player: Int, val games: Boolean) : PaksCard
}

/**
 * Paks (approved paksmenu mockup 2026-11-17, joey64-notes docs/img/paksmenu.png, "Approve as drawn"; screens 3, 4 and 6):
 * from a game's options in the library and the pause menu. One row per player: who holds that controller and the pak,
 * players with no controller greyed. A opens today's pak choices for that player in the card's place (one card at a
 * time), Transfer Pak goes on to today's Transfer Pak setup; B from them comes back to the player's row. Y on a row with
 * the dot (this game's own pak) is Use global. It changes the game's own controls, as Controls with the game in the chip.
 */
@Composable
fun PaksPopup(saveName: String, gameTitle: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ControlsStore(context) }
    val editor = remember(saveName) {
        ControlsEditor(store, saveName) { setup -> PadDevices.autoSetup(PadDevices.deviceFor(setup.device), setup.device, keep = null) }
    }
    var version by remember { mutableIntStateOf(0) }
    fun edit(block: () -> Unit) { block(); version++ }
    var facts by remember(saveName) { mutableStateOf<PakFacts?>(null) }
    LaunchedEffect(saveName) { facts = withContext(Dispatchers.IO) { pakFacts(context, saveName) } }
    var card by remember { mutableStateOf<PaksCard?>(null) }
    // The row focus comes back to from a card.
    var back by remember { mutableIntStateOf(0) }
    var focused by remember { mutableIntStateOf(0) }

    @Suppress("UNUSED_EXPRESSION") version
    val shown = editor.shown
    val f = facts
    val auto = f?.auto
    val cpOnly = f?.cpOnly == true
    fun toList(p: Int) { back = p; card = null }

    when (val k = card) {
        is PaksCard.Choice -> {
            val setup = shown.players[k.player]
            ChoicePopup("Player ${k.player + 1} pak", "What's in the back of the controller.", Pak.entries.map {
                val noRumble = it == Pak.Rumble && cpOnly
                Choice(pakValue(it, auto), if (noRumble) NO_RUMBLE else pakDetail(it), it == setup.pak, enabled = !noRumble)
            }, { toList(k.player) }) { i ->
                val pak = Pak.entries[i]
                edit { editor.set(k.player, setup.copy(pak = pak)) }
                JLog.i(TAG, "paks: $saveName player ${k.player + 1} -> ${pak.name}")
                // Transfer Pak opens its setup, also when it was already the pak (as on the Controls page).
                if (pak == Pak.Transfer) card = PaksCard.Transfer(k.player) else toList(k.player)
            }
            return
        }
        is PaksCard.Transfer -> {
            TransferSetupPopup(gameTitle, shown.players[k.player], k.row, { toList(k.player) },
                onGame = { card = PaksCard.GbGame(k.player) }, onSave = { card = PaksCard.GbSave(k.player) })
            return
        }
        is PaksCard.GbGame -> {
            val setup = shown.players[k.player]
            GameBoyListPopup(games = true, setup = setup, group = f?.tpakGroup, n64Title = gameTitle,
                onDismiss = { card = PaksCard.Transfer(k.player, "game") }, onFolders = { card = PaksCard.GbFolders(k.player, true) }) { game, save ->
                edit { editor.set(k.player, setup.copy(gbGame = game, gbSave = save)) }; card = PaksCard.Transfer(k.player, "game")
            }
            return
        }
        is PaksCard.GbSave -> {
            val setup = shown.players[k.player]
            GameBoyListPopup(games = false, setup = setup, group = f?.tpakGroup, n64Title = gameTitle,
                onDismiss = { card = PaksCard.Transfer(k.player, "save") }, onFolders = { card = PaksCard.GbFolders(k.player, false) },
                onDefaultSave = { save -> edit { editor.set(k.player, setup.copy(gbSave = save)) } }) { save, _ ->
                edit { editor.set(k.player, setup.copy(gbSave = save)) }; card = PaksCard.Transfer(k.player, "save")
            }
            return
        }
        is PaksCard.GbFolders -> {
            GameBoyFoldersPopup { card = if (k.games) PaksCard.GbGame(k.player) else PaksCard.GbSave(k.player) }
            return
        }
        null -> {}
    }

    JoeyDialog(
        onDismiss = onDismiss,
        hints = listOf(Hint("A", "Change"), Hint("Y", "Use global"), Hint("B", "Back")),
        onPadButton = { code ->
            if (code == android.view.KeyEvent.KEYCODE_BUTTON_Y && "Pak" in editor.changed(focused)) {
                edit { editor.resetRow(focused, "Pak") }
                JLog.i(TAG, "paks: $saveName player ${focused + 1} uses global")
            }
        },
        width = 470.dp,
    ) {
        val landing = LocalFocusLanding.current
        LaunchedEffect(Unit) { landing?.arm() }
        Column {
            PopupTitle("Paks", "This game's paks. A dot is this game's own; the rest follow Global. Rumble and strength stay in Controls.")
            for (i in 0..3) {
                val setup = shown.players[i]
                val none = setup.device == DeviceChoice.None
                val value = if (none) "None" else {
                    val v = if (setup.pak == Pak.Rumble && cpOnly) "Controller Pak · no rumble here" else pakValue(setup.pak, auto, setup)
                    "$v  ›"
                }
                JoeyRow("Player ${i + 1}", Modifier.initialFocus(i == back), value = value,
                    detail = if (none) "No controller" else deviceName(setup.device),
                    enabled = !none, dot = !none && "Pak" in editor.changed(i),
                    onFocused = { if (it) focused = i }) { if (!none) card = PaksCard.Choice(i) }
            }
        }
    }
}
