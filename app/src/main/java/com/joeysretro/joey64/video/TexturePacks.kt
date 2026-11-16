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

    /** GLideNHQ's cache format version (TxFilterExport.h UNDEFINED_1) and the options it writes while still adding. */
    private const val FORMAT_VERSION = 0x08000000
    private const val FAKE_CONFIG = -1

    /**
     * Whether the core will load the .hts at [f] as it is: the checks of GLideN64's TxFileStorage::load (version, options,
     * the index's place past the header and inside the file, at least one entry there), for a load with no PNG folder
     * next to it (options of any GLideN64 build are taken). A file that fails them is never handed over, as GLideN64
     * "clears" a cache it can't load by writing a new empty one over it. Throws when [f] can't be opened at all.
     */
    fun htsLoadable(f: File): Boolean = RandomAccessFile(f, "r").use { r ->
        val len = r.length()
        if (len < 20) return@use false
        val head = ByteArray(16); r.readFully(head)
        val b = java.nio.ByteBuffer.wrap(head).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val version = b.getInt(0)
        val (config, pos, first) = if (version == FORMAT_VERSION) Triple(b.getInt(4), b.getLong(8), 16L) else Triple(version, b.getLong(4), 12L)
        if (config == FAKE_CONFIG || pos <= first || pos > len - 4) return@use false
        r.seek(pos)
        val n = ByteArray(4); r.readFully(n)
        java.nio.ByteBuffer.wrap(n).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0) > 0
    }

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
        val index = index() ?: return
        writeIndex(index.filter { p -> p.folder != uri })
        // What Joey64 made from its packs goes with it: PNG packs' conversions (can be GBs) and the pictures.
        for (p in index) if (p.folder == uri) forget(p)
    }

    private fun forget(p: TexPack) {
        if (p.png) convDir(p).deleteRecursively()
        // Its "is ready" / "waits for room" note goes too (Pocket Block 2026-11-16: it stayed after the folder was removed).
        try { context.getSystemService(android.app.NotificationManager::class.java)?.cancel(TexConvertService.resultId(p.id)) }
        catch (e: Exception) { }
        for (which in listOf("pack", "own")) shot(p, which).delete()
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
        // Packs gone from their folders (deleted or moved on the SD card) leave their conversions and pictures behind.
        val ids = out.map { it.id }.toSet()
        for (p in old) if (p.id !in ids) forget(p)
        // And anything a stopped conversion wrote after its pack went (the conversion process is stopped, not waited for).
        val keep = out.filter { it.png }.map { convDir(it).name }.toSet()
        File(dir, "conv").listFiles()?.forEach { if (it.isDirectory && it.name !in keep) it.deleteRecursively() }
        JLog.i(TAG, "texture packs: ${out.size} in ${folders().size} folders" + if (unreadable.isEmpty()) "" else ", ${unreadable.size} can't be read")
        return out to unreadable
    }

    // ---- PNG packs: converted once (Joey 2026-11-15) -------------------------------------------------------------

    /** A PNG pack's conversion folder (by its document id, so two packs for one game don't share it). */
    fun convDir(p: TexPack): File = File(dir, "conv/" + Integer.toHexString(p.id.hashCode()) + "-" + com.joeysretro.joey64.controls.ControlsStore.safe(p.packName))
    /** The .hts the core made from [p]'s PNGs (named after the ROM it was made at), or null before. */
    fun converted(p: TexPack): File? = convDir(p).listFiles { f -> f.isFile && f.name.endsWith(TexPacks.SUFFIX) }?.firstOrNull()
    fun isConverted(p: TexPack) = converted(p)?.let { TexPacks.htsWhole(it) } == true
    /** The PNG copy is in place (the core builds the .hts from it at the game's next start). */
    fun isCopied(p: TexPack) = File(convDir(p), "copied").isFile

    /** The room [p]'s conversion still needs (the PNGs not copied yet, then the .hts, about the pack's size) and the room free. */
    fun room(p: TexPack): Pair<Long, Long> {
        val copied = convDir(p).walkTopDown().filter { it.isFile && it.name.endsWith(".png", ignoreCase = true) }.sumOf { it.length() }
        val need = (p.size - copied).coerceAtLeast(0) + p.size + (64L shl 20)
        return need to dir.apply { mkdirs() }.usableSpace
    }

    /** What [copyPngs] did: the PNGs in the pack (the build's total), or why not. */
    data class Copied(val pngs: Int, val why: String?)

    /**
     * Copies [p]'s PNGs into its conversion folder as `<romName>/...` ([romName] the game's header name exactly: the core
     * looks for that folder on a file system where case counts). Picks up where it left off: each file goes through
     * `.part` and a rename, and a file already there at its size is kept (Joey64 closed, the device restarted).
     * [progress] gets the bytes done and the total; [hold] is called between files (it waits while a game is played).
     * Files that fail are counted and the copy goes on; then it's not done (tried again later, the rest kept).
     */
    fun copyPngs(p: TexPack, romName: String, progress: (Long, Long) -> Unit, hold: () -> Unit): Copied {
        val out = convDir(p)
        nameFor(p, romName)
        val tree = Uri.parse(p.folder)
        val space = try {
            com.joeysretro.joey64.launcher.storage.TreeSpace(context.contentResolver, tree, DocumentsContract.getDocumentId(Uri.parse(p.id)))
        } catch (e: Exception) { return Copied(0, "the folder can't be read") }
        val files = space.walkOrNull(maxDepth = 6) ?: return Copied(0, "the folder can't be read")
        val pngs = files.filter { (_, e) -> !e.second.isDir && e.second.name.endsWith(".png", ignoreCase = true) }
        if (pngs.isEmpty()) return Copied(0, "no PNGs found in it")
        val total = pngs.sumOf { it.second.second.size }.coerceAtLeast(1)
        var done = 0L
        var failed = 0
        for ((n, f) in pngs.withIndex()) {
            val (path, e) = f
            if (n % 256 == 255 && folders().none { it.uri == p.folder }) return Copied(pngs.size, "its folder was removed")
            val to = File(out, (listOf(romName) + path).joinToString("/"))
            if (!(to.isFile && to.length() == e.second.size)) {
                hold()
                to.parentFile?.mkdirs()
                val part = File(to.path + ".part")
                val ok = try {
                    context.contentResolver.openInputStream(space.documentUri(e.first))?.use { i -> part.outputStream().use { o -> i.copyTo(o, 1 shl 16) } } != null &&
                        (part.renameTo(to) || run { to.delete(); part.renameTo(to) })
                } catch (x: Exception) { false }
                if (!ok) { part.delete(); failed++; JLog.w(TAG, "texture packs: can't copy ${e.second.name}"); continue }
            }
            done += e.second.size
            progress(done, total)
        }
        if (failed > 0) {
            JLog.w(TAG, "texture packs: $failed of ${pngs.size} PNGs of ${p.shown} couldn't be copied")
            return Copied(pngs.size, "$failed of ${pngs.size} files couldn't be copied")
        }
        JLog.i(TAG, "texture packs: ${pngs.size} PNGs of ${p.shown} copied for converting, ${TexPacks.sizeLabel(done)}")
        return Copied(pngs.size, null)
    }

    /** With a core that can't make a pack outside play: the PNG copy is marked done, and the game makes the .hts at its start. */
    fun markCopied(p: TexPack) = File(convDir(p), "copied").writeText(p.id)

    /** The PNG copy goes once the .hts is whole. */
    fun dropPngs(p: TexPack) {
        convDir(p).listFiles { f -> f.isDirectory }?.forEach { it.deleteRecursively() }
        File(convDir(p), "copied").delete()
    }

    /** The game screen is running a game (MainActivity writes it, the conversion waits): its process id and title. */
    val playingFile = File(dir, "playing")
    /** What the conversion process is doing, for the screens in Joey64's own process (TexConvertService writes it). */
    val statusFile = File(dir, "status.json")

    /** Names [p]'s PNG copy and .hts after [romName] exactly (a game whose header name differs in case from the pack's). */
    private fun nameFor(p: TexPack, romName: String) {
        convDir(p).listFiles()?.forEach { f ->
            val want = if (f.isDirectory) romName else if (f.name.endsWith(TexPacks.SUFFIX)) romName + TexPacks.SUFFIX else return@forEach
            if (f.name != want && !f.renameTo(File(f.parentFile, want))) JLog.w(TAG, "texture packs: can't rename ${f.name} to $want")
        }
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

    /** What [prepare] made of a pack: ready for the core, or why not. */
    sealed interface Prep
    /** The folder for N64_RDP_TEXPACK, and the pack. */
    data class Ready(val dir: File, val pack: TexPack) : Prep
    data class NotReady(val why: Why) : Prep
    enum class Why {
        /** The folder's grant is gone, the SD card is out, or Android won't let the core open the file. */
        Unreachable,
        /** The .hts isn't one the core can load (another format, or one GLideN64 never finished writing). */
        NotAPack,
        /** A PNG pack whose copy isn't done (or failed: TexConvert.failed says why). */
        NotCopied,
    }

    /**
     * Gets [p] ready for the core, named after [romName] (the game's header name exactly: the core looks for
     * `<romName>_HIRESTEXTURES.hts` or `<romName>/` where case counts, while packs are matched without it). A .hts is
     * opened through its folder's grant and linked as `run/<romName>_HIRESTEXTURES.hts` (the descriptor stays open for
     * the process), then read back through the link as the core will read it, and checked to load whole (else GLideN64
     * would write an empty cache over it); a PNG pack runs from its conversion folder.
     */
    fun prepare(p: TexPack, romName: String): Prep {
        release()
        if (p.png) {
            nameFor(p, romName)
            return if (isConverted(p) || isCopied(p)) Ready(convDir(p), p) else NotReady(Why.NotCopied)
        }
        val run = File(dir, "run")
        run.deleteRecursively(); run.mkdirs()
        val pfd = try { context.contentResolver.openFileDescriptor(Uri.parse(p.id), "r") } catch (e: Exception) { JLog.w(TAG, "texture packs: can't open ${p.file}: $e"); null }
            ?: return NotReady(Why.Unreachable)
        heldFd = pfd
        val link = File(run, romName + TexPacks.SUFFIX)
        val why = try {
            android.system.Os.symlink("/proc/self/fd/${pfd.fd}", link.path)
            if (TexPacks.htsLoadable(link)) null else { JLog.w(TAG, "texture packs: ${p.file} isn't a pack the core loads whole"); Why.NotAPack }
        } catch (e: Exception) { JLog.w(TAG, "texture packs: can't read ${p.file} through its link: $e"); Why.Unreachable }
        if (why == null) return Ready(run, p)
        release()
        run.deleteRecursively()
        return NotReady(why)
    }

    private fun release() {
        heldFd?.let { try { it.close() } catch (e: Exception) {} }
        heldFd = null
    }

    /** [p]'s picture with it ("pack") or the game's own ("own"); taken in play (Joey 2026-11-15). By pack, so two packs for one game keep their own. */
    fun shot(p: TexPack, which: String) = File(shots, Integer.toHexString(p.id.hashCode()) + "-" +
        com.joeysretro.joey64.controls.ControlsStore.safe(p.packName) + "-" + which + ".png")

    private fun writeFolders(list: List<Folder>) = writeJson(foldersFile, JSONObject().put("folders",
        JSONArray(list.map { JSONObject().put("uri", it.uri).put("name", it.name) })))
    private fun writeIndex(list: List<TexPack>) = writeJson(indexFile, JSONObject().put("packs", JSONArray(list.map { it.toJson() })))

    private fun displayName(uri: Uri): String? = StoreFiles.treeName(context, uri)
    private fun readJson(f: File): JSONObject? = StoreFiles.readJson(f)
    private fun writeJson(f: File, o: JSONObject) = StoreFiles.writeJson(f, o, TAG)

    companion object {
        private const val TAG = "Joey64TexPacks"
        /** The open .hts the core reads through /proc/self/fd (one game at a time). */
        @Volatile private var heldFd: android.os.ParcelFileDescriptor? = null
    }
}
