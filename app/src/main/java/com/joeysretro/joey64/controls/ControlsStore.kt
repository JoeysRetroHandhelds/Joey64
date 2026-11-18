package com.joeysretro.joey64.controls

import android.content.Context
import com.joeysretro.joey64.JLog
import org.json.JSONObject
import java.io.File

/**
 * Where Controls lives: Joey64's own `files/controls/` (both the launcher and the game screen read
 * it; the game screen runs while the launcher is paused, so they never write at once).
 *  - `all.json`: All games, the four players.
 *  - `games/<save name>.json`: a game's own controls (approved mockups 8 and 9): a whole set, and in "own" each
 *    player's rows the game changed. Only those are the game's; every other row is All games' as it is now, so a
 *    change on All games reaches every game (Joey 2026-11-17: it was a whole copy, and a game kept All games' old
 *    values as changes). A file from before has no "own": its own rows are the ones that differ from All games and
 *    from the default (a row it was copied with that All games changed since was still the default, Joey's Stick
 *    feel Range and Curve).
 *    Which controller each player has is always All games'.
 *  - `hotkeys.json`: the hotkey button and the hotkeys.
 * Written whole (`.part`, then renamed). A file that can't be read is logged and the defaults used.
 */
class ControlsStore(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, "controls"))

    private val all = File(dir, "all.json")
    private val games = File(dir, "games")
    private val hotkeysFile = File(dir, "hotkeys.json")

    fun all(): ControlsSet = read(all)?.let { ControlsSet.fromJson(it) } ?: ControlsSet.defaults()
    fun saveAll(set: ControlsSet) {
        write(all, set.toJson())
        for (p in set.players) (p.device as? DeviceChoice.Pad)?.let { markPadSeen(it.descriptor) }
    }

    /**
     * Controllers (by [android.view.InputDevice.descriptor]) Joey64 has given a player or seen in a game, `seen.txt`, one a
     * line. Only a controller not in it is given to a free player at its first press (Joey 2026-11-17: a player set to None
     * got the pad back at its next press), so None stays None for one seen before.
     */
    private val seenFile = File(dir, "seen.txt")
    // Read each time (a controller's first press in a play, or a save): the launcher and the game screen each have a store.
    private fun seenSet(): Set<String> = try { if (seenFile.isFile) seenFile.readLines().filter { it.isNotBlank() }.toSet() else emptySet() }
        catch (e: Exception) { JLog.e(TAG, "can't read ${seenFile.name}: $e"); emptySet() }
    fun padSeen(descriptor: String): Boolean = descriptor in seenSet()
    fun markPadSeen(descriptor: String) {
        if (descriptor.isEmpty() || descriptor in seenSet()) return
        try { dir.mkdirs(); seenFile.appendText(descriptor + "\n") } catch (e: Exception) { JLog.e(TAG, "can't write ${seenFile.name}: $e") }
    }
    /** Whether All games was ever saved (else the first run: [PadDevices.firstSet]). */
    fun hasAll(): Boolean = all.isFile

    /** [PadDevices.fixToggleFaceOnce] ran on this device's controls (`toggleface.done`). */
    private val toggleFaceFlag = File(dir, "toggleface.done")
    fun toggleFaceDone(): Boolean = toggleFaceFlag.isFile
    fun markToggleFaceDone() {
        try { dir.mkdirs(); toggleFaceFlag.writeText("1") } catch (e: Exception) { JLog.e(TAG, "can't write ${toggleFaceFlag.name}: $e") }
    }

    /** A game's own controls, or null when it uses All games. */
    /** A game's controls: All games' with the game's own rows (see the class's note); null when it has none. */
    fun game(saveName: String): ControlsSet? = read(gameFile(saveName))?.let { o -> resolve(ControlsSet.fromJson(o), o.optJSONArray("own"), all()) }

    /** Saves [set] as [saveName]'s: its own rows are the ones that differ from All games; none, and the file goes. */
    fun saveGame(saveName: String, set: ControlsSet) {
        val a = all()
        val own = List(4) { i -> set.players[i].copy(device = a.players[i].device).differences(a.players[i]) }
        if (own.all { it.isEmpty() }) { removeGame(saveName); return }
        write(gameFile(saveName), set.toJson().put("own", org.json.JSONArray().apply { own.forEach { put(org.json.JSONArray(it.sorted())) } }))
    }
    fun removeGame(saveName: String) { if (!gameFile(saveName).delete() && gameFile(saveName).exists()) JLog.e(TAG, "can't remove $saveName's controls") }

    /** The games with their own controls, by save name. */
    fun gamesWithOwn(): List<String> = games.listFiles { f -> f.isFile && f.name.endsWith(".json") }
        ?.map { it.name.removeSuffix(".json") }?.sorted() ?: emptyList()

    /**
     * What a game plays with: its own buttons, sticks and paks when it has them, with All games'
     * controller for each player.
     */
    fun forGame(saveName: String?): ControlsSet {
        val a = all()
        val g = saveName?.let { game(it) } ?: return a
        return ControlsSet(List(4) { i -> g.players[i].copy(device = a.players[i].device) })
    }


    /** Profiles (Controls extras), `profiles/<name>.json`, newest first. */
    fun profiles(): List<Profile> {
        val fallback = ControlsSet.defaults().players[0]
        return profilesDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f -> read(f)?.let { Profile.fromJson(it, fallback) }?.takeIf { it.name.isNotBlank() } }
            ?.sortedByDescending { it.savedAt } ?: emptyList()
    }

    /** Saves [p], replacing a profile of the same name. */
    fun saveProfile(p: Profile) {
        profiles().firstOrNull { it.name.equals(p.name, ignoreCase = true) && it.name != p.name }?.let { removeProfile(it.name) }
        write(profileFile(p.name), p.toJson())
    }

    fun removeProfile(name: String) {
        val f = profileFile(name)
        if (!f.delete() && f.exists()) JLog.e(TAG, "can't remove profile $name")
    }

    /** Renames a profile; the saved time stays. False when it doesn't exist or [new] is taken by another. */
    fun renameProfile(old: String, new: String): Boolean {
        val all = profiles()
        val p = all.firstOrNull { it.name == old } ?: return false
        if (new.isBlank() || all.any { it.name != old && it.name.equals(new, ignoreCase = true) }) return false
        // The new file first and the old one after: a failed write keeps the profile (code audit 3, p4 L8). Names that
        // differ only in case share one file.
        if (!written(profileFile(new), p.copy(name = new).toJson())) return false
        if (profileFile(new) != profileFile(old)) removeProfile(old)
        return true
    }

    /** Controls › Connecting (approved newpad3 mockup 2026-11-17): `connecting.json`, the same for every game. */
    private val connectingFile = File(dir, "connecting.json")
    fun connecting(): ConnectSettings = read(connectingFile)?.let { ConnectSettings.fromJson(it) } ?: ConnectSettings()
    fun saveConnecting(c: ConnectSettings) = write(connectingFile, c.toJson())

    fun hotkeys(): Hotkeys = read(hotkeysFile)?.let { Hotkeys.fromJson(it) } ?: Hotkeys()
    fun saveHotkeys(h: Hotkeys) = write(hotkeysFile, h.toJson())

    private fun gameFile(saveName: String) = File(games, safe(saveName) + ".json")
    private val profilesDir = File(dir, "profiles")
    private fun profileFile(name: String) = File(profilesDir, safe(name.lowercase()) + ".json")

    private fun read(f: File): JSONObject? {
        if (!f.isFile) return null
        return try { JSONObject(f.readText()) } catch (e: Exception) { JLog.e(TAG, "can't read ${f.name}: $e"); null }
    }

    private fun write(f: File, o: JSONObject) { written(f, o) }

    /** Writes [o] to [f] whole (a part file renamed in); false, logged, when it couldn't. */
    private fun written(f: File, o: JSONObject): Boolean = try {
        f.parentFile?.mkdirs()
        val part = File(f.parentFile, f.name + ".part")
        part.writeText(o.toString(1))
        if (!part.renameTo(f)) { f.delete(); if (!part.renameTo(f)) throw java.io.IOException("can't rename ${part.name}") }
        true
    } catch (e: Exception) {
        JLog.e(TAG, "can't write ${f.name}: $e"); false
    }

    companion object {
        /** [g]'s players over [all]'s: each takes only its own rows ([own]'s; for a file without it, see the class's note). */
        fun resolve(g: ControlsSet, own: org.json.JSONArray?, all: ControlsSet): ControlsSet = ControlsSet(List(4) { i ->
            val mine = g.players[i]; val base = all.players[i]
            val rows = own?.optJSONArray(i)?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
                ?: (mine.copy(device = base.device).differences(base) intersect mine.differences(ControlsSet.defaults().players[i].copy(device = mine.device)))
            base.withRowsFrom(mine, rows).copy(device = mine.device)
        })
        private const val TAG = "Joey64Controls"
        /** A save name as a file name: path separators and other unsafe characters become '_'. */
        fun safe(name: String) = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").take(150)
    }
}

/**
 * Auto pak (plan, Phase 4): the Rumble Pak for the games the core's list says use one (and don't
 * save only to a Controller Pak), the Controller Pak for the rest. Built by devtools/make-pakdb.py.
 */
object PakDb {
    @Volatile private var rumble: Set<String>? = null

    fun load(context: Context): Set<String> = rumble ?: try {
        context.assets.open("pakdb.tsv").bufferedReader().useLines { lines ->
            lines.map { it.trim().lowercase() }.filter { it.length == 32 && !it.startsWith("#") }.toHashSet()
        }
    } catch (e: Exception) {
        JLog.e("Joey64Controls", "can't read pakdb.tsv: $e"); emptySet()
    }.also { rumble = it }

    /** The pak Auto picks for the game with [md5] (null: unknown, the Controller Pak). */
    fun auto(rumbleGames: Set<String>, md5: String?): Pak =
        if (md5 != null && md5.lowercase() in rumbleGames) Pak.Rumble else Pak.Controller

    @Volatile private var cpOnly: Set<String>? = null

    /**
     * Games with no Rumble Pak support that use the Controller Pak (assets/pakonly.tsv, devtools/make-pakdb.py; Joey
     * 2026-11-16: "if cruisn doesnt support the rumble pak, then controller pak should be the only option for it").
     * They read any pak as a Controller Pak: Cruis'n USA with a Rumble Pak stops at "ERROR ACCESSING PAK!".
     */
    fun loadControllerOnly(context: Context): Set<String> = cpOnly ?: try {
        context.assets.open("pakonly.tsv").bufferedReader().useLines { lines ->
            lines.map { it.trim().lowercase() }.filter { it.length == 32 && !it.startsWith("#") }.toHashSet()
        }
    } catch (e: Exception) {
        JLog.e("Joey64Controls", "can't read pakonly.tsv: $e"); emptySet()
    }.also { cpOnly = it }

    fun controllerOnly(controllerOnlyGames: Set<String>, md5: String?) = md5 != null && md5.lowercase() in controllerOnlyGames

    /** The pak [picked] gives the game with [md5]: Auto's pick, and the Controller Pak where a Controller-Pak-only game has the Rumble Pak. */
    fun resolve(picked: Pak, rumbleGames: Set<String>, controllerOnlyGames: Set<String>, md5: String?): Pak = when {
        picked == Pak.Auto -> auto(rumbleGames, md5)
        picked == Pak.Rumble && controllerOnly(controllerOnlyGames, md5) -> Pak.Controller
        else -> picked
    }
}
