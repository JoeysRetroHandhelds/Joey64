package com.joeysretro.joey64

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DataFeedTest {
    @Test fun dueByAge() {
        val dir = Files.createTempDirectory("feed").toFile()
        val f = File(dir, "index.json")
        assertTrue("no kept copy", DataFeed.due(f))
        f.writeText("{}")
        val now = f.lastModified()
        assertFalse(DataFeed.due(f, now + DataFeed.RECHECK_MS - 1))
        assertTrue(DataFeed.due(f, now + DataFeed.RECHECK_MS))
        assertTrue("dated in the future", DataFeed.due(f, now - 60_000))
        dir.deleteRecursively()
    }

    @Test fun conditions() {
        assertNull(DataFeed.condition(null))
        assertNull(DataFeed.condition("  "))
        assertEquals("If-None-Match" to "\"abc\"", DataFeed.condition("\"abc\"\n"))
        assertEquals("If-Modified-Since" to "Mon, 16 Nov 2026 10:00:00 GMT", DataFeed.condition("lm:Mon, 16 Nov 2026 10:00:00 GMT"))
    }

    @Test fun tags() {
        assertEquals("W/\"x\"", DataFeed.tagOf("W/\"x\"", "Mon, 16 Nov 2026 10:00:00 GMT"))
        assertEquals("lm:Mon, 16 Nov 2026 10:00:00 GMT", DataFeed.tagOf(null, "Mon, 16 Nov 2026 10:00:00 GMT"))
        assertEquals("lm:Mon, 16 Nov 2026 10:00:00 GMT", DataFeed.tagOf(" ", " Mon, 16 Nov 2026 10:00:00 GMT"))
        assertNull(DataFeed.tagOf(null, null))
    }

    @Test fun keepAndForget() {
        val dir = Files.createTempDirectory("feed").toFile()
        val f = File(dir, "wanted.json"); f.writeText("{}")
        DataFeed.keep(f, DataFeed.Got.New(ByteArray(0), "\"t1\""))
        assertEquals("\"t1\"", DataFeed.tagFile(f).readText())
        DataFeed.keep(f, DataFeed.Got.New(ByteArray(0), null))
        assertFalse("no tag: fetched whole next time", DataFeed.tagFile(f).exists())
        DataFeed.keep(f, DataFeed.Got.New(ByteArray(0), "\"t2\""))
        DataFeed.forget(f)
        assertFalse(DataFeed.tagFile(f).exists())
        dir.deleteRecursively()
    }
}
