package com.joeysretro.joey64.controls

import org.json.JSONObject

/**
 * Controls › Connecting (approved newpad3 mockup 2026-11-17, joey64-notes docs/img/newpad3.png, "Approve as drawn"): what
 * happens when a controller connects, the same for every game on this device, in `files/controls/connecting.json`. The
 * same two rows are at the foot of the new controller card ([NewPadCard]).
 */
enum class AskWhen(val label: String, val detail: String) {
    Once("Ask once", "The first time a controller connects; then it keeps its player"),
    Every("Ask every time", "Every time one connects, its last player focused"),
    Never("Don't ask", "New ones take the first free player, known ones their own"),
}

/** Where the built-in controls go when a controller takes their player. */
enum class BuiltInTaken(val label: String, val detail: String) {
    Move("Move to the next free player", "Two can play: the device and the controller"),
    Off("Turn off", "Only the controller plays · no stray presses on the device"),
}

data class ConnectSettings(val ask: AskWhen = AskWhen.Once, val builtIn: BuiltInTaken = BuiltInTaken.Move) {
    fun toJson(): JSONObject = JSONObject().put("ask", ask.name).put("builtIn", builtIn.name)

    companion object {
        const val ASK_HELP = "Whether Joey64 asks which player a controller is."
        const val BUILT_IN_HELP = "When a controller takes the built-in controls' player."
        const val TAB_HELP = "What happens when a controller connects, on this device for every game."

        fun fromJson(o: JSONObject) = ConnectSettings(
            AskWhen.entries.firstOrNull { it.name == o.optString("ask") } ?: AskWhen.Once,
            BuiltInTaken.entries.firstOrNull { it.name == o.optString("builtIn") } ?: BuiltInTaken.Move)
    }
}

/**
 * The new controller card's rows and what a pick does, for a controller ([pad]) that connected. [seen]: Joey64 has seen it
 * before (the card then says "<name> connected", Ask every time). [layout]: Auto map's name for it, or null (by position).
 * [builtInName]: the built-in player's name on the card ("Built-in controls").
 */
class NewPadCard(val set: ControlsSet, val pad: DeviceChoice.Pad, val seen: Boolean, val layout: String?, val settings: ConnectSettings) {
    /** One player row: [player] 0-3, [who] has it now, [detail] what a pick does. */
    data class Row(val player: Int, val label: String, val who: String, val detail: String?)

    val title = if (seen) "${pad.name} connected" else "New controller: ${pad.name}"
    val notNowDetail = if (seen) "Leave it unused this time" else "Leave it unused · set it in Controls"

    /** The player that has this controller now, or -1. */
    val current = set.players.indexOfFirst { same(it.device, pad) }

    /**
     * The row focused first: a controller seen before, the player it has (its last); a new one, Player 1 unless a controller
     * someone picked has it, else the next free player (Joey 2026-11-17: "a) player 1").
     */
    val focus: Int = when {
        seen && current >= 0 -> current
        set.players[0].device !is DeviceChoice.Pad -> 0
        else -> set.players.indexOfFirst { it.device == DeviceChoice.None }.takeIf { it >= 0 } ?: 0
    }

    /** The rows, the focused one first and the rest in order (the mockup's screen 6). */
    val rows: List<Row> = (listOf(focus) + (0..3).filter { it != focus }).map { row(it) }

    private fun who(d: DeviceChoice) = when (d) {
        DeviceChoice.BuiltIn -> "Built-in controls"
        DeviceChoice.None -> "Free"
        is DeviceChoice.Pad -> d.name
    }

    private fun row(p: Int): Row {
        val d = set.players[p].device
        val lead = when { p != focus -> ""; seen && p == current -> "Last time · "; else -> "You play on it · " }
        val dest = movesTo(p)
        val detail = when {
            same(d, pad) -> if (lead.isNotEmpty()) lead.removeSuffix(" · ") else null
            d == DeviceChoice.BuiltIn -> lead + (if (dest >= 0) "the built-in controls move to Player ${dest + 1}" else "the built-in controls turn off")
                .let { if (lead.isEmpty()) it.replaceFirstChar(Char::uppercase) else it }
            d is DeviceChoice.Pad -> "Takes it from ${d.name}, " + (if (dest >= 0) "which moves to Player ${dest + 1}" else "which then has no player")
            p == focus && seen && p == current -> "Last time"
            p == focus -> "The next free player".takeIf { p != 0 || set.players.none { it.device == DeviceChoice.BuiltIn } }
            settings.builtIn == BuiltInTaken.Move && set.players.any { it.device == DeviceChoice.BuiltIn } &&
                p == set.players.indexOfFirst { it.device == DeviceChoice.None } -> "Plays alongside the built-in controls"
            else -> null
        }
        return Row(p, "Player ${p + 1}", who(d), detail)
    }

    /** Where player [p]'s controller goes when this one takes [p]: a player, or -1 (it has none then). */
    fun movesTo(p: Int): Int {
        val d = set.players[p].device
        if (d == DeviceChoice.None || same(d, pad)) return -1
        if (d == DeviceChoice.BuiltIn && settings.builtIn == BuiltInTaken.Off) return -1
        if (current >= 0) return current // a swap: it takes this controller's old player
        return (0..3).firstOrNull { it != p && set.players[it].device == DeviceChoice.None } ?: -1
    }

    /**
     * The set after this controller is given to [p] with [fresh] (Auto map's setup for it, kept for a new controller): what
     * [p] had moves as [movesTo] says, with its setup; a swap swaps the two setups (as the Controls page's Controller row).
     */
    fun pick(p: Int, fresh: PlayerSetup): ControlsSet {
        if (current == p) return set
        val old = set.players[p]
        val dest = movesTo(p)
        var out = set
        if (current >= 0) {
            out = out.with(p, set.players[current]).with(current, if (dest == current) old else old.copy(device = DeviceChoice.None))
            return out
        }
        out = out.with(p, fresh.copy(device = pad))
        if (dest >= 0) out = out.with(dest, old)
        return out
    }

    companion object {
        fun same(a: DeviceChoice, b: DeviceChoice.Pad) = a is DeviceChoice.Pad && a.descriptor == b.descriptor
    }
}
