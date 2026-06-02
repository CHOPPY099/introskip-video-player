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
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.Surface;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    private MediaPlayer player;
    private MediaSession mediaSession;
    private Surface outputSurface;
    private PlayerListener listener;
    private int currentIndex = -1;
    private int startOffsetMs = 180000;
    private boolean autoplayNext = true;
    private boolean playWhenPrepared = false;
    private boolean prepared = false;

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

    public void removeFromPlaylist(int index) {
        if (index < 0 || index >= playlist.size()) return;
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
        playlist.clear();
        currentIndex = -1;
        resetPlayer();
        notifyChanged();
    }

    public void playIndex(int index) {
        if (index < 0 || index >= playlist.size()) return;
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
        player.start();
        enterForeground();
        updatePlaybackState();
        notifyChanged();
    }

    public void pause() {
        if (player != null && prepared && player.isPlaying()) {
            player.pause();
        }
        updatePlaybackState();
        updateNotification();
        notifyChanged();
    }

    public void next() {
        if (playlist.isEmpty()) return;
        currentIndex = currentIndex + 1 < playlist.size() ? currentIndex + 1 : 0;
        prepareCurrent(true);
    }

    public void previous() {
        if (playlist.isEmpty()) return;
        currentIndex = currentIndex > 0 ? currentIndex - 1 : playlist.size() - 1;
        prepareCurrent(true);
    }

    public void setStartOffsetMs(int startOffsetMs) {
        this.startOffsetMs = Math.max(0, startOffsetMs);
        if (prepared && player != null) {
            int duration = player.getDuration();
            if (duration > this.startOffsetMs + 500) {
                player.seekTo(this.startOffsetMs);
            }
        }
        notifyChanged();
    }

    public void setAutoplayNext(boolean autoplayNext) {
        this.autoplayNext = autoplayNext;
        notifyChanged();
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

    private void ensurePlayer() {
        if (player != null) return;
        player = new MediaPlayer();
        player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        player.setOnPreparedListener(mp -> {
            prepared = true;
            int duration = mp.getDuration();
            if (startOffsetMs > 0 && duration > startOffsetMs + 500) {
                mp.seekTo(startOffsetMs);
            }
            if (playWhenPrepared) {
                mp.start();
                enterForeground();
            }
            updatePlaybackState();
            notifyChanged();
        });
        player.setOnCompletionListener(mp -> {
            markCurrentWatched();
            prepared = false;
            if (autoplayNext && playlist.size() > 1) {
                next();
            } else {
                updatePlaybackState();
                updateNotification();
                notifyChanged();
            }
        });
        player.setOnErrorListener((mp, what, extra) -> {
            prepared = false;
            notifyChanged();
            return true;
        });
    }

    private void prepareCurrent(boolean shouldPlay) {
        if (currentIndex < 0 || currentIndex >= playlist.size()) return;
        ensurePlayer();
        prepared = false;
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
            player.prepareAsync();
            updateNotification();
            updatePlaybackState();
            notifyChanged();
        } catch (IOException | IllegalArgumentException | SecurityException error) {
            prepared = false;
            notifyChanged();
        }
    }

    private void resetPlayer() {
        if (player != null) {
            player.reset();
        }
        prepared = false;
        playWhenPrepared = false;
        stopForeground(true);
        updatePlaybackState();
    }

    private void stopPlayback() {
        resetPlayer();
        stopSelf();
    }

    private void markCurrentWatched() {
        VideoItem current = getCurrentItem();
        if (current != null) {
            watchedKeys.add(current.key());
        }
    }

    private void setupMediaSession() {
        mediaSession = new MediaSession(this, "IntroSkipPlayer");
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
                | PlaybackState.ACTION_STOP;
        int state = isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, prepared && player != null ? player.getCurrentPosition() : 0, isPlaying() ? 1f : 0f)
                .build());
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
                .putLong(MediaMetadata.METADATA_KEY_DURATION, current.durationMs)
                .build());
    }

    private void enterForeground() {
        startForeground(NOTIFICATION_ID, buildNotification());
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
