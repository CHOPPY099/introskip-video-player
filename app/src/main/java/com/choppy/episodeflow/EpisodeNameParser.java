package com.choppy.episodeflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EpisodeNameParser {
    private static final Pattern LABELED_EPISODE = Pattern.compile("(?i)(?:episode|ep)[^0-9]*(\\d+)");

    private EpisodeNameParser() {
    }

    static int episodeNumber(String value) {
        Matcher matcher = LABELED_EPISODE.matcher(value == null ? "" : value);
        if (matcher.find()) {
            return parseInt(matcher.group(1), -1);
        }
        String normalized = normalize(value);
        for (String token : normalized.split(" ")) {
            if (!token.matches("\\d{1,4}")) continue;
            int number = parseInt(token, -1);
            if (number <= 0 || isVideoQualityNumber(number)) continue;
            return number;
        }
        return -1;
    }

    static ArrayList<String> significantTokens(String value) {
        ArrayList<String> tokens = new ArrayList<>();
        for (String token : normalize(value).split(" ")) {
            if (isNoiseToken(token)) continue;
            if (!tokens.contains(token)) tokens.add(token);
        }
        return tokens;
    }

    static boolean matchesSeries(String fileName, List<String> sourceTokens, List<String> playlistTokens) {
        String normalized = normalize(fileName);
        List<String> expected = sourceTokens != null && !sourceTokens.isEmpty() ? sourceTokens : playlistTokens;
        if (expected == null || expected.isEmpty()) return false;
        int matches = 0;
        for (String token : expected) {
            if (normalized.contains(token)) matches++;
        }
        return matches >= Math.min(3, expected.size());
    }

    static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static boolean isNoiseToken(String token) {
        if (token == null || token.length() < 2 || token.matches("\\d+")) return true;
        if ("anime".equals(token) || "episode".equals(token) || "episodes".equals(token) || "video".equals(token)) return true;
        if ("mp4".equals(token) || "mkv".equals(token) || "avi".equals(token) || "mov".equals(token) || "webm".equals(token)) return true;
        if ("480".equals(token) || "480p".equals(token) || "720".equals(token) || "720p".equals(token) || "1080".equals(token) || "1080p".equals(token)) return true;
        if ("audio".equals(token) || "eng".equals(token) || "english".equals(token) || "jpn".equals(token) || "japanese".equals(token)) return true;
        if ("dual".equals(token) || "dub".equals(token) || "dubbed".equals(token) || "sub".equals(token) || "subbed".equals(token) || "subs".equals(token)) return true;
        if ("aac".equals(token) || "x264".equals(token) || "x265".equals(token) || "h264".equals(token) || "h265".equals(token) || "hevc".equals(token)) return true;
        return "gallery".equals(token) || "download".equals(token) || "www".equals(token) || "com".equals(token);
    }

    private static boolean isVideoQualityNumber(int number) {
        return number == 144
                || number == 240
                || number == 360
                || number == 480
                || number == 540
                || number == 720
                || number == 1080
                || number == 1440
                || number == 2160;
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return raw == null ? fallback : Integer.parseInt(raw);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }
}
