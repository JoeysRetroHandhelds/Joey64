package com.joeysretro.joey64.video

import com.joeysretro.joey64.launcher.library.RomReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TexPacksTest {
    private fun seen(path: String, size: Long = 100) = TexPacks.Seen(path.split('/'), "doc:$path", size)
    private fun find(vararg s: TexPacks.Seen) = TexPacks.find(s.toList(), "tree", { "uri:$it" }, { p -> "dir:" + p.joinToString("/") })

    @Test fun htsAtTheTopAndInAFolder() {
        val packs = find(seen("GOLDENEYE_HIRESTEXTURES.hts", 593), seen("MK64 Reloaded/MARIOKART64_HIRESTEXTURES.hts", 3600), seen("readme.txt"))
        assertEquals(2, packs.size)
        val ge = packs.first { it.packName == "GOLDENEYE" }
        assertEquals("GOLDENEYE", ge.shown)
        assertEquals("uri:doc:GOLDENEYE_HIRESTEXTURES.hts", ge.id)
        assertFalse(ge.png)
        val mk = packs.first { it.packName == "MARIOKART64" }
        assertEquals("MK64 Reloaded", mk.shown)
        assertEquals(3600, mk.size)
    }

    @Test fun pngFolderNamedAsItsPngs() {
        val packs = find(
            seen("SM64 Reloaded/SUPER MARIO 64/ui/SUPER MARIO 64#1234ABCD#0#2_all.png", 10),
            seen("SM64 Reloaded/SUPER MARIO 64/level/SUPER MARIO 64#89ABCDEF#3#1_all.png", 20),
            seen("loose/picture.png", 5),
            seen("Misc/OTHER#1#0#2_all.png", 7)) // a PNG whose folder isn't its NAME: not a pack
        assertEquals(1, packs.size)
        val p = packs.single()
        assertTrue(p.png)
        assertEquals("SUPER MARIO 64", p.packName)
        assertEquals("SM64 Reloaded", p.shown)
        assertEquals(30, p.size)
        assertEquals("dir:SM64 Reloaded/SUPER MARIO 64", p.id)
    }

    @Test fun pickAutoPrefersHtsAndFallsBack() {
        val hts = TexPack("a", "t", "GOLDENEYE", "GoldenEye HD", "GOLDENEYE_HIRESTEXTURES.hts", 1, false)
        val png = TexPack("b", "t", "GOLDENEYE", "GoldenEye 4K", "GOLDENEYE", 2, true)
        val other = TexPack("c", "t", "MARIOKART64", "MK", "x", 3, false)
        val index = listOf(png, hts, other)
        assertEquals(hts, TexPacks.pick(index, "GOLDENEYE", "on"))
        assertEquals(hts, TexPacks.pick(index, "goldeneye", "on"))
        assertEquals(png, TexPacks.pick(index, "GOLDENEYE", "pack:b"))
        assertEquals(hts, TexPacks.pick(index, "GOLDENEYE", "pack:gone"))
        assertNull(TexPacks.pick(index, "GOLDENEYE", "off"))
        assertNull(TexPacks.pick(index, "ZELDA", "on"))
        assertNull(TexPacks.pick(index, null, "on"))
    }

    @Test fun htsWholeReadsTheIndexPlace() {
        fun file(pos: Long): File = File.createTempFile("pack", ".hts").apply {
            deleteOnExit()
            writeBytes(ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).putInt(0x08000000).putInt(0x123).putLong(pos).array())
        }
        assertTrue(TexPacks.htsWhole(file(123456)))
        assertFalse(TexPacks.htsWhole(file(-1)))
        assertFalse(TexPacks.htsWhole(File("no such file")))
    }

    @Test fun packNameAsGlideN64FilesIt() {
        fun name(s: String) = RomReader.packNameOf(s.toByteArray(Charsets.ISO_8859_1).copyOf(20))
        assertEquals("GOLDENEYE", name("GOLDENEYE           "))
        assertEquals("SUPER MARIO 64", name("SUPER MARIO 64      "))
        assertEquals("ZELDA MAJORA'S MASK", name("ZELDA MAJORA'S MASK "))
        assertEquals("A-B-C", name("A:B/C"))
        assertEquals("CUT", name("CUT\u0000AFTER NUL"))
    }

    @Test fun sizes() {
        assertEquals("593 MB", TexPacks.sizeLabel(593L shl 20))
        assertEquals("3.6 GB", TexPacks.sizeLabel((36L shl 30) / 10))
    }
}
