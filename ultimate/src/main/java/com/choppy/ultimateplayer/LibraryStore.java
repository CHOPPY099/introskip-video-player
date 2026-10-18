package com.choppy.ultimateplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LibraryStore {
    final SharedPreferences preferences;
    private final LinkedHashMap<String, ArrayList<VideoItem>> playlists = new LinkedHashMap<>();
    private final LinkedHashMap<String, VideoItem> catalog = new LinkedHashMap<>();

    LibraryStore(Context context) {
        preferences = context.getSharedPreferences("ultimate_store", Context.MODE_PRIVATE);
        try {
            JSONObject data = new JSONObject(preferences.getString("playlists", "{}"));
            java.util.Iterator<String> names = data.keys();
            while (names.hasNext()) {
                String name = names.next();
                playlists.put(name, decode(data.getJSONArray(name)));
            }
            for (VideoItem item : decode(new JSONArray(preferences.getString("catalog", "[]")))) {
                catalog.put(item.key(), item);
            }
        } catch (JSONException ignored) { }
        for (List<VideoItem> items : playlists.values()) for (VideoItem item : items) catalog.put(item.key(), item);
    }

    List<String> names() { return new ArrayList<>(playlists.keySet()); }
    boolean contains(String name) { return playlists.containsKey(name); }
    ArrayList<VideoItem> get(String name) { return new ArrayList<>(playlists.getOrDefault(name, new ArrayList<>())); }
    ArrayList<VideoItem> catalog() { return new ArrayList<>(catalog.values()); }

    void put(String name, List<VideoItem> items) {
        if (name == null || name.trim().isEmpty()) return;
        ArrayList<VideoItem> unique = new ArrayList<>();
        for (VideoItem item : items) {
            if (!unique.contains(item)) unique.add(item);
            catalog.put(item.key(), item);
        }
        playlists.put(name, unique);
        save();
    }

    void add(String name, List<VideoItem> items) {
        ArrayList<VideoItem> merged = get(name);
        for (VideoItem item : items) if (!merged.contains(item)) merged.add(item);
        sort(merged);
        put(name, merged);
    }

    static void sort(List<VideoItem> items) {
        items.sort((a, b) -> {
            int result = Long.compare(EpisodeNameParser.sortKey(a.name), EpisodeNameParser.sortKey(b.name));
            return result == 0 ? a.name.compareToIgnoreCase(b.name) : result;
        });
    }

    void delete(String name) { playlists.remove(name); save(); }

    String source(String name) {
        try { return new JSONObject(preferences.getString("playlist_sources", "{}")).optString(name, ""); }
        catch (JSONException ignored) { return ""; }
    }

    void source(String name, String url) {
        try {
            JSONObject sources = new JSONObject(preferences.getString("playlist_sources", "{}"));
            sources.put(name, url);
            preferences.edit().putString("playlist_sources", sources.toString()).apply();
        } catch (JSONException ignored) { }
    }

    private void save() {
        try {
            JSONObject data = new JSONObject();
            for (Map.Entry<String, ArrayList<VideoItem>> entry : playlists.entrySet()) data.put(entry.getKey(), encode(entry.getValue()));
            preferences.edit().putString("playlists", data.toString())
                    .putString("catalog", encode(new ArrayList<>(catalog.values())).toString()).apply();
        } catch (JSONException ignored) { }
    }

    private static JSONArray encode(List<VideoItem> items) throws JSONException {
        JSONArray result = new JSONArray();
        for (VideoItem item : items) result.put(new JSONObject().put("uri", item.key()).put("name", item.name)
                .put("duration", item.durationMs).put("size", item.sizeBytes));
        return result;
    }

    private static ArrayList<VideoItem> decode(JSONArray data) throws JSONException {
        ArrayList<VideoItem> result = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            result.add(new VideoItem(Uri.parse(item.getString("uri")), item.optString("name", "Video"),
                    item.optLong("duration"), item.optLong("size")));
        }
        return result;
    }
}
