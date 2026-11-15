package com.joeysretro.joey64.launcher.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Release notes as plain text in the update popup (Joey 2026-11-15: it showed the Markdown). */
class ReleaseNotesTest {
    // Shaped like 0.2.1's body: wrapped lines, headings, items with wrapped lines, links, code.
    private val md = """
        Import from M64Plus FZ, play on controllers without sticks, and faster quitting. If you have 0.2.0, the app
        offers this update itself (Settings › About › Check for updates).

        ## New

        - **Import from M64Plus FZ.** On first start you can now copy your saves, on one
          page. It's in Settings › Saves too.
        - **Swap D-pad hotkey:** switches the D-pad. Not set by
          default.

        ## Install

        New to Joey64: download `Joey64-0.2.1.apk` below. The [0.2.0 release](https://example.test/v0.2.0) has
        the full install steps.
    """.trimIndent()

    private val want = """
        Import from M64Plus FZ, play on controllers without sticks, and faster quitting. If you have 0.2.0, the app offers this update itself (Settings › About › Check for updates).

        New

        • Import from M64Plus FZ. On first start you can now copy your saves, on one page. It's in Settings › Saves too.
        • Swap D-pad hotkey: switches the D-pad. Not set by default.

        Install

        New to Joey64: download Joey64-0.2.1.apk below. The 0.2.0 release has the full install steps.
    """.trimIndent()

    @Test fun releaseBodyBecomesPlainText() {
        val out = ReleaseNotes.plain(md)
        assertEquals(want, out)
        for (mark in listOf("##", "**", "`", "](", "\n- ")) assertFalse(mark, out.contains(mark))
    }

    @Test fun plainTwiceIsTheSame() {
        assertEquals(want, ReleaseNotes.plain(ReleaseNotes.plain(md)))
    }

    @Test fun crlfNumberedItalicAndPlainText() {
        assertEquals("1. One two\n2. Three", ReleaseNotes.plain("1. One\r\n   two\r\n2. Three"))
        assertEquals("a big b and 2*3*4", ReleaseNotes.plain("a *big* b and 2*3*4"))
        assertEquals("Just a line.", ReleaseNotes.plain("  Just a line.  "))
        assertEquals("", ReleaseNotes.plain(""))
    }

    @Test fun parseGivesPlainNotes() {
        val json = """{"tag_name":"v0.3.0","body":"## New\n\n- **Fast**","assets":[{"name":"Joey64-0.3.0.apk","size":1,"browser_download_url":"https://example.test/a.apk"}]}"""
        assertEquals("New\n\n• Fast", Releases.parse(org.json.JSONObject(json))!!.notes)
    }
}
