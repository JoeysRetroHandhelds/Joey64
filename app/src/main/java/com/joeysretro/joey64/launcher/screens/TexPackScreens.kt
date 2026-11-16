package com.joeysretro.joey64.launcher.screens

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PanelChoice
import com.joeysretro.joey64.launcher.components.PanelInfo
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import com.joeysretro.joey64.video.TexPack
import com.joeysretro.joey64.video.TexPackStore
import com.joeysretro.joey64.video.TexPacks
import com.joeysretro.joey64.video.TexConvert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Video › Texture packs (approved mockup 2026-11-15, scratchpad build_texpacks3.py, "Approve as drawn"; the data
 * side is video/TexturePacks.kt). All games: On / Off and the pack folders (screen 1), A on a folder lists its packs
 * (screen 2). One game: Auto (the pack found for it), each of its packs when it has more than one, None, with the two
 * pictures taken in play and the pack's file, the name it's made for and its folder (screen 3).
 */

/** What the Video tab knows about texture packs: the folders, the packs found, which library game each name is. */
data class TexState(
    val folders: List<TexPackStore.Folder>,
    val index: List<TexPack>,
    /** Pack name (upper-case) to the library's titles for it. */
    val games: Map<String, List<String>>,
    /** The game in the chip's pack name (null: All games, or not read yet). */
    val packName: String?,
    /** PNG packs' conversions as last read (by pack id), so the panel reads no files while it draws. */
    val conv: Map<String, TexConv> = emptyMap(),
    /** The folders were looked through for this state (not only the last scan's index read). */
    val scanned: Boolean = false,
) {
    fun gameFor(p: TexPack): String? = games[p.packName.uppercase()]?.firstOrNull()
    fun forGame() = TexPacks.forGame(index, packName)
    companion object { val EMPTY = TexState(emptyList(), emptyList(), emptyMap(), null) }
}

/** A PNG pack's conversion as last read: the .hts once whole (its name and size), and whether an older core left it for the game to make. */
data class TexConv(val htsName: String?, val htsSize: Long?, val gameMakesIt: Boolean)

/** Reads the folders and packs (scanning when [rescan] or there's no index yet), the library's names, and [target]'s own. Off the main thread. */
fun readTexState(context: Context, target: String?, rescan: Boolean): TexState {
    val store = TexPackStore(context)
    val cached = if (rescan) null else store.index()
    val index = cached ?: store.scan().first
    val db = com.joeysretro.joey64.launcher.data.LibraryDb.get(context)
    val library = try { db.libraryGames().filter { !it.packName.isNullOrEmpty() } } catch (e: Exception) { emptyList() }
    val games = library.groupBy({ it.packName!!.uppercase() }, { it.title })
    val name = target?.let { t -> try { db.gameWithFile(t, com.joeysretro.joey64.states.GameStates::saveNameOf)?.packName } catch (e: Exception) { null } }
    // PNG packs for library games are converted in the background (approved mockup texpacks4).
    TexConvert.kick(context)
    TexConvert.refresh(context)
    val conv = index.filter { it.png }.associate { p ->
        val hts = store.converted(p)?.takeIf { TexPacks.htsWhole(it) }
        p.id to TexConv(hts?.name, hts?.length(), store.isCopied(p))
    }
    return TexState(store.folders(), index, games, name, conv, scanned = cached == null)
}

/** Whether [p] is a PNG pack for a library game still to be converted (its bar shows; the screens poll the status meanwhile). */
fun TexState.converting(p: TexPack) = p.png && gameFor(p) != null && conv[p.id]?.let { it.htsSize == null && !it.gameMakesIt } != false

/** Polls the conversion's status while shown (once a second), so its bar and lines move. */
@Composable
fun TexConvPoll(on: Boolean) {
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(on) {
        while (on) {
            withContext(Dispatchers.IO) { TexConvert.refresh(context) }
            kotlinx.coroutines.delay(1000)
        }
    }
}

/** The folder card's line for a PNG pack being converted: "converting, 42%" (approved mockup texpacks4 screen 4), or null. */
fun texConvShort(s: TexState, p: TexPack): String? {
    if (!s.converting(p)) return null
    val st = TexConvert.status.value
    val cur = st?.cur?.takeIf { st.fresh && it.id == p.id }
    return when {
        cur != null -> "converting, ${cur.percent}%"
        st?.room?.containsKey(p.id) == true -> "waits for room"
        else -> "converting"
    }
}

/** A pack's File line: a PNG pack's .hts once made (texpacks4 screen 2), else the folder of PNGs, or the .hts as it is. */
private fun fileLine(s: TexState, p: TexPack): String {
    if (!p.png) return "${p.file} · ${TexPacks.sizeLabel(p.size)}"
    val c = s.conv[p.id]
    return if (c?.htsName != null && c.htsSize != null) "${c.htsName} · ${TexPacks.sizeLabel(c.htsSize)}, made from the PNG folder"
        else "A folder of PNGs · ${TexPacks.sizeLabel(p.size)}"
}

/**
 * Under a PNG pack's details while it converts (approved mockup texpacks4, screens 2 and 3): the step and how far, a bar,
 * what it's doing, and that it stops while you play (or the game it waits for); no room, in orange.
 */
@Composable
private fun TexConvBar(s: TexState, p: TexPack) {
    val c = JoeyTheme.colors
    val st = TexConvert.status.value
    val pad = Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH)
    val cur = st?.cur?.takeIf { st.fresh && it.id == p.id }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)) {
        val room = st?.room?.get(p.id)
        val failed = st?.failed?.get(p.id)
        when {
            cur != null -> {
                Text(androidx.compose.ui.text.buildAnnotatedString {
                    append("Converting · step ${cur.step} of 2 · ")
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = c.accent)); append("${cur.percent}%"); pop()
                }, style = JoeyTheme.type.small, color = c.text, modifier = pad)
                Box(pad.padding(vertical = 3.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.separator)) {
                    Box(Modifier.fillMaxWidth(cur.percent / 100f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.accent))
                }
                Text(if (cur.step == 1) "Copying the PNGs · ${gb(cur.done)} of ${gb(cur.total)}"
                    else "Making the pack file · ${"%,d".format(cur.done)} of ${"%,d".format(cur.total)} textures",
                    style = JoeyTheme.type.small, color = c.textDim, modifier = pad)
                if (cur.playing != null) Text("Waiting: you're playing ${cur.playing}", style = JoeyTheme.type.small, color = c.accent, modifier = pad)
                else Text("Stops while you play, then goes on", style = JoeyTheme.type.small, color = c.textDim, modifier = pad)
            }
            room != null -> Text("Needs ${TexPacks.sizeLabel(room.first)}, ${TexPacks.sizeLabel(room.second)} free. Goes on by itself once there's room.",
                style = JoeyTheme.type.small, color = c.warn, modifier = pad)
            failed != null -> Text("Can't convert it: $failed. Tried again when Joey64 next opens.", style = JoeyTheme.type.small, color = c.warn, modifier = pad)
            else -> Text("Converting · waits its turn. Stops while you play, then goes on", style = JoeyTheme.type.small, color = c.textDim, modifier = pad)
        }
    }
}

/** "1.3 GB" (one decimal from 1 GB, else MB), for the copy line. */
private fun gb(bytes: Long): String = if (bytes >= 1L shl 30) String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble()) else "${bytes shr 20} MB"

/** The All games panel (screen 1): On / Off, then PACK FOLDERS with each folder and Add a folder. */
fun texAllPanel(s: TexState, value: String, onSet: (String) -> Unit, onFolder: (TexPackStore.Folder) -> Unit, onAdd: () -> Unit): PanelInfo {
    val choices = ArrayList<PanelChoice>()
    choices.add(PanelChoice("On", "Every game with a pack uses it", on = value != "off"))
    choices.add(PanelChoice("Off", "Every game uses its own textures", on = value == "off"))
    for ((i, f) in s.folders.withIndex()) {
        val packs = s.index.filter { it.folder == f.uri }
        val mine = packs.count { s.gameFor(it) != null }
        choices.add(PanelChoice(f.name, "${packs.size} ${if (packs.size == 1) "pack" else "packs"}, $mine for your games",
            section = if (i == 0) "Pack folders" else null, value = "›"))
    }
    choices.add(PanelChoice("Add a folder", "Packs stay where they are: nothing is copied", section = if (s.folders.isEmpty()) "Pack folders" else null))
    return PanelInfo("Texture packs", "HD textures made for one game, drawn in place of its own. Joey64 reads GLideN64 packs (.hts, or a folder of PNGs) " +
        "from folders you pick and finds each game's by its name. parallel-RDP only. Applies when the game next starts.", choices) { i: Int ->
        when {
            i == 0 -> onSet("on")
            i == 1 -> onSet("off")
            i - 2 < s.folders.size -> onFolder(s.folders[i - 2])
            else -> onAdd()
        }
    }
}

/** The row's value: All games "On · 4 packs" / "Off"; a game the pack it plays with, "None", or "None for this game". */
fun texRowValue(s: TexState, value: String, game: Boolean): String = when {
    !game -> if (value == "off") "Off" else "On · ${s.index.size} ${if (s.index.size == 1) "pack" else "packs"}"
    value == "off" -> "None"
    else -> TexPacks.pick(s.index, s.packName, value)?.shown ?: "None for this game"
}

/** The one-game panel (screen 3): the pictures, Auto · its pack (and each pack when there's more than one), None, then the pack's details. */
fun texGamePanel(context: Context, s: TexState, value: String, target: String, onSet: (String) -> Unit): PanelInfo {
    val mine = s.forGame()
    val auto = TexPacks.pick(s.index, s.packName, "on")
    val inUse = TexPacks.pick(s.index, s.packName, value)
    val opts = ArrayList<Pair<String, PanelChoice>>()
    opts.add("on" to PanelChoice(if (auto != null) "Auto · ${auto.shown}" else "Auto", if (auto != null) "The pack made for this game" else "No pack for this game in your pack folders",
        on = value == "on" || (value.startsWith(TexPacks.PICK) && inUse == auto && mine.size < 2)))
    if (mine.size > 1) for (p in mine) opts.add(TexPacks.PICK + p.id to PanelChoice(p.shown, "${p.file} · ${TexPacks.sizeLabel(p.size)}",
        on = value.startsWith(TexPacks.PICK) && inUse == p))
    opts.add("off" to PanelChoice("None", "The game's own textures", on = value == "off"))
    val lines = inUse?.let { p ->
        listOfNotNull("File" to fileLine(s, p),
            "Made for" to (if (s.packName != null) "${p.packName} · this game's name" else p.packName),
            "Folder" to (s.folders.firstOrNull { it.uri == p.folder }?.name ?: "A folder no longer picked"))
    } ?: emptyList()
    val top: (@Composable () -> Unit)? = if (inUse == null) null else { { TexPictures(context, target, inUse) } }
    val bottom: (@Composable () -> Unit)? = if (inUse == null || !s.converting(inUse)) null else { { TexConvBar(s, inUse) } }
    return PanelInfo("Texture pack", if (mine.isEmpty()) "No pack for this game in your pack folders. Add a folder in All games." else
        "HD textures made for this game. Applies when the game next starts.", opts.map { it.second }, lines, onPick = { i -> onSet(opts[i].first) }, top = top, bottom = bottom)
}

/** Screen 3's two pictures: the game's newest screenshot and the frame kept with its pack, or the line before there are any. */
@Composable
private fun TexPictures(context: Context, target: String, p: TexPack) {
    val c = JoeyTheme.colors
    val shown = p.shown
    val pics by produceState<Pair<ImageBitmap?, ImageBitmap?>?>(null, target, p.id) {
        value = withContext(Dispatchers.IO) {
            val own = try {
                val g = com.joeysretro.joey64.saves.GameScreenshots(context, target)
                g.list().firstOrNull()?.let { g.picture(it.name, 320)?.first?.asImageBitmap() }
            } catch (e: Exception) { null }
            val pack = TexPackStore(context).shot(p, "pack").takeIf { it.isFile }?.let { f ->
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() }
            own to pack
        }
    }
    val (own, pack) = pics ?: return
    if (pack == null) {
        Text("Pictures after you play with it", style = JoeyTheme.type.note, color = c.textDim,
            modifier = Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH).padding(bottom = 6.dp))
        return
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = JoeyTheme.spacing.rowPadH).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((img, label) in listOf(own to "The game's own", pack to shown)) {
            Column(Modifier.weight(1f)) {
                if (img != null) Image(img, contentDescription = label, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(6.dp)))
                else Text("No screenshot yet", style = JoeyTheme.type.small, color = c.textDim, modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f))
                Text(label, style = JoeyTheme.type.small, color = c.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Screen 2: a pack folder's packs, each with its file, size and the library game it goes with (dimmed when none),
 * then Remove this folder, which asks first as ROM folders do. B closes.
 */
@Composable
fun TexFolderPopup(s: TexState, folder: TexPackStore.Folder, onDismiss: () -> Unit, onRemove: () -> Unit) {
    val c = JoeyTheme.colors
    var asking by remember { mutableStateOf(false) }
    if (asking) {
        JoeyDialog(onDismiss = { asking = false }, hints = listOf(Hint("A", "Select"), Hint("B", "Back")), width = 470.dp) {
            Column {
                PopupTitle("Remove ${folder.name}?", "Its packs stay on the SD card. Games stop using them.")
                JoeyRow("Keep it", Modifier.initialFocus(true)) { asking = false }
                JoeyRow("Remove this folder") { onRemove() }
            }
        }
        return
    }
    val packs = s.index.filter { it.folder == folder.uri }
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Back")), width = 560.dp) {
        Column {
            PopupTitle(folder.name, "Read only")
            if (packs.isEmpty()) Text("No texture packs found in this folder.", style = JoeyTheme.type.note, color = c.textDim,
                modifier = Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH, vertical = 6.dp))
            for ((i, p) in packs.withIndex()) {
                val game = s.gameFor(p)
                val detail = if (p.png) "A folder of PNGs · ${TexPacks.sizeLabel(p.size)}" + (texConvShort(s, p)?.let { " · $it" } ?: "")
                    else "${p.file} · ${TexPacks.sizeLabel(p.size)}"
                JoeyRow(p.shown, Modifier.initialFocus(i == 0), detail = detail, enabled = game != null,
                    trailing = { _ -> Text(game ?: "No game in your library", style = JoeyTheme.type.small,
                        color = if (game != null) c.accent else c.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }) {}
            }
            JoeyRow("Remove this folder", Modifier.initialFocus(packs.isEmpty()), detail = "The packs stay on the SD card") { asking = true }
        }
    }
}

/** Gives back a removed folder's grant unless another setting uses it, then rescans. */
internal fun removeTexFolder(context: Context, uri: String, done: () -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        TexConvert.stopFor(context, uri)
        TexPackStore(context).removeFolder(uri)
        com.joeysretro.joey64.launcher.storage.RomFolders.releaseIfUnused(context.applicationContext, uri)
        withContext(Dispatchers.Main) { done() }
    }
}

/** A narrow screen has no panel: the row's panel as a popup (its pictures, choices and lines). */
@Composable
fun TexInfoPopup(info: PanelInfo, onDismiss: () -> Unit) {
    val c = JoeyTheme.colors
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 520.dp) {
        Column {
            PopupTitle(info.title, info.help)
            info.top?.invoke()
            for ((i, ch) in info.choices.withIndex()) {
                ch.section?.let { Text(it.uppercase(), style = JoeyTheme.type.small, color = c.textDim,
                    modifier = Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH).padding(top = 6.dp)) }
                JoeyRow(ch.label, Modifier.initialFocus(ch.on), detail = ch.detail,
                    trailing = if (ch.on) { _ -> PickerTick() } else ch.value?.let { v -> { _ -> Text(v, style = JoeyTheme.type.small, color = c.textDim) } }) {
                    info.onPick?.invoke(i)
                }
            }
            for ((label, value) in info.lines) Text("$label: $value", style = JoeyTheme.type.small, color = c.textDim,
                modifier = Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH, vertical = 2.dp))
            info.bottom?.invoke()
        }
    }
}

/**
 * Screen 4: pause menu › Game settings › Video › Texture pack, down the right over the paused game (as the Picture panel):
 * Auto · its pack (and each pack when there's more than one) and None. The pack is read when the game starts, so leaving
 * Game settings asks "Restart to use the new settings?" (VideoKey.AT_START). No folder rows: Android's folder picker
 * isn't offered over a paused game.
 */
@Composable
internal fun TexPanel(saveName: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val store = remember { com.joeysretro.joey64.video.VideoStore(context) }
    var version by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") version
    val cur = remember(version) { com.joeysretro.joey64.video.VideoSettings.resolve(store.all(), store.game(saveName))[com.joeysretro.joey64.video.VideoKey.TexPack] }
    val s by produceState(TexState.EMPTY, saveName) { value = withContext(Dispatchers.IO) { readTexState(context, saveName, rescan = false) } }
    val info = texGamePanel(context, s, cur, saveName) { v ->
        store.saveGame(saveName, (store.game(saveName) ?: emptyMap()) + (com.joeysretro.joey64.video.VideoKey.TexPack to v)); version++
    }
    val c = JoeyTheme.colors
    JoeyDialog(onDismiss = onDone, sidePanel = true, width = 300.dp, hints = listOf(Hint("A", "Pick"), Hint("B", "Done"))) {
        Column {
            PopupTitle("Texture pack", "Game settings › Video · This game")
            for ((i, ch) in info.choices.withIndex()) {
                JoeyRow(ch.label, Modifier.initialFocus(ch.on), detail = ch.detail,
                    trailing = if (ch.on) { _ -> PickerTick() } else null) { info.onPick?.invoke(i) }
            }
            Text("Used when the game starts again. Leaving Game settings asks \"Restart to use the new settings?\" as other start-up rows do.",
                style = JoeyTheme.type.note, color = c.textDim, modifier = Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH, vertical = 8.dp))
        }
    }
}

/**
 * Screen 1 of texpacks4: the first time a PNG pack starts converting, once. Show it in notifications brings up Android's
 * own ask; Not now (or B) doesn't ask again, and the conversion runs the same.
 */
@Composable
fun TexNotifyAsk(p: TexPack, onShow: () -> Unit, onNotNow: () -> Unit) {
    JoeyDialog(onDismiss = onNotNow, hints = listOf(Hint("A", "Select"), Hint("B", "Not now")), width = 520.dp) {
        Column {
            PopupTitle("Converting ${p.shown}", "A folder of PNGs (${TexPacks.sizeLabel(p.size)}) is made into one pack file in Joey64's storage, " +
                "once. It takes a while and keeps going after you leave Joey64. It stops while you play and goes on after. " +
                "Joey64 can show how it's going in your notifications.")
            JoeyRow("Show it in notifications", Modifier.initialFocus(true), detail = "Then Android asks to allow Joey64's notifications") { onShow() }
            JoeyRow("Not now", detail = "It still converts: Settings › Video › Texture pack shows how far") { onNotNow() }
        }
    }
}
