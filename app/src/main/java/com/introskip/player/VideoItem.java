package com.introskip.player;

import android.net.Uri;

import java.util.Objects;

public class VideoItem {
    public final Uri uri;
    public final String name;
    public final long durationMs;
    public final long sizeBytes;

    public VideoItem(Uri uri, String name, long durationMs, long sizeBytes) {
        this.uri = uri;
        this.name = name;
        this.durationMs = durationMs;
        this.sizeBytes = sizeBytes;
    }

    public String key() {
        return uri.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof VideoItem)) return false;
        return Objects.equals(key(), ((VideoItem) other).key());
    }

    @Override
    public int hashCode() {
        return key().hashCode();
    }
}
