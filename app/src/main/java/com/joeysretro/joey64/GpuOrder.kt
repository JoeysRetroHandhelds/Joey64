package com.joeysretro.joey64

import android.content.Context
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The players' reports' fastest drivers per GPU (approved drvspeed mockup 2026-11-15, screen 4): joey64-data's
 * `drivers/order.json` on release, made by devtools/driver-order.py from the reports server's sessions (each driver's
 * work a frame on the games played on more than one driver). {"version":1,"gpus":{"adreno 750":["armsx2","turnip"]}},
 * GPU names as [key] makes them, driver ids as [GpuRules.Driver]. The launcher looks at it again when the kept one is
 * 15 minutes old ([refresh], off the main thread; [DataFeed]); the game screen only reads the kept copy ([cached]).
 */
object GpuOrder {
    /** "Adreno (TM) 750", "Turnip Adreno 750" -> "adreno 750": the system's and Turnip's names of one GPU alike. */
    fun key(gpuName: String): String = gpuName.lowercase().replace("(tm)", " ").replace(Regex("^\\s*turnip\\s+"), "")
        .replace(Regex("\\s+"), " ").trim()

    fun parse(text: String): Map<String, List<String>> {
        val o = org.json.JSONObject(text)
        if (o.optInt("version") != 1) return emptyMap()
        val g = o.optJSONObject("gpus") ?: return emptyMap()
        val out = HashMap<String, List<String>>()
        for (k in g.keys()) {
            val a = g.optJSONArray(k) ?: continue
            out[key(k)] = (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> GpuRules.Driver.byId(s) != null } }.distinct()
        }
        return out
    }

    /**
     * The chain level the list puts first for this GPU, when it ranks that driver ahead of the one at [good]: the
     * list's first driver that's in [chain]; null when the list has nothing for it, or [good]'s driver is as high.
     */
    fun pick(order: List<String>?, chain: List<GpuRules.Driver>, good: Int): Int? {
        if (order.isNullOrEmpty()) return null
        val ids = chain.map { it.id }
        val first = order.firstOrNull { it in ids } ?: return null
        val goodId = ids.getOrNull(good)
        val goodAt = order.indexOf(goodId).let { if (it < 0) Int.MAX_VALUE else it }
        return if (order.indexOf(first) < goodAt) ids.indexOf(first) else null
    }

    private fun file(ctx: Context) = File(File(ctx.filesDir, "drivers"), "order.json")

    /** The kept list for [gpuName], no network. */
    fun cached(ctx: Context, gpuName: String): List<String>? = try {
        val f = file(ctx)
        if (f.isFile && f.length() > 0) parse(f.readText())[key(gpuName)] else null
    } catch (e: Exception) { null }

    /** Looks at the list again when the kept one is 15 minutes old (or missing). Network: call off the main thread. */
    fun refresh(ctx: Context) {
        val f = file(ctx)
        if (!DataFeed.due(f)) return
        try {
            val got = DataFeed.get(base(ctx) + PATH, f, 256 * Capped.KB, "order.json")
            if (got !is DataFeed.Got.New) return
            val text = String(got.bytes, Charsets.UTF_8)
            parse(text)
            f.parentFile?.mkdirs()
            val part = File(f.parentFile, f.name + ".part")
            part.writeText(text)
            if (!part.renameTo(f)) { part.delete(); throw IOException("can't keep ${f.name}") }
            DataFeed.keep(f, got)
        } catch (e: FileNotFoundException) {
            // No list published yet: kept empty, looked at again like a kept list.
            f.parentFile?.mkdirs(); f.writeText(""); DataFeed.forget(f)
        } catch (e: Exception) {
            JLog.w(TAG, "driver order not read ($e), ${if (f.exists()) "using the kept one" else "none yet"}")
        }
    }

    /** joey64-data's release branch; debug builds read a stand-in from files/dev_drivers_base (a feed on the PC). */
    private fun base(ctx: Context): String {
        if (BuildConfig.DEBUG) {
            val dev = try { File(ctx.filesDir, "dev_drivers_base").readText().trim() } catch (e: IOException) { "" }
            if (dev.isNotEmpty()) return dev.trimEnd('/') + "/"
        }
        return com.joeysretro.joey64.staterequest.StateRequests.BASE
    }

    private const val PATH = "drivers/order.json"
    private const val TAG = "Joey64Drivers"
}
