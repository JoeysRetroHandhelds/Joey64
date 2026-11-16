package com.joeysretro.joey64.video

/**
 * Games that draw junk into the border a TV hid, so Crop edges gets a default for them: used only while neither
 * All games nor the game sets Crop edges, so any choice the player makes wins (Off included). By the header's game
 * code without the region letter, so every region, revision and hack of the game gets it.
 *
 * Ocarina of Time (Joey 2026-11-12: "Yes, OoT only"): a small teal mark at the top left in dungeons, drawn by the game
 * into its own frame (both renderers, every VI filter; README Full-run fixes (f1)). Normal (8 lines) hides it, Thin
 * leaves a sliver. CZL is the USA and Japan code, NZL Europe's and LodgeNet's.
 *
 * Donkey Kong 64 (Joey 2026-11-15: "Yes", build it like OoT): the game draws its own black border inside its picture
 * (the VI area trims only 1%, so Trim the game's border can't take it off): 21-28 px of 960 at the sides and 26 of 720
 * top and bottom, about 8 lines of 240, so Normal. NDO is every region's code.
 *
 * The full-run crop sheet (Joey 2026-11-16: "51, not menu-only"; joey64-notes docs/crop/crop-joey.tsv): the games whose
 * border junk was seen in play or a picture scene get the size measured there, the largest of Thin 4 / Normal 8 /
 * Wide 12 lines that cuts no picture in any shot. Dual Heroes (Japan) has none that fits (2 lines at the sides, ~21 top
 * and bottom), so it has no default; three seen only on a menu (Ken Griffey Jr.'s Slugfest, RTL World League Soccer
 * 2000, Telefoot Soccer 2000) wait for a shot in play (the menu-only Japanese Smash Brothers shares NAL, every Smash
 * Bros.' code, so it gets the Australian one's Thin). Five have no game code in their
 * header (zero bytes: betas, a prototype, homebrew), so they go by their ROM's MD5 ([BY_MD5]).
 */
object GameCrop {
    private val GAMES = mapOf(
        "CZL" to 8, "NZL" to 8, // Ocarina of Time
        "NDO" to 8, // Donkey Kong 64
        "N2V" to 8, // Chameleon Twist 2 (Europe)
        "N4W" to 8, // 40 Winks (USA) (Aftermarket) (Unl)
        "NAB" to 8, // Air Boarder 64 (Japan)
        "NAL" to 4, // Super Smash Bros. (Australia)
        "NAY" to 8, // Aidyn Chronicles - The First Mage (Europe) (Beta) (2001-03-02)
        "NB2" to 4, // NBA Pro 99 (Europe) / NBA in the Zone 2 (Japan)
        "NB9" to 12, // NBA Jam 99 (Europe)
        "NBL" to 4, // Buck Bumble (Japan)
        "NBS" to 12, // All-Star Baseball 99 (Europe)
        "NCT" to 8, // Chameleon Twist (Japan)
        "NCY" to 12, // South Park - Chef's Luv Shack (Europe)
        "NDE" to 4, // Destruction Derby 64 (USA)
        "NDF" to 4, // Dance Dance Revolution - Disney Dancing Museum (Japan)
        "NDN" to 12, // Duke Nukem 64 (USA)
        "NER" to 8, // AeroFighters Assault (USA)
        "NGP" to 8, // Goemon - Mononoke Sugoroku (Japan)
        "NGU" to 4, // Tsumi to Batsu - Hoshi no Keishousha (Japan)
        "NHA" to 4, // Bomberman 64 (Japan)
        "NHT" to 4, // Hydro Thunder (USA) (LodgeNet)
        "NJ5" to 4, // Jikkyou Powerful Pro Yakyuu 5 (Japan) (Rev 1)
        "NJA" to 12, // NBA Jam 2000 (Europe)
        "NKA" to 4, // Fighters Destiny (USA)
        "NL2" to 8, // Top Gear Rally 2 (Japan)
        "NME" to 4, // Mace - The Dark Age (USA)
        "NNL" to 8, // NBA Live 2000 (USA) (En,Fr,De,Es)
        "NP2" to 4, // Chou Kuukan Nighter Pro Yakyuu King 2 (Japan)
        "NP6" to 4, // Jikkyou Powerful Pro Yakyuu 6 (Japan) (Rev 1)
        "NPA" to 4, // Jikkyou Powerful Pro Yakyuu 2000 (Japan) (Rev 1)
        "NPE" to 4, // Jikkyou Powerful Pro Yakyuu - Basic Ban 2001 (Japan) (Rev 1)
        "NPG" to 12, // Hey You, Pikachu! (USA)
        "NPW" to 4, // Pilotwings 64 (Japan)
        "NQ8" to 4, // NFL Quarterback Club 98 (Europe)
        "NQB" to 8, // NFL Quarterback Club 2000 (USA)
        "NR2" to 4, // Rush 2 - Extreme Racing USA (USA)
        "NSF" to 4, // San Francisco Rush - Extreme Racing (USA) (En,Fr,De) (Rev 1)
        "NSI" to 4, // Fushigi no Dungeon - Fuurai no Shiren 2 - Oni Shuurai! Shiren Jou! (Japan)
        "NSO" to 4, // NBA Showtime - NBA on NBC (USA)
        "NSU" to 8, // Rocket - Robot on Wheels (USA)
        "NTM" to 8, // Mischief Makers (USA) (Rev 1)
        "NVC" to 4, // Virtual Chess 64 (USA) (En,Fr,Es) (LodgeNet)
        "NW8" to 4, // Wayne Gretzky's 3D Hockey '98 (USA)
        "NWD" to 8, // WinBack (Japan) (Rev 1) / WinBack - Covert Operations (USA)
        "NWT" to 4, // Wetrix (Europe) (En,Fr,De,Es,It,Nl)
    )

    /** The games with no game code in their header, by the ROM's MD5 (the library's, of the .z64 bytes). */
    private val BY_MD5 = mapOf(
        "ada39573b25804610d8fafaff741b088" to 4, // Jikkyou Powerful Pro Yakyuu 4 (Japan) (Beta) (1996-11-30)
        "d0da4da6aeaca000e26b86ff7b0f20cd" to 4, // Lunar Assault 64 (World) (v1.0) (N64brew Game Jam 2020) (NTSC)
        "b6fd2a048d1f4f324cebc97ba09872bb" to 8, // Toon Panic (Japan) (Proto)
        "e0901d23a7e24e938fa62975856bd7f0" to 12, // Pikachuu Genki de Chuu (Japan) (Beta)
        "b60d26c2c2242bff61f76469fc272d2a" to 12, // Top Gear Hyper-Bike (USA) (Beta)
    )

    private const val MD5 = "md5:"

    /** The key [lines] takes: "md5:<md5>" for a game listed by its MD5, else its header [gameCode]. */
    fun keyOf(gameCode: String?, md5: String?): String? =
        md5?.lowercase()?.takeIf { it in BY_MD5 }?.let { MD5 + it } ?: gameCode

    /** The lines [gameCode] ("CZLE" or "CZL", or a [keyOf] key) crops by default, or null when it has none or [v] sets Crop edges. */
    fun lines(gameCode: String?, v: VideoSettings): Int? =
        if (VideoKey.Crop in v.values) null
        else if (gameCode != null && gameCode.startsWith(MD5)) BY_MD5[gameCode.removePrefix(MD5)]
        else gameCode?.takeIf { it.length >= 3 }?.let { GAMES[it.take(3).uppercase()] }

    /** The lines to crop: [v]'s Crop edges, or [gameCode]'s default when [v] doesn't set it. */
    fun of(gameCode: String?, v: VideoSettings): Int = lines(gameCode, v) ?: v.int(VideoKey.Crop)

    /** The Crop edges row's value for [gameCode] (Joey 2026-11-12: "Yes, show the default"): "Normal (this game's
     *  default)" while the game's default applies, else the setting's own label. */
    fun label(gameCode: String?, v: VideoSettings): String =
        lines(gameCode, v)?.let { "${VideoChoices.label(VideoKey.Crop, it.toString())} (this game's default)" }
            ?: VideoChoices.label(VideoKey.Crop, v[VideoKey.Crop])

    /** The game's [keyOf] key for [saveName] from the library, off the main thread; null when it isn't there. */
    fun codeFor(context: android.content.Context, saveName: String): String? = try {
        com.joeysretro.joey64.launcher.data.LibraryDb.get(context).gameWithFile(saveName, com.joeysretro.joey64.states.GameStates::saveNameOf)
            ?.let { keyOf(it.headerId, it.md5) }
    } catch (e: Exception) { com.joeysretro.joey64.JLog.e("Joey64Video", "can't look up $saveName: $e"); null }
}
