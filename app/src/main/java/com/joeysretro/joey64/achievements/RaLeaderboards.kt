package com.joeysretro.joey64.achievements

import com.joeysretro.joey64.JLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** One of the loaded game's leaderboards (rc_client_leaderboard_t). [value] is the live time or score while [running]. */
data class RaBoard(
    val id: Int,
    val title: String,
    val description: String,
    val value: String,
    val state: Int,
    val lowerIsBetter: Boolean,
    val format: Int,
    val set: Int,
) {
    val running get() = state == TRACKING
    /** "Lowest time wins", "Highest score wins" (the detail screen's line, approved s6 2026-11-04). */
    val rule get() = (if (lowerIsBetter) "Lowest " else "Highest ") + (if (format == FORMAT_TIME) "time" else "score") + " wins"

    companion object {
        const val TRACKING = 2
        const val FORMAT_TIME = 0

        fun parseList(json: String): List<RaBoard> = try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::parse) }
        } catch (e: Exception) { emptyList() }

        fun parse(o: JSONObject) = RaBoard(o.optInt("id"), o.optString("title"), o.optString("description"), o.optString("value"),
            o.optInt("state"), o.optBoolean("lowerIsBetter"), o.optInt("format"), o.optInt("set"))
    }
}

/** A leaderboard entry from the server (rc_client_leaderboard_entry_t): [submitted] in Unix seconds. */
data class RaEntry(val user: String, val display: String, val submitted: Long, val rank: Int, val avatar: String)

/** A page of a leaderboard's entries: [total] on the board, [userIndex] the signed-in user's place in [entries] (-1: not in it). */
data class RaEntries(val total: Int, val userIndex: Int, val entries: List<RaEntry>) {
    val you get() = entries.getOrNull(userIndex)

    companion object {
        fun parse(ev: JSONObject): RaEntries {
            val a = ev.optJSONArray("entries") ?: JSONArray()
            return RaEntries(ev.optInt("total"), ev.optInt("userIndex", -1), (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { RaEntry(it.optString("user"), it.optString("display"), it.optLong("submitted"), it.optInt("rank"), it.optString("avatar")) }
            })
        }
    }
}

/**
 * The Leaderboards tab and its detail screen (approved 2026-11-04, ach3.html / build_ach3.py s6): the running game's
 * boards from rc_client, and their entries from the server (top 10, around you), one request at a time per board and
 * kind, answered through RaBridge's "lbEntries" event. Only Hardcore plays have leaderboards (rcheevos runs them only
 * in hardcore). With the made-up account (RA_FAKE) the boards and entries are made up too.
 */
object RaLeaderboards : RaBridge.Listener {
    private const val TAG = "joey64ra"
    private const val WAIT_MS = 15_000L
    private val nextReq = AtomicInteger(1)
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<RaEntries?>>()
    @Volatile private var listening = false

    /** The loaded game's boards (the made-up ones with RA_FAKE). */
    fun list(): List<RaBoard> = if (RaBridge.debugCache != null) fakeBoards() else RaBoard.parseList(RaBridge.leaderboardList())

    /** [count] entries around the signed-in user ([around]), or from [first] (1 = the top). Null offline or on an error. */
    suspend fun entries(board: Int, around: Boolean, first: Int = 1, count: Int = 10): RaEntries? {
        if (RaBridge.debugCache != null) { delay(300); return fakeEntries(board, around, count) }
        if (!listening) { listening = true; RaBridge.listeners += this }
        val req = nextReq.getAndIncrement()
        val answer = CompletableDeferred<RaEntries?>()
        waiting[req] = answer
        RaBridge.fetchEntries(board, around, first, count, req)
        return withTimeoutOrNull(WAIT_MS) { answer.await() }.also { waiting.remove(req) }
    }

    override fun onEvent(event: JSONObject) {
        if (event.optString("event") != "lbEntries") return
        val req = event.optInt("req")
        val result = event.optInt("result")
        if (result != 0) JLog.w(TAG, "leaderboard entries: ${event.optString("error")} ($result)")
        waiting.remove(req)?.complete(if (result == 0) RaEntries.parse(event) else null)
    }

    // --- RA_FAKE ---------------------------------------------------------------------------------
    private fun fakeBoards() = listOf(
        RaBoard(501, "Mario Raceway · Time Trial", "Fastest 3 laps in Time Trial", "1:31.27", RaBoard.TRACKING, true, RaBoard.FORMAT_TIME, 1001),
        RaBoard(502, "Luigi Raceway · Fastest Lap", "Fastest single lap in Time Trial", "", 1, true, RaBoard.FORMAT_TIME, 1001),
        RaBoard(503, "Rainbow Road · Time Trial", "Fastest 3 laps in Time Trial", "", 1, true, RaBoard.FORMAT_TIME, 1001),
        RaBoard(504, "Battle · Balloons Popped", "Most balloons popped in one battle", "", 1, false, 1, 1001),
    )

    private val names = listOf("Kaizo_Kart", "redshell", "LapLord", "N64Nerd", "drift_queen", "Wario99", "boxjumper", "PeachPit",
        "TurboTom", "ghostrider", "MiniTurbo", "BlueShell", "rr_runner", "kartographer", "SlipStream")

    private fun fakeEntries(board: Int, around: Boolean, count: Int): RaEntries {
        val now = System.currentTimeMillis() / 1000
        val total = 340 - (board - 501) * 60
        val youRank = 12 + (board - 501) * 7
        val from = if (around) maxOf(1, youRank - count / 2) else 1
        val list = (0 until count).map { i ->
            val rank = from + i
            val me = rank == youRank
            val time = 85.0 + rank * 0.37
            RaEntry(if (me) (RaBridge.account.value?.display ?: "You") else names[rank % names.size], "%d:%05.2f".format((time / 60).toInt(), time % 60),
                now - (if (me) 2 * 86_400L else rank * 9_000L), rank, "")
        }
        return RaEntries(total, list.indexOfFirst { it.rank == youRank }, list)
    }
}
