package com.joeysretro.joey64.video

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A PNG pack waiting to be converted, with the library game it's for. */
data class TexJob(val pack: TexPack, val romName: String, val save: String?, val title: String)

/**
 * What the conversion is doing, as TexConvertService writes it to `status.json` for the screens in Joey64's own
 * process. [cur] is the pack on now (null: none); it's only current while [at] is recent (the service writes at least
 * every few seconds, waiting included), so a killed service doesn't leave a bar standing.
 */
data class TexConvStatus(
    val cur: Cur?,
    /** Packs after [cur] in this run. */
    val after: Int,
    /** Packs waiting for room, by id: needed and free bytes. */
    val room: Map<String, Pair<Long, Long>>,
    /** Packs that couldn't be converted this run, by id: why. */
    val failed: Map<String, String>,
    val at: Long,
) {
    /** [step] 1 copies (bytes), 2 makes the pack file (textures); [playing] is the game being played while it waits. */
    data class Cur(val id: String, val shown: String, val step: Int, val done: Long, val total: Long, val playing: String?) {
        val percent get() = if (total <= 0) 0 else ((done * 100) / total).toInt().coerceIn(0, 99)
    }

    /** Still being written (the service is alive). */
    val fresh get() = System.currentTimeMillis() - at < STALE_MS

    fun toJson(): JSONObject = JSONObject().put("at", at).put("after", after)
        .put("cur", cur?.let { JSONObject().put("id", it.id).put("shown", it.shown).put("step", it.step).put("done", it.done).put("total", it.total).put("playing", it.playing) })
        .put("room", JSONObject().also { o -> room.forEach { (k, v) -> o.put(k, JSONArray().put(v.first).put(v.second)) } })
        .put("failed", JSONObject(failed as Map<*, *>))

    companion object {
        const val STALE_MS = 15_000L

        fun of(o: JSONObject): TexConvStatus = TexConvStatus(
            o.optJSONObject("cur")?.let { c -> Cur(c.getString("id"), c.optString("shown"), c.optInt("step", 1), c.optLong("done"), c.optLong("total"),
                c.optString("playing").takeIf { it.isNotEmpty() && !c.isNull("playing") }) },
            o.optInt("after"),
            o.optJSONObject("room")?.let { r -> r.keys().asSequence().associateWith { k -> r.getJSONArray(k).let { it.getLong(0) to it.getLong(1) } } } ?: emptyMap(),
            o.optJSONObject("failed")?.let { f -> f.keys().asSequence().associateWith { k -> f.getString(k) } } ?: emptyMap(),
            o.optLong("at"))

        fun read(store: TexPackStore): TexConvStatus? = StoreFiles.readJson(store.statusFile)?.let { try { of(it) } catch (e: Exception) { null } }
    }
}

/**
 * Converts PNG packs in the background (Joey 2026-11-15, approved mockup texpacks4): copies a pack's PNGs into Joey64's
 * storage (picking up where it left off), then has the core make the .hts from them (TexBuild), one pack at a time,
 * outside play. A foreground service in its own process (:texconv), so it goes on after the user leaves Joey64 and a
 * crash in it never takes a game down; its notification shows how far it is. While a game is played (the game screen
 * writes TexPackStore.playingFile) it waits where it is. A pack with no room waits for room (tried again when Joey64
 * next opens) instead of failing. Started by TexConvert.kick from Joey64's screens.
 */
class TexConvertService : Service() {
    private var worker: Thread? = null
    private lateinit var store: TexPackStore
    private lateinit var nm: NotificationManager
    private var wake: PowerManager.WakeLock? = null

    @Volatile private var status = TexConvStatus(null, 0, emptyMap(), emptyMap(), 0)
    private var lastWrite = 0L
    private var lastNote = 0L
    private var lastPlayCheck = 0L
    private var playingTitle: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /** Android's time limit for this kind of service ran out (release audit 2026-11-17): the worker stops at its next step. */
    private class TimedOut : RuntimeException()
    @Volatile private var timedOut = false

    /**
     * Android 15+: a dataSync foreground service has 6 hours a day, and one still running a few seconds after this call
     * ends the app with an error (the converter waits while a game is played, so a long session could reach it). It stops
     * here: the copy picks up where it left off and Joey64's screens start it again when there's time (TexConvert.kick;
     * startForeground's refusal, out of time, is already caught).
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        JLog.w(TAG, "texture packs: Android's background time for today ran out, stopping (goes on later)")
        timedOut = true
        wake?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        store = TexPackStore(applicationContext)
        nm = getSystemService(NotificationManager::class.java)
        channel()
        val first = note("Converting texture packs", "Getting ready", null, ongoing = true)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(RUNNING_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(RUNNING_ID, first)
        } catch (e: Exception) {
            JLog.e(TAG, "texture packs: can't run in the background: $e"); stopSelf(); return START_NOT_STICKY
        }
        if (worker == null) worker = Thread({ runAll() }, "texconv").apply { priority = Thread.MIN_PRIORITY; start() }
        return START_NOT_STICKY
    }

    private fun runAll() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        val done = HashSet<String>()
        val room = HashMap<String, Pair<Long, Long>>()
        val failed = HashMap<String, String>()
        val ready = ArrayList<TexJob>()
        try {
            while (!timedOut) {
                val jobs = TexConvert.pending(applicationContext).filter { it.pack.id !in done }
                val job = jobs.firstOrNull() ?: break
                done.add(job.pack.id)
                val (need, free) = store.room(job.pack)
                if (free < need) {
                    JLog.w(TAG, "texture packs: ${job.pack.shown} waits for room: needs ${TexPacks.sizeLabel(need)}, ${TexPacks.sizeLabel(free)} free")
                    room[job.pack.id] = need to free
                    publish(status.copy(cur = null, room = room.toMap()), force = true)
                    continue
                }
                failed.remove(job.pack.id)
                val why = convert(job, jobs.size - 1, room, failed)
                if (why == null) ready.add(job) else { failed[job.pack.id] = why; JLog.w(TAG, "texture packs: ${job.pack.shown} not converted: $why") }
            }
        } catch (e: TimedOut) {
            JLog.i(TAG, "texture packs: stopped for Android's time limit")
        } catch (e: Throwable) {
            JLog.e(TAG, "texture packs: conversion stopped: $e")
        } finally {
            publish(TexConvStatus(null, 0, room.toMap(), failed.toMap(), System.currentTimeMillis()), force = true)
            wake?.let { if (it.isHeld) it.release() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            for (j in ready) nm.notify(resultId(j.pack.id), note("${j.pack.shown} is ready", "HD textures from the game's next start", null, ongoing = false, job = j))
            for ((id, r) in room) TexConvert.jobFor(applicationContext, id)?.let { j ->
                nm.notify(resultId(id), note("${j.pack.shown} waits for room",
                    "Needs ${TexPacks.sizeLabel(r.first)}, ${TexPacks.sizeLabel(r.second)} free. Goes on by itself once there's room.", null, ongoing = false, job = j))
            }
            worker = null
            stopSelf()
        }
    }

    /** One pack: copy, then build. Null when it's ready, else why not. */
    private fun convert(job: TexJob, after: Int, room: Map<String, Pair<Long, Long>>, failed: Map<String, String>): String? {
        val p = job.pack
        fun cur(step: Int, done: Long, total: Long) = TexConvStatus.Cur(p.id, p.shown, step, done, total, playingTitle)
        nm.cancel(resultId(p.id)) // a note from an earlier conversion of this pack ("is ready") no longer holds
        publish(TexConvStatus(cur(1, 0, p.size), after, room, failed, 0), force = true)
        awake(true)
        val copied = store.copyPngs(p, job.romName, { d, t -> publish(status.copy(cur = cur(1, d, t))) }, { hold(job, after); if (timedOut) throw TimedOut() })
        if (timedOut) throw TimedOut()
        if (copied.why != null) return copied.why
        // Step 2: the core makes the .hts, outside play.
        val core = TexBuild.corePath(applicationContext)
        if (!TexBuild.supported(core)) {
            // An older core: the game makes it at its next start, as before.
            JLog.w(TAG, "texture packs: this core can't make packs outside play; ${p.shown} is made at the game's next start")
            store.markCopied(p)
            return null
        }
        hold(job, after)
        if (timedOut) throw TimedOut()
        publish(status.copy(cur = cur(2, 0, copied.pngs.toLong())), force = true)
        val t0 = System.currentTimeMillis()
        val n = TexBuild.build(core, store.convDir(p).path, job.romName) { k ->
            publish(status.copy(cur = cur(2, k.toLong(), copied.pngs.toLong())))
            hold(job, after)
        }
        val hts = store.converted(p)
        if (n <= 0 || hts == null || !TexPacks.htsWhole(hts)) return "the pack file couldn't be made"
        store.dropPngs(p)
        JLog.i(TAG, "texture packs: ${p.shown} made into ${hts.name} (${TexPacks.sizeLabel(hts.length())}, $n textures) in ${(System.currentTimeMillis() - t0) / 1000} s")
        return null
    }

    /** Waits here while a game is played (the game screen's process is alive and its file is there); no CPU held meanwhile. */
    private fun hold(job: TexJob, after: Int) {
        val now = System.currentTimeMillis()
        if (now - lastPlayCheck < 1000) return
        lastPlayCheck = now
        var waited = false
        while (!timedOut) {
            val game = playing() ?: break
            if (!waited) { waited = true; awake(false); JLog.i(TAG, "texture packs: waiting while $game is played") }
            playingTitle = game
            status.cur?.let { publish(status.copy(cur = it.copy(playing = game), after = after), force = true) }
            Thread.sleep(2000)
        }
        if (waited) {
            playingTitle = null
            status.cur?.let { publish(status.copy(cur = it.copy(playing = null)), force = true) }
            awake(true)
            JLog.i(TAG, "texture packs: going on with ${job.pack.shown}")
        }
    }

    /** The game being played, or null (no file, or its process is gone: then the file goes too). */
    private fun playing(): String? {
        val f = store.playingFile
        val lines = try { if (f.isFile) f.readLines() else return null } catch (e: Exception) { return null }
        val pid = lines.firstOrNull()?.trim()?.toIntOrNull() ?: return null
        if (!File("/proc/$pid").exists()) { f.delete(); return null }
        return lines.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "a game"
    }

    private fun awake(on: Boolean) {
        if (on) {
            val w = wake ?: getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "joey64:texconv").also { wake = it }
            if (!w.isHeld) w.acquire(30 * 60_000L)
        } else wake?.let { if (it.isHeld) it.release() }
    }

    /** Writes the status (at most twice a second unless [force]) and updates the running notification (once a second). */
    private fun publish(s: TexConvStatus, force: Boolean = false) {
        val now = System.currentTimeMillis()
        status = s.copy(at = now)
        if (force || now - lastWrite >= 500) { lastWrite = now; StoreFiles.writeJson(store.statusFile, status.toJson(), TAG) }
        val c = status.cur ?: return
        if (!force && now - lastNote < 1000) return
        lastNote = now
        val stepText = if (c.step == 1) "Step 1 of 2 · Copying the PNGs" else "Step 2 of 2 · Making the pack file"
        val more = if (status.after > 0) " · ${status.after} more after it" else ""
        val n = if (c.playing != null) note("${c.shown} waits while you play", "Step ${c.step} of 2 · ${c.percent}% · goes on when you stop$more", c.percent, ongoing = true)
            else note("Converting ${c.shown}", "$stepText · ${c.percent}%$more", c.percent, ongoing = true)
        nm.notify(RUNNING_ID, n)
    }

    private fun channel() {
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Texture packs", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Converting texture packs made of PNGs"
            })
    }

    /** A notification; tapping it opens the game's Texture pack panel (Settings › Video with the game's chip), when [job] is known. */
    private fun note(title: String, text: String, percent: Int?, ongoing: Boolean, job: TexJob? = null): Notification {
        val j = job ?: status.cur?.id?.let { TexConvert.jobFor(applicationContext, it) }
        val open = Intent().setClassName(this, "com.joeysretro.joey64.launcher.LauncherActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (j?.save != null) open.putExtra(TexConvert.EXTRA_SAVE, j.save).putExtra(TexConvert.EXTRA_TITLE, j.title)
        val pi = PendingIntent.getActivity(this, j?.pack?.id?.hashCode() ?: 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_joey64)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .apply { if (percent != null) setProgress(100, percent, false) }
            .build()
    }

    companion object {
        private const val TAG = "Joey64TexPacks"
        const val CHANNEL = "texpacks"
        private const val RUNNING_ID = 6401
        private const val RESULT_ID = 6500

        /** The id of a pack's result note ("is ready", "waits for room"). */
        fun resultId(packId: String) = RESULT_ID + packId.hashCode()
    }
}
