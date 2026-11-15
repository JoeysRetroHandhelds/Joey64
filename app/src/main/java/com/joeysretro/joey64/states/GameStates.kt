package com.joeysretro.joey64.states

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.NativeBridge
import com.joeysretro.joey64.launcher.storage.DataFolder
import com.joeysretro.joey64.launcher.storage.DataKind
import com.joeysretro.joey64.launcher.storage.FileSpace
import java.io.File
import java.io.IOException
import java.util.Properties

/**
 * The running game's save states (plan, Phase 3), in Joey64's `states/` folder under RetroArch's
 * names ([StateSlots]), so imported RetroArch states load and ours can go back. Every call here
 * blocks (the core's data is ~10-20 MB before compression): call it off the main thread.
 *
 * Note: the data folder is always Joey64's own storage for now, like the saves the core writes to
 * `files/saves` (DataFolder's note). This goes through [FileSpace], so a picked folder only
 * changes which [DataFolder] it is given.
 */
class GameStates(
    context: Context,
    /** The ROM's file name without extension, as the save is named ([saveNameOf]). */
    val saveName: String,
    private val space: FileSpace = com.joeysretro.joey64.launcher.storage.DataFolders.current(context).space,
) {
    private val recoveryDir = Recovery.dirOf(context, saveName)
    private val appContext = context.applicationContext
    /** A quit save waiting to go into the data folder ([writeQuit], [commitStaged]). */
    private val stagedDir = QuitStage.dirOf(context, saveName)

    private fun path(name: String) = listOf(DataKind.States.dir, name)

    /** Waits for this game's quit save still being written ([writeLater]), so what's read here is the newest. */
    private fun settled() { awaitWrites(saveName, WRITE_WAIT_MS) }

    fun exists(slot: Int): Boolean {
        settled()
        return synchronized(viewLock) {
            when (val from = shownFrom(slot)) {
                STAGED -> true
                NONE -> false
                else -> space.exists(path(StateSlots.fileName(saveName, from)))
            }
        }
    }

    /**
     * Where [slot] is read from now: while a quit save waits in [stagedDir] the folder's Auto is about to become Auto
     * 2 and so on, so the slots are shown as they'll be once it's in ([folderSlotWhileStaged]). Call under [viewLock].
     */
    private fun shownFrom(slot: Int): Int = QuitStage.read(stagedDir)?.let { folderSlotWhileStaged(slot, it.keep) } ?: slot

    // ---- Undo load state / Undo save state (approved mockup 2026-11-08, scratchpad build_undo.py) ----
    // One of each, in memory, until the game closes. Undo load: where the game was just before the last
    // load by hand. Undo save: the slot's file, picture and date just before the last save wrote over it.

    private class UndoLoad(val slot: Int, val mem: ByteArray, val achv: ByteArray?)
    internal class UndoSave(val slot: Int, val state: ByteArray?, val picture: ByteArray?, val savedAt: Long?)
    @Volatile private var undoLoad: UndoLoad? = null
    @Volatile private var undoSave: UndoSave? = null

    /** What Undo save state put back: the slot, and when its old save was from (null: the slot was empty). */
    class UndoneSave(val slot: Int, val savedAt: Long?)

    /** Goes back to just before the last load by hand: the slot that load was from, or null with nothing to undo. */
    fun undoLoad(): Int? {
        val u = undoLoad ?: return null
        val ok = NativeBridge.loadState(u.mem, u.achv, TIMEOUT_MS)
        if (!ok) { JLog.e(TAG, "undo load: FAILED"); return null }
        undoLoad = null
        JLog.i(TAG, "undo load: back to before ${StateSlots.label(u.slot)} was loaded")
        return u.slot
    }

    /** Puts the slot the last save wrote over back as it was, or null with nothing to undo. */
    fun undoSave(): UndoneSave? {
        val u = undoSave ?: return null
        val name = StateSlots.fileName(saveName, u.slot)
        val thumb = StateSlots.thumbnailName(saveName, u.slot)
        try {
            if (u.state == null) { space.delete(path(name)); space.delete(path(thumb)) }
            else {
                writeWhole(name, u.state)
                if (u.picture != null) writeWhole(thumb, u.picture) else space.delete(path(thumb))
                if (u.savedAt != null) { space.setModified(path(name), u.savedAt); if (u.picture != null) space.setModified(path(thumb), u.savedAt) }
            }
        } catch (e: IOException) {
            JLog.e(TAG, "undo save: can't put ${StateSlots.label(u.slot)} back: $e"); return null
        }
        undoSave = null
        JLog.i(TAG, "undo save: ${StateSlots.label(u.slot)} back to " + (u.savedAt?.toString() ?: "empty"))
        return UndoneSave(u.slot, u.savedAt)
    }

    /** How many bytes the last save or screenshot that couldn't be written needed (Not enough space to save); 0 after one that was. */
    @Volatile var failedNeed = 0L
        private set

    /**
     * Saves the game as it is now into [slot]. False if the core's state couldn't be taken or written. [taken] runs
     * once the moment is taken, before anything is written: the game can go on from then (the pause menu closes).
     */
    fun save(slot: Int, taken: () -> Unit = {}): Boolean {
        settled()
        failedNeed = 0
        val shot = take(slot) ?: return false
        taken()
        return write(slot, shot)
    }

    /**
     * One moment of the game, taken before anything is written: the core's state, its achievements' progress, its
     * picture as the core drew it (made a PNG while the state is written, [write]).
     */
    class Shot internal constructor(internal val data: ByteArray, internal val achv: ByteArray?, internal val capture: ByteArray?,
        internal val tookMs: Long)

    // Only what must be the same moment happens here: the state and the frame it holds (~25 ms on the Thor). Undo's
    // copy of the slot and the picture's PNG moved to [write] (2026-11-14, Joey: "saving states takes too long": with
    // them here a save held the game 0.2-0.4 s on the Thor at 4x).
    private fun take(slot: Int): Shot? {
        val t0 = SystemClock.elapsedRealtime()
        val data = NativeBridge.saveState(TIMEOUT_MS) ?: return null.also { JLog.e(TAG, "slot $slot: the core gave no state") }
        val achv = NativeBridge.stateProgress()
        // The same moment's picture (the game is paused or between frames, so it's the frame the state holds).
        val capture = NativeBridge.captureFrame(2000)
        return Shot(data, achv, capture, SystemClock.elapsedRealtime() - t0)
    }

    /**
     * Writes [shot] as [slot]'s state and picture, each whole or not at all. [aside]: under the slot's names plus
     * [ASIDE], for [saveQuit] to put in place once the slot is free.
     */
    private fun write(slot: Int, shot: Shot, aside: Boolean = false): Boolean {
        val t1 = SystemClock.elapsedRealtime()
        val end = if (aside) ASIDE else ""
        val name = StateSlots.fileName(saveName, slot) + end
        // The picture beside it, as RetroArch names it.
        val thumb = StateSlots.thumbnailName(saveName, slot) + end
        // What the slot held before, for Undo save state (a slot's own quit save rotation isn't kept); read before
        // anything of the slot is written.
        val before = if (StateSlots.isAuto(slot)) null else try {
            UndoSave(slot, readWhole(StateSlots.fileName(saveName, slot)),
                space.openRead(path(StateSlots.thumbnailName(saveName, slot)))?.use { it.readBytes() }, savedAt(slot))
        } catch (e: IOException) { null }
        val readMs = SystemClock.elapsedRealtime() - t1
        // The picture is made and written on its own thread while the state is packed and written (on an SD card
        // data folder each took 0.3-2 s, one after the other). It goes to a file of its own and takes the old one's
        // place only once the state is written, so a state that can't be written keeps its old picture.
        var png: ByteArray? = null
        var pictureWritten = false
        var pictureMs = 0L
        val pictureJob = Thread({
            val p0 = SystemClock.elapsedRealtime()
            png = shot.capture?.let { FramePicture.encodePng(it, PICTURE_MAX_WIDTH) }
            png?.let { bytes ->
                pictureWritten = try { space.openWrite(path(thumb + NEXT)).use { it.write(bytes) }; true } catch (e: IOException) {
                    JLog.e(TAG, "slot $slot: can't write its picture: $e"); false
                }
            }
            pictureMs = SystemClock.elapsedRealtime() - p0
        }, "joey64-statepicture").apply { start() }
        return try {
            val file = StateFormat.wrap(shot.data, shot.achv)
            val packMs = SystemClock.elapsedRealtime() - t1 - readMs
            failedNeed = file.size.toLong() + (shot.capture?.size ?: 0) / 8
            writeWhole(name, file)
            val stateMs = SystemClock.elapsedRealtime() - t1 - readMs - packMs
            pictureJob.join()
            // The old picture (RetroArch's, or ours) shows the moment before, so it goes even when there's no new one.
            // The picture is extra: the state is saved even when it can't be written.
            runCatching { space.delete(path(thumb)) }
            val pictureOk = pictureWritten && space.rename(path(thumb + NEXT), thumb)
            if (!pictureOk) {
                runCatching { space.delete(path(thumb + NEXT)) }
                if (png == null) JLog.e(TAG, "slot $slot: no picture from the core")
                else if (pictureWritten) JLog.e(TAG, "slot $slot: can't put its picture in place")
            }
            if (before != null) undoSave = before
            failedNeed = 0
            JLog.i(TAG, "saved $name: ${shot.data.size} bytes, ${file.size} written, picture ${png?.size ?: 0}; " +
                "taking it ${shot.tookMs} ms, then ${SystemClock.elapsedRealtime() - t1} ms " +
                "(undo copy $readMs, packing $packMs, the state $stateMs; the picture alongside $pictureMs)")
            true
        } catch (e: IOException) {
            pictureJob.join()
            runCatching { space.delete(path(thumb + NEXT)) }
            JLog.e(TAG, "can't write slot $slot" + "\n" + Log.getStackTraceString(e)); false
        }
    }

    /** A slot as the slot picker shows it: when it was saved (null: empty) and the picture beside it. */
    class SlotFile(val slot: Int, val savedAt: Long?, val picture: ByteArray?)

    /** When [slot] was saved, or null if it's empty. */
    fun savedAt(slot: Int): Long? {
        settled()
        return synchronized(viewLock) {
            val staged = QuitStage.read(stagedDir)
            when (val from = staged?.let { folderSlotWhileStaged(slot, it.keep) } ?: slot) {
                STAGED -> staged?.savedAt
                NONE -> null
                else -> {
                    val name = StateSlots.fileName(saveName, from)
                    space.list(listOf(DataKind.States.dir)).firstOrNull { it.name == name }?.modified
                }
            }
        }
    }

    /** Auto, then Slots 1-10, then the older quit saves there are (Auto 2 ...), read from the folder in one listing. */
    fun slotFiles(): List<SlotFile> {
        settled()
        return synchronized(viewLock) {
            val entries = space.list(listOf(DataKind.States.dir)).associateBy { it.name }
            val staged = QuitStage.read(stagedDir)
            fun from(slot: Int) = staged?.let { folderSlotWhileStaged(slot, it.keep) } ?: slot
            fun there(slot: Int) = when (val f = from(slot)) { STAGED -> true; NONE -> false; else -> StateSlots.fileName(saveName, f) in entries }
            val older = (2..StateSlots.AUTO_MAX).map { StateSlots.auto(it) }.filter { there(it) }
            (listOf(StateSlots.AUTO) + (1..StateSlots.COUNT) + older).map { slot ->
                when (val f = from(slot)) {
                    STAGED -> SlotFile(slot, staged!!.savedAt, staged.picture())
                    NONE -> SlotFile(slot, null, null)
                    else -> {
                        val state = entries[StateSlots.fileName(saveName, f)]
                        val thumb = StateSlots.thumbnailName(saveName, f)
                        val picture = if (state != null && thumb in entries) {
                            try { space.openRead(path(thumb))?.use { it.readBytes() } } catch (e: IOException) { null }
                        } else null
                        SlotFile(slot, state?.modified, picture)
                    }
                }
            }
        }
    }

    /**
     * The quit save: with [keep] above 1 the ones before move down (Auto to Auto 2, ...), and any past
     * [keep] go. [keep] 1: as [save]. One that can't be taken or written changes none of the kept ones
     * (code audit 3, M1: the oldest went and the rest moved down even then, which a quit while a netplay
     * room waited or a stuck game thread cost every time): the state is taken first and written beside
     * them, and only then do they move.
     */
    fun saveQuit(keep: Int): Boolean { val shot = takeQuit() ?: return false; return writeQuit(shot, keep) }

    /**
     * The quit save's moment, taken now (the core's state and picture, about 0.15 s); [writeQuit] writes it, which on
     * an SD card can take seconds (Thor 2026-11-13: 8 s), so quitting needn't wait for it ([writeLater]).
     */
    fun takeQuit(): Shot? { settled(); failedNeed = 0; return take(StateSlots.AUTO) }

    /**
     * Writes [takeQuit]'s [shot] as [saveQuit] does, in two steps (2026-11-14, Joey: "exiting a game and trying to get
     * back into that game or another has a delay": Play waited for this write, 1.5-8 s on the Thor's SD card). First
     * to Joey64's own storage ([QuitStage], a few tenths of a second): from then on it's the Auto save state everywhere
     * here, and Play waits only for this. Then [commitLater] puts it in the data folder on a thread of its own; a
     * process that ends first leaves it staged, and the next launcher or game start carries on ([commitAllLater]).
     */
    fun writeQuit(shot: Shot, keep: Int): Boolean {
        val k = keep.coerceIn(1, StateSlots.AUTO_MAX)
        val t0 = SystemClock.elapsedRealtime()
        val ok = synchronized(gameLock(saveName)) {
            // An earlier quit save of this game still waiting goes in first (two quits a few seconds apart).
            if (QuitStage.read(stagedDir) != null) commitStaged()
            // Staged as the core gave it: packing (deflate, 0.6-0.9 s on the Portal) waits for the commit.
            val png = shot.capture?.let { FramePicture.encodePng(it, PICTURE_MAX_WIDTH) }
            synchronized(viewLock) { QuitStage.write(stagedDir, shot.data, shot.achv, png, System.currentTimeMillis(), k) }
                .also { if (it) JLog.i(TAG, "quit save of $saveName staged: ${shot.data.size} bytes, picture ${png?.size ?: 0}; " +
                    "taking it ${shot.tookMs} ms, then ${SystemClock.elapsedRealtime() - t0} ms") }
        }
        if (ok) commitLater(appContext, saveName)
        // Joey64's own storage refused it (full): straight to the data folder, as before.
        return ok || writeQuitDirect(shot, k)
    }

    private fun writeQuitDirect(shot: Shot, k: Int): Boolean {
        if (k == 1 || !exists(StateSlots.AUTO)) {
            if (!write(StateSlots.AUTO, shot)) return false
            for (n in StateSlots.AUTO_MAX downTo k + 1) removeSlot(StateSlots.auto(n))
            return true
        }
        if (!write(StateSlots.AUTO, shot, aside = true)) return false
        for (n in StateSlots.AUTO_MAX downTo k) removeSlot(StateSlots.auto(n))
        for (n in k - 1 downTo 1) moveSlot(StateSlots.auto(n), StateSlots.auto(n + 1))
        if (putInPlace(StateSlots.AUTO)) return true
        unshift(k)
        return false
    }

    /**
     * A new auto save state that couldn't be put in place: the kept ones move back up, so each is where it was (review
     * 2026-11-15: only the newest moved back, the rest stayed one down, and each retry dropped one more).
     */
    private fun unshift(k: Int) {
        for (n in 1 until k) moveSlot(StateSlots.auto(n + 1), StateSlots.auto(n))
    }

    /**
     * Puts the staged quit save in the data folder, the kept ones moving down; the caller holds [gameLock]. The slow
     * part (writing it beside them, [ASIDE]) is outside [viewLock], so reads go on meanwhile; the renames are inside
     * it, so a read sees the slots before or after, never halfway. False when it's still staged (to try again later).
     */
    private fun commitStaged(): Boolean {
        val st = QuitStage.read(stagedDir) ?: return true
        val t0 = SystemClock.elapsedRealtime()
        val name = StateSlots.fileName(saveName, StateSlots.AUTO)
        val thumb = StateSlots.thumbnailName(saveName, StateSlots.AUTO)
        try {
            val bytes = StateFormat.wrap(st.mem(), st.achv())
            failedNeed = bytes.size.toLong()
            writeWhole(name + ASIDE, bytes)
            val png = st.picture()
            if (png == null) space.delete(path(thumb + ASIDE))
            else try { writeWhole(thumb + ASIDE, png) } catch (e: IOException) {
                JLog.e(TAG, "quit save of $saveName: can't write its picture: $e"); space.delete(path(thumb + ASIDE))
            }
            failedNeed = 0
        } catch (e: IOException) {
            JLog.e(TAG, "quit save of $saveName: can't put it in the data folder yet, kept staged: $e"); return false
        }
        val writeMs = SystemClock.elapsedRealtime() - t0
        synchronized(viewLock) {
            // The process is about to end (switchGame): the renames wait for the next start rather than be cut off.
            if (stopping) { JLog.i(TAG, "quit save of $saveName: written beside, put in place at the next start"); return false }
            val k = st.keep
            for (n in StateSlots.AUTO_MAX downTo k) removeSlot(StateSlots.auto(n))
            for (n in k - 1 downTo 1) moveSlot(StateSlots.auto(n), StateSlots.auto(n + 1))
            if (!putInPlace(StateSlots.AUTO)) {
                unshift(k)
                return false
            }
            space.setModified(path(name), st.savedAt)
            if (space.exists(path(thumb))) space.setModified(path(thumb), st.savedAt)
            QuitStage.clear(stagedDir)
        }
        JLog.i(TAG, "quit save of $saveName in the data folder: writing $writeMs ms, " +
            "all ${SystemClock.elapsedRealtime() - t0} ms")
        return true
    }

    /** [commitStaged] for this game, now (the Saves page's Delete, a backup, a data folder move). */
    fun commitNow(): Boolean = synchronized(gameLock(saveName)) { commitStaged() }

    /** Puts [write]'s aside files in [slot]'s place, which must be free; false (and the aside files gone) when it can't. */
    private fun putInPlace(slot: Int): Boolean {
        val name = StateSlots.fileName(saveName, slot)
        val thumb = StateSlots.thumbnailName(saveName, slot)
        if (!space.rename(path(name + ASIDE), name)) {
            JLog.e(TAG, "can't put the new $name in place")
            space.delete(path(name + ASIDE)); space.delete(path(thumb + ASIDE))
            return false
        }
        if (space.exists(path(thumb + ASIDE))) {
            space.delete(path(thumb))
            if (!space.rename(path(thumb + ASIDE), thumb)) JLog.e(TAG, "can't put the new $thumb in place")
        }
        return true
    }

    /** Deletes [slot]'s state and picture (the Saves page); true when it's gone. */
    fun deleteSlot(slot: Int): Boolean {
        settled()
        if (StateSlots.isAuto(slot)) commitNow()
        removeSlot(slot)
        return !exists(slot)
    }

    private fun removeSlot(slot: Int) {
        space.delete(path(StateSlots.fileName(saveName, slot)))
        space.delete(path(StateSlots.thumbnailName(saveName, slot)))
    }

    /** Renames [from]'s state and picture to [to]'s names ([to] must be free). */
    private fun moveSlot(from: Int, to: Int) {
        val a = StateSlots.fileName(saveName, from)
        if (!space.exists(path(a))) return
        removeSlot(to)
        if (!space.rename(path(a), StateSlots.fileName(saveName, to))) JLog.e(TAG, "can't move $a to ${StateSlots.label(to)}")
        val t = StateSlots.thumbnailName(saveName, from)
        if (space.exists(path(t))) space.rename(path(t), StateSlots.thumbnailName(saveName, to))
    }

    /**
     * Loads [slot] into the running game; right after start it waits for the core to be ready. [undoable]
     * (a load by hand: the hotkey or the pause menu): where the game was first is kept for Undo load state.
     */
    fun load(slot: Int, undoable: Boolean = false): Boolean {
        settled()
        val name = StateSlots.fileName(saveName, slot)
        // A staged quit save is the core's own data, not packed ([writeQuit]).
        var staged: Pair<ByteArray, ByteArray?>? = null
        val bytes = synchronized(viewLock) {
            when (val from = shownFrom(slot)) {
                STAGED -> { staged = try { QuitStage.read(stagedDir)?.let { it.mem() to it.achv() } } catch (e: IOException) { null }; null }
                NONE -> null
                else -> readWhole(StateSlots.fileName(saveName, from))
            }
        }
        if (bytes == null && staged == null) return false.also { JLog.e(TAG, "no $name") }
        val before = if (undoable) NativeBridge.saveState(TIMEOUT_MS)?.let { UndoLoad(slot, it, NativeBridge.stateProgress()) } else null
        val ok = staged?.let { (mem, achv) ->
            NativeBridge.loadState(mem, achv, TIMEOUT_MS).also {
                if (it) JLog.i(TAG, "load $name (staged): done (${mem.size} bytes)") else JLog.e(TAG, "load $name (staged): FAILED")
            }
        } ?: loadBytes(name, bytes!!)
        if (ok && before != null) undoLoad = before
        return ok
    }

    private fun loadBytes(name: String, bytes: ByteArray): Boolean {
        val parts = StateFormat.read(bytes) ?: return false.also { JLog.e(TAG, "$name isn't a state Joey64 can read (${bytes.size} bytes)") }
        val data = parts.mem
        val ok = NativeBridge.loadState(data, parts.achv, TIMEOUT_MS)
        val achv = parts.achv?.let { ", achievements ${it.size} bytes" } ?: ""
        if (ok) JLog.i(TAG, "load $name: done (${data.size} bytes$achv)") else JLog.e(TAG, "load $name: FAILED (${data.size} bytes)")
        return ok
    }

    /**
     * A screenshot into `screenshots/`, named like RetroArch's (`<save name>-yyMMdd-HHmmss.png`).
     * The file name, or null if the core had no picture or it couldn't be written.
     */
    fun screenshot(): String? {
        failedNeed = 0
        val png = FramePicture.capturePng() ?: return null.also { JLog.e(TAG, "screenshot: no picture from the core") }
        val stamp = java.text.SimpleDateFormat("yyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        var name = "$saveName-$stamp.png"
        var n = 1
        while (space.exists(listOf(DataKind.Screenshots.dir, name))) name = "$saveName-$stamp-${n++}.png"
        return try {
            failedNeed = png.size.toLong()
            space.openWrite(listOf(DataKind.Screenshots.dir, name)).use { it.write(png) }
            failedNeed = 0
            JLog.i(TAG, "screenshot $name (${png.size} bytes)")
            name
        } catch (e: IOException) {
            JLog.e(TAG, "can't write screenshot $name" + "\n" + Log.getStackTraceString(e)); null
        }
    }

    // ---- recovery state (approved 2026-11-05) ----------------------------------------------------
    // Written every few minutes while playing, apart from the slots and the auto slot, in Joey64's own
    // storage (it isn't the user's data). Quitting cleanly deletes it, so one still there on the next
    // start means Joey64 closed while a game was being played: the launcher offers to carry on.
    // Each game has its own (Joey 2026-11-12, F3): `files/recovery/games/<save name>/`, so quitting one
    // game cleanly, or playing another, never takes away a game's state still waiting to be offered.

    fun saveRecovery(): Boolean {
        val data = NativeBridge.saveState(TIMEOUT_MS) ?: return false
        val achv = NativeBridge.stateProgress()
        return try {
            recoveryDir.mkdirs()
            val file = File(recoveryDir, RECOVERY_STATE)
            val part = File(recoveryDir, "$RECOVERY_STATE.part")
            part.writeBytes(StateFormat.wrap(data, achv))
            if (!part.renameTo(file)) throw IOException("can't rename ${part.name}")
            val info = Properties().apply {
                setProperty("save_name", saveName)
                setProperty("saved_at", System.currentTimeMillis().toString())
            }
            val infoPart = File(recoveryDir, "$RECOVERY_INFO.part")
            infoPart.outputStream().use { info.store(it, null) }
            if (!infoPart.renameTo(File(recoveryDir, RECOVERY_INFO))) throw IOException("can't rename ${infoPart.name}")
            // Its picture, shown while the game starts again from it (MainActivity's held picture): none is no loss.
            val png = FramePicture.capturePng(maxWidth = PICTURE_MAX_WIDTH)
            val picPart = File(recoveryDir, "$RECOVERY_PICTURE.part")
            if (png != null) { picPart.writeBytes(png); if (!picPart.renameTo(File(recoveryDir, RECOVERY_PICTURE))) picPart.delete() }
            JLog.i(TAG, "recovery state written (${file.length()} bytes, picture ${png?.size ?: 0})")
            true
        } catch (e: IOException) {
            JLog.e(TAG, "can't write the recovery state" + "\n" + Log.getStackTraceString(e)); false
        }
    }

    fun loadRecovery(): Boolean {
        val f = File(recoveryDir, RECOVERY_STATE)
        val bytes = try { f.readBytes() } catch (e: IOException) { return false.also { JLog.e(TAG, "no recovery state") } }
        return loadBytes("recovery state", bytes)
    }

    /**
     * The picture of the state a start loads (MainActivity.EXTRA_LOAD_STATE): the quit save's or a slot's [slot], or
     * the recovery state's ([slot] null). Null when there's none (a recovery state from before pictures were kept).
     */
    fun startPicture(slot: Int?): ByteArray? = try {
        if (slot != null) settled()
        if (slot == null) File(recoveryDir, RECOVERY_PICTURE).takeIf { it.exists() }?.readBytes()
        else synchronized(viewLock) {
            when (val from = shownFrom(slot)) {
                STAGED -> QuitStage.read(stagedDir)?.picture()
                NONE -> null
                else -> space.openRead(path(StateSlots.thumbnailName(saveName, from)))?.use { it.readBytes() }
            }
        }
    } catch (e: IOException) { null }

    /** The game was quit cleanly: nothing to recover. */
    fun clearRecovery() = Recovery.clear(recoveryDir)

    // ---- files -------------------------------------------------------------------------------------

    /** Written whole or not at all: to `.part`, then put in place; the old file goes only once the new one is in. */
    private fun writeWhole(name: String, bytes: ByteArray) {
        val part = "$name.part"
        val old = "$name.old"
        space.openWrite(path(part)).use { it.write(bytes) }
        space.delete(path(old))
        if (space.exists(path(name)) && !space.rename(path(name), old)) throw IOException("can't move the old $name aside")
        if (!space.rename(path(part), name)) {
            space.rename(path(old), name)
            throw IOException("can't put $name in place")
        }
        space.delete(path(old))
    }

    /** The file, or the copy set aside by a write that was cut off before it finished. */
    private fun readWhole(name: String): ByteArray? =
        (space.openRead(path(name)) ?: space.openRead(path("$name.old")))?.use { it.readBytes() }

    /** A recovery state waiting to be offered, for the launcher (crash popup) and the Saves page. */
    data class Recovery(val saveName: String, val savedAt: Long, val size: Long = 0L) {
        companion object {
            /** The folder [saveName]'s recovery state lives in. */
            fun dirOf(context: Context, saveName: String) = dirIn(rootOf(context), saveName)
            private fun rootOf(context: Context) = File(context.filesDir, RECOVERY_DIR)
            internal fun dirIn(root: File, saveName: String) = File(File(root, GAMES_DIR), saveName)

            /** The newest waiting recovery state, of any game. */
            fun find(context: Context): Recovery? = allIn(rootOf(context)).firstOrNull()

            /** [saveName]'s waiting recovery state. */
            fun find(context: Context, saveName: String): Recovery? = findIn(rootOf(context), saveName)

            /** Deletes [saveName]'s recovery state only. */
            fun clear(context: Context, saveName: String) = clear(dirOf(context, saveName))

            /** Every game's waiting recovery state under [root], newest first. */
            internal fun allIn(root: File): List<Recovery> {
                moveOldOne(root)
                return (File(root, GAMES_DIR).listFiles() ?: emptyArray()).mapNotNull { read(it) }.sortedByDescending { it.savedAt }
            }

            internal fun findIn(root: File, saveName: String): Recovery? {
                moveOldOne(root)
                return read(dirIn(root, saveName))?.takeIf { it.saveName == saveName }
            }

            internal fun clear(dir: File) {
                File(dir, RECOVERY_STATE).delete()
                File(dir, RECOVERY_INFO).delete()
                File(dir, RECOVERY_PICTURE).delete()
                dir.delete()
            }

            private fun read(dir: File): Recovery? {
                val state = File(dir, RECOVERY_STATE)
                if (!state.isFile) return null
                val p = Properties()
                try { File(dir, RECOVERY_INFO).inputStream().use { p.load(it) } } catch (e: IOException) { return null }
                val name = p.getProperty("save_name") ?: return null
                return Recovery(name, p.getProperty("saved_at")?.toLongOrNull() ?: 0L, state.length())
            }

            /**
             * Builds before 2026-11-12 kept one recovery state for every game, in `files/recovery/` itself: it moves
             * (renamed, the bytes unchanged) into its game's own folder the first time one is looked for.
             */
            @Synchronized internal fun moveOldOne(root: File) {
                val state = File(root, RECOVERY_STATE)
                val info = File(root, RECOVERY_INFO)
                if (!state.isFile) return
                val p = Properties()
                val name = try { info.inputStream().use { p.load(it) }; p.getProperty("save_name") } catch (e: IOException) { null }
                if (name.isNullOrEmpty() || name.contains('/') || name == "." || name == "..") {
                    JLog.i(TAG, "old recovery state with no game: dropped"); state.delete(); info.delete(); return
                }
                val dir = File(File(root, GAMES_DIR), name)
                dir.mkdirs()
                val newer = read(dir)
                val oldAt = p.getProperty("saved_at")?.toLongOrNull() ?: 0L
                if (newer != null && newer.savedAt >= oldAt) { state.delete(); info.delete(); return }
                if (state.renameTo(File(dir, RECOVERY_STATE)) && info.renameTo(File(dir, RECOVERY_INFO)))
                    JLog.i(TAG, "old recovery state moved to its game's folder")
                else JLog.e(TAG, "can't move the old recovery state to its game's folder")
            }
        }
    }

    companion object {
        private const val TAG = "Joey64States"

        // ---- quit saves written after quitting (Joey 2026-11-13: "why does quitting a game take so long?") ----
        // One thread for the whole app, so they're written in order; it outlives the game screen.

        /** The longest a read waits for a quit save still being written. */
        const val WRITE_WAIT_MS = 60_000L
        @Volatile private var writerThread: Thread? = null
        private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "joey64-quit-save").also { writerThread = it }
        }
        private val pending = HashMap<String, Int>()
        private val lock = Object()

        /** Runs [job] (writing [saveName]'s quit save) on the quit-save thread; [awaitWrites] waits for it. */
        fun writeLater(saveName: String, job: () -> Unit) {
            synchronized(lock) { pending[saveName] = (pending[saveName] ?: 0) + 1 }
            writer.execute {
                try { job() } catch (e: Exception) { JLog.e(TAG, "quit save for $saveName: $e") }
                finally {
                    synchronized(lock) {
                        val n = (pending[saveName] ?: 1) - 1
                        if (n <= 0) pending.remove(saveName) else pending[saveName] = n
                        lock.notifyAll()
                    }
                }
            }
        }

        // ---- staged quit saves going into the data folder ([writeQuit], [commitStaged]) ----
        private val committer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "joey64-quit-commit") }
        private val gameLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        private fun gameLock(saveName: String): Any = gameLocks.getOrPut(saveName) { Any() }
        /** Held while the slots are read, and while a commit renames them. */
        private val viewLock = Object()
        @Volatile private var stopping = false

        /** Puts [saveName]'s staged quit save in the data folder, on the commit thread. */
        fun commitLater(context: Context, saveName: String) {
            val app = context.applicationContext
            committer.execute {
                try { GameStates(app, saveName).commitNow() } catch (e: Exception) { JLog.e(TAG, "quit save of $saveName: $e") }
            }
        }

        /** Every game's staged quit save into the data folder, on the commit thread (launcher and game start). */
        fun commitAllLater(context: Context) { for (name in QuitStage.waiting(context)) commitLater(context, name) }

        /** Every staged quit save into the data folder before returning (a backup, a data folder move). */
        fun commitAllNow(context: Context) {
            for (name in QuitStage.waiting(context)) try { GameStates(context, name).commitNow() } catch (e: Exception) {
                JLog.e(TAG, "quit save of $name: $e")
            }
        }

        /**
         * The process ends soon (switchGame, a stuck core): a commit's renames don't start from here, and one
         * renaming now is waited for, so none is cut off halfway. What's left stays staged for the next start.
         */
        fun stopCommits() { stopping = true; synchronized(viewLock) {} }

        /** [folderSlotWhileStaged]: the staged quit save itself. */
        internal const val STAGED = -2
        /** [folderSlotWhileStaged]: nothing (an older one past what's kept, going when it goes in). */
        internal const val NONE = -1

        /**
         * The folder's slot that [slot] shows while a quit save waits staged with [keep] kept: Auto is the staged one,
         * Auto n the folder's Auto n-1 (while n is kept, else [NONE]), other slots themselves.
         */
        internal fun folderSlotWhileStaged(slot: Int, keep: Int): Int {
            if (!StateSlots.isAuto(slot)) return slot
            val n = StateSlots.autoNumber(slot)
            return when {
                n == 1 -> STAGED
                n <= keep.coerceIn(1, StateSlots.AUTO_MAX) -> StateSlots.auto(n - 1)
                else -> NONE
            }
        }

        /** Waits until no quit save of [saveName] (null: of any game) is being written; false if [timeoutMs] ran out. */
        fun awaitWrites(saveName: String?, timeoutMs: Long): Boolean {
            if (Thread.currentThread() === writerThread) return true
            val t0 = SystemClock.elapsedRealtime()
            val end = t0 + timeoutMs
            var waited = false
            synchronized(lock) {
                while (if (saveName == null) pending.isNotEmpty() else saveName in pending) {
                    val left = end - SystemClock.elapsedRealtime()
                    if (left <= 0) { JLog.e(TAG, "a quit save is still being written after $timeoutMs ms"); return false }
                    waited = true
                    lock.wait(left)
                }
            }
            if (waited) JLog.i(TAG, "waited ${SystemClock.elapsedRealtime() - t0} ms for a quit save to be written")
            return true
        }
        private const val TIMEOUT_MS = 10_000
        /** A state's picture is shown at most about half the screen wide (the Play popup, Saves): 960 keeps it sharp there. */
        private const val PICTURE_MAX_WIDTH = 960
        private const val RECOVERY_DIR = "recovery"
        private const val RECOVERY_STATE = "state"
        private const val RECOVERY_INFO = "info.properties"
        private const val RECOVERY_PICTURE = "picture.png"
        /** A state's new picture while the state is written ([write]). */
        private const val NEXT = ".next"
        private const val GAMES_DIR = "games"
        /** A new quit save's files wait under their names plus this until the kept ones have moved down ([saveQuit]). */
        private const val ASIDE = ".new"

        /** The name a ROM's save and states go by: its file name without extension, as RetroArch names them. */
        fun saveNameOf(fileName: String) = fileName.substringBeforeLast('.').replace('/', '_')
    }
}
