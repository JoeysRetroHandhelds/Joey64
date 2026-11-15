package com.joeysretro.joey64

/**
 * The GPU-specific choices in one place (README order item 15, item 38's proposals a, b, c and e; Joey 2026-11-06:
 * "After the work order"): the GPU's family, the driver Auto picks for it, the settings that differ by GPU, and the
 * start-up trial that steps Auto's driver + renderer down on a GPU where they never draw. Pure logic, so GpuRulesTest
 * pins what the four GPUs we run get (Adreno 740 Thor/Portal, 830 Odin 3, 722 Duo, Mali-G52 Flip).
 */
object GpuRules {
    enum class Vendor { ADRENO, MALI, OTHER }

    /** model: the Adreno's number (740), 0 when not known or not an Adreno; source: where it came from, for the log. */
    data class Family(val vendor: Vendor, val model: Int, val name: String, val source: String = "") {
        override fun toString() = when (vendor) {
            Vendor.ADRENO -> if (model > 0) "Adreno $model (from the $source)" else "Adreno, model not known"
            Vendor.MALI -> "Mali"
            Vendor.OTHER -> "not Adreno or Mali"
        } + if (name.isNotEmpty()) " [$name]" else ""
    }

    /** Qualcomm's marketing names in the Vulkan name, to a model (README item 38: "Adreno (TM) A12" is a 613). */
    private val MARKETING = mapOf("A12" to 613)

    /**
     * vendorId: the Vulkan vendor (0x5143 Qualcomm, 0x13B5 Arm, 0 not known); vkName: the Vulkan device name; kgslModel:
     * /sys/class/kgsl/kgsl-3d0/gpu_model ("Adreno740v2"), when readable. The number in the Vulkan name wins, so the GPUs
     * already run keep what they had; kgsl, then the marketing names, only fill in a name without one.
     */
    fun family(vendorId: Int, vkName: String, kgslModel: String?): Family {
        if (vendorId == 0x5143 || "Adreno" in vkName) {
            Regex("""Adreno \(TM\) (\d{3})""").find(vkName)?.let { return Family(Vendor.ADRENO, it.groupValues[1].toInt(), vkName, "Vulkan name") }
            Regex("""Adreno\D{0,8}(\d{3})""").find(kgslModel.orEmpty())?.let { return Family(Vendor.ADRENO, it.groupValues[1].toInt(), vkName, "kgsl gpu_model") }
            Regex("""Adreno \(TM\) (\w+)""").find(vkName)?.groupValues?.get(1)?.let { MARKETING[it] }
                ?.let { return Family(Vendor.ADRENO, it, vkName, "marketing name") }
            return Family(Vendor.ADRENO, 0, vkName)
        }
        if (vendorId == 0x13B5 || "Mali" in vkName) return Family(Vendor.MALI, 0, vkName)
        return Family(Vendor.OTHER, 0, vkName)
    }

    /**
     * The drivers Joey64 bundles (assets/drivers/[asset], listed in devtools/drivers/bundled.json) and the system's.
     * [id] is what Settings › Video › GPU driver stores and the `device` pref "driver" says; [shown] is the trial card's
     * and the picker's name; [minSdk] is the driver's own (its meta.json), below which it is never tried. All are Mesa
     * builds (MIT) or malisx2 (MIT): Qualcomm's own drivers can't be bundled (Joey 2026-11-15: "Let's try everything",
     * from his Snapdragon and Mali driver pages).
     */
    enum class Driver(val id: String, val asset: String?, val shown: String, val minSdk: Int = 0, val mali: Boolean = false) {
        TURNIP("turnip", "libvulkan_freedreno.so", "Turnip Gen8"),
        FAUX123("faux123", "faux123/libvulkan_freedreno.so", "Turnip faux123"),
        R2("r2", "r2/libvulkan_freedreno.so", "Turnip R2"),
        APEX("apex", "apex/vulkan.freedreno.so", "Balemuni Apex", minSdk = 33),
        PURPLE("purple", "purple/vulkan.purple.so", "Mr Purple T30", minSdk = 30),
        DUO("duo", "duo/libvulkan_freedreno.so", "Turnip Duo"),
        TURNIPZ("turnipz", "turnipz/libvulkan_freedreno.so", "TurnipZ", minSdk = 30),
        K11("k11", "k11/vulkan.ad07xx.so", "K11MCH1 Turnip"),
        ARMSX2("armsx2", "armsx2/libvulkan_freedreno.so", "ARMSX2 Turnip"),
        MALISX2("malisx2", "malisx2/libvulkan_malisx2.so", "malisx2", mali = true),
        PANVK("panvk", "panvk/libvulkan_panfrost.so", "PanVK", minSdk = 35, mali = true),
        SYSTEM("system", null, "The phone's own driver");

        companion object {
            fun byId(id: String?): Driver? = entries.firstOrNull { it.id == id }
        }
    }

    /**
     * Drivers picked in Settings › Video › GPU driver that didn't work on this device (approved drvnote mockup
     * 2026-11-15): the `device` pref "driver_failed", "<install>|<id>:<why>,<id>:<why>", [LOAD] it didn't load, [PRDP]
     * it loaded without what parallel-RDP needs. Only this install's count, so an update tries them again.
     */
    const val LOAD = "load"
    const val PRDP = "prdp"

    fun failedPicks(pref: String?, install: String): Map<String, String> {
        val (inst, rest) = pref?.split('|', limit = 2)?.takeIf { it.size == 2 } ?: return emptyMap()
        if (inst != install) return emptyMap()
        return rest.split(',').mapNotNull { e -> e.split(':').takeIf { it.size == 2 && it[0].isNotEmpty() }?.let { it[0] to it[1] } }.toMap()
    }

    fun withFailedPick(pref: String?, install: String, id: String, why: String): String =
        (failedPicks(pref, install) + (id to why)).entries.joinToString(",", prefix = "$install|") { "${it.key}:${it.value}" }

    /** The pop-up's and the picker's words for [why]. */
    fun failedLabel(why: String?): String? = when (why) { LOAD -> "Didn't load"; PRDP -> "Can't run parallel-RDP"; else -> null }

    /** A choice and the rule that made it, for the log (item 38 e: a tester's report shows which rule picked). */
    data class Pick<T>(val value: T, val rule: String)

    /**
     * The drivers Auto tries on this GPU, best first (Joey 2026-11-15: "try as much as possible to avoid a new user with
     * untested device having to fall back to glide"). The start-up trial ([GpuTrial]) gives each one try with
     * parallel-RDP; after the last, GLideN64 on the system driver. The first is [autoDriver]'s pick, so the GPUs we run
     * keep what they had. Drivers whose [Driver.minSdk] is above [sdk] are left out.
     */
    fun chain(f: Family, sdk: Int, upscale: Int = 1): Pick<List<Driver>> {
        val first = autoDriver(f, upscale)
        val rest: List<Driver> = when {
            // Adreno 8xx: the stock driver runs parallel-RDP (Odin 3: 82/82 at every resolution), then Turnip. Order by the
            // 2026-11-15 smoke bench (o3p-dz, Odin 3 Portal, 8 games uncapped): Gen8, faux123 and ARMSX2 even; R2 left out (it
            // draws faint noise there, drvwhich o3p-which); Apex and
            // Mr Purple about half speed, so last
            f.vendor == Vendor.ADRENO && f.model in 800..899 -> listOf(Driver.TURNIP, Driver.FAUX123, Driver.ARMSX2, Driver.K11, Driver.APEX, Driver.PURPLE)
            // The Duo's 722: faux123 (Mario Party 2's text boxes), then by the smoke bench (duo-dz): the Duo build, ARMSX2,
            // Gen8 and K11 even with faux123, R2 ~10% slower, Apex and Mr Purple about half
            f.vendor == Vendor.ADRENO && f.model == 722 -> listOf(Driver.DUO, Driver.ARMSX2, Driver.TURNIP, Driver.K11, Driver.R2, Driver.APEX, Driver.PURPLE, Driver.SYSTEM)
            // Adreno 6xx: Mesa gives a6xx no 8-bit storage, so only Mr Purple's build (8-bit storage as a Vulkan 1.2
            // feature, the RP Mini V2 / Flip 2 testers 2026-11-08) can run parallel-RDP; the rest are checked on the
            // probe, which costs no game start
            f.vendor == Vendor.ADRENO && f.model in 600..699 -> listOf(Driver.PURPLE, Driver.TURNIPZ, Driver.K11, Driver.FAUX123, Driver.R2, Driver.SYSTEM)
            // Other Adreno 7xx, the A32/A33 (G3 Gen 3, G3x Gen 2) and any Adreno with no model known
            // (smoke bench o2p-dz, Odin 2 Portal's 740: R2, faux123 and ARMSX2 even with Gen8, ARMSX2 up to 10% faster;
            // K11 R8 doesn't load there; Apex and Mr Purple about half speed)
            f.vendor == Vendor.ADRENO && (f.model in 700..799 || f.model == 0) ->
                listOf(Driver.R2, Driver.ARMSX2, Driver.FAUX123, Driver.K11, Driver.APEX, Driver.PURPLE, Driver.SYSTEM)
            f.vendor == Vendor.ADRENO -> listOf(Driver.PURPLE, Driver.K11, Driver.SYSTEM)
            f.vendor == Vendor.MALI -> listOf(Driver.MALISX2, Driver.PANVK)
            else -> emptyList()
        }
        val list = (listOf(first.value) + rest).distinct().filter { sdk >= it.minSdk }
        return Pick(list, first.rule + if (list.size > 1) ", then " + list.drop(1).joinToString(", ") { it.shown } else "")
    }

    /** Auto's driver for the system GPU's family; [upscale] is the parallel-RDP Resolution the game starts at. */
    fun autoDriver(f: Family, upscale: Int = 1): Pick<Driver> = when {
        // faux123's Turnip (Duo fixes) draws Mario Party 2's text boxes without the stray lines the general build
        // leaves, and passed the 82-game compat run (2026-11-04)
        f.vendor == Vendor.ADRENO && f.model == 722 -> Pick(Driver.FAUX123, "Adreno 722: Turnip faux123")
        // The stock Adreno 740 driver (676.53) fails to compile a parallel-RDP shader; Turnip runs it. Turnip Gen8's
        // device table knows every Adreno 6xx-8xx part (README item 38, offline coverage).
        // 8x on Adreno 8xx (Odin 3, Adreno 830; Joey 2026-11-09, "Stock driver at 8x"): Turnip draws nothing with a 256 or
        // 512 MB upscaled RDRAM buffer although it reports 2047 MB (odin3-t8-*); Qualcomm's driver draws 8x (up8-odin3-sysdrv,
        // odin3-8xsysprdp-compat). Only a8xx: a6xx/a7xx report 128 MB, so 8x is capped to 4x there on any driver.
        // 4x too (Joey 2026-11-09, "Yes use stock"): on the Odin 3 the stock driver ran the quick set +16% uncapped at 4x
        // (odin3-drv) and, capped at 60 fps, with 4-9 ms less input lag in every game and no frames over 33 ms
        // (odin3-drv4xcap60b). 1x and 2x too (2026-11-10, after Joey's "Why driver at 1x if even with stock?"): capped at
        // 1x the two drivers are even (odin3-drv1xcap60: input lag 21.6 vs 22.0 ms, no frame over 33 ms on either), and the
        // stock driver passed compat at 1x (odin3-sys1x-compat, 82/82, 0 black) and on the 2x set (odin3-sys2x-set).
        f.vendor == Vendor.ADRENO && f.model in 800..899 -> Pick(Driver.SYSTEM, "Adreno 8xx: system driver")
        // Adreno 6xx: only Mr Purple's Turnip has the 8-bit storage parallel-RDP needs (Mesa: a7xx only)
        f.vendor == Vendor.ADRENO && f.model in 600..699 -> Pick(Driver.PURPLE, "Adreno 6xx: Mr Purple T30 (8-bit storage)")
        f.vendor == Vendor.ADRENO && f.model in 700..899 -> Pick(Driver.TURNIP, "Adreno 6xx-8xx: bundled Turnip")
        // An Adreno with no model known (a marketing name not in the table: A32, A33...): Turnip Gen8 first
        f.vendor == Vendor.ADRENO && f.model == 0 -> Pick(Driver.TURNIP, "Adreno, model not known: bundled Turnip")
        f.vendor == Vendor.ADRENO -> Pick(Driver.SYSTEM, "Adreno outside 6xx-8xx: system driver")
        // Mali-G52 runs parallel-RDP at full speed on the system driver; malisx2 measured ~14% slower there.
        f.vendor == Vendor.MALI -> Pick(Driver.SYSTEM, "Mali: system driver")
        else -> Pick(Driver.SYSTEM, "other GPU: system driver")
    }

    /**
     * Auto's renderer: parallel-RDP needs Vulkan 1.1 with 16-bit and 8-bit storage buffers (without them it fails to
     * start: Adreno 610 has no 16-bit; Adreno 650 has 8-bit only on PurpleVK, as a Vulkan 1.2 core feature), and a driver
     * known to run it: a custom one, or stock Mali. Else GLideN64.
     */
    fun autoRenderer(vulkan11: Boolean, storage16: Boolean, customDriver: Boolean, f: Family,
                     littleCoresOnly: Boolean = false, upscale: Int = 1, tryStock: Boolean = false): Pick<String> = when {
        !vulkan11 -> Pick("gliden64", "no Vulkan 1.1: GLideN64")
        !storage16 -> Pick("gliden64", "no 16-bit or 8-bit storage buffers: GLideN64")
        customDriver -> Pick("parallel", "custom driver: parallel-RDP")
        // The stock driver on Adreno 8xx runs parallel-RDP (Odin 3 / Odin 3 Portal, Adreno 830: 82/82 compat at 4x and 8x), so
        // Auto never gives it GLideN64, at any resolution (Joey 2026-11-09: "Glide shouldn't be offered at auto there at all").
        f.vendor == Vendor.ADRENO && f.model in 800..899 -> Pick("parallel", "stock Adreno 8xx: parallel-RDP")
        // RK3566 (4x Cortex-A55, 1-core Mali-G52; Joey 2026-11-09, "Yes, add the rule"): parallel-RDP held full speed in
        // no game, the Mali driver's own CPU side took 39% of the CPU; GLideN64 SM64 84 vs 44 fps uncapped, OoT 65 vs 57.
        // The Flip (A75 big cores) keeps parallel-RDP.
        f.vendor == Vendor.MALI && littleCoresOnly -> Pick("gliden64", "stock Mali, only little CPU cores (A53/A55): GLideN64")
        f.vendor == Vendor.MALI -> Pick("parallel", "stock Mali: parallel-RDP")
        // The trial's system-driver step (Joey 2026-11-15): parallel-RDP is tried on any stock driver with the features;
        // the trial steps down to GLideN64 when it shows no picture
        tryStock -> Pick("parallel", "trial: parallel-RDP on the stock driver")
        else -> Pick("gliden64", "stock driver not known to run parallel-RDP: GLideN64")
    }

    /**
     * Games Auto starts on GLideN64 (which brings the HLE RSP) where the GPU rule above gave parallel-RDP, by ROM MD5
     * (the library's md5, RetroAchievements' N64 hash). Settings › Video › Renderer and --es renderer still win.
     */
    private val GLIDEN_GAMES = mapOf(
        // SM64 Last Impact (hack), README bug queue (k), Joey 2026-11-09 "GLideN64 for it": with any LLE RSP (parallel
        // RSP, cxd4) and with parallel-n64's HLE geometry the file-select pad and sign are cut, on every driver;
        // GLideN64's HLE draws them whole, as M64Plus FZ does.
        "70059d4d7ba7ac0f0741b44aec8b44dc" to "SM64 Last Impact: GLideN64 + HLE draws its file select whole",
    )

    /** Auto's per-game renderer, or null when the game has none. */
    fun gameRenderer(md5: String?): Pick<String>? = md5?.lowercase()?.let { GLIDEN_GAMES[it] }?.let { Pick("gliden64", it) }

    /**
     * The driver trial's picture test (2026-11-15): [frame] as NativeBridge.captureFrame gives it (width and height as
     * little-endian u32, then RGBA rows) has a picture, not just black or noise. The frame is cut into 32 x 24 cells, each
     * the average of 8 x 8 points in it; lit when at least 2 cells average a channel over 24, so a small logo on black
     * counts but scattered dots don't (Turnip R2 on the Odin 3 Portal's 830 draws faint noise all over, which single
     * points took for a picture).
     */
    fun lit(frame: ByteArray?): Boolean {
        if (frame == null || frame.size < 8) return false
        fun u32(o: Int) = (frame[o].toInt() and 0xff) or ((frame[o + 1].toInt() and 0xff) shl 8) or
            ((frame[o + 2].toInt() and 0xff) shl 16) or ((frame[o + 3].toInt() and 0xff) shl 24)
        val w = u32(0); val h = u32(4)
        if (w <= 0 || h <= 0 || frame.size < 8 + w.toLong() * h * 4) return false
        val cw = 32; val ch = 24
        var litCells = 0
        for (cy in 0 until ch) for (cx in 0 until cw) {
            val x0 = cx * w / cw; val x1 = maxOf(x0 + 1, (cx + 1) * w / cw)
            val y0 = cy * h / ch; val y1 = maxOf(y0 + 1, (cy + 1) * h / ch)
            var r = 0; var g = 0; var b = 0; var n = 0
            for (sy in 0 until 8) for (sx in 0 until 8) {
                val x = minOf(w - 1, x0 + (x1 - x0) * (2 * sx + 1) / 16)
                val y = minOf(h - 1, y0 + (y1 - y0) * (2 * sy + 1) / 16)
                val o = 8 + (y * w + x) * 4
                r += frame[o].toInt() and 0xff; g += frame[o + 1].toInt() and 0xff; b += frame[o + 2].toInt() and 0xff; n++
            }
            if (maxOf(r, g, b) > 24 * n) litCells++
        }
        return litCells >= 2
    }

    /** Arm's little cores by /proc/cpuinfo "CPU part": Cortex-A32, A35, A53, A55. */
    private val LITTLE_PARTS = setOf(0xd01, 0xd04, 0xd03, 0xd05)

    /** True when /proc/cpuinfo lists cores and every one is a little core (an RK3566's four A55s); false when unreadable. */
    fun littleCoresOnly(cpuinfo: String?): Boolean {
        val parts = Regex("""CPU part\s*:\s*0x([0-9a-fA-F]+)""").findAll(cpuinfo.orEmpty()).map { it.groupValues[1].toInt(16) }.toList()
        return parts.isNotEmpty() && parts.all { it in LITTLE_PARTS }
    }

    /** An environment variable (core or host switch) or a core option this GPU gets by default; --es env/opts still win. */
    data class Setting(val key: String, val value: String, val coreOption: Boolean, val rule: String)

    /** Before the driver loads (the KGSL hook reads it then); by device. */
    fun beforeDriver(deviceModel: String): List<Setting> = buildList {
        // KGSL GPU priority 1 for the bundled Turnip's contexts on the Odin 3 only (Joey 2026-11-07): +3.6% uncapped
        // there (odin3-gpu2, 13 of 14 games up); flat on the Portal (portal-gpu2). --es env N64_KGSL_PRIO= turns it off.
        if (deviceModel == "Odin3") add(Setting("N64_KGSL_PRIO", "1", false, "Odin 3: KGSL priority 1"))
    }

    /** After the driver is chosen, by the family of the GPU in use (the custom driver's, else the system's). */
    fun settings(f: Family): List<Setting> = buildList {
        val mali = f.vendor == Vendor.MALI
        val adreno = f.vendor == Vendor.ADRENO
        // Early acquire on Mali (Joey 2026-11-05): after 30 new frames in a row the next frame acquires its swapchain
        // image early. Flip compat 82/82 (flip-early30); no gain on the 120 Hz Adrenos, where GoldenEye judders with it.
        if (mali) add(Setting("N64_EARLY_ACQUIRE", "30", false, "Mali: early acquire"))
        // Single-threaded RDP command processing on Mali (README item 36): Flip +3.7% uncapped, compat 82/82
        // (flip-st). Adreno keeps the ring (Duo mixed: Banjo -6.4%, Paper Mario -5.7%).
        if (mali) add(Setting("PARALLEL_RDP_SINGLE_THREADED_COMMAND", "1", false, "Mali: single-threaded RDP commands"))
        // Lazy waking of parallel-RDP's command worker (core, on unless 0) stays on for Adreno only (Joey 2026-11-07):
        // Portal +4.4%, Duo +3.0%, compat 82/82 on both. Other GPUs were not measured with it.
        if (!adreno) add(Setting("N64_RDP_RING_LAZY", "0", false, "not Adreno: lazy RDP worker off"))
        // The recompiler's compile thread on Mali only (2026-11-08, Joey: "your call"): Flip MK64 race start worst
        // frame 88-90 -> 45 ms, speed -0.4% (flip-drcthread4). Adreno lost on worst frames (odin3-drcthread4).
        if (mali) add(Setting("N64_DRC_THREAD", "1", false, "Mali: recompiler compile thread"))
        // The recompiler's jump cache (core, on unless 0; work order item 13) stays on for Adreno only: Duo +1.6%
        // uncapped, 82/82 (duo-jc1, duo-jc-compat); Odin 3 flat (odin3-jc). The Flip lost Conker -5.8% and GoldenEye
        // -9.6% (flip-jc), so not on Mali; other GPUs were not measured with it.
        if (!adreno) add(Setting("N64_DRC_JCACHE", "0", false, "not Adreno: recompiler jump cache off"))
        // Smoother timing (CountPerOp=1) for the games that lag on hardware, strong devices only (Joey 2026-11-06;
        // compat passed on the Odin 3 and Portal): Adreno 730 and up. The host picks the games (kSmootherGames).
        if (adreno && f.model >= 730) add(Setting("N64_SMOOTHER", "1", false, "Adreno 730+: smoother timing"))
        // parallel-RDP synchronous on Adreno: async there gives black screens (Yoshi's Story, Resident Evil 2, Cruis'n
        // USA) or halts (New Tetris), and sync costs ~1-2 ms a frame. On Mali sync can cost a third of the frame
        // rate, so async plus kSyncRdpGames.
        if (adreno) add(Setting("mupen64plus-parallel-rdp-synchronous", "True", true, "Adreno: parallel-RDP synchronous"))
        // Native-first (core, smart sync only; Joey 2026-11-14 "Yes, default it"): upscaled passes go after the 1x ones
        // while the game waits on the GPU, so a CPU wait waits for the 1x work. Odin 2 Portal 4x: Paper Mario 7.08 ->
        // 3.37 ms, Perfect Dark 9.96 -> 3.86, Banjo 7.56 -> 3.90, OoT 4.32 -> 2.83, GoldenEye unchanged (no waits).
        // Adreno only: Mali runs smart sync for Snap alone and wasn't measured.
        if (adreno) add(Setting("N64_RDP_NATIVE_FIRST", "1", false, "Adreno: native-first upscaled passes"))
        // No pipeline pre-build on Adreno (Joey 2026-11-07): with the driver cache cold (after a driver update or a cleared
        // cache) building the saved list first stalled ~0.6 s at start in every game on the Portal (portal-pbfirst),
        // on-demand compiling 63-170 ms; warm launches wait 0 ms either way. Mali keeps it.
        if (adreno) add(Setting("N64_RDP_PREBUILD", "0", false, "Adreno: no pipeline pre-build"))
    }

    /**
     * parallel-RDP's highest upscale before the storage buffer range: 4x on the Adreno 722 drops most geometry on both
     * Turnip builds tried (and runs at 40-55 fps), 2x is right; elsewhere 8x.
     */
    fun upscaleTop(f: Family, customDriver: Boolean = false): Pick<Int> = when {
        f.vendor == Vendor.ADRENO && f.model == 722 -> Pick(2, "Adreno 722: 2x")
        // Turnip on Adreno draws nothing past a 128 MB upscaled RDRAM buffer, whatever it reports (Adreno 830: 2047 MB,
        // black at 8x, odin3-t8-*), so 4x there; Auto takes the system driver for 4x and 8x on a8xx.
        f.vendor == Vendor.ADRENO && customDriver -> Pick(4, "Turnip on Adreno: 4x")
        else -> Pick(8, "8x")
    }
}

/**
 * The start-up trial (README item 38 b; one try per driver since 2026-11-15, Joey: "try as much as possible to avoid a
 * new user with untested device having to fall back to glide", and say on screen what happens: approved gputry mockup).
 * Auto's drivers for a GPU ([GpuRules.chain]) are a trial until a game has shown a picture with one. Level n is the
 * chain's n-th driver with parallel-RDP; level [top] (the chain's length) is GLideN64 on the system driver. A level
 * fails when its driver can't load or lacks what parallel-RDP needs ([fail], before the game loads), when no picture
 * shows in time ([fail], from the game screen's watch), or when the start dies before a picture ([decide] finds the
 * start counted and not drawn: "the emulator stopped"). Each failure moves to the next level at once, and the game
 * screen restarts with it. Kept per install (an update tries Auto again) and per system GPU, with what each level did,
 * for the card and the reports. Only Auto's choice is tried: a driver or renderer picked in Settings or by a dev
 * switch is the user's.
 */
class GpuTrial(private val store: Store, install: String, systemGpu: String, val top: Int = 2) {
    /** SharedPreferences in the app; a map in the tests. Writes must be on disk before put returns (a crash follows). */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String?)
        fun keys(): Set<String>
    }

    private val key = "trial|$install|$systemGpu"
    private val histKey = "hist|$install|$systemGpu"

    /** [told]: the end card (no driver worked) was answered, so it isn't shown again. */
    private data class State(val level: Int, val fails: Int, val good: Boolean, val told: Boolean = false)

    private fun read(): State {
        val p = store.get(key)?.split(',')
        return State(p?.getOrNull(0)?.toIntOrNull() ?: 0, p?.getOrNull(1)?.toIntOrNull() ?: 0, p?.getOrNull(2) == "good",
            p?.getOrNull(3) == "told")
    }

    private fun write(s: State) = store.put(key, "${s.level},${s.fails},${if (s.good) "good" else "trial"}${if (s.told) ",told" else ""}")

    /** What each failed level did, oldest first: level to reason ("no picture", "the emulator stopped"...). */
    fun history(): List<Pair<Int, String>> = store.get(histKey).orEmpty().split(';').mapNotNull { e ->
        val i = e.indexOf(':'); if (i <= 0) null else e.substring(0, i).toIntOrNull()?.let { it to e.substring(i + 1) }
    }

    private fun addHistory(level: Int, reason: String) {
        val clean = reason.replace(Regex("[;:\\n]"), " ").take(60)
        store.put(histKey, (history() + (level to clean)).joinToString(";") { "${it.first}:${it.second}" })
    }

    /** The level this start runs at, after stepping down if the last start died before a picture; and what happened, for the log. */
    fun decide(): Pick {
        // Records of earlier installs or system drivers never apply again (the key changed): drop them.
        for (k in store.keys()) if ((k.startsWith("trial|") && k != key) || (k.startsWith("hist|") && k != histKey)) store.put(k, null)
        var s = read()
        var stepped = false
        if (!s.good && s.fails >= FAILS && s.level < top) {
            addHistory(s.level, STOPPED)
            s = State(s.level + 1, 0, false); write(s); stepped = true
        }
        val what = when {
            s.good -> "known good at level ${s.level}"
            stepped -> "stepped down to level ${s.level} after a start that died before a picture"
            s.level >= top && s.fails >= FAILS -> "level ${s.level} (the lowest) has failed ${s.fails} times"
            else -> "trial at level ${s.level}, ${s.fails} failed start(s) so far"
        }
        return Pick(s.level, what, s.good, history(), s.told)
    }

    /** [level]: this start's; [good]: a picture has shown at it; [history]: the failed levels before it; [told]: the end card was answered. */
    data class Pick(val level: Int, val what: String, val good: Boolean = false, val history: List<Pair<Int, String>> = emptyList(),
                    val told: Boolean = false)

    /** Just before the game loads: counts the start as failed until [drew]. */
    fun begin() { val s = read(); if (!s.good) write(s.copy(fails = s.fails + 1)) }

    /** The game didn't load (no ROM, the core refused it): not the GPU's doing, so the start doesn't count. */
    fun cancel() { val s = read(); if (!s.good && s.fails > 0) write(s.copy(fails = s.fails - 1)) }

    /** A picture showed: this level is good on this GPU from now on. */
    fun drew() { val s = read(); if (!s.good) write(State(s.level, 0, true, s.told)) }

    /**
     * This level can't work ([reason], for the card and the reports): the next start runs the next level. Returns
     * false at the lowest level (nothing below it). Not for a level already good.
     */
    fun fail(reason: String): Boolean {
        val s = read()
        if (s.good || s.level >= top) return false
        addHistory(s.level, reason)
        write(State(s.level + 1, 0, false))
        return true
    }

    /** A faster level was found ([GpuSpeed]): it's the good one from now on. */
    fun keep(level: Int) { val s = read(); write(State(level, 0, true, s.told)) }

    /**
     * After an update (no record for this install yet, a good one from an earlier install on this GPU): the trial
     * starts at [level], the driver Auto ran before (a faster one the speed check kept, say), instead of the chain's
     * first (review 2026-11-15: every update forgot a kept driver). It is still a trial: a failure moves on as usual,
     * and the speed check can try the levels above it again for this install. Call before [decide].
     */
    fun carry(level: Int) {
        if (level <= 0 || level >= top || store.get(key) != null) return
        val gpu = key.substringAfterLast('|')
        val earlierGood = store.keys().any { it.startsWith("trial|") && it != key && it.substringAfterLast('|') == gpu &&
            store.get(it)?.split(',')?.getOrNull(2) == "good" }
        if (earlierGood) write(State(level, 0, false))
    }

    /** The end card (no driver worked) was answered: it isn't shown again for this install and GPU. */
    fun told() { val s = read(); write(s.copy(told = true)) }

    companion object {
        /** Starts that die before a picture at one level before the next is tried (one since 2026-11-15). */
        const val FAILS = 1
        const val STOPPED = "the emulator stopped"
        const val NO_PICTURE = "no picture"
    }
}
