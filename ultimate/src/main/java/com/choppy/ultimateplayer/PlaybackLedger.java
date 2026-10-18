package com.choppy.ultimateplayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Main-thread state policy, deliberately independent of Android and the decoder. */
final class PlaybackLedger {
    final Map<String, Integer> progress = new HashMap<>();
    final Set<String> watched = new HashSet<>();
    final Map<String, String> seriesAnchors = new HashMap<>();
    final Map<String, String> completedAnchors = new HashMap<>();
    boolean previewing;
    String continuationKey = "";
    int continuationPositionMs;

    void selectNormal(String series, String key, int positionMs) {
        previewing = false;
        continuationKey = key;
        continuationPositionMs = Math.max(0, positionMs);
        seriesAnchors.put(series, key);
    }

    void snapshot(String key, int positionMs) {
        if (previewing || key == null || key.isEmpty()) return;
        int position = Math.max(0, positionMs);
        // A position, including one near the end, is never evidence of completion.
        if (!watched.contains(key)) progress.put(key, position);
        continuationKey = key;
        continuationPositionMs = position;
    }

    void beginPreview(String normalKey, int normalPositionMs) {
        if (!previewing) {
            snapshot(normalKey, normalPositionMs);
            continuationKey = normalKey == null ? "" : normalKey;
            continuationPositionMs = Math.max(0, normalPositionMs);
        }
        previewing = true;
    }

    boolean naturalEnd(String series, String key, int durationMs) {
        if (previewing || key == null || key.isEmpty()) return false;
        watched.add(key);
        progress.put(key, Math.max(0, durationMs));
        continuationKey = key;
        continuationPositionMs = Math.max(0, durationMs);
        seriesAnchors.put(series, key);
        completedAnchors.put(series, key);
        return true;
    }

    List<String> pruneCompleted(List<String> queue, String series) {
        if (previewing) return new ArrayList<>(queue);
        String anchor = completedAnchors.get(series);
        ArrayList<String> kept = new ArrayList<>();
        for (String key : queue) {
            if (!watched.contains(key) || key.equals(anchor)) kept.add(key);
        }
        return kept;
    }

    static int nextUnwatched(List<String> queue, int current, Set<String> watched) {
        for (int i = Math.max(0, current + 1); i < queue.size(); i++) {
            if (!watched.contains(queue.get(i))) return i;
        }
        return -1;
    }

    static int startPosition(Integer saved, boolean completed, int durationMs,
                             int introMs, boolean preview) {
        if (preview) return 0;
        // Zero is a real resume too: the user may have explicitly sought to the start.
        if (!completed && saved != null) return clampResume(saved, durationMs);
        // Unknown duration: defer intro skip until the decoder supplies a length.
        return introMs > 0 && durationMs > (long) introMs + 500 ? introMs : 0;
    }

    static int clampResume(long positionMs, int durationMs) {
        long maximum = durationMs > 0 ? Math.max(0, durationMs - 1L) : Integer.MAX_VALUE;
        return (int) Math.min(maximum, Math.max(0, positionMs));
    }
}
