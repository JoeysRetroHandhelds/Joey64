package com.joeysretro.joey64.states

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Quit saves staged in Joey64's own storage before they go into the data folder (2026-11-14, the exit delay). */
class QuitStageTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun stagedSaveReadsBackWhole() {
        val dir = File(tmp.root, "quitsave/Conker's Bad Fur Day (USA)")
        val state = ByteArray(300_000) { (it * 13).toByte() }
        val png = ByteArray(900) { it.toByte() }
        val achv = ByteArray(700) { (it * 3).toByte() }
        assertTrue(QuitStage.write(dir, state, achv, png, 1234L, 3))
        val s = QuitStage.read(dir)!!
        assertEquals(1234L, s.savedAt)
        assertEquals(3, s.keep)
        assertArrayEquals(state, s.mem())
        assertArrayEquals(achv, s.achv())
        assertArrayEquals(png, s.picture())
        assertEquals(listOf("Conker's Bad Fur Day (USA)"), QuitStage.waitingIn(File(tmp.root, "quitsave")))
    }

    @Test fun noPictureIsFine() {
        val dir = File(tmp.root, "g")
        assertTrue(QuitStage.write(dir, ByteArray(10), null, null, 5L, 1))
        assertNull(QuitStage.read(dir)!!.picture())
        assertNull(QuitStage.read(dir)!!.achv())
    }

    @Test fun aStateWithoutItsInfoIsNotStaged() {
        // Cut off before the info was renamed in: not a staged save (the recovery state still covers it).
        val dir = File(tmp.root, "g").apply { mkdirs() }
        File(dir, "mem").writeBytes(ByteArray(10))
        File(dir, "info.properties.part").writeText("saved_at=1\nkeep=1\n")
        assertNull(QuitStage.read(dir))
        assertTrue(QuitStage.waitingIn(tmp.root).isEmpty())
    }

    @Test fun clearRemovesIt() {
        val dir = File(tmp.root, "g")
        QuitStage.write(dir, ByteArray(10), null, ByteArray(3), 1L, 2)
        QuitStage.clear(dir)
        assertNull(QuitStage.read(dir))
        assertTrue(!dir.exists())
    }

    @Test fun aNewStageReplacesTheOld() {
        val dir = File(tmp.root, "g")
        QuitStage.write(dir, ByteArray(10) { 1 }, ByteArray(4), ByteArray(3), 1L, 2)
        QuitStage.write(dir, ByteArray(20) { 2 }, null, null, 9L, 5)
        val s = QuitStage.read(dir)!!
        assertEquals(9L, s.savedAt); assertEquals(5, s.keep)
        assertArrayEquals(ByteArray(20) { 2 }, s.mem())
        assertNull(s.picture())
        assertNull(s.achv())
    }

    @Test fun slotsShowAsTheyWillBeOnceItsIn() {
        val auto = StateSlots.AUTO
        val a2 = StateSlots.auto(2); val a3 = StateSlots.auto(3); val a4 = StateSlots.auto(4)
        // Keep 3: Auto is the staged one, Auto 2 the folder's Auto, Auto 3 its Auto 2, Auto 4 gone.
        assertEquals(GameStates.STAGED, GameStates.folderSlotWhileStaged(auto, 3))
        assertEquals(auto, GameStates.folderSlotWhileStaged(a2, 3))
        assertEquals(a2, GameStates.folderSlotWhileStaged(a3, 3))
        assertEquals(GameStates.NONE, GameStates.folderSlotWhileStaged(a4, 3))
        // Keep 1: only the staged one.
        assertEquals(GameStates.STAGED, GameStates.folderSlotWhileStaged(auto, 1))
        assertEquals(GameStates.NONE, GameStates.folderSlotWhileStaged(a2, 1))
        // Slots never move.
        for (s in 1..StateSlots.COUNT) assertEquals(s, GameStates.folderSlotWhileStaged(s, 3))
        // Keep 10: Auto 10 is the folder's Auto 9.
        assertEquals(StateSlots.auto(9), GameStates.folderSlotWhileStaged(StateSlots.auto(10), 10))
    }

    @Test fun twoStagedShowFirstThenTheFolder() {
        // Two quits while the data folder was out of reach, keep 4: Auto and Auto 2 staged, Auto 3-4 the folder's Auto 1-2.
        assertEquals(GameStates.STAGED, GameStates.folderSlotWhileStaged(StateSlots.AUTO, 4, 2))
        assertEquals(GameStates.STAGED - 1, GameStates.folderSlotWhileStaged(StateSlots.auto(2), 4, 2))
        assertEquals(StateSlots.AUTO, GameStates.folderSlotWhileStaged(StateSlots.auto(3), 4, 2))
        assertEquals(StateSlots.auto(2), GameStates.folderSlotWhileStaged(StateSlots.auto(4), 4, 2))
        assertEquals(GameStates.NONE, GameStates.folderSlotWhileStaged(StateSlots.auto(5), 4, 2))
    }

    @Test fun anEarlierStagedSaveIsKeptBehindTheNewOne() {
        val dir = File(tmp.root, "quitsave/Banjo")
        val older = File(tmp.root, "quitsave-older/Banjo")
        QuitStage.write(dir, ByteArray(5) { 1 }, null, null, 1L, 3)
        assertTrue(QuitStage.pushOlder(dir, older, 2))
        QuitStage.write(dir, ByteArray(5) { 2 }, null, null, 2L, 3)
        assertTrue(QuitStage.pushOlder(dir, older, 2))
        QuitStage.write(dir, ByteArray(5) { 3 }, null, null, 3L, 3)
        // Newest first: 3, then 2, then 1.
        assertEquals(listOf(3L, 2L, 1L), QuitStage.all(dir, older).map { it.second.savedAt })
        // A fourth with keep 3 (two older at most): the oldest goes.
        assertTrue(QuitStage.pushOlder(dir, older, 2))
        QuitStage.write(dir, ByteArray(5) { 4 }, null, null, 4L, 3)
        assertEquals(listOf(4L, 3L, 2L), QuitStage.all(dir, older).map { it.second.savedAt })
        // Only older ones left (the newest went in): still waiting.
        QuitStage.clear(dir)
        assertEquals(listOf("Banjo"), QuitStage.waitingIn(File(tmp.root, "quitsave"), File(tmp.root, "quitsave-older")))
        assertEquals(listOf(3L, 2L), QuitStage.all(dir, older).map { it.second.savedAt })
    }

    @Test fun keepOneDropsTheEarlierStagedSave() {
        val dir = File(tmp.root, "quitsave/Kirby")
        val older = File(tmp.root, "quitsave-older/Kirby")
        QuitStage.write(dir, ByteArray(5), null, null, 1L, 1)
        assertTrue(QuitStage.pushOlder(dir, older, 0))
        assertTrue(QuitStage.all(dir, older).isEmpty())
    }
}
