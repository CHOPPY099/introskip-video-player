package com.choppy.ultimateplayer;

import android.content.Context;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LibraryIntegrationTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void indexedPickerAndScannerShareIdentityAndHistoryUri() {
        VideoItem picked = item("content://com.android.providers.media.documents/document/video%3A123", "Episode 483");
        VideoItem scanned = item("content://media/external/video/media/123", "Episode 483");
        List<VideoItem> merged = MediaIdentity.reconcile(context, Arrays.asList(scanned, picked), Collections.singletonList(picked));
        assertEquals(1, merged.size());
        assertEquals(picked.key(), merged.get(0).key());
        assertEquals(MediaIdentity.key(context, scanned.uri), MediaIdentity.key(context, Uri.parse("content://media/external_primary/video/media/123")));
    }

    @Test public void sameFilenameOnDifferentMediaIdsIsNotCollapsed() {
        VideoItem first = item("content://media/external/video/media/123", "Episode 483");
        VideoItem second = item("content://media/external/video/media/124", "Episode 483");
        assertEquals(2, MediaIdentity.reconcile(context, Arrays.asList(first, second), Collections.emptyList()).size());
    }

    @Test public void browserSourceSurvivesAnOlderLibraryStoreSave() {
        LibraryStore first = new LibraryStore(context), browser = new LibraryStore(context);
        String name = "Source persistence test";
        browser.source(name, "https://example.org/episode-485");
        first.put(name, Collections.emptyList());
        assertEquals("https://example.org/episode-485", new LibraryStore(context).source(name));
        first.delete(name);
    }

    @Test public void mixedFilenameStylesSortByEpisodeNotResolution() {
        String name = "Episode ordering test";
        LibraryStore store = new LibraryStore(context);
        VideoItem a = item("content://media/external/video/media/201", "One.piece.audio.eng.episode.483_480[1].mp4");
        VideoItem b = item("content://media/external/video/media/202", "One_Piece_-_0485_[720p]_[Dual]_@Anime_Gallery[1].mkv");
        store.add(name, Arrays.asList(b, a));
        assertEquals(a, new LibraryStore(context).get(name).get(0));
        store.delete(name);
    }

    private VideoItem item(String uri, String name) { return new VideoItem(Uri.parse(uri), name, 12000, 1024); }
}
