package com.joeysretro.joey64.video

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.joeysretro.joey64.JLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Texture packs (approved mockup 2026-11-15, scratchpad build_texpacks3.py, "Approve as drawn"): GLideN64 packs in folders
 * the user picks with Android's folder picker, read in place, each found by the game's header name (RomHeader.packName),
 * as GLideN64 files them. parallel-RDP reads them (core `N64_RDP_TEXPACK=<dir>`: `<name>_HIRESTEXTURES.hts`, or a
 * `<name>/` folder of PNGs).
 *
 * A .hts is handed to the core as it is: the game screen opens it through the folder's grant and puts a link
 * `<name>_HIRESTEXTURES.hts` -> `/proc/self/fd/<n>` in a folder of its own ([prepare]). A folder of PNGs can't go that
 * way (Joey 2026-11-15, "Convert once"): its PNGs are copied into Joey64's storage once ([convert]), the core builds the
 * .hts from them at the game's next start (GLideN64's own cache dump, written entry by entry), and the PNG copy goes once
 * that .hts is whole ([finishConversions]).
 */
data class TexPack(
    /** The .hts's document URI, or the PNG folder's. */
    val id: String,
    /** The pack folder (tree URI) it was found in. */
    val folder: String,
    /** The header name it's filed under. */
    val packName: String,
    /** What the lists call it: the folder holding it when it isn't the pack folder itself, else [packName]. */
    val shown: String,
    /** Its file name ("GOLDENEYE_HIRESTEXTURES.hts"), or the PNG folder's. */
    val file: String,
    val size: Long,
    /** A folder of PNGs (needs converting once). */
    val png: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("folder", folder).put("packName", packName).put("shown", shown)
        .put("file", file).put("size", size).put("png", png)

    companion object {
        fun of(o: JSONObject) = TexPack(o.getString("id"), o.getString("folder"), o.getString("packName"), o.getString("shown"),
            o.getString("file"), o.optLong("size"), o.optBoolean("png"))
    }
}

object TexPacks {
    const val SUFFIX = "_HIRESTEXTURES.hts"
    /** A game's own choice of pack: "pack:<id>". "on" is Auto (All games' On), "off" None. */
    const val PICK = "pack:"

    /** One file the scan saw: its path below the pack folder (folder names, then its own), document id, and size. */
    data class Seen(val path: List<String>, val docId: String, val size: Long)

    /**
     * The packs among [seen] (everything below one pack folder): every `*_HIRESTEXTURES.hts`, and every folder of
     * GLideN64 PNGs (Rice names, `<NAME>#<crc>#...png`) whose own name is that NAME, its size the PNGs'. Pure, for tests.
     */
    fun find(seen: List<Seen>, folder: String, docUri: (String) -> String, dirUri: (List<String>) -> String?): List<TexPack> {
        val out = ArrayList<TexPack>()
        for (s in seen) {
            if (!s.path.last().endsWith(SUFFIX, ignoreCase = true)) continue
            val name = s.path.last().dropLast(SUFFIX.length)
            if (name.isEmpty()) continue
            out.add(TexPack(docUri(s.docId), folder, name, s.path.dropLast(1).lastOrNull() ?: name, s.path.last(), s.size, png = false))
        }
        // PNG packs: the folder named as the PNGs' NAME, nearest above them.
        val sizes = LinkedHashMap<List<String>, Long>()
        for (s in seen) {
            if (!s.path.last().endsWith(".png", ignoreCase = true)) continue
            val name = s.path.last().substringBefore('#', "")
            if (name.isEmpty()) continue
            val at = (s.path.size - 1 downTo 1).firstOrNull { s.path[it - 1].equals(name, ignoreCase = true) } ?: continue
            val root = s.path.take(at)
            sizes[root] = (sizes[root] ?: 0L) + s.size
        }
        for ((root, size) in sizes) {
            val dir = dirUri(root) ?: continue
            out.add(TexPack(dir, folder, root.last(), root.dropLast(1).lastOrNull() ?: root.last(), root.last(), size, png = true))
        }
        return out.sortedWith(compareBy({ it.shown.lowercase(Locale.ROOT) }, { it.file.lowercase(Locale.ROOT) }))
    }

    /** The packs for a game filed under [packName] (GLideN64 matches the name as it is; folders differ only in case on SD cards). */
    fun forGame(index: List<TexPack>, packName: String?): List<TexPack> =
        if (packName.isNullOrEmpty()) emptyList() else index.filter { it.packName.equals(packName, ignoreCase = true) }

    /**
     * The pack a game plays with: [value] is VideoKey.TexPack's ("on" Auto, "off" None, "pack:<id>"). Auto takes a .hts
     * before a PNG folder, then the first by name. A picked pack that isn't found any more falls back to Auto.
     */
    fun pick(index: List<TexPack>, packName: String?, value: String): TexPack? {
        if (value == "off") return null
        val mine = forGame(index, packName)
        if (value.startsWith(PICK)) mine.firstOrNull { it.id == value.removePrefix(PICK) }?.let { return it }
        return mine.firstOrNull { !it.png } ?: mine.firstOrNull()
    }

    /**
     * Whether the .hts at [f] is whole: GLideN64 writes -1 where the index's place goes while it's still adding (TxCache.cpp
     * TxFileStorage::add, "prevent attempts to load unsaved storage file") and the real place once saved. After the
     * format version and the options (two ints).
     */
    fun htsWhole(f: File): Boolean = try {
        if (!f.isFile || f.length() < 16) false else RandomAccessFile(f, "r").use { r ->
            r.seek(8)
            val b = ByteArray(8); r.readFully(b)
            var pos = 0L
            for (i in 7 downTo 0) pos = (pos shl 8) or (b[i].toLong() and 0xFF) // little-endian (arm64)
            pos > 0
        }
    } catch (e: Exception) { false }

    /** "593 MB", "3.6 GB". */
    fun sizeLabel(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "${bytes shr 20} MB"
        else -> "${maxOf(1L, bytes shr 10)} KB"
    }
}

/**
 * Where texture packs live: `files/video/texpacks/` with `folders.json` (the picked pack folders), `index.json` (the packs
 * the last scan found), `conv/<key>/` (a PNG pack's conversion: its PNG copy while converting, then the .hts),
 * `shots/` (each game's pictures, with and without its pack) and `run/` (the game screen's links to the open .hts).
 */
class TexPackStore(private val context: Context) {
    val dir = File(context.filesDir, "video/texpacks")
    private val foldersFile = File(dir, "folders.json")
    private val indexFile = File(dir, "index.json")
    val shots = File(dir, "shots")

    data class Folder(val uri: String, val name: String)

    fun folders(): List<Folder> = readJson(foldersFile)?.optJSONArray("folders")?.let { a ->
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> Folder(o.optString("uri"), o.optString("name")) }?.takeIf { it.uri.isNotEmpty() } }
    } ?: emptyList()

    /** Keeps [uri]'s read grant and adds it (a folder already listed isn't added twice). Then [scan]. */
    fun addFolder(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) { JLog.e(TAG, "can't keep the folder's permission: $e") }
        val list = folders()
        if (list.any { it.uri == uri.toString() }) return
        writeFolders(list + Folder(uri.toString(), displayName(uri) ?: "Texture packs"))
    }

    /** Takes [uri] off the list and its packs out of the index (the grant is released by the caller, RomFolders.releaseIfUnused). */
    fun removeFolder(uri: String) {
        writeFolders(folders().filter { it.uri != uri })
        index()?.let { writeIndex(it.filter { p -> p.folder != uri }) }
    }

    /** The packs found last time, or null before the first scan. */
    fun index(): List<TexPack>? = readJson(indexFile)?.optJSONArray("packs")?.let { a ->
        (0 until a.length()).mapNotNull { i -> try { TexPack.of(a.getJSONObject(i)) } catch (e: Exception) { null } }
    }

    /**
     * Looks through every pack folder (off the main thread: a PNG pack's folders are many). A folder that can't be read keeps
     * the packs it had, and is named in the result's second part.
     */
    fun scan(): Pair<List<TexPack>, List<String>> {
        val old = index().orEmpty()
        val out = ArrayList<TexPack>()
        val unreadable = ArrayList<String>()
        for (f in folders()) {
            val tree = Uri.parse(f.uri)
            val space = try { com.joeysretro.joey64.launcher.storage.TreeSpace(context.contentResolver, tree) } catch (e: Exception) { null }
            val walked = try { space?.walkOrNull(maxDepth = 6) } catch (e: Exception) { null }
            if (space == null || walked == null) { unreadable.add(f.uri); out.addAll(old.filter { it.folder == f.uri }); continue }
            val seen = walked.map { (path, e) -> TexPacks.Seen(path, e.first, e.second.size) }
            out.addAll(TexPacks.find(seen, f.uri, { id -> DocumentsContract.buildDocumentUriUsingTree(tree, id).toString() },
                { p -> space.uriOf(p)?.toString() }))
        }
        writeIndex(out)
        JLog.i(TAG, "texture packs: ${out.size} in ${folders().size} folders" + if (unreadable.isEmpty()) "" else ", ${unreadable.size} can't be read")
        return out to unreadable
    }

    // ---- PNG packs: converted once (Joey 2026-11-15) -------------------------------------------------------------

    /** A PNG pack's conversion folder (by its document id, so two packs for one game don't share it). */
    fun convDir(p: TexPack): File = File(dir, "conv/" + Integer.toHexString(p.id.hashCode()) + "-" + com.joeysretro.joey64.controls.ControlsStore.safe(p.packName))
    fun converted(p: TexPack): File = File(convDir(p), p.packName + TexPacks.SUFFIX)
    fun isConverted(p: TexPack) = TexPacks.htsWhole(converted(p))
    /** The PNG copy is in place (the core builds the .hts from it at the game's next start). */
    fun isCopied(p: TexPack) = File(convDir(p), "copied").isFile

    /**
     * Copies [p]'s PNGs into its conversion folder, [progress] 0..1 as it goes. Null when done, else why not (no space,
     * the folder can't be read). The core turns them into the .hts at the game's next start.
     */
    fun convert(p: TexPack, progress: (Float) -> Unit): String? {
        val out = convDir(p)
        val need = p.size + (64L shl 20)
        val free = dir.apply { mkdirs() }.usableSpace
        if (free < need) return "needs ${TexPacks.sizeLabel(need)}, ${TexPacks.sizeLabel(free)} free"
        val tree = Uri.parse(p.folder)
        val space = com.joeysretro.joey64.launcher.storage.TreeSpace(context.contentResolver, tree,
            DocumentsContract.getDocumentId(Uri.parse(p.id)))
        val files = space.walkOrNull(maxDepth = 6) ?: return "the folder can't be read"
        val pngs = files.filter { (_, e) -> !e.second.isDir && e.second.name.endsWith(".png", ignoreCase = true) }
        val total = pngs.sumOf { it.second.second.size }.coerceAtLeast(1)
        var done = 0L
        File(out, "copied").delete()
        for ((path, e) in pngs) {
            val to = File(out, (listOf(p.packName) + path).joinToString("/"))
            to.parentFile?.mkdirs()
            val ok = try {
                context.contentResolver.openInputStream(space.documentUri(e.first))?.use { i -> to.outputStream().use { o -> i.copyTo(o, 1 shl 16) } } != null
            } catch (x: Exception) { false }
            if (!ok) { JLog.w(TAG, "texture packs: can't copy ${e.second.name}"); continue }
            done += e.second.size
            progress(done.toFloat() / total)
        }
        File(out, "copied").writeText(p.id)
        JLog.i(TAG, "texture packs: ${pngs.size} PNGs of ${p.shown} copied for converting, ${TexPacks.sizeLabel(done)}")
        return null
    }

    /** Removes the PNG copies of conversions whose .hts is whole (each game start calls it). */
    fun finishConversions() {
        File(dir, "conv").listFiles()?.forEach { d ->
            val hts = d.listFiles { f -> f.name.endsWith(TexPacks.SUFFIX) }?.firstOrNull() ?: return@forEach
            if (!TexPacks.htsWhole(hts)) return@forEach
            d.listFiles { f -> f.isDirectory }?.forEach { it.deleteRecursively() }
            File(d, "copied").delete()
        }
    }

    // ---- The game screen ---------------------------------------------------------------------------------------

    /** What [prepare] set up: the folder for N64_RDP_TEXPACK, and the pack. */
    data class Ready(val dir: File, val pack: TexPack)

    /**
     * Gets [p] ready for the core: a .hts is opened through its folder's grant and linked as `run/<name>_HIRESTEXTURES.hts`
     * (the descriptor stays open for the process); a PNG pack runs from its conversion folder. Null when it can't be read
     * (the caller says so: the folder's grant is gone or the SD card is out).
     */
    fun prepare(p: TexPack): Ready? {
        heldFd?.let { try { it.close() } catch (e: Exception) {} }
        heldFd = null
        if (p.png) {
            val d = convDir(p)
            return if (isConverted(p) || isCopied(p)) Ready(d, p) else null
        }
        val run = File(dir, "run")
        run.deleteRecursively(); run.mkdirs()
        val pfd = try { context.contentResolver.openFileDescriptor(Uri.parse(p.id), "r") } catch (e: Exception) { JLog.w(TAG, "texture packs: can't open ${p.file}: $e"); null }
            ?: return null
        heldFd = pfd
        return try {
            android.system.Os.symlink("/proc/self/fd/${pfd.fd}", File(run, p.packName + TexPacks.SUFFIX).path)
            Ready(run, p)
        } catch (e: Exception) { JLog.w(TAG, "texture packs: can't link ${p.file}: $e"); null }
    }

    /** The picture with the pack ("pack") or the game's own ("own") for [packName]; taken in play (Joey 2026-11-15). */
    fun shot(packName: String, which: String) = File(shots, com.joeysretro.joey64.controls.ControlsStore.safe(packName) + "-" + which + ".png")

    private fun writeFolders(list: List<Folder>) = writeJson(foldersFile, JSONObject().put("folders",
        JSONArray(list.map { JSONObject().put("uri", it.uri).put("name", it.name) })))
    private fun writeIndex(list: List<TexPack>) = writeJson(indexFile, JSONObject().put("packs", JSONArray(list.map { it.toJson() })))

    private fun displayName(uri: Uri): String? = try {
        val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null }

    private fun readJson(f: File): JSONObject? = try { if (f.isFile) JSONObject(f.readText()) else null } catch (e: Exception) { null }

    private fun writeJson(f: File, o: JSONObject) {
        try {
            f.parentFile?.mkdirs()
            val part = File(f.parentFile, f.name + ".part")
            part.writeText(o.toString())
            if (!part.renameTo(f)) { f.delete(); part.renameTo(f) }
        } catch (e: Exception) { JLog.e(TAG, "can't write ${f.name}: $e") }
    }

    companion object {
        private const val TAG = "Joey64TexPacks"
        /** The open .hts the core reads through /proc/self/fd (one game at a time). */
        @Volatile private var heldFd: android.os.ParcelFileDescriptor? = null
    }
}
