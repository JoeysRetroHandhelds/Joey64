package com.joeysretro.joey64.launcher.storage

/** The folder walk behind [TreeSpace.walk], apart from Android so it is unit tested (TreeWalkTest). */
object TreeWalk {
    /**
     * Every file below the folder [rootId] (at [rootPath]), folders walked to [maxDepth], as path to (document id, entry).
     * [list] gives a folder's children as (document id, entry), or null when it couldn't be listed: [lenient] leaves that
     * folder out, else the whole walk is null. [failed] hears of each folder left out, as (its parent's id, its id).
     */
    fun walk(
        rootId: String,
        rootPath: List<String>,
        maxDepth: Int,
        lenient: Boolean,
        failed: ((parent: String, id: String) -> Unit)? = null,
        list: (String) -> List<Pair<String, SpaceEntry>>?,
    ): List<Pair<List<String>, Pair<String, SpaceEntry>>>? {
        val out = ArrayList<Pair<List<String>, Pair<String, SpaceEntry>>>()
        fun go(id: String, parent: String, at: List<String>, depth: Int): Boolean {
            val children = list(id) ?: return lenient.also { if (it) failed?.invoke(parent, id) }
            for ((docId, entry) in children) {
                if (entry.isDir) { if (depth < maxDepth && !go(docId, id, at + entry.name, depth + 1)) return false }
                else out += (at + entry.name) to (docId to entry)
            }
            return true
        }
        return if (go(rootId, "", rootPath, 1)) out else null
    }
}
