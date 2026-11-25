package com.joeysretro.joey64

import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.graphics.Color
import android.view.Gravity
import android.graphics.PixelFormat
import android.widget.TextView
import java.io.File
import androidx.compose.ui.graphics.asImageBitmap
import com.joeysretro.joey64.launcher.ingame.InGamePopup
import com.joeysretro.joey64.states.GameStates
import com.joeysretro.joey64.states.StateSlots
import com.joeysretro.joey64.controls.ControlsSet
import com.joeysretro.joey64.controls.ControlsStore
import com.joeysretro.joey64.controls.DeviceChoice
import com.joeysretro.joey64.controls.FastForwardMode
import com.joeysretro.joey64.controls.HotkeyAction
import com.joeysretro.joey64.controls.Hotkeys
import com.joeysretro.joey64.controls.Pak
import com.joeysretro.joey64.controls.PakDb
import com.joeysretro.joey64.controls.PadDevices
import com.joeysretro.joey64.controls.PlayerInput
import com.joeysretro.joey64.controls.QuitTo
import com.joeysretro.joey64.controls.SlowMode

class MainActivity : Activity(), SurfaceHolder.Callback2 {

    private var started = false


    /** This instance only hands its game over (switchGame): it must not touch the native side. */
    private var switching = false
    private val mine get() = !switching && (owner == null || owner === this)

    private fun hasRom(i: Intent?) = i?.data != null || (devBuild && i?.getStringExtra("rom") != null) || i?.getStringExtra(EXTRA_ROM_PATH) != null

    /**
     * Debug builds only: the adb switches (--es env/opts/cheats/driver/renderer/rom, --ez sharp/sharp2/nocap,
     * test.z64). This activity takes ROMs from other apps, and those must not set the process environment, core
     * options or cheats of a release build (code audit 2026-11-07).
     */
    private val devBuild get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private fun devString(name: String): String? = if (devBuild) intent?.getStringExtra(name) else null

    /**
     * This start came from Joey64 itself: through the non-exported [GAME_START] alias (the library, switchGame, a settings
     * restart), or a debug build (adb). Other apps can send this exported activity a ROM, but not the launcher's extras:
     * a room with a router port mapping, a server to join, the MD5, a state to load, a cartridge URI or a save name
     * (code audit 3, H9).
     */
    private val fromJoey64 get() = devBuild || intent?.component?.className == GAME_START
    /** One of the launcher's own extras ([LAUNCHER_EXTRAS]), only from a start [fromJoey64]. */
    /**
     * This play is in a netplay room (the room's env, set in onCreate before anything reads it). Joey 2026-11-15: "Net
     * play shouldn't touch save states, nothing created or loaded": no quit save, recovery state, state-request ring or
     * start load, and an older recovery state of the game is left as it is. The menu, hotkeys and touch buttons refuse
     * saves and loads already ([netBlocked]); the host refuses loads and rewind.
     */
    private var roomPlay = false
    /** The GPU setupGpu found (GpuRules.familyKey), for the room's rollback ([com.joeysretro.joey64.netplay.RollbackCap]). */
    private var gpuFamilyKey: String? = null

    private fun launcherString(name: String): String? = if (fromJoey64) intent?.getStringExtra(name) else null
    private fun launcherBool(name: String): Boolean = fromJoey64 && intent?.getBooleanExtra(name, false) == true

    /**
     * The extras to carry into the next start (switchGame, a settings restart), which goes through [GAME_START] and is
     * trusted: a start from another app loses the launcher's extras here, or they'd be trusted on the way.
     */
    private fun carriedExtras(): Bundle? = try {
        intent?.extras?.let { b -> Bundle(b).apply { if (!fromJoey64) LAUNCHER_EXTRAS.forEach { remove(it) } } }
    } catch (e: RuntimeException) {
        // A bad parcel from another app (BadParcelableException and the like): carry nothing.
        JLog.w(TAG, "can't read the start's extras: $e"); null
    }

    /** The parallel-RDP Resolution this start asks for (dev `--es opts` upscaling, else Settings › Video), for Auto's driver. */
    private fun wantedUpscale(): Int =
        devString("opts")?.split(',')?.map { it.split('=', limit = 2) }?.firstOrNull { it.size == 2 && it[0].trim() == UPSCALE_KEY }
            ?.get(1)?.trim()?.removeSuffix("x")?.toIntOrNull()
            ?: video.int(com.joeysretro.joey64.video.VideoKey.Resolution)
    private fun devFlag(name: String): Boolean = devBuild && intent?.getBooleanExtra(name, false) == true

    /** A save name as a file name only: a "/" or ".." in one handed over put the save outside saves/. */
    private fun cleanSaveName(name: String) = name.replace('/', '_').replace('\\', '_').let { if (it == "." || it == "..") "_" else it }

    /**
     * The ROM switchGame or a settings restart handed over (EXTRA_ROM_PATH, EXTRA_SAVE_NAME). Other apps can send
     * these extras too, so a release build takes only a ROM in Joey64's own cache, where both unpack it.
     */
    private fun handedRom(): Pair<String, String>? {
        val path = launcherString(EXTRA_ROM_PATH) ?: return null
        if (!devBuild) {
            val cache = cacheDir.canonicalFile.path + File.separator
            val f = try { File(path).canonicalFile.path } catch (e: java.io.IOException) { null }
            if (f == null || !f.startsWith(cache)) { JLog.e(TAG, "handed ROM outside Joey64's cache, not loaded: $path"); return null }
        }
        return path to cleanSaveName(launcherString(EXTRA_SAVE_NAME) ?: "")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.joeysretro.joey64.video.ScreenRefresh.update(this)
        super.onCreate(savedInstanceState)
        LogFiles.start(this, intent)
        Controllers.init(this)
        if (gameLoaded && hasRom(intent)) { switching = true; afterLauncherSaved { if (!isDestroyed) switchGame() }; return }
        // A start with no ROM while a game is up (another app starting this exported screen bare): the setup below
        // would reload the core under the running game and end it with no quit save (code audit 4, A-H1, Thor). This
        // instance closes at once, so the game's own instance comes back to the front, untouched.
        if (owner != null) { JLog.w(TAG, "a start with no ROM while a game runs: ignored"); finish(); return }
        // Quit saves a process ended before they were in the data folder (switchGame) go in now, behind the game.
        GameStates.commitAllLater(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Dev check (2026-11-05 recheck): whether this device offers sustained performance mode (checked before only on the Flip and Portal)
        JLog.w(TAG, "sustained performance mode supported: " +
            (getSystemService(POWER_SERVICE) as android.os.PowerManager).isSustainedPerformanceModeSupported)
        // Settings › Emulation for this game (before Video: it moves an older Video file's Thermal fallback over).
        emuAtStart = com.joeysretro.joey64.emulation.EmuStore(this).forGame(earlySaveName())
        // Settings › Video for this game (All games, then its own rows), read before anything starts.
        video = com.joeysretro.joey64.video.VideoStore(this).forGame(earlySaveName())
        videoAtStart = video
        applyWindowVideo()
        // Render straight into the activity's window, like RetroArch's NativeActivity, not a SurfaceView:
        // Qualcomm's frame pacing library (libpenguin, vendor.perf.framepacing) hooks SurfaceView layers of
        // games and runs a 120 Hz vsync loop in our process (Duo: ~17 ms of CPU a second). The window's
        // views no longer draw, so the FPS counter is its own panel window (showFpsCounter).
        window.takeSurface(this)
        hideSystemBars()
        requestUnbufferedJoystick(late = false)
        registerDebugStick()

        // The bundled core (devtools/bundle-core.sh); older test builds still carry it under upstream's file names.
        val coreFile = listOf("libjoey64_libretro_android.so", "libmupen64plus_next_libretro_android.so", "mupen64plus_next_libretro_android.so")
            .map { File(applicationInfo.nativeLibraryDir, it) }
            .firstOrNull { it.exists() } ?: File(applicationInfo.nativeLibraryDir, "libjoey64_libretro_android.so")
        val sys = File(filesDir, "system").apply { mkdirs() }
        val saves = File(filesDir, "saves").apply { mkdirs() }

        // Netplay (Play together, 2026-11-10): the room's extras, as the environment the host and core read. A game screen
        // runs in the launcher's process, and the host sets N64_NETPLAY itself when it opens a room, so a game without the
        // extras clears them first: one netplay game must never make the next one a netplay game.
        for (k in NETPLAY_ENV) android.system.Os.unsetenv(k)
        // Only from the library (fromJoey64): another app must not open a room on this device or point it at a server.
        launcherString(EXTRA_NETPLAY_HOST)?.let { android.system.Os.setenv("N64_NETPLAY_SERVE", it, true) }
        launcherString(EXTRA_NETPLAY_JOIN)?.let { android.system.Os.setenv("N64_NETPLAY", it, true) }
        launcherString(EXTRA_NETPLAY_NAME)?.let { android.system.Os.setenv("N64_NETPLAY_NAME", it, true) }
        launcherString(EXTRA_NETPLAY_KEYS)?.let { android.system.Os.setenv("N64_NETPLAY_KEYS", it, true) }
        launcherString(EXTRA_NETPLAY_KEY)?.let { android.system.Os.setenv("N64_NETPLAY_KEY", it, true) }
        // The last start's settings (frame pacing, run-ahead, frame skip, thermal fallback) go first. A guard only:
        // each game already gets a fresh process (release audit 0.2.7 found no game whose settings carried over),
        // but a game screen started again in the same process must not keep the previous start's values
        for (k in settingsEnvKeys) android.system.Os.unsetenv(k)
        settingsEnvKeys.clear()
        // Dev: --es env "PARALLEL_RDP_UBERSHADER=1,KEY=value" (process environment, read by the core).
        // Set before setupGpu: the driver hooks (N64_KGSL_PRIO) read it while the custom driver loads
        devString("env")?.split(',')?.forEach { kv ->
            val (k, v) = kv.split('=', limit = 2).takeIf { it.size == 2 } ?: return@forEach
            android.system.Os.setenv(k.trim(), v.trim(), true)
        }
        roomPlay = android.system.Os.getenv("N64_NETPLAY") != null || android.system.Os.getenv("N64_NETPLAY_SERVE") != null
        if (roomPlay) JLog.i(TAG, "netplay: no save states in a room (none written, loaded or cleared)")
        // The per-device switches the driver reads as it loads (GpuRules.beforeDriver: the Odin 3's KGSL priority).
        applyGpuSettings(GpuRules.beforeDriver(Build.MODEL))
        // Frame pacing (Settings › Video), Thermal fallback and Run-ahead (Settings › Emulation) are read by the host
        // from the environment; --es env wins.
        for ((k, v) in com.joeysretro.joey64.video.VideoApply.of(video, true).env + com.joeysretro.joey64.emulation.EmuApply.of(emuAtStart, true).env)
            if (android.system.Os.getenv(k) == null) {
                android.system.Os.setenv(k, v, true)
                settingsEnvKeys.add(k)
            }
        // Driver and renderer by GPU (setupGpu), or Settings › Video's choice. Override with: am start ... --es renderer parallel|gliden64
        val renderer = setupGpu()
        // The trial's driver can't work here: the game screen is starting again with the next one.
        if (trialRestarting) return
        parallel = renderer == "parallel"
        NativeBridge.setRenderer(renderer == "parallel")
        onScreen = onScreenStore.read()
        // Settings > About shows the renderer the last game started with (the launcher can't probe Vulkan itself).
        getSharedPreferences("device", MODE_PRIVATE).edit().putString("renderer", renderer).apply()
        // The switches that differ by GPU (early acquire, single-threaded commands, lazy RDP worker, compile thread,
        // smoother timing, parallel-RDP synchronous): one table, GpuRules.settings, with the reasons. --es env/opts win.
        applyGpuSettings(GpuRules.settings(gpuFamily))
        // Settings › Emulation's Overclock popup says what Auto gives here: smoother timing on this device or not.
        getSharedPreferences("device", MODE_PRIVATE).edit().putBoolean("smoother", android.system.Os.getenv("N64_SMOOTHER") == "1").apply()
        // Settings › Video: resolution and the N64 video filters (core options), the picture's shape, integer
        // scaling, black frames, colour and sharpening. The dev switches below still win.
        // A netplay room renders at 1x whatever is asked (the host answers the core so, Joey 2026-11-10): the stats
        // overlay says 1x there, and Look Auto gives Original (lookScale).
        val room1x = android.system.Os.getenv("N64_NETPLAY") != null || android.system.Os.getenv("N64_NETPLAY_SERVE") != null
        if (room1x) lookScale = 1
        val vApply = com.joeysretro.joey64.video.VideoApply.of(video, renderer == "parallel", lookScale ?: video.int(com.joeysretro.joey64.video.VideoKey.Resolution))
        for ((k, v) in vApply.coreOptions) NativeBridge.setOption(k, capUpscaling(k, v))
        // The stats overlay's "parallel-RDP 2x" (after the Duo's cap; a dev --es opts upscale isn't shown).
        statsRenderer = if (renderer == "parallel") "parallel-RDP " + (if (room1x) "1x" else capUpscaling("mupen64plus-parallel-rdp-upscaling",
            vApply.coreOptions["mupen64plus-parallel-rdp-upscaling"] ?: "1x")) else "GLideN64 " + (if (room1x) "1" else vApply.coreOptions["mupen64plus-EnableNativeResFactor"] ?: "1") + "x"
        if (renderer == "parallel") playUpscale = if (room1x) "1x" else capUpscaling(UPSCALE_KEY, vApply.coreOptions[UPSCALE_KEY] ?: "1x")
        // The game's own Crop edges (Smash 4 lines) from the library's header code, so the held picture goes up cut as the
        // game will be: it came from the ROM in launchGame, after the picture was placed, and Continue zoomed in a little
        // as the crop arrived (Joey 2026-11-22, Thor: cut [12, 3, 10, 6] then [28, 15, 26, 18] 170 ms later).
        gameCode = com.joeysretro.joey64.video.GameCrop.keyOf(launcherString(EXTRA_GAME_CODE), launcherString(EXTRA_MD5))
        applyVideoDisplay()
        applyPicture()
        devSharp = devFlag("sharp2") || devFlag("sharp")
        // Sharp display (opt-in; the native look stays the default): the VI's anti-aliasing and bilinear
        // blur off, and a sharp-bilinear final scale. Dev: --ez sharp true, until the settings screen exists.
        // --ez sharp2 true (experiment, Joey 2026-11-05): Sharp plus the VI dither filter off. That filter blurs the
        // games' dither pattern away and is most of the native softness (Flip screenshots: edge contrast +28% with it
        // off, +9% for VI AA off); the price is the dither grain showing on flat surfaces.
        val sharp2 = devFlag("sharp2")
        if (sharp2 || devFlag("sharp")) {
            NativeBridge.setOption("mupen64plus-parallel-rdp-vi-aa", "False")
            NativeBridge.setOption("mupen64plus-parallel-rdp-vi-bilinear", "False")
            if (sharp2) NativeBridge.setOption("mupen64plus-parallel-rdp-dither-filter", "False")
            NativeBridge.setSharp(true)
        }
        // Dev: --es opts "mupen64plus-parallel-rdp-synchronous=False,key=value"
        devString("opts")?.split(',')?.forEach { kv ->
            val (k, v) = kv.split('=', limit = 2).takeIf { it.size == 2 } ?: return@forEach
            NativeBridge.setOption(k.trim(), capUpscaling(k.trim(), v.trim()))
        }
        // Dev: --es cheats "D11E3184 2881,811E3184 2001;<next cheat>"
        devString("cheats")?.split(';')?.filter { it.isNotBlank() }?.forEach { NativeBridge.addCheat(it) }
        if (!NativeBridge.loadCore(coreFile.absolutePath, sys.absolutePath, saves.absolutePath)) {
            JLog.e(TAG, "Core failed to load: $coreFile"); finish()
        }
    }

    // ---- Stats overlay (Settings › On screen, approved mockup 2026-11-07) ---------------------------
    private val fpsHandler = Handler(Looper.getMainLooper())
    private var stats: StatsOverlay? = null
    private val onScreenStore by lazy { com.joeysretro.joey64.onscreen.OnScreenStore(this) }
    private var onScreen = com.joeysretro.joey64.onscreen.OnScreen()
    private var statsDriver = ""
    private var statsRenderer = ""

    // Android batches joystick moves (sticks, hat D-pads, analog triggers) to the next frame:
    // Odin 2 Portal 10 ms -> 0.5 ms from event to dispatch. Requested on the decor view because
    // only the focused-view chain counts and nothing here takes focus. The window (ViewRootImpl) only
    // takes the request when it is made while the decor view is attached: made in onCreate alone, the
    // Flip (Android 11) never got it (dumpsys: mUnbufferedInputSource=0; the Portal showed 10). So it
    // is made again once attached and on every focus gain (a focus change recomputes it). The same
    // value twice is ignored by View, hence NONE first.
    private fun requestUnbufferedJoystick(late: Boolean = true) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        // Default since 2026-11-06 (Flip, unbuf.sh: stick moves 15.5 -> 1.6 ms, window source 0 -> 10; compat 82/82).
        // N64_UNBUF_LATE=0 goes back to the onCreate request alone.
        if (late && android.system.Os.getenv("N64_UNBUF_LATE") == "0") return
        val decor = window.decorView
        decor.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_NONE)
        decor.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_JOYSTICK)
    }

    // Dev (--es env N64_INPUT_LAT=1): every 5 s, how long joystick moves and key presses took from
    // the kernel event to our dispatch (uptime ms), and how many moves came batched (with history).
    private val inputLat by lazy { android.system.Os.getenv("N64_INPUT_LAT") == "1" } // env is set in onCreate
    private var latStart = 0L
    private var latMoves = 0; private var latMoveSum = 0L; private var latMoveMax = 0L; private var latBatched = 0
    private var latKeys = 0; private var latKeySum = 0L; private var latKeyMax = 0L
    private fun noteInputLat(eventTime: Long, key: Boolean, history: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        val d = now - eventTime
        if (key) { latKeys++; latKeySum += d; latKeyMax = maxOf(latKeyMax, d) }
        else { latMoves++; latMoveSum += d; latMoveMax = maxOf(latMoveMax, d); if (history > 0) latBatched++ }
        if (latStart == 0L) latStart = now
        if (now - latStart < 5000) return
        JLog.e(TAG, "input lat: moves $latMoves mean " + "%.1f".format(latMoveSum.toDouble() / maxOf(latMoves, 1)) +
            " max $latMoveMax ms batched $latBatched; keys $latKeys mean " +
            "%.1f".format(latKeySum.toDouble() / maxOf(latKeys, 1)) + " max $latKeyMax ms")
        latStart = now
        latMoves = 0; latMoveSum = 0; latMoveMax = 0; latBatched = 0; latKeys = 0; latKeySum = 0; latKeyMax = 0
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestUnbufferedJoystick()
        if (stats != null || destroyed) return
        stats = StatsOverlay(this, windowManager).also {
            it.device = statsDevice()
            it.driver = statsDriver
            it.renderer = statsRenderer
            it.game = ::statsGame
            it.sideBar = ::statsSideBar
            it.apply(onScreen)
            if (resumed) it.start()
        }
    }

    /**
     * The stats overlay's THIS GAME (approved stats mockup 2, 2026-11-10): the settings that change how this play runs,
     * only those not at their default, as they are now (the Upscale and shader hotkeys included).
     */
    private fun statsGame(): List<com.joeysretro.joey64.onscreen.GameSetting> {
        val out = ArrayList<com.joeysretro.joey64.onscreen.GameSetting>()
        fun add(name: String, value: String) { out += com.joeysretro.joey64.onscreen.GameSetting(name, value) }
        val inRoom = android.system.Os.getenv("N64_NETPLAY") != null || android.system.Os.getenv("N64_NETPLAY_SERVE") != null
        val res = if (inRoom) 1 else if (parallel) playUpscale?.removeSuffix("x")?.toIntOrNull() ?: 1
            else video.int(com.joeysretro.joey64.video.VideoKey.Resolution)
        if (res > 1) add(com.joeysretro.joey64.onscreen.GameSetting.RESOLUTION, "${res}x")
        // Run-ahead: Settings › Emulation's, or --es env N64_RUNAHEAD (libretro_host.cpp: 1-4, off in a netplay room).
        val ahead = android.system.Os.getenv("N64_RUNAHEAD")?.toIntOrNull()?.coerceIn(0, 4) ?: 0
        if (ahead > 0 && !inRoom) add("Run-ahead", "$ahead")
        // Frame skip: Settings › Emulation's, or --es env N64_FRAMESKIP (the core's parallel-RDP: 1 every other, 2 when
        // behind; the host drops it in a netplay room).
        if (parallel && !inRoom) when (android.system.Os.getenv("N64_FRAMESKIP")) {
            "2" -> add("Frame skip", "when behind"); "1" -> add("Frame skip", "every other frame") }
        extrasOn.shape?.let { (w, h) -> add("Widescreen", "$w:$h") }
        if (extrasOn.fps) add("60 fps", "On")
        val shader = playShader ?: video[com.joeysretro.joey64.video.VideoKey.Shader]
        if (shaderOff) add("Shader", "Off")
        else if (shader != com.joeysretro.joey64.video.VideoKey.Shader.default)
            add("Shader", com.joeysretro.joey64.video.Picture.valueLabel(shader).removePrefix("Your own: "))
        val oc = emuAtStart[com.joeysretro.joey64.emulation.EmuKey.Overclock]
        if (oc != com.joeysretro.joey64.emulation.EmuKey.Overclock.default && !overclockSetAside)
            add("Overclock", com.joeysretro.joey64.emulation.EmuChoices.overclock.firstOrNull { it.value == oc }?.label ?: oc)
        return out
    }

    /**
     * The black bar each side of the picture, in px, for the stats overlay's Auto layout: the window's width less the
     * picture's (Aspect ratio, a widescreen code's shape), halved. 0 when the picture is stretched to fill.
     */
    private fun statsSideBar(): Int {
        val d = window.decorView
        val w = d.width; val h = d.height
        val (aw, ah) = com.joeysretro.joey64.video.VideoApply.aspect(video, extrasOn.shape)
        if (w <= 0 || h <= 0 || aw <= 0 || ah <= 0) return 0
        val pic = minOf(w.toDouble(), h.toDouble() * aw / ah)
        return ((w - pic) / 2).toInt()
    }

    /** "AYN Thor · SM8550 · Adreno 740": the device, its chip (Android 12+) and GPU. */
    private fun statsDevice(): String {
        val name = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL
            else Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null
        return listOfNotNull(name, soc, gpuName.replace("(TM) ", "").takeIf { it.isNotEmpty() }).joinToString(" · ")
    }

    /** The stats hotkey: the next of Off, Simple, Full, Custom, saved, with a pop-up naming it. */
    private fun stepStats() {
        onScreen = onScreenStore.update { it.copy(stats = it.stats.next) }
        overlay?.onScreen = onScreen
        stats?.apply(onScreen)
        if (onScreen.popHotkeys) overlay?.show(InGamePopup("stats", InGamePopup.Kind.Stats, "Stats overlay", onScreen.stats.label, onScreen.stats.detail, stayMs = 2_000))
    }

    // ---- Rumble Pak ---------------------------------------------------------------------------
    // The core switches each player's pak motor on and off; a background thread waits for each change
    // (a per-vsync poll cost ~60 ms of CPU a second at 120 Hz). Each player vibrates their own pad when
    // it has a motor (players 2-4 since 2026-11-07); the player with the built-in controls vibrates the
    // device, and so does player 1 when nobody has the built-in controls (as before: a pad without a motor).
    private var padDeviceId = -1
    /** Bit p: player p+1's motor is running. */
    private var rumbling = 0
    private val rumbleVibrators = arrayOfNulls<Vibrator>(4)

    private fun padVibrator(pad: InputDevice?): Vibrator? {
        val v = if (Build.VERSION.SDK_INT >= 31) pad?.vibratorManager?.defaultVibrator
            else @Suppress("DEPRECATION") pad?.vibrator
        return v?.takeIf { it.hasVibrator() }
    }

    private fun systemVibrator(): Vibrator? {
        val sys = if (Build.VERSION.SDK_INT >= 31)
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        return sys.takeIf { it.hasVibrator() }
    }

    private fun vibratorFor(p: Int): Vibrator? {
        // Controls › Rumble on (approved paks mockup 2026-11-08): Off shakes nothing; This device shakes the device for any controller.
        when (controls.players.getOrNull(p)?.rumbleOn) {
            com.joeysretro.joey64.controls.RumbleOn.Off -> return null
            com.joeysretro.joey64.controls.RumbleOn.Device -> return systemVibrator()
            else -> {}
        }
        // The pads playing as p, the one last used first; then a connected pad that is p's controller but hasn't been pressed yet.
        val ids = playerOfDevice.filterValues { it == p }.keys.sortedByDescending { it == padDeviceId }
        for (id in ids) padVibrator(InputDevice.getDevice(id))?.let { return it }
        val choice = controls.players.getOrNull(p)?.device
        if (choice is DeviceChoice.Pad) for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if (PadDevices.isPad(d) && PadDevices.matches(choice, d)) padVibrator(d)?.let { return it }
        }
        val builtInPlayer = controls.players.indexOfFirst { it.device == DeviceChoice.BuiltIn }
        if (p != builtInPlayer && !(p == 0 && builtInPlayer < 0)) return null
        return systemVibrator()
    }

    private fun setRumbling(mask: Int) {
        for (p in 0..3) {
            val on = (mask shr p) and 1 == 1
            if (on == ((rumbling shr p) and 1 == 1)) continue
            if (on) {
                rumbleVibrators[p] = vibratorFor(p)
                JLog.i(TAG, "rumble player ${p + 1} on: ${rumbleVibrators[p] ?: "no vibrator"}")
                // Long one-shot, cancelled when the game switches the motor off. Rumble strength sets the amplitude on a
                // motor that has amplitude control (the others shake at full: the row says "Full only").
                val v = rumbleVibrators[p]
                val pct = controls.players.getOrNull(p)?.rumbleStrength ?: 100
                val amp = if (v != null && v.hasAmplitudeControl() && pct < 100) (255 * pct / 100).coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
                v?.vibrate(VibrationEffect.createOneShot(10_000, amp))
            } else {
                rumbleVibrators[p]?.cancel()
                rumbleVibrators[p] = null
            }
        }
        rumbling = mask
    }

    @Volatile private var resumed = false
    @Volatile private var destroyed = false
    /** Watches RetroAchievements' sign-in state for the pop-ups; ends with the activity. */
    private val uiScope = kotlinx.coroutines.MainScope()
    private val rumbleWatcher = Thread({
        var on = 0
        while (!destroyed) {
            val now = NativeBridge.waitRumble(on, 1000)
            if (now != on) { on = now; runOnUiThread { if (resumed) setRumbling(now) } }
        }
    }, "RumbleWatcher").apply { isDaemon = true }

    // ---- Fast-forward -------------------------------------------------------------------------
    // Its hotkey toggles it (hotkey button + R2 by default since 2026-11-07, was R3 on its own;
    // Controls > Hotkeys). Runs up to 4x with audio muted.
    // Its mode and speed are the user's (approved fast-forward popup, 2026-11-07): Toggle, Hold (on while
    // the hotkey is held) or Adaptive (held, and the speed follows how far the trigger is pressed).
    private var fastForward = false
    /** Hold / Adaptive: the hotkey is down. */
    private var ffHeld = false
    /** The latest trigger depths (L2, R2), so Adaptive starts at the right speed on the press. */
    private var triggerDepth = floatArrayOf(0f, 0f)

    private fun setFastForward(on: Boolean, percent: Int = hotkeys.ffSpeed) {
        NativeBridge.setFastForwardSpeed(percent)
        if (on != fastForward) { sessionMark(); fastForward = on; NativeBridge.setFastForward(on) }
        if (on && slowMotion) setSlowMotion(false) // straight to fast-forward
        // On screen while it's on, as Slow motion's (Joey 2026-11-11), as the small chip "4x" (approved ffchip mockup A,
        // 2026-11-14); Adaptive's speed follows the trigger, so the same chip is shown again with each new speed (an id
        // already showing is updated in place, as Rewind's).
        if (on) { if (percent != ffShown) { ffShown = percent
            overlay?.show(InGamePopup("ff", InGamePopup.Kind.FastForward, "Fast forward", speedTimes(percent), "", chip = true,
                stayMs = com.joeysretro.joey64.launcher.ingame.PopupQueue.UNTIL_HIDDEN)) } }
        else if (ffShown != 0) { ffShown = 0; overlay?.hide("ff", waiting = true) }
    }
    /** The speed the fast-forward pop-up shows; 0: not shown. */
    private var ffShown = 0

    /** 400 -> "4x", 150 -> "1.5x", 125 -> "1.25x" (the fast-forward chip). */
    private fun speedTimes(percent: Int): String {
        val s = "%.2f".format(java.util.Locale.ROOT, percent / 100.0).trimEnd('0').trimEnd('.')
        return "${s}x"
    }

    // ---- Slow motion and Rewind (approved mockup 2026-11-08; Controls > Hotkeys) -----------------------------
    // Slow motion: Toggle or Hold, at Hotkeys.slowSpeed; a pop-up stays while it's on. Rewind: held, the game goes
    // back while Hotkeys.rewindOn records; the pop-up says how far, and the game goes on from there when let go.
    private var slowMotion = false
    private var slowHeld = false
    private var rewindHeld = false
    /** This press of rewind has shown the full "Can't go further back" pop-up. */
    private var rewindEndShown = false
    /** The rewind settings last given to the host (a change starts the recording over). */
    private var rewindApplied: Triple<Boolean, Int, Int>? = null

    private fun setSlowMotion(on: Boolean) {
        if (on == slowMotion) return
        if (on && com.joeysretro.joey64.achievements.RaHardcore.on()) { popHardcore("Slow motion"); return }
        sessionMark(); slowMotion = on
        if (on && fastForward) { ffHeld = false; setFastForward(false) }
        NativeBridge.setSlowMotion(if (on) hotkeys.slowSpeed else 0)
        if (on) overlay?.show(InGamePopup("slow", InGamePopup.Kind.SlowMotion, "Slow motion", "${hotkeys.slowSpeed}%", "", chip = true,
            stayMs = com.joeysretro.joey64.launcher.ingame.PopupQueue.UNTIL_HIDDEN))
        else overlay?.hide("slow", waiting = true)
        JLog.i(TAG, "slow motion ${if (on) "on, ${hotkeys.slowSpeed}%" else "off"}")
    }

    /** The slow motion hotkey went down or up (only the press counts for Toggle). */
    private fun slowMotionKey(down: Boolean) {
        if (hotkeys.slowMode == SlowMode.Toggle) { if (down) setSlowMotion(!slowMotion); return }
        slowHeld = down
        setSlowMotion(down)
    }

    private fun slowMotionRelease() { if (slowHeld) { slowHeld = false; setSlowMotion(false) } }

    /**
     * A hotkey Hardcore keeps off (approved "Hardcore / Load state is off", part B mockup 2026-11-16 screen 6): one
     * pop-up at a time, the same one again only after 3 s (a held or repeated press doesn't stack them).
     */
    private fun popHardcore(what: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (what == hcPopWhat && now - hcPopAt < 3_000) return
        hcPopWhat = what; hcPopAt = now
        val name = if (what.endsWith("s")) "$what are off" else "$what is off" // "Macros are off"
        pop("hardcore", InGamePopup.Kind.Hardcore, "Hardcore", name, "Save states and fast-forward still work")
    }
    private var hcPopWhat = ""
    private var hcPopAt = 0L

    /**
     * Hardcore on or off for the rest of this play (the pause menu's row, approved option A; the debug broadcast). On
     * restarts the game (the host, at its next frame); macros, slow motion and rewind stop. Off: the approved pop-up.
     */
    private fun hardcoreMidGame(on: Boolean) {
        if (on && overclockedPlay) { restartInHardcore(); return }
        com.joeysretro.joey64.achievements.RaHardcore.setMidGame(on)
        if (on) {
            for (p in 0 until 4) NativeBridge.stopMacro(p, true)
            setSlowMotion(false); rewindRelease()
        } else overlay?.show(hardcoreOffPop())
        applyRewind()
    }

    /** Gives the host the rewind settings (at start, and when Controls change over the paused game). */
    private fun applyRewind() {
        val on = hotkeys.rewindOn && !com.joeysretro.joey64.achievements.RaHardcore.on()
        val t = Triple(on, hotkeys.rewindEvery, hotkeys.rewindSeconds * 60 / hotkeys.rewindEvery)
        if (t == rewindApplied) return
        rewindApplied = t
        NativeBridge.setRewind(t.first, t.second, t.third)
    }

    private val rewindPoll = object : Runnable {
        override fun run() {
            if (!rewindHeld || destroyed) return
            showRewind()
            fpsHandler.postDelayed(this, 100)
        }
    }

    private fun showRewind() {
        val i = NativeBridge.rewindInfo()
        val kept = Hotkeys.rewindSecondsLabel(hotkeys.rewindSeconds)
        val bar = if (i[1] > 0) i[0].toFloat() / i[1] else 0f
        // The small chip (approved ffchip mockup A, 2026-11-14): how far back, the bar as a thin line; at the end it says
        // so in orange, and the full pop-up with where to keep more shows once per press.
        if (i[3] == 1) {
            overlay?.show(InGamePopup("rewind", InGamePopup.Kind.Rewind, "Rewinding", "Can't go further", "", chip = true, warn = true,
                stayMs = com.joeysretro.joey64.launcher.ingame.PopupQueue.UNTIL_HIDDEN, bar = 0f))
            if (!rewindEndShown) { rewindEndShown = true
                overlay?.show(InGamePopup("rewindend", InGamePopup.Kind.Rewind, "Rewinding", "Can't go further back",
                    "$kept kept: Controls › Hotkeys › Rewind")) }
        }
        else overlay?.show(InGamePopup("rewind", InGamePopup.Kind.Rewind, "Rewinding", "${(i[2] + 30) / 60} s", "", chip = true,
            stayMs = com.joeysretro.joey64.launcher.ingame.PopupQueue.UNTIL_HIDDEN, bar = bar))
    }

    /** The rewind hotkey went down or up: back while held. */
    private fun rewindKey(down: Boolean) {
        if (down) {
            if (rewindHeld) return
            if (com.joeysretro.joey64.achievements.RaHardcore.on()) { popHardcore("Rewind"); return }
            if (!hotkeys.rewindOn) {
                pop("rewind", InGamePopup.Kind.Problem, "Rewind", "Rewind is off", "Turn it on in Controls › Hotkeys › Rewind")
                return
            }
            sessionMark(); rewindHeld = true; rewindEndShown = false
            if (fastForward) { ffHeld = false; setFastForward(false) }
            NativeBridge.setRewinding(true)
            fpsHandler.removeCallbacks(rewindPoll)
            fpsHandler.post(rewindPoll)
        } else rewindRelease()
    }

    private fun rewindRelease() {
        if (!rewindHeld) return
        rewindHeld = false
        NativeBridge.setRewinding(false)
        fpsHandler.removeCallbacks(rewindPoll)
        val back = NativeBridge.rewindInfo()[2]
        JLog.i(TAG, "rewind: let go after $back frames")
        overlay?.hide("rewind", waiting = true)
    }

    private fun toggleFastForward() = setFastForward(!fastForward)

    /** The fast-forward hotkey went down or up (only the press counts for Toggle). */
    private fun fastForwardKey(down: Boolean) {
        if (hotkeys.ffMode == FastForwardMode.Toggle) { if (down) toggleFastForward(); return }
        ffHeld = down
        setFastForward(down, if (hotkeys.ffAdaptive) adaptivePercent() else hotkeys.ffSpeed)
    }

    /** Hold and Adaptive stop when the hotkey button is let go too, or the game loses focus. */
    private fun fastForwardRelease() { if (ffHeld) { ffHeld = false; setFastForward(false) } }

    private fun ffTrigger(): Int = if (hotkeys.keys[HotkeyAction.FastForward] == KeyEvent.KEYCODE_BUTTON_L2) 0 else 1

    private fun adaptivePercent() = Hotkeys.adaptiveSpeed(triggerDepth[ffTrigger()], hotkeys.ffSpeed)

    // ---- GPU driver + renderer ------------------------------------------------------------------
    // Until M3's profile DB:
    //  - Adreno 6xx-8xx: bundled Turnip. The stock Adreno 740 driver (676.53) fails to compile a
    //    parallel-RDP shader; Turnip renders it at 60 fps.
    //  - Mali: the system driver (Mali-G52 runs parallel-RDP at full speed on it). malisx2
    //    (bmdhacks' Mali driver) isn't bundled: on the G52 it measured ~14% slower than stock, and
    //    no device we have lacks a Vulkan 1.1 system driver. It can still be dropped in (below).
    //  - parallel-RDP when the driver in use is known good (a custom one, or stock Mali), else GLideN64.
    // A driver dropped into <external files>/drivers/ (or files/drivers/) overrides the choice.
    // Dev: --es driver system|turnip

    // storage16: 16-bit and 8-bit storage buffers (1 both, 0 not both, -1 not asked: before Vulkan 1.1). parallel-RDP runs
    // without them (the core's 32-bit fallback, 2026-11-21), so any Vulkan 1.1 GPU that was asked can run it (canParallel).
    private class VkGpu(val vendor: Int, val major: Int, val minor: Int, val storage16: Int, val name: String, val driver: String = "",
                        /** maxStorageBufferRange in MB (0: not known). */ val storageRangeMb: Int = 0) {
        val vulkan11 get() = major > 1 || (major == 1 && minor >= 1)
        val canParallel get() = vulkan11 && storage16 >= 0
        val adreno get() = vendor == 0x5143
        val mali get() = vendor == 0x13B5
        override fun toString() = "$name (Vulkan $major.$minor)"
    }

    private fun parseGpu(s: String): VkGpu? {
        // vendor|major.minor|16+8-bit storage|maxStorageBufferRange MB|name|driver
        val p = s.split('|', limit = 6).takeIf { it.size >= 5 } ?: return null
        val v = p[1].split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return null
        return VkGpu(p[0].toIntOrNull() ?: return null, v[0], v[1], p[2].toIntOrNull() ?: -1, p[4], p.getOrNull(5).orEmpty(), p[3].toIntOrNull() ?: 0)
    }

    /** Picks and installs the Vulkan driver; returns the renderer, "parallel" or "gliden64". */
    private fun setupGpu(): String {
        val system = parseGpu(NativeBridge.probeVulkan(false))
        val kgsl = kgslModel()
        val systemFamily = GpuRules.family(system?.vendor ?: 0, system?.name.orEmpty(), kgsl)
        // For Settings › Video › Resolution's note (the launcher doesn't open Vulkan).
        if (systemFamily.vendor != GpuRules.Vendor.OTHER) com.joeysretro.joey64.video.ResNote.remember(this, GpuRules.familyKey(systemFamily))
        gpuFamilyKey = if (systemFamily.vendor != GpuRules.Vendor.OTHER) GpuRules.familyKey(systemFamily) else null
        val upscale = wantedUpscale()
        val auto = GpuRules.autoDriver(systemFamily, upscale)
        // Every driver Auto tries on this GPU, best first (GpuRules.chain; Joey 2026-11-15)
        val chain = GpuRules.chain(systemFamily, Build.VERSION.SDK_INT, upscale)
        val videoDriver = when (val v = video[com.joeysretro.joey64.video.VideoKey.Driver]) {
            "auto", null, "" -> null
            "bundled" -> auto.value.id.takeIf { auto.value != GpuRules.Driver.SYSTEM } ?: "turnip"
            else -> v
        }
        val dev = devDriver()
        // A driver picked in Settings that didn't work here before (approved drvnote mockup 2026-11-15): this start runs
        // Auto's pick instead (the one Auto last ran here, else Auto's first), and a pop-up says so. Settings keeps the pick.
        val install = packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
        val devicePrefs = getSharedPreferences("device", MODE_PRIVATE)
        val glideOnly = devString("renderer") == "gliden64" || video[com.joeysretro.joey64.video.VideoKey.Renderer] == "gliden64"
        val pickChecked = videoDriver != null && dev == null && devString("driver") == null && !glideOnly
        val pickFailed = if (pickChecked) GpuRules.failedPicks(devicePrefs.getString("driver_failed", null), install)[videoDriver] else null
        val autoInstead = if (pickFailed == null) null else
            GpuRules.Driver.byId(devicePrefs.getString("driver", null))?.takeIf { it.id != videoDriver } ?: auto.value
        // The start-up trial (GpuTrial) runs only on Auto's own choice: nothing in Settings, no dev driver or switch.
        val autoOnly = dev == null && devString("driver") == null && videoDriver == null && devString("renderer") == null &&
            video[com.joeysretro.joey64.video.VideoKey.Renderer] != "gliden64" && video[com.joeysretro.joey64.video.VideoKey.Renderer] != "parallel"
        trialGpuName = system?.name ?: "none"
        trialTested = GpuRules.tested(systemFamily)
        val trialPick = if (autoOnly) GpuTrial(trialStore(), install, trialGpuName, chain.value.size).also { trial = it }.apply {
            // After an update, start on the driver Auto ran before (review 2026-11-15)
            // (not when that was only Auto's first pick then: a new best first replaces it, GpuRules.carryOver; never on
            // a GPU we test, where our pick is the one to run)
            if (!GpuRules.tested(systemFamily)) GpuRules.carryOver(GpuRules.Driver.byId(devicePrefs.getString("driver", null)), devicePrefs.getString("driver_first", null), systemFamily)
                ?.let { d -> carry(chain.value.indexOf(d)) }
        }.decide() else null
        val level = trialPick?.level ?: 0
        trialChain = chain.value
        trialInfo = trialPick
        // Auto looks for a faster driver once the trial has a good one (approved drvspeed mockup 2026-11-15): a try of
        // a later driver for a slow game, or of the players' reports' pick, runs instead of the good level. Not for a
        // dev start (env or opts extras) or a game Auto runs on GLideN64.
        speed = null; speedTry = null; speedGood = level
        val md5Now = earlyMd5()
        var speedGone: GpuSpeed.Start.Gone? = null
        // Not on the GPUs we test ourselves: Auto runs our pick there (GpuRules.tested, Joey 2026-11-15 night)
        if (trialPick != null && trialPick.good && level < chain.value.size && md5Now != null && !GpuRules.tested(systemFamily) && (devString("env") == null || devString("env")!!.matches(Regex("N64_TRIAL_BLACK=[0-9:]+"))) &&
            devString("opts") == null && GpuRules.gameRenderer(md5Now) == null) {
            val sp = GpuSpeed(trialStore(), install, trialGpuName, chain.value.size, upscale)
            val listLevel = GpuOrder.pick(GpuOrder.cached(this, trialGpuName), chain.value, level)
            when (val st = sp.start(md5Now, level, listLevel)) {
                is GpuSpeed.Start.Run -> speedTry = st.t
                is GpuSpeed.Start.Gone -> speedGone = st
                GpuSpeed.Start.None -> {}
            }
            speed = sp; speedMd5 = md5Now; speedInstall = install
        }
        val runLevel = speedTry?.level ?: level
        speedLevel = runLevel
        // The driver this start runs: a dev driver, a dev switch or Settings' pick, else the trial's level of the chain
        // (past the chain's end: the system driver with GLideN64).
        // A game broken on a tested GPU's pick runs its own driver (GpuRules.gameDriver), only where Auto would run the
        // first one: a trial that stepped down keeps its step.
        // A rule's driver this build doesn't have is left out (the GPU's pick runs). The trial doesn't judge a start on a
        // game's own driver: a failure there would step the GPU's pick down for every game (review 2026-11-15).
        val gameDrv = (if (autoOnly && runLevel == 0 && GpuRules.tested(systemFamily)) GpuRules.gameDriver(md5Now, systemFamily) else null)
            ?.takeIf { g -> g.value.asset?.let { bundledDriver(it) != null } ?: true }
        gameDrv?.let { JLog.i(TAG, "GPU: game rule: ${it.value.shown} (${it.rule}); the trial leaves this start out") }
        if (gameDrv != null) { trial = null; trialInfo = null }
        val trialOn = trialPick != null && gameDrv == null
        val stepDriver: GpuRules.Driver = autoInstead ?: GpuRules.Driver.byId(devString("driver") ?: videoDriver)
            ?: gameDrv?.value ?: chain.value.getOrNull(runLevel) ?: GpuRules.Driver.SYSTEM
        val lib = dev ?: stepDriver.asset?.let { bundledDriver(it) }
        if (speedTry != null && stepDriver.asset != null && lib == null) { speedFailNow("not in this build"); return "gliden64" }
        // A bundled driver this build doesn't have: the trial moves on (no game start spent on it)
        if (dev == null && trialOn && !trialPick!!.good && level < chain.value.size && stepDriver.asset != null && lib == null) {
            trialFailNow("not in this build"); return "gliden64"
        }
        var custom: VkGpu? = null
        if (lib != null) {
            val tmp = File(cacheDir, "gpu_tmp").apply { mkdirs() }
            NativeBridge.setVulkanDriver("${lib.parent}/", lib.name, "${applicationInfo.nativeLibraryDir}/", "${tmp.absolutePath}/")
            custom = parseGpu(NativeBridge.probeVulkan(true))
            if (custom == null) JLog.w(TAG, "Driver ${lib.name} has no usable GPU, using the system driver")
        }
        // Settings' pick, tried for the first time on this install: it didn't load, or can't run parallel-RDP. Noted, and
        // the game screen starts again in a fresh process (a driver once loaded stays loaded) with Auto's pick.
        if (pickChecked && pickFailed == null && stepDriver != GpuRules.Driver.SYSTEM) {
            val why = when {
                custom == null -> GpuRules.LOAD
                !custom.canParallel -> GpuRules.PRDP
                else -> null
            }
            if (why != null) {
                devicePrefs.edit().putString("driver_failed",
                    GpuRules.withFailedPick(devicePrefs.getString("driver_failed", null), install, stepDriver.id, why)).commit()
                JLog.w(TAG, "GPU driver ${stepDriver.shown} picked in Settings: ${GpuRules.failedLabel(why)}; starting again with Auto's pick")
                trialRestarting = true
                finish()
                RestartActivity.restart(this, trialNextStart())
                return "gliden64"
            }
        }
        // A speed try's driver that doesn't load, or can't run parallel-RDP: failed, and the next start says so.
        if (speedTry != null && (custom == null || !custom.canParallel)) {
            speedFailNow(if (custom == null) "doesn't load on this GPU" else "can't run parallel-RDP"); return "gliden64"
        }
        speedStartPopup(speedGone)
        if (autoInstead != null) {
            val picked = GpuRules.Driver.byId(videoDriver)?.shown ?: videoDriver.orEmpty()
            JLog.w(TAG, "GPU driver $picked picked in Settings: ${GpuRules.failedLabel(pickFailed)} before; using Auto's pick ${autoInstead.shown}")
            val p = InGamePopup("gpupick", InGamePopup.Kind.GpuNote,
                if (pickFailed == GpuRules.PRDP) "$picked can't run parallel-RDP here" else "$picked didn't load on this GPU",
                "Using ${autoInstead.shown}, Auto's pick", "Pick another in Settings › Video › GPU driver", stayMs = 6_000, warn = true)
            overlay?.show(p) ?: earlyPops.add(p)
        }
        // The trial's checks before any game start (approved gputry mockup: "checked off quietly"): a driver that
        // doesn't load, or a GPU without what parallel-RDP needs on it, moves to the next level at once.
        if (trialOn && !trialPick!!.good && level < chain.value.size) {
            val g = if (stepDriver == GpuRules.Driver.SYSTEM) system else custom
            val why = when {
                stepDriver != GpuRules.Driver.SYSTEM && custom == null -> "doesn't load on this GPU"
                g == null -> "no Vulkan"
                !g.vulkan11 -> "no Vulkan 1.1"
                g.storage16 < 0 -> "no Vulkan 1.1 features"
                else -> null
            }
            if (why != null) { trialFailNow(why); return "gliden64" }
        }
        val gpu = custom ?: system
        gpuName = gpu?.name ?: ""
        gpuFamily = GpuRules.family(gpu?.vendor ?: 0, gpuName, kgsl)
        storageRangeMb = gpu?.storageRangeMb ?: 0
        customDriverInUse = custom != null
        statsDriver = statsDriverName(lib?.takeIf { custom != null }, dev != null, gpu?.driver.orEmpty())
        driverIdNow = when { custom == null -> "system"; dev != null -> "custom"; else -> stepDriver.id }
        val cpuinfo = try { File("/proc/cpuinfo").readText() } catch (e: Exception) { null }
        val rendererPick = GpuRules.autoRenderer(gpu != null && gpu.vulkan11, gpu != null && gpu.canParallel, custom != null, gpuFamily,
            GpuRules.littleCoresOnly(cpuinfo), upscale, tryStock = trialPick != null && level < chain.value.size)
        // Past the chain's last driver the trial runs GLideN64 on the system driver.
        val trialGl = trialPick != null && level >= chain.value.size
        val parallelForGpu = rendererPick.value == "parallel" && !trialGl
        // A game Auto runs on GLideN64 (GpuRules.gameRenderer: SM64 Last Impact); the GPU's own pick still goes to
        // Settings' "Auto · ..." below.
        val gamePick = if (parallelForGpu) GpuRules.gameRenderer(earlyMd5()) else null
        val parallel = parallelForGpu && gamePick == null
        if (!parallel && speed != null) {
            // Only parallel-RDP's speed is compared (the driver doesn't change GLideN64)
            speedTry?.let { speed?.failed(it.level, false) }
            speed = null; speedTry = null
        }
        if (gpu != null && gpu.vulkan11 && gpu.storage16 == 0) JLog.i(TAG, "GPU ${gpu.name}: no 16-bit or 8-bit storage buffers, parallel-RDP uses its 32-bit fallback")
        JLog.i(TAG, "GPU: ${gpu ?: "no Vulkan"}, driver ${if (custom != null) lib!!.relativeTo(gpuDriverDir).path else "system"}, " +
            "default renderer ${if (parallel) "parallel" else "gliden64"}")
        // Which rule picked what (README item 38 e: a tester's log says it).
        JLog.i(TAG, "GPU rules: family $systemFamily; Auto drivers: ${chain.rule}; Auto renderer: ${rendererPick.rule}; " +
            "trial: ${trialPick?.what ?: "off (driver or renderer not left on Auto)"}${if (trialGl) ", so GLideN64" else ""}" +
            (gamePick?.let { "; this game: ${it.rule}" } ?: ""))
        // The same for reports (Joey 2026-11-09): which rules picked the driver and renderer, the trial's level, and
        // whether Auto picked at all (Settings did when autoOnly is false; the rules then say what Auto would pick).
        // trialLevel stays 0-2 for the server (0 Auto's first driver, 1 a later one, 2 GLideN64); the drivers tried and
        // what each did go in the driver rule.
        val pickNote = (if (autoInstead != null) "picked $videoDriver ${pickFailed}, so ${autoInstead.id}; " else "") +
            (speedTry?.let { "speed try ${stepDriver.id}${if (it.fromList) " (list)" else ""} over ${chain.value.getOrNull(level)?.id}; " } ?: "")
        gpuWhy = GpuWhy((pickNote + trialRule(auto.rule, stepDriver, trialPick)).take(120), gamePick?.rule ?: rendererPick.rule,
            trialPick?.level?.let { if (trialGl) 2 else if (it == 0) 0 else 1 }, if (autoOnly) "auto" else "user")
        // What Auto picks here, for Settings › Video's "Auto · ..." (the launcher can't probe Vulkan itself):
        // the renderer by GPU, and the driver that ran when nothing but Auto chose it (a dev driver in
        // files/drivers counts: it is what Auto uses then).
        val prefs = getSharedPreferences("device", MODE_PRIVATE).edit()
        prefs.putString("auto_renderer", if (parallelForGpu) "parallel" else "gliden64")
        if (devString("driver") == null && videoDriver == null) prefs.putString("driver", when {
            custom == null -> "system"
            dev != null -> "custom"
            speedTry != null -> chain.value.getOrNull(level)?.id ?: stepDriver.id
            gameDrv != null -> chain.value.getOrNull(runLevel)?.id ?: stepDriver.id
            else -> stepDriver.id
        })
        chain.value.firstOrNull()?.let { prefs.putString("driver_first", it.id) }
        speedNote()?.let { prefs.putString("speed_note", it) }
        prefs.apply()
        devString("renderer")?.let { return it }
        // Settings › Video › Renderer: parallel-RDP only where it can start (Vulkan 1.1), else
        // the core crashes; it may still draw wrong on a stock Adreno driver, which is the user's choice there.
        return when (video[com.joeysretro.joey64.video.VideoKey.Renderer]) {
            "gliden64" -> "gliden64"
            "parallel" -> if (gpu != null && gpu.canParallel) "parallel"
                else { JLog.w(TAG, "Renderer parallel-RDP picked, but this GPU can't run it: GLideN64"); "gliden64" }
            else -> if (parallel) "parallel" else "gliden64"
        }
    }

    private var gpuName = ""
    private var gpuFamily = GpuRules.Family(GpuRules.Vendor.OTHER, 0, "")
    private var storageRangeMb = 0
    private var customDriverInUse = false
    /** Why this start ran its driver and renderer, for reports (setupGpu's "GPU rules:" line). */
    private data class GpuWhy(val driverRule: String, val rendererRule: String, val trialLevel: Int?, val pickedBy: String)
    private var gpuWhy: GpuWhy? = null
    /** Auto's start-up trial on this GPU; null when the driver or renderer was picked. */
    private var trial: GpuTrial? = null
    /** Whether the trial's start was taken back: once only (code audit 3, p1 L7). */
    private var trialCancelled = false
    private fun cancelTrial() { if (!trialCancelled) { trialCancelled = true; trial?.cancel(); if (speedTry != null) speed?.cancel() } }
    /** The drivers Auto tries on this GPU (GpuRules.chain) and this start's place in the trial, from [setupGpu]. */
    private var trialChain: List<GpuRules.Driver> = emptyList()
    private var trialInfo: GpuTrial.Pick? = null
    /** The system GPU's name, the trial's key with the install. */
    private var trialGpuName = "none"
    /** The system GPU is one we test ([GpuRules.tested]): the watch doesn't fail its pick for a black screen. */
    private var trialTested = false
    /** A trial restart is under way: onCreate stops and onDestroy does nothing (the process is ended for the next start). */
    private var trialRestarting = false

    /**
     * Before the game loads: this start's driver can't work ([reason]: it doesn't load, or lacks what parallel-RDP needs),
     * so the trial moves to the next one and the game screen starts again with it, no game start spent (approved gputry
     * mockup: "checked off quietly").
     */
    private fun trialFailNow(reason: String) {
        val t = trial ?: return
        val name = trialChain.getOrNull(trialInfo?.level ?: 0)?.shown ?: "?"
        if (!t.fail(reason)) return
        JLog.w(TAG, "GPU trial: $name $reason, starting again with the next driver")
        trialRestarting = true
        finish()
        RestartActivity.restart(this, trialNextStart())
    }

    // ---- Auto's speed check (approved drvspeed mockup 2026-11-15, scratchpad build_drvspeed.py) ----
    private var speed: GpuSpeed? = null
    /** This start's try of a faster driver; null on the good level. */
    private var speedTry: GpuSpeed.Try? = null
    /** The trial's good level, and the level this start runs (the try's or the good one). */
    private var speedGood = 0
    private var speedLevel = 0
    private var speedMd5: String? = null
    private var speedInstall = ""
    /** The try's picture showed. */
    private var speedDrew = false

    private fun chainName(level: Int) = trialChain.getOrNull(level)?.shown ?: "?"

    /** The try's driver can't work here ([reason], for the log): failed, and the game screen starts again on the good level. */
    private fun speedFailNow(reason: String) {
        val t = speedTry ?: return
        speed?.failed(t.level, true)
        JLog.w(TAG, "GPU speed: ${chainName(t.level)} $reason, starting again with ${chainName(speedGood)}")
        trialRestarting = true
        finish()
        RestartActivity.restart(this, trialNextStart())
    }

    /** At the start: pop-up 1 or 4 for a try's first start, or the word that the last try didn't start. */
    private fun speedStartPopup(gone: GpuSpeed.Start.Gone?) {
        val p = when {
            gone != null -> {
                JLog.w(TAG, "GPU speed: ${chainName(gone.level)} didn't start (${gone.why.ifEmpty { "checks" }}), back to ${chainName(speedGood)}")
                InGamePopup("gpuspeed", InGamePopup.Kind.GpuBack, "${chainName(gone.level)} didn't start in this game",
                    "Back to ${chainName(speedGood)}", "")
            }
            speedTry?.begun == false -> speedTry!!.let { t ->
                JLog.i(TAG, "GPU speed: trying ${chainName(t.level)} over ${chainName(speedGood)}${if (t.fromList) " (the reports' list)" else ""}")
                if (t.fromList) InGamePopup("gpuspeed", InGamePopup.Kind.GpuFaster,
                    "Faster on the ${trialGpuName.replace("(TM)", "").replace(Regex("\\s+"), " ").trim()} in players' reports",
                    "Switching to ${chainName(t.level)}", "Joey64 checks it on this device as you play")
                else InGamePopup("gpuspeed", InGamePopup.Kind.GpuFaster, "Trying a faster driver", chainName(t.level),
                    "${chainName(speedGood)} ran this game below full speed")
            }
            else -> null
        } ?: return
        overlay?.show(p) ?: earlyPops.add(p)
    }

    /** No picture in time on the try's driver: card 3, then the game screen starts again on the good level. */
    private fun speedNoPicture() {
        val t = speedTry ?: return
        speed?.failed(t.level, false)
        trial = null; speedTry = null
        JLog.w(TAG, "GPU speed: ${chainName(t.level)} drew no picture, back to ${chainName(speedGood)}")
        if (!menuOpen) overlay?.screen = com.joeysretro.joey64.launcher.ingame.GpuTrialCard.speedCard(chainName(t.level), chainName(speedGood))
        fpsHandler.postDelayed({ restartForTrial() }, TRIAL_CARD_MS)
    }

    /** Settings' "speed_note": each tried driver's work a frame here, the one in use first, then fastest first, failed last. */
    private fun speedNote(): String? {
        val sp = speed ?: return null
        val sum = sp.summary()
        if (sum.size < 2) return null
        val ordered = sum.entries.sortedWith(compareBy({ it.key != speedGood }, { it.value == null }, { it.value ?: 0.0 }))
        return ordered.mapNotNull { (l, ms) -> trialChain.getOrNull(l)?.let { "${it.id}:${ms?.let { m -> "%.1f".format(java.util.Locale.ROOT, m) } ?: "-"}" } }
            .joinToString(",")
    }

    /** Work a frame over stretches of normal play, every [SPEED_POLL_MS]; added to the store every [SPEED_FLUSH_MS]. */
    private val speedBuf = DoubleArray(2)
    /** The work totals when this game loaded (the host's count the whole process). */
    private val workBase = DoubleArray(2)
    /** This start's driver for reports: a [GpuRules.Driver] id, "system" or "custom" (a dev driver). */
    private var driverIdNow = "system"
    private var speedLast: DoubleArray? = null
    private var speedAcc = GpuSpeed.Measure(0.0, 0, 0, 0)
    private var speedFlushAt = 0L
    private val speedSample = object : Runnable {
        override fun run() {
            if (speed == null || isFinishing || quitting) return
            NativeBridge.workTotals(speedBuf)
            val now = android.os.SystemClock.elapsedRealtime()
            val run = NativeBridge.framesRun()
            val ok = sessionCounting && !slowMotion && !rewindHeld && !net.inRoom && (speedTry == null || speedDrew)
            val last = speedLast
            if (ok && last != null && speedBuf[1] >= last[1]) speedAcc += GpuSpeed.Measure(speedBuf[0] - last[0],
                (speedBuf[1] - last[1]).toLong(), now - last[3].toLong(), (run - last[2].toInt()).toLong().coerceAtLeast(0))
            speedLast = if (ok) doubleArrayOf(speedBuf[0], speedBuf[1], run.toDouble(), now.toDouble()) else null
            if (now - speedFlushAt >= SPEED_FLUSH_MS) speedFlush(true)
            fpsHandler.postDelayed(this, SPEED_POLL_MS)
        }
    }

    /** Adds the stretch so far and asks what's next; [popups]: on screen (not from onStop). */
    private fun speedFlush(popups: Boolean) {
        speedFlushAt = android.os.SystemClock.elapsedRealtime()
        val sp = speed ?: return
        val md5 = speedMd5 ?: return
        val m = speedAcc; speedAcc = GpuSpeed.Measure(0.0, 0, 0, 0)
        sp.measure(md5, speedLevel, m)
        val skip = trialInfo?.history?.map { it.first }?.toSet().orEmpty()
        when (val v = sp.judge(md5, speedLevel, speedGood, NativeBridge.contentFps(), skip)) {
            is GpuSpeed.Verdict.Next -> JLog.i(TAG, "GPU speed: ${chainName(speedGood)} is slow in this game, ${chainName(v.level)} next start")
            is GpuSpeed.Verdict.Kept -> {
                GpuTrial(trialStore(), speedInstall, trialGpuName, trialChain.size).keep(v.level)
                speedGood = v.level; speedTry = null
                JLog.i(TAG, "GPU speed: ${chainName(v.level)} kept, faster than ${chainName(v.from)}")
                getSharedPreferences("device", MODE_PRIVATE).edit().putString("driver", trialChain.getOrNull(v.level)?.id).apply()
                if (popups) overlay?.show(InGamePopup("gpuspeed", InGamePopup.Kind.GpuOk, "Faster driver found", chainName(v.level),
                    "Joey64 will use it on this device from now on"))
            }
            is GpuSpeed.Verdict.Back -> {
                speedTry = null
                JLog.i(TAG, "GPU speed: ${chainName(v.level)} wasn't faster than ${chainName(v.to)}, back to it")
                if (popups) overlay?.show(InGamePopup("gpuspeed", InGamePopup.Kind.GpuBack, "${chainName(v.level)} wasn't faster in this game",
                    "Back to ${chainName(v.to)}", "From the next start"))
            }
            GpuSpeed.Verdict.None -> {}
        }
        speedNote()?.let { getSharedPreferences("device", MODE_PRIVATE).edit().putString("speed_note", it).apply() }
    }

    /** The same start again (the same game, state and extras), for the trial's next driver. */
    private fun trialNextStart(): Intent = Intent(intent).apply {
        component = intent.component
        removeExtra(EXTRA_PLAY_NOTE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (data != null) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * The driver rule for reports (at most 120 characters, the server's limit): this start's driver, the ones that failed
     * before it with what each did, then Auto's rule.
     */
    private fun trialRule(rule: String, d: GpuRules.Driver, pick: GpuTrial.Pick?): String {
        if (pick == null) return rule.take(120)
        val failed = pick.history.joinToString(",") { (l, r) -> "${trialChain.getOrNull(l)?.id ?: "l$l"}:${trialShort(r)}" }
        val head = if (pick.good) "${d.id} good" else "trial ${d.id}"
        return (if (failed.isEmpty()) "$head; $rule" else "$head; failed $failed; $rule").take(120)
    }

    private fun trialShort(reason: String) = when (reason) {
        GpuTrial.NO_PICTURE -> "black"
        GpuTrial.STOPPED -> "died"
        "doesn't load on this GPU" -> "noload"
        "no Vulkan" -> "novk"
        "no Vulkan 1.1" -> "vk10"
        "no 8- or 16-bit storage" -> "nostorage"
        "not in this build" -> "missing"
        else -> reason.take(12)
    }

    /** The trial's card as the game opens: a driver tried before this one failed (screens 1-2), or none worked (3b-3c). */
    private fun trialCardAtStart() {
        val p = trialInfo ?: return
        if (trial == null || p.good) return
        val names = trialChain.map { it.shown }
        val o = overlay ?: return
        if (p.level >= names.size) {
            if (p.told || p.history.isEmpty()) return
            JLog.i(TAG, "GPU trial: no driver drew, GLideN64; the end card is up")
            menuOpen = true; sessionMark(); NativeBridge.pause(true)
            o.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.GpuNone(
                com.joeysretro.joey64.launcher.ingame.GpuTrialCard.steps(names, p.history, p.level),
                com.joeysretro.joey64.reports.ReportPrefs.on(this))
            return
        }
        com.joeysretro.joey64.launcher.ingame.GpuTrialCard.tryCard(names, p.history, p.level)?.let { o.screen = it }
    }

    /** The trial's driver showed a picture: it's the one from now on; the card goes and, after failures, the pop-up says so (3a). */
    private fun trialDrew() {
        val t = trial ?: return
        t.drew(); trial = null
        if (speedTry != null && !speedDrew) { speedDrew = true; speed?.drew(); JLog.i(TAG, "GPU speed: ${chainName(speedLevel)} drew a picture") }
        JLog.i(TAG, "GPU trial: a picture was drawn, good")
        val p = trialInfo ?: return
        if (overlay?.screen is com.joeysretro.joey64.launcher.ingame.PauseScreen.GpuTry) overlay?.screen = null
        if (!p.good && p.history.isNotEmpty() && p.level < trialChain.size) {
            val p2 = InGamePopup("gpuok", InGamePopup.Kind.GpuOk, "parallel-RDP is running", "With ${trialChain[p.level].shown}",
                "Joey64 will use it on this device from now on", stayMs = 5_000)
            overlay?.show(p2) ?: earlyPops.add(p2)
        }
    }

    /** No picture in time (the watch): the next driver, said on the card, then the game screen starts again with it. */
    private fun trialNoPicture() {
        val t = trial ?: return
        val p = trialInfo ?: return
        if (!t.fail(GpuTrial.NO_PICTURE)) return
        trial = null
        val after = t.decide()
        val names = trialChain.map { it.shown }
        JLog.w(TAG, "GPU trial: ${names.getOrNull(p.level)} drew no picture, restarting with level ${after.level}")
        val card = com.joeysretro.joey64.launcher.ingame.GpuTrialCard.tryCard(names, after.history, after.level)
        if (card != null && !menuOpen) overlay?.screen = card
        // Long enough to read the card's line before the screen goes.
        fpsHandler.postDelayed({ restartForTrial() }, TRIAL_CARD_MS)
    }

    /** The game screen again in a fresh process with the trial's next level; nothing is saved (the picture never showed). */
    private fun restartForTrial() {
        if (quitting || isFinishing) return
        quitting = true
        sessionEnd = "next"
        keepRecovery = true
        stateHandler.removeCallbacks(recoveryTick)
        if (owner === this) { NativeBridge.stop(); owner = null }
        finish()
        RestartActivity.restart(this, trialNextStart())
    }

    /** /sys/class/kgsl/kgsl-3d0/gpu_model ("Adreno740v2"), when this device lets the app read it. */
    private fun kgslModel(): String? = try { File("/sys/class/kgsl/kgsl-3d0/gpu_model").readText().trim().takeIf { it.isNotEmpty() } } catch (_: Exception) { null }

    private fun trialStore() = object : GpuTrial.Store {
        val prefs = getSharedPreferences("gpu_trial", MODE_PRIVATE)
        override fun get(key: String) = prefs.getString(key, null)
        // commit: on disk before the start that may crash
        override fun put(key: String, value: String?) { prefs.edit().putString(key, value).commit() }
        override fun keys(): Set<String> = prefs.all.keys
    }

    /** GpuRules' switches, unless --es env (a variable) or --es opts (a core option, applied after these) sets them. */
    private fun applyGpuSettings(list: List<GpuRules.Setting>) {
        for (st in list) {
            if (st.coreOption) NativeBridge.setOption(st.key, st.value)
            else if (android.system.Os.getenv(st.key) == null) android.system.Os.setenv(st.key, st.value, true)
            else continue
            JLog.i(TAG, "GPU setting ${st.key}=${st.value} (${st.rule})")
        }
    }

    /**
     * Watches a trial start for a picture: a drawn frame that isn't black (a driver that can't run parallel-RDP's shaders
     * draws frames, all black, so a drawn frame alone doesn't count; 2026-11-15). The frame is read back off the main
     * thread. No frame by [TRIAL_NO_FRAME_MS] of play, or only black ones by [TRIAL_BLACK_MS], is "no picture": the next
     * driver. Time with the menu or another popup up doesn't count; in a netplay room it never fails (the room may wait).
     * On a GPU we test the pick is never failed for its picture (Joey 2026-11-15 night: it changes only for a broken
     * game, by a per-game rule): a dark first scene after an update stepped it down (review 2026-11-15). Where nothing
     * is judged (that, a known-good level) a drawn frame is enough and no frame is read back: reading one every 0.5 s
     * went on all session in a dark game.
     */
    private var trialRunMs = 0L
    @Volatile private var trialChecking = false
    private val trialCheck by lazy { java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "joey64-gputrial") } }
    private val trialWatch = object : Runnable {
        override fun run() {
            if (trial == null || isFinishing || quitting) return
            val scr = overlay?.screen
            if (!menuOpen && !hotkeyPaused && resumed && (scr == null || scr is com.joeysretro.joey64.launcher.ingame.PauseScreen.GpuTry)) trialRunMs += TRIAL_POLL_MS
            val drawn = NativeBridge.framesDrawn() > 0
            // Out of time, once the last frame check is back (a new one starts every poll, so it's tested first)
            val p = trialInfo
            val net = android.system.Os.getenv("N64_NETPLAY") != null || android.system.Os.getenv("N64_NETPLAY_SERVE") != null
            val speedWait = speedTry != null && !speedDrew
            // (the debug switch N64_TRIAL_BLACK judges a tested GPU too, so the cards can still be seen on our devices)
            val blackDebug = BuildConfig.DEBUG && android.system.Os.getenv("N64_TRIAL_BLACK") != null
            val judged = (p != null && !p.good && p.level < trialChain.size && (!trialTested || blackDebug)) || speedWait
            if (judged && !net && !trialChecking &&
                ((!drawn && trialRunMs >= TRIAL_NO_FRAME_MS) || trialRunMs >= TRIAL_BLACK_MS)) { if (speedWait) speedNoPicture() else trialNoPicture(); return }
            if (drawn && !judged && !blackDebug) { trialDrew(); return }
            if (drawn && !trialChecking) {
                trialChecking = true
                trialCheck.execute {
                    // Debug: --es env N64_TRIAL_BLACK=0:1 makes those levels' pictures count as black, to see the cards.
                    val fake = BuildConfig.DEBUG && android.system.Os.getenv("N64_TRIAL_BLACK")?.split(':')?.contains("${if (speedTry != null) speedLevel else trialInfo?.level}") == true
                    val lit = !fake && GpuRules.lit(NativeBridge.captureFrame(500))
                    runOnUiThread { trialChecking = false; if (lit && !isFinishing) trialDrew() }
                }
            }
            fpsHandler.postDelayed(this, TRIAL_POLL_MS)
        }
    }

    /**
     * The stats overlay's driver: the bundled ones by their release name (devtools/drivers/bundled.json's "shown", packed
     * as an asset, so it follows a driver update; code audit 3, p7 L2), a dev driver by its file, the system's by what it
     * reports.
     */
    private fun statsDriverName(lib: File?, dev: Boolean, reported: String): String = when {
        lib == null -> "System driver" + reported.substringAfter(", ", "").takeIf { it.isNotEmpty() && it.length <= 24 }?.let { " $it" }.orEmpty()
        dev -> "Dev driver ${lib.name}"
        else -> {
            val file = lib.relativeToOrNull(gpuDriverDir)?.invariantSeparatorsPath ?: lib.name
            val json = try { assets.open(BundledDrivers.ASSET).use { String(it.readBytes(), Charsets.UTF_8) } } catch (e: java.io.IOException) { "" }
            BundledDrivers.shownName(json, file) ?: "Bundled driver $file"
        }
    }

    /** The widescreen and 60 fps codes this game started with (Settings › Video), from [prepareGame]. */
    private var extrasOn = com.joeysretro.joey64.video.ExtrasOn.NONE
    /** This play's [com.joeysretro.joey64.video.GameCrop] key: the header game code ("CZLE"), or "md5:..." for a listed game with none; null before or for a disk alone. */
    private var gameCode: String? = null

    /**
     * On [gameIo]: [rom]'s widescreen and 60 fps versions from its kept cheat lists (the scan downloads them; nothing is
     * fetched here), and the ones Video's rows pick. None while Hardcore is on.
     */
    private fun gameExtras(rom: String): com.joeysretro.joey64.video.ExtrasOn =
        com.joeysretro.joey64.video.ExtrasOn.withOwnMode(gameExtrasFromLists(rom), romGameCode(rom), videoAtStart,
            com.joeysretro.joey64.achievements.RaHardcore.on())

    private fun gameExtrasFromLists(rom: String): com.joeysretro.joey64.video.ExtrasOn {
        val keys = headerKeys(rom)
        if (keys.isEmpty()) return com.joeysretro.joey64.video.ExtrasOn.NONE
        val v = videoAtStart
        if (v[com.joeysretro.joey64.video.VideoKey.Widescreen] == "off" && v[com.joeysretro.joey64.video.VideoKey.Fps] == "off")
            return com.joeysretro.joey64.video.ExtrasOn.NONE
        val lists = kotlinx.coroutines.runBlocking { com.joeysretro.joey64.cheats.CheatLists(this@MainActivity).sourcesForGame(keys, download = false) }
            ?: return com.joeysretro.joey64.video.ExtrasOn.NONE.copy(log = "lists not downloaded")
        val x = com.joeysretro.joey64.video.GameExtras.from(lists, keys.first())
        return com.joeysretro.joey64.video.ExtrasOn.of(x, v, com.joeysretro.joey64.achievements.RaHardcore.on())
    }

    /** [rom]'s header (game code, cheat keys, pack name), or null ("" a 64DD disk with no cartridge, or unreadable: logged under [what]). */
    private fun romHeader(rom: String, what: String): com.joeysretro.joey64.launcher.library.RomHeader? = if (rom.isEmpty()) null else try {
        val buf = File(rom).inputStream().use { i -> ByteArray(0x40).also { b -> if (i.read(b) < b.size) throw java.io.IOException("short ROM") } }
        val order = com.joeysretro.joey64.launcher.library.RomByteOrder.of(buf) ?: throw java.io.IOException("not an N64 ROM")
        order.toBigEndian(buf, buf.size)
        com.joeysretro.joey64.launcher.library.RomReader.parseHeader(buf)
    } catch (e: Exception) {
        JLog.e(TAG, "$what: can't read the ROM header: $e"); null
    }

    /** [rom]'s header name as texture packs are filed (RomHeader.packName), or null. */
    private fun romPackName(rom: String): String? = romHeader(rom, "texture packs")?.packName?.takeIf { it.isNotEmpty() }

    /** The pack this play uses (null: none), and its name for the picture taken in play. */
    private var texPack: com.joeysretro.joey64.video.TexPack? = null

    /**
     * Texture packs at game start (approved mockup 2026-11-15, screen 5): the game's pack by Settings › Video's choice, made
     * ready for the core (N64_RDP_TEXPACK), with the pop-up naming it for 4 s; a pack whose folder can't be read gets the
     * orange pop-up and the game plays with its own textures. A minute in, the frame is kept as the pack's picture
     * for Settings (Joey 2026-11-15, "Take them in play"), once.
     */
    private fun setUpTexPack(rom: String) {
        texPack = null
        val name = romPackName(rom) ?: return
        val store = com.joeysretro.joey64.video.TexPackStore(this)
        store.finishConversions()
        val index = store.index() ?: return
        val choice = video[com.joeysretro.joey64.video.VideoKey.TexPack]
        val note = { title: String, line: String ->
            InGamePopup("texpack", InGamePopup.Kind.TexPackNote, title, "Playing with the game's own textures", line)
        }
        // A pack picked for this game that's gone (its folder removed, or moved on the SD card) falls back to Auto; with no
        // other pack for the game, the orange pop-up says so (Duo 2026-11-16: it started without a word).
        val gone = choice.startsWith(com.joeysretro.joey64.video.TexPacks.PICK) &&
            index.none { it.id == choice.removePrefix(com.joeysretro.joey64.video.TexPacks.PICK) }
        if (gone) JLog.w(TAG, "texture packs: the pack picked for $name isn't in the pack folders any more, using Auto's")
        val pick = com.joeysretro.joey64.video.TexPacks.pick(index, name, choice) ?: run {
            if (gone) {
                val p = note("Texture pack not found", "Its folder was removed or moved. Pick another in Settings › Video › Texture pack")
                overlay?.show(p) ?: earlyPops.add(p)
            }
            return
        }
        val ready = store.prepare(pick, name)
        val p = when (ready) {
            is com.joeysretro.joey64.video.TexPackStore.Ready -> {
                android.system.Os.setenv("N64_RDP_TEXPACK", ready.dir.path, true)
                texPack = pick
                val building = pick.png && !store.isConverted(pick)
                val playsSize = if (pick.png) store.converted(pick)?.length() ?: pick.size else pick.size
                JLog.i(TAG, "texture packs: ${pick.shown} (${pick.file}, ${com.joeysretro.joey64.video.TexPacks.sizeLabel(playsSize)}) for $name" +
                    if (building) ", converting it this play" else "")
                // The picture waits for a play with the pack whole (while it's converted, HD textures aren't in yet).
                if (!building && !store.shot(pick, "pack").isFile) fpsHandler.postDelayed({ texShot(pick) }, TEXPACK_SHOT_MS)
                InGamePopup("texpack", InGamePopup.Kind.TexPack, "Texture pack", pick.shown,
                    if (building) "Getting it ready: HD textures come in after a while" else "HD textures come in as you play",
                    stayMs = 4_000)
            }
            is com.joeysretro.joey64.video.TexPackStore.NotReady -> when (ready.why) {
                com.joeysretro.joey64.video.TexPackStore.Why.NotCopied -> {
                    // Converting in the background, outside play (approved mockup texpacks4, screen 6): how far, or why it waits.
                    val st = com.joeysretro.joey64.video.TexConvStatus.read(store)
                    val room = st?.room?.get(pick.id)
                    val why = st?.failed?.get(pick.id)
                    val cur = st?.cur?.takeIf { st.fresh && it.id == pick.id }
                    JLog.w(TAG, "texture packs: ${pick.shown} isn't converted yet" + (cur?.let { " (${it.percent}%)" } ?: room?.let { " (waits for room)" }
                        ?: why?.let { " ($it)" } ?: "") + ", playing without it")
                    // It starts (or goes on) after this game: the conversion waits while a game is played.
                    Thread { com.joeysretro.joey64.video.TexConvert.kick(applicationContext) }.start()
                    when {
                        room != null -> note("Texture pack waits for room", "Needs ${com.joeysretro.joey64.video.TexPacks.sizeLabel(room.first)}, " +
                            "${com.joeysretro.joey64.video.TexPacks.sizeLabel(room.second)} free")
                        why != null -> note("Texture pack can't be converted", why)
                        cur != null -> note("Texture pack isn't ready yet", "Converting it: ${cur.percent}%. It waits while you play")
                        else -> note("Texture pack isn't ready yet", "Converting it after you play")
                    }
                }
                com.joeysretro.joey64.video.TexPackStore.Why.NotAPack -> {
                    JLog.w(TAG, "texture packs: ${pick.file} doesn't load, playing without it")
                    note("Texture pack can't be read", "${pick.file} isn't a whole GLideN64 pack")
                }
                com.joeysretro.joey64.video.TexPackStore.Why.Unreachable -> {
                    JLog.w(TAG, "texture packs: ${pick.file} can't be read, playing without it")
                    note("Texture packs folder can't be reached", "Pick it again in Settings › Video › Texture packs")
                }
            }
        }
        overlay?.show(p) ?: earlyPops.add(p)
    }

    /** Keeps this frame as [pack]'s picture with it, off the main thread; skipped while paused or in a menu. */
    private fun texShot(pack: com.joeysretro.joey64.video.TexPack) {
        if (destroyed || !gameLoaded || texPack == null) return
        if (!resumed || menuOpen || hotkeyPaused) { fpsHandler.postDelayed({ texShot(pack) }, 15_000); return }
        val f = com.joeysretro.joey64.video.TexPackStore(this).shot(pack, "pack")
        Thread {
            val png = com.joeysretro.joey64.states.FramePicture.capturePng(1500, 640) ?: return@Thread
            try { f.parentFile?.mkdirs(); val part = File(f.path + ".part"); part.writeBytes(png); part.renameTo(f) } catch (e: Exception) { JLog.e(TAG, "texture packs: picture: $e") }
        }.start()
    }

    /** [rom]'s header game code ("NDOE"), or null ("" a 64DD disk with no cartridge, or unreadable). */
    private fun romGameCode(rom: String): String? = romHeader(rom, "emulation")?.gameCode

    /**
     * README item 36 (shipped block lists): a game with no block list of its own yet starts from the one in
     * `assets/blocklists/<CRC1-CRC2-country>.txt` (recorded on the Flip), so its first play gets the early compiles too
     * (Mario Kart 64's race-start hitch). The core checks each block against the code in RAM, so a list from another
     * copy only compiles the blocks that match; from then on the core adds to the game's own copy.
     */
    private fun shippedBlockList(rom: String, to: File) {
        val key = headerKeys(rom).firstOrNull() ?: return
        try {
            val part = File(to.path + ".part")
            assets.open("blocklists/$key.txt").use { i -> part.outputStream().use { o -> i.copyTo(o) } }
            if (part.renameTo(to)) JLog.i(TAG, "block list: started from the shipped list for $key")
        } catch (e: java.io.FileNotFoundException) {
            // none shipped for this game
        } catch (e: Exception) {
            JLog.e(TAG, "block list: shipped list for $key: $e")
        }
    }

    /** The cheat lists' keys for [rom]'s header ("" a 64DD disk with no cartridge: none). */
    private fun headerKeys(rom: String): List<String> = romHeader(rom, "cheats")?.let { h ->
        try { com.joeysretro.joey64.cheats.CheatLogic.keys(com.joeysretro.joey64.cheats.CheatLogic.keyOf(h)) }
        catch (e: Exception) { JLog.e(TAG, "cheats: can't key the ROM header: $e"); null }
    } ?: emptyList()

    /** Settings › Video for the game this screen was started for (onCreate), then as Game settings changes it in game. */
    private var video = com.joeysretro.joey64.video.VideoSettings.DEFAULTS
    /** Video as the game started: the rows read only at start (VideoKey.AT_START) keep these values until a restart. */
    private var videoAtStart = com.joeysretro.joey64.video.VideoSettings.DEFAULTS
    private var audioAtStart: com.joeysretro.joey64.audio.AudioSettings? = null
    /** Settings › Emulation as the game started (every row is read only at start). */
    private var emuAtStart = com.joeysretro.joey64.emulation.EmuSettings.DEFAULTS
    /** This play runs overclocked (2x or 4x, Casual): the core takes its speed only at start, so Hardcore turned on
     *  mid-game restarts the game screen instead of resetting the core ([restartInHardcore]). */
    @Volatile private var overclockedPlay = false
    /** A Hardcore start set Overclock's 2x or 4x aside: the play runs at Normal (the stats overlay leaves it out). */
    @Volatile private var overclockSetAside = false
    private var parallel = false
    /** --ez sharp / sharp2 (dev): they set the N64 filters themselves, so Game settings leaves those alone. */
    private var devSharp = false
    /** The scale Look Auto goes by when it isn't the Resolution row's (1 in a netplay room); the caps never take 2x and up below 2x. */
    private var lookScale: Int? = null
    /** The ROM and save name the game started with, for Restart now. */
    private var startRom: String? = null
    private var startSave: String? = null
    /** Start-only rows the restart question was already asked about (key to value), so it's asked once per change. */
    private val restartAsked = HashMap<String, String>()

    /** The fastest of the screen's modes at its current size (null when it has only one rate). */
    private fun topRefreshMode(): android.view.Display.Mode? {
        @Suppress("DEPRECATION") val d = windowManager.defaultDisplay ?: return null
        val now = d.mode
        val same = d.supportedModes.filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
        // only a whole multiple of 60 Hz (120, 180, 240): a 90, 144 or 165 Hz mode shows 60 fps unevenly (release audit
        // 2026-11-22), so a screen without one is left as it is
        val fits = same.filter { val k = Math.round(it.refreshRate / 60f); k >= 1 && kotlin.math.abs(it.refreshRate - 60f * k) < 1f }
        val top = fits.maxByOrNull { it.refreshRate } ?: return null
        return if (same.size > 1) top else null
    }

    /** Fill the camera cutout (the window reaches behind it only when asked; it matters for Stretch) and Screen orientation. */
    private fun applyWindowVideo() {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (video.on(com.joeysretro.joey64.video.VideoKey.Cutout))
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            // Ask for the screen's fastest mode at its current size while the game screen is up (Fractal 0f0cc53b77,
            // Joey 2026-11-21: "Add it, check devices"): Android keeps many screens at 60 Hz for an app that asks for
            // nothing, and Steady pacing presents each frame twice at 120 Hz. `--es env N64_TOP_REFRESH=0` (dev) asks
            // for nothing, as before.
            if (devString("env")?.contains("N64_TOP_REFRESH=0") != true) topRefreshMode()?.let {
                if (preferredDisplayModeId != it.modeId) JLog.i(TAG, "display: asking for mode ${it.modeId}, ${it.physicalWidth}x${it.physicalHeight} at ${it.refreshRate} Hz")
                preferredDisplayModeId = it.modeId
            }
        }
        requestedOrientation = when (video[com.joeysretro.joey64.video.VideoKey.Orientation]) {
            "portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "auto" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    /**
     * Back from Game settings (approved mockup 2026-11-07, screen 9): Video and Audio read again. What
     * can change while the game runs does now (the picture's shape, colour, sharpening, black frames,
     * the N64 filters, cutout, orientation; volume, mute, mono, fast-forward sound); the rows read
     * only at start (AT_START) keep the values the game started with until it starts again.
     */
    private fun applyGameSettings(saveName: String) {
        val fresh = com.joeysretro.joey64.video.VideoStore(this).forGame(saveName)
        val atStart = com.joeysretro.joey64.video.VideoKey.AT_START
        val next = com.joeysretro.joey64.video.VideoSettings(fresh.values.filterKeys { it !in atStart } + videoAtStart.values.filterKeys { it in atStart })
        val before = video
        video = next
        applyVideoDisplay()
        applyPicture()
        applyWindowVideo()
        if (!devSharp) {
            val scale = lookScale ?: videoAtStart.int(com.joeysretro.joey64.video.VideoKey.Resolution)
            val was = com.joeysretro.joey64.video.VideoApply.of(before, parallel, scale)
            val now = com.joeysretro.joey64.video.VideoApply.of(next, parallel, scale)
            val changed = now.coreOptions.filter { (k, v) -> was.coreOptions[k] != v }
            if (changed.isNotEmpty()) {
                NativeBridge.setOptionsLive(changed.keys.toTypedArray(), changed.map { (k, v) -> capUpscaling(k, v) }.toTypedArray())
                JLog.i(TAG, "game settings: core options now ${changed}")
            }
        }
        val a = applyAudioNow(saveName)
        val start = audioAtStart ?: a
        JLog.i(TAG, "game settings: audio volume ${a.volume}, mute ${a.on(com.joeysretro.joey64.audio.AudioKey.Mute)}, ff sound ${a.ffMode}, latency kept ${start.latencyMode}; video aspect ${next[com.joeysretro.joey64.video.VideoKey.Aspect]}")
    }

    /** The start-only rows whose saved value differs from the one the game started with: name (value) for each, in row order. */
    private fun pendingAtStart(saveName: String): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        val v = com.joeysretro.joey64.video.VideoStore(this).forGame(saveName)
        for (k in com.joeysretro.joey64.video.VideoKey.entries) {
            if (k !in com.joeysretro.joey64.video.VideoKey.AT_START || v[k] == videoAtStart[k]) continue
            out += Triple("v:" + k.name, startRowName(k.name), com.joeysretro.joey64.video.VideoChoices.label(k, v[k]))
        }
        val e = com.joeysretro.joey64.emulation.EmuStore(this).forGame(saveName)
        for (k in com.joeysretro.joey64.emulation.EmuKey.entries) {
            if (e[k] == emuAtStart[k]) continue
            out += Triple("e:" + k.name, startRowName(k.name), com.joeysretro.joey64.emulation.EmuChoices.label(k, e[k]))
        }
        // A Transfer Pak picked or changed in game (approved paksmenu mockup 2026-11-17, screen 7): its Game Boy game
        // loads only when the game starts. Going back to what started, or to no Transfer Pak, needs nothing.
        tpakAtStart?.let { was ->
            val now = controlsStore.forGame(saveName).players
            for (i in 0..3) {
                val key = com.joeysretro.joey64.controls.PakSummary.transferKey(now[i])
                if (key.isEmpty() || key == was[i]) continue
                out += Triple("t:$i", "Player ${i + 1}'s Transfer Pak", now[i].gbGame?.let { com.joeysretro.joey64.controls.GameBoyFiles.title(it.name) } ?: "")
            }
        }
        val start = audioAtStart
        if (start != null) {
            val a = com.joeysretro.joey64.audio.AudioStore(this).forGame(saveName)
            for (k in com.joeysretro.joey64.audio.AudioKey.entries) {
                if (k !in com.joeysretro.joey64.audio.AudioKey.AT_START || a[k] == start[k]) continue
                out += Triple("a:" + k.name, startRowName(k.name), com.joeysretro.joey64.audio.AudioChoices.label(k, a[k]))
            }
        }
        return out
    }

    private fun startRowName(key: String) = when (key) {
        "Driver" -> "GPU driver"; "Pacing" -> "Frame pacing"; "Thermal" -> "Thermal fallback"; "Supersample" -> "Supersampling"
        "NativeTexLod" -> "Native texture detail"; "NativeTexRect" -> "Native 2D (TEX_RECT)"; "Apps" -> "Play alongside other apps"
        "Fps" -> "60 fps"; "CpuCore" -> "CPU core"; "ExpansionPak" -> "Expansion Pak"; "RunAhead" -> "Run-ahead"; "FrameSkip" -> "Frame skip"
        else -> key
    }

    /**
     * Resuming after Game settings changed a row read only at start (mockup screen 10): asks once per
     * change whether to restart. True when the question is up instead of the game.
     */
    private fun askRestart(): Boolean {
        val st = states ?: return false
        // In a netplay room a restart would leave the room: no question, the rows apply at the next start
        // (code audit 4, E2-M1).
        if (android.system.Os.getenv("N64_NETPLAY") != null || android.system.Os.getenv("N64_NETPLAY_SERVE") != null) return false
        val pending = pendingAtStart(st.saveName)
        if (pending.isEmpty() || pending.all { (id, _, value) -> restartAsked[id] == value }) return false
        for ((id, _, value) in pending) restartAsked[id] = value
        val names = pending.map { (_, name, value) -> "$name ($value)" }
        val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
        // Only a Transfer Pak change: the approved line (paksmenu mockup screen 7).
        val sub = if (pending.all { it.first.startsWith("t:") }) "The Transfer Pak's Game Boy game loads when the game starts."
            else "$list ${if (names.size == 1) "applies" else "apply"} when the game next starts."
        overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Restart(sub)
        return true
    }

    /**
     * Restart now: the auto slot is written, then the game screen starts again in a fresh process
     * (RestartActivity, as switchGame) and loads it. The game's own screen is finished first, so Back
     * from the new one goes where it went before (the library). Without a written state the game
     * starts from the beginning.
     */
    /**
     * Hardcore turned on mid-game in an overclocked play: the core takes its CPU speed only when a game starts (a reset
     * keeps it), so the game screen starts again fresh, in Hardcore at Normal speed ([EXTRA_HARDCORE_PLAY]), with the
     * start's Overclock pop-up. Nothing is saved, as the in-place restart saves nothing; the recovery state stays.
     */
    private fun restartInHardcore() {
        val st = states ?: return
        val rom = startRom ?: return
        if (quitting) return
        sessionEnd = "next"
        quitting = true
        keepRecovery = true
        overlay?.screen = null
        sessionMark(); menuOpen = false
        NativeBridge.pause(true)
        stateHandler.removeCallbacks(recoveryTick)
        JLog.i(TAG, "Hardcore turned on mid-game in an overclocked play: the game starts again at Normal speed")
        val next = gameStart(this).apply {
            carriedExtras()?.let { putExtras(it) }
            removeExtra("rom")
            intent.data?.let { d ->
                if (!hasExtra(EXTRA_SOURCE_URI)) putExtra(EXTRA_SOURCE_URI, d.toString())
                if (!hasExtra(EXTRA_SOURCE_NAME)) displayName(d)?.let { putExtra(EXTRA_SOURCE_NAME, it) }
            }
            putExtra(EXTRA_ROM_PATH, rom)
            putExtra(EXTRA_SAVE_NAME, startSave ?: st.saveName)
            gameMd5?.let { putExtra(EXTRA_KNOWN_MD5, it) } ?: removeExtra(EXTRA_KNOWN_MD5)
            removeExtra(EXTRA_LOAD_STATE); removeExtra(EXTRA_PLAY_NOTE)
            putExtra(EXTRA_CASUAL_STATE, false)
            putExtra(EXTRA_WAS_HARDCORE, false)
            putExtra(EXTRA_HARDCORE_PLAY, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (owner === this) { NativeBridge.stop(); owner = null }
        finish()
        RestartActivity.restart(this, next)
    }

    private fun restartForSettings() {
        val st = states ?: return
        val rom = startRom ?: return
        if (quitting) return
        sessionEnd = "next"
        quitting = true
        overlay?.screen = null
        sessionMark(); menuOpen = false
        NativeBridge.pause(true)
        stateHandler.removeCallbacks(recoveryTick)
        stateHandler.post {
            // Always saved, Save on quit or not: the restart loads it straight back. As the recovery state, so the quit
            // saves stay as they were (code audit 3, M1: each restart pushed the oldest kept one out); the restarted
            // game replaces or clears it as any play does.
            val saved = !startLoadFailed && !roomPlay && st.saveRecovery()
            if (saved) keepRecovery = true
            JLog.i(TAG, "game settings: restarting for the new settings, its state ${if (saved) "kept as the recovery state" else "not written"}")
            runOnUiThread {
                val next = gameStart(this).apply {
                    carriedExtras()?.let { putExtras(it) }
                    removeExtra("rom")
                    intent.data?.let { d ->
                        if (!hasExtra(EXTRA_SOURCE_URI)) putExtra(EXTRA_SOURCE_URI, d.toString())
                        if (!hasExtra(EXTRA_SOURCE_NAME)) displayName(d)?.let { putExtra(EXTRA_SOURCE_NAME, it) }
                    }
                    putExtra(EXTRA_ROM_PATH, rom)
                    putExtra(EXTRA_SAVE_NAME, startSave ?: st.saveName)
                    gameMd5?.let { putExtra(EXTRA_KNOWN_MD5, it) } ?: removeExtra(EXTRA_KNOWN_MD5)
                    if (saved) putExtra(EXTRA_LOAD_STATE, STATE_RECOVERY) else removeExtra(EXTRA_LOAD_STATE)
                    // Coming back from a state is a state load, so the restarted play is Casual; a Hardcore play says so
                    // with the approved Hardcore off pop-up (part B mockup 2026-11-16, screen 3).
                    putExtra(EXTRA_CASUAL_STATE, saved)
                    putExtra(EXTRA_WAS_HARDCORE, saved && com.joeysretro.joey64.achievements.RaHardcore.on())
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (owner === this) { NativeBridge.stop(); owner = null }
                finish()
                RestartActivity.restart(this, next)
            }
        }
    }

    /** The save name the launcher gave, or the one the ROM's file name makes, before the ROM is read (null: the dev path). */
    private fun earlySaveName(): String? {
        val i = intent ?: return null
        launcherString(EXTRA_SAVE_NAME)?.takeIf { it.isNotEmpty() }?.let { return cleanSaveName(it) }
        val file = launcherString(EXTRA_SOURCE_NAME) ?: (if (devBuild) i.getStringExtra("rom") else null) ?: i.data?.let { displayName(it) } ?: return null
        return saveName(file)
    }

    /**
     * The game's MD5 before the ROM is loaded, for Auto's per-game renderer ([GpuRules.gameRenderer]): the launcher's
     * EXTRA_MD5, else a handed-over or dev (`--es rom`) ROM hashed here; null when not known.
     */
    private fun earlyMd5(): String? {
        knownMd5()?.let { return it }
        // A handed-over ROM comes with the MD5 the start before took (EXTRA_KNOWN_MD5); hashing it here held the main
        // thread up to a second (code audit 3, M18). Only the dev ROM is still hashed here.
        val path = devString("rom")?.let { n -> listOfNotNull(getExternalFilesDir(null), filesDir).map { File(it, "roms/$n") }.firstOrNull { it.exists() }?.path }
            ?: return null
        return romMd5(path)
    }

    /** The ROM's MD5 when the start says it: the library's, or the one the game screen before took. */
    private fun knownMd5(): String? =
        launcherString(EXTRA_MD5)?.takeIf { it.isNotEmpty() } ?: launcherString(EXTRA_KNOWN_MD5)?.takeIf { it.isNotEmpty() }

    /** This play's ROM MD5 (.z64 order), from [knownMd5] or hashed in [prepareGame] on [gameIo]; null when not known. */
    @Volatile private var gameMd5: String? = null

    /** The host's display state from [video]: shape, integer scaling, black frames, colour, sharpening. */
    // ---- Settings › Video's Picture rows (approved mockup 2026-11-08; video/Picture.kt) ----------------
    /** Next shader's pick, for this play only (a Shader value); null: Settings › Video's. */
    private var playShader: String? = null
    /** Shader on/off: the shader off for this play. */
    private var shaderOff = false
    private val lookImageKeys = arrayOfNulls<String>(2)

    /** The screen's height in landscape, for Auto (Sharp pixels when it isn't a whole number of times 240). */
    private fun screenSize(): Pair<Int, Int> {
        val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) windowManager.maximumWindowMetrics.bounds.let { it.width() to it.height() }
            else android.graphics.Point().also { @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealSize(it) }.let { it.x to it.y }
        return maxOf(w, h) to minOf(w, h)
    }

    /** What Auto gives here: the resolution this play draws at (Upscale hotkeys included). */
    internal fun autoSharp(v: com.joeysretro.joey64.video.VideoSettings): Boolean {
        val res = if (parallel) playUpscale?.removeSuffix("x")?.toIntOrNull() ?: v.int(com.joeysretro.joey64.video.VideoKey.Resolution)
            else v.int(com.joeysretro.joey64.video.VideoKey.Resolution)
        return com.joeysretro.joey64.video.Picture.autoSharp(res, screenSize().second)
    }

    /**
     * Hands the host Shader, Sides of the picture, Overlay and colour-blind help: [v] (the in-game Picture
     * panel's live pick) or the game's settings with this play's Next shader / Shader on/off.
     */
    internal fun applyPicture(v: com.joeysretro.joey64.video.VideoSettings? = null) {
        val s = v ?: playShader?.let { com.joeysretro.joey64.video.VideoSettings(video.values + (com.joeysretro.joey64.video.VideoKey.Shader to it)) } ?: video
        val store = com.joeysretro.joey64.video.PictureStore(this)
        val setup = if (shaderOff && v == null) com.joeysretro.joey64.video.ShaderSetup(emptyList())
            else com.joeysretro.joey64.video.Picture.setup(s, store::preset, autoSharp(s))
        lastPictureV = v
        val n = com.joeysretro.joey64.video.Picture.native(s, setup, raOverlayFor(s))
        NativeBridge.setLook(n.ids, n.params, n.scale, n.sides, n.colourBlind, n.overlay, n.placeX, n.placeY, n.placeW)
        handLookImage(0, n.sidesImage, store)
        handLookImage(1, n.overlayImage, store)
        applySlang(setup.slang, setup.slangParams)
        NativeBridge.showAgain() // paused (the in-game Picture panel): the change shows now
        JLog.i(TAG, "picture: shader ${setup.label}, scale ${setup.scale}, sides ${n.sides}, overlay ${n.overlay}%, colour-blind ${n.colourBlind}")
    }

    private var slangKey: String? = null

    // ---- Sides › RetroArch overlay (approved mockup 2026-11-08; video/RaOverlay.kt) ----
    /** What [raUse] was worked out for: the overlay, the orientation, its Place the game. */
    private var raKey: String? = null
    private var raUse: com.joeysretro.joey64.video.Picture.RaUse? = null
    private var raNoneTold = false
    private var lastPictureV: com.joeysretro.joey64.video.VideoSettings? = null

    /**
     * The RetroArch overlay this game uses with [s], or null (not that Sides choice, none for this game, or not ready:
     * its copy, image and hole are worked out on [gameIo] first, then the picture is handed over again).
     */
    private fun raOverlayFor(s: com.joeysretro.joey64.video.VideoSettings): com.joeysretro.joey64.video.Picture.RaUse? {
        val ra = com.joeysretro.joey64.video.RaOverlay
        if (s[com.joeysretro.joey64.video.VideoKey.Sides] != "retroarch") { raKey = null; raUse = null; return null }
        val sel = s[com.joeysretro.joey64.video.VideoKey.SidesRa]
        val store = com.joeysretro.joey64.video.RaOverlayStore(this)
        val value = when {
            ra.isOverlay(sel) -> sel
            ra.isMatch(sel) -> store.matchFor(listOfNotNull(earlySaveName(), launcherString(EXTRA_TITLE)))
            else -> null
        }
        if (value == null) {
            raKey = null; raUse = null
            if (ra.isMatch(sel) && !raNoneTold) {
                raNoneTold = true
                val other = com.joeysretro.joey64.video.Picture.SIDES_OTHER.firstOrNull { it.value == s[com.joeysretro.joey64.video.VideoKey.SidesRaOther] }?.label ?: "None"
                pop("sides", InGamePopup.Kind.Sides, "Sides of the picture", "No overlay for this game", "Using $other")
            }
            return null
        }
        val portrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val place = store.place(value)
        val key = "$value|$portrait|$place"
        if (key == raKey) return raUse
        raKey = key; raUse = null
        val (lw, lh) = screenSize()
        val (sw, sh) = if (portrait) lh to lw else lw to lh
        gameIo.execute {
            val err = store.ensure(value)
            val img = if (err == null) store.imageFile(value, portrait) else null
            val spot = place ?: img?.let { store.holeOf(it) }?.let { (hole, iw, ih) -> hole?.let { ra.placeIn(it, iw, ih, sw, sh) } }
            runOnUiThread {
                if (destroyed || raKey != key) return@runOnUiThread
                if (img == null) {
                    if (err == null) store.fail(value, "its image isn't there")
                    pop("sides", InGamePopup.Kind.Problem, "Sides of the picture", "${ra.name(value)} didn't load",
                        "Its image is missing from the folder you picked")
                    return@runOnUiThread
                }
                JLog.i(TAG, "RetroArch overlay ${ra.name(value)}: ${img.name}, game at ${spot ?: "the middle"}")
                raUse = com.joeysretro.joey64.video.Picture.RaUse(img.path, spot)
                applyPicture(lastPictureV)
            }
        }
        return null
    }

    /**
     * Your own RetroArch preset (video/Slang.kt) to the host: its copy in files/video/slang/ (made on [gameIo] first
     * when there's none), then how it loaded: one that doesn't is recorded (the Shader row says "<name> didn't load")
     * and a pop-up says so; the game then draws with None.
     */
    private fun applySlang(value: String?, params: Map<String, Float>) {
        val names = params.keys.toTypedArray()
        val values = FloatArray(names.size) { params.getValue(names[it]) }
        val key = value?.let { it + "|" + com.joeysretro.joey64.video.Slang.paramsString(params) }
        if (key == slangKey) return
        val newPreset = value != slangKey?.substringBefore('|')
        slangKey = key
        if (value == null) { NativeBridge.setSlang(null, names, values); return }
        val slang = com.joeysretro.joey64.video.SlangStore(this)
        gameIo.execute {
            val err = slang.ensure(value)
            val file = slang.localFile(value)
            runOnUiThread {
                if (destroyed || slangKey != key) return@runOnUiThread
                if (err != null) { slangFailed(value, err); return@runOnUiThread }
                NativeBridge.setSlang(file.path, names, values)
                NativeBridge.showAgain()
                if (newPreset) checkSlang(value, file.path, 0)
            }
        }
    }

    /** Polls the host's word on [path] (its chain is made at the next frame drawn) for up to 5 s. */
    private fun checkSlang(value: String, path: String, tries: Int) {
        window.decorView.postDelayed({
            if (destroyed || slangKey?.substringBefore('|') != value) return@postDelayed
            val st = NativeBridge.slangStatus().split('\n', limit = 3)
            when {
                st.size >= 2 && st[1] == path && st[0] == "ok" -> com.joeysretro.joey64.video.SlangStore(this).loaded(value)
                st.size >= 3 && st[1] == path && st[0] == "error" -> slangFailed(value, st[2])
                tries < 20 -> checkSlang(value, path, tries + 1)
            }
        }, 250)
    }

    private fun slangFailed(value: String, message: String) {
        JLog.e(TAG, "RetroArch preset $value didn't load: $message")
        com.joeysretro.joey64.video.SlangStore(this).fail(value, message)
        val name = com.joeysretro.joey64.video.Slang.name(value)
        pop("shader", InGamePopup.Kind.Problem, "Shader", "$name didn't load", com.joeysretro.joey64.video.Slang.plainError(message))
    }

    /** A sides (0) or overlay (1) image to the host, read on [gameIo] when it changed. */
    private fun handLookImage(slot: Int, what: String?, store: com.joeysretro.joey64.video.PictureStore) {
        val f = what?.takeIf { it != com.joeysretro.joey64.video.Picture.FRAME }?.let { if (it.startsWith("/")) File(it) else File(store.imagesDir, it) }
        val key = when { what == null -> null; f == null -> "frame"; else -> "${f.name}:${f.lastModified()}:${f.length()}" }
        if (key == lookImageKeys[slot]) return
        lookImageKeys[slot] = key
        if (key == null) { NativeBridge.setLookImage(slot, null, 0, 0); return }
        val (sw, sh) = screenSize()
        gameIo.execute {
            val img = if (f == null) com.joeysretro.joey64.video.PictureImages.rgba(
                com.joeysretro.joey64.video.PictureImages.frame(this, 960, (960L * sh / maxOf(1, sw)).toInt().coerceAtLeast(1)))
                else com.joeysretro.joey64.video.PictureImages.load(f)
            NativeBridge.setLookImage(slot, img?.bytes, img?.w ?: 0, img?.h ?: 0)
            NativeBridge.showAgain()
        }
    }

    /** Next shader (hotkey): Auto, None, the built-ins, then the presets, for this play. */
    private fun nextShader() {
        val list = com.joeysretro.joey64.video.Picture.cycle(com.joeysretro.joey64.video.PictureStore(this).presets().map { it.name })
        val cur = playShader ?: video[com.joeysretro.joey64.video.VideoKey.Shader]
        val i = (list.indexOf(cur) + 1).mod(list.size)
        playShader = list[i]
        shaderOff = false
        applyPicture()
        JLog.i(TAG, "next shader hotkey: ${list[i]} for this play")
        pop("shader", InGamePopup.Kind.Shader, "Next shader", com.joeysretro.joey64.video.Picture.valueLabel(list[i]), "${i + 1} of ${list.size} · for this play")
    }

    /** Shader on/off (hotkey): no shader for this play, and back. */
    private fun toggleShader() {
        shaderOff = !shaderOff
        applyPicture()
        val name = com.joeysretro.joey64.video.Picture.valueLabel(playShader ?: video[com.joeysretro.joey64.video.VideoKey.Shader])
        pop("shader", InGamePopup.Kind.Shader, "Shader on/off", if (shaderOff) "Shader off" else name, "For this play")
    }

    private fun applyVideoDisplay() {
        val v = video
        val (aw, ah) = com.joeysretro.joey64.video.VideoApply.aspect(v, extrasOn.shape)
        NativeBridge.setDisplay(aw, ah, v.on(com.joeysretro.joey64.video.VideoKey.Integer), v.on(com.joeysretro.joey64.video.VideoKey.Bfi))
        NativeBridge.setCrop(com.joeysretro.joey64.video.GameCrop.of(gameCode, v)) // a game's default when Crop edges isn't set (OoT)
        NativeBridge.setTrim(v.on(com.joeysretro.joey64.video.VideoKey.Trim))
        NativeBridge.setColour(v.int(com.joeysretro.joey64.video.VideoKey.Brightness), v.int(com.joeysretro.joey64.video.VideoKey.Contrast), v.int(com.joeysretro.joey64.video.VideoKey.Saturation), v.int(com.joeysretro.joey64.video.VideoKey.Gamma))
        NativeBridge.setSharpen(when (v[com.joeysretro.joey64.video.VideoKey.Sharpen]) { "cas" -> 1; "fsr" -> 2; else -> 0 }, v.int(com.joeysretro.joey64.video.VideoKey.SharpenStrength))
        // The state's picture held over a start goes where the game's will with these (a game's own Crop edges, Smash's
        // 4 lines, came after it was placed: it showed more and the game grew, Joey 2026-11-17).
        held?.let { h -> runOnUiThread { h.place() } }
    }

    // parallel-RDP at 4x on the Adreno 722 (Retroid Pocket Duo) drops most geometry (characters
    // vanish, the screen flickers between layers) on both Turnip builds tried, and runs at 40-55 fps
    // anyway; 2x renders correctly at 60. The Adreno 740 is fine at 4x.
    //
    // parallel-RDP binds the upscaled RDRAM (8 MB x factor^2) as one storage buffer, so the factor can't go past
    // what maxStorageBufferRange holds: Adreno reports 128 MB on Qualcomm's driver and Turnip (Thor, 2026-11-07),
    // so 4x (128 MB) is its top and 8x (512 MB) was a black screen at 19-35 fps.
    private fun capUpscaling(key: String, value: String): String {
        if (key != UPSCALE_KEY) return value
        val f = value.removeSuffix("x").toIntOrNull() ?: return value
        // Dev: --ez nocap true lifts the caps, to try other drivers
        if (devFlag("nocap")) return value
        var top = GpuRules.upscaleTop(gpuFamily, customDriverInUse).value
        if (storageRangeMb > 0) while (top > 1 && 8 * top * top > storageRangeMb) top /= 2
        if (f <= top) return value
        JLog.w(TAG, "parallel-RDP upscaling $value capped at ${top}x on $gpuName (storage buffer range $storageRangeMb MB)")
        return "${top}x"
    }

    private val gpuDriverDir get() = File(filesDir, "gpu_driver").apply { mkdirs() }

    /** Dev: the first .so in <external files>/drivers/ or files/drivers/, copied where it can be dlopen'd. */
    private fun devDriver(): File? {
        // Debug builds only (code audit 3, H10): Android/data/.../files/drivers is shared storage other apps can write on
        // Android 10, and a .so there would be loaded as the GPU driver, native code run inside Joey64.
        if (!devBuild) return null
        // Internal files/drivers too, for devices whose adb can't write Android/data (see resolveRom).
        val src = listOfNotNull(getExternalFilesDir("drivers"), File(filesDir, "drivers"))
            .firstNotNullOfOrNull { d -> d.listFiles { f -> f.name.endsWith(".so") }?.firstOrNull() } ?: return null
        // Own directory: dev drivers usually share the bundled one's file name, and overwriting that
        // copy kept it in use (its stamp still matched) after the dev driver was removed.
        val dst = File(File(gpuDriverDir, "dev").apply { mkdirs() }, src.name)
        if (!dst.exists() || dst.length() != src.length() || dst.lastModified() < src.lastModified()) src.copyTo(dst, true)
        return dst
    }

    /** A driver bundled in assets/drivers/, copied out once per app install/update; null if not bundled. */
    private fun bundledDriver(name: String): File? {
        val dst = File(gpuDriverDir, name)
        val stamp = File(gpuDriverDir, "$name.stamp")
        val version = packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
        if (dst.exists() && stamp.exists() && stamp.readText() == version) return dst
        return try {
            dst.parentFile?.mkdirs()
            val tmp = File(gpuDriverDir, "$name.part")
            assets.open("drivers/$name").use { i -> tmp.outputStream().use { i.copyTo(it) } }
            // Stamped only once the new copy is in place: a failed rename left the old (or no) driver stamped as this
            // version's (code audit 3, p1 L8). Not stamped, the next start copies it again.
            if (!tmp.renameTo(dst)) { tmp.delete(); JLog.e(TAG, "Driver $name: can't put the new copy in place"); return dst.takeIf { it.exists() } }
            stamp.writeText(version)
            dst
        } catch (e: java.io.IOException) {
            JLog.w(TAG, "Driver $name is not bundled"); null
        }
    }

    /**
     * The ROM path and its save name (the file name without extension, as RetroArch names saves).
     * ROM comes from a VIEW intent, else (dev) `--es rom <file>` in roms/, else test.z64, whose save
     * is named by content hash since different games get pushed there. M5 replaces this with a game list.
     */
    private fun resolveRom(): Pair<String, String>? {
        // Handed over by switchGame from the process before this one: the ROM is already unpacked.
        if (launcherString(EXTRA_ROM_PATH) != null) return handedRom()
        intent?.data?.let { uri ->
            val shown = displayName(uri)
            val inner = copyToCache(uri, shown) ?: run { JLog.e(TAG, "No N64 ROM in $uri"); return null }
            if (inner.isNotEmpty()) JLog.i(TAG, "ROM $inner from archive $uri")
            val save = saveName(shown ?: inner)
            return placeDisk(inner.ifEmpty { shown }, save) to save
        }
        // Some Android 11 devices (Retroid Pocket Flip) block adb from Android/data: there, copy the
        // ROM into internal storage with run-as instead.
        val dirs = listOfNotNull(getExternalFilesDir(null), filesDir)
        devString("rom")?.let { name ->
            val rom = dirs.map { File(it, "roms/$name") }.firstOrNull { it.exists() }
                ?: run { JLog.e(TAG, "ROM $name not found in roms/"); return null }
            if (!RomFiles.isArchive(rom.inputStream().use { i -> ByteArray(4).also { i.read(it) } }))
                return rom.absolutePath to saveName(rom.name)
            val inner = rom.inputStream().use { RomFiles.extract(it, romCache) }
                ?: run { JLog.e(TAG, "No N64 ROM in $name"); return null }
            JLog.i(TAG, "ROM $inner from archive $name")
            return placeDisk(inner, saveName(rom.name)) to saveName(rom.name)
        }
        if (!devBuild) return null
        return dirs.map { File(it, "test.z64") }.firstOrNull { it.exists() }?.let { it.absolutePath to "" }
    }

    private fun saveName(fileName: String) = GameStates.saveNameOf(fileName)

    /**
     * A 64DD disk just unpacked to [romCache] ([name] is its file name) moves to cache/dd/<save name>.<ext>, since the
     * core names the disk's save after the disk's file: saves/<that>.disk_save, as RetroArch does (the whole disk,
     * written as the game writes it). A ROM stays where it is.
     */
    private fun placeDisk(name: String?, saveName: String): String {
        if (!RomFiles.isDiskName(name) || saveName.isEmpty()) return romCache.absolutePath
        val dir = File(cacheDir, "dd").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val disk = File(dir, "$saveName.${name!!.substringAfterLast('.')}")
        if (!romCache.renameTo(disk)) { romCache.copyTo(disk, true); romCache.delete() }
        return disk.absolutePath
    }

    /**
     * The cartridge for a 64DD disk, unpacked to cache/dd-cart.rom: EXTRA_DD_CART (a content URI), or, dev, `--es ddcart
     * <file in roms/>`. Null: the disk alone.
     */
    private fun resolveDdCart(): String? {
        val out = File(cacheDir, "dd-cart.rom")
        val given = launcherString(EXTRA_DD_CART)
        val inner = when {
            given != null -> try { contentResolver.openInputStream(Uri.parse(given))?.use { RomFiles.extract(it, out) } } catch (e: Exception) { JLog.e(TAG, "64DD cartridge $given: $e"); null }
            else -> devString("ddcart")?.let { name ->
                listOfNotNull(getExternalFilesDir(null), filesDir).map { File(it, "roms/$name") }.firstOrNull { it.exists() }
                    ?.inputStream()?.use { RomFiles.extract(it, out) }
            }
        } ?: return null
        JLog.i(TAG, "64DD cartridge ${inner.ifEmpty { given ?: "" }}")
        return out.absolutePath
    }
    /** The running game's cheat-list keys (CheatLogic.keys), read from its header at start. */
    private var cheatKeys: List<String> = emptyList()

    /** The cheats the user left on for this game ([Prepared.cheats], read in [prepareGame]), given to the core before it loads the game. */
    private fun applyCheats(rom: String, saveName: String, userLines: List<String>) {
        cheatKeys = headerKeys(rom) // "": a 64DD disk with no cartridge, no header
        if (saveName.isEmpty()) return
        // The codes Hardcore keeps: the widescreen code (Joey 2026-11-16, option A), also for Hardcore turned on mid-game.
        NativeBridge.setHardcoreCodes(listOfNotNull(extrasOn.widescreenLine).toTypedArray())
        // Hardcore: no cheats (RetroAchievements' rules; the host also refuses them), 60 fps off; one pop-up says so
        // (approved part B mockup 2026-11-16, screen 7).
        if (com.joeysretro.joey64.achievements.RaHardcore.on()) {
            extrasOn.widescreenLine?.let { NativeBridge.addCheat(it) }
            if (userLines.isNotEmpty()) {
                JLog.i(TAG, "cheats: ${userLines.size} left on, not used in Hardcore")
                val n = userLines.size
                hardcoreStartPops += InGamePopup("hccheats", InGamePopup.Kind.Hardcore, "Hardcore",
                    if (n == 1) "1 cheat is off for this play" else "$n cheats are off for this play", "They stay on for Casual plays")
            }
            return
        }
        val lines = userLines + extrasOn.codeLines
        for (l in lines) NativeBridge.addCheat(l)
        cheatsMost = lines.size
        JLog.i(TAG, "cheats: ${lines.size} on, keys ${cheatKeys.joinToString()}")
    }

    // A file that was moved or deleted since the library listed it makes the provider throw from query
    // (IllegalArgumentException "Failed to determine if ...: FileNotFoundException", report 97: three crashes at start,
    // Thor 0.2.7): fall back to the link's own name, and the ROM copy then fails the start the normal way.
    private fun displayName(uri: Uri): String? =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.onFailure { JLog.w(TAG, "display name: ${it.javaClass.simpleName}: ${it.message?.take(120)}") }.getOrNull()
            ?: uri.lastPathSegment?.substringAfterLast('/')

    private val romCache get() = File(cacheDir, "current.rom")

    /**
     * Copies the ROM behind [uri] to [romCache], unpacking a zip or 7z (RomFiles). Returns the ROM's own name inside an
     * archive (logged only: saves are named after the archive, as RetroArch names them, checked on the Thor 2026-11-06:
     * "Paper Mario - TTYD64.zip" holding "Paper Mario (USA) (patched).z64" has "Paper Mario - TTYD64.srm"; the inner
     * name would also let a hack that keeps the original file name overwrite the real game's save), "" for a plain
     * ROM, or null when it holds no N64 ROM.
     */
    private fun copyToCache(uri: Uri, name: String?): String? =
        try { contentResolver.openInputStream(uri)?.use { RomFiles.extract(it, romCache, name) } }
        catch (e: Exception) { JLog.e(TAG, "ROM $uri can't be read: ${e.javaClass.simpleName}: ${e.message?.take(120)}"); null }

    /** What [prepareGame] got ready off the main thread: the ROM, its save name, a 64DD disk's cartridge, Settings › Saves. */
    private class Prepared(val rom: String, val saveName: String, val ddCart: String?, val savesOpts: com.joeysretro.joey64.saves.SavesOptions,
                           val extras: com.joeysretro.joey64.video.ExtrasOn, val tpak: List<Pair<String, String>>? = null, val md5: String? = null,
                           val cheats: List<String> = emptyList())
    /** [prepareGame] stopped because a 64DD disk's cartridge didn't unpack. */
    @Volatile private var cartFailed = false
    /** [prepareGame] is running on [gameIo]. */
    private var preparing = false
    /** Ready, waiting for a surface (it went away while the files were copied). */
    private var prepared: Prepared? = null
    private var surfaceUp = false

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!mine) return
        NativeBridge.setSurface(holder.surface)
        surfaceUp = true
        // Paused (the pause menu, or back from a picker it opened): draw the held frame on the new surface,
        // or the game stays black behind the menu until Resume (Thor 2026-11-08). Running, the next frame does.
        if (started) { NativeBridge.showAgain(); return }
        prepared?.let { launchGame(it); return }
        if (preparing) return
        showHeldPicture()
        // The file work before a game loads (unpacking the ROM, the 64DD BIOS, a picked data folder's saves, the
        // save backups: a 64DD disk save is 64.9 MB) runs off the main thread (code audit 2, finding 1).
        preparing = true
        reportGameState(loading = true)
        gameIo.execute {
            val p = try { prepareGame() } catch (e: Exception) { JLog.e(TAG, "can't get the game ready: $e"); null }
            runOnUiThread {
                preparing = false
                if (destroyed) return@runOnUiThread
                if (p == null) { reportGameState(loading = false); notStarted(cart = cartFailed, why = com.joeysretro.joey64.launcher.GameStartFailed.Why.Unreadable); return@runOnUiThread }
                prepared = p
                if (surfaceUp) launchGame(p)
            }
        }
    }

    /** The state's picture held over a start from a state ([HeldPicture]); null when this start has none. */
    private var held: HeldPicture? = null

    /**
     * A start from a state (Continue, carry on after a crash or Android ending the process, a slot): its picture goes
     * up at once, read on [gameIo] ahead of [prepareGame], and stays until the game's frames from that state are drawn.
     */
    private fun showHeldPicture() {
        val which = startLoad() ?: return
        val name = earlySaveName()?.takeIf { it.isNotEmpty() } ?: return
        val slot: Int? = when {
            which == STATE_AUTO -> StateSlots.AUTO
            which == STATE_RECOVERY -> null
            which.startsWith(STATE_SLOT) -> which.removePrefix(STATE_SLOT).toIntOrNull() ?: return
            else -> return
        }
        gameIo.execute {
            var trim: IntArray? = null
            val bmp = try {
                GameStates(this, name).startPicture(slot)?.let {
                    trim = com.joeysretro.joey64.states.FramePicture.trimOf(it)
                    android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)
                }
            } catch (e: Exception) { JLog.w(TAG, "held picture: can't read it: $e"); null }
            if (bmp != null) runOnUiThread {
                if (!destroyed && !started && held == null) held = HeldPicture(this, bmp, trim).takeIf { it.show() }
            }
        }
    }

    /**
     * Hardcore wanted and the launcher's Continue (or carrying on after a crash) asked for the auto or recovery state:
     * the state isn't loaded and the game starts fresh in Hardcore (approved 2026-11-04). A state the user picked
     * ([EXTRA_CASUAL_STATE], "Play from here in Casual") loads and the play is Casual.
     */
    private val hardcoreFresh: Boolean by lazy {
        val which = launcherString(EXTRA_LOAD_STATE)
        (which == STATE_AUTO || which == STATE_RECOVERY) && !launcherBool(EXTRA_CASUAL_STATE) &&
            com.joeysretro.joey64.achievements.RaHardcore.wanted(this) &&
            android.system.Os.getenv("N64_NETPLAY") == null && android.system.Os.getenv("N64_NETPLAY_SERVE") == null
    }

    /** The state this start loads: the launcher's, unless Hardcore starts the game fresh ([hardcoreFresh]). */
    private fun startLoad(): String? = if (hardcoreFresh || roomPlay) null else launcherString(EXTRA_LOAD_STATE)

    /** Hardcore's pop-ups for this start (cheats left off, Continue starting fresh), shown once the game is up. */
    private val hardcoreStartPops = java.util.concurrent.CopyOnWriteArrayList<InGamePopup>()

    private fun hardcoreOffPop() = InGamePopup("hardcore", InGamePopup.Kind.HardcoreOff, "Hardcore off", "Casual for the rest of this play",
        "Turning it back on restarts the game")

    /** On [gameIo]: everything before loadGame that reads or writes files. Null: no ROM. */
    private fun prepareGame(): Prepared? {
        // RetroAchievements Hardcore for this play, before the widescreen / 60 fps codes and cheats are picked: a start
        // from a state (Continue, a slot, carrying on after a crash) or in a netplay room plays in Casual.
        // Continue and carrying on after a crash start fresh in Hardcore instead (approved 2026-11-04 and part B screen 5).
        val netplay = android.system.Os.getenv("N64_NETPLAY") != null || android.system.Os.getenv("N64_NETPLAY_SERVE") != null
        com.joeysretro.joey64.achievements.RaHardcore.startPlay(this, casualState = startLoad() != null, netplay = netplay,
            asked = launcherBool(EXTRA_HARDCORE_PLAY))
        if (hardcoreFresh) {
            JLog.i(TAG, "Hardcore: ${launcherString(EXTRA_LOAD_STATE)} not loaded, the game starts fresh")
            hardcoreStartPops += InGamePopup("hcfresh", InGamePopup.Kind.Hardcore, "Hardcore", "Continue starts fresh", "In-game saves still load")
        }
        if (launcherBool(EXTRA_WAS_HARDCORE)) hardcoreStartPops += hardcoreOffPop()
        var (rom, saveName) = resolveRom() ?: run { JLog.e(TAG, "No ROM. Push one to ${getExternalFilesDir(null)}/test.z64"); return null }
        val disk = RomFiles.isDiskName(rom)
        // A 64DD disk: the IPL BIOS comes from the data folder's bios/ (the core reads its copy in files/system).
        if (disk) com.joeysretro.joey64.launcher.storage.BiosSync.refresh(this)
        val ddCart = if (disk) resolveDdCart() else null
        // A cartridge was given and didn't unpack: the disk alone would start with no word of it (code audit 2).
        if (disk && ddCart == null && (launcherString(EXTRA_DD_CART) != null || devString("ddcart") != null)) {
            JLog.e(TAG, "64DD: the cartridge didn't unpack, not starting the disk alone")
            cartFailed = true
            return null
        }
        // Settings › Saves: a copy of the save RAM before the core can write it (Back up saves).
        val opts = com.joeysretro.joey64.saves.SavesStore(this).read()
        // A picked data folder: its copy of the save RAM comes into files/saves, where the core reads it.
        com.joeysretro.joey64.launcher.storage.SrmSync.pull(this, saveName)
        com.joeysretro.joey64.saves.SaveBackups.maybeBackUp(com.joeysretro.joey64.launcher.storage.DataFolders.current(this).space,
            saveName, opts.backups, opts.backupsKeep)
        // A disk whose dump doesn't run as dumped gets its per-game bytes (DiskFixes): the unpacked copy and the core's
        // disk save, after SrmSync brought the folder's save in. The disk's MD5 stays the dump's.
        // Only ever a copy in the cache: a disk read straight from a file (dev `--es rom` from files/roms) is copied there
        // first, so the user's own file is never written.
        var diskMd5: String? = null
        if (disk) try {
            val f = File(rom)
            DiskFixes.fixFor(f, knownMd5())?.let { (m, fix) ->
                diskMd5 = m
                val work = if (f.canonicalPath.startsWith(cacheDir.canonicalPath + File.separator)) f
                    else File(File(cacheDir, "dd").apply { mkdirs(); listFiles()?.forEach { it.delete() } }, f.name).also { f.copyTo(it, true); rom = it.absolutePath }
                JLog.i(TAG, DiskFixes.apply(fix, work, File(File(filesDir, "saves"), "${f.name}.disk_save")))
            }
        } catch (e: Exception) { JLog.e(TAG, "64DD: disk fix failed: $e") }
        // The ROM's MD5 once, here off the main thread, for Auto pak, Controls, reports and the next restart (code audit 3, M18).
        val md5 = knownMd5() ?: diskMd5 ?: romMd5(rom)
        // The cheats left on, here too: a picked data folder's file is a provider query and read (code audit 3, M19).
        val cheats = if (saveName.isEmpty()) emptyList() else try { com.joeysretro.joey64.cheats.GameCheats(this, saveName).read().codeLines() }
            catch (e: Exception) { JLog.e(TAG, "cheats: can't read this game's: $e"); emptyList() }
        return Prepared(rom, saveName, ddCart, opts, gameExtras(if (disk) ddCart ?: "" else rom), if (disk) null else transferPak(saveName), md5, cheats)
    }

    /**
     * Transfer Pak (approved paks mockup 2026-11-08, scratchpad build_paks2.py): each player whose pak is the Transfer
     * Pak, with a Game Boy game picked, plays their own Game Boy game (copied, unzipped, to cache/tpak/p<n>/) and save
     * (Joey64's own copy, saves/<save name>.gb.sav for player 1, <save name>.p<n>.gb.sav for 2-4, synced with the data
     * folder like the save RAM; GameBoyFiles.saveCopy). Four pairs by port, ("", "") for none (README work order (44),
     * core tpak-ports). Null: no player has one.
     */
    private fun transferPak(saveName: String): List<Pair<String, String>>? {
        if (saveName.isEmpty()) return null
        val set = try { controlsStore.forGame(saveName) } catch (e: Exception) { return null }
        tpakAtStart = set.players.map { com.joeysretro.joey64.controls.PakSummary.transferKey(it) }
        val paks = set.players.mapIndexed { i, p ->
            val game = p.gbGame
            if (p.device == DeviceChoice.None || p.pak != Pak.Transfer || game == null) return@mapIndexed "" to ""
            val rom = com.joeysretro.joey64.controls.GameBoyFiles.copyGame(this, game.uri, game.name, File(cacheDir, "tpak/p${i + 1}").apply { mkdirs() })
                ?: return@mapIndexed "" to ""
            val ram = com.joeysretro.joey64.controls.GameBoyFiles.saveCopy(this, saveName, p.gbSave?.uri, i + 1) ?: run {
                // Not with another save in its place (code audit 3, M8): this player's Transfer Pak is empty this play, and says so.
                val what = p.gbSave?.name ?: "The Game Boy save"
                runOnUiThread { pop("tpak", InGamePopup.Kind.Problem, "Transfer Pak", "$what didn't load", "Player ${i + 1}'s Transfer Pak is empty this time") }
                return@mapIndexed "" to ""
            }
            JLog.i(TAG, "Transfer Pak, player ${i + 1}: ${com.joeysretro.joey64.controls.GameBoyFiles.describe(game.name, p.gbSave?.name)}, playing ${ram.name}")
            rom.absolutePath to ram.absolutePath
        }
        return paks.takeIf { l -> l.any { it.first.isNotEmpty() } }
    }

    /** On the main thread, with a surface: the rest of the start. */
    private fun launchGame(p: Prepared) {
        prepared = null
        // Back (or Leave the room) while the game was getting ready: nothing loads, and Auto's GPU trial isn't started
        // for a start that never ran (code audit 3, p1 L7).
        if (isFinishing) { JLog.i(TAG, "closed while the game was getting ready: not loaded"); reportGameState(loading = false); return }
        // The state's picture first, on screen before loadGame holds the main thread.
        held?.let { h -> if (!h.ready) { h.whenDrawn { if (destroyed) return@whenDrawn; if (surfaceUp) launchGame(p) else prepared = p }; return } }
        val rom = p.rom; val saveName = p.saveName; val ddCart = p.ddCart
        gameMd5 = p.md5
        startRom = rom; startSave = saveName
        NativeBridge.setDdCart(ddCart ?: "")
        NativeBridge.setTransferPak(Array(4) { p.tpak?.get(it)?.first ?: "" }, Array(4) { p.tpak?.get(it)?.second ?: "" })
        applyControls(rom, saveName)
        extrasOn = p.extras // before applyCheats, which hands the core their codes
        applyCheats(if (RomFiles.isDiskName(rom)) ddCart ?: "" else rom, saveName, p.cheats)
        applyAudio(saveName)
        savesOpts = p.savesOpts
        // Settings › Emulation: Overclock, CPU core, Expansion Pak (never Off for a game that needs it). --es opts wins.
        val code = romGameCode(if (RomFiles.isDiskName(rom)) ddCart ?: "" else rom)
        gameCode = com.joeysretro.joey64.video.GameCrop.keyOf(code, gameMd5) // header code, or the MD5 key of a game with none
        com.joeysretro.joey64.video.GameCrop.lines(gameCode, video)?.let { JLog.i(TAG, "crop edges: this game's default, $it lines"); applyVideoDisplay() }
        // Hardcore plays run at Normal speed whatever Overclock says (approved mockup hardcore-overclock A and A,
        // 2026-11-17): its own pop-up, after the cheats one.
        val hc = com.joeysretro.joey64.achievements.RaHardcore.on()
        val ocDrops = com.joeysretro.joey64.emulation.EmuApply.hardcoreDrops(emuAtStart)
        overclockedPlay = ocDrops && !hc
        overclockSetAside = ocDrops && hc
        if (overclockSetAside) {
            JLog.i(TAG, "Hardcore: Overclock ${emuAtStart[com.joeysretro.joey64.emulation.EmuKey.Overclock]} not used, Normal speed for this play")
            hardcoreStartPops += InGamePopup("hcoverclock", InGamePopup.Kind.Hardcore, "Hardcore", "Overclock is off for this play",
                "Normal speed · it stays on for Casual plays")
        }
        val eApply = com.joeysretro.joey64.emulation.EmuApply.of(emuAtStart, com.joeysretro.joey64.emulation.ExpansionPak.needed(code), hc)
        val devOpts = devString("opts").orEmpty()
        for ((k, v) in eApply.coreOptions) if (!devOpts.contains("$k=")) NativeBridge.setOption(k, v)
        if (eApply.coreOptions.isNotEmpty()) JLog.i(TAG, "emulation ($code): ${eApply.coreOptions}")
        // A 60 fps code makes the game's CPU work harder: the CPU runs at counter factor 1 (the smoother timing's
        // CountPerOp=1), unless Overclock is set or --es opts sets it.
        if (p.extras.fps && !eApply.overclockSet && !devOpts.contains("mupen64plus-CountPerOp=")) NativeBridge.setOption("mupen64plus-CountPerOp", "1")
        if (p.extras.shape != null) applyVideoDisplay()
        JLog.i(TAG, "widescreen / 60 fps: ${p.extras.log}")
        // RetroAchievements: signs in with the kept account first, so the game is identified as it loads.
        com.joeysretro.joey64.achievements.RaBridge.init(this)
        com.joeysretro.joey64.achievements.RaBridge.gameStarting()
        // The recompiler's block list, one file per game (Joey 2026-11-09: "Yes, build it"): the blocks the game compiled
        // last time are compiled early on the compile thread as their code loads, so a scene's first compile doesn't
        // land in a frame (Flip, Mario Kart 64's race start: a 42-46 ms hitch without the list, none with it). The core
        // uses it only with the compile thread (Mali, GpuRules.settings). --es env N64_DRC_LIST=<file> or =0 (off) wins.
        if (devString("env")?.contains("N64_DRC_LIST=") != true) {
            val list = File(File(filesDir, "blocklists").apply { mkdirs() }, "$saveName.txt")
            if (!list.exists()) shippedBlockList(rom, list)
            android.system.Os.setenv("N64_DRC_LIST", list.path, true)
        }
        // The router's address for a hosted room's NAT-PMP (when no UPnP router answers): from the network's default
        // route, since Android's main route table that libnatpmp reads has none. --es env N64_NETPLAY_GW=<ip> wins.
        if (devString("env")?.contains("N64_NETPLAY_GW=") != true) com.joeysretro.joey64.netplay.RouterAddress.set(this)
        // Automatic rollback (Joey 2026-11-24): the core tells the room how many inputs ahead this device can guess
        // (netplay/RollbackCap.kt); the room picks rollback or its shared delay. --es env N64_NETPLAY_RB_CAP wins (0 =
        // never), N64_NETPLAY_ROLLBACK=<n> still forces it on this device without asking the room (dev).
        if (roomPlay && devString("env")?.contains("N64_NETPLAY_RB_CAP=") != true) {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem shr 20
            val mhz = com.joeysretro.joey64.netplay.RollbackCap.maxCpuMhz()
            val cap = com.joeysretro.joey64.netplay.RollbackCap.of(mem, mhz, gpuFamilyKey)
            android.system.Os.setenv("N64_NETPLAY_RB_CAP", cap.toString(), true)
            JLog.i(TAG, "netplay: this device can guess $cap inputs ahead for rollback ($mem MB, ${mhz} MHz, ${gpuFamilyKey ?: "GPU unknown"})")
        }
        // State-request ring (Joey 2026-11-12; host state_ring.h): for a game on the wanted list the launcher sets
        // EXTRA_STATE_RING, and the host keeps a few recent states and meta.json in files/states/ring/<md5>/ for the
        // launcher to read after quit. The env lives on in the process, so it is cleared for every other start.
        // --es env N64_STATE_RING=<dir> wins (dev).
        if (devString("env")?.contains("N64_STATE_RING=") != true) {
            val md5 = gameMd5 ?: knownMd5()
            if (launcherBool(EXTRA_STATE_RING) && md5 != null && !roomPlay) android.system.Os.setenv("N64_STATE_RING", stateRingDir(this, md5).path, true)
            else android.system.Os.unsetenv("N64_STATE_RING")
        }
        // A texture pack converting in the background waits while a game is played (approved mockup texpacks4).
        com.joeysretro.joey64.video.TexConvert.playing(this, launcherString(EXTRA_TITLE) ?: saveName.ifEmpty { "a game" })
        // Texture packs (approved mockup 2026-11-15; video/TexturePacks.kt): parallel-RDP reads the game's pack from the folder
        // set here. The env lives on in the process, so it's cleared for every other start; --es env N64_RDP_TEXPACK wins.
        if (devString("env")?.contains("N64_RDP_TEXPACK=") != true) {
            android.system.Os.unsetenv("N64_RDP_TEXPACK")
            if (parallel) setUpTexPack(if (RomFiles.isDiskName(rom)) ddCart ?: "" else rom)
        }
        trial?.begin()
        if (speedTry != null) speed?.begin()
        NativeBridge.workTotals(workBase)
        if (NativeBridge.loadGame(rom, saveName)) {
            NativeBridge.start(); started = true; owner = this; gameLoaded = true
            if (trial != null) fpsHandler.postDelayed(trialWatch, TRIAL_POLL_MS)
            if (speed != null && android.system.Os.getenv("N64_NETPLAY") == null && android.system.Os.getenv("N64_NETPLAY_SERVE") == null) {
                speedFlushAt = android.os.SystemClock.elapsedRealtime(); fpsHandler.postDelayed(speedSample, SPEED_POLL_MS)
            } else { if (speedTry != null) speed?.cancel(); speed = null } // netplay: not measured, and the try's start doesn't count
            startStates(saveName)
            startSession(rom, saveName)
            fpsHandler.postDelayed(shutdownWatch, SHUTDOWN_POLL_MS)
            fpsHandler.postDelayed(saveRamWatch, SAVE_RAM_POLL_MS)
            if (android.system.Os.getenv("N64_NETPLAY") != null || android.system.Os.getenv("N64_NETPLAY_SERVE") != null)
                fpsHandler.postDelayed(netWatch, NET_POLL_MS)
            getSystemService(android.hardware.input.InputManager::class.java)?.registerInputDeviceListener(padListener, fpsHandler)
            fpsHandler.post { connectAtStart() }
            reportGameState(loading = false)
        } else {
            cancelTrial()
            reportGameState(loading = false)
            notStarted(cart = false, why = com.joeysretro.joey64.launcher.GameStartFailed.Why.Refused)
        }
    }

    /** The game couldn't start (no ROM, a cartridge that didn't unpack, the core refused it): back to what started it, not a black screen. */
    private fun notStarted(cart: Boolean, why: com.joeysretro.joey64.launcher.GameStartFailed.Why) {
        JLog.e(TAG, "the game didn't start (${why.name}), closing the game screen")
        held?.hide()
        if (cart) intent?.let { com.joeysretro.joey64.launcher.GameStartFailed.diskNeedsCart(it) }
        // Started from the library (it passes the MD5): it says "<game> won't start" (approved problem screens 2026-11-08).
        else intent?.takeIf { launcherString(EXTRA_MD5) != null }?.let { com.joeysretro.joey64.launcher.GameStartFailed.romFailed(it, why) }
        finish()
    }

    /**
     * The core shut down (a 64DD disk whose region has no IPL BIOS: code audit 2, finding 2): no frames come, so the game
     * screen closes instead of staying black, and the library asks for the BIOS again ([GameStartFailed]).
     */
    private val shutdownWatch = object : Runnable {
        override fun run() {
            if (destroyed || isFinishing) return
            val why = NativeBridge.coreShutDown()
            if (why == 0) { fpsHandler.postDelayed(this, SHUTDOWN_POLL_MS); return }
            if (why == 7) {
                // The GPU stopped answering mid-game (code audit 3, M12: the game thread waited for it for good, and the
                // app then hung on quit). The save RAM is written; a pop-up says why, then the screen closes. The recovery
                // state stays, so the library offers to carry on from it.
                JLog.e(TAG, "the GPU stopped answering: closing the game screen")
                keepRecovery = true
                pop("gpu", InGamePopup.Kind.Problem, "Video", "The GPU stopped answering", "Going back to the library")
                fpsHandler.postDelayed({ if (!destroyed && !isFinishing) finish() }, GPU_LOST_CLOSE_MS)
                return
            }
            val noIpl = why == 2
            val why2 = when (why) { 2 -> " (no 64DD IPL BIOS for the disk's region)"; 3 -> " (netplay: lost the connection to the room)"
                4 -> " (netplay: can't reach the room)"; 5 -> " (netplay: the room turned this player down)"
                6 -> " (netplay: this ROM isn't the host's)"; 8 -> " (netplay: the host closed the room)"; else -> "" }
            JLog.e(TAG, "the core shut down: the game stopped$why2, closing the game screen")
            // Not the GPU's doing: the start doesn't count against Auto's trial (a no-op once a frame was drawn).
            cancelTrial()
            // Only a missing IPL is asked for; a damaged disk or disk save just goes back.
            if (noIpl) intent?.let { com.joeysretro.joey64.launcher.GameStartFailed.diskNeedsBios(it) }
            // Netplay: the room was lost, couldn't be reached, turned this player down, or has another copy (approved netplay screens).
            // A quit the player asked for stops the room's wait (joey64_netplay_abort), and the core then says the
            // connection was lost or the room can't be reached: no problem screen for that.
            else if (why in 3..6 || why == 8) { com.joeysretro.joey64.reports.NetStats.endOf(why)?.let { if (!quitting) netStats.end = it }; if (!quitting) intent?.takeIf { launcherString(EXTRA_MD5) != null }?.let { com.joeysretro.joey64.launcher.GameStartFailed.netEnded(it, why) } }
            else intent?.takeIf { launcherString(EXTRA_MD5) != null }?.let { com.joeysretro.joey64.launcher.GameStartFailed.romFailed(it, com.joeysretro.joey64.launcher.GameStartFailed.Why.Stopped) }
            finish()
        }
    }

    // ---- save states (plan, Phase 3) ---------------------------------------------------------------
    // Saving and loading block for a while (the core's data is ~10-20 MB, packed like RetroArch's), so
    // they run on their own thread, one at a time, in order.
    private var states: GameStates? = null
    private val stateThread by lazy { android.os.HandlerThread("joey64-states").apply { start() } }
    private val stateHandler by lazy { Handler(stateThread.looper) }
    private var quitting = false
    /** Restart now's state is the recovery state the next start loads: closing mustn't remove it. */
    @Volatile private var keepRecovery = false
    /**
     * Continue's state didn't load, so the game started from power-on: quitting then leaves the auto
     * slot as it was, or the place Continue would have gone back to is lost. (Only touched on [stateThread].)
     */
    private var startLoadFailed = false

    /** Settings › Saves, read when the game starts (approved Saves mockup 2026-11-07). */
    private var savesOpts = com.joeysretro.joey64.saves.SavesOptions()
    private val recoveryEveryMs get() = savesOpts.recoveryMinutes * 60_000L

    /** The game is up: load the state the launcher asked for (Continue), and start the recovery state's timer. */
    private fun startStates(saveName: String) {
        if (saveName.isEmpty()) { JLog.i(TAG, "states: off for a ROM without a name (the dev path)"); return }
        val st = GameStates(this, saveName)
        states = st
        val givenTitle = launcherString(EXTRA_TITLE)
        overlay = GameOverlay(this, pauseActions).also {
            it.title = givenTitle ?: saveName
            it.saveSlotName = slotName(currentSlot())
            it.onScreen = onScreen
            for (p in earlyPops) it.show(p)
            earlyPops.clear()
        }
        trialCardAtStart()
        // Hardcore's pop-ups for this start (cheats left off, Continue starting fresh, Hardcore off after a restart).
        for (p in hardcoreStartPops) overlay?.show(p)
        hardcoreStartPops.clear()
        // "Play will always ... Change it in Settings › Saves." (approved E4 screens 5-6), once, as the game opens.
        launcherString(EXTRA_PLAY_NOTE)?.let { pop("playnote", InGamePopup.Kind.PlayNote, "Continue on Play", it, "Change it in Settings › Saves.") }
        noSticksNote()
        // No touchscreen (a TV): no touch controls (Android TV mockup 2026-11-09, screen 7).
        if (com.joeysretro.joey64.launcher.layout.Tv.hasTouch(this)) touch = com.joeysretro.joey64.touch.TouchLayer(this, touchHost)
        applyTouch(fresh = true)
        // RetroAchievements no longer takes the kept sign-in (the password changed on the website): say so
        // once the game is up (approved sign-in mockup g). Signing in again is in Settings › RetroAchievements.
        uiScope.launch {
            com.joeysretro.joey64.achievements.RaBridge.expired.collect { gone ->
                if (gone) pop("ra", InGamePopup.Kind.Account, "RetroAchievements · Sign-in expired", "Sign in again to save unlocks", "")
            }
        }
        // Achievement pop-ups (approved toast.html / toast2.html), the challenge badges, Screenshot on unlock.
        raPopups = com.joeysretro.joey64.achievements.RaPopups(this,
            show = { p -> runOnUiThread { if (!destroyed) overlay?.show(p) } },
            hide = { key -> runOnUiThread { if (!destroyed) overlay?.hide(key) } },
            badges = { ids, files, timers -> runOnUiThread { if (!destroyed) overlay?.setChallengeBadges(ids, files, timers) } },
            screenshot = { stateHandler.post { if (st.screenshot() == null) JLog.w(TAG, "screenshot on unlock: not saved") } },
        ).also { com.joeysretro.joey64.achievements.RaBridge.listeners += it }
        // Offline unlocks: RAOfflineProxy picked and not running, so no achievements this game (in place of the summary).
        if (com.joeysretro.joey64.achievements.RaBridge.offThisGame) uiScope.launch {
            pop("ra", InGamePopup.Kind.AchievementsOff, "", "Achievements off", com.joeysretro.joey64.achievements.OfflineRoute.OFF_LINE)
        }
        // The progress cache (decided 2026-11-04): the set and its progress, for the Achievements pages.
        raCache = com.joeysretro.joey64.achievements.RaCache(this).also { com.joeysretro.joey64.achievements.RaBridge.listeners += it }
        // The pause menu's title: the game's name in the library, else the ROM's (a frontend's game).
        stateHandler.post {
            val title = givenTitle ?: try {
                com.joeysretro.joey64.launcher.data.LibraryDb.get(this).gameWithFile(saveName, GameStates::saveNameOf)?.title
            } catch (e: Exception) { JLog.e(TAG, "pause menu: can't read the library: $e"); null }
            if (title != null && givenTitle == null) runOnUiThread { overlay?.title = title }
            recent = recentEntry(saveName, title)
            recent?.let { RecentlyPlayed.record(this, it) }
        }
        when (val which = startLoad()) {
            null -> {}
            STATE_AUTO -> stateHandler.post { startLoadFailed = !st.load(StateSlots.AUTO) }
            STATE_RECOVERY -> stateHandler.post { startLoadFailed = !st.loadRecovery() }
            // The Saves page's Play from here: "slot:<n>", StateSlots' number.
            else -> which.removePrefix(STATE_SLOT).toIntOrNull()?.takeIf { which.startsWith(STATE_SLOT) }
                ?.let { n -> stateHandler.post { startLoadFailed = !st.load(n) } }
                ?: JLog.e(TAG, "states: unknown state to load: $which")
        }
        // The held picture goes once the loaded state's own frames are drawn (after the load on the same thread), or
        // at once if the load failed.
        held?.let { h -> stateHandler.post { val ok = !startLoadFailed; runOnUiThread { if (ok) h.hideAfterFrames() else h.hide() } } }
        // A picked data folder gets the save RAM the core wrote, every 30 s when it changed (and on exit).
        stateHandler.postDelayed(srmTick, SRM_PUSH_MS)
        // Settings › Saves › Recovery state Off: none is written, so none is offered after a crash.
        if (roomPlay) {}
        else if (recoveryEveryMs > 0) stateHandler.postDelayed(recoveryTick, recoveryEveryMs)
        else stateHandler.post { st.clearRecovery() }
        // Dev (--es env N64_POPUP_TEST=1): a pop-up kept on screen the whole time, renewed every 4 s,
        // to measure what one costs (plan, Phase 3: the measure).
        if (android.system.Os.getenv("N64_POPUP_TEST") == "1") fpsHandler.post(popupTest)
    }

    private val popupTest = object : Runnable {
        override fun run() {
            if (destroyed) return
            pop("state", InGamePopup.Kind.Saved, "Save state", "Slot 3 saved", overlay?.title ?: "")
            fpsHandler.postDelayed(this, 4_000)
        }
    }

    /** This game's line in recently played (JoeyOS reads it); null for a dev start. Only touched on [stateThread]. */
    private var recent: RecentlyPlayed.Entry? = null

    private fun recentEntry(saveName: String, title: String?): RecentlyPlayed.Entry? {
        val i = intent ?: return null
        val uri = launcherString(EXTRA_SOURCE_URI) ?: i.data?.toString() ?: return null
        val file = launcherString(EXTRA_SOURCE_NAME) ?: i.data?.let { displayName(it) } ?: saveName
        val path = try { RecentlyPlayed.pathOf(Uri.parse(uri)) } catch (e: Exception) { "" }
        return RecentlyPlayed.Entry(title ?: saveName, file, uri, path, saveName, System.currentTimeMillis())
    }

    private val srmTick = object : Runnable {
        override fun run() {
            val st = states ?: return
            com.joeysretro.joey64.launcher.storage.SrmSync.push(this@MainActivity, st.saveName)
            stateHandler.postDelayed(this, SRM_PUSH_MS)
        }
    }

    // ---- reports (approved reports mockup, version A, Joey 2026-11-08) ----------------------------------
    // Send reports on: this play session (time at normal speed, frames run and drawn) goes to files/reports,
    // and the library sends it with the next report. Off: nothing is recorded. Dev starts (--es env/opts,
    // renderer or driver overrides) are tests, not play: not recorded either.
    private var session: com.joeysretro.joey64.reports.SessionTracker? = null
    /** How this play ends for its report: "quit", or "next" when Joey64 restarts the process for another game or settings. */
    private var sessionEnd = "quit"
    /** The most cheat codes on at once this session (reports: a game that misbehaves with cheats on). */
    private var cheatsMost = 0
    private val sessionStats = DoubleArray(16)

    private fun startSession(rom: String, saveName: String) {
        // The device's own speed and fan modes (performance mode and the like), for the log and the report.
        val modes = com.joeysretro.joey64.reports.DeviceModes.read(this)
        JLog.i(TAG, "device modes: " + com.joeysretro.joey64.reports.DeviceModes.line(modes))
        if (!com.joeysretro.joey64.reports.ReportPrefs.recording(this)) return
        if (listOf("env", "opts", "renderer", "driver").any { intent?.hasExtra(it) == true }) { JLog.i(TAG, "reports: a dev start, not recorded"); return }
        val md5 = gameMd5 ?: return
        // Settings that differ from the defaults: Video's for this game (all of them count for speed), Audio's.
        val v = com.joeysretro.joey64.video.VideoStore(this).forGame(saveName.ifEmpty { null })
        val a = com.joeysretro.joey64.audio.AudioStore(this).forGame(saveName.ifEmpty { null })
        val settings = org.json.JSONObject()
        for (k in com.joeysretro.joey64.video.VideoKey.entries) if (v[k] != k.default) settings.put("video." + k.key, v[k])
        for (k in com.joeysretro.joey64.audio.AudioKey.entries) if (a[k] != k.default) settings.put("audio." + k.key, a[k])
        for (k in com.joeysretro.joey64.emulation.EmuKey.entries) if (emuAtStart[k] != k.default) settings.put("emulation." + k.key, emuAtStart[k])
        // The widescreen / 60 fps versions actually on (the rows above say what was asked for).
        extrasOn.widescreenName?.let { settings.put("on.widescreen", it) }
        extrasOn.fpsName?.let { settings.put("on.fps", it) }
        for ((k, v) in modes) settings.put("device.$k", v)
        val scale = if (parallel) (playUpscale ?: "1x").removeSuffix("x").toIntOrNull() ?: 1
            else statsRenderer.substringAfter("GLideN64 ").removeSuffix("x").trim().toIntOrNull() ?: 1
        val base = org.json.JSONObject().apply {
            put("md5", md5.lowercase())
            put("renderer", if (parallel) "parallel" else "gliden64")
            put("scale", scale)
            put("settings", settings)
            put("gpu", gpuName.replace("(TM) ", ""))
            put("driver", statsDriver)
            put("driverId", driverIdNow)
            @Suppress("DEPRECATION") val shown = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
            shown?.refreshRate?.takeIf { it > 0 }?.let { put("refreshHz", Math.round(it)) }
            put("fromState", startLoad() != null)
            put("cheats", cheatsMost)
            gpuWhy?.let { w ->
                put("driverRule", w.driverRule)
                put("rendererRule", w.rendererRule)
                w.trialLevel?.let { put("trialLevel", it) }
                put("pickedBy", w.pickedBy)
            }
        }
        // The library's report with no session yet names the GPU and driver from here.
        getSharedPreferences("device", MODE_PRIVATE).edit().putString("report_gpu", gpuName.replace("(TM) ", "")).putString("report_driver", statsDriver).apply()
        session = com.joeysretro.joey64.reports.SessionTracker(File(com.joeysretro.joey64.reports.ReportFiles.open(this), "${android.os.Process.myPid()}.json"),
            com.joeysretro.joey64.reports.ReportFiles.pending(this), base)
        sessionMark()
        fpsHandler.postDelayed(sessionTick, SESSION_TICK_MS)
    }

    /** Playing at normal speed right now: not paused, no menu, no fast-forward. */
    private val sessionCounting get() = resumed && !menuOpen && !hotkeyPaused && !fastForward && !quitting

    /** Closes the stretch since the last mark, counted if play was counting through it. */
    private fun sessionMark() {
        val s = session ?: return
        s.tick(sessionCounting, NativeBridge.framesRun(), NativeBridge.framesDrawn(), android.os.SystemClock.elapsedRealtime())
        s.native(sessionStats, NativeBridge.sessionStats(sessionStats))
    }

    private val sessionTick = object : Runnable {
        override fun run() {
            val s = session ?: return
            sessionMark()
            stateHandler.post { s.writeOpen() }
            fpsHandler.postDelayed(this, SESSION_TICK_MS)
        }
    }

    /** Every few minutes while playing (not while paused or quitting), the recovery state. */
    private val recoveryTick = object : Runnable {
        override fun run() {
            val st = states ?: return
            if (resumed && !quitting && !menuOpen && !hotkeyPaused) st.saveRecovery()
            stateHandler.postDelayed(this, recoveryEveryMs)
        }
    }

    // ---- pause menu (plan, Phase 3) ------------------------------------------------------------------
    // Back pauses the game and opens the menu over it. The game stays paused until the menu closes,
    // and through a save, load or reset picked from it (they run on the state thread, then it resumes).
    private var overlay: GameOverlay? = null
    private var raPopups: com.joeysretro.joey64.achievements.RaPopups? = null
    private var raCache: com.joeysretro.joey64.achievements.RaCache? = null
    private var menuOpen = false
    private val slotPrefs by lazy { getSharedPreferences("states", Context.MODE_PRIVATE) }

    /** The slot Save state saves to: the last one saved to or loaded, per game; Slot 1 at first. */
    private fun currentSlot(): Int = states?.let { slotPrefs.getInt("slot/" + it.saveName, 1) }?.coerceIn(1, StateSlots.COUNT) ?: 1
    private fun setCurrentSlot(slot: Int) {
        val st = states ?: return
        if (slot !in 1..StateSlots.COUNT) return
        slotPrefs.edit().putInt("slot/" + st.saveName, slot).apply()
        overlay?.saveSlotName = slotName(slot)
    }
    private fun slotName(slot: Int) = StateSlots.label(slot)

    /**
     * Pauses and opens the menu; false when it didn't (no overlay, or Hardcore's pause limit held it: then [retry], or
     * the menu, runs once it's allowed, as approved 2026-11-04).
     */
    private fun openMenu(retry: (() -> Unit)? = null, force: Boolean = false): Boolean {
        val o = overlay ?: return false
        // [force]: Joey64's own pauses (a controller gone, no space, netplay) aren't the player's, so aren't held.
        if (!force && !menuOpen && pauseHeldBack { if (!quitting && !menuOpen) (retry ?: { openMenu() })() }) return false
        sessionMark(); menuOpen = true
        achievementsDirect = false
        NativeBridge.pause(true)
        // Held buttons and sticks are let go: the menu's window takes the pad from here.
        hotkeyHeld = false
        volumeKey(0, false)
        gameSharkButton(false)
        releaseAll()
        o.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Menu()
        touch?.setActive(false)
        return true
    }

    /**
     * Tap to open (plan, Phase 7): tapping an achievement pop-up or the challenge badges opens the
     * pause menu's Achievements at that achievement; tapping Sign-in expired opens the sign-in popup
     * over the paused game. The pop-up windows can't be touched (the game keeps the pad), so the
     * game's window checks where a tap lands. Other pop-ups do nothing.
     */
    private var tapDown: GameOverlay.Tap? = null
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val o = overlay
        if (o == null || menuOpen) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> tapDown = o.tapAt(ev.rawX, ev.rawY)?.takeIf { opensAchievements(it) }
            MotionEvent.ACTION_UP -> {
                val down = tapDown
                tapDown = null
                if (down != null && o.tapAt(ev.rawX, ev.rawY) == down) { openAchievementsAt(down); return true }
            }
            MotionEvent.ACTION_CANCEL -> tapDown = null
        }
        if (tapDown != null) return true
        if (touch?.touchedWhileHidden(ev) == true) return true
        return super.dispatchTouchEvent(ev)
    }

    private fun opensAchievements(t: GameOverlay.Tap) = when (t) {
        is GameOverlay.Tap.Badges, GameOverlay.Tap.Timer -> true
        is GameOverlay.Tap.Popup -> t.popup.kind in setOf(InGamePopup.Kind.Achievement, InGamePopup.Kind.Progress,
            InGamePopup.Kind.Leaderboard, InGamePopup.Kind.LeaderboardResult,
            InGamePopup.Kind.ChallengeFailed, InGamePopup.Kind.Game) || signsIn(t)
    }

    private fun signsIn(t: GameOverlay.Tap) = t is GameOverlay.Tap.Popup && t.popup.key == "ra" && t.popup.kind == InGamePopup.Kind.Account

    private fun openAchievementsAt(t: GameOverlay.Tap) {
        if (signsIn(t)) {
            overlay?.hide("ra")
            if (!openMenu(retry = { openAchievementsAt(t) })) return
            overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.SignIn
            return
        }
        // A leaderboard pop-up opens the Leaderboards tab on its board, the timer on the board running (approved 2026-11-04).
        val board = when {
            t is GameOverlay.Tap.Timer -> com.joeysretro.joey64.launcher.ingame.PauseScreen.RUNNING_BOARD
            t is GameOverlay.Tap.Popup && t.popup.kind in setOf(InGamePopup.Kind.Leaderboard, InGamePopup.Kind.LeaderboardResult) ->
                t.popup.key.substringAfter('-', "").toIntOrNull()
            else -> null
        }
        val id = when (t) {
            is GameOverlay.Tap.Badges -> t.newest
            is GameOverlay.Tap.Popup -> if (board != null) null else t.popup.key.substringAfter('-', "").toIntOrNull()
            GameOverlay.Tap.Timer -> null
        }
        if (!openMenu(retry = { openAchievementsAt(t) })) return
        overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Achievements(id, board = board)
    }

    /** The menu is done: the game carries on (unless the screen is in the background). */
    /** The cheats the Cheats page left on, to the core (between frames). */
    private fun sendCheats() {
        val st = states ?: return
        stateHandler.post {
            // The page's last change written first (code audit 3, M7), then read.
            com.joeysretro.joey64.cheats.GameCheats.awaitWrites()
            // The widescreen / 60 fps codes the game started with stay with the cheats.
            if (com.joeysretro.joey64.achievements.RaHardcore.on()) { JLog.i(TAG, "cheats: not changed in Hardcore"); return@post }
            val lines = com.joeysretro.joey64.cheats.GameCheats(this@MainActivity, st.saveName).read().codeLines() + extrasOn.codeLines
            NativeBridge.setCheats(lines.toTypedArray())
            JLog.i(TAG, "cheats: ${lines.size} on")
            if (lines.size > cheatsMost) { cheatsMost = lines.size; session?.put("cheats", cheatsMost) }
        }
    }

    // The GameShark cartridge's button (approved 2026-11-07): held with its hotkey (Not set by default since
    // 2026-11-13, was hotkey button + L3) for as long as both are, or for a second from the pause menu's Cheats.
    private var gameSharkDown = false
    private var gameSharkSince = 0L
    private val gameSharkUp = Runnable { gameSharkButton(false) }
    private fun gameSharkButton(down: Boolean) {
        if (down == gameSharkDown) return
        if (down && com.joeysretro.joey64.achievements.RaHardcore.on()) { popHardcore("GameShark button"); return }
        gameSharkDown = down
        if (down) gameSharkSince = android.os.SystemClock.uptimeMillis()
        NativeBridge.setGameShark(down)
    }
    /** The hotkey let go: a tap shorter than [GAMESHARK_MIN_MS] still holds the button that long, so the game sees it. */
    private fun gameSharkRelease() {
        val left = GAMESHARK_MIN_MS - (android.os.SystemClock.uptimeMillis() - gameSharkSince)
        fpsHandler.removeCallbacks(gameSharkUp)
        if (gameSharkDown && left > 0) fpsHandler.postDelayed(gameSharkUp, left) else gameSharkButton(false)
    }

    private fun closeMenu() {
        overlay?.screen = null
        sessionMark(); menuOpen = false
        achievementsDirect = false
        // Resume goes on after the Pause hotkey too (approved hotkeys mockup 2026-11-07).
        if (hotkeyPaused) { sessionMark(); hotkeyPaused = false; overlay?.hide("paused", waiting = true) }
        if (resumed && !quitting) NativeBridge.pause(false)
        touch?.setActive(touchActive())
        // The next controller card waiting (approved newpad3 mockup): never over another card, so after this one.
        if (missingAtStart.isNotEmpty() || newPadQueue.isNotEmpty()) fpsHandler.post { showNextCard() }
    }

    /** The picker's cells: each slot's date and its picture, shrunk for a 100 x 75 dp cell. */
    private fun slotViews(st: GameStates): List<com.joeysretro.joey64.launcher.ingame.SlotView> =
        st.slotFiles().map { f ->
            val bmp = f.picture?.let {
                android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 })
            }
            com.joeysretro.joey64.launcher.ingame.SlotView(f.slot, slotName(f.slot), f.savedAt,
                bmp?.asImageBitmap())
        }

    private val pauseActions = object : com.joeysretro.joey64.launcher.ingame.PauseActions {
        override fun resume() { if (!askRestart()) closeMenu() }
        override fun openGameSettings() {
            val st = states ?: return
            overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.GameSettings(st.saveName)
        }
        override fun pictureChanged() {
            // The in-game Picture panel: its pick replaces this play's Next shader / Shader on/off.
            val name = states?.saveName ?: return
            playShader = null; shaderOff = false
            applyPicture(com.joeysretro.joey64.video.VideoStore(this@MainActivity).forGame(name))
        }
        override fun closeGameSettings() {
            states?.let { applyGameSettings(it.saveName) }
            reloadControls()
            if (menuOpen) overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Menu(com.joeysretro.joey64.launcher.ingame.MenuRow.GameSettings)
        }
        override val gameSettingsLabel: String get() = states?.let {
            com.joeysretro.joey64.launcher.gamesettings.GameSettingsModel(this@MainActivity).summary(it.saveName).label
        } ?: ""
        override fun restartNow() = restartForSettings()
        override fun restartLater() = closeMenu()
        override fun openSave() = openPicker(save = true)
        override fun openLoad() = openPicker(save = false)
        override fun openControls() {
            val st = states ?: return
            overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Controls(st.saveName)
        }
        override fun openCheats() {
            val st = states ?: return
            val gsKeys = hotkeys.keys[HotkeyAction.GameShark]?.let {
                com.joeysretro.joey64.launcher.screens.hotkeyKey(hotkeys.button) + " + " + com.joeysretro.joey64.launcher.screens.hotkeyKey(it)
            }
            overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Cheats(
                com.joeysretro.joey64.launcher.screens.CheatsGame(overlay?.title ?: st.saveName, st.saveName, cheatKeys), gsKeys)
        }
        override fun closeCheats() {
            sendCheats()
            if (menuOpen) overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Menu(com.joeysretro.joey64.launcher.ingame.MenuRow.Cheats)
        }
        override fun openAchievements() {
            overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Achievements()
        }
        override fun closeSignIn() { closeMenu() }
        override fun closeAchievements() {
            // Opened by the Achievements hotkey: B goes back to the game, not the menu.
            if (achievementsDirect) { closeMenu(); return }
            if (menuOpen) overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Menu(com.joeysretro.joey64.launcher.ingame.MenuRow.Achievements)
        }
        override fun pressGameShark() {
            sendCheats()
            closeMenu()
            gameSharkButton(true)
            fpsHandler.removeCallbacks(gameSharkUp)
            fpsHandler.postDelayed(gameSharkUp, GAMESHARK_PRESS_MS)
        }
        override fun closeControls() {
            reloadControls()
            if (menuOpen) overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Menu(com.joeysretro.joey64.launcher.ingame.MenuRow.Controls)
        }
        override fun openPaks() {
            val st = states ?: return
            overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Paks(st.saveName)
        }
        override fun closePaks() {
            reloadControls()
            if (menuOpen) overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Menu(com.joeysretro.joey64.launcher.ingame.MenuRow.Paks)
        }
        // What each player's pak is now (reloadControls keeps [paks] resolved), null for a player with no controller.
        override val paksLabel: String get() = com.joeysretro.joey64.controls.PakSummary.label(
            controls.players.mapIndexed { i, p -> if (p.device == DeviceChoice.None) null else paks[i] })
        override fun backToMenu(from: com.joeysretro.joey64.launcher.ingame.MenuRow) {
            overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Menu(from)
        }
        // On: always (its locks, and the row to turn it off). Off: the row only where it can be turned on, signed in.
        override val hardcore: Boolean? get() = when {
            com.joeysretro.joey64.achievements.RaHardcore.on() -> true
            net.inRoom || !com.joeysretro.joey64.achievements.RaHardcore.available(this@MainActivity) ||
                com.joeysretro.joey64.achievements.RaBridge.account.value == null -> null
            else -> false
        }
        override fun hardcoreRow() {
            if (com.joeysretro.joey64.achievements.RaHardcore.on()) { closeMenu(); hardcoreMidGame(false) }
            else overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.HardcoreOn
        }
        override fun hardcoreOn(restart: Boolean) {
            if (!restart) { overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Menu(com.joeysretro.joey64.launcher.ingame.MenuRow.Hardcore); return }
            closeMenu(); hardcoreMidGame(true)
        }
        override fun reset() {
            overlay?.screen = null
            stateHandler.post {
                val ok = resetNow()
                runOnUiThread { closeMenu(); popReset(ok) }
            }
        }
        override fun quit() {
            overlay?.screen = null
            quitToLibrary()
        }
        // The trial card's B: the start doesn't count against the driver (it wasn't given its time).
        override fun gpuQuit() { cancelTrial(); trial = null; quit() }
        override fun gpuStart(send: Boolean) {
            GpuTrial(trialStore(), packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString(), trialGpuName, trialChain.size).told()
            if (send) {
                com.joeysretro.joey64.reports.ReportPrefs.setOnce(this@MainActivity, true)
                val rom = startRom; val save = startSave
                if (session == null && rom != null && save != null) startSession(rom, save)
                JLog.i(TAG, "GPU trial: this play is recorded and sent once (Send reports off)")
            }
            closeMenu()
        }
        override fun disconnectChoice(choice: com.joeysretro.joey64.launcher.ingame.DisconnectChoice) = disconnected(choice)
        override fun newPadPick(player: Int) = newPadAnswer(player)
        override fun newPadSettings(settings: com.joeysretro.joey64.controls.ConnectSettings) = controlsStore.saveConnecting(settings)
        override fun closeNoSpace() { closeMenu() }
        // A play started in a room stays one for the menu (also before the room has connected).
        override val inRoom: Boolean get() = net.inRoom || roomPlay
        override val netLine: String? get() = if (!net.inRoom) null
            else com.joeysretro.joey64.netplay.NetplayWords.waitingForYou(
                (netPlayers.map { it.player }.ifEmpty { net.playersIn(net.joined) }).filter { it != netMe })
        override fun netMenu() { openMenu() }
        override fun netCarryOn() { closeMenu() }
        override fun netLeave() { JLog.i(TAG, "netplay: leaving the room"); overlay?.screen = null; quitToLibrary() }
        override val quitLabel: String get() = hotkeys.quitTo.menuRow
        override fun pick(save: Boolean, slot: Int) {
            val st = states ?: return
            if (!save && com.joeysretro.joey64.achievements.RaHardcore.on()) { overlay?.screen = null; closeMenu(); popHardcore("Load state"); return }
            overlay?.screen = null
            stateHandler.post {
                val savedAt = if (save) null else st.savedAt(slot)
                // A save closes the menu as soon as the moment is taken; writing it goes on behind the game
                // (2026-11-14, Joey: "saving states takes too long": the menu stayed 1-4 s on the Thor).
                var closed = false
                val ok = if (save) st.save(slot) { closed = true; runOnUiThread { closeMenu() } } else st.load(slot, undoable = true)
                // A state loaded by hand: the game is somewhere real again, so quitting writes the auto slot.
                if (ok && !save) { startLoadFailed = false; loadedThisPlay = true }
                if (ok && save) savedThisPlay = true
                runOnUiThread {
                    if (ok && slot != StateSlots.AUTO) setCurrentSlot(slot)
                    if (!closed) closeMenu()
                    if (save) popSaved(slot, ok) else popLoaded(slot, ok, savedAt)
                }
            }
        }
        // The Save state / Load state window's undo row: closes the menu, then does what the hotkey does (its pop-up too).
        override fun undo(save: Boolean) {
            overlay?.screen = null
            closeMenu()
            if (save) hotkeyUndoSave() else hotkeyUndoLoad()
        }
    }

    private fun openPicker(save: Boolean) {
        val st = states ?: return
        stateHandler.post {
            val views = slotViews(st)
            // Save: the slot it saves to. Load: the newest state (the one most likely wanted), else that slot.
            val focus = if (save) currentSlot()
                else views.filter { it.savedAt != null }.maxByOrNull { it.savedAt!! }?.slot ?: currentSlot()
            // The window's undo row (approved undomenu2 mockup 2026-11-23): only with something to undo; no Undo load
            // state in Hardcore (its window is locked there anyway).
            val undo = if (save) st.undoSavePending()?.let { u -> slotName(u.slot) + " · " + (u.savedAt?.let { slotDate(it) } ?: "empty") }
                else if (com.joeysretro.joey64.achievements.RaHardcore.on()) null
                else st.undoLoadSlot()?.let { "Before " + slotName(it).lowercase() }
            runOnUiThread {
                if (menuOpen && overlay?.screen != null) overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Picker(save, views, focus, undo)
            }
        }
    }

    // ---- hotkeys (approved Hotkeys design, 2026-11-05; Controls > Hotkeys, Phase 4) -----------------
    // Hold the hotkey button (Select by default, the N64 has none; Joey, 2026-11-06: not Back) + R1 save
    // state, L1 load state (both the current slot), Right / Left change the slot, R2 fast-forward, Y screenshot,
    // Start quit to library; Reset not set. On its own: Back the pause menu. All of them are the user's
    // (`files/controls/hotkeys.json`). The game never sees the hotkey button or a hotkey.
    private var hotkeyHeld = false
    /** Read when the game starts (Controls are changed in the launcher, or in the pause menu with the game paused). */
    private var hotkeys = Hotkeys()
    private var hotkeyHatX = 0
    private var hotkeyHatY = 0

    private fun hotkey(action: HotkeyAction) {
        if (netBlocked(action)) return
        when (action) {
            HotkeyAction.Save -> hotkeySave()
            HotkeyAction.Load -> hotkeyLoad()
            HotkeyAction.NextSlot -> stepSlot(1)
            HotkeyAction.PrevSlot -> stepSlot(-1)
            HotkeyAction.Screenshot -> hotkeyScreenshot()
            HotkeyAction.Stats -> stepStats()
            HotkeyAction.Reset -> stateHandler.post { val ok = resetNow(); runOnUiThread { popReset(ok) } }
            HotkeyAction.Quit -> quitToLibrary()
            HotkeyAction.PauseMenu -> if (!menuOpen) openMenu()
            HotkeyAction.FastForward -> toggleFastForward()
            HotkeyAction.SlowMotion -> setSlowMotion(!slowMotion)
            HotkeyAction.Rewind -> {}  // both edges, in hotkeyEdge
            HotkeyAction.GameShark -> {}  // both edges, in dispatchKeyEvent
            HotkeyAction.VolumeUp -> stepVolume(5)
            HotkeyAction.VolumeDown -> stepVolume(-5)
            HotkeyAction.Mute -> toggleMute()
            HotkeyAction.UpscaleUp -> stepUpscale(1)
            HotkeyAction.UpscaleDown -> stepUpscale(-1)
            HotkeyAction.Pause -> togglePause()
            HotkeyAction.FrameAdvance -> frameAdvance()
            HotkeyAction.Achievements -> openAchievementsHotkey()
            HotkeyAction.UndoLoad -> hotkeyUndoLoad()
            HotkeyAction.UndoSave -> hotkeyUndoSave()
            HotkeyAction.Turbo -> toggleTurbo()
            HotkeyAction.DPadSwap -> swapDPad()
            HotkeyAction.NextShader -> nextShader()
            HotkeyAction.ShaderToggle -> toggleShader()
        }
    }

    /**
     * A hotkey's press or release (with the hotkey button, or on its own): fast-forward, GameShark and
     * the volume use both edges, the pause menu opens on release (its window would get the release
     * otherwise), the rest fire on the press. The game sees neither edge.
     */
    private fun hotkeyEdge(action: HotkeyAction, event: KeyEvent, down: Boolean) {
        val up = event.action == KeyEvent.ACTION_UP
        if (quitting) return
        if ((net.inRoom || roomPlay) && com.joeysretro.joey64.netplay.NetplayWords.blockedLine(action) != null) { if (down) netBlocked(action); return }
        when (action) {
            HotkeyAction.GameShark -> if (down) { fpsHandler.removeCallbacks(gameSharkUp); gameSharkButton(true) } else if (up) gameSharkRelease()
            HotkeyAction.FastForward -> if (down || up) fastForwardKey(down)
            HotkeyAction.SlowMotion -> if (down || up) slowMotionKey(down)
            HotkeyAction.Rewind -> if (down || up) rewindKey(down)
            HotkeyAction.VolumeUp, HotkeyAction.VolumeDown -> if (down || up) volumeKey(if (action == HotkeyAction.VolumeUp) 5 else -5, down)
            HotkeyAction.PauseMenu -> if (up && !event.isCanceled && !menuOpen) openMenu()
            else -> if (down) hotkey(action)
        }
    }

    // ---- Sound, Picture, Pause and Achievements hotkeys (approved hotkeys mockup, 2026-11-07) ----------
    /** The volume hotkey held: 5% now, then every [VOLUME_REPEAT_MS] after [VOLUME_FIRST_REPEAT_MS]. 0: none held. */
    private var volumeHeld = 0
    private val volumeRepeat = object : Runnable {
        override fun run() {
            if (volumeHeld == 0 || destroyed) return
            stepVolume(volumeHeld)
            fpsHandler.postDelayed(this, VOLUME_REPEAT_MS)
        }
    }
    private fun volumeKey(step: Int, down: Boolean) {
        fpsHandler.removeCallbacks(volumeRepeat)
        if (!down) { volumeHeld = 0; return }
        if (volumeHeld == step) return
        volumeHeld = step
        stepVolume(step)
        fpsHandler.postDelayed(volumeRepeat, VOLUME_FIRST_REPEAT_MS)
    }

    /** Settings › Audio's sound for this game, now (volume, mute, mono, fast-forward sound; latency stays as started). */
    private fun applyAudioNow(saveName: String): com.joeysretro.joey64.audio.AudioSettings {
        val a = com.joeysretro.joey64.audio.AudioStore(this).forGame(saveName)
        val start = audioAtStart ?: a
        NativeBridge.setAudio(a.volume, a.on(com.joeysretro.joey64.audio.AudioKey.Mute), a.on(com.joeysretro.joey64.audio.AudioKey.Mono), a.ffMode, start.latencyMode)
        return a
    }

    /** 5% louder or quieter, saved where Settings › Audio would: the game's own Volume if it has one, else All games'. */
    private fun stepVolume(step: Int) {
        val save = states?.saveName ?: return
        val store = com.joeysretro.joey64.audio.AudioStore(this)
        val own = store.game(save)
        val gameHas = own?.containsKey(com.joeysretro.joey64.audio.AudioKey.Volume) == true
        val now = store.forGame(save).volume
        // To the next 5% step (a volume set between steps lands on one).
        val next = (if (step > 0) (now / 5 + 1) * 5 else ((now + 4) / 5 - 1) * 5).coerceIn(0, 100)
        if (next != now) {
            if (gameHas) store.saveGame(save, own!! + (com.joeysretro.joey64.audio.AudioKey.Volume to next.toString()))
            else store.saveAll(store.all() + (com.joeysretro.joey64.audio.AudioKey.Volume to next.toString()))
            applyAudioNow(save)
        }
        JLog.i(TAG, "volume hotkey: $now -> $next (${if (gameHas) "this game" else "All games"})")
        pop("volume", InGamePopup.Kind.Volume, "Volume", "$next%", "Settings › Audio, " + if (gameHas) "this game" else "Global", bar = next / 100f)
    }

    /** Settings › Audio's Mute (All games), off or on. */
    private fun toggleMute() {
        val save = states?.saveName ?: return
        val store = com.joeysretro.joey64.audio.AudioStore(this)
        val muted = !store.forGame(save).on(com.joeysretro.joey64.audio.AudioKey.Mute)
        store.saveAll(store.all() + (com.joeysretro.joey64.audio.AudioKey.Mute to if (muted) "on" else "off"))
        applyAudioNow(save)
        JLog.i(TAG, "mute hotkey: ${if (muted) "sound off" else "sound on"}")
        pop("mute", InGamePopup.Kind.Mute, "Mute", if (muted) "Sound off" else "Sound on", if (muted) "Stays off until you press it again" else "")
    }

    /** parallel-RDP's Resolution in this play (Settings › Video's at the start, after the Duo's cap); null on GLideN64. */
    private var playUpscale: String? = null

    /** Resolution one step up or down for this play only: parallel-RDP reads it between frames; GLideN64 only at start. */
    private fun stepUpscale(step: Int) {
        val now = playUpscale
        if (!parallel || now == null) {
            pop("upscale", InGamePopup.Kind.Problem, "Resolution", "Can't change while playing", "GLideN64 reads it when the game starts: Settings › Video")
            return
        }
        val top = UPSCALES.indexOf(capUpscaling(UPSCALE_KEY, UPSCALES.last()))
        val i = UPSCALES.indexOf(now).coerceAtLeast(0) + step
        if (i > top) { pop("upscale", InGamePopup.Kind.Resolution, "Resolution", "Already the highest", ""); return }
        if (i < 0) { pop("upscale", InGamePopup.Kind.Resolution, "Resolution", "Already the lowest", ""); return }
        val next = UPSCALES[i]
        playUpscale = next
        NativeBridge.setOptionsLive(arrayOf(UPSCALE_KEY), arrayOf(next))
        applyPicture() // Auto follows the resolution
        statsRenderer = "parallel-RDP $next"
        stats?.renderer = statsRenderer
        val kept = com.joeysretro.joey64.video.VideoChoices.label(com.joeysretro.joey64.video.VideoKey.Resolution,
            videoAtStart[com.joeysretro.joey64.video.VideoKey.Resolution])
        JLog.i(TAG, "upscale hotkey: $now -> $next for this play")
        pop("upscale", InGamePopup.Kind.Resolution, "Resolution", if (i == 0) "Native (1x)" else next, "This play only. Settings › Video keeps $kept")
    }

    /** Stopped by the Pause hotkey: no menu, the pop-up stays until it's pressed again (or the menu's Resume). */
    private var hotkeyPaused = false
    private fun togglePause() {
        if (menuOpen) return
        if (!hotkeyPaused && pauseHeldBack { if (!menuOpen && !hotkeyPaused) togglePause() }) return
        sessionMark(); hotkeyPaused = !hotkeyPaused
        if (hotkeyPaused) {
            NativeBridge.pause(true)
            releaseAll()
            overlay?.show(InGamePopup("paused", InGamePopup.Kind.Paused, "Paused", pausedLine(), "", stayMs = com.joeysretro.joey64.launcher.ingame.PopupQueue.UNTIL_HIDDEN))
        } else {
            overlay?.hide("paused", waiting = true)
            if (resumed) NativeBridge.pause(false)
        }
        JLog.i(TAG, "pause hotkey: ${if (hotkeyPaused) "paused" else "going on"}")
    }

    /** The Paused pop-up's line: how to go on (Pause has no button by default: then the pause menu's Resume), and Frame advance when it has one. */
    private fun pausedLine(): String {
        val pause = hotkeys.keys[HotkeyAction.Pause] != null
        // Short enough for the pop-up's one line.
        return if (hotkeys.keys[HotkeyAction.FrameAdvance] == null) (if (pause) "Press Pause again to go on" else "Resume from the pause menu to go on")
            else (if (pause) "Pause: go on" else "Menu: Resume") + " · Frame advance: one frame"
    }

    /** Frame advance: running, it pauses (as Pause); paused by a hotkey, one more frame each press. */
    private fun frameAdvance() {
        if (menuOpen) return
        if (com.joeysretro.joey64.achievements.RaHardcore.on()) { popHardcore("Frame advance"); return }
        if (!hotkeyPaused) { togglePause(); return }
        NativeBridge.stepFrame()
    }

    /** The Achievements hotkey opened the page (B goes back to the game). */
    private var achievementsDirect = false
    private fun openAchievementsHotkey() {
        if (menuOpen) return
        val ra = com.joeysretro.joey64.achievements.RaBridge
        if (ra.gameInfo() == "null" && ra.debugCache == null) {
            if (ra.account.value == null) pop("achievements", InGamePopup.Kind.NoAchievements, "Achievements", "Signed out", "Sign in at Settings › RetroAchievements")
            else pop("achievements", InGamePopup.Kind.NoAchievements, "Achievements", "None for this game", "RetroAchievements has no set for it")
            return
        }
        if (!openMenu(retry = { openAchievementsHotkey() })) return
        achievementsDirect = true
        overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Achievements(toGame = true)
    }

    /** The hotkey button is held: a hat D-pad's directions are the D-pad keys' hotkeys (Up / Down hold for the volume). */
    private fun hotkeyHat(event: MotionEvent) {
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val x = if (hx < -0.5f) -1 else if (hx > 0.5f) 1 else 0
        if (x != 0 && x != hotkeyHatX && !quitting)
            hotkeys.actionFor(if (x < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT, alone = false)?.let { hotkey(it) }
        hotkeyHatX = x
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val y = if (hy < -0.5f) -1 else if (hy > 0.5f) 1 else 0
        if (y != hotkeyHatY) {
            if (volumeHeld != 0) volumeKey(0, false)
            if (y != 0 && !quitting) hotkeys.actionFor(if (y < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN, alone = false)?.let {
                when (it) {
                    HotkeyAction.VolumeUp -> volumeKey(5, true)
                    HotkeyAction.VolumeDown -> volumeKey(-5, true)
                    else -> hotkey(it)
                }
            }
        }
        hotkeyHatY = y
    }

    private fun hotkeySave() {
        val st = states ?: return
        letGoHeld()
        val slot = currentSlot()
        stateHandler.post { val ok = st.save(slot); if (ok) savedThisPlay = true; runOnUiThread { popSaved(slot, ok) } }
    }

    private fun hotkeyLoad() {
        val st = states ?: return
        if (com.joeysretro.joey64.achievements.RaHardcore.on()) { popHardcore("Load state"); return }
        letGoHeld()
        val slot = currentSlot()
        stateHandler.post {
            val savedAt = st.savedAt(slot)
            if (savedAt == null) { runOnUiThread { popEmpty(slot) }; return@post }
            val ok = st.load(slot, undoable = true)
            if (ok) { startLoadFailed = false; loadedThisPlay = true }
            runOnUiThread { popLoaded(slot, ok, savedAt) }
        }
    }

    // ---- Undo load state / Undo save state (approved mockup 2026-11-08, scratchpad build_undo.py) ----

    /** Whether a load / save by hand happened this play, for Nothing to undo's line. */
    @Volatile private var loadedThisPlay = false
    @Volatile private var savedThisPlay = false

    private fun hotkeyUndoLoad() {
        val st = states ?: return
        if (com.joeysretro.joey64.achievements.RaHardcore.on()) { popHardcore("Undo load state"); return }
        letGoHeld()
        stateHandler.post {
            val slot = st.undoLoad()
            runOnUiThread {
                if (slot != null) pop("state", InGamePopup.Kind.Undo, "Undo load state", "Back to before loading ${slotName(slot).lowercase()}", "")
                else pop("state", InGamePopup.Kind.Undo, "Undo load state", NOTHING_TO_UNDO,
                    if (loadedThisPlay) "Already undone" else "No save state loaded since the game started")
            }
        }
    }

    private fun hotkeyUndoSave() {
        val st = states ?: return
        stateHandler.post {
            val u = st.undoSave()
            runOnUiThread {
                if (u != null) pop("state", InGamePopup.Kind.Undo, "Undo save state",
                    slotName(u.slot) + (u.savedAt?.let { " is back to " + slotDate(it) } ?: " is empty again"), "")
                else pop("state", InGamePopup.Kind.Undo, "Undo save state", NOTHING_TO_UNDO,
                    if (savedThisPlay) "Already undone" else "No save state saved since the game started")
            }
        }
    }

    // ---- Turbo (approved mockup 2026-11-08, scratchpad build_turbo.py) ----

    /** The Turbo hotkey's switch, for this play only: the next game starts with it on. */
    private var turboOn = true

    /** Each player's turbo buttons and speed to the host (a button held by a tap doesn't repeat). */
    private fun applyTurbo() {
        for (i in 0..3) {
            val s = players[i].setup
            NativeBridge.setTurbo(i, com.joeysretro.joey64.controls.turboMask(s.turbo, s.latch), s.turboSpeed)
        }
        NativeBridge.setTurboOn(turboOn)
    }

    private fun toggleTurbo() {
        val all = (0..3).filter { controls.players[it].device != DeviceChoice.None }
            .flatMap { players[it].setup.let { s -> s.turbo - s.latch } }.toSet()
        if (all.isEmpty()) { pop("turbo", InGamePopup.Kind.Turbo, "", "No turbo buttons", "Controls › Turbo"); return }
        turboOn = !turboOn
        NativeBridge.setTurboOn(turboOn)
        JLog.i(TAG, "turbo " + if (turboOn) "on" else "off")
        if (turboOn) pop("turbo", InGamePopup.Kind.Turbo, "", "Turbo on", com.joeysretro.joey64.controls.turboNames(all))
        else pop("turbo", InGamePopup.Kind.TurboOff, "", "Turbo off",
            com.joeysretro.joey64.controls.turboNames(all, and = true) + if (all.size == 1) " presses normally" else " press normally")
    }

    // ---- Swap D-pad (Joey 2026-11-13: "add it as a hotkey option") ----

    /** The Swap D-pad hotkey's switch, for this play only: the next game starts with the D-pad as set in Controls. */
    private var dpadSwapped = false

    private fun applyDPadSwap() { for (i in 0..3) players[i].dpadSwapped = dpadSwapped }

    private fun swapDPad() {
        val who = (0..3).filter { controls.players[it].device != DeviceChoice.None && players[it].setup.stick.usesDPad }
        if (who.isEmpty()) { pop("dpadswap", InGamePopup.Kind.DPadSwap, "", "The D-pad is the N64 D-pad", "No player's stick is on the D-pad · Controls › Stick"); return }
        dpadSwapped = !dpadSwapped
        // Let go first, so a direction held across the swap doesn't stay pressed on the other side.
        for (i in who) players[i].release()
        applyDPadSwap()
        for (i in who) push(i)
        JLog.i(TAG, "swap D-pad: " + if (dpadSwapped) "N64 D-pad" else "stick")
        if (dpadSwapped) pop("dpadswap", InGamePopup.Kind.DPadSwap, "", "D-pad: N64 D-pad", "Swap D-pad again for the stick")
        else pop("dpadswap", InGamePopup.Kind.DPadSwap, "", "D-pad: moves the stick", "Swap D-pad again for the N64 D-pad")
    }

    private fun stepSlot(by: Int) {
        val st = states ?: return
        val slot = (currentSlot() - 1 + by).mod(StateSlots.COUNT) + 1
        setCurrentSlot(slot)
        stateHandler.post {
            val savedAt = st.savedAt(slot)
            runOnUiThread {
                pop("state", InGamePopup.Kind.Slot, "Save state", slotName(slot),
                    savedAt?.let { "Saved " + slotDate(it) } ?: "Empty")
            }
        }
    }

    private fun hotkeyScreenshot() {
        val st = states ?: return
        stateHandler.post {
            val name = st.screenshot()
            runOnUiThread {
                if (name != null) pop("screenshot", InGamePopup.Kind.Screenshot, "Screenshot", "Saved", "In the screenshots folder")
                else if (!noSpace(com.joeysretro.joey64.saves.NoSpace.What.Screenshot, st.failedNeed)) pop("screenshot", InGamePopup.Kind.Problem, "Screenshot", "Not saved", "The log has the details")
            }
        }
    }

    /** The console's reset button, between frames. On the state thread. */
    private fun resetNow(): Boolean {
        val ok = NativeBridge.resetGame(STATE_TIMEOUT_MS)
        if (!ok) JLog.e(TAG, "reset wasn't served")
        return ok
    }

    private fun slotDate(at: Long) = com.joeysretro.joey64.launcher.ingame.SlotDates.label(at, System.currentTimeMillis())

    // Every state pop-up (slot, saved, loaded, empty) has the key "state": the newest takes the place
    // of the one showing, so "Slot 3 saved" doesn't wait behind "Slot 3, Empty".
    /**
     * The first game on a device with no sticks (approved stickless mockup 2026-11-13, screen 7): what the D-pad and the
     * hold button do, top left for 8 s, once on this device, when player 1 plays on the stickless setup.
     */
    private fun noSticksNote() {
        val p = controls.players[0]
        val hold = p.cHold ?: return
        if (p.device != DeviceChoice.BuiltIn || !p.stick.usesDPad || !PadDevices.noSticks(PadDevices.builtIn())) return
        val prefs = getSharedPreferences("device", MODE_PRIVATE)
        if (prefs.getBoolean("nosticks_note", false)) return
        prefs.edit().putBoolean("nosticks_note", true).apply()
        val h = com.joeysretro.joey64.controls.InputNames.key(hold.code, null)
        val keys = com.joeysretro.joey64.controls.N64Button.C.mapNotNull { (p.buttons[it] as? com.joeysretro.joey64.controls.PadInput.Key)?.code }
            .map { com.joeysretro.joey64.controls.InputNames.key(it, null) }.sorted().joinToString(" ")
        // The shared pop-up's lines: the title in gold, then the approved words (the name line is one line long).
        val pp = InGamePopup("nosticks", InGamePopup.Kind.NoSticks, "*No sticks on this device*", "The D-pad moves.",
            "Hold $h and press $keys for the C buttons. Controls › Player 1 to change it.", stayMs = 8_000)
        overlay?.show(pp) ?: earlyPops.add(pp)
    }

    private fun pop(key: String, kind: InGamePopup.Kind, label: String, name: String, line: String, bar: Float? = null) {
        if (destroyed) return
        // Settings › On screen › Pop-ups (problems always show).
        val off = when (kind) {
            InGamePopup.Kind.Saved, InGamePopup.Kind.Loaded, InGamePopup.Kind.Slot -> !onScreen.popStates
            InGamePopup.Kind.Undo -> !onScreen.popStates && name != NOTHING_TO_UNDO
            InGamePopup.Kind.Screenshot -> !onScreen.popScreenshots
            InGamePopup.Kind.Reset -> !onScreen.popReset
            InGamePopup.Kind.Volume, InGamePopup.Kind.Mute, InGamePopup.Kind.Resolution, InGamePopup.Kind.Shader -> !onScreen.popHotkeys
            InGamePopup.Kind.Turbo, InGamePopup.Kind.TurboOff -> !onScreen.popHotkeys && name != "No turbo buttons"
            else -> false
        }
        if (off) return
        val p = InGamePopup(key, kind, label, name, line, bar = bar)
        // Before the game is up (a RetroArch preset that didn't load at start): shown once the overlay is there.
        overlay?.show(p) ?: earlyPops.add(p)
    }

    private val earlyPops = ArrayList<InGamePopup>()

    private fun popSaved(slot: Int, ok: Boolean) {
        if (!ok && noSpace(com.joeysretro.joey64.saves.NoSpace.What.State, states?.failedNeed ?: 0, slotName(slot))) return
        if (ok) pop("state", InGamePopup.Kind.Saved, "Save state", "${slotName(slot)} saved", overlay?.title ?: "")
        else pop("state", InGamePopup.Kind.Problem, "Save state", "${slotName(slot)} wasn't saved", "The log has the details")
    }

    private fun popLoaded(slot: Int, ok: Boolean, savedAt: Long?) {
        if (ok) pop("state", InGamePopup.Kind.Loaded, "Load state", "${slotName(slot)} loaded", savedAt?.let { "Saved " + slotDate(it) } ?: "")
        else pop("state", InGamePopup.Kind.Problem, "Load state", "${slotName(slot)} didn't load", "The log has the details")
    }

    private fun popEmpty(slot: Int) =
        pop("state", InGamePopup.Kind.Problem, "Load state", "${slotName(slot)} is empty", "Nothing was loaded")

    private fun popReset(ok: Boolean) {
        if (ok) pop("reset", InGamePopup.Kind.Reset, "Reset", "Game reset", overlay?.title ?: "")
        else pop("reset", InGamePopup.Kind.Problem, "Reset", "The game wasn't reset", "The log has the details")
    }

    /** Back opens the pause menu over the paused game (Back again, or B, resumes). */
    @Deprecated("Activity's own Back, kept for the controller's Back key")
    override fun onBackPressed() {
        if (states == null || overlay == null || !mine) { @Suppress("DEPRECATION") super.onBackPressed(); return }
        if (quitting || menuOpen) return
        openMenu()
    }

    /**
     * RetroAchievements' pause limit in Hardcore (rc_client_can_pause; approved 2026-11-04: a pause asked for too soon
     * is held until it's allowed, with no message). True: held, [retry] runs once the frames have run.
     */
    private fun pauseHeldBack(retry: () -> Unit): Boolean {
        if (!com.joeysretro.joey64.achievements.RaHardcore.on()) return false
        val wait = com.joeysretro.joey64.achievements.RaBridge.pauseWait()
        if (wait <= 0) return false
        if (pauseHeld != null) return true
        JLog.i(TAG, "Hardcore: pause held for $wait frames")
        val r = Runnable { pauseHeld = null; if (!destroyed && !pauseHeldBack(retry)) retry() }
        pauseHeld = r
        fpsHandler.postDelayed(r, wait * 17L)
        return true
    }
    private var pauseHeld: Runnable? = null

    /**
     * Quit to library: the game is paused, the auto slot written (Continue starts from it) and the
     * recovery state dropped, then the screen closes.
     */
    // ---- Netplay in game (approved mockup 2026-11-10, scratchpad build_netplay3.py / netplay3_mock.png, "Approve as drawn") ----
    // The room is read four times a second while the game is in one (dev env N64_NETPLAY / N64_NETPLAY_SERVE until the
    // Play together screens). Screens: (1) another player paused, (2) the pauser's menu line (PauseScreens), (3) waiting for a
    // slow player, (5) out of step, (6) a hotkey that waits until you leave; the player-left popup (approved 2026-11-05).
    // Room lost (4) is the library's (GameStartFailed.netEnded).
    private var net = com.joeysretro.joey64.netplay.NetInfo.OFF
    /** This device's player (1-4), 0 until known. */
    private var netMe = 0
    /** The room's figures for this play's report (Send reports): see reports/NetStats. */
    private val netStats = com.joeysretro.joey64.reports.NetStats()
    private var netStatsPolls = 0
    /** Out of step shows once a play; players who left are said once. */
    private var netOutShown = false
    private var netLeftSaid = 0
    /** The player the Waiting pop-up names, 0 for none. */
    private var netWaitShown = 0
    /** The room's players (names, pings), and every name seen this play (a player who left isn't listed any more). */
    private var netPlayers = emptyList<com.joeysretro.joey64.netplay.NetPlayer>()
    private val netNames = HashMap<Int, String>()
    private var netChip: com.joeysretro.joey64.netplay.NetChip? = null
    private fun netName(p: Int) = netNames[p]

    private val netWatch = object : Runnable {
        override fun run() {
            if (destroyed || isFinishing) return
            val n = com.joeysretro.joey64.netplay.NetInfo.of(NativeBridge.netplayInfo())
            net = n
            if (n.inRoom && netMe == 0) {
                netMe = com.joeysretro.joey64.netplay.NetplayWords.myPlayer(n.hosting, android.system.Os.getenv("N64_NETPLAY"), n.me)
                if (netMe != 0) JLog.i(TAG, "netplay: in the room as player $netMe" + if (n.hosting) " (hosting)" else "")
            }
            netPlayers = if (n.inRoom) com.joeysretro.joey64.netplay.NetPlayers.parse(NativeBridge.netplayPlayers()) else emptyList()
            for (p in netPlayers) netNames[p.player] = p.name
            // Reports: the room's figures go with the session every 2 s, and the names seen so the log excerpt can
            // leave them out (ReportJson.session never sends them).
            if (n.inRoom) {
                netStats.sample(n, netPlayers, android.os.SystemClock.elapsedRealtime())
                if (netStatsPolls++ % 8 == 0) session?.let { s ->
                    netStats.json()?.let { s.put("netplay", it) }
                    s.put("maskNames", org.json.JSONArray(netNames.values.toList()))
                }
            }
            if (n.inRoom && !quitting) netShow(n)
            // The players chip (approved), top right; at the bottom right when the stats are top right.
            if (netChip == null && netPlayers.isNotEmpty()) netChip = com.joeysretro.joey64.netplay.NetChip(this@MainActivity, windowManager)
            netChip?.show(if (quitting) emptyList() else netPlayers,
                onScreen.stats != com.joeysretro.joey64.onscreen.StatsMode.Off && onScreen.corner.top && !onScreen.corner.left)
            fpsHandler.postDelayed(this, NET_POLL_MS)
        }
    }

    private fun netShow(n: com.joeysretro.joey64.netplay.NetInfo) {
        val o = overlay ?: return
        val w = com.joeysretro.joey64.netplay.NetplayWords
        // (5) Out of step: over the paused game (pausing tells the room, so everyone stops), once a play.
        if (n.outOfStep && !netOutShown) {
            netOutShown = true
            JLog.i(TAG, "netplay: the games are out of step, asking")
            if (!menuOpen) openMenu()
            o.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.NetOutOfStep(n.hosting)
            return
        }
        // Player left: the game paused for everyone while the popup is up.
        val newLeft = n.left and netLeftSaid.inv()
        if (newLeft != 0) {
            netLeftSaid = netLeftSaid or n.left
            val p = n.playersIn(newLeft).first()
            // Player 1 is the host: its room closes with it, so there's nothing to carry on in. Back to the library,
            // which says "The host closed the room" (Joey 2026-11-12).
            if (p == 1 && !n.hosting && netMe != 1) {
                JLog.i(TAG, "netplay: the host closed the room, back to the library")
                netStats.end = "hostclosed"
                intent?.takeIf { launcherString(EXTRA_MD5) != null }?.let { com.joeysretro.joey64.launcher.GameStartFailed.netEnded(it, 8) }
                quitToLibrary()
                return
            }
            JLog.i(TAG, "netplay: player $p left, asking")
            if (!menuOpen) openMenu()
            if (o.screen !is com.joeysretro.joey64.launcher.ingame.PauseScreen.NetOutOfStep) o.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.NetLeft(p, netName(p))
            return
        }
        // (1) Another player's menu has the game stopped: shown while it's open, unless this player's own menu is.
        val other = n.pausedBy != 0 && n.pausedBy != netMe
        val scr = o.screen
        if (other && !menuOpen && (scr == null || (scr is com.joeysretro.joey64.launcher.ingame.PauseScreen.NetPaused && (scr.player != n.pausedBy || scr.name != netName(n.pausedBy))))) {
            if (scr == null) JLog.i(TAG, "netplay: player ${n.pausedBy} paused the game")
            o.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.NetPaused(n.pausedBy, netName(n.pausedBy))
            touch?.setActive(false)
        } else if (!other && scr is com.joeysretro.joey64.launcher.ingame.PauseScreen.NetPaused) {
            JLog.i(TAG, "netplay: player ${scr.player} is back")
            o.screen = null
            touch?.setActive(touchActive())
        }
        // (3) Waiting for a slow player (the core says so after a second's wait), while nobody has paused.
        val waitFor = if (n.pausedBy == 0 && n.waitingFor != netMe) n.waitingFor else 0
        if (waitFor != netWaitShown) {
            netWaitShown = waitFor
            if (waitFor == 0) o.hide("npwait")
            else o.show(InGamePopup("npwait", InGamePopup.Kind.NetWait, "Netplay", w.waitingTitle(waitFor), w.slowLine(waitFor, netName(waitFor)),
                stayMs = com.joeysretro.joey64.launcher.ingame.PopupQueue.UNTIL_HIDDEN))
        }
    }

    /** (6) In a room, a hotkey for something that changes the game's course says it waits until you leave (3 s). True if it's blocked. */
    private fun netBlocked(action: HotkeyAction): Boolean {
        if (!net.inRoom && !roomPlay) return false
        val line = com.joeysretro.joey64.netplay.NetplayWords.blockedLine(action) ?: return false
        JLog.i(TAG, "netplay: ${action.label} refused in a room")
        overlay?.show(InGamePopup("npblock", InGamePopup.Kind.NetBlocked, "Netplay", com.joeysretro.joey64.netplay.NetplayWords.BLOCKED, line, stayMs = NET_BLOCKED_MS))
        return true
    }

    private fun quitToLibrary() {
        val st = states ?: run { finish(); return }
        if (quitting) return
        quitting = true
        NativeBridge.pause(true)
        releaseAll()
        stateHandler.removeCallbacks(recoveryTick)
        stateHandler.post {
            val stuckMs = NativeBridge.coreStuckMs()
            val shot = if (roomPlay) null.also { keepRecovery = true; JLog.i(TAG, "states: a netplay room, no quit save") }
                else if (stuckMs > STUCK_MS) null.also {
                    // Its state can't be taken (that waits 10 s for the next frame, which never comes) and the recovery
                    // state from before is the last good moment: kept, so the library offers it.
                    keepRecovery = true
                    JLog.e(TAG, "states: the core has been in one frame for $stuckMs ms: no quit save, the recovery state is kept")
                }
                else if (startLoadFailed) null.also { JLog.e(TAG, "states: the state asked for at start didn't load, so the auto slot is kept as it was") }
                else if (!savesOpts.saveOnQuit) null.also { JLog.i(TAG, "states: Save on quit is off, no quit save written") }
                else st.takeQuit()
            if (shot != null) {
                // Written once the library is back (Joey 2026-11-13: "why does quitting a game take so long?": 8 s on
                // the Thor's SD card, all of it this write; taking it is 0.15 s). The recovery state stays until the
                // quit save is in, so a write that doesn't finish loses nothing; the next game's start waits for it.
                keepRecovery = true
                val keep = savesOpts.quitSaves
                GameStates.writeLater(st.saveName) { if (st.writeQuit(shot, keep)) st.clearRecovery() }
            } else if (stuckMs <= STUCK_MS && !roomPlay) st.clearRecovery()
            recent?.let { RecentlyPlayed.record(this, it.copy(lastPlayed = System.currentTimeMillis())) }
            // Quit to: the library (or the frontend that started the game), or out of Joey64 altogether:
            // every screen of ours in this task closes, so Android shows the frontend or home screen.
            runOnUiThread { if (hotkeys.quitTo == QuitTo.Exit) finishAffinity() else finish() }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        if (!mine) return
        NativeBridge.surfaceResized()
        if (started) NativeBridge.showAgain()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceUp = false; if (mine) NativeBridge.setSurface(null) }

    /**
     * A ROM arrived while a game is loaded in this process (a frontend sending a second game). The core cannot load
     * another one, so: stop the running game (its save is written on exit), unpack the new ROM here, and start the
     * game screen again in a fresh process (RestartActivity), passing the unpacked path and save name so no URI grant
     * has to survive the restart. The intent's other extras (env, opts, renderer, ...) go along.
     */
    /**
     * Runs [then] once the library (LauncherActivity, in this process) has had its state saved, so the process restart
     * in [switchGame] doesn't drop it: Android removes an activity whose process dies before its state was saved
     * ("app died, no saved state", Thor 2026-11-07), and Quit to library then went to the home screen. Right away when
     * the library isn't showing; after 2 s at the latest.
     */
    private fun afterLauncherSaved(then: () -> Unit) {
        if (!com.joeysretro.joey64.launcher.LauncherActivity.started) { then(); return }
        val app = application
        val handler = android.os.Handler(mainLooper)
        var done = false
        lateinit var watch: android.app.Application.ActivityLifecycleCallbacks
        val go = { if (!done) { done = true; app.unregisterActivityLifecycleCallbacks(watch); then() } }
        watch = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivitySaveInstanceState(a: android.app.Activity, out: Bundle) {
                if (a is com.joeysretro.joey64.launcher.LauncherActivity) handler.post { go() }
            }
            override fun onActivityCreated(a: android.app.Activity, b: Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        }
        app.registerActivityLifecycleCallbacks(watch)
        handler.postDelayed({ go() }, 2000)
    }

    /**
     * The core didn't stop (stuck in a frame, NativeBridge.stop): its thread would spin on a core until the process
     * ends, heating the device and draining the battery while the library or another app is up. So the process ends
     * once quit saves still being written are in: the library, when it's there, comes back in a fresh process
     * (RestartActivity, as switchGame); with no library up the process just ends.
     */
    private fun endStuckProcess() {
        JLog.e(TAG, "the core is stuck: this process ends once the library is saved (a fresh one shows it)")
        val app = applicationContext
        val main = Handler(mainLooper)
        Thread({
            GameStates.awaitWrites(null, GameStates.WRITE_WAIT_MS)
            GameStates.stopCommits()
            main.post {
                if (com.joeysretro.joey64.launcher.LauncherActivity.started) afterLauncherSaved {
                    RestartActivity.restart(app, Intent(app, com.joeysretro.joey64.launcher.LauncherActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else android.os.Process.killProcess(android.os.Process.myPid())
            }
        }, "joey64-stuck-end").start()
    }

    private fun switchGame() {
        JLog.i(TAG, "new ROM while a game is loaded: restarting the process for it")
        // A game screen still open (a frontend sent a ROM mid-game): the new one replaces the task. None open (the
        // library's Play after Quit to library): the task keeps the library under the new game screen, so its Quit to
        // library goes back there (2026-11-07: CLEAR_TASK here sent every second game's quit to the home screen).
        val clearTask = owner != null
        // The game before is closed on purpose: nothing to recover for it (only its own; another game's stays, F3).
        val closing = owner?.states?.saveName
        // The process is ended for the new game before the old screen's onDestroy runs: a start that hadn't shown a
        // picture yet is let off here (it was counted as one that died, and the trial stepped down).
        owner?.let { it.sessionEnd = "next"; it.cancelTrial(); NativeBridge.stop(); it.finish() }
        owner = null
        // Unpacking the new ROM (up to 64 MB, or a zip / 7z) runs on gameIo, as prepareGame does for the first game: on
        // the main thread it risked an ANR, and a missing file or a full disk crashed the app (code audit 3, H2).
        gameIo.execute {
            // The process restarts for the new game: a quit save still being written ([quitToLibrary]) is finished first.
            GameStates.awaitWrites(null, GameStates.WRITE_WAIT_MS)
            val got = try { resolveRom() } catch (e: Exception) { JLog.e(TAG, "can't get the new game ready: $e"); null }
            // Only once the new game is there (code audit 4, A-M1): a file that won't open keeps the closed game's
            // recovery state, so its play isn't lost with it.
            if (got != null) closing?.let { GameStates.Recovery.clear(this, it) }
            // Its MD5 here too, so the next process's onCreate doesn't hash it (code audit 3, M18).
            val md5 = got?.let { knownMd5() ?: romMd5(it.first) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val (rom, saveName) = got ?: run {
                    JLog.e(TAG, "No N64 ROM in the new intent")
                    // Started from the library: it says "<game> won't start", as for a first game.
                    intent?.takeIf { launcherString(EXTRA_MD5) != null }?.let {
                        com.joeysretro.joey64.launcher.GameStartFailed.romFailed(it, com.joeysretro.joey64.launcher.GameStartFailed.Why.Unreadable)
                    }
                    finish(); return@runOnUiThread
                }
                val next = gameStart(this).apply {
                    carriedExtras()?.let { putExtras(it) }
                    removeExtra("rom")
                    // The URI isn't carried over (the ROM is), so recently played gets the source as extras.
                    intent.data?.let { d ->
                        if (!hasExtra(EXTRA_SOURCE_URI)) putExtra(EXTRA_SOURCE_URI, d.toString())
                        if (!hasExtra(EXTRA_SOURCE_NAME)) displayName(d)?.let { putExtra(EXTRA_SOURCE_NAME, it) }
                    }
                    putExtra(EXTRA_ROM_PATH, rom)
                    putExtra(EXTRA_SAVE_NAME, saveName)
                    md5?.let { putExtra(EXTRA_KNOWN_MD5, it) } ?: removeExtra(EXTRA_KNOWN_MD5)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (clearTask) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                // This screen only hands over: gone before the new one comes, so Back from it doesn't land here.
                if (!clearTask) finish()
                // A staged quit save going into the data folder waits for the new process (GameStates.commitAllLater).
                GameStates.stopCommits()
                RestartActivity.restart(this, next)
            }
        }
    }
    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {}

    // Immersive: hide status + nav bars so the surface is the whole screen. Re-applied on focus
    // because the system shows the bars again after dialogs / swipes.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The hotkey button's release goes to whichever window has focus (the menu, the notification
        // shade): let go of it here, or B alone would quit to the library afterwards.
        if (!hasFocus) { hotkeyHeld = false; fastForwardRelease(); slowMotionRelease(); rewindRelease(); if (!menuOpen) gameSharkButton(false); return }
        requestUnbufferedJoystick()
        hideSystemBars()
    }

    /**
     * Hides the status and navigation bars. Also in onCreate, before the window first shows: started by another
     * launcher (JoeyOS), the navigation bar showed while the game loaded, since focus (above) only comes once it has
     * (Joey 2026-11-11). From Joey64's own library the bars were already hidden, so it didn't show there.
     */
    private fun hideSystemBars() {
        // Install the decor first: called from onCreate (before any setContentView), PhoneWindow.getInsetsController()
        // reads a null decor and throws (crashed every game start on 6cc2c6f, Duo + Odin 2 Portal).
        window.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    // ---- Controls: controllers to N64 ports (plan, Phase 4) ------------------------------------------
    // Each player has a controller (Controls' Controller row) and its own buttons, stick, dead zone and
    // pak, from `files/controls/` (All games, or the game's own). Every event goes to the player whose
    // controller sent it; a new controller nobody has goes to the first player with None, mapped by Auto
    // map, and is kept there. The core runs with independent C buttons (alt-map), so each N64 button is
    // its own RetroPad bit; the N64 stick goes to the core's left analog, its right analog stays still
    // (C buttons on a stick are bindings). Stick reach is calibrated per device (Controllers.StickCal).
    private val controlsStore by lazy { ControlsStore(this) }
    private var controls = ControlsSet.defaults()
    private val players = Array(4) { PlayerInput(controls.players[it]) }
    /** deviceId to player (-1: no player has it). */
    private val playerOfDevice = HashMap<Int, Int>()
    private val stickCals = HashMap<Int, Controllers.StickCal>()
    private val stickPrefs by lazy { getSharedPreferences("sticks", Context.MODE_PRIVATE) }
    /** Players whose port is plugged in this session (the core's ports are set as the game loads). */
    private var portsPresent = 1

    // ---- Touch controls (approved mockup 2026-11-09, scratchpad build_touch3.py): Player 1's, in a window over the game ----
    private var touch: com.joeysretro.joey64.touch.TouchLayer? = null
    private var touchSettings = com.joeysretro.joey64.touch.TouchSettings()
    private var touchFfDown = 0L
    private var touchFfWasOn = false
    private val touchHost = object : com.joeysretro.joey64.touch.TouchLayer.Host {
        override fun onTouchInput(bits: Int, stick: Pair<Float, Float>?) {
            players[0].touchBits = bits; players[0].touchStick = stick
            push(0)
        }
        override fun onTouchAction(c: com.joeysretro.joey64.touch.TouchControl, down: Boolean) {
            if (quitting) return
            when (c.kind) {
                com.joeysretro.joey64.touch.TouchKind.Menu -> if (!down && !menuOpen) openMenu()
                com.joeysretro.joey64.touch.TouchKind.FastForward -> if (!net.inRoom) touchFastForward(down) else if (down) netBlocked(HotkeyAction.FastForward)
                com.joeysretro.joey64.touch.TouchKind.SaveState -> if (down) hotkey(HotkeyAction.Save)
                com.joeysretro.joey64.touch.TouchKind.LoadState -> if (down) hotkey(HotkeyAction.Load)
                com.joeysretro.joey64.touch.TouchKind.Screenshot -> if (down) hotkey(HotkeyAction.Screenshot)
                com.joeysretro.joey64.touch.TouchKind.Macro -> touchMacro(c, down)
                else -> {}
            }
        }
        override fun onEmptyTap(downX: Float, downY: Float, upX: Float, upY: Float) {
            // A tap on an achievement pop-up under the controls opens it, as on the game's own window.
            val o = overlay ?: return
            val d = o.tapAt(downX, downY)?.takeIf { opensAchievements(it) } ?: return
            if (o.tapAt(upX, upY) == d) openAchievementsAt(d)
        }
    }

    /** The touch controls for this game (All games, or its own): at the start ([fresh]), or after a change in the pause menu. */
    private fun applyTouch(fresh: Boolean) {
        val t = touch ?: return
        val s = com.joeysretro.joey64.touch.TouchStore(this).forGame(currentSaveName.ifEmpty { null })
        touchSettings = s
        if (fresh) t.apply(s, controllerConnected = PadDevices.pads().isNotEmpty()) else t.reapply(s)
        NativeBridge.setPortraitTop(if (s.show == com.joeysretro.joey64.touch.TouchShow.Never) -1 else PORTRAIT_TOP_PER_MILLE)
        t.setActive(touchActive())
    }

    /** The touch controls take touches only while the game plays with nothing over it. */
    private fun touchActive() = resumed && !menuOpen && !quitting && overlay?.screen == null

    /** Fast-forward on the screen: a tap turns it on or off, a hold is for while held ("Tap to turn on or off, hold for while held"). */
    private fun touchFastForward(down: Boolean) {
        val now = android.os.SystemClock.uptimeMillis()
        if (down) { touchFfWasOn = fastForward; touchFfDown = now; setFastForward(!fastForward); return }
        if (!touchFfWasOn && now - touchFfDown >= TOUCH_HOLD_MS) setFastForward(false)
    }

    /** A macro button: one of Player 1's macros by name, played as its own button would (held, a repeating one repeats). */
    private fun touchMacro(c: com.joeysretro.joey64.touch.TouchControl, down: Boolean) {
        val m = players[0].setup.macros.firstOrNull { it.name == c.macro }
        if (m == null) { if (down) JLog.i(TAG, "touch: no macro named ${c.macro} for Player 1"); return }
        if (down) playMacro(0, m) else if (m.repeat) NativeBridge.stopMacro(0, false)
    }

    /** Reads the controls for [saveName] and sets the core's ports, paks and input options. Before loadGame. */
    private fun applyControls(rom: String, saveName: String) {
        if (!controlsStore.hasAll()) controlsStore.saveAll(PadDevices.firstSet(com.joeysretro.joey64.launcher.layout.Tv.isTv(this)))
        // A set made before stickless pads were known: moved to the stickless setup once (approved mockup 2026-11-13).
        else PadDevices.fixStickless(controlsStore.all())?.let { controlsStore.saveAll(it); JLog.i(TAG, "controls: this device has no sticks, moved to the stickless setup") }
        // A set Auto map made before this pad's Xbox/Nintendo toggle was known: moved once (Controllers.faceKey).
        PadDevices.fixToggleFaceOnce(controlsStore) { JLog.i(TAG, it) }
        controls = controlsStore.forGame(saveName.ifEmpty { null })
        hotkeys = controlsStore.hotkeys()
        applyRewind()
        for (i in 0..3) players[i].setup = controls.players[i]
        turboOn = true
        applyTurbo()
        dpadSwapped = false
        applyDPadSwap()
        playerOfDevice.clear()
        portsPresent = 1
        for (i in 1..3) if (controls.players[i].device != DeviceChoice.None) portsPresent = portsPresent or (1 shl i)
        NativeBridge.setPortsPresent(portsPresent)
        NativeBridge.setOption("mupen64plus-alt-map", "True")
        NativeBridge.setOption("mupen64plus-astick-deadzone", "0") // PlayerInput applies the player's own
        // Stick feel's Range and octagon gate reach past 80: the core maps 32767 to 120 (PlayerInput.toRetroStick).
        NativeBridge.setOption("mupen64plus-astick-sensitivity", "150")
        val md5 = gameMd5
        val rumbleGames by lazy { PakDb.load(this) }
        val cpOnlyGames by lazy { PakDb.loadControllerOnly(this) }
        val transferGames by lazy { PakDb.loadTransferAuto(this) }
        for (i in 0..3) {
            val p = controls.players[i]
            val pak = PakDb.resolve(p.pak, rumbleGames, transferGames, cpOnlyGames, md5)
            if (p.pak == Pak.Rumble && pak == Pak.Controller)
                JLog.i(TAG, "P${i + 1}: Rumble Pak picked, this game knows only the Controller Pak: Controller Pak")
            paks[i] = pak
            NativeBridge.setOption("mupen64plus-pak${i + 1}", pak.core!!)
        }
        lastRom = rom
        JLog.i(TAG, "controls: " + controls.players.mapIndexed { i, p ->
            "P${i + 1} ${deviceLabel(p.device)}" + if (p.device == DeviceChoice.None) "" else " (${p.pak.name})"
        }.joinToString(", ") + if (saveName.isNotEmpty() && controlsStore.game(saveName) != null) ", the game's own" else "")
    }

    private fun deviceLabel(d: DeviceChoice) = when (d) {
        DeviceChoice.BuiltIn -> "built in"; DeviceChoice.None -> "none"; is DeviceChoice.Pad -> d.name
    }

    /** The ROM's MD5 in .z64 order (as the library keys games), when the start didn't say it. Reads the whole ROM: off the main thread. */
    private fun romMd5(rom: String): String? = try {
        File(rom).inputStream().buffered(256 * 1024).use { com.joeysretro.joey64.launcher.library.RomReader.read(it)?.md5 }
    } catch (e: Exception) { JLog.e(TAG, "controls: can't hash the ROM for Auto pak: $e"); null }

    /** The player [dev] plays as, or -1. A controller seen for the first time may be given to a free player. */
    private fun playerFor(dev: InputDevice?, id: Int): Int {
        playerOfDevice[id]?.let { return it }
        // Keyboards and adb's virtual keys play as player 1 (a key nobody binds still goes on to Android).
        if (dev == null || !PadDevices.isPad(dev)) return 0
        // Not now on the new controller card for one seen before: unused until it connects again.
        if (dev.descriptor in notNowThisPlay) { playerOfDevice[id] = -1; return -1 }
        var p = controls.players.indexOfFirst { it.device is DeviceChoice.Pad && PadDevices.matches(it.device, dev) }
        val builtIn = PadDevices.isBuiltIn(dev)
        // This device's own controls play only as the player set to Built-in controls: with none, they play as no one.
        if (p < 0 && builtIn) p = controls.players.indexOfFirst { it.device == DeviceChoice.BuiltIn }
        else if (p < 0) p = claimFreePlayer(dev)
        playerOfDevice[id] = p
        // One waiting for the new controller card is marked seen by its answer.
        if (!builtIn) { padNames[id] = dev.name; if (!newPadPending(id)) controlsStore.markPadSeen(dev.descriptor) }
        val ids = "%04x:%04x".format(dev.vendorId, dev.productId)
        if (p >= 0) JLog.i(TAG, "controls: ${dev.name} ($ids) is player ${p + 1}")
        else JLog.i(TAG, "controls: ${dev.name} ($ids) is no player's" + if (builtIn) " (no player has Built-in controls)" else "")
        return p
    }

    /**
     * A controller no player has: with no built-in pad here (a phone), it plays as the built-in player;
     * else, the first time Joey64 sees it, the first player with None gets it, Auto mapped, saved to All games so it stays
     * theirs. One seen before ([ControlsStore.padSeen]) whose player was set to None stays unused.
     */
    private fun claimFreePlayer(dev: InputDevice): Int {
        val builtInPlayer = controls.players.indexOfFirst { it.device == DeviceChoice.BuiltIn }
        if (builtInPlayer >= 0 && PadDevices.builtIn() == null &&
            playerOfDevice.values.none { it == builtInPlayer }) return builtInPlayer
        if (controlsStore.padSeen(dev.descriptor)) return -1
        // Ask once / Ask every time (approved newpad3 mockup 2026-11-17): the card asks which player it is. Not in a
        // netplay room: the card pauses the game, which stops the room for everyone (release audit 2026-11-22), so
        // there a new pad takes the first free player as with Don't ask.
        if (gameLoaded && overlay != null && !inNetRoom() && controlsStore.connecting().ask != com.joeysretro.joey64.controls.AskWhen.Never) {
            offerNewPad(dev.id, event = false); fpsHandler.post { showNextCard() }
            return -1
        }
        val free = controls.players.indexOfFirst { it.device == DeviceChoice.None }
        if (free < 0) return -1
        val choice = PadDevices.choiceFor(dev)
        val all = controlsStore.all()
        val setup = PadDevices.autoSetup(dev, choice, keep = all.players[free])
        controlsStore.saveAll(all.with(free, setup))
        controls = controls.with(free, if (currentSaveName.isNotEmpty() && controlsStore.game(currentSaveName) != null) controls.players[free].copy(device = choice) else setup)
        players[free].setup = controls.players[free]
        applyTurbo()
        if (portsPresent and (1 shl free) == 0) {
            portsPresent = portsPresent or (1 shl free)
            NativeBridge.setPortsPresent(portsPresent)
        }
        JLog.i(TAG, "controls: new controller ${dev.name} given to player ${free + 1}")
        return free
    }

    private val currentSaveName get() = states?.saveName ?: ""

    /**
     * Controls changed over the paused game: the players, hotkeys and ports follow at once, and a pak
     * change reaches the core with the next frame (it reads the pak options again when they change).
     */
    private fun reloadControls() {
        val save = currentSaveName
        controls = controlsStore.forGame(save.ifEmpty { null })
        hotkeys = controlsStore.hotkeys()
        applyRewind()
        if (slowMotion) NativeBridge.setSlowMotion(hotkeys.slowSpeed)
        for (i in 0..3) { players[i].setup = controls.players[i]; players[i].release(); push(i) }
        applyTurbo()
        playerOfDevice.clear()
        var ports = 1
        for (i in 1..3) if (controls.players[i].device != DeviceChoice.None) ports = ports or (1 shl i)
        if (ports != portsPresent) { portsPresent = ports; NativeBridge.setPortsPresent(ports) }
        val md5 = gameMd5
        val rumbleGames by lazy { PakDb.load(this) }
        val cpOnlyGames by lazy { PakDb.loadControllerOnly(this) }
        val transferGames by lazy { PakDb.loadTransferAuto(this) }
        for (i in 0..3) {
            val p = controls.players[i]
            val pak = PakDb.resolve(p.pak, rumbleGames, transferGames, cpOnlyGames, md5)
            // Live: the game is loaded, and setOption writes the option table the emulation thread reads unlocked.
            if (pak != paks[i]) { paks[i] = pak; NativeBridge.setOptionsLive(arrayOf("mupen64plus-pak${i + 1}"), arrayOf(pak.core!!)) }
        }
        applyTouch(fresh = false)
        JLog.i(TAG, "controls: changed in game, " + controls.players.mapIndexed { i, p -> "P${i + 1} ${deviceLabel(p.device)} (${paks[i].name})" }.joinToString(", "))
    }
    private val paks = Array(4) { Pak.Controller }
    /** Each player's Transfer Pak as the game started with it (PakSummary.transferKey); null before the start read it. */
    @Volatile private var tpakAtStart: List<String>? = null
    private var lastRom: String? = null

    // ---- Controller disconnected (approved problem screens, scratchpad build_problems2.py, 2026-11-08, screen 4) ------
    // A controller a player was playing on goes (its battery, out of range, unplugged): the game pauses with a popup and
    // carries on by itself when it comes back. Built-in controls never "go". Use built-in controls puts the device's own
    // controls on that player for now (Auto mapped, nothing saved); Carry on without them (players 2-4) unplugs their port
    // for now. Either way the player's own controller, when it comes back, has them again.
    /** External controllers' names by device id, as they last sent input (the device is gone by the time it's removed). */
    private val padNames = HashMap<Int, String>()
    /** The player the game waits for (Controller disconnected is up), or -1. */
    private var waitingPlayer = -1
    /** Players on the built-in controls for now (Use built-in controls) and players left out for now (Carry on without them). */
    private val onBuiltIn = HashSet<Int>()
    private val leftOut = HashSet<Int>()
    /** Of [leftOut], the players whose port this unplugged (only those are plugged back in). */
    private val unplugged = HashSet<Int>()

    private val padListener = object : android.hardware.input.InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) { padConnected(id) }
        override fun onInputDeviceRemoved(id: Int) { padGone(id) }
        override fun onInputDeviceChanged(id: Int) {}
    }

    private fun padGone(id: Int) {
        newPadQueue.removeAll { it.first == id }
        if (newPadShown?.first == id) {
            JLog.i(TAG, "controls: ${newPadShown?.second?.pad?.name} went while its card was up")
            newPadShown = null
            if (overlay?.screen is com.joeysretro.joey64.launcher.ingame.PauseScreen.NewPad) closeMenu()
        }
        val p = playerOfDevice.remove(id) ?: return
        val name = padNames.remove(id) ?: return
        if (p < 0 || playerOfDevice.values.any { it == p }) { JLog.i(TAG, "controls: $name went, player ${p + 1} still has a controller"); return }
        if (!gameLoaded || quitting || destroyed || overlay == null || p in leftOut) {
            JLog.i(TAG, "controls: $name went (player ${p + 1}), no game to pause (loaded $gameLoaded, overlay ${overlay != null})"); return
        }
        JLog.i(TAG, "controls: $name (player ${p + 1}) disconnected, the game waits for it")
        players[p].release(); push(p)
        waitingPlayer = p
        if (!menuOpen) openMenu(force = true)
        val builtIn = PadDevices.builtIn() != null
        overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Disconnected(p, name, builtIn,
            touch = !builtIn && p == 0 && touch != null && touchSettings.show != com.joeysretro.joey64.touch.TouchShow.Never)
    }

    /** The player whose own controller [dev] is (Controls' Controller row), without giving it to a free player. */
    private fun ownerOf(dev: InputDevice): Int {
        val p = controls.players.indexOfFirst { it.device is DeviceChoice.Pad && PadDevices.matches(it.device, dev) }
        if (p >= 0) return p
        // A phone: a controller plays as the built-in player (claimFreePlayer).
        val b = controls.players.indexOfFirst { it.device == DeviceChoice.BuiltIn }
        return if (b >= 0 && PadDevices.builtIn() == null && (b == waitingPlayer || b in onBuiltIn || b in leftOut)) b else -1
    }

    /** True when [id] is a player's own controller coming back (Controller disconnected, or one they're without for now). */
    private fun padBack(id: Int): Boolean {
        val dev = InputDevice.getDevice(id) ?: return false
        if (!PadDevices.isPad(dev) || PadDevices.isBuiltIn(dev)) return false
        val p = ownerOf(dev)
        if (p < 0 || (p != waitingPlayer && p !in onBuiltIn && p !in leftOut)) return false
        JLog.i(TAG, "controls: ${dev.name} is back, player ${p + 1} plays on it again")
        playerOfDevice[id] = p; padNames[id] = dev.name
        playerBack(p, id)
        return true
    }

    // ---- New controller (approved newpad3 mockup 2026-11-17, joey64-notes docs/img/newpad3.png, "Approve as drawn") ----
    // A controller that connects during a game: the new controller card over the paused game (screen 4), as Controls ›
    // Connecting says; one seen before with Ask once or Don't ask gets the pop-up (screen 7). A player's controller that
    // isn't there when the game starts: Controller disconnected's card, "<name> isn't connected" (screen 8).
    /** Controllers waiting for the card, with whether each connected during the game. */
    private val newPadQueue = ArrayList<Pair<Int, Boolean>>()
    /** The card up now: its device id and card. */
    private var newPadShown: Pair<Int, com.joeysretro.joey64.controls.NewPadCard>? = null
    /** Descriptors left unused this time (Not now on a controller seen before), until they connect again. */
    private val notNowThisPlay = HashSet<String>()
    /** Players whose controller wasn't connected at the start, still to ask about. */
    private val missingAtStart = ArrayList<Int>()

    private fun newPadPending(id: Int) = newPadShown?.first == id || newPadQueue.any { it.first == id }

    /** In a netplay room (joined or hosted this play): no card may pause the game. */
    private fun inNetRoom() = net.inRoom || roomPlay

    private fun offerNewPad(id: Int, event: Boolean) {
        if (!newPadPending(id)) newPadQueue += id to event
    }

    private fun padConnected(id: Int) {
        if (padBack(id)) return
        if (!gameLoaded || quitting || destroyed || overlay == null) return
        val d = InputDevice.getDevice(id) ?: return
        if (!PadDevices.isPad(d) || PadDevices.isBuiltIn(d)) return
        notNowThisPlay.remove(d.descriptor)
        if (!inNetRoom() && com.joeysretro.joey64.controls.NewPads.cardFor(controlsStore, d, event = true) != null) {
            offerNewPad(id, event = true); showNextCard(); return
        }
        playerOfDevice.remove(id)
        val p = playerFor(d, id)
        if (newPadPending(id)) return
        pop("pad", InGamePopup.Kind.PadConnected, "Controller connected", d.name ?: "Controller",
            if (p >= 0) "Player ${p + 1}" else "Not playing · set it in Controls")
    }

    /** The game is up: players whose controller isn't connected, and new controllers already connected. Not for dev starts. */
    private fun connectAtStart() {
        if (!gameLoaded || quitting || destroyed || overlay == null) return
        val dev = listOf("env", "opts", "renderer", "driver").any { intent?.hasExtra(it) == true }
        val net = android.system.Os.getenv("N64_NETPLAY") != null || android.system.Os.getenv("N64_NETPLAY_SERVE") != null
        if (dev || net) return
        val pads = PadDevices.pads()
        for (p in 0..3) {
            val d = controls.players[p].device
            if (d is DeviceChoice.Pad && pads.none { PadDevices.matches(d, it) }) missingAtStart += p
        }
        for (d in pads) if (!PadDevices.isBuiltIn(d) && playerOfDevice[d.id] == null) offerNewPad(d.id, event = false)
        showNextCard()
    }

    /** The next card waiting (a missing controller first, then a new one), when no other card or menu is up. */
    private fun showNextCard(): Boolean {
        val o = overlay ?: return false
        if (!gameLoaded || quitting || destroyed || newPadShown != null || waitingPlayer >= 0 || menuOpen || o.screen != null) return false
        while (missingAtStart.isNotEmpty()) {
            val p = missingAtStart.removeAt(0)
            val d = controls.players[p].device as? DeviceChoice.Pad ?: continue
            if (PadDevices.pads().any { PadDevices.matches(d, it) }) continue
            JLog.i(TAG, "controls: ${d.name} (player ${p + 1}) isn't connected at the start, the game waits for it")
            players[p].release(); push(p)
            waitingPlayer = p
            openMenu(force = true)
            val builtIn = PadDevices.builtIn() != null
            o.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Disconnected(p, d.name, builtIn,
                touch = !builtIn && p == 0 && touch != null && touchSettings.show != com.joeysretro.joey64.touch.TouchShow.Never, atStart = true)
            return true
        }
        while (newPadQueue.isNotEmpty() && !inNetRoom()) {
            val (id, event) = newPadQueue.removeAt(0)
            val d = InputDevice.getDevice(id) ?: continue
            if (d.descriptor in notNowThisPlay) continue
            val card = com.joeysretro.joey64.controls.NewPads.cardFor(controlsStore, d, event) ?: continue
            JLog.i(TAG, "controls: ${card.title}, asking which player (focus Player ${card.focus + 1})")
            newPadShown = id to card
            openMenu(force = true)
            o.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.NewPad(card)
            return true
        }
        return false
    }

    /** The card's answer: a player (0-3), or -1 for Not now (B too). */
    private fun newPadAnswer(player: Int) {
        val (id, card) = newPadShown ?: return
        newPadShown = null
        com.joeysretro.joey64.controls.NewPads.answer(controlsStore, card, InputDevice.getDevice(id), player)
        if (player < 0) {
            if (card.seen) notNowThisPlay += card.pad.descriptor
            playerOfDevice.remove(id)
            JLog.i(TAG, "controls: ${card.pad.name} not now")
        } else {
            JLog.i(TAG, "controls: ${card.pad.name} is player ${player + 1}")
            reloadControls()
        }
        closeMenu()
    }

    /** [p]'s own controller ([id]; null from the debug broadcast) is back: they play on it again. */
    private fun playerBack(p: Int, id: Int?) {
        if (onBuiltIn.remove(p)) {
            // The built-in controls go back to whoever they belong to.
            playerOfDevice.entries.removeAll { (d, q) -> q == p && d != id }
            players[p].release(); players[p].setup = controls.players[p]; push(p)
        }
        leftOut.remove(p)
        if (unplugged.remove(p) && portsPresent and (1 shl p) == 0) { portsPresent = portsPresent or (1 shl p); NativeBridge.setPortsPresent(portsPresent) }
        if (p == waitingPlayer) {
            waitingPlayer = -1
            if (overlay?.screen is com.joeysretro.joey64.launcher.ingame.PauseScreen.Disconnected) closeMenu()
        }
    }

    private fun disconnected(choice: com.joeysretro.joey64.launcher.ingame.DisconnectChoice) {
        val p = waitingPlayer
        if (choice == com.joeysretro.joey64.launcher.ingame.DisconnectChoice.Quit) { waitingPlayer = -1; pauseActions.quit(); return }
        if (p < 0) { closeMenu(); return }
        when (choice) {
            com.joeysretro.joey64.launcher.ingame.DisconnectChoice.BuiltIn -> {
                val own = PadDevices.pads().filter { PadDevices.isBuiltIn(it) }
                if (own.isEmpty()) return
                for (d in own) playerOfDevice[d.id] = p
                players[p].release(); players[p].setup = PadDevices.autoSetup(own.first(), DeviceChoice.BuiltIn, keep = controls.players[p])
                onBuiltIn += p
                JLog.i(TAG, "controls: player ${p + 1} on the built-in controls until their controller is back")
            }
            com.joeysretro.joey64.launcher.ingame.DisconnectChoice.Touch -> {
                touch?.showNow()
                JLog.i(TAG, "controls: player 1 on the touch controls until their controller is back")
            }
            com.joeysretro.joey64.launcher.ingame.DisconnectChoice.WithoutThem -> {
                leftOut += p
                players[p].release(); push(p)
                if (portsPresent and (1 shl p) != 0) { unplugged += p; portsPresent = portsPresent and (1 shl p).inv(); NativeBridge.setPortsPresent(portsPresent) }
                JLog.i(TAG, "controls: carrying on without player ${p + 1} until their controller is back")
            }
            else -> {}
        }
        waitingPlayer = -1
        closeMenu()
    }

    // ---- Not enough space to save (approved problem screens, screen 5) ------------------------------------------
    /**
     * A state, a screenshot or the game's own save that couldn't be written ([need] bytes) on a full disk: the popup over
     * the paused game says so. False (nothing shown) when there's room, so the failure was something else.
     */
    private fun noSpace(what: com.joeysretro.joey64.saves.NoSpace.What, need: Long, slot: String? = null, devFree: Long? = null): Boolean {
        if (need <= 0 || overlay == null || quitting || destroyed) return false
        // The game's own save is written to Joey64's own files first (a picked data folder gets a copy).
        val tree = if (what == com.joeysretro.joey64.saves.NoSpace.What.GameSave) null
            else com.joeysretro.joey64.launcher.storage.DataFolders.current(this).treeUri
        val place = com.joeysretro.joey64.saves.NoSpace.placeOf(this, tree)
        val free = devFree ?: place.free
        if (free == null || !com.joeysretro.joey64.saves.NoSpace.isFull(free, need)) return false
        JLog.e(TAG, "not enough space: ${what.name} needs $need bytes, ${place.name} has $free free")
        if (!menuOpen) openMenu(force = true)
        overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.NoSpace(com.joeysretro.joey64.saves.NoSpace.text(what, slot, place.name, free, need))
        return true
    }

    /** The game's own save couldn't be written: says so once, until a write works again. */
    private var saveRamWarned = false
    private val saveRamWatch = object : Runnable {
        override fun run() {
            if (destroyed) return
            val failed = NativeBridge.saveRamFailed()
            if (failed == 0L) saveRamWarned = false
            else if (!saveRamWarned && !menuOpen) saveRamWarned = noSpace(com.joeysretro.joey64.saves.NoSpace.What.GameSave, failed)
            fpsHandler.postDelayed(this, SAVE_RAM_POLL_MS)
        }
    }

    /** Switches the rumble and stick calibration to the pad that sent the last input. */
    private fun usePad(dev: InputDevice?, id: Int): Controllers.StickCal {
        stickCals[id]?.let { padDeviceId = id; return it }
        padDeviceId = id
        val profile = Controllers.profile(dev)
        JLog.i(TAG, "Pad: ${dev?.name} (" + "%04x:%04x".format(dev?.vendorId ?: 0, dev?.productId ?: 0) + "), profile ${profile.name}" +
            (profile.toggle?.let { ", layout toggle " + (if (Controllers.toggleXbox(profile)) "Xbox" else "Nintendo") + " (${it.first})" } ?: "") +
            Controllers.padDetails(dev))
        return Controllers.StickCal(dev?.descriptor ?: "unknown", profile.stickReach, stickPrefs).also { stickCals[id] = it }
    }

    private fun push(p: Int) {
        val input = players[p]
        NativeBridge.setButtons(p, input.buttons())
        val (x, y) = input.stick()
        NativeBridge.setAnalog(p, 0, PlayerInput.toRetroStick(x), PlayerInput.toRetroStick(y))
        showHeld()
    }

    private fun releaseAll() {
        stopMacros()
        for (p in 0..3) { players[p].release(); push(p); NativeBridge.setAnalog(p, 1, 0, 0) }
    }

    /** A save state or load: buttons held by a tap and macros playing stop; buttons pressed now stay pressed. */
    private fun letGoHeld() {
        stopMacros()
        for (p in 0..3) { players[p].releaseLatches(); push(p) }
    }

    // Controls extras' Tap to hold (approved mockup 2026-11-08): a pop-up at the top left while a button is held by a tap.
    private var heldShown = ""
    private fun showHeld() {
        val parts = (0..3).mapNotNull { p -> players[p].latched().takeIf { it.isNotEmpty() }?.let { p to it } }
        val text = when (parts.size) {
            0 -> ""
            1 -> com.joeysretro.joey64.controls.heldLine(parts[0].second)
            else -> parts.joinToString(" · ") { (p, held) -> "P${p + 1} " + com.joeysretro.joey64.controls.latchLabel(held) } + " held"
        }
        if (text == heldShown) return
        heldShown = text
        if (text.isEmpty()) overlay?.hide("held", waiting = true)
        else overlay?.show(InGamePopup("held", InGamePopup.Kind.Held, "", text, "", compact = true,
            stayMs = com.joeysretro.joey64.launcher.ingame.PopupQueue.UNTIL_HIDDEN))
    }

    // Controls extras' macros: the key that started one, with its player, until it's let go.
    private val macroKeys = HashMap<Int, Pair<Int, com.joeysretro.joey64.controls.Macro>>()

    /** A macro's button for player [p] ([withHotkey]: the hotkey button is held): it plays, and the game never sees the button. */
    private fun macroKey(p: Int, code: Int, event: KeyEvent, withHotkey: Boolean): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val m = com.joeysretro.joey64.controls.Macro.forKey(players[p].setup.macros, code, withHotkey) ?: return false
        if (event.repeatCount == 0) {
            playMacro(p, m)
            macroKeys[code] = p to m
        }
        return true
    }

    private fun playMacro(p: Int, m: com.joeysretro.joey64.controls.Macro) {
        if (com.joeysretro.joey64.achievements.RaHardcore.on()) { popHardcore("Macros"); return } // Joey 2026-11-16: no macros in Hardcore
        val steps = m.steps
        NativeBridge.playMacro(p, IntArray(steps.size) { steps[it].mask() },
            IntArray(steps.size) { PlayerInput.toRetroStick(steps[it].stickXY().first) },
            IntArray(steps.size) { PlayerInput.toRetroStick(steps[it].stickXY().second) },
            IntArray(steps.size) { steps[it].frames }, m.repeat)
        JLog.i(TAG, "macro: P${p + 1} ${m.name}, ${steps.size} steps${if (m.repeat) ", repeating" else ""}")
    }

    /** A macro's button let go: a repeating one stops after its pass; a Once one plays to its end. */
    private fun macroKeyUp(code: Int): Boolean {
        val (p, m) = macroKeys.remove(code) ?: return false
        if (m.repeat) NativeBridge.stopMacro(p, false)
        return true
    }

    private fun stopMacros() {
        for (p in 0..3) NativeBridge.stopMacro(p, true)
        macroKeys.clear()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The release of a button the Controls remap popup took (it closed on the press).
        if (com.joeysretro.joey64.launcher.controls.PadCapture.swallowRelease(event)) return true
        if (event.action == KeyEvent.ACTION_DOWN && (KeyEvent.isGamepadButton(event.keyCode) ||
                event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD)) touch?.controllerUsed()
        val inGame = states != null && overlay != null && mine
        // A pad with an Xbox/Nintendo toggle (the RG477M): its Xbox position's codes as its Nintendo position sends them.
        Controllers.faceSeenLine(event)?.let { JLog.i(TAG, it) }
        val code = Controllers.faceKey(event.device, event.keyCode)
        val down = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        // The pause menu's button on its own (Back by default) opens it on release (the menu's window would get the release otherwise).
        if (inGame && hotkeys.isAlone(HotkeyAction.PauseMenu) && code == hotkeys.keys[HotkeyAction.PauseMenu]) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && !quitting && !menuOpen) openMenu()
            return true
        }
        // A macro's button let go (the hotkey button may already be up).
        if (event.action == KeyEvent.ACTION_UP && macroKeyUp(code)) return true
        // The hotkey button held makes the next button a hotkey.
        if (inGame && code == hotkeys.button) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) { hotkeyHeld = true; hotkeyHatX = 0; hotkeyHatY = 0 }
                KeyEvent.ACTION_UP -> { hotkeyHeld = false; gameSharkRelease(); fastForwardRelease(); slowMotionRelease(); rewindRelease(); volumeKey(0, false) }
            }
            return true
        }
        if (inGame && gameSharkDown && event.action == KeyEvent.ACTION_UP && code == hotkeys.keys[HotkeyAction.GameShark]) {
            gameSharkRelease()
            return true
        }
        // A volume hotkey let go after the hotkey button: the repeat stops.
        if (volumeHeld != 0 && event.action == KeyEvent.ACTION_UP &&
            (code == hotkeys.keys[HotkeyAction.VolumeUp] || code == hotkeys.keys[HotkeyAction.VolumeDown])) volumeKey(0, false)
        if (hotkeyHeld && inGame) {
            // Both edges are the hotkey's: the game never sees them.
            hotkeys.actionFor(code, alone = false)?.let { action -> hotkeyEdge(action, event, down); return true }
            // Then a macro on hotkey button + this button.
            val hp = playerFor(event.device, event.deviceId)
            if (hp >= 0 && macroKey(hp, code, event, withHotkey = true)) return true
        }
        val p = playerFor(event.device, event.deviceId)
        if (KeyEvent.isGamepadButton(code) || p >= 0) usePad(event.device, event.deviceId)
        // A hotkey on its own wins over the game's use of the button (the Hotkeys popup says so).
        if (inGame) hotkeys.actionFor(code, alone = true)?.let { action -> hotkeyEdge(action, event, down); return true }
        // A macro's button wins over the player's use of it.
        if (inGame && p >= 0 && macroKey(p, code, event, withHotkey = false)) return true
        // In game, a pad button no player uses goes nowhere: handed to super, Android's fallback turns an unhandled
        // BUTTON_B into BACK, the pause menu (Xbox-layout pads: Auto map leaves B and Y unused; tester's Mini V2,
        // 2026-11-12). BACK itself isn't a gamepad button, so the menu still opens on Back.
        if (inGame && KeyEvent.isGamepadButton(code) && (p < 0 || !players[p].uses(code))) return true
        if (p < 0 || !players[p].uses(code)) return super.dispatchKeyEvent(event)
        if (inputLat && event.action == KeyEvent.ACTION_DOWN) noteInputLat(event.eventTime, true, 0)
        // A trigger whose axis has moved on this pad gives the game its axis only (past Stick feel's threshold): its
        // key copy, which many pads send at the first touch, would press L2 / R2 on a light squeeze (ARMSX2
        // 00c63a3b54, Joey 2026-11-21: "Add it, test pads"). A release always goes through.
        val axisTrigger = (code == KeyEvent.KEYCODE_BUTTON_L2 && analogTriggers[event.deviceId]?.and(1) == 1) ||
            (code == KeyEvent.KEYCODE_BUTTON_R2 && analogTriggers[event.deviceId]?.and(2) == 2)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (!axisTrigger) players[p].key(code, true)
            KeyEvent.ACTION_UP -> players[p].key(code, false)
        }
        push(p)
        return true
    }

    /** Per input device: bit 1 its left trigger's axis has moved, bit 2 its right one's (a pad's key copies are then left out). */
    private val analogTriggers = HashMap<Int, Int>()

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK ||
            event.action != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(event)
        val cal = usePad(event.device, event.deviceId)
        // A stick pushed on purpose (not a resting stick's jitter) hides When no controller's touch controls.
        if (touch != null && listOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_HAT_X,
                MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER).any { kotlin.math.abs(event.getAxisValue(it)) > 0.5f })
            touch?.controllerUsed()
        if (hotkeyHeld) hotkeyHat(event)
        if (inputLat) noteInputLat(if (event.historySize > 0) event.getHistoricalEventTime(0) else event.eventTime, false, event.historySize)
        val p = playerFor(event.device, event.deviceId)
        if (p < 0) return super.dispatchGenericMotionEvent(event)
        val input = players[p]
        val (lx, ly) = cal.apply(0, event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y))
        val (rx, ry) = cal.apply(1, event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RZ))
        input.setAxes(lx, ly, rx, ry)
        // With the hotkey button held, left / right change the slot and don't reach the game (as the D-pad keys).
        input.setHat(if (hotkeyHeld) 0f else event.getAxisValue(MotionEvent.AXIS_HAT_X), if (hotkeyHeld) 0f else event.getAxisValue(MotionEvent.AXIS_HAT_Y))
        // Analog triggers (Thor reports both keys and BRAKE/GAS axes).
        val lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        triggerDepth[0] = lt; triggerDepth[1] = rt
        val moved = (if (lt > 0.05f) 1 else 0) or (if (rt > 0.05f) 2 else 0)
        val was = analogTriggers[event.deviceId] ?: 0
        if (moved and was.inv() != 0) {
            analogTriggers[event.deviceId] = was or moved
            JLog.i(TAG, "pad ${event.deviceId}: ${if (moved and 1 != 0 && was and 1 == 0) "L2" else "R2"} has an axis: its key copy left out")
            // A key copy pressed before its axis moved: let go of it, the axis holds the trigger from now on.
            if (moved and 1 != 0) input.key(KeyEvent.KEYCODE_BUTTON_L2, false)
            if (moved and 2 != 0) input.key(KeyEvent.KEYCODE_BUTTON_R2, false)
        }
        // Adaptive fast-forward: the speed follows the trigger, which the game doesn't see while it's the hotkey.
        val ffAdaptive = ffHeld && hotkeys.ffAdaptive
        if (ffAdaptive) NativeBridge.setFastForwardSpeed(adaptivePercent())
        input.setTriggers(if (ffAdaptive && ffTrigger() == 0) 0f else lt, if (ffAdaptive && ffTrigger() == 1) 0f else rt)
        push(p)
        return true
    }

    /** Settings › Audio for this game (All games, then its own Volume and Latency), before the stream opens. */
    private fun applyAudio(saveName: String?) {
        val a = com.joeysretro.joey64.audio.AudioStore(this).forGame(saveName)
        audioAtStart = a
        audioApps = a.on(com.joeysretro.joey64.audio.AudioKey.Apps)
        NativeBridge.setAudio(a.volume, a.on(com.joeysretro.joey64.audio.AudioKey.Mute), a.on(com.joeysretro.joey64.audio.AudioKey.Mono), a.ffMode, a.latencyMode)
        JLog.i(TAG, "audio settings: volume ${a.volume}, ff sound ${a.ffMode}, latency ${a.latencyMode}, play alongside other apps $audioApps")
        requestAudioFocus()
    }

    // Play alongside other apps Off (the default): the game takes audio focus while it runs, so other apps'
    // music or video pauses; given back on pause. On: no request, both mix.
    private var audioApps = false
    private var focusRequest: android.media.AudioFocusRequest? = null
    private fun requestAudioFocus() {
        if (audioApps || focusRequest != null) return
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_GAME)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setOnAudioFocusChangeListener { JLog.i(TAG, "audio focus change $it") }
            .build()
        val r = am.requestAudioFocus(req)
        focusRequest = req
        JLog.i(TAG, "audio focus requested: $r")
    }
    private fun abandonAudioFocus() {
        val req = focusRequest ?: return
        (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).abandonAudioFocusRequest(req)
        focusRequest = null
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        // A picker opened from the pause menu (GameOverlay.results).
        if (overlay?.results?.dispatchResult(requestCode, resultCode, data) != true) super.onActivityResult(requestCode, resultCode, data)
    }

    // In a netplay room (2026-11-10, Joey: "Wi-Fi lock first"): a low-latency Wi-Fi lock while the game is on screen,
    // so Wi-Fi power saving doesn't hold packets back (Android applies it only in the foreground with the screen on).
    // Dev: --es env N64_NETPLAY_WIFILOCK=0 leaves it off, for the before/after.
    private var netWifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private fun netWifiLockSet(on: Boolean) {
        val inRoom = android.system.Os.getenv("N64_NETPLAY") != null || android.system.Os.getenv("N64_NETPLAY_SERVE") != null
        if (on && (!inRoom || android.system.Os.getenv("N64_NETPLAY_WIFILOCK") == "0")) return
        runCatching {
            if (on && netWifiLock == null) {
                val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java) ?: return
                netWifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "joey64:netplay").apply {
                    setReferenceCounted(false)
                }
            }
            val l = netWifiLock ?: return
            if (on && !l.isHeld) {
                l.acquire()
                JLog.i(TAG, "netplay: low-latency Wi-Fi lock on (held ${l.isHeld})")
            } else if (!on && l.isHeld) {
                l.release()
                JLog.i(TAG, "netplay: low-latency Wi-Fi lock off")
            }
        }.onFailure { JLog.e(TAG, "netplay: Wi-Fi lock: $it") }
    }

    override fun onPause() {
        super.onPause()
        if (!mine) return
        netWifiLockSet(false)
        sessionMark(); session?.writeOpen()
        NativeBridge.pause(true)
        abandonAudioFocus()
        // Leaving the game (quit, home, another app): its progress is kept while rc_client still has it.
        raCache?.keepNow()
        reportGameState(loading = false, playing = false)
        stats?.stop()
        resumed = false
        touch?.setActive(false)
        hotkeyHeld = false
        fastForwardRelease()
        slowMotionRelease()
        rewindRelease()
        volumeKey(0, false)
        fpsHandler.removeCallbacks(gameSharkUp)
        gameSharkButton(false)
        setRumbling(0)
        stickCals.values.forEach { it.save(stickPrefs) }
    }
    /**
     * Gone to the background (home, another app, the screen off): Android may end the process there without a word,
     * so the recovery state is written now (Settings › Saves › Recovery state's rules, as [recoveryTick]) and a picked
     * data folder gets the save RAM: coming back to Joey64 after that offers to carry on from here, not from the last
     * timed recovery state minutes before (queue "Never lose progress when Android kills the app", 2026-11-13).
     */
    override fun onStop() {
        super.onStop()
        held?.hide()
        if (mine && started && speed != null) { speedLast = null; speedFlush(false); speed?.ended() }
        if (!mine || isFinishing || quitting || !started) return
        val st = states ?: return
        if (recoveryEveryMs > 0 && !startLoadFailed && !roomPlay) stateHandler.post {
            if (st.saveRecovery()) JLog.i(TAG, "recovery state: written on going to the background")
        }
        stateHandler.post { com.joeysretro.joey64.launcher.storage.SrmSync.push(this, st.saveName) }
    }
    override fun onResume() {
        super.onResume()
        if (!mine) return
        if (!menuOpen && !hotkeyPaused) NativeBridge.pause(false)
        if (started) { reportGameState(loading = false); requestAudioFocus() }
        if (started && speedTry != null && speedDrew) speed?.again()
        session?.tick(false, NativeBridge.framesRun(), NativeBridge.framesDrawn(), android.os.SystemClock.elapsedRealtime())
        resumed = true
        touch?.setActive(touchActive())
        netWifiLockSet(true)
        stats?.start()
        setRumbling(NativeBridge.rumble())
        if (!rumbleWatcher.isAlive) rumbleWatcher.start()
    }
    // Item 31 (dev, --es env N64_GAME_STATE=1, Android 13+): tell the system a game is being played
    // (GameManager.setGameState, as ARMSX2 and DroidDeck do), so OEM game modes and the thermal/power
    // policy know. Interruptible gameplay while running, loading during the ROM load, none while paused.
    private fun reportGameState(loading: Boolean, playing: Boolean = true) {
        if (Build.VERSION.SDK_INT < 33 || android.system.Os.getenv("N64_GAME_STATE") != "1") return
        val gm = getSystemService(android.app.GameManager::class.java) ?: return
        val mode = if (playing) android.app.GameState.MODE_GAMEPLAY_INTERRUPTIBLE else android.app.GameState.MODE_NONE
        try {
            gm.setGameState(android.app.GameState(loading, mode))
            JLog.e(TAG, "game state: loading $loading, mode $mode")
        } catch (e: Exception) {
            JLog.e(TAG, "setGameState: $e")
        }
    }

    override fun onDestroy() {
        if (trialRestarting) { super.onDestroy(); return }
        held?.hide(); held = null
        if (isFinishing) {
            com.joeysretro.joey64.video.TexConvert.playing(this, null)
            fpsHandler.removeCallbacks(sessionTick)
            session?.let { s ->
                netStats.json()?.let { s.put("netplay", it) }
                if (netNames.isNotEmpty()) s.put("maskNames", org.json.JSONArray(netNames.values.toList()))
                // The mean work a frame (not fast-forward) for the reports' driver ranking (approved drvspeed, screen 4)
                NativeBridge.workTotals(speedBuf)
                val wf = speedBuf[1] - workBase[1]
                if (wf > 0) s.put("workMs", Math.round((speedBuf[0] - workBase[0]) / wf * 100) / 100.0)
                // A core stuck in a frame (endStuckProcess follows): its own ending, not a quit.
                if (gameLoaded && NativeBridge.coreStuckMs() > STUCK_MS) sessionEnd = "stuck"
                s.finish(sessionEnd)
            }
            session = null
        }
        fpsHandler.removeCallbacks(saveRamWatch)
        getSystemService(android.hardware.input.InputManager::class.java)?.unregisterInputDeviceListener(padListener)
        uiScope.cancel()
        debugStick?.let { unregisterReceiver(it) }
        debugButtons?.let { unregisterReceiver(it) }
        debugDump?.let { unregisterReceiver(it) }
        debugLogs?.let { unregisterReceiver(it) }
        debugProblem?.let { unregisterReceiver(it) }
        debugRaEvent?.let { unregisterReceiver(it) }
        debugHardcore?.let { unregisterReceiver(it) }
        destroyed = true
        stats?.destroy()
        netChip?.remove()
        raPopups?.stop()
        raCache?.stop()
        touch?.destroy()
        overlay?.destroy()
        // Quit before the trial saw a picture: not the GPU's doing, so the start doesn't count against Auto's trial (code
        // audit 3, p1 L7). Since the one-try trial (2026-11-15) a counted start steps the driver down at once, and a black
        // screen is the watch's to fail (trialNoPicture), so every quit is let off, not only one in the first 5 s:
        // 2026-11-15 night, Odin 2 Portal, a start ended 1 s in (frames drawn, not yet lit) moved ARMSX2 to Gen8.
        if (isFinishing && gameLoaded && trial != null) cancelTrial()
        val stopped = if (owner === this) NativeBridge.stop().also { owner = null } else if (owner == null && !switching) NativeBridge.stop() else true
        if (owner == null && !switching) com.joeysretro.joey64.achievements.RaHardcore.gameEnded()
        if (!stopped) endStuckProcess()
        // The save RAM and a 64DD disk save written as the core stopped, into a picked data folder: on [gameIo], after
        // any start still copying and before the next one (a disk save is 64.9 MB; code audit 2, finding 1).
        startSave?.let { save -> val app = applicationContext; gameIo.execute { com.joeysretro.joey64.launcher.storage.SrmSync.push(app, save) } }
        // Closed on purpose (Back, or a frontend sending the next game): no recovery state is due.
        if (isFinishing && states != null) { stateHandler.removeCallbacks(recoveryTick); if (!keepRecovery && !roomPlay) states?.clearRecovery() }
        if (states != null) stateThread.quitSafely()
        super.onDestroy()
    }

    /**
     * Debug builds only: lets adb move the analog sticks, which `input keyevent` cannot, for
     * scripted testing. Example (left stick down for 200 ms):
     *   adb shell am broadcast -a com.joeysretro.joey64.STICK --ei x 0 --ei y 32767 --ei ms 200
     * --ei stick 1 targets the right stick (C buttons).
     */
    private var debugStick: BroadcastReceiver? = null
    /**
     * Debug builds: presses player 1's N64 buttons from adb (2026-11-14: adb's key presses come from a device that's no
     * player's, so scripted checks couldn't play; the Paper Mario glitch). Through the touch controls' path, so the
     * game sees them as player 1's:
     *   adb shell am broadcast -a com.joeysretro.joey64.BUTTONS --es press A,DRight --ei ms 300
     * Names: A B Z Start L R DUp DDown DLeft DRight CUp CDown CLeft CRight. Held for ms (default 150), then let go;
     * a new press replaces the one held. With the STICK broadcast, enough to walk and fight.
     */
    private var debugButtons: BroadcastReceiver? = null
    private var debugDump: BroadcastReceiver? = null
    private var debugLogs: BroadcastReceiver? = null
    private var debugProblem: BroadcastReceiver? = null
    private var debugRaEvent: BroadcastReceiver? = null
    private var debugHardcore: BroadcastReceiver? = null

    private fun registerDebugStick() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val handler = Handler(Looper.getMainLooper())
        debugStick = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                val stick = i.getIntExtra("stick", 0)
                NativeBridge.setAnalog(0, stick, i.getIntExtra("x", 0), i.getIntExtra("y", 0))
                handler.postDelayed({ NativeBridge.setAnalog(0, stick, 0, 0) }, i.getIntExtra("ms", 150).toLong())
            }
        }
        var buttonsOff: Runnable? = null
        debugButtons = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                val names = (i.getStringExtra("press") ?: "").split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
                var bits = 0
                for (n in names) {
                    val b = com.joeysretro.joey64.controls.N64Button.entries.firstOrNull { it.name.equals(n, ignoreCase = true) }
                    if (b == null) { JLog.e(TAG, "debug BUTTONS: no N64 button $n"); return }
                    bits = bits or (1 shl b.retroBit)
                }
                buttonsOff?.let { handler.removeCallbacks(it) }
                players[0].touchBits = bits; push(0)
                buttonsOff = Runnable { players[0].touchBits = 0; push(0) }.also { handler.postDelayed(it, i.getIntExtra("ms", 150).toLong()) }
            }
        }
        // Approved problem screens (2026-11-08), checks without pulling a controller or filling the disk:
        // adb shell am broadcast -a com.joeysretro.joey64.PROBLEM --es what disconnect [--ei player 1] [--es name "8BitDo Pro 2"]
        //   (the player's controller went), back (it came back), nospace | nospace-shot | nospace-save (a full disk: 1 MB free),
        //   net-paused | net-outofstep | net-left [--es name Sam] (the netplay cards, no room needed), screenshot (the hotkey's).
        debugProblem = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                JLog.i(TAG, "debug PROBLEM: ${i.getStringExtra("what")}")
                val mb = 1024L * 1024
                when (i.getStringExtra("what")) {
                    "disconnect" -> {
                        val p = i.getIntExtra("player", 0).coerceIn(0, 3)
                        // As if this were the player's only controller (on the Thor, player 1 also has the built-in one).
                        playerOfDevice.entries.removeAll { it.value == p }
                        playerOfDevice[-99] = p; padNames[-99] = i.getStringExtra("name") ?: "8BitDo Pro 2"
                        padGone(-99)
                    }
                    "back" -> if (waitingPlayer >= 0) playerBack(waitingPlayer, null) else (onBuiltIn + leftOut).firstOrNull()?.let { playerBack(it, null) }
                    // The screenshot hotkey without the chord (taking the stock preview pictures, 2026-11-12).
                    "screenshot" -> hotkeyScreenshot()
                    "nospace" -> noSpace(com.joeysretro.joey64.saves.NoSpace.What.State, 4 * mb, slotName(currentSlot()), devFree = mb)
                    "nospace-shot" -> noSpace(com.joeysretro.joey64.saves.NoSpace.What.Screenshot, 900 * 1024L, devFree = mb / 2)
                    "nospace-save" -> noSpace(com.joeysretro.joey64.saves.NoSpace.What.GameSave, 290 * 1024L, devFree = 100 * 1024L)
                    // The crash flush's Java path: an uncaught exception on the main thread (the log shows the flush).
                    "crash-java" -> throw RuntimeException("PROBLEM crash-java (debug)")
                    // The netplay cards without a room (F2 step 2 checks): another player's menu is open, the games are
                    // out of step, a player left. Their room-only buttons (Leave) quit to the library as in a room.
                    "net-paused" -> overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.NetPaused(2, i.getStringExtra("name") ?: "Sam")
                    "net-outofstep" -> { if (!menuOpen) openMenu(); overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.NetOutOfStep(true) }
                    "net-left" -> { if (!menuOpen) openMenu(); overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.NetLeft(2, i.getStringExtra("name") ?: "Sam") }
                }
            }
        }
        // adb shell am broadcast -a com.joeysretro.joey64.DUMP --es name snap1  -> files/dumps/snap1.bin
        debugDump = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                // Internal storage: adb and run-as can't read the external files folder on the Flip.
                val dir = File(filesDir, "dumps").apply { mkdirs() }
                NativeBridge.dumpRam(File(dir, "${i.getStringExtra("name") ?: "ram"}.bin").absolutePath)
            }
        }
        // adb shell am broadcast -a com.joeysretro.joey64.LOGS  -> cache/share/joey64-logs.zip (what Settings › About › Share logs sends)
        debugLogs = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                JLog.i(TAG, "log: zipped to ${LogFiles.zip(ctx)}")
            }
        }
        // adb push ev.json /sdcard/Android/data/com.joeysretro.joey64/files/ra_event.json
        // adb shell am broadcast -a com.joeysretro.joey64.RA_EVENT  -> hands that rcheevos event JSON to the
        // listeners as if the core had sent it (pop-up checks without a real unlock); --es file x.json for another file;
        // --ez expired true puts up the Sign-in expired pop-up as a refused kept sign-in would (false takes it back)
        debugRaEvent = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                if (i.hasExtra("expired")) { com.joeysretro.joey64.achievements.RaBridge.debugExpired(i.getBooleanExtra("expired", true)); return }
                // --ez fake true|false: RA_FAKE's made-up account and sets (and leaderboards) in the game's process, whose
                // launcher (RA_FAKE's receiver) may be gone.
                if (i.hasExtra("fake")) { com.joeysretro.joey64.achievements.RaBridge.debugFakeAccount(i.getBooleanExtra("fake", true)); return }
                val f = File(getExternalFilesDir(null), i.getStringExtra("file") ?: "ra_event.json")
                val text = try { f.readText() } catch (e: Exception) { JLog.e(TAG, "RA_EVENT: can't read $f: $e"); return }
                // One event per line, sent a second apart (an unlock, then what follows it).
                val lines = text.lines().filter { it.isNotBlank() }
                lines.forEachIndexed { n, line -> handler.postDelayed({ Thread { com.joeysretro.joey64.achievements.RaBridge.onEvent(line) }.start() }, n * i.getLongExtra("gap", 1000L)) }
            }
        }
        // adb shell am broadcast -a com.joeysretro.joey64.HARDCORE --ez on true|false  -> RetroAchievements Hardcore on or off
        // for the running game (on restarts it; RaHardcore). The Settings row and its pop-ups come with Joey's mockups.
        debugHardcore = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                if (owner == null) { JLog.w(TAG, "HARDCORE: no game running"); return }
                val on = i.getBooleanExtra("on", true)
                runOnUiThread { hardcoreMidGame(on) }
            }
        }
        for ((receiver, action) in listOf(debugStick to "com.joeysretro.joey64.STICK", debugButtons to "com.joeysretro.joey64.BUTTONS", debugDump to "com.joeysretro.joey64.DUMP",
                debugLogs to "com.joeysretro.joey64.LOGS", debugRaEvent to "com.joeysretro.joey64.RA_EVENT",
                debugProblem to "com.joeysretro.joey64.PROBLEM", debugHardcore to "com.joeysretro.joey64.HARDCORE")) {
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else registerReceiver(receiver, filter)
        }
    }

    companion object {
        /** The environment keys Settings set at the last game start (not --es env's), cleared at the next one. */
        private val settingsEnvKeys = HashSet<String>()
        private const val NOTHING_TO_UNDO = "Nothing to undo"
        /** A core in one frame this long is stuck (a frame takes ms): no quit save is taken from it. */
        private const val STUCK_MS = 2000L
        /**
         * The instance whose surface, pause and stop calls reach the native side: the one that loaded the game. A ROM
         * handed over while the game screen is already open makes a second instance; the first one's surfaceDestroyed,
         * onPause and onDestroy used to take the surface and the game away from it (black screen at 0 fps, Thor
         * 2026-11-06). Null while no game is loaded, when any instance may set the surface.
         */
        private var owner: MainActivity? = null
        /** A game is up in this process (the launcher offers no recovery while one is). */
        val gameRunning get() = owner != null
        /** A game was loaded in this process; the core cannot load a second one, so a new ROM restarts the process. */
        private var gameLoaded = false
        private const val SHUTDOWN_POLL_MS = 500L
        /** The driver trial's watch (approved gputry mockup 2026-11-15): how often, and how long a start gets. */
        private const val TRIAL_POLL_MS = 500L
        /** When in a play with a texture pack its picture is taken (Joey 2026-11-15): past the title screens in most games. */
        private const val TEXPACK_SHOT_MS = 60_000L
        private const val SPEED_POLL_MS = 1_000L
        private const val SPEED_FLUSH_MS = 30_000L
        private const val TRIAL_NO_FRAME_MS = 10_000L
        private const val TRIAL_BLACK_MS = 20_000L
        /** How long the card says which driver comes next before the game screen restarts. */
        private const val TRIAL_CARD_MS = 1_500L
        private const val NET_POLL_MS = 250L
        private const val NET_BLOCKED_MS = 3_000L
        /** How often the game screen checks whether the game's own save was written (Not enough space to save). */
        private const val SAVE_RAM_POLL_MS = 3_000L
        /** Game start and quit file work, one job at a time in order, for every instance (they share cache/current.rom and saves/). */
        private val gameIo = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "joey64-gameio") }
        /** The non-exported alias Joey64's own game starts go through (manifest; [fromJoey64]). */
        const val GAME_START = "com.joeysretro.joey64.GameStart"
        /** An intent for the game screen through [GAME_START]: use it for every start from inside Joey64. */
        fun gameStart(context: Context): Intent = Intent().setClassName(context, GAME_START)
        /** switchGame -> the next process: the ROM already unpacked, and its save name. */
        const val EXTRA_ROM_PATH = "com.joeysretro.joey64.ROM_PATH"
        const val EXTRA_SAVE_NAME = "com.joeysretro.joey64.SAVE_NAME"
        /** The launcher: load this state once the game is up, [STATE_AUTO] (Continue) or [STATE_RECOVERY] (after a crash). */
        const val EXTRA_LOAD_STATE = "com.joeysretro.joey64.LOAD_STATE"
        /** The launcher: [EXTRA_LOAD_STATE] is a state the user chose to play in Casual with Hardcore wanted ("Play from this state?"). */
        const val EXTRA_CASUAL_STATE = "com.joeysretro.joey64.CASUAL_STATE"
        /** A Restart now from a Hardcore play: the restarted play is Casual (it loads a state) and says so. */
        const val EXTRA_WAS_HARDCORE = "com.joeysretro.joey64.WAS_HARDCORE"
        /** Hardcore turned on mid-game in an overclocked play ([restartInHardcore]): this start plays in Hardcore. */
        const val EXTRA_HARDCORE_PLAY = "com.joeysretro.joey64.HARDCORE_PLAY"
        /** The ROM as the user has it (the archive in the ROMs folder), for recently played. */
        const val EXTRA_SOURCE_URI = "com.joeysretro.joey64.SOURCE_URI"
        const val EXTRA_SOURCE_NAME = "com.joeysretro.joey64.SOURCE_NAME"
        /** The library's title (pause menu, recently played). */
        const val EXTRA_TITLE = "com.joeysretro.joey64.TITLE"
        /** The ROM's MD5 (.z64 order), from the launcher: Auto pak looks it up without hashing the ROM again. */
        const val EXTRA_MD5 = "com.joeysretro.joey64.MD5"
        /** The library's header game code ("NALE"): a game's default Crop edges known before the held picture goes up. */
        const val EXTRA_GAME_CODE = "com.joeysretro.joey64.GAME_CODE"
        /**
         * Joey64's own restarts and switches (switchGame, Restart now): the ROM's MD5 as it was hashed off the main thread
         * for the start before, so the next start's Auto renderer needn't hash it in onCreate (code audit 3, M18). Unlike
         * [EXTRA_MD5] it doesn't mean the library started the game.
         */
        const val EXTRA_KNOWN_MD5 = "com.joeysretro.joey64.KNOWN_MD5"
        /**
         * The launcher's mark for a game on the state-request wanted list (boolean): the host keeps the state ring in
         * [stateRingDir] this play. Read after quit: meta.json there, {"version":1,"playSeconds":N,"entries":[{"path",
         * "takenAt" (ms since 1970),"playAt" (s of play),"drew3d"}]}, oldest first; a new play of the game starts it over.
         */
        const val EXTRA_STATE_RING = "com.joeysretro.joey64.STATE_RING"
        /** Where the host keeps a game's state ring (EXTRA_STATE_RING). */
        fun stateRingDir(ctx: android.content.Context, md5: String): File = File(ctx.filesDir, "states/ring/$md5")
        private const val SESSION_TICK_MS = 5_000L
        /** A 64DD disk's cartridge, as a content URI (F-Zero X Expansion Kit: F-Zero X (Japan)); without it a disk boots alone. */
        const val EXTRA_DD_CART = "com.joeysretro.joey64.DD_CART"
        /** Play together: host a room, "port:players:auto" (N64_NETPLAY_SERVE); with [EXTRA_NETPLAY_JOIN] "127.0.0.1:port:own player". */
        const val EXTRA_NETPLAY_HOST = "com.joeysretro.joey64.NETPLAY_HOST"
        /** Play together: join the room at "host:port:player" (N64_NETPLAY). */
        const val EXTRA_NETPLAY_JOIN = "com.joeysretro.joey64.NETPLAY_JOIN"
        /** Play together: this player's name in the room (N64_NETPLAY_NAME). */
        const val EXTRA_NETPLAY_NAME = "com.joeysretro.joey64.NETPLAY_NAME"
        /** Play together, the host: the joiners' one-time keys by place, "k1,k2,k3,k4" (N64_NETPLAY_KEYS, read by the room). */
        const val EXTRA_NETPLAY_KEYS = "com.joeysretro.joey64.NETPLAY_KEYS"
        /** Play together, a joiner: its one-time key (N64_NETPLAY_KEY: the core registers with it). */
        const val EXTRA_NETPLAY_KEY = "com.joeysretro.joey64.NETPLAY_KEY"
        private val NETPLAY_ENV = listOf("N64_NETPLAY", "N64_NETPLAY_SERVE", "N64_NETPLAY_NAME", "N64_NETPLAY_KEYS", "N64_NETPLAY_KEY", "N64_NETPLAY_RB_CAP")
        /** Extras only Joey64's own starts may carry ([fromJoey64]); a start from another app has them dropped. */
        private val LAUNCHER_EXTRAS = listOf(EXTRA_ROM_PATH, EXTRA_SAVE_NAME, EXTRA_LOAD_STATE, EXTRA_SOURCE_URI, EXTRA_SOURCE_NAME,
            EXTRA_TITLE, EXTRA_MD5, EXTRA_GAME_CODE, EXTRA_KNOWN_MD5, EXTRA_DD_CART, EXTRA_NETPLAY_HOST, EXTRA_NETPLAY_JOIN, EXTRA_NETPLAY_NAME,
            EXTRA_NETPLAY_KEYS, EXTRA_NETPLAY_KEY, EXTRA_PLAY_NOTE, EXTRA_CASUAL_STATE, EXTRA_WAS_HARDCORE, EXTRA_HARDCORE_PLAY,
            com.joeysretro.joey64.launcher.library.GameLauncher.EXTRA_IS_DISK, com.joeysretro.joey64.launcher.library.GameLauncher.EXTRA_NEEDS_CART)
        const val STATE_AUTO = "auto"
        /** Play's popup set Continue on Play (Y, Always do this; approved E4 2026-11-11): the line shown as the game opens. */
        const val EXTRA_PLAY_NOTE = "com.joeysretro.joey64.PLAY_NOTE"
        const val STATE_RECOVERY = "recovery"
        /** How long the GPU-lost pop-up shows before the game screen closes. */
        private const val GPU_LOST_CLOSE_MS = 3_000L
        /** "slot:<n>": the state in StateSlots' slot n (the Saves page's Play from here). */
        const val STATE_SLOT = "slot:"
        private const val SRM_PUSH_MS = 30_000L
        /** "Every few minutes" (approved 2026-11-05). */
        /** The pause menu's "Press GameShark button": how long it holds it (approved 2026-11-07: a second). */
        private const val GAMESHARK_PRESS_MS = 1_000L
        /** The hotkey's shortest hold: a few frames, so a quick tap reaches the game. */
        private const val GAMESHARK_MIN_MS = 200L
        /** Touch controls in portrait: the picture's top, 115 px of a 2400 px phone (approved mockup screen 6). */
        private const val PORTRAIT_TOP_PER_MILLE = 48
        /** A touch fast-forward held this long is "while held"; shorter is a tap. */
        private const val TOUCH_HOLD_MS = 400L
        private const val UPSCALE_KEY = "mupen64plus-parallel-rdp-upscaling"
        /** parallel-RDP's Resolution steps for the Upscale hotkeys (the Duo stops at 2x, [capUpscaling]). */
        private val UPSCALES = listOf("1x", "2x", "4x", "8x")
        private const val VOLUME_FIRST_REPEAT_MS = 400L
        private const val VOLUME_REPEAT_MS = 150L
        private const val STATE_TIMEOUT_MS = 10_000
        const val TAG = "Joey64"
        // Bundled in assets/drivers/ (see assets/licenses/): Turnip (Mesa freedreno).
        const val TURNIP = "libvulkan_freedreno.so"
        // Same file name in its own folder: the driver is loaded by that name
        const val TURNIP_FAUX123 = "faux123/libvulkan_freedreno.so"
    }
}
