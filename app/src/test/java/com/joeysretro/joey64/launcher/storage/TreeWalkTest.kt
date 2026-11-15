package com.joeysretro.joey64.launcher.storage

import com.joeysretro.joey64.launcher.library.ScanPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeWalkTest {
    private fun file(name: String) = SpaceEntry(name, false, 10, 1)
    private fun dir(name: String) = SpaceEntry(name, true, 0, 1)

    /** root: a.z64, sub/ (b.z64, deep/ (c.z64)) */
    private val tree = mapOf(
        "root" to listOf("1" to file("a.z64"), "sub" to dir("sub")),
        "sub" to listOf("2" to file("b.z64"), "deep" to dir("deep")),
        "deep" to listOf("3" to file("c.z64")),
    )

    private fun names(r: List<Pair<List<String>, Pair<String, SpaceEntry>>>?) = r?.map { it.first.joinToString("/") }

    @Test fun walksEveryFolder() {
        assertEquals(listOf("a.z64", "sub/b.z64", "sub/deep/c.z64"), names(TreeWalk.walk("root", emptyList(), 4, false) { tree[it] }))
    }

    @Test fun stopsAtMaxDepth() {
        assertEquals(listOf("a.z64", "sub/b.z64"), names(TreeWalk.walk("root", emptyList(), 2, false) { tree[it] }))
    }

    // Odin 3 Portal 2026-11-14: a listing that failed read as empty and the folder's games all looked gone
    @Test fun aFailedListingIsNullWhenStrict() {
        assertNull(TreeWalk.walk("root", emptyList(), 4, false) { if (it == "root") null else tree[it] })
        assertNull(TreeWalk.walk("root", emptyList(), 4, false) { if (it == "deep") null else tree[it] })
    }

    @Test fun aFailedListingIsLeftOutWhenLenient() {
        assertEquals(listOf("a.z64", "sub/b.z64"), names(TreeWalk.walk("root", emptyList(), 4, true) { if (it == "deep") null else tree[it] }))
        assertEquals(emptyList<String>(), names(TreeWalk.walk("root", emptyList(), 4, true) { null }))
    }

    @Test fun anEmptyFolderIsNotAFailure() {
        assertEquals(emptyList<String>(), names(TreeWalk.walk("root", emptyList(), 4, false) { emptyList() }))
    }

    @Test fun aFolderThatHadGamesAndListsNoneIsReadAgain() {
        assertTrue(ScanPlanner.relist(0, 6))
        assertFalse(ScanPlanner.relist(0, 0))
        assertFalse(ScanPlanner.relist(5, 6))
    }

    @Test fun lenientNamesTheFolderItLeftOut() {
        val failed = ArrayList<Pair<String, String>>()
        val r = TreeWalk.walk("root", emptyList(), 4, true, failed = { p, id -> failed += p to id }) { if (it == "sub") null else tree[it] }
        assertEquals(listOf("a.z64"), names(r))
        assertEquals(listOf("root" to "sub"), failed)
    }

    @Test fun unlistedFolderKeepsOnlyItsOwnGames() {
        val tree = "content://t/tree/primary%3AR"
        val sub = "$tree/document/primary%3AR%2Fsub"
        val known = listOf(
            com.joeysretro.joey64.launcher.library.KnownFile("$sub%2Fb.z64", tree, 1, 1, 1),
            com.joeysretro.joey64.launcher.library.KnownFile("$tree/document/primary%3AR%2Fgone.z64", tree, 1, 1, 2),
            com.joeysretro.joey64.launcher.library.KnownFile("$tree/document/primary%3AR%2Fsubway.z64", tree, 1, 1, 3))
        val plan = ScanPlanner.plan(emptyList(), known, setOf(tree), setOf(ScanPlanner.childPrefix(sub)))
        assertEquals(listOf("$tree/document/primary%3AR%2Fgone.z64", "$tree/document/primary%3AR%2Fsubway.z64"), plan.gone)
    }
}
