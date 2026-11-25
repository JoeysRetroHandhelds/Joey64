package com.joeysretro.joey64.netplay

import org.junit.Assert.assertEquals
import org.junit.Test

class RollbackCapTest {
    @Test fun fastAdrenosGuessTheWindow() {
        assertEquals(12, RollbackCap.of(11_000, 3_200, "adreno740")) // Odin 2 Portal
        assertEquals(12, RollbackCap.of(11_000, 4_320, "adreno830")) // Odin 3 Portal
        assertEquals(12, RollbackCap.of(7_400, 3_000, "adreno730"))
    }

    @Test fun othersUseTheSharedDelay() {
        assertEquals(0, RollbackCap.of(7_400, 2_840, "adreno650"))   // Retroid Pocket 5: a rollback a stutter
        assertEquals(0, RollbackCap.of(11_000, 3_350, "mali615"))    // RG477M: the same
        assertEquals(0, RollbackCap.of(7_400, 2_800, "adreno722"))   // Duo: not measured
        assertEquals(0, RollbackCap.of(1_900, 1_800, "mali52"))      // RG353P
        assertEquals(0, RollbackCap.of(3_600, 2_400, "adreno613"))   // RG 55G1
        assertEquals(0, RollbackCap.of(3_000, 3_200, "adreno740"))   // under 4 GB
        assertEquals(0, RollbackCap.of(8_000, 2_000, "adreno740"))   // no fast core
        assertEquals(0, RollbackCap.of(8_000, 3_000, null))          // GPU not known
    }

    @Test fun unreadableValuesDontBlock() {
        assertEquals(12, RollbackCap.of(0, 0, "adreno830"))
    }
}
