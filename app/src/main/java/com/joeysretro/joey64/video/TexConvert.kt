package com.joeysretro.joey64.video

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import com.joeysretro.joey64.JLog

/**
 * PNG packs' background conversion, from Joey64's own process (approved mockup texpacks4): which packs wait
 * ([pending]), starting TexConvertService for them ([kick]: when Joey64 opens, a folder is added, the Video tab is
 * read, a game starts with a pack not ready), and the service's status for the screens ([status], read by [refresh]).
 */
object TexConvert {
    const val EXTRA_SAVE = "texpack_save"
    const val EXTRA_TITLE = "texpack_title"
    private const val TAG = "Joey64TexPacks"
    private const val PREFS = "texpacks"
    private const val ASKED = "askedNotifications"

    /** The service's last status (null: none written yet); the screens poll it while they show a conversion. */
    val status = mutableStateOf<TexConvStatus?>(null)

    /** A conversion notification was tapped: the game (save name, title) whose Texture pack row Settings › Video opens on. */
    val openPanel = mutableStateOf<Pair<String, String>?>(null)

    fun refresh(context: Context) { status.value = TexConvStatus.read(TexPackStore(context)) }

    /** The PNG packs for library games that aren't made into a .hts yet (nor left for the game to make, with an older core). */
    fun pending(context: Context): List<TexJob> {
        val store = TexPackStore(context)
        val index = store.index() ?: return emptyList()
        if (index.none { it.png }) return emptyList()
        val db = com.joeysretro.joey64.launcher.data.LibraryDb.get(context)
        val games = try { db.libraryGames().filter { !it.packName.isNullOrEmpty() && it.hiddenAt == null }.groupBy { it.packName!!.uppercase() } }
            catch (e: Exception) { return emptyList() }
        val launcher by lazy { com.joeysretro.joey64.launcher.library.GameLauncher(context, db) }
        return index.filter { it.png }.mapNotNull { p ->
            val g = games[p.packName.uppercase()]?.firstOrNull() ?: return@mapNotNull null
            if (store.isConverted(p) || store.isCopied(p)) return@mapNotNull null
            TexJob(p, g.packName!!, try { launcher.saveNameFor(g) } catch (e: Exception) { null }, g.title)
        }
    }

    fun jobFor(context: Context, id: String): TexJob? = pending(context).firstOrNull { it.pack.id == id }
        ?: TexPackStore(context).index()?.firstOrNull { it.id == id }?.let { p -> TexJob(p, p.packName, null, p.shown) }

    /** Starts the conversion when packs wait and it isn't running already. Off the main thread; only from a screen Joey64 shows. */
    fun kick(context: Context) {
        val app = context.applicationContext
        val running = TexConvStatus.read(TexPackStore(app))?.let { it.fresh && it.cur != null } == true
        if (running) return
        val jobs = pending(app)
        if (jobs.isEmpty()) return
        try {
            androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, TexConvertService::class.java))
            JLog.i(TAG, "texture packs: converting ${jobs.size} in the background: ${jobs.joinToString { it.pack.shown }}")
        } catch (e: Exception) { JLog.w(TAG, "texture packs: can't start converting: $e") }
    }

    /**
     * A pack folder is being removed: a conversion of one of its packs stops now (its process is ended; the next scan
     * deletes what it made, and [kick] goes on with the rest).
     */
    fun stopFor(context: Context, folderUri: String) {
        val store = TexPackStore(context)
        val st = TexConvStatus.read(store)?.takeIf { it.fresh } ?: return
        val id = st.cur?.id ?: return
        if (store.index()?.firstOrNull { it.id == id }?.folder != folderUri) return
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        am.runningAppProcesses?.firstOrNull { it.processName.endsWith(":texconv") }?.let { proc ->
            JLog.i(TAG, "texture packs: stopping the conversion of ${st.cur.shown} (its folder was removed)")
            android.os.Process.killProcess(proc.pid)
        }
        store.statusFile.delete()
    }

    /**
     * The game screen tells the conversion a game is played (it waits) or not ([title] null). Its process id goes with it,
     * so a game screen that dies without saying so doesn't hold the conversion.
     */
    fun playing(context: Context, title: String?) {
        val f = TexPackStore(context).playingFile
        try {
            if (title == null) f.delete() else { f.parentFile?.mkdirs(); f.writeText("${android.os.Process.myPid()}\n$title") }
        } catch (e: Exception) { JLog.w(TAG, "texture packs: playing file: $e") }
    }

    /** Screen 1 of texpacks4 is shown once: Android 13+, not on a TV, notifications not allowed yet, not asked before. */
    fun shouldAsk(context: Context): Boolean = Build.VERSION.SDK_INT >= 33 &&
        !com.joeysretro.joey64.launcher.layout.Tv.isTv(context) &&
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED, false)

    fun asked(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ASKED, true).apply()
}
