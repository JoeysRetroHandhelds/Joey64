package com.joeysretro.joey64.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResNoteTest {
    private val mb = 1024L * 1024

    @Test fun lowerTierGpus() {
        assertTrue("Duo", ResNote.lowGpu("adreno722"))
        assertTrue(ResNote.lowGpu("adreno650"))
        assertTrue("Flip", ResNote.lowGpu("mali52"))
        assertTrue(ResNote.lowGpu("mali57"))
        assertFalse("Thor / Odin 2 Portal", ResNote.lowGpu("adreno740"))
        assertFalse("Odin 3", ResNote.lowGpu("adreno830"))
        assertFalse(ResNote.lowGpu("adreno730"))
        assertFalse("RG477M", ResNote.lowGpu("mali615"))
        assertFalse("model not known", ResNote.lowGpu("adreno0"))
        assertFalse(ResNote.lowGpu("other0"))
        assertFalse(ResNote.lowGpu(null))
    }

    @Test fun whichText() {
        assertEquals("Pocket Block", ResNote.RAM, ResNote.text(1934 * mb, "adreno740"))
        assertEquals("2 GB wins over the GPU", ResNote.RAM, ResNote.text(1934 * mb, "mali52"))
        assertEquals(ResNote.RAM, ResNote.text(2200 * mb, null))
        assertEquals("Duo", ResNote.GPU, ResNote.text(7600 * mb, "adreno722"))
        assertNull("Odin 2 Portal", ResNote.text(11_000 * mb, "adreno740"))
        assertNull(ResNote.text(2201 * mb, null))
        assertNull("memory not known", ResNote.text(0, null))
    }
}
