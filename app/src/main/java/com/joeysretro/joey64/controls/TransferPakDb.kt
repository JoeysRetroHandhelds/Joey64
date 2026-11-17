package com.joeysretro.joey64.controls

import android.content.Context
import com.joeysretro.joey64.JLog
import java.text.Normalizer

/**
 * Which Game Boy games a Transfer Pak game takes (Joey 2026-11-17: the Game Boy game list "should only load the games
 * that are applicable for the game we are in, save with saves"). `assets/tpakdb.tsv` (devtools/make-tpakdb.py, from the
 * core's mupen64plus.ini) gives each N64 ROM MD5 a group; [fits] says whether a Game Boy file belongs to that group, by
 * the words of its file name (No-Intro's names: "Pokemon - Red Version (USA, Europe)", "Pocket Monsters - Aka (Japan)").
 * A game with no group (not a Transfer Pak game we know, or one that takes any Game Boy game) lists everything.
 * The partner games are Wikipedia's Transfer Pak table (read 2026-11-17).
 */
object TransferPakDb {
    @Volatile private var table: Map<String, String>? = null

    fun load(context: Context): Map<String, String> = table ?: try {
        context.assets.open("tpakdb.tsv").bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { l ->
                val c = l.split('\t'); if (c.size < 2) null else c[0].trim().lowercase() to c[1].trim()
            }.toMap()
        }
    } catch (e: Exception) {
        JLog.e("Joey64Controls", "can't read tpakdb.tsv: $e"); emptyMap()
    }.also { table = it }

    /** The Game Boy group of the N64 game with [md5]; null: any Game Boy game. */
    fun groupOf(table: Map<String, String>, md5: String?): String? = md5?.let { table[it.lowercase()] }

    /** The file name's words, lower case, accents dropped: "Pokémon - Gelbe Edition" -> pokemon, gelbe, edition. */
    internal fun words(name: String): List<String> {
        val base = GameBoyFiles.title(name.substringAfterLast('/'))
        val plain = Normalizer.normalize(base, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
        return plain.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    }

    private val GEN1 = setOf("red", "blue", "green", "yellow", "aka", "midori", "ao", "pikachu",
        "rouge", "bleue", "jaune", "rote", "blaue", "gelbe", "roja", "azul", "amarilla", "rossa", "blu", "gialla")
    private val GEN2 = setOf("gold", "silver", "crystal", "kin", "gin", "or", "argent", "cristal", "silberne", "goldene",
        "kristall", "oro", "plata", "argento")

    /** True when the Game Boy file [name] is one of [group]'s games; a null group takes every game. */
    fun fits(group: String?, name: String): Boolean {
        if (group == null) return true
        // Tags in brackets come after the title: "(Japan)", "[T-En]"; the title is what's before them.
        val title = words(name.substringAfterLast('/').substringBefore(" (").substringBefore(" ["))
        fun has(vararg ws: String) = ws.all { it in title }
        val pokemon = title.firstOrNull() == "pokemon" || (title.size >= 2 && title[0] == "pocket" && title[1] == "monsters")
        return when (group) {
            "pokemon1" -> pokemon && title.any { it in GEN1 }
            "pokemon12" -> pokemon && title.any { it in GEN1 || it in GEN2 }
            "mariogolf" -> has("mario", "golf")
            "mariotennis" -> has("mario", "tennis")
            "mickey" -> "mickey" in title && ("speedway" in title || "racing" in title)
            "perfectdark" -> has("perfect", "dark")
            "gbcamera" -> "camera" in title
            "choroq" -> has("choro")
            "powerpro1" -> has("power", "pro", "kun", "pocket") && title.none { it.length == 1 && it[0] in '2'..'9' }
            "powerpro2" -> has("power", "pro", "kun", "pocket", "2")
            "uminushi2" -> has("umi", "nushi", "tsuri", "2")
            "kawanushi4" -> has("kawa", "nushi", "tsuri", "4")
            "puyosun" -> has("puyo", "sun")
            "robopon" -> "ponkottsu" in title || "robopon" in title
            "bdaman" -> "daman" in title && "phoenix" in title
            "linkbattler" -> has("robot", "taisen", "link")
            else -> true
        }
    }
}
