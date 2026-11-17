package com.joeysretro.joey64.launcher.ingame

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.R
import com.joeysretro.joey64.launcher.components.JoeyToast
import com.joeysretro.joey64.launcher.components.rememberBadge
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import java.io.File

/**
 * A pop-up over the running game (plan, Phase 3: the pop-up system; Phase 7: achievements): the
 * approved toast's shape at Settings › Pop-up place, with nothing to press. [key] names what it's
 * about: a newer pop-up with the same key takes the place of the one showing or waiting (pressing
 * the slot hotkey five times shows the last slot, not five pop-ups in a row).
 *
 * [label] may mark a part in the accent colour with asterisks ("Achievement unlocked · *10 points*").
 * Achievement pop-ups carry their [badge] picture (approved toast.html / toast2.html): an unlock has
 * a 40 dp badge and three lines, plus [extra] when it was earned offline; [compact] ones (progress,
 * challenge failed) a 30 dp badge and two lines; [framed] ones (Beaten, Mastered) a yellow border.
 * [stayMs] overrides how long it stays (the short ones), else Settings › Pop-ups stay for;
 * [PopupQueue.UNTIL_HIDDEN] stays until it's hidden (Paused), letting newer ones show over it meanwhile.
 * [bar] (0 to 1) draws a bar under the lines (the volume).
 * [chip]: the small chip (fast-forward, rewind, slow motion; approved ffchip mockup A 2026-11-14), [name] only,
 * in orange with [warn].
 */
data class InGamePopup(
    val key: String,
    val kind: Kind,
    val label: String,
    val name: String,
    val line: String,
    val badge: File? = null,
    val compact: Boolean = false,
    val framed: Boolean = false,
    val extra: String? = null,
    val stayMs: Long? = null,
    val bar: Float? = null,
    val chip: Boolean = false,
    val warn: Boolean = false,
) {
    enum class Kind { Saved, Loaded, Slot, Screenshot, Reset, Problem, Account, Achievement, Progress, ChallengeFailed, Game, Stats,
        /** The hotkeys' (approved hotkeys mockup 2026-11-07). */
        Volume, Mute, Resolution, Paused, NoAchievements,
        /** Approved Slow motion and Rewind mockup (2026-11-08). */
        SlowMotion, Rewind,
        /** Fast forward while it's on, as Slow motion (Joey 2026-11-11: "fast forward should have an on screen indicator"). */
        FastForward,
        /** Controls extras' Tap to hold: a button held by a tap (approved mockup 2026-11-08). */
        Held,
        /** Offline unlocks: RAOfflineProxy picked and not running (approved mockup 2026-11-08). */
        AchievementsOff,
        /** The Turbo hotkey and Undo load / save state (approved mockups 2026-11-08). */
        Turbo, TurboOff, Undo,
        /** Next shader and Shader on/off (approved Picture mockup 2026-11-08). */
        Shader,
        /** A RetroArch overlay's word at game start: "No overlay for this game" (approved mockup 2026-11-08). */
        Sides,
        /** Netplay (approved mockup 2026-11-10): waiting for a slow player (screen 3), a hotkey that waits until you leave (screen 6). */
        NetWait, NetBlocked,
        /** Play's popup set Continue on Play (approved E4 2026-11-11, screens 5-6). */
        PlayNote,
        /** The first game on a device with no sticks (approved stickless mockup 2026-11-13, screen 7). */
        NoSticks,
        /** The Swap D-pad hotkey (Joey 2026-11-13). */
        DPadSwap,
        /** Auto's driver trial found one that draws (approved gputry mockup 2026-11-15, screen 3a). */
        GpuOk,
        /** A driver picked in Settings that didn't work here: Auto's pick runs (approved drvnote mockup 2026-11-15). */
        GpuNote,
        /** Auto tries a faster driver (approved drvspeed mockup 2026-11-15, screens 1, 4). */
        GpuFaster,
        /** Texture packs (approved mockup 2026-11-15, screen 5): the pack a game starts with, and its folder that can't be reached (orange). */
        TexPack, TexPackNote,
        /** RetroAchievements Hardcore (approved part B mockup 2026-11-16): what it keeps off (gold), and Hardcore off for this play (grey). */
        Hardcore, HardcoreOff,
        /** Leaderboards (approved 2026-11-04, toast2.html): started (flag), the result (trophy). */
        Leaderboard, LeaderboardResult,
        /** The tried driver wasn't faster, or didn't start: back to the one in use (screen 2b). */
        GpuBack }
}

/**
 * The pop-up queue: one at a time, each for [stayMs] (Settings › Pop-ups stay for, 5 s by default
 * as approved) or its own, then the next. Times are passed in, so it's tested without a clock.
 */
class PopupQueue(var stayMs: Long = 5_000) {
    var current: InGamePopup? = null
        private set
    /** When [current] goes. */
    var until = 0L
        private set
    private val waiting = ArrayDeque<InGamePopup>()

    private fun stay(p: InGamePopup) = p.stayMs ?: stayMs

    fun add(p: InGamePopup, now: Long) {
        val showing = current
        when {
            showing == null || showing.key == p.key -> { current = p; until = now + stay(p) }
            // One that stays until hidden lets the new one show now, and comes back after it.
            showing.stayMs == UNTIL_HIDDEN -> { waiting.removeAll { it.key == p.key }; waiting.addFirst(showing); current = p; until = now + stay(p) }
            else -> { waiting.removeAll { it.key == p.key }; waiting.addLast(p) }
        }
    }

    /** Moves on once [current]'s time is up. True if what shows changed. */
    fun tick(now: Long): Boolean {
        if (current == null || now < until) return false
        current = waiting.removeFirstOrNull()
        current?.let { until = now + stay(it) }
        return true
    }

    /** Takes a pop-up away before its time (a progress pop-up rcheevos says to hide). */
    fun remove(key: String, now: Long) {
        waiting.removeAll { it.key == key }
        if (current?.key == key) { current = waiting.removeFirstOrNull(); current?.let { until = now + stay(it) } }
    }

    fun clear() { current = null; waiting.clear() }

    companion object {
        /** A pop-up's [InGamePopup.stayMs] for one that stays until it's hidden. */
        const val UNTIL_HIDDEN = Long.MAX_VALUE / 4
    }
}

@Composable
fun InGamePopupCard(p: InGamePopup) {
    val icon = when (p.kind) {
        InGamePopup.Kind.Saved, InGamePopup.Kind.Slot -> R.drawable.ic_state_save
        InGamePopup.Kind.Loaded -> R.drawable.ic_state_load
        InGamePopup.Kind.Screenshot -> R.drawable.ic_camera
        InGamePopup.Kind.Reset -> R.drawable.ic_reset
        InGamePopup.Kind.Problem -> R.drawable.ic_alert
        InGamePopup.Kind.Account -> R.drawable.ic_user_alert
        InGamePopup.Kind.Achievement, InGamePopup.Kind.Progress, InGamePopup.Kind.Game -> R.drawable.ic_trophy
        InGamePopup.Kind.ChallengeFailed -> R.drawable.ic_target_off
        InGamePopup.Kind.Stats -> R.drawable.ic_sliders
        InGamePopup.Kind.Volume -> R.drawable.ic_volume
        InGamePopup.Kind.Mute -> if (p.name == "Sound off") R.drawable.ic_volume_off else R.drawable.ic_volume
        InGamePopup.Kind.Resolution -> R.drawable.ic_zoom_in
        InGamePopup.Kind.Paused -> R.drawable.ic_pause
        InGamePopup.Kind.NoAchievements -> R.drawable.ic_trophy
        InGamePopup.Kind.SlowMotion -> R.drawable.ic_clock
        InGamePopup.Kind.Rewind -> R.drawable.ic_history
        InGamePopup.Kind.FastForward -> R.drawable.ic_fast_forward
        InGamePopup.Kind.Held -> R.drawable.ic_hand_click
        InGamePopup.Kind.AchievementsOff -> R.drawable.ic_trophy_off
        InGamePopup.Kind.Turbo -> R.drawable.ic_bolt
        InGamePopup.Kind.TurboOff -> R.drawable.ic_bolt_off
        InGamePopup.Kind.Undo -> R.drawable.ic_arrow_back_up
        InGamePopup.Kind.Shader, InGamePopup.Kind.Sides -> R.drawable.ic_device_tv_old
        InGamePopup.Kind.NetWait -> R.drawable.ic_hourglass
        InGamePopup.Kind.NetBlocked -> R.drawable.ic_x
        InGamePopup.Kind.PlayNote -> R.drawable.ic_history
        InGamePopup.Kind.NoSticks -> R.drawable.ic_rail_controls
        InGamePopup.Kind.DPadSwap -> R.drawable.ic_rail_controls
        InGamePopup.Kind.GpuOk -> R.drawable.ic_circle_check
        InGamePopup.Kind.GpuNote -> R.drawable.ic_alert
        InGamePopup.Kind.GpuFaster -> R.drawable.ic_bolt
        InGamePopup.Kind.GpuBack -> R.drawable.ic_arrow_back_up
        InGamePopup.Kind.TexPack -> R.drawable.ic_hd
        InGamePopup.Kind.TexPackNote -> R.drawable.ic_alert
        InGamePopup.Kind.Hardcore -> R.drawable.ic_alert
        InGamePopup.Kind.HardcoreOff -> R.drawable.ic_trophy_off
        InGamePopup.Kind.Leaderboard -> R.drawable.ic_flag
        InGamePopup.Kind.LeaderboardResult -> R.drawable.ic_trophy
    }
    JoeyToast(icon, p.label, p.name, p.line, iconTint = when (p.kind) {
        InGamePopup.Kind.Problem, InGamePopup.Kind.ChallengeFailed -> JoeyTheme.colors.error
        InGamePopup.Kind.Account, InGamePopup.Kind.Achievement, InGamePopup.Kind.Progress, InGamePopup.Kind.Game,
        InGamePopup.Kind.NoAchievements, InGamePopup.Kind.AchievementsOff, InGamePopup.Kind.GpuFaster,
        InGamePopup.Kind.Turbo, InGamePopup.Kind.TurboOff, InGamePopup.Kind.NetBlocked, InGamePopup.Kind.Hardcore,
        InGamePopup.Kind.Leaderboard, InGamePopup.Kind.LeaderboardResult -> JoeyTheme.colors.gold
        InGamePopup.Kind.NetWait, InGamePopup.Kind.GpuNote, InGamePopup.Kind.TexPackNote -> JoeyTheme.colors.tagMissable
        InGamePopup.Kind.TexPack -> JoeyTheme.colors.accent
        InGamePopup.Kind.GpuOk -> JoeyTheme.colors.good
        InGamePopup.Kind.GpuBack, InGamePopup.Kind.HardcoreOff -> JoeyTheme.colors.textDim
        else -> null
    }, badge = p.badge, compact = p.compact, framed = p.framed, extra = p.extra, bar = p.bar, chip = p.chip, warn = p.warn)
}

/**
 * The bottom-right stack (approved toast2.html and ach3.html, 2026-11-04): the challenge badges, the achievements whose
 * challenge is running right now, 30 dp each, newest on the right (a badge that hasn't come yet shows the trophy);
 * under them the leaderboard timer, a stopwatch and the live time or score, one line per board running. One box at
 * Settings › Badge place; the whole box is the tap area. [onTimer] gets where the timer part is, for taps.
 */
@Composable
fun ChallengeBadges(files: List<File?>, timers: List<String> = emptyList(), onTimer: (androidx.compose.ui.geometry.Rect) -> Unit = {}) {
    val c = JoeyTheme.colors
    val shape = RoundedCornerShape(8.dp)
    androidx.compose.foundation.layout.Column(
        Modifier.padding(12.dp).background(c.surface, shape).border(1.dp, c.border, shape).padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp), horizontalAlignment = Alignment.End,
    ) {
        if (files.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (f in files) {
                val picture = rememberBadge(f)
                val badgeShape = RoundedCornerShape(5.dp)
                if (picture != null) Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(30.dp).clip(badgeShape))
                else Box(Modifier.size(30.dp).background(c.chip, badgeShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_trophy), contentDescription = null, tint = c.gold, modifier = Modifier.size(17.dp))
                }
            }
        }
        if (timers.isNotEmpty()) androidx.compose.foundation.layout.Column(
            Modifier.onGloballyPositioned { onTimer(it.boundsInWindow()) },
            verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.End,
        ) {
            for (t in timers) Row(Modifier.padding(horizontal = 3.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(painterResource(R.drawable.ic_stopwatch), contentDescription = null, tint = c.gold, modifier = Modifier.size(16.dp))
                androidx.compose.material3.Text(t, style = JoeyTheme.type.small.copy(fontFeatureSettings = "tnum"),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = c.text)
            }
        }
    }
}
