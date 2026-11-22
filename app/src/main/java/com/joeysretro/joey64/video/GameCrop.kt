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
 * Beetle Adventure Racing! (tester report, Joey 2026-11-21: "needs crop fix like we did for dk64"): in a race the game
 * draws its own black frame inside its picture, 14.0-17.1 lines of 240 (bottom 14.0, left 14.7, right 16.8, top 17.1;
 * Odin 3 Portal at 1x), so Exact 14 lines, the most that cuts no race picture. Its menus and title fill more of the
 * screen: 14 lines trims the film strip's outer edges there, no text. NNS is the USA and Europe code; Japan's NB8
 * isn't measured yet.
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
 *
 * Crop pass 3 (Joey 2026-11-22: "Yes, all of them"; joey64-notes docs/crop/crop8-sum.tsv): 75 more games, one version
 * each, whose border kept 4+ black lines on both shots (7 s and 30 s into their attract scene) on top and bottom or both
 * sides, the largest of Thin 4 / Normal 8 / Wide 12 / Widest 16 that cuts no picture. Many are PAL bars, which the
 * other regions' versions don't have, so these go by the full code with its region letter ([GAMES4], checked first).
 * Protos, betas and demos go by their dump's MD5: some share the retail game's code (GT 64, Road Rash, Frogger 2, Tony
 * Hawk's), and Twintris (homebrew) carries Super Mario 64 USA's NSME.
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
        "NNS" to 14, // Beetle Adventure Racing! (USA, Europe)
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

    /** Crop pass 3: by the full header code, region letter included (PAL bars are the PAL version's only). */
    private val GAMES4 = mapOf(
        "N8WP" to 4, // World Cup 98 (Europe) (En,Fr,De,Es,It,Nl,Sv,Da)
        "N9FP" to 16, // FIFA 99 (Europe) (En,Fr,De,Es,It,Nl,Pt,Sv)
        "NABP" to 16, // Airboarder 64 (Europe)
        "NBAJ" to 8, // NBA in the Zone '98 (Japan)
        "NBFP" to 4, // Bio F.R.E.A.K.S. (Europe)
        "NBMJ" to 4, // Baku Bomberman (Japan)
        "NBVJ" to 4, // Baku Bomberman 2 (Japan)
        "NBYP" to 16, // Bug's Life, A (Europe)
        "NCBP" to 8, // Charlie Blast's Territory (Europe)
        "NCFP" to 16, // Clay Fighter 63 1-3 (Europe)
        "NCRE" to 8, // Penny Racers (USA)
        "NCXJ" to 16, // Custom Robo (Japan)
        "NDRJ" to 8, // Doraemon - Nobita to 3tsu no Seireiseki (Japan)
        "NDUE" to 16, // Duck Dodgers Starring Daffy Duck (USA) (En,Fr,Es)
        "NDUP" to 16, // Daffy Duck Starring as Duck Dodgers (Europe) (En,Fr,De,Es,It,Nl)
        "NDYP" to 8, // Diddy Kong Racing (Europe) (En,Fr,De)
        "NEPP" to 4, // Star Wars Episode I - Racer (Europe) (En,Fr,De)
        "NFRP" to 8, // F1 Racing Championship (Europe) (En,Fr,De,Es,It)
        "NFWF" to 4, // F-1 World Grand Prix (France)
        "NFYJ" to 8, // Kakutou Denshou - F-Cup Maniax (Japan)
        "NFZP" to 8, // F-Zero X (Europe)
        "NG6J" to 8, // Ganbare Goemon - Dero Dero Douchuu Obake Tenkomori (Japan)
        "NGBP" to 16, // Top Gear Hyper Bike (Europe)
        "NGME" to 8, // Goemon's Great Adventure (USA)
        "NGMP" to 8, // Mystical Ninja 2 Starring Goemon (Europe) (En,Fr,De)
        "NH9P" to 4, // NHL Breakaway 99 (Europe)
        "NHLE" to 16, // NHL Breakaway 98 (USA)
        "NHNE" to 8, // Olympic Hockey 98 (USA)
        "NHOE" to 8, // NHL Blades of Steel '99 (USA)
        "NHOP" to 4, // NHL Pro 99 (Europe)
        "NHYJ" to 12, // Hybrid Heaven (Japan)
        "NICE" to 16, // Indy Racing 2000 (USA)
        "NJ2J" to 16, // Wonder Project J2 - Koruro no Mori no Jozet (Japan)
        "NK4J" to 8, // Hoshi no Kirby 64 (Japan) (Rev 1)
        "NK4P" to 8, // Kirby 64 - The Crystal Shards (Europe)
        "NKGP" to 4, // Major League Baseball Featuring Ken Griffey Jr. (Australia)
        "NKTP" to 16, // Mario Kart 64 (Europe) (Rev 1)
        "NLLJ" to 8, // Last Legion UX (Japan)
        "NLRJ" to 16, // Lode Runner 3-D (Japan)
        "NM8P" to 8, // Mario Tennis (Europe)
        "NMFJ" to 4, // Mario Golf 64 (Japan) (Rev 1)
        "NMFP" to 4, // Mario Golf (Europe)
        "NMGD" to 4, // Racing Simulation 2 (Germany)
        "NMGP" to 4, // Monaco Grand Prix - Racing Simulation 2 (Europe) (En,Fr,Es,It)
        "NMXG" to 12, // Excitebike 64 (USA) (LodgeNet)
        "NOBJ" to 16, // Ogre Battle 64 - Person of Lordly Caliber (Japan) (Rev 1)
        "NOHE" to 4, // Transformers - Beast Wars Transmetals (USA)
        "NPLJ" to 16, // Power League 64 (Japan)
        "NPMP" to 16, // Premier Manager 64 (Europe)
        "NRGF" to 8, // Razmoket, Les - La Chasse aux Tresors (France)
        "NRSP" to 8, // Star Wars - Rogue Squadron (Europe) (En,Fr,De) (Rev 1)
        "NSAJ" to 12, // Sonic Wings Assault (Japan)
        "NSMJ" to 4, // Super Mario 64 (Japan) (Rev 3) (Shindou Edition)
        "NT2X" to 16, // Turok 2 - Seeds of Evil (Europe) (En,Fr,Es,It)
        "NTBJ" to 4, // Transformers - Beast Wars Metals 64 (Japan)
        "NTFP" to 4, // Tony Hawk's Skateboarding (Europe)
        "NTNP" to 16, // All Star Tennis '99 (Europe) (En,Fr,De,Es,It)
        "NTRJ" to 4, // Top Gear Rally (Japan)
        "NTXP" to 4, // Taz Express (Europe) (En,Fr,De,Es,It,Nl)
        "NUTJ" to 8, // Nushi Zuri 64 (Japan) (Rev 1)
        "NWGJ" to 8, // Wayne Gretzky's 3D Hockey (Japan)
        "NWME" to 16, // WCW Mayhem (USA)
        "NWRP" to 8, // Wave Race 64 - Kawasaki Jet Ski (Europe) (En,De)
        "NWVE" to 16, // WCW Backstage Assault (USA)
    )

    /** The games with no game code in their header, by the ROM's MD5 (the library's, of the .z64 bytes). */
    private val BY_MD5 = mapOf(
        "ada39573b25804610d8fafaff741b088" to 4, // Jikkyou Powerful Pro Yakyuu 4 (Japan) (Beta) (1996-11-30)
        "d0da4da6aeaca000e26b86ff7b0f20cd" to 4, // Lunar Assault 64 (World) (v1.0) (N64brew Game Jam 2020) (NTSC)
        "b6fd2a048d1f4f324cebc97ba09872bb" to 8, // Toon Panic (Japan) (Proto)
        "e0901d23a7e24e938fa62975856bd7f0" to 12, // Pikachuu Genki de Chuu (Japan) (Beta)
        "b60d26c2c2242bff61f76469fc272d2a" to 12, // Top Gear Hyper-Bike (USA) (Beta)
        "29b79bf5812e5f9e5ecef073d59f8915" to 4, // California Speed (Europe) (Proto)
        "6c329dc4cc2fc9a96475e8a30abfe735" to 16, // Doubutsu Banchou (Japan) (Proto)
        "097189b4c9bf6775e4685951b6e66f24" to 8, // Frogger 2 (USA) (Proto 1)
        "4690cab33d0bc10b62d62cecdcaa50a1" to 12, // GT 64 - Championship Edition (USA) (Beta) (1998-05-25)
        "436f93c0d8b1650bc308ccf344c08c93" to 16, // Road Rash 64 (Europe) (Beta) (1999-10-12)
        "e66ed1cc4ab95d0872bb2ebc49b206c4" to 4, // SpaceStation Silicon Valley (Japan) (Proto)
        "fc32007ba03ff2510020e979c7bdad4f" to 4, // Sydney 2000 (Europe) (Proto)
        "035c0700406197104d977c379e410cb7" to 4, // Tony Hawk's Pro Skater (USA) (Beta)
        "6440a67e2c27bd9adedfc60939c2bf1b" to 4, // Tony Hawk's Pro Skater 2 (USA) (v012) (Beta) (2001-06-04)
        "975d01545d0803f20d1ee61385c28eed" to 8, // Top Gear Overdrive (USA) (Beta)
        "a74738d03aca4e51690408d203e86d49" to 16, // Twintris (World) (Demo) (Presence of Mind '98) (Unl)
    )

    private const val MD5 = "md5:"

    /** The key [lines] takes: "md5:<md5>" for a game listed by its MD5, else its header [gameCode]. */
    fun keyOf(gameCode: String?, md5: String?): String? =
        md5?.lowercase()?.takeIf { it in BY_MD5 }?.let { MD5 + it } ?: gameCode

    /** The lines [gameCode] ("CZLE" or "CZL", or a [keyOf] key) crops by default, or null when it has none or [v] sets Crop edges. */
    fun lines(gameCode: String?, v: VideoSettings): Int? =
        if (VideoKey.Crop in v.values) null
        else if (gameCode != null && gameCode.startsWith(MD5)) BY_MD5[gameCode.removePrefix(MD5)]
        else gameCode?.takeIf { it.length >= 4 }?.let { GAMES4[it.take(4).uppercase()] }
            ?: gameCode?.takeIf { it.length >= 3 }?.let { GAMES[it.take(3).uppercase()] }

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
