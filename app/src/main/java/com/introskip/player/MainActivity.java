package com.introskip.player;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends Activity implements BackgroundPlayerService.PlayerListener {
    private static final int REQUEST_PERMISSIONS = 100;
    private static final int REQUEST_PICK_VIDEOS = 101;
    private static final String PREFS = "introskip_store";
    private static final String KEY_PLAYLISTS = "playlists";
    private static final String KEY_CURRENT_PLAYLIST = "current_playlist";
    private static final String KEY_PROGRESS = "progress";
    private static final String KEY_WATCHED = "watched";
    private static final String KEY_SKIP_MINUTES = "skip_minutes";
    private static final String KEY_SKIP_SECONDS = "skip_seconds";

    private final ArrayList<VideoItem> libraryVideos = new ArrayList<>();
    private final LinkedHashMap<String, ArrayList<VideoItem>> savedPlaylists = new LinkedHashMap<>();
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private BackgroundPlayerService playerService;
    private boolean serviceBound = false;
    private boolean videoFullscreen = false;
    private boolean userDraggingVideoProgress = false;
    private boolean activityResumed = false;
    private long lastDragSeekMs = 0;
    private String currentPlaylistName = "Default";
    private FrameLayout videoContainer;
    private TextureView textureView;
    private Surface playbackSurface;
    private GestureDetector videoGestureDetector;
    private LinearLayout videoControlsOverlay;
    private Button overlayPlayPauseButton;
    private SeekBar videoProgressBar;
    private TextView videoProgressText;
    private LinearLayout rootLayout;
    private TextView nowPlayingText;
    private TextView counterText;
    private Button playPauseButton;
    private EditText searchInput;
    private EditText playlistNameInput;
    private EditText minutesInput;
    private EditText secondsInput;
    private CheckBox autoplayCheck;
    private LinearLayout playlistTabs;
    private LinearLayout libraryList;
    private LinearLayout playlistList;
    private LinearLayout historyList;

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            if (playerService != null) {
                saveHistory();
                refreshPlayerHeader();
                refreshPlaylist();
                refreshHistory();
            }
            progressHandler.postDelayed(this, 1000);
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            playerService = ((BackgroundPlayerService.LocalBinder) service).getService();
            attachPlaybackSurface();
            playerService.restoreHistory(loadProgress(), loadWatched());
            loadCurrentPlaylistIntoService();
            playerService.setAutoplayNext(autoplayCheck.isChecked());
            applySkipTime();
            playerService.setListener(MainActivity.this);
            serviceBound = true;
            progressHandler.removeCallbacks(progressTicker);
            progressHandler.postDelayed(progressTicker, 1000);
            refreshAll();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            playerService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadStoredPlaylists();
        Intent serviceIntent = new Intent(this, BackgroundPlayerService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        requestNeededPermissions();
        rootLayout.post(this::applyOrientationFullscreen);
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        applyOrientationFullscreen();
        updateScreenAwakeState();
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        clearScreenAwakeState();
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        rootLayout.post(this::applyOrientationFullscreen);
    }

    @Override
    protected void onDestroy() {
        saveActivePlaylistFromService();
        saveAllPlaylists();
        saveHistory();
        saveSkipTime();
        progressHandler.removeCallbacks(progressTicker);
        clearScreenAwakeState();
        if (playerService != null) {
            if (playbackSurface != null) {
                playerService.clearOutputSurface(playbackSurface);
            }
            playerService.setListener(null);
        }
        releasePlaybackSurface();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        super.onDestroy();
    }

    @Override
    public void onPlayerChanged() {
        saveHistory();
        runOnUiThread(this::refreshPlaylist);
        runOnUiThread(this::refreshPlayerHeader);
        runOnUiThread(this::refreshHistory);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            loadLibraryVideos();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_VIDEOS || resultCode != RESULT_OK || data == null || playerService == null) {
            return;
        }

        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                addPickedVideo(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            addPickedVideo(data.getData());
        }
        saveActivePlaylistFromService();
        refreshAll();
    }

    private void buildUi() {
        int bg = Color.rgb(17, 19, 23);
        int panel = Color.rgb(27, 31, 37);
        int text = Color.rgb(244, 247, 251);
        int muted = Color.rgb(170, 180, 194);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(bg);

        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setPadding(dp(14), dp(18), dp(14), dp(28));
        scrollView.addView(rootLayout, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView title = text("IntroSkip Player", 26, text, true);
        rootLayout.addView(title);
        TextView subtitle = text("Choose downloaded videos, set the intro skip time, then play in the background.", 14, muted, false);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        rootLayout.addView(subtitle);

        videoContainer = new FrameLayout(this);
        videoContainer.setBackgroundColor(Color.BLACK);
        textureView = new TextureView(this);
        LinearLayout.LayoutParams videoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(210)
        );
        rootLayout.addView(videoContainer, videoParams);
        videoContainer.addView(textureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        buildVideoOverlay(text);
        setupVideoGestures();
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                releasePlaybackSurface();
                playbackSurface = new Surface(surfaceTexture);
                attachPlaybackSurface();
                applyVideoAspectTransform();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
                attachPlaybackSurface();
                applyVideoAspectTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                if (playerService != null && playbackSurface != null) {
                    playerService.clearOutputSurface(playbackSurface);
                }
                releasePlaybackSurface();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
            }
        });

        LinearLayout nowPanel = panel();
        nowPanel.setPadding(dp(12), dp(12), dp(12), dp(12));
        nowPanel.setOrientation(LinearLayout.VERTICAL);
        rootLayout.addView(nowPanel);

        nowPlayingText = text("Nothing loaded", 18, text, true);
        counterText = text("0 / 0", 13, muted, false);
        nowPanel.addView(nowPlayingText);
        nowPanel.addView(counterText);

        LinearLayout transport = row();
        Button previousButton = secondaryButton("Previous");
        playPauseButton = primaryButton("Play");
        Button nextButton = secondaryButton("Next");
        previousButton.setOnClickListener(v -> {
            if (playerService != null) playerService.previous();
        });
        playPauseButton.setOnClickListener(v -> togglePlayback());
        nextButton.setOnClickListener(v -> {
            if (playerService != null) playerService.next();
        });
        transport.addView(previousButton, weightParams());
        transport.addView(playPauseButton, weightParams());
        transport.addView(nextButton, weightParams());
        rootLayout.addView(transport);

        LinearLayout options = panel();
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(dp(12), dp(12), dp(12), dp(12));
        rootLayout.addView(options);

        TextView skipTitle = text("Start every video after", 16, text, true);
        options.addView(skipTitle);
        LinearLayout timeRow = row();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        minutesInput = input(String.valueOf(prefs.getInt(KEY_SKIP_MINUTES, 3)));
        secondsInput = input(String.valueOf(prefs.getInt(KEY_SKIP_SECONDS, 0)));
        minutesInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        secondsInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        timeRow.addView(labeledInput("Min", minutesInput), weightParams());
        timeRow.addView(labeledInput("Sec", secondsInput), weightParams());
        options.addView(timeRow);

        TextWatcher timeWatcher = new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                applySkipTime();
                saveSkipTime();
            }
        };
        minutesInput.addTextChangedListener(timeWatcher);
        secondsInput.addTextChangedListener(timeWatcher);

        autoplayCheck = new CheckBox(this);
        autoplayCheck.setText("Auto play next");
        autoplayCheck.setTextColor(text);
        autoplayCheck.setTextSize(15);
        autoplayCheck.setChecked(true);
        autoplayCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.rgb(33, 199, 168)));
        autoplayCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (playerService != null) playerService.setAutoplayNext(isChecked);
        });
        options.addView(autoplayCheck);

        LinearLayout actions = row();
        Button pickButton = primaryButton("Pick videos");
        Button scanButton = secondaryButton("Scan phone");
        pickButton.setOnClickListener(v -> openVideoPicker());
        scanButton.setOnClickListener(v -> loadLibraryVideos());
        actions.addView(pickButton, weightParams());
        actions.addView(scanButton, weightParams());
        rootLayout.addView(actions);

        LinearLayout playlistControls = panel();
        playlistControls.setOrientation(LinearLayout.VERTICAL);
        playlistControls.setPadding(dp(12), dp(12), dp(12), dp(12));
        playlistControls.addView(text("Playlists", 16, text, true));
        LinearLayout playlistEditRow = row();
        playlistNameInput = input("Default");
        Button openPlaylistButton = primaryButton("Open/Create");
        Button deletePlaylistButton = secondaryButton("Delete");
        openPlaylistButton.setOnClickListener(v -> openOrCreatePlaylist());
        deletePlaylistButton.setOnClickListener(v -> deleteCurrentPlaylist());
        playlistEditRow.addView(playlistNameInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        playlistEditRow.addView(openPlaylistButton);
        playlistEditRow.addView(deletePlaylistButton);
        playlistControls.addView(playlistEditRow);
        playlistTabs = new LinearLayout(this);
        playlistTabs.setOrientation(LinearLayout.VERTICAL);
        playlistControls.addView(playlistTabs);
        rootLayout.addView(playlistControls);

        TextView playlistTitle = sectionTitle("Playlist - play order");
        playlistTitle.setPadding(0, dp(18), 0, dp(8));
        rootLayout.addView(playlistTitle);
        playlistList = new LinearLayout(this);
        playlistList.setOrientation(LinearLayout.VERTICAL);
        rootLayout.addView(playlistList);

        TextView historyTitle = sectionTitle("History");
        historyTitle.setPadding(0, dp(18), 0, dp(8));
        rootLayout.addView(historyTitle);
        historyList = new LinearLayout(this);
        historyList.setOrientation(LinearLayout.VERTICAL);
        rootLayout.addView(historyList);

        searchInput = input("");
        searchInput.setHint("Search phone videos...");
        searchInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                refreshLibrary();
            }
        });
        rootLayout.addView(searchInput, fullParams());

        TextView libraryTitle = sectionTitle("Phone videos");
        rootLayout.addView(libraryTitle);
        libraryList = new LinearLayout(this);
        libraryList.setOrientation(LinearLayout.VERTICAL);
        rootLayout.addView(libraryList);

        setContentView(scrollView);
    }

    private void requestNeededPermissions() {
        ArrayList<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
        } else if (Build.VERSION.SDK_INT < 33 && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (permissions.isEmpty()) {
            loadLibraryVideos();
        } else {
            requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    private void loadStoredPlaylists() {
        savedPlaylists.clear();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        currentPlaylistName = prefs.getString(KEY_CURRENT_PLAYLIST, "Default");
        try {
            JSONObject root = new JSONObject(prefs.getString(KEY_PLAYLISTS, "{}"));
            JSONArray names = root.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String name = names.getString(i);
                    JSONArray items = root.getJSONArray(name);
                    ArrayList<VideoItem> playlist = new ArrayList<>();
                    for (int j = 0; j < items.length(); j++) {
                        playlist.add(videoFromJson(items.getJSONObject(j)));
                    }
                    savedPlaylists.put(name, playlist);
                }
            }
        } catch (JSONException ignored) {
        }

        if (!savedPlaylists.containsKey(currentPlaylistName)) {
            currentPlaylistName = "Default";
        }
        if (!savedPlaylists.containsKey("Default")) {
            savedPlaylists.put("Default", new ArrayList<>());
        }
        playlistNameInput.setText(currentPlaylistName);
        refreshPlaylistTabs();
    }

    private void loadCurrentPlaylistIntoService() {
        if (playerService == null) return;
        playerService.clearPlaylist();
        ArrayList<VideoItem> playlist = savedPlaylists.get(currentPlaylistName);
        if (playlist == null) return;
        for (VideoItem item : playlist) {
            playerService.addToPlaylist(item);
        }
    }

    private void saveActivePlaylistFromService() {
        if (playerService == null) return;
        savedPlaylists.put(currentPlaylistName, new ArrayList<>(playerService.getPlaylist()));
        saveAllPlaylists();
    }

    private void saveAllPlaylists() {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, ArrayList<VideoItem>> entry : savedPlaylists.entrySet()) {
                JSONArray items = new JSONArray();
                for (VideoItem item : entry.getValue()) {
                    items.put(videoToJson(item));
                }
                root.put(entry.getKey(), items);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_PLAYLISTS, root.toString())
                    .putString(KEY_CURRENT_PLAYLIST, currentPlaylistName)
                    .commit();
        } catch (JSONException ignored) {
        }
    }

    private JSONObject videoToJson(VideoItem item) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("uri", item.uri.toString());
        json.put("name", item.name);
        json.put("duration", item.durationMs);
        json.put("size", item.sizeBytes);
        return json;
    }

    private VideoItem videoFromJson(JSONObject json) throws JSONException {
        return new VideoItem(
                Uri.parse(json.getString("uri")),
                json.optString("name", "Video"),
                json.optLong("duration", 0),
                json.optLong("size", 0)
        );
    }

    private void saveHistory() {
        if (playerService == null) return;
        try {
            JSONObject progress = new JSONObject();
            for (Map.Entry<String, Integer> entry : playerService.getProgressSnapshot().entrySet()) {
                progress.put(entry.getKey(), entry.getValue());
            }
            JSONArray watched = new JSONArray();
            for (String key : playerService.getWatchedSnapshot()) {
                watched.put(key);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_PROGRESS, progress.toString())
                    .putString(KEY_WATCHED, watched.toString())
                    .commit();
        } catch (JSONException ignored) {
        }
    }

    private Map<String, Integer> loadProgress() {
        Map<String, Integer> progress = new HashMap<>();
        try {
            JSONObject json = new JSONObject(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PROGRESS, "{}"));
            JSONArray names = json.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String key = names.getString(i);
                    progress.put(key, json.optInt(key, 0));
                }
            }
        } catch (JSONException ignored) {
        }
        return progress;
    }

    private Set<String> loadWatched() {
        Set<String> watched = new HashSet<>();
        try {
            JSONArray json = new JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_WATCHED, "[]"));
            for (int i = 0; i < json.length(); i++) {
                watched.add(json.getString(i));
            }
        } catch (JSONException ignored) {
        }
        return watched;
    }

    private void openOrCreatePlaylist() {
        String name = playlistNameInput.getText().toString().trim();
        if (name.isEmpty()) return;
        saveActivePlaylistFromService();
        if (!savedPlaylists.containsKey(name)) {
            savedPlaylists.put(name, new ArrayList<>());
        }
        currentPlaylistName = name;
        loadCurrentPlaylistIntoService();
        saveAllPlaylists();
        refreshAll();
    }

    private void deleteCurrentPlaylist() {
        if (savedPlaylists.size() <= 1) return;
        savedPlaylists.remove(currentPlaylistName);
        currentPlaylistName = savedPlaylists.keySet().iterator().next();
        playlistNameInput.setText(currentPlaylistName);
        loadCurrentPlaylistIntoService();
        saveAllPlaylists();
        refreshAll();
    }

    private void buildVideoOverlay(int textColor) {
        videoControlsOverlay = new LinearLayout(this);
        videoControlsOverlay.setOrientation(LinearLayout.HORIZONTAL);
        videoControlsOverlay.setGravity(Gravity.CENTER_VERTICAL);
        videoControlsOverlay.setPadding(dp(8), dp(6), dp(8), dp(6));
        videoControlsOverlay.setBackgroundColor(Color.argb(190, 0, 0, 0));
        videoControlsOverlay.setVisibility(View.GONE);

        overlayPlayPauseButton = primaryButton("Play");
        overlayPlayPauseButton.setMinWidth(dp(82));
        overlayPlayPauseButton.setOnClickListener(v -> togglePlayback());
        videoControlsOverlay.addView(overlayPlayPauseButton);

        videoProgressBar = new SeekBar(this);
        videoProgressBar.setMax(0);
        videoProgressBar.setProgress(0);
        videoProgressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    updateVideoProgressText(progress, seekBar.getMax());
                    long now = System.currentTimeMillis();
                    if (playerService != null && now - lastDragSeekMs > 120) {
                        lastDragSeekMs = now;
                        playerService.seekTo(progress);
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userDraggingVideoProgress = true;
                lastDragSeekMs = 0;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                userDraggingVideoProgress = false;
                lastDragSeekMs = 0;
                if (playerService != null) {
                    playerService.seekTo(seekBar.getProgress());
                }
                updateVideoControls();
            }
        });
        videoControlsOverlay.addView(videoProgressBar, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        ));

        videoProgressText = text("0:00 / 0:00", 12, textColor, false);
        videoProgressText.setGravity(Gravity.CENTER_VERTICAL);
        videoControlsOverlay.addView(videoProgressText);

        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        videoContainer.addView(videoControlsOverlay, overlayParams);
    }

    private void setupVideoGestures() {
        videoGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent event) {
                toggleVideoControls();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent event) {
                float x = event.getX();
                int width = Math.max(1, videoContainer.getWidth());
                if (x < width / 3f) {
                    if (playerService != null) playerService.seekBy(-10000);
                } else if (x > width * 2f / 3f) {
                    if (playerService != null) playerService.seekBy(10000);
                } else {
                    setVideoFullscreen(!videoFullscreen);
                }
                return true;
            }

            @Override
            public boolean onDown(MotionEvent event) {
                return true;
            }
        });

        textureView.setOnTouchListener((view, event) -> videoGestureDetector.onTouchEvent(event));
        videoContainer.setOnTouchListener((view, event) -> videoGestureDetector.onTouchEvent(event));
    }

    private void setVideoFullscreen(boolean fullscreen) {
        videoFullscreen = fullscreen;
        int height = fullscreen ? getResources().getDisplayMetrics().heightPixels : dp(210);
        videoContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height
        ));

        for (int i = 0; i < rootLayout.getChildCount(); i++) {
            View child = rootLayout.getChildAt(i);
            if (child != videoContainer) {
                child.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
            }
        }

        rootLayout.setPadding(
                fullscreen ? 0 : dp(14),
                fullscreen ? 0 : dp(18),
                fullscreen ? 0 : dp(14),
                fullscreen ? 0 : dp(28)
        );

        if (fullscreen) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            videoContainer.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            videoContainer.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
        videoContainer.post(this::applyVideoAspectTransform);
    }

    private void applyOrientationFullscreen() {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (videoContainer != null && videoFullscreen != landscape) {
            setVideoFullscreen(landscape);
        } else if (videoContainer != null) {
            videoContainer.post(this::applyVideoAspectTransform);
        }
    }

    private void updateScreenAwakeState() {
        boolean shouldKeepAwake = activityResumed && playerService != null && playerService.isPlaying();
        if (videoContainer != null) {
            videoContainer.setKeepScreenOn(shouldKeepAwake);
        }
        if (shouldKeepAwake) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void clearScreenAwakeState() {
        if (videoContainer != null) {
            videoContainer.setKeepScreenOn(false);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void togglePlayback() {
        if (playerService == null) return;
        if (playerService.isPlaying()) {
            playerService.pause();
        } else {
            if (playerService.getPlaylist().isEmpty()) return;
            startPlaybackServiceIfNeeded();
            playerService.play();
        }
        updateVideoControls();
        updateScreenAwakeState();
    }

    private void toggleVideoControls() {
        setVideoControlsVisible(videoControlsOverlay == null || videoControlsOverlay.getVisibility() != View.VISIBLE);
    }

    private void setVideoControlsVisible(boolean visible) {
        if (videoControlsOverlay == null) return;
        videoControlsOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            updateVideoControls();
        }
    }

    private void updateVideoControls() {
        if (playerService == null || videoProgressBar == null || overlayPlayPauseButton == null) return;
        int duration = Math.max(0, playerService.getDurationMs());
        int position = Math.max(0, playerService.getCurrentPositionMs());
        overlayPlayPauseButton.setText(playerService.isPlaying() ? "Pause" : "Play");
        if (!userDraggingVideoProgress) {
            videoProgressBar.setMax(duration);
            videoProgressBar.setProgress(Math.min(position, duration));
            updateVideoProgressText(position, duration);
        }
        applyVideoAspectTransform();
    }

    private void updateVideoProgressText(int position, int duration) {
        if (videoProgressText == null) return;
        videoProgressText.setText(formatTime(position) + " / " + formatTime(duration));
    }

    private void applyVideoAspectTransform() {
        if (textureView == null || playerService == null) return;
        int viewWidth = textureView.getWidth();
        int viewHeight = textureView.getHeight();
        int videoWidth = playerService.getVideoWidth();
        int videoHeight = playerService.getVideoHeight();
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            textureView.setTransform(null);
            return;
        }

        float viewAspect = viewWidth / (float) viewHeight;
        float videoAspect = videoWidth / (float) videoHeight;
        float scaleX = 1f;
        float scaleY = 1f;
        if (videoAspect > viewAspect) {
            scaleY = viewAspect / videoAspect;
        } else {
            scaleX = videoAspect / viewAspect;
        }

        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f);
        textureView.setTransform(matrix);
    }

    private void startPlaybackServiceIfNeeded() {
        Intent serviceIntent = new Intent(this, BackgroundPlayerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void attachPlaybackSurface() {
        if (playerService != null && playbackSurface != null && playbackSurface.isValid()) {
            playerService.setOutputSurface(playbackSurface);
        }
    }

    private void releasePlaybackSurface() {
        if (playbackSurface != null) {
            playbackSurface.release();
            playbackSurface = null;
        }
    }

    private void loadLibraryVideos() {
        libraryVideos.clear();
        Uri collection = Build.VERSION.SDK_INT >= 29
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE
        };
        String orderBy = MediaStore.Video.Media.DATE_ADDED + " DESC";

        try (Cursor cursor = getContentResolver().query(collection, projection, null, null, orderBy)) {
            if (cursor == null) {
                refreshLibrary();
                return;
            }
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
            int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                Uri contentUri = ContentUris.withAppendedId(collection, id);
                String name = cursor.getString(nameColumn);
                long duration = cursor.getLong(durationColumn);
                long size = cursor.getLong(sizeColumn);
                libraryVideos.add(new VideoItem(contentUri, name == null ? "Video" : name, duration, size));
            }
        } catch (SecurityException ignored) {
            // Permission was denied; users can still use Pick videos.
        }
        refreshLibrary();
    }

    private void openVideoPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PICK_VIDEOS);
    }

    private void addPickedVideo(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
        String name = queryName(uri);
        playerService.addToPlaylist(new VideoItem(uri, name, 0, 0));
        saveActivePlaylistFromService();
    }

    private String queryName(Uri uri) {
        String[] projection = {MediaStore.MediaColumns.DISPLAY_NAME};
        try (Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) return name;
            }
        } catch (Exception ignored) {
        }
        return uri.getLastPathSegment() == null ? "Picked video" : uri.getLastPathSegment();
    }

    private void applySkipTime() {
        int minutes = parseBounded(minutesInput, 0, 999);
        int seconds = parseBounded(secondsInput, 0, 59);
        if (playerService != null) {
            playerService.setStartOffsetMs((minutes * 60 + seconds) * 1000);
        }
    }

    private void saveSkipTime() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_SKIP_MINUTES, parseBounded(minutesInput, 0, 999))
                .putInt(KEY_SKIP_SECONDS, parseBounded(secondsInput, 0, 59))
                .commit();
    }

    private int parseBounded(EditText editText, int min, int max) {
        String raw = editText.getText().toString().trim();
        int value;
        try {
            value = raw.isEmpty() ? 0 : Integer.parseInt(raw);
        } catch (NumberFormatException error) {
            value = 0;
        }
        return Math.max(min, Math.min(max, value));
    }

    private void refreshAll() {
        refreshPlayerHeader();
        refreshPlaylistTabs();
        refreshLibrary();
        refreshPlaylist();
        refreshHistory();
    }

    private void refreshPlayerHeader() {
        if (playerService == null) return;
        VideoItem current = playerService.getCurrentItem();
        nowPlayingText.setText(current == null ? "Nothing loaded" : current.name);
        int index = playerService.getCurrentIndex();
        int total = playerService.getPlaylist().size();
        counterText.setText(total == 0 ? "0 / 0" : String.format(Locale.US, "%d / %d", index + 1, total));
        playPauseButton.setText(playerService.isPlaying() ? "Pause" : "Play");
        updateVideoControls();
        updateScreenAwakeState();
    }

    private void refreshLibrary() {
        libraryList.removeAllViews();
        String query = searchInput == null ? "" : searchInput.getText().toString().trim().toLowerCase(Locale.US);
        int shown = 0;
        for (VideoItem item : libraryVideos) {
            if (!query.isEmpty() && !item.name.toLowerCase(Locale.US).contains(query)) continue;
            libraryList.addView(videoRow(item, "Add", false, "", v -> {
                if (playerService != null) {
                    playerService.addToPlaylist(item);
                    saveActivePlaylistFromService();
                    refreshPlaylist();
                    refreshPlaylistTabs();
                }
            }));
            shown++;
            if (shown >= 120) break;
        }
        if (shown == 0) {
            libraryList.addView(emptyText("No scanned videos found. Use Pick videos to choose files manually."));
        }
    }

    private void refreshPlaylist() {
        playlistList.removeAllViews();
        if (playerService == null || playerService.getPlaylist().isEmpty()) {
            playlistList.addView(emptyText("No playlist videos yet."));
            refreshPlayerHeader();
            return;
        }

        List<VideoItem> playlist = playerService.getPlaylist();
        int current = playerService.getCurrentIndex();
        for (int i = 0; i < playlist.size(); i++) {
            int index = i;
            VideoItem item = playlist.get(i);
            boolean watched = playerService.isWatched(item);
            String action = index == current && playerService.isPlaying() ? "Playing" : (index == current ? "Current" : "Play");
            LinearLayout row = videoRow(item, action, watched, (index + 1) + ". ", v -> {
                if (playerService != null) {
                    if (index == playerService.getCurrentIndex() && playerService.isPlaying()) {
                        playerService.pause();
                        saveHistory();
                    } else {
                        startPlaybackServiceIfNeeded();
                        playerService.playIndex(index);
                    }
                }
            });
            Button remove = secondaryButton("Remove");
            remove.setOnClickListener(v -> {
                if (playerService != null) {
                    playerService.removeFromPlaylist(index);
                    saveActivePlaylistFromService();
                    refreshPlaylist();
                }
            });
            row.addView(remove);
            playlistList.addView(row);
        }
        refreshPlayerHeader();
    }

    private void refreshPlaylistTabs() {
        if (playlistTabs == null) return;
        playlistTabs.removeAllViews();
        for (String name : savedPlaylists.keySet()) {
            Button button = name.equals(currentPlaylistName)
                    ? primaryButton(name)
                    : secondaryButton(name);
            button.setOnClickListener(v -> {
                playlistNameInput.setText(name);
                openOrCreatePlaylist();
            });
            playlistTabs.addView(button, fullParams());
        }
    }

    private void refreshHistory() {
        if (historyList == null) return;
        historyList.removeAllViews();
        if (playerService == null) {
            historyList.addView(emptyText("History loads after the player starts."));
            return;
        }

        LinkedHashMap<String, VideoItem> known = new LinkedHashMap<>();
        for (ArrayList<VideoItem> playlist : savedPlaylists.values()) {
            for (VideoItem item : playlist) known.put(item.key(), item);
        }
        for (VideoItem item : playerService.getPlaylist()) known.put(item.key(), item);
        for (VideoItem item : libraryVideos) known.put(item.key(), item);

        int shown = 0;
        for (VideoItem item : known.values()) {
            int progress = progressFor(item);
            if (!playerService.isWatched(item) && progress <= 0) continue;
            TextView line = text(
                    (playerService.isWatched(item) ? "Watched - " : "Started - ") + item.name + " (" + progressLabel(item) + ")",
                    13,
                    Color.rgb(170, 180, 194),
                    false
            );
            line.setPadding(dp(8), dp(5), dp(8), dp(5));
            historyList.addView(line);
            shown++;
        }

        if (shown == 0) {
            historyList.addView(emptyText("No watched videos yet."));
        }
    }

    private LinearLayout videoRow(VideoItem item, String actionText, boolean watched, String prefix, View.OnClickListener action) {
        LinearLayout row = panel();
        if (watched) {
            row.setBackgroundResource(R.drawable.panel_watched_bg);
        }
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));

        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(prefix + item.name, 15, watched ? Color.rgb(158, 166, 176) : Color.rgb(244, 247, 251), true);
        TextView meta = text(watched ? "Watched - " + formatMeta(item) : formatMeta(item), 12, Color.rgb(170, 180, 194), false);
        textBlock.addView(name);
        textBlock.addView(meta);
        row.addView(textBlock, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button actionButton = primaryButton(actionText);
        actionButton.setOnClickListener(action);
        row.addView(actionButton);
        return row;
    }

    private String formatMeta(VideoItem item) {
        String progress = progressLabel(item);
        String duration = item.durationMs > 0
                ? String.format(Locale.US, "%d:%02d", item.durationMs / 60000, (item.durationMs / 1000) % 60)
                : "Picked file";
        String size = item.sizeBytes > 0
                ? String.format(Locale.US, "%.1f MB", item.sizeBytes / 1024f / 1024f)
                : "";
        String base = size.isEmpty() ? duration : duration + " - " + size;
        return progress.isEmpty() ? base : progress + " - " + base;
    }

    private String formatProgress(VideoItem item) {
        int progress = progressFor(item);
        int duration = durationFor(item);
        if (progress <= 0) return "";
        if (duration <= 0) return formatTime(progress);
        return formatTime(progress) + " / " + formatTime(duration);
    }

    private String progressLabel(VideoItem item) {
        String progress = formatProgress(item);
        if (progress.isEmpty()) return "";
        VideoItem current = playerService == null ? null : playerService.getCurrentItem();
        if (current != null && current.key().equals(item.key()) && playerService.isPlaying()) {
            return "Position " + progress;
        }
        return "Resume " + progress;
    }

    private int progressFor(VideoItem item) {
        if (playerService == null || item == null) return 0;
        VideoItem current = playerService.getCurrentItem();
        if (current != null && current.key().equals(item.key()) && playerService.isPlaying()) {
            return playerService.getCurrentPositionMs();
        }
        return playerService.getSavedProgressMs(item);
    }

    private int durationFor(VideoItem item) {
        if (playerService != null) {
            VideoItem current = playerService.getCurrentItem();
            if (current != null && current.key().equals(item.key())) {
                int duration = playerService.getDurationMs();
                if (duration > 0) return duration;
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, item.durationMs));
    }

    private String formatTime(int millis) {
        int totalSeconds = Math.max(0, millis / 1000);
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private TextView emptyText(String value) {
        TextView textView = text(value, 14, Color.rgb(170, 180, 194), false);
        textView.setPadding(dp(8), dp(10), dp(8), dp(10));
        return textView;
    }

    private TextView sectionTitle(String value) {
        TextView textView = text(value, 20, Color.rgb(244, 247, 251), true);
        textView.setPadding(0, dp(16), 0, dp(8));
        return textView;
    }

    private LinearLayout panel() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setBackgroundResource(R.drawable.panel_bg);
        LinearLayout.LayoutParams params = fullParams();
        params.setMargins(0, dp(8), 0, 0);
        layout.setLayoutParams(params);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(0, dp(10), 0, dp(4));
        return layout;
    }

    private LinearLayout labeledInput(String label, EditText input) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        TextView labelView = text(label, 12, Color.rgb(170, 180, 194), false);
        layout.addView(labelView);
        layout.addView(input, fullParams());
        return layout;
    }

    private EditText input(String value) {
        EditText editText = new EditText(this);
        editText.setText(value);
        editText.setTextColor(Color.rgb(244, 247, 251));
        editText.setHintTextColor(Color.rgb(120, 130, 143));
        editText.setSingleLine(true);
        editText.setTextSize(15);
        editText.setBackgroundResource(R.drawable.input_bg);
        editText.setPadding(dp(12), 0, dp(12), 0);
        return editText;
    }

    private Button primaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(Color.rgb(6, 33, 28));
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundResource(R.drawable.button_primary);
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(Color.rgb(244, 247, 251));
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundResource(R.drawable.button_secondary);
        return button;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextColor(color);
        textView.setTextSize(sp);
        textView.setIncludeFontPadding(true);
        if (bold) textView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return textView;
    }

    private LinearLayout.LayoutParams fullParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(8), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        );
        params.setMargins(dp(4), 0, dp(4), 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }
}
