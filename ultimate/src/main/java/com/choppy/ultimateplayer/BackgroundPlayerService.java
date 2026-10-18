package com.choppy.ultimateplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.interfaces.IVLCVout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Binder calls, VLC events and policy are main-thread confined; disk writes are serialized. */
public class BackgroundPlayerService extends Service {
    private static final String CHANNEL_ID = "playback";
    private static final int NOTIFICATION_ID = 42;
    private static final String ACTION_PLAY_PAUSE = "com.choppy.ultimateplayer.PLAY_PAUSE";
    private static final String ACTION_NEXT = "com.choppy.ultimateplayer.NEXT";
    private static final String ACTION_PREVIOUS = "com.choppy.ultimateplayer.PREVIOUS";
    private static final String ACTION_STOP = "com.choppy.ultimateplayer.STOP";
    private static final long LOAD_TIMEOUT_MS = 30000;
    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService stateWriter = Executors.newSingleThreadExecutor();
    private final ExecutorService playerReleaser = Executors.newSingleThreadExecutor();
    private final ExecutorService fontCacheWorker = Executors.newSingleThreadExecutor();
    private final ArrayList<VideoItem> playlist = new ArrayList<>();
    private final LinkedHashMap<String, VideoItem> catalog = new LinkedHashMap<>();
    private final PlaybackLedger ledger = new PlaybackLedger();
    private final Map<String, String> audioLanguages = new HashMap<>();
    private final Map<String, Integer> audioOrdinals = new HashMap<>();
    private SharedPreferences preferences;
    private LibVLC libVLC;
    private LibVLC startupLibVLC;
    private boolean fontCacheReady, fontCacheWarming, textRendererDeferred;
    private int pendingSubtitleOrdinal = -1;
    private MediaPlayer player;
    private AssetFileDescriptor mediaDescriptor;
    private MediaSession mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private PowerManager.WakeLock wakeLock;
    private PlayerListener listener;
    private Surface outputSurface;
    private int outputWidth, outputHeight, videoWidth, videoHeight;
    private int currentIndex = -1;
    private int currentPositionMs, durationMs;
    private int startOffsetMs = 180000;
    private int preferredAudioTrackIndex = -1;
    private String preferredAudioLanguage = "";
    private float playbackSpeed = 1f;
    private boolean autoplayNext = true;
    private boolean playRequested, prepared, loading, sessionStarted, ended;
    private boolean foreground, serviceStarted, focusHeld, resumeAfterFocusGain;
    private boolean restoredState, destroyed, tickerScheduled, initialSeekApplied, introPending, explicitPositionPending;
    private int pendingSeekMs = -1;
    private long pendingSeekExpires, lastPersistAt, loadStartedAt, sleepDeadlineElapsed;
    private int generation, retryCount;
    private String queueName = "Playlist";
    private String pendingQueueName;
    private String lastError = "";
    private Runnable retryRunnable;

    public interface PlayerListener { void onPlayerChanged(); }
    public class LocalBinder extends Binder {
        public BackgroundPlayerService getService() { return BackgroundPlayerService.this; }
    }
    private final BroadcastReceiver noisyAudioReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction()) && playRequested) pause();
        }
    };
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            tickerScheduled = false;
            if (destroyed) return;
            long now = SystemClock.elapsedRealtime();
            if (sleepDeadlineElapsed > 0 && now >= sleepDeadlineElapsed) {
                sleepDeadlineElapsed = 0;
                pause();
            }
            if (loading && playRequested && now - loadStartedAt > LOAD_TIMEOUT_MS) {
                handleFailure("Playback timed out. Check the file or network.", true);
            }
            if (prepared) {
                readPosition();
                if (now - lastPersistAt >= 5000) { snapshotCurrentProgress(); persist(false); }
            }
            updatePlaybackState();
            updateWakeLock();
            if (playRequested || loading || sleepDeadlineElapsed > 0) scheduleTicker();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        preferences = getSharedPreferences("ultimate_playback", MODE_PRIVATE);
        restoreState();
        wakeLock = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UltimatePlayer:Playback");
        wakeLock.setReferenceCounted(false);
        createNotificationChannel();
        setupAudioFocus();
        setupMediaSession();
        try { ensureLibraries(); }
        catch (RuntimeException | LinkageError error) { lastError = "Playback engine unavailable: " + errorText(error); }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisyAudioReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED);
        } else registerReceiver(noisyAudioReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        if (sleepDeadlineElapsed > 0) scheduleTicker();
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        serviceStarted = true;
        enterForeground(); // Satisfy callers using startForegroundService before binding.
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PLAY_PAUSE.equals(action)) { if (playRequested) pause(); else play(); }
        else if (ACTION_NEXT.equals(action)) next();
        else if (ACTION_PREVIOUS.equals(action)) previous();
        else if (ACTION_STOP.equals(action)) stopPlayback();
        else refreshNotification();
        return START_STICKY;
    }
    @Override public void onDestroy() {
        snapshotCurrentProgress();
        playRequested = false;
        persist(true);
        destroyed = true;
        listener = null;
        main.removeCallbacksAndMessages(null);
        releasePlayer();
        if (libVLC != null) {
            LibVLC oldLibrary = libVLC;
            libVLC = null;
            playerReleaser.execute(oldLibrary::release);
        }
        if (startupLibVLC != null) {
            LibVLC oldLibrary = startupLibVLC;
            startupLibVLC = null;
            playerReleaser.execute(oldLibrary::release);
        }
        fontCacheWorker.shutdown();
        playerReleaser.shutdown();
        abandonAudioFocus();
        updateWakeLock();
        if (mediaSession != null) mediaSession.release();
        try { unregisterReceiver(noisyAudioReceiver); } catch (IllegalArgumentException ignored) { }
        stopForeground(true);
        stateWriter.shutdown();
        super.onDestroy();
    }
    @Override public void onTaskRemoved(Intent rootIntent) {
        snapshotCurrentProgress();
        persist(true);
        super.onTaskRemoved(rootIntent);
    }
    public void setListener(PlayerListener value) { listener = value; notifyChanged(); }
    public void setOutputSurface(Surface surface) {
        if (surface == outputSurface && player != null && player.getVLCVout().areViewsAttached()) return;
        Log.d("UltimatePlayback", "surface attach generation=" + generation + " loading=" + loading);
        outputSurface = surface;
        attachSurface();
    }
    public void clearOutputSurface(Surface surface) {
        if (surface != outputSurface) return;
        if (surface != null) Log.d("UltimatePlayback", "surface detach generation=" + generation + " loading=" + loading);
        outputSurface = null;
        attachSurface();
    }
    public void setOutputSize(int width, int height) {
        outputWidth = Math.max(0, width);
        outputHeight = Math.max(0, height);
        if (player != null && outputWidth > 0 && outputHeight > 0) player.getVLCVout().setWindowSize(outputWidth, outputHeight);
    }
    private void attachSurface() {
        if (player == null) return;
        IVLCVout vout = player.getVLCVout();
        player.setVideoTrackEnabled(false);
        if (vout.areViewsAttached()) vout.detachViews();
        if (outputSurface == null || !outputSurface.isValid()) return;
        try {
            vout.setVideoSurface(outputSurface, null);
            // Null layout listener selects GLES, which composites subtitles into this Surface.
            vout.attachViews();
            int width = outputWidth > 0 ? outputWidth : getResources().getDisplayMetrics().widthPixels;
            int height = outputHeight > 0 ? outputHeight : getResources().getDisplayMetrics().heightPixels;
            vout.setWindowSize(Math.max(1, width), Math.max(1, height));
            player.setVideoTrackEnabled(true);
        } catch (IllegalArgumentException | IllegalStateException error) {
            if (vout.areViewsAttached()) vout.detachViews();
            lastError = "Video surface unavailable: " + errorText(error);
            notifyChanged();
        }
    }

    /** Staged until replacePlaylist; there is no intermediate callback with mismatched queue/name. */
    public void setQueueName(String name) {
        pendingQueueName = name == null || name.trim().isEmpty() ? "Playlist" : name.trim();
    }
    public String getQueueName() { return queueName; }
    public void setQueue(String name, List<VideoItem> items) { setQueueName(name); replacePlaylist(items); }
    public void addToPlaylist(VideoItem item) {
        if (item == null || playlist.contains(item)) return;
        if (playlist.isEmpty() && pendingQueueName != null) activateQueueName();
        playlist.add(item);
        catalog.put(item.key(), item);
        if (currentIndex < 0) selectCurrent(0, false, false, null);
        persist(true);
        notifyChanged();
    }
    public void replacePlaylist(List<VideoItem> items) {
        ArrayList<VideoItem> replacement = new ArrayList<>();
        if (items != null) for (VideoItem item : items) {
            if (item != null && !replacement.contains(item)) replacement.add(item);
        }
        String newName = pendingQueueName == null ? queueName : pendingQueueName;
        boolean sameSeries = newName.equals(queueName);
        if (sameSeries && playlist.equals(replacement)) { pendingQueueName = null; return; }
        snapshotCurrentProgress();
        persist(true);
        String oldKey = currentKey();
        boolean resume = playRequested;
        playlist.clear();
        playlist.addAll(replacement);
        for (VideoItem item : replacement) catalog.put(item.key(), item);
        activateQueueName();
        int retained = sameSeries ? indexOfKey(oldKey) : -1;
        if (retained >= 0) { currentIndex = retained; persist(true); notifyChanged(); return; }
        ledger.previewing = false;
        int anchor = indexOfKey(ledger.seriesAnchors.get(queueName));
        selectCurrent(anchor >= 0 ? anchor : (playlist.isEmpty() ? -1 : 0), resume, false, null);
    }
    private void activateQueueName() {
        if (pendingQueueName != null) queueName = pendingQueueName;
        pendingQueueName = null;
        preferredAudioTrackIndex = audioOrdinals.getOrDefault(queueName, -1);
        preferredAudioLanguage = audioLanguages.getOrDefault(queueName, "");
    }
    public void removeFromPlaylist(int index) {
        if (!validIndex(index)) return;
        snapshotCurrentProgress();
        boolean removingCurrent = currentIndex == index;
        playlist.remove(index);
        if (removingCurrent) selectCurrent(playlist.isEmpty() ? -1 : Math.min(index, playlist.size() - 1), false, ledger.previewing, null);
        else { if (index < currentIndex) currentIndex--; persist(true); notifyChanged(); }
    }
    public void clearPlaylist() {
        snapshotCurrentProgress();
        playlist.clear();
        queueName = "";
        pendingQueueName = null;
        selectCurrent(-1, false, false, null);
    }
    public void playIndex(int index) {
        if (!validIndex(index)) return;
        if (!ledger.previewing && index == currentIndex) { play(); return; }
        snapshotCurrentProgress();
        selectCurrent(index, true, false, null);
    }
    public void previewIndex(int index) {
        if (!validIndex(index)) return;
        snapshotCurrentProgress();
        ledger.beginPreview(currentKey(), getCurrentPositionMs());
        selectCurrent(index, true, true, 0);
    }
    public boolean isPreviewing() { return ledger.previewing; }
    public void continueSeries() {
        if (playlist.isEmpty()) return;
        if (ledger.previewing) {
            String key = ledger.continuationKey;
            int position = ledger.continuationPositionMs;
            int index = indexOfKey(key);
            if (index < 0 && catalog.containsKey(key)) { playlist.add(0, catalog.get(key)); index = 0; }
            if (index < 0) index = 0;
            if (ledger.watched.contains(playlist.get(index).key())) {
                int next = PlaybackLedger.nextUnwatched(queueKeys(), index, ledger.watched);
                if (next >= 0) { selectCurrent(next, true, false, null); return; }
                selectCurrent(index, false, false, position);
                return;
            }
            selectCurrent(index, true, false, position);
        } else if (getCurrentItem() != null && ledger.watched.contains(currentKey())) {
            int next = PlaybackLedger.nextUnwatched(queueKeys(), currentIndex, ledger.watched);
            if (next >= 0) selectCurrent(next, true, false, null);
        } else play();
    }
    /** Returns the last normal selection for scanning; a preview never moves this anchor. */
    public String getSeriesAnchorKey(String name) { return ledger.seriesAnchors.getOrDefault(name, ""); }
    public void next() {
        if (validIndex(currentIndex + 1)) {
            snapshotCurrentProgress();
            selectCurrent(currentIndex + 1, true, ledger.previewing, ledger.previewing ? Integer.valueOf(0) : null);
        }
    }
    public void previous() {
        if (validIndex(currentIndex - 1)) {
            snapshotCurrentProgress();
            selectCurrent(currentIndex - 1, true, ledger.previewing, ledger.previewing ? Integer.valueOf(0) : null);
        }
    }
    private void selectCurrent(int index, boolean shouldPlay, boolean preview, Integer exactPosition) {
        cancelRetry();
        playRequested = false;
        releasePlayer();
        prepared = loading = sessionStarted = ended = false;
        explicitPositionPending = false;
        currentIndex = index;
        durationMs = validIndex(index) ? toInt(playlist.get(index).durationMs) : 0;
        videoWidth = videoHeight = 0;
        retryCount = 0;
        lastError = "";
        pendingSubtitleOrdinal = -1;
        ledger.previewing = preview;
        currentPositionMs = exactPosition == null ? startPosition() : Math.max(0, exactPosition);
        if (!preview && validIndex(index)) ledger.selectNormal(queueName, currentKey(), currentPositionMs);
        pendingSeekMs = -1;
        persist(true);
        updateMetadata();
        if (shouldPlay && validIndex(index)) loadCurrent(currentPositionMs);
        else { abandonAudioFocus(); updateWakeLock(); refreshNotification(); notifyChanged(); }
    }
    public void play() {
        if (playlist.isEmpty()) return;
        resumeAfterFocusGain = false;
        if (currentIndex < 0) currentIndex = 0;
        if (playRequested && (loading || isPlaying())) return;
        lastError = "";
        retryCount = 0;
        if (player == null || !prepared || ended || (textRendererDeferred && fontCacheReady && pendingSubtitleOrdinal > 0)) {
            int position = ended ? startPosition() : currentPositionMs;
            if (!sessionStarted && !ledger.previewing && !explicitPositionPending) position = startPosition();
            if (explicitPositionPending) position = currentPositionMs;
            loadCurrent(position);
            return;
        }
        playRequested = true;
        if (!beginPlaybackFocus()) return;
        player.setRate(playbackSpeed);
        player.play();
        updateWakeLock();
        scheduleTicker();
        notifyChanged();
    }
    public void pause() { pauseInternal(true); }
    private void pauseInternal(boolean abandonFocus) {
        snapshotCurrentProgress();
        playRequested = false;
        if (abandonFocus) resumeAfterFocusGain = false;
        cancelRetry();
        if (player != null) {
            if (prepared) player.pause();
            else releasePlayer(); // A cancelled preparation must not start later.
        }
        loading = false;
        if (abandonFocus) abandonAudioFocus();
        updateWakeLock();
        persist(true);
        refreshNotification();
        notifyChanged();
    }
    private void loadCurrent(int position) {
        if (getCurrentItem() == null || destroyed) return;
        cancelRetry();
        releasePlayer();
        prepared = sessionStarted = ended = false;
        explicitPositionPending = false;
        playRequested = loading = true;
        loadStartedAt = SystemClock.elapsedRealtime();
        currentPositionMs = Math.max(0, position);
        pendingSeekMs = currentPositionMs;
        pendingSeekExpires = loadStartedAt + LOAD_TIMEOUT_MS;
        initialSeekApplied = false;
        introPending = !ledger.previewing && !ledger.progress.containsKey(currentKey()) && durationMs <= 0;
        if (!beginPlaybackFocus()) return;
        updateWakeLock();
        scheduleTicker();
        try {
            ensureLibraries();
            // VLC creates the text renderer before its video output, even for files without subtitles.
            // A separate bootstrap instance avoids the global font scan on the playback path.
            textRendererDeferred = !fontCacheReady;
            LibVLC playbackLibrary = textRendererDeferred ? startupLibVLC : libVLC;
            player = new MediaPlayer(playbackLibrary);
            Log.i("UltimatePlayback", "load generation=" + generation + " deferredText=" + textRendererDeferred
                    + " surface=" + (outputSurface != null && outputSurface.isValid()));
            int token = generation;
            player.setEventListener(event -> {
                if (!destroyed && token == generation && player != null) handlePlayerEvent(event);
            });
            attachSurface();
            VideoItem item = getCurrentItem();
            Media media;
            if ("content".equalsIgnoreCase(item.uri.getScheme())) {
                mediaDescriptor = getContentResolver().openAssetFileDescriptor(item.uri, "r");
                if (mediaDescriptor == null) throw new IOException("The provider returned no file");
                media = new Media(playbackLibrary, mediaDescriptor);
            } else media = new Media(playbackLibrary, item.uri);
            try {
                media.setHWDecoderEnabled(retryCount == 0, false);
                if (currentPositionMs > 0) media.addOption(":start-time=" + (currentPositionMs / 1000.0));
                player.setMedia(media);
            } finally { media.release(); }
            player.setRate(playbackSpeed);
            player.play();
        } catch (SecurityException error) {
            handleFailure("Video access denied. Grant access or choose the file again.", false);
        } catch (IOException | RuntimeException | LinkageError error) {
            handleFailure("Cannot play this video: " + errorText(error), !(error instanceof IOException));
        }
        refreshNotification();
        notifyChanged();
    }
    private void handlePlayerEvent(MediaPlayer.Event event) {
        switch (event.type) {
            case MediaPlayer.Event.Playing:
                prepared = true;
                loading = false;
                ended = false;
                if (!playRequested) { player.pause(); updateWakeLock(); break; }
                sessionStarted = true;
                lastError = "";
                refreshDurationAndTracks();
                applyInitialSeek();
                player.setRate(playbackSpeed);
                applyPreferredAudioTrack();
                applyPendingSubtitleTrack();
                updateVideoEnabled();
                snapshotCurrentProgress();
                persist(false);
                refreshNotification();
                updateWakeLock();
                scheduleTicker();
                resumeTextRenderingIfNeeded();
                break;
            case MediaPlayer.Event.Paused:
                loading = false;
                updateWakeLock();
                break;
            case MediaPlayer.Event.Buffering:
                boolean buffering = event.getBuffering() < 100f && playRequested;
                if (buffering && !loading) loadStartedAt = SystemClock.elapsedRealtime();
                loading = buffering || (!prepared && playRequested);
                updateWakeLock();
                break;
            case MediaPlayer.Event.TimeChanged:
                if (prepared) readPosition();
                return;
            case MediaPlayer.Event.LengthChanged:
            case MediaPlayer.Event.ESAdded:
                refreshDurationAndTracks();
                applyPreferredAudioTrack();
                applyPendingSubtitleTrack();
                updateVideoEnabled();
                applyInitialSeek();
                break;
            case MediaPlayer.Event.SeekableChanged: applyInitialSeek(); break;
            case MediaPlayer.Event.ESSelected:
            case MediaPlayer.Event.ESDeleted:
            case MediaPlayer.Event.Vout: refreshDurationAndTracks(); break;
            case MediaPlayer.Event.EndReached:
                if (!ended && sessionStarted && playRequested) onNaturalEnd();
                return;
            case MediaPlayer.Event.EncounteredError:
                handleFailure("LibVLC could not decode or read this video.", true);
                return;
            default: return;
        }
        notifyChanged();
    }
    private void onNaturalEnd() {
        int completedPosition = Math.max(durationMs, currentPositionMs);
        currentPositionMs = completedPosition;
        pendingSeekMs = -1;
        playRequested = loading = false;
        ended = true;
        String key = currentKey();
        if (ledger.naturalEnd(queueName, key, completedPosition)) {
            Set<String> kept = new HashSet<>(ledger.pruneCompleted(queueKeys(), queueName));
            for (int i = playlist.size() - 1; i >= 0; i--) if (!kept.contains(playlist.get(i).key())) playlist.remove(i);
            currentIndex = indexOfKey(key);
            int next = autoplayNext ? PlaybackLedger.nextUnwatched(queueKeys(), currentIndex, ledger.watched) : -1;
            persist(true);
            if (next >= 0) { selectCurrent(next, true, false, null); return; }
        }
        // Preview EOF deliberately neither marks watched nor advances the series.
        releasePlayer();
        prepared = false;
        abandonAudioFocus();
        updateWakeLock();
        persist(true);
        refreshNotification();
        notifyChanged();
    }
    private void handleFailure(String message, boolean retryable) {
        boolean retry = playRequested && retryable && retryCount < 1;
        readPosition();
        snapshotCurrentProgress();
        int resumePosition = currentPositionMs;
        lastError = message + (retry ? " Retrying once with software decoding." : " Tap Play to retry.");
        releasePlayer();
        prepared = false;
        loading = retry;
        playRequested = retry;
        if (retry) {
            retryCount++;
            loadStartedAt = SystemClock.elapsedRealtime();
            final int token = generation;
            retryRunnable = () -> {
                retryRunnable = null;
                if (token == generation && playRequested && !destroyed) loadCurrent(resumePosition);
            };
            main.postDelayed(retryRunnable, 1000);
            scheduleTicker();
        } else abandonAudioFocus();
        updateWakeLock();
        persist(true);
        refreshNotification();
        notifyChanged();
    }
    private void cancelRetry() { if (retryRunnable != null) main.removeCallbacks(retryRunnable); retryRunnable = null; }
    private void releasePlayer() {
        generation++;
        MediaPlayer old = player;
        AssetFileDescriptor descriptor = mediaDescriptor;
        player = null;
        mediaDescriptor = null;
        if (old != null) {
            old.setEventListener(null);
            old.setVolume(0);
            old.setVideoTrackEnabled(false);
            if (old.getVLCVout().areViewsAttached()) old.getVLCVout().detachViews();
        }
        if (old != null || descriptor != null) {
            // Native stop may wait for an I/O thread. Views detach on main; blocking teardown does not.
            playerReleaser.execute(() -> {
                try {
                    if (old != null) {
                        try { old.stop(); } finally { old.release(); }
                    }
                } finally {
                    if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) { }
                }
            });
        }
    }
    private void stopPlayback() {
        pause();
        releasePlayer();
        prepared = false;
        stopForeground(true);
        foreground = false;
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
        serviceStarted = false;
        stopSelf();
    }

    public String getLastError() {
        if (lastError.isEmpty() && textRendererDeferred && pendingSubtitleOrdinal > 0 && !fontCacheReady) {
            return fontCacheWarming ? "Preparing subtitle fonts; video playback can continue." : "Subtitle font cache unavailable. Select the subtitle track again to retry.";
        }
        return lastError;
    }
    public boolean isLoading() { return loading; }
    public boolean isPlayRequested() { return playRequested; }
    public boolean isPlaying() { return player != null && prepared && playRequested && player.isPlaying(); }
    public int getCurrentIndex() { return currentIndex; }
    public VideoItem getCurrentItem() { return validIndex(currentIndex) ? playlist.get(currentIndex) : null; }
    public List<VideoItem> getPlaylist() { return new ArrayList<>(playlist); }
    public int getVideoWidth() { return videoWidth; }
    public int getVideoHeight() { return videoHeight; }
    public int getDurationMs() { return durationMs; }
    public int getCurrentPositionMs() { readPosition(); return currentPositionMs; }
    public int getStartOffsetMs() { return startOffsetMs; }
    public boolean isAutoplayNext() { return autoplayNext; }
    public float getPlaybackSpeed() { return playbackSpeed; }
    public void setStartOffsetMs(int value) {
        startOffsetMs = Math.max(0, value);
        if (!sessionStarted && !loading) currentPositionMs = startPosition();
        persist(true);
        notifyChanged();
    }
    public void setAutoplayNext(boolean value) { autoplayNext = value; persist(true); notifyChanged(); }
    public void setPlaybackSpeed(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return;
        readPosition();
        playbackSpeed = Math.max(0.5f, Math.min(2f, value));
        if (player != null) player.setRate(playbackSpeed);
        persist(true);
        notifyChanged();
    }
    public void seekBy(int deltaMs) { seekToLong((long) getCurrentPositionMs() + deltaMs); }
    public void seekTo(int positionMs) { seekToLong(positionMs); }
    private void seekToLong(long positionMs) {
        if (getCurrentItem() == null) return;
        int target = PlaybackLedger.clampResume(positionMs, durationMs);
        currentPositionMs = target;
        pendingSeekMs = target;
        pendingSeekExpires = SystemClock.elapsedRealtime() + (prepared ? 5000 : LOAD_TIMEOUT_MS);
        introPending = false;
        explicitPositionPending = true;
        initialSeekApplied = false;
        if (player != null && prepared && player.isSeekable()) { player.setTime(target, false); initialSeekApplied = true; }
        ledger.snapshot(currentKey(), target);
        persist(true);
        notifyChanged();
    }
    private void applyInitialSeek() {
        if (player == null || !prepared) return;
        if (introPending && durationMs > 0) {
            introPending = false;
            int target = PlaybackLedger.startPosition(null, false, durationMs, startOffsetMs, false);
            if (target > 0) { pendingSeekMs = target; initialSeekApplied = false; }
        }
        if (!initialSeekApplied && pendingSeekMs >= 0 && player.isSeekable()) {
            pendingSeekMs = PlaybackLedger.clampResume(pendingSeekMs, durationMs);
            currentPositionMs = pendingSeekMs;
            player.setTime(pendingSeekMs, false);
            pendingSeekExpires = SystemClock.elapsedRealtime() + 5000;
            initialSeekApplied = true;
        }
    }
    private void readPosition() {
        if (player == null || !prepared || ended) return;
        long raw = player.getTime();
        if (raw < 0) return;
        if (pendingSeekMs >= 0) {
            if (Math.abs(raw - pendingSeekMs) <= 250 || SystemClock.elapsedRealtime() > pendingSeekExpires) pendingSeekMs = -1;
            else { currentPositionMs = pendingSeekMs; return; }
        }
        currentPositionMs = toInt(raw);
    }
    private int startPosition() {
        return PlaybackLedger.startPosition(ledger.progress.get(currentKey()), ledger.watched.contains(currentKey()), durationMs, startOffsetMs, ledger.previewing);
    }
    private void snapshotCurrentProgress() {
        if (getCurrentItem() == null || !sessionStarted || ended) return;
        readPosition();
        ledger.snapshot(currentKey(), currentPositionMs);
    }
    public int getSavedProgressMs(VideoItem item) { return item == null ? 0 : ledger.progress.getOrDefault(item.key(), 0); }
    public boolean hasSavedProgress(VideoItem item) { return getSavedProgressMs(item) > 0; }
    public boolean isWatched(VideoItem item) { return item != null && ledger.watched.contains(item.key()); }
    public Map<String, Integer> getProgressSnapshot() { snapshotCurrentProgress(); return new HashMap<>(ledger.progress); }
    public Set<String> getWatchedSnapshot() { return new HashSet<>(ledger.watched); }
    public Set<String> getWatchedKeysSnapshot() { return getWatchedSnapshot(); }
    public List<VideoItem> getHistoryItems() {
        ArrayList<VideoItem> result = new ArrayList<>();
        for (VideoItem item : catalog.values()) if (ledger.progress.containsKey(item.key()) || ledger.watched.contains(item.key())) result.add(item);
        return result;
    }
    public void clearWatched() { ledger.watched.clear(); ledger.completedAnchors.clear(); persist(true); notifyChanged(); }
    public void clearHistory(VideoItem item) {
        if (item == null) return;
        ledger.progress.remove(item.key());
        ledger.watched.remove(item.key());
        ledger.completedAnchors.values().removeAll(Arrays.asList(item.key()));
        if (item.key().equals(ledger.continuationKey)) ledger.continuationPositionMs = 0;
        if (item.key().equals(currentKey()) && !ledger.previewing) {
            currentPositionMs = startPosition();
            if (player != null && prepared) {
                pendingSeekMs = currentPositionMs;
                pendingSeekExpires = SystemClock.elapsedRealtime() + 5000;
                player.setTime(currentPositionMs, false);
            }
        }
        persist(true);
        notifyChanged();
    }
    /** Compatibility import, never an overwrite of an existing service checkpoint. */
    public void restoreHistory(Map<String, Integer> progress, Set<String> watched) {
        if (restoredState || !ledger.progress.isEmpty() || !ledger.watched.isEmpty()) return;
        if (progress != null) ledger.progress.putAll(progress);
        if (watched != null) ledger.watched.addAll(watched);
        restoredState = true;
        persist(true);
        notifyChanged();
    }

    public List<String> getAudioTrackLabels() { return trackLabels(false); }
    /** Subtitle ordinal 0 means Off. Audio ordinals exclude the disabled track. */
    public List<String> getSubtitleTrackLabels() { return trackLabels(true); }
    private List<MediaPlayer.TrackDescription> tracks(boolean subtitles) {
        ArrayList<MediaPlayer.TrackDescription> result = new ArrayList<>();
        if (player == null || !prepared) return result;
        MediaPlayer.TrackDescription[] available = subtitles ? player.getSpuTracks() : player.getAudioTracks();
        if (available != null) for (MediaPlayer.TrackDescription track : available) if (track.id >= 0) result.add(track);
        return result;
    }
    private List<String> trackLabels(boolean subtitles) {
        ArrayList<String> labels = new ArrayList<>();
        if (subtitles) labels.add("Off");
        for (MediaPlayer.TrackDescription track : tracks(subtitles)) {
            String name = track.name;
            labels.add(name == null || name.trim().isEmpty() ? (subtitles ? "Subtitle " : "Audio ")
                    + (labels.size() + (subtitles ? 0 : 1)) : name);
        }
        return labels;
    }
    public int getSelectedAudioTrackIndex() {
        if (player == null || !prepared) return -1;
        List<MediaPlayer.TrackDescription> tracks = tracks(false);
        int selected = player.getAudioTrack();
        for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).id == selected) return i;
        return -1;
    }
    public void selectAudioTrack(int ordinal) {
        List<MediaPlayer.TrackDescription> tracks = tracks(false);
        if (ordinal < 0 || ordinal >= tracks.size()) return;
        if (player.setAudioTrack(tracks.get(ordinal).id)) {
            preferredAudioTrackIndex = ordinal;
            preferredAudioLanguage = getAudioTrackLanguage(ordinal);
            rememberAudioPreference();
            notifyChanged();
        }
    }
    public int getSelectedSubtitleTrackIndex() {
        if (pendingSubtitleOrdinal >= 0) return pendingSubtitleOrdinal;
        if (player == null || !prepared) return 0;
        int selected = player.getSpuTrack();
        List<MediaPlayer.TrackDescription> tracks = tracks(true);
        for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).id == selected) return i + 1;
        return 0;
    }
    public void selectSubtitleTrack(int ordinal) {
        if (player == null || !prepared || ordinal < 0) return;
        List<MediaPlayer.TrackDescription> tracks = tracks(true);
        if (ordinal > tracks.size()) return;
        pendingSubtitleOrdinal = ordinal;
        if (ordinal > 0 && textRendererDeferred) {
            if (!fontCacheReady) startFontCacheWarmup();
            else if (playRequested) {
                snapshotCurrentProgress();
                loadCurrent(getCurrentPositionMs());
                return;
            }
        }
        player.setSpuTrack(ordinal == 0 ? -1 : tracks.get(ordinal - 1).id);
        notifyChanged();
    }
    private void applyPendingSubtitleTrack() {
        if (player == null || !prepared || pendingSubtitleOrdinal < 0) return;
        List<MediaPlayer.TrackDescription> available = tracks(true);
        if (pendingSubtitleOrdinal > available.size()) return;
        int id = pendingSubtitleOrdinal == 0 ? -1 : available.get(pendingSubtitleOrdinal - 1).id;
        if (player.getSpuTrack() != id) player.setSpuTrack(id);
    }
    private void ensureLibraries() {
        if (libVLC == null) {
            libVLC = new LibVLC(getApplicationContext(), new ArrayList<>(Arrays.asList("--audio-time-stretch", "--no-video-title-show")));
        }
        if (!fontCacheReady && startupLibVLC == null) {
            startupLibVLC = new LibVLC(getApplicationContext(), new ArrayList<>(Arrays.asList(
                    "--audio-time-stretch", "--no-video-title-show", "--text-renderer=none")));
        }
        startFontCacheWarmup();
    }
    private void startFontCacheWarmup() {
        if (destroyed || fontCacheReady || fontCacheWarming || libVLC == null) return;
        fontCacheWarming = true;
        LibVLC library = libVLC;
        library.retain(); // Keep the instance alive if service teardown occurs during the blocking scan.
        fontCacheWorker.execute(() -> {
            long start = SystemClock.elapsedRealtime();
            boolean ready = false;
            try { ready = library.buildFontCache(); }
            catch (RuntimeException | LinkageError error) { Log.w("UltimatePlayback", "Font cache preparation failed", error); }
            finally { library.release(); }
            boolean result = ready;
            Log.i("UltimatePlayback", "Font cache ready=" + result + " elapsedMs=" + (SystemClock.elapsedRealtime() - start));
            main.post(() -> {
                if (destroyed) return;
                fontCacheWarming = false;
                fontCacheReady = result;
                resumeTextRenderingIfNeeded();
                notifyChanged();
            });
        });
    }
    private void resumeTextRenderingIfNeeded() {
        if (!fontCacheReady || !textRendererDeferred || player == null || !prepared) return;
        int subtitle = getSelectedSubtitleTrackIndex();
        if (subtitle <= 0) return;
        pendingSubtitleOrdinal = subtitle;
        if (playRequested) {
            snapshotCurrentProgress();
            loadCurrent(getCurrentPositionMs());
        }
    }
    public void restorePreferredAudioTrack(int ordinal, String language) {
        preferredAudioTrackIndex = ordinal;
        preferredAudioLanguage = normalizeLanguage(language);
        applyPreferredAudioTrack();
        rememberAudioPreference();
    }
    public void clearPreferredAudioTrack() {
        preferredAudioTrackIndex = -1;
        preferredAudioLanguage = "";
        rememberAudioPreference();
        notifyChanged();
    }
    private void rememberAudioPreference() {
        audioOrdinals.put(queueName, preferredAudioTrackIndex);
        audioLanguages.put(queueName, preferredAudioLanguage);
        persist(true);
    }
    public int getPreferredAudioTrackIndex() { return preferredAudioTrackIndex; }
    public String getPreferredAudioLanguage() { return preferredAudioLanguage; }
    public String getAudioTrackLanguage(int ordinal) {
        List<MediaPlayer.TrackDescription> tracks = tracks(false);
        if (ordinal < 0 || ordinal >= tracks.size()) return "";
        IMedia media = player.getMedia();
        if (media == null) return "";
        try {
            int id = tracks.get(ordinal).id;
            for (int i = 0; i < media.getTrackCount(); i++) {
                IMedia.Track track = media.getTrack(i);
                if (track != null && track.type == IMedia.Track.Type.Audio && track.id == id) return normalizeLanguage(track.language);
            }
        } finally { media.release(); }
        return "";
    }
    private void applyPreferredAudioTrack() {
        if (player == null || !prepared) return;
        List<MediaPlayer.TrackDescription> tracks = tracks(false);
        int ordinal = preferredAudioTrackIndex;
        if (!preferredAudioLanguage.isEmpty()) {
            for (int i = 0; i < tracks.size(); i++) {
                if (preferredAudioLanguage.equals(getAudioTrackLanguage(i))) { ordinal = i; break; }
            }
        }
        if (ordinal >= 0 && ordinal < tracks.size() && player.getAudioTrack() != tracks.get(ordinal).id) player.setAudioTrack(tracks.get(ordinal).id);
    }
    private static String normalizeLanguage(String language) {
        if (language == null) return "";
        String value = language.trim().toLowerCase(Locale.US);
        if (value.isEmpty() || "und".equals(value)) return "";
        if (value.length() == 2) {
            try { return new Locale(value).getISO3Language(); } catch (java.util.MissingResourceException ignored) { }
        }
        return value;
    }
    private void refreshDurationAndTracks() {
        if (player == null) return;
        long length = player.getLength();
        if (length > 0) durationMs = toInt(length);
        IMedia media = player.getMedia();
        if (media != null) try {
            for (int i = 0; i < media.getTrackCount(); i++) {
                IMedia.Track raw = media.getTrack(i);
                if (!(raw instanceof IMedia.VideoTrack)) continue;
                IMedia.VideoTrack video = (IMedia.VideoTrack) raw;
                int width = video.sarNum > 0 && video.sarDen > 0 ? toInt((long) video.width * video.sarNum / video.sarDen) : video.width;
                boolean rotated = video.orientation >= IMedia.VideoTrack.Orientation.LeftTop;
                videoWidth = rotated ? video.height : width;
                videoHeight = rotated ? width : video.height;
                break;
            }
        } finally { media.release(); }
        VideoItem item = getCurrentItem();
        if (item != null && durationMs > 0 && item.durationMs != durationMs) {
            VideoItem updated = new VideoItem(item.uri, item.name, durationMs, item.sizeBytes);
            playlist.set(currentIndex, updated);
            catalog.put(updated.key(), updated);
        }
        updateMetadata();
    }
    private void updateVideoEnabled() {
        if (player != null) player.setVideoTrackEnabled(outputSurface != null && outputSurface.isValid() && player.getVLCVout().areViewsAttached());
    }
    public void setSleepTimerMinutes(int minutes) {
        sleepDeadlineElapsed = minutes <= 0 ? 0 : SystemClock.elapsedRealtime() + Math.min(minutes, 1440) * 60000L;
        if (sleepDeadlineElapsed > 0) scheduleTicker();
        persist(true);
        notifyChanged();
    }
    public int getSleepTimerMinutesRemaining() {
        return sleepDeadlineElapsed <= 0 ? 0 : (int) Math.max(0, (sleepDeadlineElapsed - SystemClock.elapsedRealtime() + 59999) / 60000);
    }
    private void setupAudioFocus() {
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setOnAudioFocusChangeListener(this::handleAudioFocusChange, main).build();
    }
    private boolean beginPlaybackFocus() {
        if (!serviceStarted) {
            try { startService(new Intent(this, BackgroundPlayerService.class)); serviceStarted = true; }
            catch (IllegalStateException | SecurityException error) {
                lastError = "Playback service could not start: " + errorText(error);
                playRequested = loading = false;
                updateWakeLock();
                notifyChanged();
                return false;
            }
        }
        // Android 15 requires foreground ownership before requesting focus in the background.
        if (!enterForeground()) { playRequested = loading = false; updateWakeLock(); notifyChanged(); return false; }
        if (!focusHeld) focusHeld = audioManager == null || audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (!focusHeld) {
            lastError = "Audio focus is unavailable. Pause the other audio app and try again.";
            playRequested = loading = false;
            refreshNotification();
            updateWakeLock();
            notifyChanged();
        }
        return focusHeld;
    }
    private void abandonAudioFocus() {
        if (focusHeld && audioManager != null) audioManager.abandonAudioFocusRequest(audioFocusRequest);
        focusHeld = false;
    }
    private void handleAudioFocusChange(int change) {
        if (destroyed) return;
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            focusHeld = true;
            if (player != null) player.setVolume(100);
            if (resumeAfterFocusGain) { resumeAfterFocusGain = false; play(); }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (player != null) player.setVolume(25);
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            resumeAfterFocusGain = playRequested;
            pauseInternal(false);
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            resumeAfterFocusGain = false;
            pauseInternal(true);
        }
    }
    private void updateWakeLock() {
        if (wakeLock == null) return;
        boolean hold = !destroyed && playRequested && (loading || prepared);
        if (hold && !wakeLock.isHeld()) wakeLock.acquire();
        else if (!hold && wakeLock.isHeld()) wakeLock.release();
    }
    private void scheduleTicker() {
        if (tickerScheduled || destroyed) return;
        tickerScheduled = true;
        main.postDelayed(ticker, 1000);
    }
    private void setupMediaSession() {
        mediaSession = new MediaSession(this, "Ultimate Player");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long positionMs) { seekToLong(positionMs); }
            @Override public void onSetPlaybackSpeed(float speed) { setPlaybackSpeed(speed); }
            @Override public void onStop() { stopPlayback(); }
        }, main);
        mediaSession.setActive(true);
        updateMetadata();
        updatePlaybackState();
    }
    private void updatePlaybackState() {
        if (mediaSession == null || destroyed) return;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;
        if (validIndex(currentIndex + 1)) actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
        if (validIndex(currentIndex - 1)) actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        if (Build.VERSION.SDK_INT >= 31) actions |= PlaybackState.ACTION_SET_PLAYBACK_SPEED;
        int state = getCurrentItem() == null ? PlaybackState.STATE_STOPPED : loading && playRequested ? PlaybackState.STATE_BUFFERING :
                isPlaying() ? PlaybackState.STATE_PLAYING : !lastError.isEmpty() && !prepared ? PlaybackState.STATE_ERROR : PlaybackState.STATE_PAUSED;
        PlaybackState.Builder builder = new PlaybackState.Builder().setActions(actions)
                .setState(state, getCurrentPositionMs(), state == PlaybackState.STATE_PLAYING ? playbackSpeed : 0f, SystemClock.elapsedRealtime());
        if (state == PlaybackState.STATE_ERROR) builder.setErrorMessage(lastError);
        mediaSession.setPlaybackState(builder.build());
    }
    private void updateMetadata() {
        if (mediaSession == null || destroyed) return;
        VideoItem item = getCurrentItem();
        if (item == null) { mediaSession.setMetadata(null); return; }
        mediaSession.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, item.name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, queueName + (ledger.previewing ? " - Preview" : ""))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs).build());
    }
    private boolean enterForeground() {
        try { startForeground(NOTIFICATION_ID, buildNotification()); foreground = true; return true; }
        catch (RuntimeException error) { lastError = "Background playback unavailable: " + errorText(error); return false; }
    }
    private void refreshNotification() {
        if (destroyed) return;
        if (playRequested) { if (!foreground) enterForeground(); }
        else if (foreground) { stopForeground(false); foreground = false; }
        if (!serviceStarted && !foreground) return;
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, buildNotification()); }
        catch (SecurityException ignored) { }
    }
    private Notification buildNotification() {
        VideoItem item = getCurrentItem();
        String status = loading && playRequested ? "Loading" : isPlaying() ? "Playing" : "Paused";
        if (!lastError.isEmpty()) status = lastError;
        else if (ledger.previewing) status = "Preview - " + status;
        PendingIntent content = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_stat_play)
                .setContentTitle(item == null ? "Ultimate Player" : item.name).setContentText(status)
                .setSubText(queueName).setContentIntent(content).setOngoing(playRequested).setShowWhen(false)
                .setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_TRANSPORT).setDeleteIntent(serviceIntent(ACTION_STOP, 4))
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", serviceIntent(ACTION_PREVIOUS, 1)).build())
                .addAction(new Notification.Action.Builder(playRequested ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playRequested ? "Pause" : "Play", serviceIntent(ACTION_PLAY_PAUSE, 2)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", serviceIntent(ACTION_NEXT, 3)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(0, 1, 2)).build();
    }
    private PendingIntent serviceIntent(String action, int code) {
        return PendingIntent.getService(this, code, new Intent(this, BackgroundPlayerService.class).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private void createNotificationChannel() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(
                new NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW));
    }
    private void notifyChanged() {
        updatePlaybackState();
        if (listener != null && !destroyed) listener.onPlayerChanged();
    }
    private void persist(boolean durable) {
        if (preferences == null || destroyed) return;
        lastPersistAt = SystemClock.elapsedRealtime();
        try {
            JSONObject state = new JSONObject().put("version", 1).put("queueName", queueName)
                    .put("queue", encodeItems(playlist)).put("catalog", encodeItems(new ArrayList<>(catalog.values())))
                    .put("currentKey", currentKey()).put("position", currentPositionMs)
                    .put("progress", new JSONObject(ledger.progress)).put("watched", new JSONArray(ledger.watched))
                    .put("anchors", new JSONObject(ledger.seriesAnchors)).put("completedAnchors", new JSONObject(ledger.completedAnchors))
                    .put("preview", ledger.previewing).put("continuationKey", ledger.continuationKey)
                    .put("continuationPosition", ledger.continuationPositionMs).put("intro", startOffsetMs)
                    .put("autoplay", autoplayNext).put("speed", playbackSpeed)
                    .put("audioOrdinals", new JSONObject(audioOrdinals)).put("audioLanguages", new JSONObject(audioLanguages))
                    .put("sleepDeadline", sleepDeadlineElapsed <= 0 ? 0 : System.currentTimeMillis()
                            + Math.max(0, sleepDeadlineElapsed - SystemClock.elapsedRealtime()));
            // Ordered worker commits prevent an older periodic save overwriting a transition.
            Future<?> write = stateWriter.submit(() -> {
                if (!preferences.edit().putString("state", state.toString()).commit()) {
                    main.post(() -> { if (!destroyed) { lastError = "Could not save playback state."; notifyChanged(); } });
                }
            });
            // Bound UI wait: a slow filesystem continues committing on the worker, never freezes controls.
            if (durable) {
                try { write.get(100, TimeUnit.MILLISECONDS); }
                catch (java.util.concurrent.TimeoutException ignored) { }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            lastError = "Playback state save interrupted.";
        } catch (Exception error) { lastError = "Could not checkpoint playback: " + errorText(error); }
    }
    private void restoreState() {
        String saved = preferences.getString("state", null);
        if (saved == null) return;
        try {
            JSONObject state = new JSONObject(saved);
            playlist.addAll(decodeItems(state.optJSONArray("queue")));
            for (VideoItem item : decodeItems(state.optJSONArray("catalog"))) catalog.put(item.key(), item);
            for (VideoItem item : playlist) catalog.put(item.key(), item);
            queueName = state.optString("queueName", "Playlist");
            decodeIntMap(state.optJSONObject("progress"), ledger.progress);
            JSONArray watched = state.optJSONArray("watched");
            if (watched != null) for (int i = 0; i < watched.length(); i++) ledger.watched.add(watched.getString(i));
            decodeStringMap(state.optJSONObject("anchors"), ledger.seriesAnchors);
            decodeStringMap(state.optJSONObject("completedAnchors"), ledger.completedAnchors);
            decodeIntMap(state.optJSONObject("audioOrdinals"), audioOrdinals);
            decodeStringMap(state.optJSONObject("audioLanguages"), audioLanguages);
            preferredAudioTrackIndex = audioOrdinals.getOrDefault(queueName, -1);
            preferredAudioLanguage = audioLanguages.getOrDefault(queueName, "");
            ledger.previewing = state.optBoolean("preview");
            ledger.continuationKey = state.optString("continuationKey", "");
            ledger.continuationPositionMs = Math.max(0, state.optInt("continuationPosition"));
            currentIndex = indexOfKey(state.optString("currentKey", ""));
            if (currentIndex < 0 && !playlist.isEmpty()) currentIndex = 0;
            currentPositionMs = Math.max(0, state.optInt("position"));
            durationMs = getCurrentItem() == null ? 0 : toInt(getCurrentItem().durationMs);
            startOffsetMs = Math.max(0, state.optInt("intro", 180000));
            autoplayNext = state.optBoolean("autoplay", true);
            playbackSpeed = Math.max(0.5f, Math.min(2f, (float) state.optDouble("speed", 1)));
            long sleepRemaining = state.optLong("sleepDeadline") - System.currentTimeMillis();
            sleepDeadlineElapsed = sleepRemaining > 0 ? SystemClock.elapsedRealtime() + Math.min(86400000, sleepRemaining) : 0;
            restoredState = true;
        } catch (JSONException | RuntimeException error) { lastError = "Saved state could not be fully restored: " + errorText(error); }
    }
    private static JSONArray encodeItems(List<VideoItem> items) throws JSONException {
        JSONArray array = new JSONArray();
        for (VideoItem item : items) array.put(new JSONObject().put("uri", item.key()).put("name", item.name).put("duration", item.durationMs).put("size", item.sizeBytes));
        return array;
    }
    private static List<VideoItem> decodeItems(JSONArray array) {
        ArrayList<VideoItem> items = new ArrayList<>();
        if (array == null) return items;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null || item.optString("uri").isEmpty()) continue;
            VideoItem video = new VideoItem(Uri.parse(item.optString("uri")), item.optString("name", "Video"), Math.max(0, item.optLong("duration")), Math.max(0, item.optLong("size")));
            if (!items.contains(video)) items.add(video);
        }
        return items;
    }
    private static void decodeStringMap(JSONObject object, Map<String, String> target) {
        if (object == null) return;
        for (Iterator<String> keys = object.keys(); keys.hasNext();) { String key = keys.next(); target.put(key, object.optString(key)); }
    }
    private static void decodeIntMap(JSONObject object, Map<String, Integer> target) {
        if (object == null) return;
        for (Iterator<String> keys = object.keys(); keys.hasNext();) { String key = keys.next(); target.put(key, object.optInt(key)); }
    }
    private boolean validIndex(int index) { return index >= 0 && index < playlist.size(); }
    private String currentKey() { return getCurrentItem() == null ? "" : getCurrentItem().key(); }
    private int indexOfKey(String key) {
        for (int i = 0; i < playlist.size(); i++) if (playlist.get(i).key().equals(key)) return i;
        return -1;
    }
    private List<String> queueKeys() {
        ArrayList<String> keys = new ArrayList<>();
        for (VideoItem item : playlist) keys.add(item.key());
        return keys;
    }
    private static int toInt(long value) { return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value)); }
    private static String errorText(Throwable error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
}
