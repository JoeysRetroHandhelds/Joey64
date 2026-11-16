package com.joeysretro.joey64.launcher.update

/**
 * A GitHub release body (Markdown) as plain text for the update popup and Settings › About
 * (Joey 2026-11-15: the popup showed "## New", "**Import ...**" and lines broken mid-sentence).
 * Headings become their own line, "- " items "• ", emphasis, code marks and links keep only their
 * text, and lines wrapped inside a paragraph or item are joined. Running it twice changes nothing.
 */
object ReleaseNotes {
    private val HEADING = Regex("""^#{1,6}\s+(.*?)\s*#*\s*$""")
    private val ITEM = Regex("""^\s*[-*+]\s+(.*)$""")
    private val NUMBERED = Regex("""^\s*(\d+[.)])\s+(.*)$""")
    private val LINK = Regex("""!?\[([^\]]*)]\([^)]*\)""")
    private val BOLD = Regex("""(\*\*|__)(.+?)\1""")
    private val ITALIC = Regex("""(?<![\w*])\*(?!\s)([^*]+?)(?<!\s)\*(?![\w*])""")
    private val CODE = Regex("""`([^`]*)`""")
    /** HTML comments: GitHub's page hides them, its API's body keeps them (review 2026-11-15). */
    private val COMMENT = Regex("""<!--.*?(-->|$)""", RegexOption.DOT_MATCHES_ALL)

    fun plain(markdown: String): String {
        val blocks = ArrayList<StringBuilder>()
        // A block is a heading, an item or a paragraph; [gap] says whether a blank line goes before it.
        val gaps = ArrayList<Boolean>()
        var open: StringBuilder? = null
        var sawBlank = false
        fun start(text: String, joinable: Boolean, gap: Boolean) {
            blocks.add(StringBuilder(text)); gaps.add(gap && blocks.size > 1)
            open = if (joinable) blocks.last() else null
        }
        var lastWasItem = false
        for (raw in COMMENT.replace(markdown.replace("\r\n", "\n"), "").split('\n')) {
            val line = raw.trimEnd()
            if (line.isBlank()) { open = null; sawBlank = true; continue }
            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                start(heading.groupValues[1], false, true); lastWasItem = false; sawBlank = false; continue
            }
            val item = ITEM.matchEntire(line)
            val num = NUMBERED.matchEntire(line)
            when {
                line.trimStart().startsWith("• ") -> {   // already plain
                    start(line.trim(), true, sawBlank && !lastWasItem); lastWasItem = true
                }
                item != null -> { start("• " + item.groupValues[1].trim(), true, sawBlank && !lastWasItem); lastWasItem = true }
                num != null -> {
                    start(num.groupValues[1] + " " + num.groupValues[2].trim(), true, sawBlank && !lastWasItem)
                    lastWasItem = true
                }
                open != null -> open!!.append(' ').append(line.trim())
                else -> { start(line.trim(), true, true); lastWasItem = false }
            }
            sawBlank = false
        }
        val out = StringBuilder()
        blocks.forEachIndexed { i, b ->
            if (i > 0) out.append(if (gaps[i]) "\n\n" else "\n")
            out.append(inline(b.toString()))
        }
        return out.toString().trim()
    }

    private fun inline(s: String): String {
        var t = LINK.replace(s) { it.groupValues[1] }
        t = CODE.replace(t) { it.groupValues[1] }
        t = BOLD.replace(t) { it.groupValues[2] }
        t = ITALIC.replace(t) { it.groupValues[1] }
        return t
    }
}
