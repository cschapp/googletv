package dev.craig.reel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrobblerParseTest {
    private fun parse(vararg f: String) = Scrobbler.parse(f.toList())

    @Test fun episodeMarkerInTitleAfterShowName() =
        assertEquals(Scrobbler.Parsed("Severance", 2 to 3, null), parse("Severance S2:E3 “Who Is Alive?”"))

    @Test fun episodeInSubtitleShowInTitle() =
        assertEquals(Scrobbler.Parsed("The Bear", 3 to 1, null), parse("The Bear", "S3 E1 · Tomorrow"))

    @Test fun episodeTitleFirstShowElsewhere() =
        assertEquals(Scrobbler.Parsed("Andor", 1 to 4, null), parse("Season 1, Episode 4: Aldhani", "Andor"))

    @Test fun crossFormat() =
        assertEquals(Scrobbler.Parsed("Friends", 5 to 14, null), parse("Friends - 5x14 - The One Where Everybody Finds Out"))

    @Test fun movieWithYear() =
        assertEquals(Scrobbler.Parsed("Dune: Part Two", null, 2024), parse("Dune: Part Two (2024)"))

    @Test fun plainMovie() = assertEquals(Scrobbler.Parsed("Heat", null, null), parse("Heat", "Michael Mann"))

    @Test fun adsAreSkipped() = assertNull(parse("Advertisement", "Hulu"))

    @Test fun nothing() = assertNull(parse())

    @Test fun episodeWithNoShowName() =
        assertEquals(Scrobbler.Parsed(null, 1 to 1, null), parse("S01:E01 - Day One"))

    @Test fun appNameIsNotATitle() = assertNull(parse("PrimeVideo"))

    @Test fun primeGivesOnlyItsId() {
        val id = "amzn1.dv.gti.d35c0f45-93b1-4622-9aa5-8452ba01812a"
        assertEquals(Scrobbler.Parsed(null, null, null, mediaId = id), Scrobbler.parse(listOf("PrimeVideo"), id))
    }

    @Test fun unknownIdsAreIgnored() = assertNull(Scrobbler.parse(listOf("Netflix"), "81234567"))
}
