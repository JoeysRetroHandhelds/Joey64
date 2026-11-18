package com.joeysretro.joey64.video

import android.content.Context
import java.io.File

/**
 * The note in Settings › Video › Resolution (approved mockup resnote layout A, Joey 2026-11-16: "A, rule as is"; Joey
 * 2026-11-15: "based on specs", never by model name): one box under the panel's text when this device's memory or GPU
 * makes the higher sizes slow. It never changes the setting; Native stays the default everywhere.
 *
 * - 2 GB of memory or less (total RAM up to 2.2 GB as Android reports it): [RAM]. Pocket Block (1934 MB): every test
 *   game below 60 fps at 2x, and 4x died at start twice.
 * - A GPU in the lower tier: an Adreno below 730 (the Duo's 722: GoldenEye's mission folder 53.8 fps at 2x) or a
 *   Mali-G31/G51/G52/G57 (the Flip's G52: every bench game below 60 at 2x): [GPU].
 * - Both: the 2 GB text, the stronger one.
 * - A GPU in the middle tier: an Adreno 730-749 (8 Gen 1, 8 Gen 2; Joey 2026-11-17 "If 8 Gen 2 can't run 4x we
 *   should have the same warning ... 2x+sharp as it looks just as good as 4x"; Thor reports 53-57 4x: Mario Tennis
 *   51.3 fps with 320 slow frames in 72 s, Smash 50.8): [GPU4X], with "Use 2x and the Sharp look" in the box
 *   (approved resnote2 mockup A). The 750 isn't measured, so it has none; the Odin 3's 830 holds 4x.
 * - None of these: no note.
 *
 * The launcher doesn't open Vulkan, so the GPU comes from what the game screen found at its last start ([remember],
 * "gpu_family" in the "device" prefs), else from kgsl's gpu_model (Adreno only); a Mali shows its note after the
 * first game has started.
 */
object ResNote {
    const val GPU = "On this device 2x and higher can run below full speed in heavier games."
    const val RAM = "This device has 2 GB of memory: 4x and 8x may not start, and 2x runs below full speed in most games."
    const val GPU4X = "On this device 4x and higher can run below full speed in heavier games. 2x with the Sharp look is almost as crisp."
    const val SHARP_LINK = "Use 2x and the Sharp look"
    /**
     * Settings › Emulation › Run-ahead's note (approved runahead mockup screen 2, Joey 2026-11-17 "Yes, show it"): the
     * same lower tier as [GPU] and [RAM] (Flip 2 frames 27-34 fps uncapped, Duo 4 frames 54-65); not the middle tier.
     */
    const val RUN_AHEAD = "On this device run-ahead can run below full speed."

    /** Run-ahead's note for this device's Resolution [note], or null. */
    fun runAhead(note: String?): String? = if (note == GPU || note == RAM) RUN_AHEAD else null

    /** Whether the GPU of [familyKey] is in the middle tier (8 Gen 1 / 8 Gen 2: 4x slows in heavier games). */
    fun midGpu(familyKey: String?): Boolean {
        val k = familyKey ?: return false
        return k.startsWith("adreno") && (k.removePrefix("adreno").toIntOrNull() ?: return false) in 730..749
    }

    /** Whether [note] gets the "Use 2x and the Sharp look" line, shown while the setting isn't already 2x + Sharp. */
    fun sharpLink(note: String?, resolution: String, look: String) = note == GPU4X && !(resolution == "2" && (look == "sharp" || look == "auto"))

    /** 2.2 GB, in bytes, as ActivityManager's totalMem counts them. */
    const val RAM_LIMIT = 2_200L * 1024 * 1024

    private val LOW_MALI = setOf(31, 51, 52, 57)

    /** Whether the GPU of [familyKey] (GpuRules.familyKey: "adreno722", "mali52") is in the lower tier. */
    fun lowGpu(familyKey: String?): Boolean {
        val k = familyKey ?: return false
        val n = k.dropWhile { !it.isDigit() }.toIntOrNull() ?: return false
        return when {
            k.startsWith("adreno") -> n in 1..729
            k.startsWith("mali") -> n in LOW_MALI
            else -> false
        }
    }

    /** The note for a device with [totalMem] bytes and the GPU [familyKey], or null for none. */
    fun text(totalMem: Long, familyKey: String?): String? = when {
        totalMem in 1..RAM_LIMIT -> RAM
        lowGpu(familyKey) -> GPU
        midGpu(familyKey) -> GPU4X
        else -> null
    }

    /** This device's note (reads the memory and the kept GPU; cheap enough for the main thread). */
    fun of(context: Context): String? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val total = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
        val kept = context.getSharedPreferences("device", Context.MODE_PRIVATE).getString(PREF, null)
        return text(total, kept ?: kgslFamily())
    }

    /** The game screen keeps the GPU it found ([com.joeysretro.joey64.GpuRules.familyKey]) for [of]. */
    fun remember(context: Context, familyKey: String) {
        context.getSharedPreferences("device", Context.MODE_PRIVATE).edit().putString(PREF, familyKey).apply()
    }

    private fun kgslFamily(): String? = try {
        File("/sys/class/kgsl/kgsl-3d0/gpu_model").readText().trim().takeIf { it.isNotEmpty() }
            ?.let { com.joeysretro.joey64.GpuRules.familyKey(com.joeysretro.joey64.GpuRules.family(0x5143, "", it)) }
    } catch (e: Exception) { null }

    private const val PREF = "gpu_family"
}
