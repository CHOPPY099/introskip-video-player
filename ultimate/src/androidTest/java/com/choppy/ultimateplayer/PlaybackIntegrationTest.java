package com.choppy.ultimateplayer;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.View;
import android.view.TextureView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Real LibVLC + Android MediaSession tests using a generated dual-audio video. */
@RunWith(AndroidJUnit4.class)
public class PlaybackIntegrationTest {
    private Instrumentation instrumentation;
    private Context context;
    private MainActivity activity;
    private BackgroundPlayerService service;
    private ServiceConnection connection;
    private final List<VideoItem> items = new ArrayList<>();

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        context.getSharedPreferences("ultimate_store", Context.MODE_PRIVATE).edit()
                .putString("screen", "Player").putInt("intro_ms", 2000).putFloat("speed", 1f).commit();
        for (int i = 0; i < 6; i++) {
            File file = new File(context.getFilesDir(), "Test.Series.episode." + (479+i) + ".mkv");
            try (InputStream input = instrumentation.getContext().getAssets().open("dual-audio.mkv"); FileOutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            items.add(new VideoItem(Uri.fromFile(file), file.getName(), 12000, file.length()));
        }
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        CountDownLatch connected = new CountDownLatch(1);
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                service = ((BackgroundPlayerService.LocalBinder) binder).getService(); connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        context.bindService(new Intent(context, BackgroundPlayerService.class), connection, Context.BIND_AUTO_CREATE);
        assertTrue("service bind", connected.await(10, TimeUnit.SECONDS));
        onMain(() -> {
            service.pause(); service.clearPlaylist(); service.clearWatched();
            for (VideoItem item : items) service.clearHistory(item);
            new LibraryStore(context).put("QA series", items);
            service.setQueue("QA series", items);
            service.setStartOffsetMs(2000); service.setPlaybackSpeed(1); service.setAutoplayNext(true);
            service.clearPreferredAudioTrack();
            context.startService(new Intent(context, BackgroundPlayerService.class));
        });
    }

    @After public void tearDown() {
        if (service != null) onMain(() -> { service.pause(); service.setSleepTimerMinutes(0); });
        if (connection != null) context.unbindService(connection);
        if (activity != null) onMain(activity::finish);
    }

    @Test public void seekPauseRateAndMediaSessionMilliseconds() throws Exception {
        onMain(() -> service.playIndex(0));
        await("start", () -> service.isPlaying() && service.getCurrentPositionMs() >= 1500, 15000);
        onMain(() -> service.seekTo(6000));
        await("seek to six seconds", () -> service.getCurrentPositionMs() >= 5600, 5000);
        onMain(service::pause);
        int paused = value(service::getCurrentPositionMs);
        onMain(() -> service.setPlaybackSpeed(1.5f));
        SystemClock.sleep(1000);
        assertFalse("rate change must not resume", value(service::isPlaying));
        assertTrue("paused position stable", Math.abs(value(service::getCurrentPositionMs)-paused) < 400);
        Field field = BackgroundPlayerService.class.getDeclaredField("mediaSession"); field.setAccessible(true);
        MediaSession session = (MediaSession) field.get(service);
        MediaController controller = new MediaController(context, session.getSessionToken());
        controller.getTransportControls().seekTo(2000);
        await("session seek uses milliseconds", () -> Math.abs(service.getCurrentPositionMs()-2000) < 700, 5000);
        controller.getTransportControls().play();
        await("session play", service::isPlaying, 5000);
        await("published session playing state", () -> controller.getPlaybackState() != null
                && controller.getPlaybackState().getState() == PlaybackState.STATE_PLAYING
                && Math.abs(controller.getPlaybackState().getPlaybackSpeed()-1.5f) < .01f, 3000);
        PlaybackState before = controller.getPlaybackState();
        assertEquals(1.5f, before.getPlaybackSpeed(), .01f);
        SystemClock.sleep(1800);
        assertTrue("session timeline advances", controller.getPlaybackState().getPosition() > before.getPosition()+500);
    }

    @Test public void manualNextDoesNotMarkWatchedAndPreviewKeepsSeries() throws Exception {
        onMain(() -> service.playIndex(0)); await("play", service::isPlaying, 15000);
        onMain(() -> service.seekTo(5000)); onMain(service::next);
        await("next", () -> service.getCurrentIndex()==1 && service.isPlaying(), 15000);
        assertFalse("manual skip is not completion", value(() -> service.isWatched(items.get(0))));
        assertEquals(6, (int)value(() -> service.getPlaylist().size()));
        onMain(() -> service.seekTo(4500));
        String anchor = value(() -> service.getSeriesAnchorKey("QA series"));
        onMain(() -> service.previewIndex(4));
        await("preview", () -> service.getCurrentIndex()==4 && service.isPlaying(), 15000);
        assertEquals(anchor, value(() -> service.getSeriesAnchorKey("QA series")));
        onMain(service::continueSeries);
        await("return to series", () -> service.getCurrentIndex()==1 && service.isPlaying(), 15000);
        assertTrue("resume prior position", value(service::getCurrentPositionMs) >= 4000);
        assertFalse(value(service::isPreviewing));
    }

    @Test public void sixAudioOnlyCompletionsKeepOnlyLastCompletedAnchor() throws Exception {
        onMain(() -> service.playIndex(0)); await("initial play", service::isPlaying, 15000);
        // Background the real activity so its surface detaches and the service runs audio-only.
        onMain(() -> activity.moveTaskToBack(true));
        for (int index=0; index<items.size(); index++) {
            VideoItem expected = items.get(index);
            await("episode " + index, () -> expected.equals(service.getCurrentItem()) && service.isPlaying(), 15000);
            if (index>0) assertTrue("intro applied to next episode", value(service::getCurrentPositionMs)>=1500);
            onMain(() -> service.seekTo(10500));
            await("natural completion " + index, () -> service.isWatched(expected), 10000);
        }
        assertEquals("only newest completed episode retained", 1, (int)value(() -> service.getPlaylist().size()));
        assertEquals(items.get(5), value(service::getCurrentItem));
        assertFalse(value(service::isPlaying));
    }

    @Test public void audioLanguageFollowsNextEpisodeAndPauseCancelsLoad() throws Exception {
        onMain(() -> service.playIndex(0)); await("play", service::isPlaying, 15000);
        await("dual audio", () -> service.getAudioTrackLabels().size()>=2, 5000);
        onMain(() -> service.selectAudioTrack(1));
        String language = value(service::getPreferredAudioLanguage);
        assertFalse("language metadata", language.isEmpty());
        onMain(service::next); await("next", () -> service.getCurrentIndex()==1 && service.isPlaying(), 15000);
        await("remember selected language", () -> service.getSelectedAudioTrackIndex()==1, 5000);
        assertEquals(language, value(service::getPreferredAudioLanguage));
        onMain(() -> { service.playIndex(3); service.pause(); });
        SystemClock.sleep(1500);
        assertFalse("pause during preparation stays paused", value(service::isPlaying));
        assertFalse(value(service::isPlayRequested));
    }

    @Test public void continuationAfterCompletionDoesNotGoBackToEarlierUnwatchedEpisode() throws Exception {
        onMain(() -> { service.setAutoplayNext(false); service.playIndex(2); });
        await("middle episode", service::isPlaying, 15000);
        onMain(() -> service.seekTo(10500));
        await("middle episode completes", () -> service.isWatched(items.get(2)), 10000);
        onMain(service::continueSeries);
        await("continue forward", () -> items.get(3).equals(service.getCurrentItem()) && service.isPlaying(), 15000);
        assertFalse(value(() -> service.isWatched(items.get(0))));
    }

    @Test public void deletingActiveQueueClearsIdentityBeforeRecreatingSamePlaylist() {
        onMain(service::clearPlaylist);
        assertTrue(value(service::getQueueName).isEmpty());
        onMain(() -> service.setQueue("QA series", items));
        assertEquals("QA series", value(service::getQueueName));
        assertEquals(6, (int)value(() -> service.getPlaylist().size()));
    }

    @Test public void portraitSeekBarAndTransportStayInsideVideo() throws Exception {
        SystemClock.sleep(1200);
        Field videoField = MainActivity.class.getDeclaredField("video"); videoField.setAccessible(true);
        Field seekField = MainActivity.class.getDeclaredField("seek"); seekField.setAccessible(true);
        Field controlsField = MainActivity.class.getDeclaredField("transport"); controlsField.setAccessible(true);
        View video = (View)videoField.get(activity), seek = (View)seekField.get(activity), controls = (View)controlsField.get(activity);
        onMain(() -> {
            android.graphics.Rect videoBounds = new android.graphics.Rect(), seekBounds = new android.graphics.Rect(), controlsBounds = new android.graphics.Rect();
            assertTrue("video visible", video.getGlobalVisibleRect(videoBounds));
            assertTrue("seek bar visible", seek.getGlobalVisibleRect(seekBounds));
            assertTrue("controls visible", controls.getGlobalVisibleRect(controlsBounds));
            assertTrue("seek bar within video", videoBounds.contains(seekBounds));
            assertTrue("controls must not cover seek bar", controlsBounds.bottom <= seekBounds.top);
        });
    }

    @Test public void videoHasRealPixelsAndSurvivesLandscapeRotation() throws Exception {
        Field field = MainActivity.class.getDeclaredField("texture"); field.setAccessible(true);
        TextureView texture = (TextureView)field.get(activity);
        onMain(() -> service.playIndex(0));
        await("video starts", service::isPlaying, 15000);
        await("portrait decoded pixels", () -> hasColorFrame(texture), 8000);
        awaitMovingFrame(texture, "portrait moving frames");
        Field playlistField = MainActivity.class.getDeclaredField("playlistName"); playlistField.setAccessible(true);
        java.lang.reflect.Method switchTab = MainActivity.class.getDeclaredMethod("switchTab", String.class); switchTab.setAccessible(true);
        onMain(() -> {
            try { playlistField.set(activity, "QA series"); switchTab.invoke(activity, "Playlists"); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        await("playlist decoded pixels", () -> hasColorFrame(texture), 8000);
        onMain(service::next);
        await("next episode video", () -> service.getCurrentIndex() == 1 && service.isPlaying() && hasColorFrame(texture), 15000);
        awaitMovingFrame(texture, "next episode moving frames");
        onMain(() -> activity.moveTaskToBack(true));
        SystemClock.sleep(800);
        onMain(() -> activity.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
        await("return from background", () -> activity.hasWindowFocus() && hasColorFrame(texture), 8000);
        awaitMovingFrame(texture, "restored video moving frames");
        onMain(() -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        try {
            await("landscape orientation", () -> activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE, 8000);
            await("landscape decoded pixels", () -> texture.getWidth() > texture.getHeight() && hasColorFrame(texture), 8000);
        } finally {
            onMain(() -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        }
    }

    private void awaitMovingFrame(TextureView texture, String description) {
        long before = value(() -> frameHash(texture));
        await(description, () -> hasColorFrame(texture) && frameHash(texture) != before, 6000);
    }

    @Test public void pictureInPictureContainsVideoAndReturnsToApp() throws Exception {
        Field textureField = MainActivity.class.getDeclaredField("texture"); textureField.setAccessible(true);
        Field navField = MainActivity.class.getDeclaredField("nav"); navField.setAccessible(true);
        Field toolbarField = MainActivity.class.getDeclaredField("toolbar"); toolbarField.setAccessible(true);
        TextureView texture = (TextureView)textureField.get(activity);
        View nav = (View)navField.get(activity), toolbar = (View)toolbarField.get(activity);
        java.lang.reflect.Method enterPip = MainActivity.class.getDeclaredMethod("enterPip"); enterPip.setAccessible(true);
        onMain(() -> service.playIndex(0));
        await("video before PiP", () -> service.isPlaying() && hasColorFrame(texture), 15000);
        onMain(() -> {
            try { enterPip.invoke(activity); } catch (Exception error) { throw new RuntimeException(error); }
        });
        await("PiP active", activity::isInPictureInPictureMode, 6000);
        assertEquals(View.GONE, (int)value(nav::getVisibility));
        assertEquals(View.GONE, (int)value(toolbar::getVisibility));
        await("PiP contains video", () -> hasColorFrame(texture), 6000);
        awaitMovingFrame(texture, "PiP keeps moving");
        onMain(() -> activity.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
        await("return from PiP", () -> !activity.isInPictureInPictureMode() && activity.hasWindowFocus(), 6000);
        await("video after PiP", () -> hasColorFrame(texture), 6000);
        assertEquals(View.VISIBLE, (int)value(nav::getVisibility));
    }

    private long frameHash(TextureView texture) {
        Bitmap bitmap = texture.getBitmap(64, 36);
        if (bitmap == null) return 0;
        int[] pixels = new int[64*36]; bitmap.getPixels(pixels, 0, 64, 0, 0, 64, 36); bitmap.recycle();
        return java.util.Arrays.hashCode(pixels);
    }

    private boolean hasColorFrame(TextureView texture) {
        Bitmap bitmap = texture.getBitmap(64, 36);
        if (bitmap == null) return false;
        int colored = 0;
        for (int y=0; y<36; y++) for (int x=0; x<64; x++) {
            int color = bitmap.getPixel(x, y);
            int max = Math.max(Color.red(color), Math.max(Color.green(color), Color.blue(color)));
            int min = Math.min(Color.red(color), Math.min(Color.green(color), Color.blue(color)));
            if (max-min > 40) colored++;
        }
        bitmap.recycle();
        return colored > 300;
    }

    private void onMain(Runnable action) { instrumentation.runOnMainSync(action); }
    private <T> T value(java.util.concurrent.Callable<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        onMain(() -> { try { result.set(action.call()); } catch (Exception e) { throw new RuntimeException(e); } });
        return result.get();
    }
    private void await(String description, BooleanSupplier condition, long timeout) {
        long deadline=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<deadline) {
            if(value(condition::getAsBoolean)) return;
            SystemClock.sleep(100);
        }
        fail(description + ": " + value(service::getLastError) + ", index=" + value(service::getCurrentIndex)
                + ", pos=" + value(service::getCurrentPositionMs));
    }
}
