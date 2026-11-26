package com.joeysretro.joey64

import android.content.SharedPreferences
import android.view.InputDevice
import android.view.KeyEvent

// Controller profiles and stick calibration (M5 starts here; a press-to-bind remap screen comes next).
//
// The N64 mapping is by position: bottom face button = N64 A, left = N64 B, right = C-down, top = C-up,
// L2 = Z, right stick = C buttons. Android names the face buttons A/B/X/Y, and most pads send them by
// position (A bottom, B right, X left, Y top, the Xbox layout). Some send them by printed label instead:
// the AYN Odin 3 has Nintendo labels (A right, B bottom, X top, Y left) and sends BUTTON_A for its A
// (checked with getevent, 2026-11-04), so position-based code put N64 A on its right button.
object Controllers {
    enum class FaceCodes { POSITION, NINTENDO_LABELS }

    /**
     * [stickReach]: the starting guess for how far each stick direction reaches (0..1); calibration learns the real value.
     * [toggle]: the Settings.System key of the device's Xbox/Nintendo layout toggle and its Xbox value, when it has one.
     */
    class Profile(val name: String, val faceCodes: FaceCodes, val stickReach: Float, val toggle: Pair<String, String>? = null,
                  val xboxPosition: Boolean = false)

    val DEFAULT = Profile("generic", FaceCodes.POSITION, 0.9f)

    // By USB vendor/product id. Odin 3 built-in pad: "Odin Controller", 2020:0111; its stick reached
    // 77% forward and 93% back in getevent.
    // Anbernic RG477M built-in pad "retrogame_joypad", 484b:1101 (Joey 2026-11-16, getevent both ways, results/abxy):
    // Nintendo labels (A right, B bottom, X top, Y left); its toggle's NS position sends them by label (A = BTN_SOUTH,
    // BUTTON_A), its Xbox position swaps A/B and X/Y (A = BTN_EAST) and sets Settings.System abxy_mode=1 (0, or no key
    // before the first flip, in NS). [faceKey] turns the Xbox position's codes back into the NS position's, so a setup
    // (Auto map's or a hand-made one) keeps each N64 button on the same physical button whichever way the toggle is.
    // AYN (Odin 3, Odin 3 Portal, Odin 2 Portal; Joey 2026-11-16, getevent both ways, results/abxy): the Xbox/Odin
    // toggle decides the codes, not the printed letters (the Odin 3 has Nintendo letters, the Odin 3 Portal Xbox ones).
    // Odin mode is "Odin Controller" 2020:0111 and sends Nintendo-style (bottom BUTTON_B, right A, left Y, top X); Xbox
    // mode re-enumerates as "Xbox Wireless Controller" 2020:0112, a new device, and sends by position (bottom A, right B,
    // left X, top Y), with Settings.System flip_button_layout 1. So 0112 is the same built-in pad in its Xbox position:
    // [faceKey] turns its codes back into 0111's.
    private val KNOWN = mapOf(
        (0x2020 shl 16) or 0x0111 to Profile("AYN, Odin position", FaceCodes.NINTENDO_LABELS, 0.75f),
        (0x2020 shl 16) or 0x0112 to Profile("AYN, Xbox position", FaceCodes.NINTENDO_LABELS, 0.75f, xboxPosition = true),
        (0x484b shl 16) or 0x1101 to Profile("Anbernic RG477M", FaceCodes.NINTENDO_LABELS, 0.9f, "abxy_mode" to "1"),
        // Retroid Pocket (Flip recorded 2026-11-16): Nintendo letters. Nintendo position = "Retroid Pocket Controller",
        // each button sends its own letter; Xbox position = "X-Box Controller", same ids, positional codes,
        // Settings.System flip_button_layout=1 (absent until the first flip).
        (0x2022 shl 16) or 0x3001 to Profile("Retroid Pocket", FaceCodes.NINTENDO_LABELS, 0.9f, "flip_button_layout" to "1"),
    )

    fun profile(dev: InputDevice?): Profile =
        dev?.takeUnless { com.joeysretro.joey64.controls.AutoMap.passedOn(it.vendorId, it.productId, it.name ?: "") }
            ?.let { KNOWN[(it.vendorId shl 16) or it.productId] } ?: DEFAULT

    @Volatile private var resolver: android.content.ContentResolver? = null
    @Volatile private var toggleRead = 0L
    @Volatile private var toggleValue: String? = null

    /** Gives [faceKey] the settings to read; the launcher and the game screen call it in onCreate. */
    fun init(context: android.content.Context) { resolver = context.applicationContext.contentResolver }

    /** Whether [p]'s toggle is in its Xbox position now (read at most twice a second, so a flip in game counts). */
    fun toggleXbox(p: Profile): Boolean {
        val t = p.toggle ?: return false
        val now = android.os.SystemClock.uptimeMillis()
        if (now - toggleRead > 500) {
            toggleRead = now
            toggleValue = try { resolver?.let { android.provider.Settings.System.getString(it, t.first) } }
                catch (e: Exception) { null }
        }
        return toggleValue == t.second
    }

    /** [keyCode] from [dev] as its toggle's Nintendo position sends it: A/B and X/Y swapped back in the Xbox position. */
    fun faceKey(dev: InputDevice?, keyCode: Int): Int {
        val p = profile(dev)
        if (!p.xboxPosition && (p.toggle == null || !toggleXbox(p))) return keyCode
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
            KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
            KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
            else -> keyCode
        }
    }

    // For pads not in [KNOWN] yet: what a report needs to add one (Joey 2026-11-16, "start logging this for devices we
    // don't know"). Each face button's first press per pad is logged with its Linux code, and the pad's first use with
    // the Settings.System values that look like a layout toggle, so both toggle positions can be read from two logs.
    private val faceSeen = java.util.Collections.synchronizedSet(HashSet<Long>())
    private val TOGGLE_LIKE = Regex("abxy|button_layout|layout_mode|swap|xbox|nintendo|gamepad|joypad", RegexOption.IGNORE_CASE)
    private val KNOWN_TOGGLES = listOf("abxy_mode", "flip_button_layout", "temp_abxy_layout_mode")

    private fun linuxName(scan: Int) = when (scan) {
        304 -> "BTN_SOUTH"; 305 -> "BTN_EAST"; 307 -> "BTN_NORTH"; 308 -> "BTN_WEST"; else -> "scan $scan"
    }

    /** A log line for [e] when it is a face button's first press on its pad this session, else null. */
    fun faceSeenLine(e: KeyEvent): String? {
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount != 0) return null
        if (e.keyCode !in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_Y) return null
        val dev = e.device ?: return null
        if (!faceSeen.add((dev.id.toLong() shl 32) or e.keyCode.toLong())) return null
        return "Pad face: ${dev.name} (" + "%04x:%04x".format(dev.vendorId, dev.productId) + ") " +
            "${KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")} from ${linuxName(e.scanCode)}" +
            (if (profile(dev) === DEFAULT) ", pad not known" else "")
    }

    /** The pad's ids and the toggle-like Settings.System values, for its first-use log line. */
    fun padDetails(dev: InputDevice?): String {
        if (dev == null) return ""
        val found = sortedMapOf<String, String?>()
        val r = resolver
        if (r != null) {
            try {
                r.query(android.provider.Settings.System.CONTENT_URI, arrayOf("name", "value"), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val n = c.getString(0) ?: continue
                        if (TOGGLE_LIKE.containsMatchIn(n)) found[n] = c.getString(1)
                    }
                }
            } catch (e: Exception) { }
            for (k in KNOWN_TOGGLES) if (k !in found) try {
                android.provider.Settings.System.getString(r, k)?.let { found[k] = it }
            } catch (e: Exception) { }
        }
        return ", descriptor ${dev.descriptor.take(8)}, " + (if (dev.isExternal) "external" else "built in") +
            ", settings " + (if (found.isEmpty()) "none" else found.entries.joinToString(" ") { "${it.key}=${it.value}" })
    }

    private const val BOTTOM = 0
    private const val RIGHT = 1
    private const val LEFT = 2
    private const val TOP = 3

    private fun position(keyCode: Int, p: Profile): Int = when (p.faceCodes) {
        FaceCodes.POSITION -> when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> BOTTOM
            KeyEvent.KEYCODE_BUTTON_B -> RIGHT
            KeyEvent.KEYCODE_BUTTON_X -> LEFT
            KeyEvent.KEYCODE_BUTTON_Y -> TOP
            else -> -1
        }
        FaceCodes.NINTENDO_LABELS -> when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> RIGHT
            KeyEvent.KEYCODE_BUTTON_B -> BOTTOM
            KeyEvent.KEYCODE_BUTTON_X -> TOP
            KeyEvent.KEYCODE_BUTTON_Y -> LEFT
            else -> -1
        }
    }

    /** RetroPad id of a face button (mupen64plus-next: B = N64 A, Y = N64 B, A = C-down, X = C-up), or -1. */
    fun faceRetroId(keyCode: Int, p: Profile): Int = when (position(keyCode, p)) {
        BOTTOM -> 0 // RETRO_DEVICE_ID_JOYPAD_B → N64 A
        LEFT -> 1   // RETRO_DEVICE_ID_JOYPAD_Y → N64 B
        RIGHT -> 8  // RETRO_DEVICE_ID_JOYPAD_A → C-down
        TOP -> 9    // RETRO_DEVICE_ID_JOYPAD_X → C-up
        else -> -1
    }

    /**
     * Stick calibration: each direction of each axis is scaled by the furthest it has reached, so a stick
     * whose hardware stops short (Odin 3 forward: 77%) still gives full N64 deflection, and then the pair is
     * clamped to the unit circle. Reach starts at the profile's guess and only grows; it is kept per
     * device (InputDevice.descriptor) across launches.
     */
    class StickCal(private val key: String, start: Float, prefs: SharedPreferences) {
        // [axis pair][x+, x-, y+, y-]
        private val reach = Array(2) { s -> FloatArray(4) { i -> prefs.getFloat("$key:$s:$i", start) } }
        private var changed = false

        /** Returns the calibrated x, y in -1..1 for stick [s] (0 left, 1 right). */
        fun apply(s: Int, x: Float, y: Float): Pair<Float, Float> {
            val r = reach[s]
            fun one(v: Float, pos: Int): Float {
                val i = if (v >= 0) pos else pos + 1
                val a = kotlin.math.abs(v)
                if (a > r[i]) { r[i] = a; changed = true }
                return v / r[i]
            }
            var cx = one(x, 0)
            var cy = one(y, 2)
            val len = kotlin.math.sqrt(cx * cx + cy * cy)
            if (len > 1f) { cx /= len; cy /= len }
            return cx to cy
        }

        fun save(prefs: SharedPreferences) {
            if (!changed) return
            val e = prefs.edit()
            for (s in 0..1) for (i in 0..3) e.putFloat("$key:$s:$i", reach[s][i])
            e.apply()
            changed = false
        }
    }
}
