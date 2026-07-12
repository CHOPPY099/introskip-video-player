package com.choppy.episodeflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;

import org.junit.Test;

public class EpisodeNameParserTest {
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
}
