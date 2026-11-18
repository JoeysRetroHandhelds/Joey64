package com.joeysretro.joey64.states

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.ByteBuffer

/** The game's border saved with a state's picture, for the picture held over a start (Smash, Joey 2026-11-17). */
class FramePictureTrimTest {
    private fun chunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray(Charsets.ISO_8859_1)
        val crc = java.util.zip.CRC32().apply { update(t); update(data) }.value
        return ByteBuffer.allocate(12 + data.size).putInt(data.size).put(t).put(data).putInt(crc.toInt()).array()
    }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        chunk("IHDR", ByteArray(13)) + chunk("IDAT", ByteArray(5)) + chunk("IEND", ByteArray(0))

    @Test fun theTrimGoesInAndComesBack() {
        val out = FramePicture.withTrim(png, intArrayOf(125, 41, 109, 83))
        assertArrayEquals(intArrayOf(125, 41, 109, 83), FramePicture.trimOf(out))
        // The rest of the file is as it was, the chunk after the header.
        assertArrayEquals(png.copyOfRange(0, 33), out.copyOfRange(0, 33))
        assertArrayEquals(png.copyOfRange(33, png.size), out.copyOfRange(out.size - (png.size - 33), out.size))
    }

    @Test fun noBorderOrAnOldPictureHasNone() {
        assertSame(png, FramePicture.withTrim(png, intArrayOf(0, 0, 0, 0)))
        assertSame(png, FramePicture.withTrim(png, null))
        assertNull(FramePicture.trimOf(png))
        assertNull(FramePicture.trimOf(ByteArray(4)))
    }
}
