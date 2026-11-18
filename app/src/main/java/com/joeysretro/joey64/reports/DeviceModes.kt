package com.joeysretro.joey64.reports

import android.content.Context
import android.os.PowerManager
import android.provider.Settings

/**
 * The device's own speed modes when a game starts, for reports and the session log (Joey 2026-11-18: a player's Smash
 * ran below full speed at 2x with run-ahead and the report couldn't say whether the device was in its performance
 * mode). Handheld makers keep them in Settings.System under their own names: AYN and Retroid `performance_mode` and
 * `fan_mode`, AYANEO / KONKR `open_performance_mode`, Anbernic `cpu_mode`; any other name like them is read too, so a
 * device we haven't seen still says what it has. Plus Android's battery saver. Read only, never written.
 */
object DeviceModes {
    /** Names a maker's speed or fan mode goes by. Not per-button turbo or equalizer boosts. */
    internal val MODE_LIKE = Regex("(^|_)(performance|perf|fan|cpu|gpu|power)(_|$)", RegexOption.IGNORE_CASE)
    private val NOT_MODES = Regex("gamepad|turbo_[a-z0-9]+$|equalizer|thermal_management", RegexOption.IGNORE_CASE)
    internal const val MAX = 8

    /** Settings name -> value, sorted, at most [MAX], plus "power_saver" on / off. */
    fun read(context: Context): Map<String, String> {
        val found = sortedMapOf<String, String>()
        try {
            context.contentResolver.query(Settings.System.CONTENT_URI, arrayOf("name", "value"), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    if (keep(n)) found[n] = (c.getString(1) ?: "").take(32)
                }
            }
        } catch (e: Exception) { }
        val out = LinkedHashMap<String, String>()
        for ((k, v) in found) { if (out.size >= MAX) break; out[k.take(40)] = v }
        try {
            val pm = context.getSystemService(PowerManager::class.java)
            if (pm != null) out["power_saver"] = if (pm.isPowerSaveMode) "on" else "off"
        } catch (e: Exception) { }
        return out
    }

    internal fun keep(name: String) = MODE_LIKE.containsMatchIn(name) && !NOT_MODES.containsMatchIn(name)

    fun line(m: Map<String, String>) = if (m.isEmpty()) "none" else m.entries.joinToString(" ") { "${it.key}=${it.value}" }
}
