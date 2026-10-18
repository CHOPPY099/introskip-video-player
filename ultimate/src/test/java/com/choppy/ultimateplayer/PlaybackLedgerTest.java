package com.choppy.ultimateplayer;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.Assert.*;

public class PlaybackLedgerTest {
    @Test public void nearEndPauseAndManualNextDoNotComplete() {
        PlaybackLedger state = new PlaybackLedger();
        state.selectNormal("show", "ep1", 0);
        state.snapshot("ep1", 99999);
        state.selectNormal("show", "ep2", 0);
        assertTrue(state.watched.isEmpty());
        assertEquals(Integer.valueOf(99999), state.progress.get("ep1"));
        assertEquals(Arrays.asList("ep1", "ep2"),
                state.pruneCompleted(Arrays.asList("ep1", "ep2"), "show"));
    }

    @Test public void eofPrunesOnlyOlderCompletedAndKeepsLatestAnchor() {
        PlaybackLedger state = new PlaybackLedger();
        state.naturalEnd("show", "ep1", 100000);
        assertEquals(Arrays.asList("ep1", "ep2", "ep3"),
                state.pruneCompleted(Arrays.asList("ep1", "ep2", "ep3"), "show"));
        state.naturalEnd("show", "ep2", 100000);
        assertEquals(Arrays.asList("ep2", "ep3"),
                state.pruneCompleted(Arrays.asList("ep1", "ep2", "ep3"), "show"));
    }

    @Test public void nextNeverWrapsAndSkipsCompleted() {
        assertEquals(2, PlaybackLedger.nextUnwatched(Arrays.asList("a", "b", "c"), 0,
                new HashSet<>(Arrays.asList("b"))));
        assertEquals(-1, PlaybackLedger.nextUnwatched(Arrays.asList("a", "b"), 1,
                new HashSet<>()));
    }

    @Test public void realResumeAlwaysBeatsIntroIncludingZeroAndNearEnd() {
        assertEquals(1234, PlaybackLedger.startPosition(1234, false, 1000000, 180000, false));
        assertEquals(0, PlaybackLedger.startPosition(0, false, 1000000, 180000, false));
        assertEquals(999999, PlaybackLedger.startPosition(1000000, false, 1000000, 180000, false));
    }

    @Test public void introOnlyAppliesToNewOrCompletedLongEnoughEpisodes() {
        assertEquals(180000, PlaybackLedger.startPosition(null, false, 1000000, 180000, false));
        assertEquals(180000, PlaybackLedger.startPosition(1000000, true, 1000000, 180000, false));
        assertEquals(0, PlaybackLedger.startPosition(null, false, 60000, 180000, false));
        assertEquals(0, PlaybackLedger.startPosition(null, false, 0, 180000, false));
    }

    @Test public void previewDoesNotChangeHistoryAnchorsOrNormalContinuation() {
        PlaybackLedger state = new PlaybackLedger();
        state.selectNormal("show", "ep2", 25000);
        state.beginPreview("ep2", 25000);
        state.snapshot("ep8", 90000);
        assertFalse(state.naturalEnd("show", "ep8", 100000));
        state.beginPreview("ep8", 90000);
        assertEquals("ep2", state.continuationKey);
        assertEquals(25000, state.continuationPositionMs);
        assertEquals("ep2", state.seriesAnchors.get("show"));
        assertFalse(state.progress.containsKey("ep8"));
        assertTrue(state.watched.isEmpty());
        assertEquals(0, PlaybackLedger.startPosition(45000, false, 1000000, 180000, true));
    }

    @Test public void sameEpisodePreviewPreservesExactNormalResume() {
        PlaybackLedger state = new PlaybackLedger();
        state.selectNormal("show", "ep1", 0);
        state.beginPreview("ep1", 80000);
        state.snapshot("ep1", 4000);
        assertEquals(Integer.valueOf(80000), state.progress.get("ep1"));
        state.selectNormal("show", state.continuationKey, state.continuationPositionMs);
        assertFalse(state.previewing);
        assertEquals(80000, state.continuationPositionMs);
    }

    @Test public void completionHistorySurvivesReplayingWithoutRecompletion() {
        PlaybackLedger state = new PlaybackLedger();
        state.naturalEnd("show", "ep1", 100000);
        state.snapshot("ep1", 20000);
        assertEquals(Integer.valueOf(100000), state.progress.get("ep1"));
    }
}
