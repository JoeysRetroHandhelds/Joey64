package com.joeysretro.joey64.states

import android.graphics.Bitmap
import com.joeysretro.joey64.NativeBridge
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The game's picture as the core last drew it (plan, Phase 3: screenshots, and the picture beside
 * each state slot), from [NativeBridge.captureFrame]: 8 bytes of width and height, then RGBA8 rows.
 */
object FramePicture {
    /** Width and height from a capture's header, or null if the bytes don't hold that many pixels. */
    fun size(capture: ByteArray): Pair<Int, Int>? {
        if (capture.size < 8) return null
        fun u32(at: Int) = (0 until 4).fold(0L) { v, i -> v or ((capture[at + i].toLong() and 0xFF) shl (8 * i)) }
        val w = u32(0); val h = u32(4)
        if (w <= 0 || h <= 0 || w * h * 4 != (capture.size - 8).toLong()) return null
        return w.toInt() to h.toInt()
    }

    /**
     * The picture as PNG bytes, or null if the core has none (nothing drawn yet). Blocks briefly. [maxWidth]: made
     * no wider than that, keeping its shape (a state's picture: at 4x upscaling the 2560x1920 PNG took 650 ms of a
     * quit on the Thor, 2026-11-12).
     */
    fun capturePng(timeoutMs: Int = 2000, maxWidth: Int = Int.MAX_VALUE): ByteArray? =
        NativeBridge.captureFrame(timeoutMs)?.let { encodePng(it, maxWidth) }

    /**
     * A [NativeBridge.captureFrame] capture as PNG bytes, or null if it isn't one. Separate from taking it so a save
     * state holds the game only for the capture (~15 ms) and encodes on its own thread (2026-11-14, Joey: "saving
     * states takes too long": on the Thor at 4x the encode was most of the 0.2-0.4 s a save paused).
     */
    fun encodePng(capture: ByteArray, maxWidth: Int = Int.MAX_VALUE): ByteArray? {
        val (w, h) = size(capture) ?: return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // ARGB_8888's memory order is R, G, B, A: the capture's own.
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(capture, 8, capture.size - 8))
        // As it's shown: the screen is always 4:3 (parallel-RDP hands over e.g. 640x240).
        val (sw, sh) = shownSize(w, h).let { (fw, fh) -> if (fw <= maxWidth) fw to fh else maxWidth to (fh * maxWidth + fw / 2) / fw }
        val shown = if (sw == w && sh == h) bmp else Bitmap.createScaledBitmap(bmp, sw, sh, true).also { bmp.recycle() }
        val out = ByteArrayOutputStream(sw * sh)
        shown.compress(Bitmap.CompressFormat.PNG, 100, out)
        shown.recycle()
        // The game's border with it, so the picture held over a start from this state is cut as the game will be.
        val trim = try { NativeBridge.viTrim() } catch (e: Throwable) { null }
        return withTrim(out.toByteArray(), trim)
    }

    /** The PNG text chunk's keyword for the game's border ("left,top,right,bottom", ten thousandths of the picture). */
    const val TRIM_KEY = "Joey64 trim"

    /** [png] with the game's border [trim] in a text chunk after its header; as it is when there's no border. */
    fun withTrim(png: ByteArray, trim: IntArray?): ByteArray {
        if (trim == null || trim.size < 4 || trim.take(4).all { it == 0 } || png.size < 33) return png
        val data = (TRIM_KEY + "\u0000" + trim.take(4).joinToString(",")).toByteArray(Charsets.ISO_8859_1)
        val type = "tEXt".toByteArray(Charsets.ISO_8859_1)
        val crc = java.util.zip.CRC32().apply { update(type); update(data) }.value
        val chunk = ByteBuffer.allocate(12 + data.size).putInt(data.size).put(type).put(data).putInt(crc.toInt()).array()
        // The signature (8) and IHDR (25) come first.
        return png.copyOfRange(0, 33) + chunk + png.copyOfRange(33, png.size)
    }

    /** The game's border saved in [png] by [withTrim], or null (none, or a picture from before). */
    fun trimOf(png: ByteArray): IntArray? {
        var at = 8
        while (at + 8 <= png.size) {
            val len = ByteBuffer.wrap(png, at, 4).int
            if (len < 0 || at + 12 + len > png.size) return null
            val type = String(png, at + 4, 4, Charsets.ISO_8859_1)
            if (type == "IDAT" || type == "IEND") return null
            if (type == "tEXt") {
                val text = String(png, at + 8, len, Charsets.ISO_8859_1)
                if (text.startsWith(TRIM_KEY + "\u0000")) {
                    val v = text.substringAfter('\u0000').split(',').mapNotNull { it.trim().toIntOrNull() }
                    return if (v.size == 4 && v.all { it in 0..5000 }) v.toIntArray() else null
                }
            }
            at += 12 + len
        }
        return null
    }

    /** The 4:3 size the picture is shown at: the width kept, the height made to fit (640x240 -> 640x480). */
    fun shownSize(w: Int, h: Int): Pair<Int, Int> = w to (w * 3 + 2) / 4
}
