package com.joeysretro.joey64.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameCropTest {
    private val unset = VideoSettings.resolve(emptyMap(), null)

    @Test fun ocarinaGetsNormalInEveryRegion() {
        for (code in listOf("CZLE", "CZLJ", "NZLP", "NZLG", "czl")) assertEquals(code, 8, GameCrop.of(code, unset))
    }

    @Test fun donkeyKong64GetsNormalInEveryRegion() {
        for (code in listOf("NDOE", "NDOJ", "NDOP")) assertEquals(code, 8, GameCrop.of(code, unset))
        assertEquals("Normal (this game's default)", GameCrop.label("NDOE", unset))
    }

    @Test fun beetleAdventureRacingGetsExact14() {
        for (code in listOf("NNSE", "NNSP")) assertEquals(code, 14, GameCrop.of(code, unset))
        assertEquals("Exact, 14 lines (this game's default)", GameCrop.label("NNSE", unset))
    }

    @Test fun theCropSheetsSizes() {
        assertEquals(4, GameCrop.of("NPWJ", unset)) // Pilotwings 64 (Japan): Thin, every region
        assertEquals(4, GameCrop.of("NPWE", unset))
        assertEquals(8, GameCrop.of("NTME", unset)) // Mischief Makers: Normal
        assertEquals(12, GameCrop.of("NDNE", unset)) // Duke Nukem 64: Wide
        assertEquals(4, GameCrop.of("NB2P", unset)) // NBA Pro 99 and NBA in the Zone 2 share NB2 and its size
        assertEquals(4, GameCrop.of("NB2J", unset))
        assertNull("Dual Heroes: no size fits", GameCrop.lines("NDHJ", unset))
    }

    @Test fun cropPass3GoesByRegion() {
        assertEquals(16, GameCrop.of("NKTP", unset)) // Mario Kart 64 (Europe): PAL bars, Widest
        assertNull("Mario Kart 64 (USA) has no bars", GameCrop.lines("NKTE", unset))
        assertEquals(16, GameCrop.of("NABP", unset)) // Airboarder 64 (Europe) over the Japanese one's Normal
        assertEquals(8, GameCrop.of("NABJ", unset))
        assertEquals(8, GameCrop.of("NHOE", unset)) // NHL Blades of Steel '99 (USA) and NHL Pro 99 (Europe) share NHO
        assertEquals(4, GameCrop.of("NHOP", unset))
        assertNull("Super Mario 64 (USA): Twintris carries its code, so Twintris goes by MD5", GameCrop.lines("NSME", unset))
        assertEquals(16, GameCrop.of(GameCrop.keyOf("NSME", "a74738d03aca4e51690408d203e86d49"), unset))
        assertEquals(4, GameCrop.of("NSMJ", unset)) // Super Mario 64 (Japan) (Shindou Edition): Thin
    }

    @Test fun gamesWithNoCodeGoByMd5() {
        val toonPanic = "b6fd2a048d1f4f324cebc97ba09872bb"
        assertEquals("md5:$toonPanic", GameCrop.keyOf(null, toonPanic.uppercase()))
        assertEquals(8, GameCrop.of(GameCrop.keyOf(null, toonPanic), unset))
        assertEquals(12, GameCrop.of(GameCrop.keyOf(null, "e0901d23a7e24e938fa62975856bd7f0"), unset))
        assertEquals("NDOE", GameCrop.keyOf("NDOE", "00000000000000000000000000000000"))
        assertNull(GameCrop.keyOf(null, null))
        assertNull(GameCrop.lines("md5:00000000000000000000000000000000", unset))
        assertEquals("Normal (this game's default)", GameCrop.label("md5:$toonPanic", unset))
    }

    @Test fun otherGamesKeepCropOff() {
        assertNull(GameCrop.lines("NSME", unset))
        assertEquals(0, GameCrop.of("NSME", unset))
        assertEquals(0, GameCrop.of(null, unset))
        assertEquals(0, GameCrop.of("", unset))
    }

    @Test fun thePlayersChoiceWins() {
        val allOff = VideoSettings.resolve(mapOf(VideoKey.Crop to "0"), null)
        assertEquals(0, GameCrop.of("CZLE", allOff))
        val gameWide = VideoSettings.resolve(emptyMap(), mapOf(VideoKey.Crop to "12"))
        assertEquals(12, GameCrop.of("CZLE", gameWide))
        val allThin = VideoSettings.resolve(mapOf(VideoKey.Crop to "4"), null)
        assertEquals(4, GameCrop.of("NZLP", allThin))
    }

    @Test fun theRowShowsTheGamesDefault() {
        assertEquals("Normal (this game's default)", GameCrop.label("CZLE", unset))
        assertEquals("Off", GameCrop.label("NSME", unset))
        assertEquals("Off", GameCrop.label("CZLE", VideoSettings.resolve(mapOf(VideoKey.Crop to "0"), null)))
        assertEquals("Wide", GameCrop.label("NZLP", VideoSettings.resolve(emptyMap(), mapOf(VideoKey.Crop to "12"))))
    }
}
