package com.joeysretro.joey64.video

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.joeysretro.joey64.JLog
import org.json.JSONObject
import java.io.File

/** What the Video stores (shader presets, RetroArch overlays, texture packs) share: their JSON files and picked folders' names. */
internal object StoreFiles {
    /** A picked folder's (document tree's) name as Android shows it, or null. */
    fun treeName(context: Context, uri: Uri): String? = try {
        val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null }

    fun readJson(f: File): JSONObject? = try { if (f.isFile) JSONObject(f.readText()) else null } catch (e: Exception) { null }

    /** Writes [o] to [f] through `.part` and a rename, so a crash mid-write leaves the old file. */
    fun writeJson(f: File, o: JSONObject, tag: String) {
        try {
            f.parentFile?.mkdirs()
            val part = File(f.parentFile, f.name + ".part")
            part.writeText(o.toString())
            if (!part.renameTo(f)) { f.delete(); part.renameTo(f) }
        } catch (e: Exception) { JLog.e(tag, "can't write ${f.name}: $e") }
    }
}
