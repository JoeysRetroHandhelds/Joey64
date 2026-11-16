package com.joeysretro.joey64.states

import android.content.Context
import com.joeysretro.joey64.JLog
import java.io.File
import java.io.IOException
import java.util.Properties

/**
 * A quit save waiting to go into the data folder (2026-11-14, Joey: "exiting a game and trying to get back into that
 * game or another has a delay"): Joey64's own storage takes it in a few tenths of a second, where the Thor's SD card
 * took 1.5-8 s, and [GameStates] shows it as the Auto save state until it's in. One per game, in
 * `files/quitsave/<save name>/`: `mem` (the core's state as it gave it, unpacked: packing it took longer than the
 * write), `achv` (the achievements' progress, when there is some), `picture.png`, `info.properties` (saved_at, keep).
 * Each file is written to `.part` and renamed, the info last, so a staged save is whole or absent.
 *
 * Quit saves staged before it that couldn't go in yet (the data folder out of reach) wait in
 * `files/quitsave-older/<save name>/1, 2, ...` (1 the newest of them), so a new quit doesn't write over them (review
 * 2026-11-15); they go in oldest first.
 */
internal object QuitStage {
    private const val TAG = "Joey64States"
    private const val DIR = "quitsave"
    private const val OLDER = "quitsave-older"
    private const val STATE = "mem"
    private const val ACHV = "achv"
    private const val PICTURE = "picture.png"
    private const val INFO = "info.properties"

    class Staged(private val dir: File, val savedAt: Long, val keep: Int) {
        fun mem(): ByteArray = File(dir, STATE).readBytes()
        fun achv(): ByteArray? = File(dir, ACHV).takeIf { it.isFile }?.readBytes()
        fun picture(): ByteArray? = try { File(dir, PICTURE).takeIf { it.isFile }?.readBytes() } catch (e: IOException) { null }
    }

    fun rootOf(context: Context) = File(context.filesDir, DIR)
    fun dirOf(context: Context, saveName: String) = File(rootOf(context), saveName)
    fun olderOf(context: Context, saveName: String) = File(File(context.filesDir, OLDER), saveName)

    /** The save names with a quit save staged (the newest, or only older ones). */
    fun waiting(context: Context): List<String> = waitingIn(rootOf(context), File(context.filesDir, OLDER))
    internal fun waitingIn(root: File, olderRoot: File? = null): List<String> =
        ((root.listFiles() ?: emptyArray()).filter { read(it) != null }.map { it.name } +
            (olderRoot?.listFiles() ?: emptyArray()).filter { olderIn(it).isNotEmpty() }.map { it.name }).distinct().sorted()

    /** The older staged saves in [older], newest first, with their folders. */
    private fun olderIn(older: File): List<Pair<File, Staged>> =
        (older.listFiles() ?: emptyArray()).mapNotNull { f -> f.name.toIntOrNull()?.let { it to f } }.sortedBy { it.first }
            .mapNotNull { (_, f) -> read(f)?.let { f to it } }

    /** Every staged quit save of one game, newest first: [dir]'s, then the older ones in [older]. */
    fun all(dir: File, older: File): List<Pair<File, Staged>> = listOfNotNull(read(dir)?.let { dir to it }) + olderIn(older)

    /**
     * [dir]'s staged save becomes the newest older one in [older] (1), those there move one on, and any past
     * [maxOlder] go (they'd go anyway as it goes in: past what's kept). False when one couldn't be moved.
     */
    fun pushOlder(dir: File, older: File, maxOlder: Int): Boolean {
        if (read(dir) == null) return true
        older.mkdirs()
        val olds = (older.listFiles() ?: emptyArray()).mapNotNull { f -> f.name.toIntOrNull()?.let { it to f } }.sortedByDescending { it.first }
        for ((n, f) in olds) {
            if (n + 1 > maxOlder) clear(f)
            else if (!f.renameTo(File(older, "${n + 1}"))) return false
        }
        if (maxOlder < 1) { clear(dir); return true }
        return dir.renameTo(File(older, "1"))
    }

    /** The staged quit save in [dir], or null when there's none (or only part of one). */
    fun read(dir: File): Staged? {
        val info = File(dir, INFO)
        if (!info.isFile || !File(dir, STATE).isFile) return null
        val p = Properties()
        try { info.inputStream().use { p.load(it) } } catch (e: IOException) { return null }
        val at = p.getProperty("saved_at")?.toLongOrNull() ?: return null
        val keep = p.getProperty("keep")?.toIntOrNull() ?: return null
        return Staged(dir, at, keep)
    }

    /** Stages [mem] (and [achv], [picture]) in [dir], replacing what's there; false when it couldn't be written. */
    fun write(dir: File, mem: ByteArray, achv: ByteArray?, picture: ByteArray?, savedAt: Long, keep: Int): Boolean = try {
        clear(dir)
        dir.mkdirs()
        put(dir, STATE, mem)
        if (achv != null) put(dir, ACHV, achv)
        if (picture != null) put(dir, PICTURE, picture)
        val p = Properties().apply { setProperty("saved_at", savedAt.toString()); setProperty("keep", keep.toString()) }
        val part = File(dir, "$INFO.part")
        part.outputStream().use { p.store(it, null) }
        if (!part.renameTo(File(dir, INFO))) throw IOException("can't rename $INFO.part")
        true
    } catch (e: IOException) {
        JLog.e(TAG, "can't stage the quit save in ${dir.name}: $e")
        clear(dir); false
    }

    /** Removes [dir]'s staged quit save (the info first, so a cut-off clear leaves nothing that reads as staged). */
    fun clear(dir: File) {
        File(dir, INFO).delete()
        for (f in dir.listFiles() ?: emptyArray()) f.delete()
        dir.delete()
    }

    private fun put(dir: File, name: String, bytes: ByteArray) {
        val part = File(dir, "$name.part")
        part.writeBytes(bytes)
        if (!part.renameTo(File(dir, name))) throw IOException("can't rename $name.part")
    }
}
