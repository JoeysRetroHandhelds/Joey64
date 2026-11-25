package com.joeysretro.joey64.netplay

import java.io.File

/**
 * Automatic rollback (Joey 2026-11-24: "No setting. The room uses rollback when every device can afford it, else
 * today's shared delay"). Each game in a room tells the room how many inputs ahead this device can guess
 * (N64_NETPLAY_RB_CAP, read by the core at its registration); the room runs rollback at the smallest of them, or its
 * shared delay when any is 0 or a game is from a build that doesn't say.
 *
 * Rollback keeps [WINDOW] * 2 + 6 states (about 17 MB each) and, on a wrong guess, runs the frames since it again, each
 * with its state kept, inside one frame: affording it means that fits in a frame. Mario Kart 64 rooms on a 45 ms +-25 ms
 * internet (rb26r, rba1, rba2, 2026-11-24), rollbacks 4-5 frames deep about twice a second:
 *  - Odin 3 Portal (Adreno 830) and Odin 2 Portal (740): a state 0.44 / 0.75 ms, worst frame 14-16 ms, no stutter
 *    from rollback;
 *  - Retroid Pocket 5 (650) and RG477M (Mali-G615): a state 2.0-2.8 ms, worst frames 17-49 ms, 71-102 stutters a minute
 *    as the joiner: about one a rollback.
 * So the Adreno 730 and up (8 Gen 1 and newer) can, with [MIN_MEM_MB] of memory and a core of [MIN_CPU_MHZ]; the rest,
 * and a GPU we can't tell, keep the shared delay until a state costs less there.
 */
object RollbackCap {
    const val WINDOW = 12
    /** ActivityManager's totalMem on a 4 GB device reads about 3.6 GB. */
    const val MIN_MEM_MB = 3400L
    const val MIN_CPU_MHZ = 2200L
    const val MIN_ADRENO = 730

    /** The window for a device with [totalMemMb], its fastest core's [maxCpuMhz] and the GPU [familyKey]. */
    fun of(totalMemMb: Long, maxCpuMhz: Long, familyKey: String?): Int {
        if (totalMemMb in 1 until MIN_MEM_MB) return 0
        if (maxCpuMhz in 1 until MIN_CPU_MHZ) return 0
        val k = familyKey ?: return 0
        val n = k.dropWhile { !it.isDigit() }.toIntOrNull() ?: return 0
        return if (k.startsWith("adreno") && n >= MIN_ADRENO) WINDOW else 0
    }

    /** The fastest core's top clock in MHz, 0 when none can be read. */
    fun maxCpuMhz(): Long = (0 until Runtime.getRuntime().availableProcessors()).maxOfOrNull { i ->
        try { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1000 } catch (e: Exception) { 0L }
    } ?: 0L
}
