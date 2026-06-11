package com.introskip.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Surface;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class BackgroundPlayerService extends Service {
    private static final String CHANNEL_ID = "playback";
    private static final int NOTIFICATION_ID = 42;
    private static final String ACTION_PLAY_PAUSE = "com.introskip.player.PLAY_PAUSE";
    private static final String ACTION_NEXT = "com.introskip.player.NEXT";
    private static final String ACTION_PREVIOUS = "com.introskip.player.PREVIOUS";
    private static final String ACTION_STOP = "com.introskip.player.STOP";

    private final IBinder binder = new LocalBinder();
    private final ArrayList<VideoItem> playlist = new ArrayList<>();
    private final Set<String> watchedKeys = new HashSet<>();
    private final Set<String> startedKeys = new HashSet<>();
    private final Map<String, Integer> progressByKey = new HashMap<>();
    private final Handler mediaSessionHandler = new Handler(Looper.getMainLooper());
    private final Runnable clearPendingSeekRunnable = new Runnable() {
        @Override
        public void run() {
            pendingSeekPositionMs = -1;
            updatePlaybackState();
            notifyChanged();
        }
    };
    private final Runnable playbackStateTicker = new Runnable() {
        @Override
        public void run() {
            if (isPlaying()) {
                updatePlaybackState();
                mediaSessionHandler.postDelayed(this, 1000);
            }
        }
    };
    private MediaPlayer player;
    private MediaSession mediaSession;
    private Surface outputSurface;
    private PlayerListener listener;
    private int currentIndex = -1;
    private int startOffsetMs = 180000;
    private int pendingSeekPositionMs = -1;
    private float playbackSpeed = 1f;
    private boolean autoplayNext = true;
    private boolean playWhenPrepared = false;
    private boolean prepared = false;
    private boolean forceStartOffsetOnPrepare = false;

    public interface PlayerListener {
        void onPlayerChanged();
    }

    public class LocalBinder extends Binder {
        BackgroundPlayerService getService() {
            return BackgroundPlayerService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        setupMediaSession();
        ensurePlayer();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (ACTION_PLAY_PAUSE.equals(action)) {
                if (isPlaying()) pause(); else play();
            } else if (ACTION_NEXT.equals(action)) {
                next();
            } else if (ACTION_PREVIOUS.equals(action)) {
                previous();
            } else if (ACTION_STOP.equals(action)) {
                stopPlayback();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopPlaybackStateTicker();
        mediaSessionHandler.removeCallbacks(clearPendingSeekRunnable);
        if (player != null) {
            player.release();
            player = null;
        }
        if (mediaSession != null) {
            mediaSession.release();
        }
        super.onDestroy();
    }

    public void setListener(PlayerListener listener) {
        this.listener = listener;
        notifyChanged();
    }

    public void setOutputSurface(Surface surface) {
        outputSurface = surface;
        if (player != null) {
            player.setSurface(outputSurface);
        }
    }

    public void clearOutputSurface(Surface surface) {
        if (outputSurface == surface) {
            outputSurface = null;
            if (player != null) {
                player.setSurface(null);
            }
        }
    }

    public void addToPlaylist(VideoItem item) {
        if (!playlist.contains(item)) {
            playlist.add(item);
            if (currentIndex == -1) {
                currentIndex = 0;
                prepareCurrent(false);
            }
            notifyChanged();
        }
    }

    public void replacePlaylist(List<VideoItem> items) {
        snapshotCurrentProgress();
        VideoItem current = getCurrentItem();
        boolean wasPlaying = isPlaying();
        String currentKey = current == null ? null : current.key();
        playlist.clear();
        for (VideoItem item : items) {
            if (!playlist.contains(item)) {
                playlist.add(item);
            }
        }
        if (playlist.isEmpty()) {
            currentIndex = -1;
            resetPlayer();
        } else {
            currentIndex = 0;
            if (currentKey != null) {
                for (int i = 0; i < playlist.size(); i++) {
                    if (playlist.get(i).key().equals(currentKey)) {
                        currentIndex = i;
                        break;
                    }
                }
            }
            prepareCurrent(wasPlaying);
        }
        notifyChanged();
    }

    public void removeFromPlaylist(int index) {
        if (index < 0 || index >= playlist.size()) return;
        snapshotCurrentProgress();
        boolean removingCurrent = index == currentIndex;
        playlist.remove(index);
        if (playlist.isEmpty()) {
            currentIndex = -1;
            resetPlayer();
        } else if (removingCurrent) {
            currentIndex = Math.min(index, playlist.size() - 1);
            prepareCurrent(false);
        } else if (index < currentIndex) {
            currentIndex--;
        }
        notifyChanged();
    }

    public void clearPlaylist() {
        snapshotCurrentProgress();
        playlist.clear();
        currentIndex = -1;
        resetPlayer();
        notifyChanged();
    }

    public void playIndex(int index) {
        if (index < 0 || index >= playlist.size()) return;
        snapshotCurrentProgress();
        currentIndex = index;
        prepareCurrent(true);
    }

    public void play() {
        if (playlist.isEmpty()) return;
        if (currentIndex == -1) currentIndex = 0;
        if (!prepared) {
            prepareCurrent(true);
            return;
        }
        seekToStartPositionIfNeeded(player);
        markCurrentStarted();
        player.start();
        enterForeground();
        updatePlaybackState();
        schedulePlaybackStateTicker();
        notifyChanged();
    }

    public void pause() {
        if (player != null && prepared && player.isPlaying()) {
            player.pause();
        }
        snapshotCurrentProgress();
        updatePlaybackState();
        stopPlaybackStateTicker();
        updateNotification();
        notifyChanged();
    }

    public void next() {
        if (playlist.isEmpty()) return;
        snapshotCurrentProgress();
        currentIndex = currentIndex + 1 < playlist.size() ? currentIndex + 1 : 0;
        prepareCurrent(true, true);
    }

    public void previous() {
        if (playlist.isEmpty()) return;
        snapshotCurrentProgress();
        currentIndex = currentIndex > 0 ? currentIndex - 1 : playlist.size() - 1;
        prepareCurrent(true, true);
    }

    public void setStartOffsetMs(int startOffsetMs) {
        this.startOffsetMs = Math.max(0, startOffsetMs);
        notifyChanged();
    }

    public void setAutoplayNext(boolean autoplayNext) {
        this.autoplayNext = autoplayNext;
        notifyChanged();
    }

    public void setPlaybackSpeed(float speed) {
        playbackSpeed = Math.max(0.5f, Math.min(2f, speed));
        applyPlaybackSpeed();
        notifyChanged();
    }

    public float getPlaybackSpeed() {
        return playbackSpeed;
    }

    public List<String> getAudioTrackLabels() {
        ArrayList<String> labels = new ArrayList<>();
        if (player == null || !prepared) return labels;
        try {
            MediaPlayer.TrackInfo[] tracks = player.getTrackInfo();
            int audioCount = 0;
            for (MediaPlayer.TrackInfo track : tracks) {
                if (track.getTrackType() != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) continue;
                audioCount++;
                String language = track.getLanguage();
                if (language == null || language.trim().isEmpty() || "und".equalsIgnoreCase(language)) {
                    labels.add("Audio " + audioCount);
                } else {
                    labels.add("Audio " + audioCount + " - " + language.toUpperCase(Locale.US));
                }
            }
        } catch (IllegalStateException ignored) {
        }
        return labels;
    }

    public void selectAudioTrack(int audioTrackIndex) {
        if (player == null || !prepared || audioTrackIndex < 0) return;
        try {
            MediaPlayer.TrackInfo[] tracks = player.getTrackInfo();
            int audioCount = 0;
            for (int i = 0; i < tracks.length; i++) {
                if (tracks[i].getTrackType() != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) continue;
                if (audioCount == audioTrackIndex) {
                    player.selectTrack(i);
                    notifyChanged();
                    return;
                }
                audioCount++;
            }
        } catch (IllegalStateException | IllegalArgumentException ignored) {
        }
    }

    public void seekBy(int deltaMs) {
        if (player == null || !prepared) return;
        int duration = Math.max(0, player.getDuration());
        int target = Math.max(0, Math.min(duration, player.getCurrentPosition() + deltaMs));
        seekTo(target);
    }

    public void seekTo(int positionMs) {
        if (player == null || !prepared) return;
        int duration = Math.max(0, player.getDuration());
        seekToResolved(Math.max(0, Math.min(duration, positionMs)));
    }

    private void seekToFromMediaSession(long positionMs) {
        if (player == null || !prepared) return;
        int duration = Math.max(0, player.getDuration());
        int requested = (int) Math.min(Integer.MAX_VALUE, Math.max(0, positionMs));
        int target = Math.max(0, Math.min(duration, normalizeExternalSeekPosition(requested, duration)));
        seekToResolved(target);
    }

    private void seekToResolved(int target) {
        pendingSeekPositionMs = target;
        mediaSessionHandler.removeCallbacks(clearPendingSeekRunnable);
        VideoItem current = getCurrentItem();
        if (current != null) {
            startedKeys.add(current.key());
            progressByKey.put(current.key(), target);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            player.seekTo(target, MediaPlayer.SEEK_CLOSEST);
        } else {
            player.seekTo(target);
        }
        updatePlaybackState();
        notifyChanged();
    }

    private int normalizeExternalSeekPosition(int requestedPosition, int duration) {
        if (duration > 60000 && requestedPosition > 0 && requestedPosition <= duration / 1000) {
            return requestedPosition * 1000;
        }
        return requestedPosition;
    }

    public boolean isAutoplayNext() {
        return autoplayNext;
    }

    public boolean isPlaying() {
        return player != null && prepared && player.isPlaying();
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public int getStartOffsetMs() {
        return startOffsetMs;
    }

    public List<VideoItem> getPlaylist() {
        return new ArrayList<>(playlist);
    }

    public VideoItem getCurrentItem() {
        if (currentIndex < 0 || currentIndex >= playlist.size()) return null;
        return playlist.get(currentIndex);
    }

    public boolean isWatched(VideoItem item) {
        return item != null && watchedKeys.contains(item.key());
    }

    public void clearWatched() {
        watchedKeys.clear();
        notifyChanged();
    }

    public void clearHistory(VideoItem item) {
        if (item == null) return;
        String key = item.key();
        watchedKeys.remove(key);
        progressByKey.remove(key);
        startedKeys.remove(key);
        VideoItem current = getCurrentItem();
        if (current != null && current.key().equals(key) && player != null && prepared) {
            seekToStartPositionIfNeeded(player);
        }
        notifyChanged();
    }

    public int getCurrentPositionMs() {
        if (player == null || !prepared) return 0;
        return Math.max(0, player.getCurrentPosition());
    }

    public int getDurationMs() {
        if (player == null || !prepared) {
            VideoItem current = getCurrentItem();
            return current == null ? 0 : (int) Math.min(Integer.MAX_VALUE, current.durationMs);
        }
        return Math.max(0, player.getDuration());
    }

    public int getVideoWidth() {
        return player == null ? 0 : Math.max(0, player.getVideoWidth());
    }

    public int getVideoHeight() {
        return player == null ? 0 : Math.max(0, player.getVideoHeight());
    }

    public int getSavedProgressMs(VideoItem item) {
        if (item == null) return 0;
        Integer value = progressByKey.get(item.key());
        return value == null ? 0 : value;
    }

    public boolean hasSavedProgress(VideoItem item) {
        return item != null && progressByKey.containsKey(item.key()) && getSavedProgressMs(item) > 0;
    }

    public Map<String, Integer> getProgressSnapshot() {
        snapshotCurrentProgress();
        return new HashMap<>(progressByKey);
    }

    public Set<String> getWatchedSnapshot() {
        return new HashSet<>(watchedKeys);
    }

    public void restoreHistory(Map<String, Integer> progress, Set<String> watched) {
        progressByKey.clear();
        progressByKey.putAll(progress);
        watchedKeys.clear();
        watchedKeys.addAll(watched);
        notifyChanged();
    }

    private void ensurePlayer() {
        if (player != null) return;
        player = new MediaPlayer();
        player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        player.setOnPreparedListener(mp -> {
            prepared = true;
            seekToStartPositionIfNeeded(mp, forceStartOffsetOnPrepare);
            forceStartOffsetOnPrepare = false;
            applyPlaybackSpeed();
            updateMetadata();
            if (playWhenPrepared) {
                markCurrentStarted();
                mp.start();
                enterForeground();
                schedulePlaybackStateTicker();
            }
            updatePlaybackState();
            notifyChanged();
        });
        player.setOnVideoSizeChangedListener((mp, width, height) -> notifyChanged());
        player.setOnSeekCompleteListener(mp -> {
            updatePlaybackState();
            notifyChanged();
            mediaSessionHandler.removeCallbacks(clearPendingSeekRunnable);
            mediaSessionHandler.postDelayed(clearPendingSeekRunnable, 3000);
        });
        player.setOnCompletionListener(mp -> {
            markCurrentWatched();
            prepared = false;
            removeCompletedCurrent();
            if (playlist.isEmpty()) {
                currentIndex = -1;
                resetPlayer();
                updateNotification();
                notifyChanged();
            } else {
                if (currentIndex >= playlist.size()) {
                    currentIndex = 0;
                }
                prepareCurrent(autoplayNext, autoplayNext);
            }
        });
        player.setOnErrorListener((mp, what, extra) -> {
            prepared = false;
            notifyChanged();
            return true;
        });
    }

    private void removeCompletedCurrent() {
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            playlist.remove(currentIndex);
        }
    }

    private void prepareCurrent(boolean shouldPlay) {
        prepareCurrent(shouldPlay, false);
    }

    private void prepareCurrent(boolean shouldPlay, boolean forceStartOffset) {
        if (currentIndex < 0 || currentIndex >= playlist.size()) return;
        ensurePlayer();
        prepared = false;
        pendingSeekPositionMs = -1;
        forceStartOffsetOnPrepare = forceStartOffset;
        playWhenPrepared = shouldPlay;
        player.reset();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build());
        player.setSurface(outputSurface);
        try {
            player.setDataSource(this, playlist.get(currentIndex).uri);
            updateMetadata();
            if (shouldPlay) {
                enterForeground();
            }
            player.prepareAsync();
            updateNotification();
            updatePlaybackState();
            notifyChanged();
        } catch (IOException | IllegalArgumentException | SecurityException error) {
            prepared = false;
            forceStartOffsetOnPrepare = false;
            notifyChanged();
        }
    }

    private void resetPlayer() {
        if (player != null) {
            player.reset();
        }
        prepared = false;
        pendingSeekPositionMs = -1;
        playWhenPrepared = false;
        forceStartOffsetOnPrepare = false;
        stopPlaybackStateTicker();
        stopForeground(true);
        updatePlaybackState();
    }

    private void stopPlayback() {
        snapshotCurrentProgress();
        resetPlayer();
        stopSelf();
    }

    private void markCurrentWatched() {
        VideoItem current = getCurrentItem();
        if (current != null) {
            watchedKeys.add(current.key());
            startedKeys.add(current.key());
            progressByKey.put(current.key(), Math.max(0, getDurationMs()));
        }
    }

    private void snapshotCurrentProgress() {
        VideoItem current = getCurrentItem();
        if (current == null || player == null || !prepared) return;
        String key = current.key();
        if (!startedKeys.contains(key) && !progressByKey.containsKey(key) && !watchedKeys.contains(key)) {
            return;
        }
        int duration = Math.max(0, player.getDuration());
        int position = Math.max(0, player.getCurrentPosition());
        if (duration > 0 && position >= duration - 1200) {
            watchedKeys.add(key);
            progressByKey.put(key, duration);
        } else {
            progressByKey.put(key, position);
        }
    }

    private void seekToStartPositionIfNeeded(MediaPlayer mp) {
        seekToStartPositionIfNeeded(mp, false);
    }

    private void seekToStartPositionIfNeeded(MediaPlayer mp, boolean forceStartOffset) {
        int duration = Math.max(0, mp.getDuration());
        VideoItem current = getCurrentItem();
        int savedProgress = current == null ? 0 : getSavedProgressMs(current);
        if (!forceStartOffset && savedProgress > 0 && duration > savedProgress + 500) {
            mp.seekTo(savedProgress);
        } else if (startOffsetMs > 0 && duration > startOffsetMs + 500) {
            mp.seekTo(startOffsetMs);
        }
    }

    private void applyPlaybackSpeed() {
        if (player == null || !prepared || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            PlaybackParams params = player.getPlaybackParams();
            params.setSpeed(playbackSpeed);
            player.setPlaybackParams(params);
        } catch (IllegalStateException | IllegalArgumentException ignored) {
        }
    }

    private void markCurrentStarted() {
        VideoItem current = getCurrentItem();
        if (current != null) {
            startedKeys.add(current.key());
        }
    }

    private void setupMediaSession() {
        mediaSession = new MediaSession(this, "IntroSkipPlayer");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onSkipToNext() {
                next();
            }

            @Override
            public void onSkipToPrevious() {
                previous();
            }

            @Override
            public void onSeekTo(long pos) {
                seekToFromMediaSession(pos);
            }

            @Override
            public void onStop() {
                stopPlayback();
            }
        });
        mediaSession.setActive(true);
        updatePlaybackState();
    }

    private void updatePlaybackState() {
        if (mediaSession == null) return;
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_STOP;
        int state = isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        long position = pendingSeekPositionMs >= 0
                ? pendingSeekPositionMs
                : (prepared && player != null ? player.getCurrentPosition() : 0);
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, position, isPlaying() ? 1f : 0f, System.currentTimeMillis())
                .build());
    }

    private void schedulePlaybackStateTicker() {
        mediaSessionHandler.removeCallbacks(playbackStateTicker);
        if (isPlaying()) {
            mediaSessionHandler.postDelayed(playbackStateTicker, 1000);
        }
    }

    private void stopPlaybackStateTicker() {
        mediaSessionHandler.removeCallbacks(playbackStateTicker);
    }

    private void updateMetadata() {
        if (mediaSession == null) return;
        VideoItem current = getCurrentItem();
        if (current == null) {
            mediaSession.setMetadata(null);
            return;
        }
        mediaSession.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, current.name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "IntroSkip Player")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(0, getDurationMs()))
                .build());
    }

    private void enterForeground() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (SecurityException ignored) {
            // If notification permission is denied, keep playback available inside the app.
        }
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            manager.notify(NOTIFICATION_ID, buildNotification());
        } catch (SecurityException ignored) {
            // Notification permission can be denied on Android 13+; playback can still continue from the app.
        }
    }

    private Notification buildNotification() {
        VideoItem current = getCurrentItem();
        String title = current == null ? "IntroSkip" : current.name;
        String status = isPlaying() ? "Playing in background" : "Paused";
        Intent launchIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Action previous = new Notification.Action.Builder(
                android.R.drawable.ic_media_previous,
                "Previous",
                serviceIntent(ACTION_PREVIOUS, 1)
        ).build();
        Notification.Action playPause = new Notification.Action.Builder(
                isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                isPlaying() ? "Pause" : "Play",
                serviceIntent(ACTION_PLAY_PAUSE, 2)
        ).build();
        Notification.Action next = new Notification.Action.Builder(
                android.R.drawable.ic_media_next,
                "Next",
                serviceIntent(ACTION_NEXT, 3)
        ).build();

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_stat_play)
                .setContentTitle(title)
                .setContentText(status)
                .setContentIntent(contentIntent)
                .setOngoing(isPlaying())
                .setShowWhen(false)
                .addAction(previous)
                .addAction(playPause)
                .addAction(next)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        return builder.build();
    }

    private PendingIntent serviceIntent(String action, int requestCode) {
        Intent intent = new Intent(this, BackgroundPlayerService.class);
        intent.setAction(action);
        return PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Video audio playback controls");
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
    }

    private void notifyChanged() {
        updatePlaybackState();
        if (listener != null) {
            listener.onPlayerChanged();
        }
    }
}
