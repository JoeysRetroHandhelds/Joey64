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
 */
internal object QuitStage {
    private const val TAG = "Joey64States"
    private const val DIR = "quitsave"
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

    /** The save names with a quit save staged. */
    fun waiting(context: Context): List<String> = waitingIn(rootOf(context))
    internal fun waitingIn(root: File): List<String> =
        (root.listFiles() ?: emptyArray()).filter { read(it) != null }.map { it.name }.sorted()

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
