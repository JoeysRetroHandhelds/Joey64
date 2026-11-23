package com.joeysretro.joey64.launcher.ingame

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyOption
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.PopupDivider
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.coverSelection
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.components.pressable
import com.joeysretro.joey64.launcher.components.rememberFocusState
import com.joeysretro.joey64.launcher.theme.DarkColors
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/** What the game screen shows over the paused game (plan, Phase 3). */
sealed interface PauseScreen {
    companion object {
        /** [Achievements.board] for the leaderboard timer: the board that's running. */
        const val RUNNING_BOARD = -1
    }
    /** The menu; [from] is the row focus goes back to (the one that opened the picker). */
    data class Menu(val from: MenuRow = MenuRow.Resume) : PauseScreen
    /**
     * The slot picker, for Save state ([save]) or Load state, over [slots] (Auto first, then Slots 1-10). [undo]: the undo
     * row's value under the slots (approved undomenu2 mockup 2026-11-23), null with nothing to undo (no row).
     */
    data class Picker(val save: Boolean, val slots: List<SlotView>, val focus: Int, val undo: String? = null) : PauseScreen {
        companion object {
            /** The picker's focus on the undo row (no slot has this number). */
            const val UNDO_ROW = -2
        }
    }
    /** Controls over the paused game (Phase 4, mockup 11), set to the game [saveName]. B goes back to the menu. */
    data class Controls(val saveName: String) : PauseScreen
    /** Paks over the paused game (approved paksmenu mockup 2026-11-17, screen 6), set to [saveName]. B goes back to the menu. */
    data class Paks(val saveName: String) : PauseScreen
    /** Cheats over the paused game (Phase 6) for [game]. B goes back to the menu; the game takes the changes on resume. */
    /** The game's achievements over the paused game (Phase 7), on the row with [focusId] when a tap opened it. B goes back to the menu. */
    /** [toGame]: opened by the Achievements hotkey, so B goes back to the game. */
    /** [board]: opened on the Leaderboards tab at that board ([RUNNING_BOARD]: the one running), from a leaderboard pop-up or the timer. */
    data class Achievements(val focusId: Int? = null, val toGame: Boolean = false, val board: Int? = null) : PauseScreen
    data class Cheats(val game: com.joeysretro.joey64.launcher.screens.CheatsGame, /** "Select + L3", or null with no hotkey for it. */ val gameSharkKeys: String? = null) : PauseScreen
    /** Game settings over the paused game (approved mockup 2026-11-07, screen 9) for [saveName]. B goes back to the menu. */
    data class GameSettings(val saveName: String) : PauseScreen
    /** Resuming after a row read only at start changed (screen 10): Restart now or Later. [subtitle] names the rows. */
    data class Restart(val subtitle: String) : PauseScreen
    /** RetroAchievements' sign-in, from a tap on the Sign-in expired pop-up; closes back to the game. */
    data object SignIn : PauseScreen
    /**
     * Controller disconnected (approved problem screens 2026-11-08, screen 4): [name]'s controller, [player]'s (0-3), went
     * and the game is paused until it comes back. [builtIn]: this device has built-in controls to play on instead.
     */
    data class Disconnected(val player: Int, val name: String, val builtIn: Boolean, /** No built-in controls: Use touch controls (player 1). */ val touch: Boolean = false,
        /** The controller wasn't there when the game started (approved newpad3 mockup 2026-11-17, screen 8): "<name> isn't connected". */
        val atStart: Boolean = false) : PauseScreen
    /** A controller connected (approved newpad3 mockup 2026-11-17, screens 2-5): which player it is. */
    data class NewPad(val card: com.joeysretro.joey64.controls.NewPadCard) : PauseScreen
    /** Not enough space to save (screen 5): [text] says what wasn't saved and how much room there is. OK goes back to the game. */
    data class NoSpace(val text: String) : PauseScreen
    /**
     * Netplay (approved mockup 2026-11-10, screen 1): [player]'s pause menu has the game stopped for everyone. No buttons;
     * it goes by itself when they're back. B opens this player's own menu.
     */
    data class NetPaused(val player: Int, /** From the room's list, null before it arrives. */ val name: String? = null) : PauseScreen
    /** Netplay screen 5: the games no longer match. Keep playing, or Leave the room ([host]: End for everyone). */
    data class NetOutOfStep(val host: Boolean) : PauseScreen
    /** Netplay, the host: [player] left (player-left popup, approved 2026-11-05). The game is paused for everyone while it's up. */
    data class NetLeft(val player: Int, val name: String? = null) : PauseScreen
    /**
     * Auto's driver trial (approved gputry mockup 2026-11-15, screens 1-2): [failed] didn't start parallel-RDP and the game
     * screen restarts with [next]. Over the (black) game, which keeps running; no rows, B quits to the library.
     * [speed]: a faster driver's try didn't start, and [next] is the one in use (approved drvspeed mockup, screen 3).
     */
    data class GpuTry(val failed: String, val next: String, val either: Boolean, val steps: List<GpuStep>, val progress: Float,
                      val speed: Boolean = false) : PauseScreen
    /** No driver worked (screens 3b-3c): starts with GLideN64, the game paused until answered. [reportsOn]: Send reports is on. */
    data class GpuNone(val steps: List<GpuStep>, val reportsOn: Boolean) : PauseScreen
    /** The pause menu's Hardcore row, Off to On (approved part B mockup 2026-11-16, screen 2): "Turn on Hardcore?", Cancel focused. */
    data object HardcoreOn : PauseScreen
}

/** One line of the driver trial's list: ✕ failed with its reason, ▶ starting now, · later, ✓ what runs. */
data class GpuStep(val mark: Mark, val text: String) { enum class Mark { Failed, Now, Later, Good } }

/** The driver trial cards' words and lists (approved gputry mockup 2026-11-15), from the chain's names and the trial's history. */
object GpuTrialCard {
    const val TRY_TITLE = "Getting parallel-RDP going on this device"
    const val NONE_TITLE = "parallel-RDP can't run on this device yet"
    const val GLIDE = "GLideN64"
    const val SPEED_TITLE = "Trying a faster driver"

    /** Screen 3 of the drvspeed mockup: [tried] showed no picture, the game screen starts again on [back]. */
    fun speedCard(tried: String, back: String) = PauseScreen.GpuTry(tried, back, false,
        listOf(GpuStep(GpuStep.Mark.Failed, "$tried: no picture"), GpuStep(GpuStep.Mark.Good, "$back: back to it")), 0.6f, speed = true)

    /** [names]: the chain's drivers; [history]: level to reason; [level]: this start's ([names].size: GLideN64). */
    fun steps(names: List<String>, history: List<Pair<Int, String>>, level: Int): List<GpuStep> {
        val none = level >= names.size
        val rows = names.mapIndexed { i, n ->
            val why = history.lastOrNull { it.first == i }?.second
            when {
                why != null -> GpuStep(GpuStep.Mark.Failed, "$n: $why")
                i == level -> GpuStep(GpuStep.Mark.Now, "$n: starting")
                else -> GpuStep(GpuStep.Mark.Later, n)
            }
        }
        return rows + if (none) GpuStep(GpuStep.Mark.Good, GLIDE) else GpuStep(GpuStep.Mark.Later, "$GLIDE, if none of them can")
    }

    /** The card for a start at [level] after failures; null on a first try, when nothing failed yet, or at GLideN64. */
    fun tryCard(names: List<String>, history: List<Pair<Int, String>>, level: Int): PauseScreen.GpuTry? {
        val last = history.lastOrNull() ?: return null
        if (level >= names.size) return null
        return PauseScreen.GpuTry(names.getOrElse(last.first) { "The driver" }, names[level], history.size >= 2,
            steps(names, history, level), (level + 0.5f) / (names.size + 1))
    }
}

/** What the Controller disconnected popup's rows do. */
enum class DisconnectChoice { BuiltIn, Touch, WithoutThem, Quit }

enum class MenuRow { Resume, Save, Load, GameSettings, Controls, Paks, Cheats, Hardcore, Achievements }

/** One cell of the slot picker. [savedAt] null: nothing in the slot. [picture]: the PNG beside the state, if any. */
data class SlotView(val slot: Int, val name: String, val savedAt: Long?, val picture: ImageBitmap?)

/** What the pause menu and the slot picker do. */
interface PauseActions {
    fun resume()
    fun openSave()
    fun openLoad()
    fun openGameSettings()
    /** Back from Game settings to the menu; what can change in game does now, the rest on Restart now or the next start. */
    fun closeGameSettings()
    /** The in-game Picture panel changed Shader, Sides or Overlay: the paused picture is drawn again with it. */
    fun pictureChanged() {}
    /** The menu's Game settings value: "3 changed" or "Global". */
    val gameSettingsLabel: String get() = ""
    fun restartNow()
    fun restartLater()
    fun openControls()
    /** Paks (approved paksmenu mockup 2026-11-17, screens 5-6): the card over the paused game. */
    fun openPaks() {}
    /** Back from Paks to the menu; Controller, Rumble Pak and None take effect when the game resumes. */
    fun closePaks() {}
    /** The menu's Paks value: "Rumble Pak", or "P1 Rumble · P2 Transfer". */
    val paksLabel: String get() = ""
    /** Back from Controls to the menu; what changed there takes effect when the game resumes. */
    fun closeControls()
    fun openCheats()
    /** Back from Cheats to the menu; the cheats on now are what the game runs. */
    fun closeCheats()
    fun openAchievements()
    fun closeAchievements()
    /** The sign-in popup closed (signed in, or B): back to the game. */
    fun closeSignIn()
    /** A row of the Controller disconnected popup. */
    fun disconnectChoice(choice: DisconnectChoice) {}
    /** The new controller card: a player (0-3), or -1 for Not now. */
    fun newPadPick(player: Int) {}
    /** The new controller card's Connecting rows changed (saved for the device). */
    fun newPadSettings(settings: com.joeysretro.joey64.controls.ConnectSettings) {}
    /** Not enough space to save: OK or B, back to the game. */
    fun closeNoSpace() {}
    /** In a netplay room: the menu greys Save state, Load state, Cheats and Reset, and Quit is Leave the room. */
    val inRoom: Boolean get() = false
    /** In a netplay room: the menu's line under its title (screen 2), "Players 1 and 3 are waiting for you". */
    val netLine: String? get() = null
    /** Netplay screen 1's B: this player's own pause menu. */
    fun netMenu() {}
    /** Netplay: Keep playing (out of step) or Carry on without them (player left). */
    fun netCarryOn() {}
    /** Netplay: Leave the room, or the host's End for everyone. */
    fun netLeave() {}
    /** The driver trial card's B: back to the library (the start doesn't count against the driver). */
    fun gpuQuit() {}
    /** The end card's rows: Start with GLideN64, or ([send]) send what happened first (Send reports off). */
    fun gpuStart(send: Boolean) {}
    /** Cheats' "Press GameShark button": back to the game with the button held for a second. */
    fun pressGameShark()
    fun reset()
    fun quit()
    /** The Quit row's name, from Controls > Hotkeys > Quit game ("Quit to library" or "Quit and exit Joey64"). */
    val quitLabel: String get() = "Quit to library"
    /**
     * RetroAchievements Hardcore for this play (approved part B mockup 2026-11-16, option A): null when the menu has no
     * Hardcore row (not available, not signed in, a netplay room), else whether it's on. On, Load state and Cheats are
     * locked and the hint row says why (approved 2026-11-04).
     */
    val hardcore: Boolean? get() = null
    /** The Hardcore row: On goes Off at once (back to the game with a pop-up), Off asks first ([PauseScreen.HardcoreOn]). */
    fun hardcoreRow() {}
    /** The question's answer: Restart in Hardcore ([restart]), or Cancel / B back to the menu. */
    fun hardcoreOn(restart: Boolean) {}
    /** Back from the picker to the menu. */
    fun backToMenu(from: MenuRow)
    fun pick(save: Boolean, slot: Int)
    /** The picker's undo row: Undo save state ([save]) or Undo load state; closes the menu and shows the hotkey's pop-up. */
    fun undo(save: Boolean) {}
}

/**
 * The pause menu on Back and the slot picker it opens, in one popup window whose contents change
 * (the game's popups' lesson: a window closed for the next one let the undimmed screen flash through).
 *  - **Menu** (approved mockup, 2026-11-05): the game's name, Resume, Save state with the slot it
 *    saves to, Load state, then Reset and Quit to library. Game settings, Controls, Cheats and
 *    Achievements join it as they're built. B and Back resume.
 *  - **Slot picker** (approved mockup): Auto then Slots 1-10 (then Auto 2 ..., Settings › Saves), four to a row as 100 x 75 dp pictures,
 *    the selected slot's name and date at the right of the title. Saving can't go to Auto (dimmed:
 *    quitting writes it). A on an empty slot when loading does nothing. B goes back to the menu.
 */
@Composable
fun PausePopup(screen: PauseScreen, title: String, saveSlotName: String, now: Long, actions: PauseActions) {
    if (screen is PauseScreen.GameSettings) {
        com.joeysretro.joey64.launcher.screens.InGameGameSettings(com.joeysretro.joey64.launcher.gamesettings.GameRef(screen.saveName, title), onPictureChanged = { actions.pictureChanged() }) { actions.closeGameSettings() }
        return
    }
    if (screen is PauseScreen.Controls) {
        com.joeysretro.joey64.launcher.screens.InGameControls(screen.saveName, title) { actions.closeControls() }
        return
    }
    if (screen is PauseScreen.Paks) {
        com.joeysretro.joey64.launcher.screens.PaksPopup(screen.saveName, com.joeysretro.joey64.launcher.components.gameName(title)) { actions.closePaks() }
        return
    }
    if (screen is PauseScreen.Cheats) {
        com.joeysretro.joey64.launcher.screens.InGameCheats(screen.game,
            com.joeysretro.joey64.launcher.screens.GameSharkRow(screen.gameSharkKeys) { actions.pressGameShark() }) { actions.closeCheats() }
        return
    }
    if (screen is PauseScreen.SignIn) {
        com.joeysretro.joey64.launcher.screens.InGameSignIn { actions.closeSignIn() }
        return
    }
    if (screen is PauseScreen.Achievements) {
        com.joeysretro.joey64.launcher.screens.InGameAchievements(title, screen.focusId, focusBoard = screen.board,
            backLabel = if (screen.toGame) "Back to the game" else "Back to pause menu") { actions.closeAchievements() }
        return
    }
    if (screen is PauseScreen.Disconnected) { DisconnectedPopup(screen, actions); return }
    if (screen is PauseScreen.NewPad) {
        androidx.compose.runtime.key(screen.card) {
            com.joeysretro.joey64.launcher.screens.NewControllerCard(screen.card, { actions.newPadSettings(it) }) { actions.newPadPick(it) }
        }
        return
    }
    if (screen is PauseScreen.NetPaused || screen is PauseScreen.NetOutOfStep || screen is PauseScreen.NetLeft) { NetplayPopup(screen, actions); return }
    if (screen is PauseScreen.GpuTry || screen is PauseScreen.GpuNone) { GpuTrialPopup(screen, actions); return }
    if (screen is PauseScreen.HardcoreOn) {
        // Takes the menu's place (one card at a time); Cancel and B go back to the menu's Hardcore row.
        JoeyDialog(onDismiss = { actions.hardcoreOn(false) }, hints = listOf(Hint("A", "Select"), Hint("B", "Cancel")), width = 470.dp) {
            Column {
                PopupTitle("Turn on Hardcore?", "${com.joeysretro.joey64.launcher.components.gameName(title)} restarts from the start. " +
                    "Anything not saved in the game is lost. Your save states stay.")
                com.joeysretro.joey64.launcher.components.JoeyRow("Cancel", Modifier.initialFocus(), detail = "Keep playing in Casual") { actions.hardcoreOn(false) }
                com.joeysretro.joey64.launcher.components.JoeyRow("Restart in Hardcore") { actions.hardcoreOn(true) }
            }
        }
        return
    }
    if (screen is PauseScreen.NoSpace) {
        JoeyDialog(onDismiss = { actions.closeNoSpace() }, hints = listOf(Hint("A", "OK"), Hint("B", "Back to game")), width = 470.dp) {
            Column {
                PopupTitle(com.joeysretro.joey64.saves.NoSpace.TITLE, screen.text)
                JoeyOption("OK", Modifier.initialFocus()) { actions.closeNoSpace() }
            }
        }
        return
    }
    val picker = screen as? PauseScreen.Picker
    val restart = screen as? PauseScreen.Restart
    val hardcore = androidx.compose.runtime.remember(screen) { actions.hardcore }
    // The slot the picker's focus is on: Auto while saving has no A (it's dimmed, quitting writes it).
    var pickerAt by androidx.compose.runtime.remember(picker) { androidx.compose.runtime.mutableIntStateOf(picker?.focus ?: -1) }
    val hints = when {
        restart != null -> listOf(Hint("A", "Select"), Hint("B", "Later"))
        picker == null -> listOf(Hint("A", "Select"), Hint("B", "Resume"))
        pickerAt == PauseScreen.Picker.UNDO_ROW -> listOf(Hint("A", "Undo"), Hint("B", "Back"))
        picker.save && com.joeysretro.joey64.states.StateSlots.isAuto(pickerAt) -> listOf(Hint("D-pad", "Move"), Hint("B", "Back"))
        else -> listOf(Hint("A", if (picker.save) "Save" else "Load"), Hint("D-pad", "Move"), Hint("B", "Back"))
    }
    JoeyDialog(
        onDismiss = { if (restart != null) actions.restartLater() else actions.resume() },
        hints = hints,
        interceptBack = { if (picker != null) { actions.backToMenu(if (picker.save) MenuRow.Save else MenuRow.Load); true } else false },
        // Five slot pictures to a row once older quit saves make it longer than three rows of four.
        width = when { restart != null -> 470.dp; picker == null -> 300.dp; picker.slots.size > 12 -> 564.dp; else -> 454.dp },
        wrap = screen is PauseScreen.Menu,
        // Hardcore on: the hint row says why Load state and Cheats are locked (approved 2026-11-04).
        hintEnd = if (screen is PauseScreen.Menu && hardcore == true) "Loading a state is off while Hardcore is on. Saving still works." else null,
    ) {
        val landing = LocalFocusLanding.current
        LaunchedEffect(screen) { landing?.arm() }
        when (screen) {
            is PauseScreen.Menu -> Column {
                if (hardcore == null) PopupTitle(com.joeysretro.joey64.launcher.components.gameName(title))
                else Row(verticalAlignment = Alignment.Top) {
                    // The play's mode at the title's right (approved 2026-11-04; part B mockup 2026-11-16, screens 2 and 6).
                    Column(Modifier.weight(1f)) { PopupTitle(com.joeysretro.joey64.launcher.components.gameName(title)) }
                    Text(if (hardcore) "Hardcore" else "Casual", style = JoeyTheme.type.note,
                        color = if (hardcore) JoeyTheme.colors.gold else JoeyTheme.colors.textDim,
                        modifier = Modifier.padding(end = JoeyTheme.spacing.rowPadH, top = 6.dp))
                }
                // Netplay screen 2: only the line is new; the menu is today's.
                val netLine = androidx.compose.runtime.remember(screen) { actions.netLine }
                if (netLine != null) Text(netLine, style = JoeyTheme.type.note, color = JoeyTheme.colors.gold,
                    modifier = Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH).padding(bottom = 6.dp))
                val room = androidx.compose.runtime.remember(screen) { actions.inRoom }
                // The rows scroll under the title: on a short screen (the Thor at 1080p) the card ran out of
                // room and squeezed the last row, Quit, to a thin line with no text. Focus brings a row into view.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                JoeyOption("Resume", Modifier.initialFocus(screen.from == MenuRow.Resume)) { actions.resume() }
                // Netplay screen 6: greyed in a room, with the pop-up's words.
                if (room) {
                    NetBlockedRow("Save state")
                    NetBlockedRow("Load state")
                } else {
                JoeyOption("Save state", Modifier.initialFocus(screen.from == MenuRow.Save), value = "$saveSlotName  ▸") { actions.openSave() }
                if (hardcore == true) com.joeysretro.joey64.launcher.components.JoeyRow("Load state", Modifier.initialFocus(screen.from == MenuRow.Load),
                    value = "›", enabled = false, lock = true) {}
                else JoeyOption("Load state", Modifier.initialFocus(screen.from == MenuRow.Load), value = "›") { actions.openLoad() }
                }
                PopupDivider()
                JoeyOption("Game settings", Modifier.initialFocus(screen.from == MenuRow.GameSettings),
                    value = androidx.compose.runtime.remember(screen) { actions.gameSettingsLabel } + "  ›") { actions.openGameSettings() }
                JoeyOption("Controls", Modifier.initialFocus(screen.from == MenuRow.Controls), value = "›") { actions.openControls() }
                // Approved paksmenu mockup 2026-11-17, screen 5: after Controls; nothing else moves.
                JoeyOption("Paks", Modifier.initialFocus(screen.from == MenuRow.Paks),
                    value = androidx.compose.runtime.remember(screen) { actions.paksLabel } + "  ›") { actions.openPaks() }
                if (room) NetBlockedRow("Cheats")
                else if (hardcore == true) com.joeysretro.joey64.launcher.components.JoeyRow("Cheats", Modifier.initialFocus(screen.from == MenuRow.Cheats),
                    value = "›", enabled = false, lock = true) {}
                else JoeyOption("Cheats", Modifier.initialFocus(screen.from == MenuRow.Cheats), value = "›") { actions.openCheats() }
                // NEW in part B (option A): this play's Hardcore, only when it can be picked and outside a netplay room.
                if (hardcore != null) JoeyOption("Hardcore", Modifier.initialFocus(screen.from == MenuRow.Hardcore),
                    value = if (hardcore) "On  ›" else "Off  ›") { actions.hardcoreRow() }
                // Only when RetroAchievements knows the game (signed in, and it has a set).
                val hasSet = androidx.compose.runtime.remember(screen) { com.joeysretro.joey64.achievements.RaBridge.gameInfo() != "null" ||
                    com.joeysretro.joey64.achievements.RaBridge.debugCache != null }
                if (hasSet) JoeyOption("Achievements", Modifier.initialFocus(screen.from == MenuRow.Achievements), value = "›") { actions.openAchievements() }
                PopupDivider()
                if (room) NetBlockedRow("Reset") else JoeyOption("Reset") { actions.reset() }
                if (room) JoeyOption(com.joeysretro.joey64.netplay.NetplayWords.LEAVE) { actions.netLeave() }
                else JoeyOption(actions.quitLabel) { actions.quit() }
                }
            }
            is PauseScreen.Picker -> SlotGrid(screen, now, onFocus = { pickerAt = it }, onUndo = { actions.undo(screen.save) }) {
                actions.pick(screen.save, it) }
            // In Hardcore (approved mockup A 2026-11-16, joey64-notes hardcore-restart.png): the line says a restart goes
            // Casual, Later is focused and keeps the change for the next start.
            is PauseScreen.Restart -> if (hardcore == true) Column {
                // PopupTitle's layout, with the Casual part brighter as drawn.
                Column(Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH).padding(bottom = 6.dp)) {
                    Text("Restart to use the new settings?", style = JoeyTheme.type.popupTitle, color = JoeyTheme.colors.text)
                    Text(buildAnnotatedString {
                        append(screen.subtitle + " Hardcore can't go on from where you are: ")
                        withStyle(SpanStyle(color = JoeyTheme.colors.text, fontWeight = FontWeight.SemiBold)) {
                            append("restarting here plays the rest in Casual.")
                        }
                    }, style = JoeyTheme.type.note, color = JoeyTheme.colors.textDim)
                }
                com.joeysretro.joey64.launcher.components.JoeyRow("Later", Modifier.initialFocus(),
                    detail = "Stay in Hardcore · the new settings start next time") { actions.restartLater() }
                com.joeysretro.joey64.launcher.components.JoeyRow("Restart in Casual",
                    detail = "Keeps where you are · unlocks count as softcore for this play") { actions.restartNow() }
            } else Column {
                PopupTitle("Restart to use the new settings?", screen.subtitle)
                com.joeysretro.joey64.launcher.components.JoeyRow("Restart now", Modifier.initialFocus(),
                    detail = "Keeps where you are, then starts again from there") { actions.restartNow() }
                com.joeysretro.joey64.launcher.components.JoeyRow("Later", detail = "Keep playing as it is") { actions.restartLater() }
            }
            is PauseScreen.Controls, is PauseScreen.Paks, is PauseScreen.Cheats, is PauseScreen.Achievements, is PauseScreen.GameSettings, PauseScreen.SignIn,
            is PauseScreen.Disconnected, is PauseScreen.NewPad, is PauseScreen.NoSpace, is PauseScreen.NetPaused, is PauseScreen.NetOutOfStep, is PauseScreen.NetLeft,
            is PauseScreen.GpuTry, is PauseScreen.GpuNone, PauseScreen.HardcoreOn -> {}
        }
    }
}

@Composable
private fun SlotGrid(picker: PauseScreen.Picker, now: Long, onFocus: (Int) -> Unit, onUndo: () -> Unit, onPick: (Int) -> Unit) {
    val c = JoeyTheme.colors
    var focused by remember(picker) { mutableIntStateOf(picker.focus) }
    Column(Modifier.padding(horizontal = 2.dp)) {
        val sel = picker.slots.firstOrNull { it.slot == focused }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            PopupTitle(if (picker.save) "Save state" else "Load state")
            Spacer(Modifier.weight(1f))
            if (sel != null) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = c.text, fontWeight = FontWeight.Bold)) { append(sel.name) }
                        // Auto, dimmed when saving: says why (greyed items take focus, Joey 2026-11-12: "Focus them everywhere").
                        append(" · " + if (picker.save && com.joeysretro.joey64.states.StateSlots.isAuto(sel.slot)) "Made when you quit"
                            else (sel.savedAt?.let { SlotDates.label(it, now) } ?: "Empty"))
                    },
                    style = JoeyTheme.type.small, color = c.textDim,
                    modifier = Modifier.padding(end = 10.dp, bottom = 8.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp)) {
            // Older quit saves (Settings › Saves) make it longer than three rows of four: five to a row then.
            // The cells share the row's width (fixed 100 dp cells overflowed the popup: the last column came out narrower,
            // and "Auto save state 2" wrapped into its edge, Odin 2 Portal 2026-11-13).
            val perRow = if (picker.slots.size > 12) 5 else 4
            for (row in picker.slots.chunked(perRow)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (s in row) {
                        val off = picker.save && com.joeysretro.joey64.states.StateSlots.isAuto(s.slot)
                        SlotCell(s, off, now, initial = s.slot == picker.focus, onFocused = { focused = s.slot; onFocus(s.slot) },
                            modifier = Modifier.weight(1f)) {
                            if (picker.save || s.savedAt != null) onPick(s.slot)
                        }
                    }
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        // Approved undomenu2 mockup 2026-11-23: the window's undo, under the slots, only with something to undo (Down from
        // the bottom row reaches it). Its value: the save it puts back, or the slot it goes back to before.
        if (picker.undo != null) {
            PopupDivider()
            com.joeysretro.joey64.launcher.components.JoeyRow(
                if (picker.save) "Undo save state" else "Undo load state",
                value = picker.undo,
                detail = if (picker.save) "Puts back the save this slot had before" else "Back to where you were before loading",
                // The header keeps the last slot focused (mockup screen 3): only the hints change.
                onFocused = { if (it) onFocus(PauseScreen.Picker.UNDO_ROW) },
                onClick = onUndo,
            )
        }
    }
}

@Composable
private fun SlotCell(s: SlotView, off: Boolean, now: Long, initial: Boolean, onFocused: () -> Unit, modifier: Modifier = Modifier,
                     onClick: () -> Unit) {
    val c = JoeyTheme.colors
    val (source, focused) = rememberFocusState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .widthIn(max = 100.dp).height(75.dp)
            // The outline before the dimming: a dimmed cell takes focus too and its outline shows in full.
            .coverSelection(focused, 6.dp)
            .alpha(if (off) 0.35f else 1f)
            .clip(shape)
            .background(c.chip)
            .then(if (initial && !off) Modifier.initialFocus() else Modifier)
            // A dimmed cell is still a focus stop (as greyed rows are, Joey 2026-11-12); A does nothing on it.
            .pressable(source, onClick = { if (!off) onClick() }),
    ) {
        if (s.picture != null) {
            Image(s.picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            // The name over the picture, on a dark fade (light text in both themes: it sits on the game's picture).
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, DarkColors.surface.copy(alpha = 0.92f))))
                    .padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 4.dp),
            ) {
                Text(s.name, style = JoeyTheme.type.thumbLabel, color = if (focused) c.accent else DarkColors.text)
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.name, style = JoeyTheme.type.thumbLabel, color = if (focused && c.isDark) c.accent else c.text,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2,
                    modifier = Modifier.padding(horizontal = 4.dp))
                Spacer(Modifier.size(2.dp))
                // A state with no picture beside it (one RetroArch saved without): its date in place of "Empty".
                Text(s.savedAt?.let { SlotDates.label(it, now) } ?: "Empty", style = JoeyTheme.type.label, color = c.textDim,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

/**
 * Controller disconnected (approved problem screens, scratchpad build_problems2.py, "Approve as drawn" 2026-11-08): the
 * game is paused and carries on by itself when the controller comes back (MainActivity closes this then). Use built-in
 * controls when the device has them, else Use touch controls for player 1 (the touch controls show for now), Carry on without them for
 * players 2-4, Quit to library. B does nothing: the game waits.
 */
@Composable
private fun DisconnectedPopup(screen: PauseScreen.Disconnected, actions: PauseActions) {
    JoeyDialog(onDismiss = {}, hints = listOf(Hint("A", "Select")), width = 470.dp) {
        Column {
            var first = true
            fun firstFocus(): Modifier = if (first) { first = false; Modifier.initialFocus() } else Modifier
            if (screen.atStart) {
                // Approved newpad3 mockup 2026-11-17, screen 8: the same card when the controller wasn't there at the start.
                PopupTitle("${screen.name} isn't connected", "Player ${screen.player + 1}'s controller. Connect it and the game starts, or:")
                val now = "for this play · nothing saved"
                if (screen.builtIn) com.joeysretro.joey64.launcher.components.JoeyRow("Use built-in controls", firstFocus(),
                    detail = "Player ${screen.player + 1} plays on them $now") { actions.disconnectChoice(DisconnectChoice.BuiltIn) }
                else if (screen.touch) com.joeysretro.joey64.launcher.components.JoeyRow("Use touch controls", firstFocus(),
                    detail = "Player 1 plays on them $now") { actions.disconnectChoice(DisconnectChoice.Touch) }
                if (screen.player > 0) com.joeysretro.joey64.launcher.components.JoeyRow("Carry on without them", firstFocus(),
                    detail = "Player ${screen.player + 1} sits out $now") { actions.disconnectChoice(DisconnectChoice.WithoutThem) }
                com.joeysretro.joey64.launcher.components.JoeyRow(actions.quitLabel, firstFocus(), detail = "Back to the library") {
                    actions.disconnectChoice(DisconnectChoice.Quit) }
                return@Column
            }
            val text = androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = JoeyTheme.colors.text))
                append(screen.name); pop()
                append(" (Player ${screen.player + 1}) disconnected, so the game is paused. It carries on by itself when the controller comes back.")
            }
            PopupTitle("Controller disconnected")
            com.joeysretro.joey64.launcher.components.PromptText(text)
            if (screen.builtIn) JoeyOption("Use built-in controls", firstFocus()) { actions.disconnectChoice(DisconnectChoice.BuiltIn) }
            else if (screen.touch) JoeyOption("Use touch controls", firstFocus()) { actions.disconnectChoice(DisconnectChoice.Touch) }
            if (screen.player > 0) JoeyOption("Carry on without them", firstFocus()) { actions.disconnectChoice(DisconnectChoice.WithoutThem) }
            JoeyOption(actions.quitLabel, firstFocus()) { actions.disconnectChoice(DisconnectChoice.Quit) }
        }
    }
}

/** A pause menu row that does nothing in a netplay room (approved netplay mockup 2026-11-10, screen 6): greyed, with the pop-up's words. */
@Composable
private fun NetBlockedRow(label: String) =
    com.joeysretro.joey64.launcher.components.JoeyRow(label, enabled = false, detail = com.joeysretro.joey64.netplay.NetplayWords.BLOCKED) {}

/**
 * Auto's driver trial, said on screen (approved gputry mockup 2026-11-15, scratchpad build_gputry.py, "Approve as drawn"):
 *  - **Getting parallel-RDP going on this device** (screens 1-2): which driver didn't start and which one the game screen
 *    restarts with, the list with each driver's result, and a bar. Nothing to press; B quits to the library.
 *  - **parallel-RDP can't run on this device yet** (3b-3c): every driver failed, so GLideN64; Start with GLideN64, and with
 *    Send reports off also Send what happened, then start (this one report only).
 */
@Composable
private fun GpuTrialPopup(screen: PauseScreen, actions: PauseActions) {
    val c = JoeyTheme.colors
    val bold = SpanStyle(fontWeight = FontWeight.SemiBold, color = c.text)
    when (screen) {
        is PauseScreen.GpuTry -> JoeyDialog(onDismiss = { actions.gpuQuit() }, hints = listOf(Hint("B", "Quit to library")), width = 470.dp) {
            Column {
                PopupTitle(if (screen.speed) GpuTrialCard.SPEED_TITLE else GpuTrialCard.TRY_TITLE)
                com.joeysretro.joey64.launcher.components.PromptText(buildAnnotatedString {
                    if (screen.speed) {
                        withStyle(bold) { append(screen.failed) }; append(" didn't start. Going back to ")
                        withStyle(bold) { append(screen.next) }; append("…")
                        return@buildAnnotatedString
                    }
                    if (screen.either) { withStyle(bold) { append(screen.failed) }; append(" didn't start either. Restarting with ") }
                    else { append("parallel-RDP didn't start with "); withStyle(bold) { append(screen.failed) }; append(". Restarting with ") }
                    withStyle(bold) { append(screen.next) }
                    append("…")
                })
                GpuSteps(screen.steps)
                Box(Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 10.dp).fillMaxWidth().height(4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(c.separator)) {
                    Box(Modifier.fillMaxWidth(screen.progress.coerceIn(0f, 1f)).height(4.dp).background(c.accent))
                }
                // Something has to hold focus so B reaches the dialog; nothing to press (as NetPaused).
                Box(Modifier.size(1.dp).initialFocus().clearAndSetSemantics { }.pressable(rememberFocusState().first, onClick = {}))
            }
        }
        is PauseScreen.GpuNone -> JoeyDialog(onDismiss = {}, hints = listOf(Hint("A", if (screen.reportsOn) "Start" else "Select")), width = 470.dp) {
            Column {
                PopupTitle(GpuTrialCard.NONE_TITLE)
                com.joeysretro.joey64.launcher.components.PromptText(buildAnnotatedString {
                    append("None of the drivers got a picture from it. Starting with ")
                    withStyle(bold) { append(GpuTrialCard.GLIDE) }
                    append(", which runs most games well.")
                    if (screen.reportsOn) append("\nA report of what happened goes with your next report, so we can look at it.")
                })
                GpuSteps(screen.steps)
                JoeyOption("Start with GLideN64", Modifier.initialFocus()) { actions.gpuStart(false) }
                if (!screen.reportsOn) JoeyOption("Send what happened, then start") { actions.gpuStart(true) }
            }
        }
        else -> {}
    }
}

@Composable
private fun GpuSteps(steps: List<GpuStep>) {
    val c = JoeyTheme.colors
    Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp)) {
        for (s in steps) {
            val (mark, colour) = when (s.mark) {
                GpuStep.Mark.Failed -> "✕" to c.error
                GpuStep.Mark.Now -> "▶" to c.accent
                GpuStep.Mark.Later -> "·" to c.textFaint
                GpuStep.Mark.Good -> "✓" to c.textSecondary
            }
            Text("$mark  ${s.text}", color = colour, style = JoeyTheme.type.small)
        }
    }
}

/**
 * The in-game netplay popups (approved mockup 2026-11-10, scratchpad build_netplay3.py, "Approve as drawn"):
 *  - **Player N paused the game** (screen 1), on every other screen while that player's menu is open: no rows, B opens this
 *    player's own menu; MainActivity takes it away when they're back.
 *  - **The games are out of step** (screen 5), over the paused game: Keep playing, Leave the room (the host: End for everyone).
 *  - **Player N left the game** (the host; the player-left popup approved 2026-11-05): Carry on without them, Leave the room.
 *    Its Wait row waits for rejoin (after the first testing build, Joey 2026-11-10).
 */
@Composable
private fun NetplayPopup(screen: PauseScreen, actions: PauseActions) {
    val w = com.joeysretro.joey64.netplay.NetplayWords
    when (screen) {
        is PauseScreen.NetPaused -> JoeyDialog(onDismiss = { actions.netMenu() }, hints = listOf(Hint("B", "Menu")), width = 470.dp) {
            Column {
                PopupTitle(w.pausedTitle(screen.player))
                com.joeysretro.joey64.launcher.components.PromptText(buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = JoeyTheme.colors.text)) { append(screen.name ?: "They") }
                    append(w.PAUSED_TEXT)
                })
                // Something has to hold focus so B reaches the dialog; nothing to press.
                // TalkBack skips it: the clear comes before pressable, so pressable's button goes too (code audit 3, M33).
                Box(Modifier.size(1.dp).initialFocus().clearAndSetSemantics { }.pressable(rememberFocusState().first, onClick = {}))
            }
        }
        is PauseScreen.NetOutOfStep -> JoeyDialog(onDismiss = {}, hints = listOf(Hint("A", "Select")), width = 470.dp) {
            Column {
                PopupTitle(w.OUT_OF_STEP_TITLE)
                com.joeysretro.joey64.launcher.components.PromptText(androidx.compose.ui.text.AnnotatedString(w.OUT_OF_STEP_TEXT))
                JoeyOption("Keep playing", Modifier.initialFocus()) { actions.netCarryOn() }
                JoeyOption(if (screen.host) w.END_FOR_EVERYONE else w.LEAVE) { actions.netLeave() }
            }
        }
        is PauseScreen.NetLeft -> JoeyDialog(onDismiss = {}, hints = listOf(Hint("A", "Select")), width = 470.dp) {
            Column {
                PopupTitle(w.leftTitle(screen.player, screen.name))
                com.joeysretro.joey64.launcher.components.PromptText(androidx.compose.ui.text.AnnotatedString(w.leftText(screen.player)))
                JoeyOption("Carry on without them", Modifier.initialFocus()) { actions.netCarryOn() }
                JoeyOption(w.LEAVE) { actions.netLeave() }
            }
        }
        else -> {}
    }
}
