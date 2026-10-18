package com.choppy.ultimateplayer;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Resolves indexed picker/scan aliases while retaining the original permission-bearing URI. */
final class MediaIdentity {
    private MediaIdentity() { }

    static String key(Context context, Uri uri) {
        Uri media = uri;
        if ("com.android.providers.media.documents".equals(uri.getAuthority())) {
            try {
                String id = DocumentsContract.getDocumentId(uri);
                if (id.startsWith("video:")) return "media-video:" + Long.parseLong(id.substring(6));
            } catch (RuntimeException ignored) { }
        }
        if (Build.VERSION.SDK_INT >= 29 && !"media".equals(uri.getAuthority())) {
            try {
                Uri resolved = MediaStore.getMediaUri(context, uri);
                if (resolved != null) media = resolved;
            } catch (RuntimeException ignored) { }
        }
        List<String> path = media.getPathSegments();
        if ("media".equals(media.getAuthority()) && path.size() == 4
                && ("external".equals(path.get(0)) || "external_primary".equals(path.get(0)))
                && "video".equals(path.get(1)) && "media".equals(path.get(2))) {
            try { return "media-video:" + Long.parseLong(path.get(3)); }
            catch (NumberFormatException ignored) { }
        }
        return uri.toString();
    }

    static ArrayList<VideoItem> reconcile(Context context, List<VideoItem> incoming, List<VideoItem> known) {
        Map<String, VideoItem> identities = new HashMap<>();
        for (VideoItem item : known) identities.putIfAbsent(key(context, item.uri), item);
        ArrayList<VideoItem> result = new ArrayList<>();
        for (VideoItem item : incoming) {
            VideoItem existing = identities.putIfAbsent(key(context, item.uri), item);
            VideoItem selected = existing == null ? item : existing;
            if (!result.contains(selected)) result.add(selected);
        }
        return result;
    }
}
