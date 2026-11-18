package com.joeysretro.joey64.video

import android.content.Context
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.controls.ControlsStore
import org.json.JSONObject
import java.io.File

/**
 * Settings › Video (approved mockup, 2026-11-07: scratchpad build_video.py). Every setting is a
 * key with a string value, so All games and a game's own settings are the same kind of map: a
 * game stores only the keys changed for it (the yellow dot), and Y removes one ("Use All games").
 */
enum class VideoKey(val key: String, private val base: String) {
    Resolution("resolution", "1"),
    Aspect("aspect", "auto"),
    /** Crop edges (approved mockup 2026-11-09): lines of the N64's 240 off every edge, "0" off (display_opts.h cropOf). */
    Crop("crop", "0"),
    /** Crop edges › Trim the game's border (Joey 2026-11-12, on by default): the black a game leaves round its picture (display_opts.h cutOf). */
    Trim("trim_border", "on"),
    Integer("integer", "off"),
    Orientation("orientation", "landscape"),
    Cutout("cutout", "off"),
    Brightness("brightness", "0"),
    Contrast("contrast", "0"),
    Saturation("saturation", "0"),
    Gamma("gamma", "10"),
    Sharpen("sharpen", "off"),
    SharpenStrength("sharpen_strength", "50"),
    /**
     * Picture rows (approved mockup 2026-11-08; video/Picture.kt): auto, none, a stack "ntsc+crt_heavy", "preset:<name>",
     * or "slang:<folder name>/<path>.slangp" (Your own RetroArch preset, video/Slang.kt).
     */
    Shader("shader", "auto"),
    /** Each adjusted shader's settings, "crt_heavy=60,0,1,20,10;lcd=50,30". */
    ShaderParams("shader_params", ""),
    ShaderScale("shader_scale", "auto"),
    /** A RetroArch preset's settings changed from its own, "name=value;name=value" (Slang.paramsOf). */
    SlangParams("slang_params", ""),
    Sides("sides", "none"),
    /** The picked sides image's file in files/video/images/. */
    SidesImage("sides_image", ""),
    /** Place the game in that image: "left,top,width" in tenths of a percent; empty = centred. */
    SidesPlace("sides_place", ""),
    /** Sides › RetroArch overlay: "raov:<folder>/<path>.cfg", or "match:<folder>" (Match each game; RaOverlay.kt). */
    SidesRa("sides_ra", ""),
    /** With Match each game: what a game without its own overlay gets (none, blur, frame). */
    SidesRaOther("sides_ra_other", "blur"),
    Overlay("overlay", "off"),
    OverlayImage("overlay_image", ""),
    OverlayOpacity("overlay_opacity", "30"),
    /** Colour's colour-blind help: off, red, green, blue (weak). */
    ColourBlind("colour_blind", "off"),
    Bfi("bfi", "off"),
    Renderer("renderer", "auto"),
    Driver("driver", "auto"),
    Pacing("pacing", "lowest"),
    // "auto" (Look Auto, the default since 2026-11-17, Joey: "Yes" to Sharp when upscaled): off at 2x and up, on at
    // Native (1x); [VideoSettings.filterOn] / [VideoApply.of] resolve it. "on" / "off" are a pick (Original, Sharp, a row).
    ViAa("vi_aa", "auto"),
    ViBilinear("vi_bilinear", "auto"),
    DitherFilter("dither_filter", "on"),
    Divot("divot", "on"),
    GammaDither("gamma_dither", "on"),
    Deinterlace("deinterlace", "weave"), // Weave since 2026-11-10 (Joey): upscaled, Bob shook still 480i pictures (THPS2, Spider-Man)
    Supersample("supersample", "off"),
    // On since 2026-11-15 (Joey: "Test on by default all games"): upscaled, Paper Mario's Shooting Star Summit stair tops
    // drew lavender (the LOD picked by the upscaled size). 82/82 on the Odin 3 4x, Duo 2x and RG477M 4x, same speed.
    NativeTexLod("native_tex_lod", "on"),
    NativeTexRect("native_tex_rect", "on"),
    /** Widescreen code (version A, 2026-11-08): "off"; All games "auto" (When a game has one); a game a version's cheat name. */
    Widescreen("widescreen", "off"),
    /** 60 fps code, stored as [Widescreen]. */
    Fps("fps60", "off"),
    /** Texture packs (approved mockup 2026-11-15; TexturePacks.kt): All games "on" / "off"; a game "on" (Auto), "off" (None) or "pack:<id>". */
    TexPack("texpack", "on");

    /**
     * The value a row has when nobody set it. Frame pacing is Steady on a screen running at 120 Hz (Joey 2026-11-15:
     * RG477M, Paper Mario at 30 fps: Lowest lag showed 9-14% of frames for 3 refreshes instead of 4, Steady none,
     * at the same poll-to-display, 19-33 ms); 60 Hz screens keep Lowest lag (Steady costs 8-16 ms there).
     */
    val default: String get() = if (this == Pacing && highRefresh) "steady" else base

    companion object {
        /** The screen runs at 100 Hz or more (set by [ScreenRefresh.update] when an activity starts). */
        @Volatile var highRefresh = false
        fun of(key: String) = entries.firstOrNull { it.key == key }
        /** The rows the N64 video filters' Look preset sets. */
        val LOOK_KEYS = listOf(ViAa, ViBilinear, DitherFilter, Divot, GammaDither)
        /** Rows that only do something when the game is drawn above Native (1x). */
        val UPSCALE_ONLY = setOf(Supersample, NativeTexLod, NativeTexRect)
        /** Rows read when the game starts, not while it runs. */
        val AT_START = setOf(Resolution, Renderer, Driver, Pacing, Supersample, NativeTexLod, NativeTexRect, Widescreen, Fps, TexPack)
    }
}

/** One choice of a setting: the stored value, what the row shows, and the line under it in the popup. */
data class VideoChoice(val value: String, val label: String, val detail: String? = null, val experimental: Boolean = false)

object VideoChoices {
    val resolution = listOf(
        // Each line names the size most games draw at (320 x 240 at native) and the screen it suits: the biggest scale
        // that still fits under that screen's height (Joey 2026-11-11: "show the resolutions / say best for 1080p / best for 4k").
        VideoChoice("1", "Native (1x)", "320 × 240 · the N64's own size: the most accurate and the fastest"),
        VideoChoice("2", "2x", "640 × 480 · best for 720p screens"),
        VideoChoice("4", "4x", "1280 × 960 · best for 1080p screens"),
        VideoChoice("8", "8x", "2560 × 1920 · best for 4K screens, strong devices only"))
    val aspect = listOf(
        VideoChoice("auto", "Auto", "4:3, or 16:9 when Widescreen is on"),
        VideoChoice("4:3", "4:3", "The N64's own shape"),
        VideoChoice("16:9", "16:9", "For widescreen cheats and hacks"),
        VideoChoice("stretch", "Stretch to the screen", "Fills the screen, the picture is pulled wider"))
    val onOff = listOf(VideoChoice("off", "Off"), VideoChoice("on", "On"))
    val orientation = listOf(
        VideoChoice("landscape", "Landscape"), VideoChoice("portrait", "Portrait", "For phones held upright"),
        VideoChoice("auto", "Follow the device", "Turns with the device"))
    val sharpen = listOf(
        VideoChoice("off", "Off"), VideoChoice("cas", "CAS", "Light, works at any resolution"),
        VideoChoice("fsr", "FSR", "Stronger, best with Resolution 2x or more"))
    val renderer = listOf(
        VideoChoice("auto", "Auto", "Picks by GPU and game"),
        VideoChoice("parallel", "parallel-RDP", "Draws like the real N64. Needs Vulkan"),
        VideoChoice("gliden64", "GLideN64", "The fallback for GPUs that can't run parallel-RDP"))
    val driver = listOf(
        VideoChoice("auto", "Auto", "Picks by GPU; on a GPU it hasn't run, tries the next driver until one shows a picture"),
        VideoChoice("bundled", "Joey64's Turnip", "Adreno GPUs only"),
        // Every bundled driver by name (2026-11-15, the driver trial's list: GpuRules.Driver), for testers to pick one.
        VideoChoice("turnip", "Turnip Gen8", "Adreno 6xx-8xx"),
        VideoChoice("faux123", "Turnip faux123", "Adreno 6xx-8xx, made for the 722"),
        VideoChoice("r2", "Turnip R2", "Adreno 6xx-8xx"),
        VideoChoice("apex", "Balemuni Apex", "Adreno 7xx-8xx, Android 13 and up"),
        VideoChoice("purple", "Mr Purple T30", "Adreno 6xx-7xx, Android 11 and up"),
        VideoChoice("purple19", "Mr Purple T19", "Adreno 6xx, Android 11 and up"),
        VideoChoice("duo", "Turnip Duo", "Adreno 722"),
        VideoChoice("turnipz", "TurnipZ", "Adreno 650, Android 11 and up"),
        VideoChoice("k11", "K11MCH1 Turnip", "Adreno 6xx-7xx"),
        VideoChoice("armsx2", "ARMSX2 Turnip", "Adreno 6xx-7xx"),
        VideoChoice("malisx2", "malisx2", "Mali GPUs"),
        VideoChoice("panvk", "PanVK", "Mali GPUs, Android 15 and up"),
        VideoChoice("system", "The system driver", "The one the device came with"))
    val pacing = listOf(
        VideoChoice("lowest", "Lowest lag", "Each frame shows as soon as it's ready"),
        VideoChoice("steady", "Steady", "Each frame shows for the same time on 120 Hz screens. More lag"),
        VideoChoice("timed", "Steady, less lag", "Steady, with frames timed to the screen", experimental = true))
    val look = listOf(
        VideoChoice("auto", "Auto", "Sharp at 2x and up, Original at Native"),
        VideoChoice("original", "Original", "The N64's own soft picture"),
        VideoChoice("sharp", "Sharp", "Edge smoothing and soft scaling off"),
        VideoChoice("sharper", "Sharper", "Dither smoothing off too, so the dither grain shows"))
    val deinterlace = listOf(VideoChoice("bob", "Bob", "Smooth, a little flicker"), VideoChoice("weave", "Weave", "Sharp, combing on movement"))
    /** All games' Widescreen and 60 fps (a game's own popup lists its versions instead). */
    val extrasAll = listOf(VideoChoice("off", "Off", "Only games you turn it on for"), VideoChoice("auto", "When a game has one"))
    /** Crop edges' named amounts; any other even amount up to 32 is Exact. */
    val crop = listOf(
        VideoChoice("0", "Off", "The whole picture, border and all"),
        VideoChoice("4", "Thin", "4 lines of 240 off each edge"),
        VideoChoice("8", "Normal", "8 lines: most games' black border"),
        VideoChoice("12", "Wide", "12 lines"),
        VideoChoice("16", "Widest", "16 lines"))
    val CROP_STEPS: List<Int> = (0..32 step 2).toList()
    val supersample = listOf(
        VideoChoice("off", "Off"), VideoChoice("1/2", "2x"), VideoChoice("1/4", "4x"), VideoChoice("1/8", "8x"))

    fun of(key: VideoKey): List<VideoChoice> = when (key) {
        VideoKey.Resolution -> resolution
        VideoKey.Aspect -> aspect
        VideoKey.Orientation -> orientation
        VideoKey.Sharpen -> sharpen
        VideoKey.Renderer -> renderer
        VideoKey.Driver -> driver
        VideoKey.Pacing -> pacing
        VideoKey.Deinterlace -> deinterlace
        VideoKey.Supersample -> supersample
        VideoKey.Widescreen, VideoKey.Fps -> extrasAll
        VideoKey.Crop -> crop
        else -> onOff
    }

    fun label(key: VideoKey, value: String): String = of(key).firstOrNull { it.value == value }?.label
        // A game's own Widescreen / 60 fps: the version's cheat name, as its popup names it.
        ?: if (key == VideoKey.Widescreen || key == VideoKey.Fps) GameExtras.fpsLabel(value)
        else if (key == VideoKey.Crop) "Exact, $value lines" else value
}

/** Video settings with every key filled in: All games over the defaults, then a game's own keys. */
data class VideoSettings(val values: Map<VideoKey, String>) {
    operator fun get(k: VideoKey): String = values[k] ?: k.default
    fun on(k: VideoKey) = get(k) == "on"
    /** A filter row as it plays: "auto" (Look Auto's edge smoothing and soft scaling) is on only at Native (1x), [scale] the resolution in use. */
    fun filterOn(k: VideoKey, scale: Int = int(VideoKey.Resolution)) = get(k).let { if (it == AUTO) scale < 2 else it == "on" }
    fun int(k: VideoKey) = get(k).toIntOrNull() ?: k.default.toInt()

    /** The Look preset these filter rows match, or "custom". */
    val look: String get() = LOOKS.entries.firstOrNull { (_, v) -> VideoKey.LOOK_KEYS.all { get(it) == v.getValue(it) } }?.key ?: "custom"

    /** What Look Auto gives at [scale]: "sharp" at 2x and up, else "original". */
    fun autoLook(scale: Int = int(VideoKey.Resolution)) = if (scale >= 2) "sharp" else "original"

    /** The Look's name; Auto says what it gives at this Resolution ("Auto · Sharp"), [at] adds the Resolution ("Auto · Sharp at 4x"). */
    fun lookLabel(at: Boolean = false): String {
        if (look != "auto") return VideoChoices.look.firstOrNull { it.value == look }?.label ?: "Custom"
        val gives = VideoChoices.look.first { it.value == autoLook() }.label
        val res = int(VideoKey.Resolution)
        return "Auto · $gives" + if (at) " at " + (if (res == 1) "Native" else "${res}x") else ""
    }

    companion object {
        const val AUTO = "auto"
        val DEFAULTS = VideoSettings(emptyMap())

        /** What each Look preset sets the filter rows to (the dev switches sharp and sharp2). */
        val LOOKS: Map<String, Map<VideoKey, String>> = linkedMapOf(
            "auto" to VideoKey.LOOK_KEYS.associateWith { "on" } + mapOf(VideoKey.ViAa to AUTO, VideoKey.ViBilinear to AUTO),
            "original" to VideoKey.LOOK_KEYS.associateWith { "on" },
            "sharp" to VideoKey.LOOK_KEYS.associateWith { "on" } + mapOf(VideoKey.ViAa to "off", VideoKey.ViBilinear to "off"),
            "sharper" to VideoKey.LOOK_KEYS.associateWith { "on" } + mapOf(VideoKey.ViAa to "off", VideoKey.ViBilinear to "off", VideoKey.DitherFilter to "off"),
        )

        fun resolve(all: Map<VideoKey, String>, game: Map<VideoKey, String>?): VideoSettings = VideoSettings(all + (game ?: emptyMap()))

        fun toJson(m: Map<VideoKey, String>) = JSONObject().apply { put("version", 1); for ((k, v) in m) put(k.key, v) }
        fun fromJson(o: JSONObject): Map<VideoKey, String> = o.keys().asSequence().mapNotNull { k ->
            VideoKey.of(k)?.let { it to o.optString(k) }?.takeIf { it.second.isNotEmpty() }
        }.toMap()

        /** Brightness, contrast and saturation in percent (-50..50); gamma in tenths (5..20). */
        val COLOUR_STEPS: Map<VideoKey, IntProgression> = mapOf(
            VideoKey.Brightness to (-50..50 step 5), VideoKey.Contrast to (-50..50 step 5),
            VideoKey.Saturation to (-50..50 step 5), VideoKey.Gamma to (5..20 step 1))
        val STRENGTH_STEPS: IntProgression = 0..100 step 10

        fun colourLabel(k: VideoKey, v: Int): String = if (k == VideoKey.Gamma) "%d.%d".format(v / 10, v % 10)
            else if (v > 0) "+$v%" else "$v%"
    }
}

/**
 * What a game starts with, from its [VideoSettings]: the core's options for the renderer in use,
 * process environment the host and core read, and the host's display state. Pure, so it's tested.
 */
data class VideoApply(
    val coreOptions: Map<String, String>,
    val env: Map<String, String>,
) {
    companion object {
        /** [scale]: the resolution the game plays at (a netplay room plays at 1x), for Look Auto. */
        fun of(v: VideoSettings, parallel: Boolean, scale: Int = v.int(VideoKey.Resolution)): VideoApply {
            val opts = LinkedHashMap<String, String>()
            fun tf(k: VideoKey) = if (v.filterOn(k, scale)) "True" else "False"
            if (parallel) {
                opts["mupen64plus-parallel-rdp-upscaling"] = "${v.int(VideoKey.Resolution)}x"
                opts["mupen64plus-parallel-rdp-vi-aa"] = tf(VideoKey.ViAa)
                opts["mupen64plus-parallel-rdp-vi-bilinear"] = tf(VideoKey.ViBilinear)
                opts["mupen64plus-parallel-rdp-dither-filter"] = tf(VideoKey.DitherFilter)
                opts["mupen64plus-parallel-rdp-divot-filter"] = tf(VideoKey.Divot)
                opts["mupen64plus-parallel-rdp-gamma-dither"] = tf(VideoKey.GammaDither)
                opts["mupen64plus-parallel-rdp-deinterlace-method"] = if (v[VideoKey.Deinterlace] == "weave") "Weave" else "Bob"
                opts["mupen64plus-parallel-rdp-downscaling"] = v[VideoKey.Supersample].takeIf { it in setOf("1/2", "1/4", "1/8") } ?: "disable"
                opts["mupen64plus-parallel-rdp-native-texture-lod"] = tf(VideoKey.NativeTexLod)
                opts["mupen64plus-parallel-rdp-native-tex-rect"] = tf(VideoKey.NativeTexRect)
                // Upscaled, skip the extra 1x pass: game memory gets the upscaled picture averaged down
                // (deep dive idea 1: +17-21% uncapped at 2x, +8.6% at 4x). No effect at 1x.
                opts["mupen64plus-parallel-rdp-super-sampled-read-back"] = "True"
            } else {
                opts["mupen64plus-EnableNativeResFactor"] = v.int(VideoKey.Resolution).toString()
            }
            val env = LinkedHashMap<String, String>()
            // Steady on a 120 Hz screen is auto (3): Steady until the swapchain queue backs up, then timed pacing
            // (Joey 2026-11-17, "Yes, limit 4": game 59 45-52 -> 13-16 ms poll->display, other games unchanged).
            when (v[VideoKey.Pacing]) {
                "steady" -> env["N64_SWAP_INTERVAL"] = if (VideoKey.highRefresh) "3" else "1"
                "timed" -> env["N64_SWAP_INTERVAL"] = "2"
            }
            // Look's Sharp preset no longer turns on the sharp-pixels drawing (Picture mockup's Q1, 2026-11-08):
            // that is Shader's Sharp pixels now (Auto gives it at Native on most screens).
            return VideoApply(opts, env)
        }

        /** The picture's shape as W:H (0:0 = stretch to the screen); Auto takes the widescreen code's [widescreen] shape. */
        fun aspect(v: VideoSettings, widescreen: Pair<Int, Int>?): Pair<Int, Int> = when (v[VideoKey.Aspect]) {
            "16:9" -> 16 to 9
            "stretch" -> 0 to 0
            "4:3" -> 4 to 3
            else -> widescreen ?: (4 to 3)
        }
    }
}

/**
 * Where Video lives: `files/video/all.json` (All games) and `files/video/games/<save name>.json`
 * (only the keys changed for that game). Both the launcher and the game screen read it. Written
 * whole (`.part`, then renamed), as Controls is.
 */
class VideoStore(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, "video"))

    private val allFile = File(dir, "all.json")
    private val games = File(dir, "games")

    fun all(): Map<VideoKey, String> = read(allFile) ?: emptyMap()
    fun saveAll(m: Map<VideoKey, String>) = write(allFile, m)
    fun game(saveName: String): Map<VideoKey, String>? = read(gameFile(saveName))
    fun saveGame(saveName: String, m: Map<VideoKey, String>) { if (m.isEmpty()) removeGame(saveName) else write(gameFile(saveName), m) }
    fun removeGame(saveName: String) { gameFile(saveName).delete() }
    fun gamesWithOwn(): List<String> = games.listFiles { f -> f.isFile && f.name.endsWith(".json") }
        ?.map { it.name.removeSuffix(".json") }?.sorted() ?: emptyList()
    fun forGame(saveName: String?): VideoSettings = VideoSettings.resolve(all(), saveName?.takeIf { it.isNotEmpty() }?.let { game(it) })

    private fun gameFile(saveName: String) = File(games, ControlsStore.safe(saveName) + ".json")

    private fun read(f: File): Map<VideoKey, String>? {
        if (!f.isFile) return null
        return try { VideoSettings.fromJson(JSONObject(f.readText())) } catch (e: Exception) { JLog.e(TAG, "can't read ${f.name}: $e"); null }
    }

    private fun write(f: File, m: Map<VideoKey, String>) {
        try {
            f.parentFile?.mkdirs()
            val part = File(f.parentFile, f.name + ".part")
            part.writeText(VideoSettings.toJson(m).toString(1))
            if (!part.renameTo(f)) { f.delete(); if (!part.renameTo(f)) throw java.io.IOException("can't rename ${part.name}") }
        } catch (e: Exception) {
            JLog.e(TAG, "can't write ${f.name}: $e")
        }
    }

    private companion object { const val TAG = "Joey64Video" }
}
