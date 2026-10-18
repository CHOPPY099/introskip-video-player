package com.choppy.ultimateplayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EpisodeNameParser {
    private static final Pattern EXTENSION = Pattern.compile("(?i)\\.(?:mp4|mkv|avi|mov|webm|m4v|ts|m2ts|wmv|flv)$");
    private static final Pattern BRACKETS = Pattern.compile("\\[[^\\[\\]]*\\]|\\{[^{}]*\\}");
    private static final Pattern COPY_SUFFIX = Pattern.compile("(?:\\s*\\(\\d{1,3}\\))+$");
    private static final Pattern PARENTHESES = Pattern.compile("\\(([^()]*)\\)");
    private static final Pattern SEASON_EPISODE = Pattern.compile(
            "(?i)(?<![a-z0-9])(?:season\\s*|s)(\\d+)[ ._-]*(?:episode|ep|e)[ ._-]*(\\d+)(?:v\\d+)?(?![a-z0-9])");
    private static final Pattern SEASON_X_EPISODE = Pattern.compile(
            "(?i)(?<![a-z0-9])(\\d{1,2})x(\\d{1,3})(?:v\\d+)?(?![a-z0-9])");
    private static final Pattern LABELED_EPISODE = Pattern.compile(
            "(?i)(?<![a-z0-9])(?:episode|ep|e)[ ._:#-]*(\\d+)(?:v\\d+)?(?![a-z0-9])");
    private static final Pattern BARE_EPISODE = Pattern.compile(
            "(?i)(?<![a-z0-9])(\\d{1,5})(?:v\\d+)?(?![a-z0-9])");
    private static final Pattern METADATA = Pattern.compile(
            "(?i)(?<![a-z0-9])(?:\\d{3,4}\\s*[pi]|\\d{3,4}\\s*x\\s*\\d{3,4}|[248]k|"
                    + "(?:8|10|12)\\s*bit|(?:x|h)\\s*26[45]|hevc|avc|av1|"
                    + "aac(?:\\d+)?|ac3|eac3|ddp?(?:\\d+)?|dts|flac|opus|"
                    + "blu[ -]?ray|b[dr]rip|web[ -]?(?:dl|rip)|hdtv|dvd(?:rip)?|"
                    + "audio|download|www)(?![a-z0-9])");
    private static final Pattern LANGUAGE_SUFFIX = Pattern.compile(
            "(?i)(?:[\\s-]+(?:eng|english|jpn|japanese|dual|multi|dub(?:bed)?|sub(?:bed|s)?))+$");
    private static final Pattern EPISODE_RANGE = Pattern.compile("(?i)^\\s*(?:-|to|&)\\s*\\d+(?![a-z0-9])");

    private EpisodeNameParser() {
    }

    static int episodeNumber(String value) {
        return findEpisode(cleanName(value)).number;
    }

    // Unseasoned episodes use season zero; unknowns sort after every known episode.
    static long sortKey(String value) {
        Episode episode = findEpisode(cleanName(value));
        return episode.number < 0 ? Long.MAX_VALUE
                : ((long) Math.max(0, episode.season) << 32) | episode.number;
    }

    static String displayTitle(String value) {
        String name = cleanName(value);
        Episode episode = findEpisode(name);
        String title = seriesTitle(name, episode);
        if (episode.number < 0) return title.isEmpty() ? "Untitled video" : title;
        String label = episode.season < 0 ? "Episode " + episode.number
                : String.format(Locale.US, "S%02dE%02d", episode.season, episode.number);
        return title.isEmpty() ? label : title + " - " + label;
    }

    static ArrayList<String> significantTokens(String value) {
        String name = cleanName(value);
        return titleTokens(seriesTitle(name, findEpisode(name)));
    }

    static boolean matchesSeries(String fileName, List<String> sourceTokens, List<String> playlistTokens) {
        ArrayList<String> expected = cleanTokens(sourceTokens);
        if (expected.isEmpty()) expected = cleanTokens(playlistTokens);
        // Full, ordered title identity avoids both substring matches and shared-title spin-offs.
        return !expected.isEmpty() && significantTokens(fileName).equals(expected);
    }

    static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String cleanName(String value) {
        if (value == null) return "";
        String name = value.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(slash + 1);
        // Remove release tags and browser copy counters before looking for any numbers.
        String previous;
        do {
            previous = name;
            name = BRACKETS.matcher(name).replaceAll(" ").trim();
        } while (!name.equals(previous));
        name = COPY_SUFFIX.matcher(name).replaceFirst("");
        name = EXTENSION.matcher(name).replaceFirst("");
        name = COPY_SUFFIX.matcher(name.trim()).replaceFirst("");
        Matcher parentheses = PARENTHESES.matcher(name);
        StringBuffer cleaned = new StringBuffer();
        while (parentheses.find()) {
            String content = parentheses.group(1).replaceAll("[._]+", " ");
            parentheses.appendReplacement(cleaned, (METADATA.matcher(content).find()
                    || LANGUAGE_SUFFIX.matcher(" " + content.trim()).find())
                    ? " " : Matcher.quoteReplacement(parentheses.group()));
        }
        parentheses.appendTail(cleaned);
        name = cleaned.toString();
        int handle = name.indexOf('@');
        if (handle >= 0) name = name.substring(0, handle);
        return name.replaceAll("[._]+", " ").replaceAll("\\s+", " ").trim();
    }

    private static Episode findEpisode(String name) {
        Matcher matcher = SEASON_EPISODE.matcher(name);
        if (matcher.find()) return explicitEpisode(name, matcher, true);
        matcher = SEASON_X_EPISODE.matcher(name);
        if (matcher.find()) return explicitEpisode(name, matcher, true);
        matcher = LABELED_EPISODE.matcher(name);
        if (matcher.find()) return explicitEpisode(name, matcher, false);

        String title = withoutMetadata(name);
        matcher = BARE_EPISODE.matcher(title);
        while (matcher.find()) {
            int number = parseInt(matcher.group(1));
            String prefix = title.substring(0, matcher.start()).trim();
            String suffix = title.substring(matcher.end()).trim();
            boolean separated = prefix.endsWith("-");
            if (number <= 0 || !prefix.matches(".*[a-zA-Z].*")) continue;
            if (EPISODE_RANGE.matcher(suffix).find()) return new Episode(-1, -1, matcher.start());
            if (!separated && prefix.matches(".*(?:^| )\\d+$")) continue;
            // A year is too ambiguous unless an episode delimiter or zero padding makes intent clear.
            if (!separated && number >= 1900 && number <= 2099
                    && !matcher.group(1).startsWith("0")) continue;
            boolean qualitySuffix = suffix.matches("\\d{3,4}") && isVideoQualityNumber(parseInt(suffix));
            boolean episodeTitle = separated && suffix.matches("-\\s*[a-zA-Z].*");
            if (!suffix.isEmpty() && !qualitySuffix && !episodeTitle) continue;
            return new Episode(-1, number, matcher.start());
        }
        return new Episode(-1, -1, -1);
    }

    private static Episode explicitEpisode(String name, Matcher matcher, boolean seasonal) {
        int start = matcher.start();
        int end = matcher.end();
        int season = seasonal ? parseInt(matcher.group(1)) : -1;
        int number = parseInt(matcher.group(seasonal ? 2 : 1));
        String suffix = name.substring(end);
        Matcher trailingNumber = BARE_EPISODE.matcher(suffix.trim());
        boolean ambiguousNumber = trailingNumber.lookingAt()
                && !isVideoQualityNumber(parseInt(trailingNumber.group(1)));
        if ((seasonal && season < 0) || matcher.find() || ambiguousNumber
                || EPISODE_RANGE.matcher(suffix).find() || LABELED_EPISODE.matcher(suffix).find()) number = -1;
        return new Episode(season, number, start);
    }

    private static String seriesTitle(String name, Episode episode) {
        String title = episode.start < 0 ? name : name.substring(0, episode.start);
        title = title.replaceAll("^[\\s-]+|[\\s-]+$", "");
        return withoutMetadata(title).replaceAll("^[\\s-]+|[\\s-]+$", "").trim();
    }

    private static String withoutMetadata(String value) {
        Matcher matcher = METADATA.matcher(value);
        String title = (matcher.find() ? value.substring(0, matcher.start()) : value).trim();
        return LANGUAGE_SUFFIX.matcher(title).replaceFirst("").trim();
    }

    private static ArrayList<String> titleTokens(String title) {
        ArrayList<String> tokens = new ArrayList<>();
        for (String token : normalize(title).split(" ")) {
            if (isNoiseToken(token)) continue;
            if (!tokens.contains(token)) tokens.add(token);
        }
        return tokens;
    }

    private static ArrayList<String> cleanTokens(List<String> values) {
        ArrayList<String> tokens = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                for (String token : titleTokens(value)) {
                    if (!tokens.contains(token)) tokens.add(token);
                }
            }
        }
        return tokens;
    }

    private static boolean isNoiseToken(String token) {
        return token.isEmpty() || METADATA.matcher(token).matches()
                || "mp4".equals(token) || "mkv".equals(token) || "avi".equals(token)
                || "mov".equals(token) || "webm".equals(token);
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

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static final class Episode {
        final int season;
        final int number;
        final int start;

        Episode(int season, int number, int start) {
            this.season = season;
            this.number = number;
            this.start = start;
        }
    }
}
