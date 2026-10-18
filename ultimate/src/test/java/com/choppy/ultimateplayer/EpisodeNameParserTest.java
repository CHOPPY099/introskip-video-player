package com.choppy.ultimateplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.junit.Test;

public class EpisodeNameParserTest {
    private static final String LABELED_RELEASE = "One.piece.audio.eng.episode.483_480[1].mp4";
    private static final String NUMBERED_RELEASE = "One_Piece_-_0485_[720p]_[Dual]_@Anime_Gallery[1].mkv";

    @Test
    public void detectsLabeledEpisodeBeforeResolutionSuffix() {
        assertEquals(483, EpisodeNameParser.episodeNumber("One.piece.audio.eng.episode.483_480[1].mp4"));
    }

    @Test
    public void detectsUnlabeledEpisodeAndIgnoresVideoQuality() {
        assertEquals(485, EpisodeNameParser.episodeNumber("One_Piece_-_0485_[720p]_[Dual]_@Anime_Gallery[1].mkv"));
    }

    @Test
    public void treatsDifferentReleaseNamesAsTheSameSeries() {
        ArrayList<String> source = EpisodeNameParser.significantTokens("One.piece.audio.eng.episode.483_480[1].mp4");
        assertTrue(EpisodeNameParser.matchesSeries(
                "One_Piece_-_0485_[720p]_[Dual]_@Anime_Gallery[1].mkv",
                new ArrayList<>(),
                source
        ));
        assertFalse(EpisodeNameParser.matchesSeries("Different Show Episode 485.mkv", new ArrayList<>(), source));
    }

    @Test
    public void realReleasesHaveTheSameCompleteSeriesIdentity() {
        assertEquals(Arrays.asList("one", "piece"), EpisodeNameParser.significantTokens(LABELED_RELEASE));
        assertEquals(EpisodeNameParser.significantTokens(LABELED_RELEASE),
                EpisodeNameParser.significantTokens(NUMBERED_RELEASE));
        assertSameSeries(LABELED_RELEASE, NUMBERED_RELEASE);
        assertSameSeries(NUMBERED_RELEASE, LABELED_RELEASE);
        assertTrue(EpisodeNameParser.sortKey(LABELED_RELEASE) < EpisodeNameParser.sortKey(NUMBERED_RELEASE));
    }

    @Test
    public void parsesEpisodesAtResolutionBoundaries() {
        for (int number : new int[] {479, 480, 481, 719, 720, 721}) {
            assertEpisode(number, "One Piece - " + number + " [720p][1].mkv");
            assertEpisode(number, "One Piece " + number + " 1080p.mkv");
            assertEpisode(number, "One.piece.audio.eng.episode." + number + "_480[1].mp4");
            assertEpisode(number, "[Group] One_Piece_-_0" + number + "_[480p]_[Dual].mkv");
        }
    }

    @Test
    public void sortsNumericallyInsteadOfLexically() {
        List<String> names = new ArrayList<>(Arrays.asList(
                "One Piece - 721.mkv", "One Piece - 480.mkv", "One Piece - 10.mkv",
                "One Piece - 720.mkv", "One Piece - 2.mkv", "One Piece - 719.mkv",
                "One Piece - 481.mkv", "One Piece - 479.mkv", LABELED_RELEASE, NUMBERED_RELEASE));
        names.sort(Comparator.comparingLong(EpisodeNameParser::sortKey));
        List<Integer> episodes = new ArrayList<>();
        for (String name : names) episodes.add(EpisodeNameParser.episodeNumber(name));
        assertEquals(Arrays.asList(2, 10, 479, 480, 481, 483, 485, 719, 720, 721), episodes);
    }

    @Test
    public void parsesSeasonalNotation() {
        assertEpisode(3, "[Release] Show.S02E03.1080p.WEB-DL.x265-GROUP.mkv");
        assertEpisode(3, "Show s02 e03.mkv");
        assertEpisode(3, "Show Season 2 Episode 3.mkv");
        assertEpisode(3, "Show 2x03.mkv");
        assertEpisode(0, "Show S00E00.mkv");
    }

    @Test
    public void ordersSeasonsBeforeEpisodes() {
        List<String> names = new ArrayList<>(Arrays.asList("Show S02E03.mkv", "Show S02E01.mkv",
                "Show S01E12.mkv", "Show S10E01.mkv", "Show S00E01.mkv", "Show S01E02.mkv"));
        names.sort(Comparator.comparingLong(EpisodeNameParser::sortKey));
        assertEquals(Arrays.asList("Show S00E01.mkv", "Show S01E02.mkv", "Show S01E12.mkv",
                "Show S02E01.mkv", "Show S02E03.mkv", "Show S10E01.mkv"), names);
        assertEquals(EpisodeNameParser.sortKey("Show S02E03.mkv"), EpisodeNameParser.sortKey("Show 2x03.mkv"));
    }

    @Test
    public void qualityAndCopyNumbersAreNotEpisodes() {
        for (String name : Arrays.asList("One Piece [720p][1].mkv", "One Piece 480p (1).mp4",
                "One Piece 1080p.mkv (1)", "One Piece [480][1].mp4", "One Piece [720][1].mkv",
                "One Piece [1920x1080][AAC 2.0][10bit][1].mkv", "One Piece 1920x1080 [1].mkv",
                "One Piece 1920 x 1080 [1].mkv", "One Piece 720 p [1].mkv",
                "One Piece H.264 AAC 2.0 [1].mp4", "One Piece 10 bit x265 [1].mkv",
                "One Piece [S01E01-Group] [1].mkv", "One Piece [Group 12] [1].mp4",
                "One Piece (1).mp4", "One Piece[1].mkv", "[1080p][1].mkv")) {
            assertEpisode(-1, name);
            assertEquals(name, Long.MAX_VALUE, EpisodeNameParser.sortKey(name));
        }
    }

    @Test
    public void stripsNestedReleaseTagsAndCopySuffixes() {
        assertEpisode(480, "[Group [720p]] One Piece - 0480 {x265} (Dual Audio) [1] (2).mkv");
        assertSameSeries("[Release One] One Piece - 480.mkv", "[Other 123] One Piece - 481 (1).mkv");
        assertSameSeries("One Piece S01E12 [AAC].mkv", "One Piece S02E01 WEB-DL x265-Other.mkv");
        assertEquals("One Piece - Episode 480",
                EpisodeNameParser.displayTitle("[Group [720p]] One Piece - 0480 {x265} (Dual Audio) [1] (2).mkv"));
    }

    @Test
    public void acceptsCommonEpisodeLabelsAndRevisionSuffixes() {
        for (String name : Arrays.asList("Show Episode 12.mkv", "Show EP.0012.mkv", "Show E12.mkv",
                "Show ep12v2.mkv", "Show - 0012v3 [720p].mkv", "Show S01E12v2.mkv")) {
            assertEpisode(12, name);
        }
    }

    @Test
    public void labelMustBeAnEntireToken() {
        for (String name : Arrays.asList("Step12.mkv", "PreEpisode12.mkv", "Show episode12extra.mkv",
                "Show S02E03extra.mkv", "Show myep12.mkv")) {
            assertEpisode(-1, name);
        }
    }

    @Test
    public void seriesMatchingDoesNotUseSubstrings() {
        assertDifferentSeries("One Piece Episode 1.mkv", "Someone Pieces Episode 2.mkv");
        assertDifferentSeries("Naruto Episode 1.mkv", "Boruto Episode 2.mkv");
        assertDifferentSeries("Monster Episode 1.mkv", "Monsters Episode 2.mkv");
        assertDifferentSeries("86 Episode 1.mkv", "186 Episode 2.mkv");
    }

    @Test
    public void overlappingTitlesAreNotTheSameSeries() {
        assertDifferentSeries("One Piece Episode 1.mkv", "One Piece Film Red Episode 2.mkv");
        assertDifferentSeries("Naruto Episode 1.mkv", "Naruto Shippuden Episode 2.mkv");
        assertDifferentSeries("Legend Of The Galactic Heroes Episode 1.mkv", "Legend Of The Seeker Episode 2.mkv");
        assertDifferentSeries("The Office Episode 1.mkv", "Office Episode 2.mkv");
        assertDifferentSeries("Show A Episode 1.mkv", "Show B Episode 2.mkv");
        assertDifferentSeries("Dragon Ball Episode 1.mkv", "Dragon Ball Z Episode 2.mkv");
        assertDifferentSeries("Mob Psycho 100 Episode 1.mkv", "Mob Psycho 200 Episode 2.mkv");
    }

    @Test
    public void parentheticalTitleQualifiersAreNotReleaseMetadata() {
        assertDifferentSeries("The Office (US) S01E01.mkv", "The Office (UK) S01E02.mkv");
        assertDifferentSeries("Hunter x Hunter (1999) Episode 1.mkv", "Hunter x Hunter (2011) Episode 2.mkv");
        assertSameSeries("The Office (US) S01E01.mkv", "[Group] The Office (US) S02E01 (1).mkv");
        assertEquals("The Office (US) - S01E01", EpisodeNameParser.displayTitle("The.Office.(US).S01E01.mkv"));
    }

    @Test
    public void titleWordsAreNotGloballyDiscardedAsLanguageOrGenericNoise() {
        assertSameSeries("English Teacher S01E01.mkv", "[Group] English.Teacher.S02E01.1080p.mkv");
        assertDifferentSeries("English Teacher S01E01.mkv", "Teacher S01E02.mkv");
        assertDifferentSeries("Video Girl Ai Episode 1.mkv", "Girl Ai Episode 2.mkv");
        assertSameSeries("One Piece eng dub Episode 1.mkv", NUMBERED_RELEASE);
        assertSameSeries("One Piece eng - 001.mkv", NUMBERED_RELEASE);
        assertSameSeries("One Piece - 480 (Dual).mkv", NUMBERED_RELEASE);
    }

    @Test
    public void titlesInsideEpisodeNamesAndReleaseGroupsDoNotMatch() {
        assertDifferentSeries("One Piece Episode 1.mkv", "Different Show Episode 2 - One Piece.mkv");
        assertDifferentSeries("One Piece Episode 1.mkv", "[One Piece] Different Show - 2.mkv");
        assertDifferentSeries("One Piece Episode 1.mkv", "Different Show - 2 @One_Piece.mkv");
    }

    @Test
    public void episodeSubtitlesDoNotPolluteSeriesTokens() {
        assertSameSeries("One Piece Episode 483 - Looking for an Answer.mkv", NUMBERED_RELEASE);
        assertSameSeries("[Group] One Piece - 0483 - Looking for an Answer [720p].mkv", NUMBERED_RELEASE);
    }

    @Test
    public void sourceTitleTakesPriorityOverPlaylistTitle() {
        assertTrue(EpisodeNameParser.matchesSeries(NUMBERED_RELEASE,
                EpisodeNameParser.significantTokens(LABELED_RELEASE),
                EpisodeNameParser.significantTokens("Different Show Episode 1.mkv")));
        assertFalse(EpisodeNameParser.matchesSeries(NUMBERED_RELEASE,
                EpisodeNameParser.significantTokens("Different Show Episode 1.mkv"),
                EpisodeNameParser.significantTokens(LABELED_RELEASE)));
        assertTrue(EpisodeNameParser.matchesSeries(NUMBERED_RELEASE, null,
                EpisodeNameParser.significantTokens(LABELED_RELEASE)));
    }

    @Test
    public void missingIdentityNeverMatches() {
        assertFalse(EpisodeNameParser.matchesSeries("Show Episode 1.mkv", null, null));
        assertFalse(EpisodeNameParser.matchesSeries("Show Episode 1.mkv", Collections.emptyList(), Collections.emptyList()));
        assertFalse(EpisodeNameParser.matchesSeries("[720p][1].mkv", Arrays.asList("720p", "mkv"), null));
        assertFalse(EpisodeNameParser.matchesSeries(null, Arrays.asList("show"), null));
        assertTrue(EpisodeNameParser.significantTokens("Episode 1 [720p].mkv").isEmpty());
    }

    @Test
    public void unknownEpisodesRemainUnknown() {
        for (String name : Arrays.asList(null, "", " ", "A Movie.mkv", "Show 2024 [1080p].mkv",
                "2001 A Space Odyssey.mkv", "Show 12 13.mkv", "Show - 0000.mkv",
                "720p.mkv", "480.mkv", "Trailer [1].mp4")) {
            assertEpisode(-1, name);
            assertEquals(Long.MAX_VALUE, EpisodeNameParser.sortKey(name));
        }
    }

    @Test
    public void overflowsAndExplicitBatchesStayUnknown() {
        for (String name : Arrays.asList("Show Episode 99999999999999999999999_480.mkv",
                "Show S999999999999999E03.mkv", "Show S02E99999999999999.mkv",
                "Show Episode 1-12.mkv", "Show S01E01-12.mkv", "Show Ep1 Ep2.mkv",
                "Show - 01-12.mkv", "Show 01-12.mkv", "Show S01E01-E12.mkv",
                "Show Episode 12.5.mkv", "Show 12.5.mkv")) {
            assertEpisode(-1, name);
            assertEquals(Long.MAX_VALUE, EpisodeNameParser.sortKey(name));
        }
    }

    @Test
    public void unknownEpisodesSortLastWithoutLongOverflow() {
        long largest = EpisodeNameParser.sortKey("Show S2147483647E2147483647.mkv");
        assertTrue(largest > EpisodeNameParser.sortKey("Show S02E03.mkv"));
        assertTrue(largest < EpisodeNameParser.sortKey("Unknown.mkv"));
        assertTrue(EpisodeNameParser.sortKey("Show Episode 2147483647.mkv") >= 0);
    }

    @Test
    public void buildsReadableTitlesWithoutDownloadMetadata() {
        assertEquals("One piece - Episode 483", EpisodeNameParser.displayTitle(LABELED_RELEASE));
        assertEquals("One Piece - Episode 485", EpisodeNameParser.displayTitle(NUMBERED_RELEASE));
        assertEquals("Show - S02E03", EpisodeNameParser.displayTitle("[Group] Show.S02E03.1080p.WEB-DL.x265.mkv"));
        assertEquals("A Movie", EpisodeNameParser.displayTitle("A_Movie_[1080p][1].mkv"));
        assertEquals("Untitled video", EpisodeNameParser.displayTitle(null));
        assertEquals("Episode 3", EpisodeNameParser.displayTitle("Episode 3 [720p].mkv"));
    }

    @Test
    public void filePathsDoNotContributeNumbersOrTokens() {
        assertEpisode(480, "C:\\Season 2\\[Downloads]\\One Piece - 480[1].mkv");
        assertEquals(Arrays.asList("one", "piece"), EpisodeNameParser.significantTokens("/downloads/123/One Piece - 480.mkv"));
    }

    @Test
    public void normalizationKeepsItsExistingContract() {
        assertEquals("one piece 0485 720p", EpisodeNameParser.normalize("ONE_Piece.-.0485_[720p]"));
        assertEquals("", EpisodeNameParser.normalize(null));
    }

    private static void assertEpisode(int expected, String name) {
        assertEquals(name, expected, EpisodeNameParser.episodeNumber(name));
    }

    private static void assertSameSeries(String first, String second) {
        assertTrue(second, EpisodeNameParser.matchesSeries(second,
                EpisodeNameParser.significantTokens(first), Collections.emptyList()));
    }

    private static void assertDifferentSeries(String first, String second) {
        assertFalse(second, EpisodeNameParser.matchesSeries(second,
                EpisodeNameParser.significantTokens(first), Collections.emptyList()));
        assertFalse(first, EpisodeNameParser.matchesSeries(first,
                EpisodeNameParser.significantTokens(second), Collections.emptyList()));
    }
}
