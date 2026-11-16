package com.joeysretro.joey64.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TexConvStatusTest {
    @Test fun roundTrip() {
        val s = TexConvStatus(TexConvStatus.Cur("pack-a", "GoldenEye 007 4K", 2, 5812, 9400, "Mario Kart 64"), 1,
            mapOf("pack-b" to (6_200L shl 20 to (3_100L shl 20))), mapOf("pack-c" to "no PNGs found in it"), System.currentTimeMillis())
        val back = TexConvStatus.of(org.json.JSONObject(s.toJson().toString()))
        assertEquals(s, back)
        assertEquals(61, back.cur!!.percent)
        assertTrue(back.fresh)
    }

    @Test fun nothingOnAndStale() {
        val s = TexConvStatus(null, 0, emptyMap(), emptyMap(), System.currentTimeMillis() - TexConvStatus.STALE_MS - 1)
        val back = TexConvStatus.of(org.json.JSONObject(s.toJson().toString()))
        assertNull(back.cur)
        assertFalse(back.fresh)
    }

    @Test fun percentNeverSaysDoneEarly() {
        assertEquals(99, TexConvStatus.Cur("a", "A", 2, 9400, 9400, null).percent) // the file's written after the last texture
        assertEquals(0, TexConvStatus.Cur("a", "A", 1, 0, 0, null).percent)
    }
}
