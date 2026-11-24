package com.joeysretro.joey64.reports

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whole session logs (SessionLogs, server/reports/src/logs.js): the id the server takes, and the cleaning. */
class SessionLogsTest {
    private val md5 = "9feee5da895fb2e115bb15d1c4a9f906"

    @Test fun newIdsAre32Hex() {
        val ids = (1..50).map { SessionLogs.newId() }
        assertTrue(ids.all { Regex("[0-9a-f]{32}").matches(it) })
        assertEquals(50, ids.toSet().size)
    }

    @Test fun aSessionCarriesItsLogIdOnlyWhenItIsOne() {
        val id = SessionLogs.newId()
        assertEquals(id, ReportJson.session(JSONObject().put("md5", md5).put("logId", id), null)!!.getString("logId"))
        assertFalse(ReportJson.session(JSONObject().put("md5", md5).put("logId", "ABC"), null)!!.has("logId"))
        assertFalse(ReportJson.session(JSONObject().put("md5", md5), null)!!.has("logId"))
        assertNull(ReportJson.session(JSONObject().put("md5", "zz").put("logId", id), null))
    }

    @Test fun aWholeLogIsCleanedAsTheExcerptIs() {
        val raw = "11-24 I Joey64: intent: data content://com.android.externalstorage.documents/tree/primary%3AROMs/document/x.z64\r\n" +
            "11-24 I joey64host: ROM /storage/emulated/0/ROMs/Conker.z64 opened\u0007\n" +
            "11-24 I Joey64: netplay: Sam joined from 81.2.69.160\n" +
            "11-24 I Joey64: Joey64 0.2.7 on RG 476H\n"
        val out = SessionLogs.cleanBlock(raw, listOf("Sam"))
        assertFalse(out.contains("content://"))
        assertFalse(out.contains("/storage/"))
        assertFalse(out.contains("Sam"))
        assertFalse(out.contains("81.2.69.160"))
        assertFalse(out.contains('\r') || out.contains('\u0007'))
        assertTrue(out.contains("Joey64 0.2.7 on RG 476H"))
        assertEquals(4, out.trimEnd('\n').lines().size)
    }
}
