package com.choppy.episodeflow;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.util.Rational;
import android.util.Size;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends Activity implements BackgroundPlayerService.PlayerListener {
    private static final int REQUEST_PERMISSIONS = 100;
    private static final int REQUEST_PICK_VIDEOS = 101;
    private static final String PREFS = "episodeflow_store";
    private static final String KEY_PLAYLISTS = "playlists";
    private static final String KEY_PLAYLIST_SOURCES = "playlist_sources";
    private static final String KEY_CURRENT_PLAYLIST = "current_playlist";
    private static final String KEY_PROGRESS = "progress";
    private static final String KEY_WATCHED = "watched";
    private static final String KEY_HISTORY_ITEMS = "history_items";
    private static final String KEY_SKIP_MINUTES = "skip_minutes";
    private static final String KEY_SKIP_SECONDS = "skip_seconds";
    private static final String KEY_ACTIVE_TAB = "active_tab";
    private static final String KEY_PLAYLIST_DETAIL_OPEN = "playlist_detail_open";
    private static final String KEY_AUDIO_TRACK_INDEX = "audio_track_index";
    private static final String KEY_AUDIO_TRACK_LANGUAGE = "audio_track_language";
    private static final String KEY_AUTOPLAY = "autoplay_next";
    private static final String KEY_PLAYBACK_SPEED = "playback_speed";
    private static final long FOREGROUND_PROGRESS_TICK_MS = 1000;
    private static final long BACKGROUND_PROGRESS_TICK_MS = 15000;

    private final ArrayList<VideoItem> libraryVideos = new ArrayList<>();
    private final LinkedHashMap<String, ArrayList<VideoItem>> savedPlaylists = new LinkedHashMap<>();
    private final Map<String, String> playlistSourceLinks = new HashMap<>();
    private final LinkedHashMap<String, VideoItem> historyCatalog = new LinkedHashMap<>();
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private BackgroundPlayerService playerService;
    private boolean serviceBound = false;
    private boolean videoFullscreen = false;
    private boolean inPictureInPicture = false;
    private boolean userDraggingVideoProgress = false;
    private boolean activityResumed = false;
    private boolean controlsLocked = false;
    private boolean audioOnlyMode = false;
    private long lastDragSeekMs = 0;
    private String lastScanMessage = "No scan yet";
    private String currentPlaylistName = "Default";
    private ScrollView mainScrollView;
    private FrameLayout videoContainer;
    private TextureView textureView;
    private Surface playbackSurface;
    private GestureDetector videoGestureDetector;
    private LinearLayout videoControlsOverlay;
    private ImageButton overlayPlayPauseButton;
    private ImageButton overlayPipButton;
    private ImageButton overlayAudioButton;
    private ImageButton overlayLockButton;
    private SeekBar videoProgressBar;
    private TextView videoProgressText;
    private LinearLayout rootLayout;
    private LinearLayout topBar;
    private LinearLayout bottomNavBar;
    private TextView topBarTitle;
    private TextView topBarSubtitle;
    private LinearLayout miniPlayer;
    private ImageView miniPlayerThumbnail;
    private TextView miniPlayerTitle;
    private TextView miniPlayerMeta;
    private ProgressBar miniPlayerProgress;
    private ImageButton miniPlayerPlayPause;
    private Button bottomVideoButton;
    private Button bottomPlaylistButton;
    private Button bottomDownloadsButton;
    private Button bottomHistoryButton;
    private Button bottomMoreButton;
    private final ArrayList<View> videoPageViews = new ArrayList<>();
    private final ArrayList<View> playlistPageViews = new ArrayList<>();
    private final ArrayList<View> downloadsPageViews = new ArrayList<>();
    private final ArrayList<View> historyPageViews = new ArrayList<>();
    private final ArrayList<View> morePageViews = new ArrayList<>();
    private final ArrayList<View> playbackViews = new ArrayList<>();
    private final ArrayList<View> settingsViews = new ArrayList<>();
    private final ArrayList<View> playlistHubViews = new ArrayList<>();
    private final ArrayList<View> playlistDetailViews = new ArrayList<>();
    private String activeBottomTab = "video";
    private boolean playlistDetailOpen = false;
    private boolean sourceToolsExpanded = false;
    private View playerSection;
    private View playlistSection;
    private View historySection;
    private View downloadsSection;
    private View settingsSection;
    private View debugSection;
    private TextView debugText;
    private TextView nowPlayingText;
    private TextView counterText;
    private ImageButton playPauseButton;
    private Button speedMenuButton;
    private EditText searchInput;
    private EditText playlistNameInput;
    private EditText sourceLinkInput;
    private EditText minutesInput;
    private EditText secondsInput;
    private Switch autoplayCheck;
    private LinearLayout playlistTabs;
    private LinearLayout libraryList;
    private LinearLayout playlistList;
    private LinearLayout historyList;
    private TextView playlistDetailTitle;
    private TextView playlistDetailMeta;
    private LinearLayout sourceToolsPanel;
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService libraryExecutor = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> thumbnailCache = new LruCache<>(48);

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            if (playerService != null) {
                saveHistory();
                if (activityResumed) {
                    refreshPlayerHeader();
                    updateVideoControls();
                }
            }
            progressHandler.postDelayed(this, activityResumed ? FOREGROUND_PROGRESS_TICK_MS : BACKGROUND_PROGRESS_TICK_MS);
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            playerService = ((BackgroundPlayerService.LocalBinder) service).getService();
            attachPlaybackSurface();
            boolean serviceHasQueue = !playerService.getPlaylist().isEmpty();
            if (!serviceHasQueue) {
                playerService.restoreHistory(loadProgress(), loadWatched());
                loadCurrentPlaylistIntoService(false);
            }
            playerService.restorePreferredAudioTrack(loadPreferredAudioTrackIndex(), loadPreferredAudioTrackLanguage());
            playerService.setAutoplayNext(autoplayCheck.isChecked());
            playerService.setPlaybackSpeed(getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(KEY_PLAYBACK_SPEED, 1f));
            applySkipTime();
            playerService.setListener(MainActivity.this);
            serviceBound = true;
            recordHistoryCatalog();
            progressHandler.removeCallbacks(progressTicker);
            progressHandler.post(progressTicker);
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
        loadNavigationState();
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
        audioOnlyMode = false;
        attachPlaybackSurface();
        applyOrientationFullscreen();
        applyBottomTabVisibility();
        reloadPlaylistSourceLinks();
        updateSourceInputForCurrentPlaylist();
        updateScreenAwakeState();
        progressHandler.removeCallbacks(progressTicker);
        progressHandler.post(progressTicker);
        refreshAll();
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        saveHistory();
        saveNavigationState();
        progressHandler.removeCallbacks(progressTicker);
        progressHandler.postDelayed(progressTicker, BACKGROUND_PROGRESS_TICK_MS);
        clearScreenAwakeState();
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        rootLayout.post(this::applyOrientationFullscreen);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        inPictureInPicture = isInPictureInPictureMode;
        applyPictureInPictureUi();
    }

    @Override
    protected void onDestroy() {
        saveActivePlaylistFromService(true);
        saveHistory(true);
        saveSkipTime();
        saveNavigationState();
        progressHandler.removeCallbacks(progressTicker);
        clearScreenAwakeState();
        if (playerService != null) {
            if (playbackSurface != null) {
                playerService.clearOutputSurface(playbackSurface);
            }
            playerService.setListener(null);
        }
        releasePlaybackSurface();
        thumbnailExecutor.shutdownNow();
        libraryExecutor.shutdownNow();
        thumbnailCache.evictAll();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        super.onDestroy();
    }

    @Override
    public void onPlayerChanged() {
        recordHistoryCatalog();
        saveHistory();
        saveActivePlaylistFromService();
        if (activityResumed) {
            runOnUiThread(this::refreshPlaylist);
            runOnUiThread(this::refreshPlayerHeader);
            runOnUiThread(this::refreshHistory);
        }
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
        int bg = Color.rgb(16, 20, 22);
        int textColor = Color.rgb(244, 248, 247);
        int muted = Color.rgb(170, 182, 179);

        LinearLayout screenLayout = new LinearLayout(this);
        screenLayout.setOrientation(LinearLayout.VERTICAL);
        screenLayout.setBackgroundColor(bg);

        topBar = buildTopBar(textColor, muted);
        screenLayout.addView(topBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(66)
        ));

        mainScrollView = new ScrollView(this);
        mainScrollView.setFillViewport(true);
        mainScrollView.setBackgroundColor(bg);
        mainScrollView.setClipToPadding(false);
        screenLayout.addView(mainScrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setPadding(dp(14), dp(8), dp(14), dp(24));
        mainScrollView.addView(rootLayout, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        buildPlayerPage(textColor, muted);
        buildPlaylistPage(textColor, muted);
        buildLibraryPage(textColor);
        buildHistoryPage();
        buildDiagnosticsPage(muted);

        miniPlayer = buildMiniPlayer(textColor, muted);
        LinearLayout.LayoutParams miniParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        miniParams.setMargins(dp(8), dp(4), dp(8), dp(4));
        screenLayout.addView(miniPlayer, miniParams);

        bottomNavBar = buildBottomNavigation();
        screenLayout.addView(bottomNavBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(70)
        ));
        setContentView(screenLayout);
        applySystemBarPadding(screenLayout);
        showBottomTab(activeBottomTab, bottomButtonForTab(activeBottomTab));
    }

    private LinearLayout buildTopBar(int textColor, int muted) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(6), dp(14), dp(6));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.episodeflow_icon_foreground);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bar.addView(logo, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(10), 0, 0, 0);
        topBarTitle = text("EpisodeFlow", 21, textColor, true);
        topBarSubtitle = text("Player", 12, muted, false);
        labels.addView(topBarTitle);
        labels.addView(topBarSubtitle);
        bar.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return bar;
    }

    private void buildPlayerPage(int textColor, int muted) {
        TextView playerTitle = sectionTitle("Now playing");
        rootLayout.addView(playerTitle);
        videoPageViews.add(playerTitle);
        playbackViews.add(playerTitle);

        videoContainer = new AccessibleFrameLayout(this);
        playerSection = videoContainer;
        videoContainer.setBackgroundResource(R.drawable.video_surface_bg);
        videoContainer.setClipToOutline(true);
        textureView = new AccessibleTextureView(this);
        LinearLayout.LayoutParams videoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(218)
        );
        rootLayout.addView(videoContainer, videoParams);
        videoPageViews.add(videoContainer);
        playbackViews.add(videoContainer);
        videoContainer.addView(textureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        buildVideoOverlay(textColor);
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
        nowPanel.setPadding(dp(14), dp(12), dp(14), dp(12));
        nowPanel.setOrientation(LinearLayout.VERTICAL);
        nowPlayingText = text("Nothing loaded", 18, textColor, true);
        nowPlayingText.setMaxLines(2);
        counterText = text("No playlist selected", 13, muted, false);
        nowPanel.addView(nowPlayingText);
        nowPanel.addView(counterText);
        rootLayout.addView(nowPanel);
        videoPageViews.add(nowPanel);
        playbackViews.add(nowPanel);

        LinearLayout transport = row();
        ImageButton previousButton = iconButton(android.R.drawable.ic_media_previous, "Previous episode", false);
        playPauseButton = iconButton(android.R.drawable.ic_media_play, "Play", true);
        ImageButton nextButton = iconButton(android.R.drawable.ic_media_next, "Next episode", false);
        previousButton.setOnClickListener(v -> {
            if (playerService != null) playerService.previous();
        });
        playPauseButton.setOnClickListener(v -> togglePlayback());
        nextButton.setOnClickListener(v -> {
            if (playerService != null) playerService.next();
        });
        transport.addView(previousButton, transportButtonParams(false));
        transport.addView(playPauseButton, transportButtonParams(true));
        transport.addView(nextButton, transportButtonParams(false));
        rootLayout.addView(transport);
        videoPageViews.add(transport);
        playbackViews.add(transport);

        TextView settingsTitle = sectionTitle("Playback defaults");
        rootLayout.addView(settingsTitle);
        videoPageViews.add(settingsTitle);
        morePageViews.add(settingsTitle);
        settingsViews.add(settingsTitle);

        LinearLayout options = panel();
        settingsSection = options;
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(dp(14), dp(12), dp(14), dp(12));
        rootLayout.addView(options);
        videoPageViews.add(options);
        morePageViews.add(options);
        settingsViews.add(options);

        options.addView(text("Intro skip", 15, textColor, true));
        LinearLayout timeRow = row();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        minutesInput = input(String.valueOf(prefs.getInt(KEY_SKIP_MINUTES, 3)));
        secondsInput = input(String.valueOf(prefs.getInt(KEY_SKIP_SECONDS, 0)));
        minutesInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        secondsInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        timeRow.addView(labeledInput("Minutes", minutesInput), weightParams());
        timeRow.addView(labeledInput("Seconds", secondsInput), weightParams());
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

        autoplayCheck = new Switch(this);
        autoplayCheck.setText("Auto-play next episode");
        autoplayCheck.setTextColor(textColor);
        autoplayCheck.setTextSize(15);
        autoplayCheck.setChecked(prefs.getBoolean(KEY_AUTOPLAY, true));
        autoplayCheck.setThumbTintList(ColorStateList.valueOf(Color.rgb(47, 209, 181)));
        autoplayCheck.setTrackTintList(ColorStateList.valueOf(Color.rgb(61, 92, 87)));
        autoplayCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_AUTOPLAY, isChecked).apply();
            if (playerService != null) playerService.setAutoplayNext(isChecked);
        });
        options.addView(autoplayCheck, fullParams());

        LinearLayout speedRow = row();
        speedRow.addView(text("Playback speed", 15, textColor, false), new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        ));
        float savedSpeed = prefs.getFloat(KEY_PLAYBACK_SPEED, 1f);
        speedMenuButton = secondaryButton(speedText(savedSpeed));
        speedMenuButton.setOnClickListener(v -> showSpeedDialog());
        speedRow.addView(speedMenuButton);
        options.addView(speedRow);

        LinearLayout modeRow = row();
        Button pipMode = commandButton("PiP", android.R.drawable.ic_menu_slideshow);
        Button audioOnly = commandButton("Audio only", android.R.drawable.ic_lock_silent_mode_off);
        Button audioTrack = commandButton("Audio track", android.R.drawable.ic_menu_manage);
        pipMode.setOnClickListener(v -> enterVideoPictureInPicture());
        audioOnly.setOnClickListener(v -> enterAudioOnlyMode());
        audioTrack.setOnClickListener(v -> showAudioTrackDialog());
        modeRow.addView(pipMode, weightParams());
        modeRow.addView(audioOnly, weightParams());
        modeRow.addView(audioTrack, weightParams());
        options.addView(modeRow);
    }

    private void buildPlaylistPage(int textColor, int muted) {
        LinearLayout playlistControls = panel();
        playlistSection = playlistControls;
        playlistControls.setOrientation(LinearLayout.VERTICAL);
        playlistControls.setPadding(dp(14), dp(12), dp(14), dp(12));
        rootLayout.addView(playlistControls);
        playlistPageViews.add(playlistControls);

        TextView hubTitle = text("Your playlists", 20, textColor, true);
        playlistControls.addView(hubTitle);
        playlistHubViews.add(hubTitle);

        LinearLayout playlistEditRow = row();
        playlistNameInput = input("Default");
        playlistNameInput.setHint("Playlist name");
        ImageButton openPlaylistButton = iconButton(android.R.drawable.ic_menu_add, "Create or open playlist", true);
        openPlaylistButton.setOnClickListener(v -> openOrCreatePlaylist());
        playlistEditRow.addView(playlistNameInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(dp(52), dp(52));
        addParams.setMargins(dp(8), 0, 0, 0);
        playlistEditRow.addView(openPlaylistButton, addParams);
        playlistControls.addView(playlistEditRow);
        playlistHubViews.add(playlistEditRow);

        playlistTabs = new LinearLayout(this);
        playlistTabs.setOrientation(LinearLayout.VERTICAL);
        playlistControls.addView(playlistTabs);
        playlistHubViews.add(playlistTabs);

        LinearLayout detailHeader = new LinearLayout(this);
        detailHeader.setOrientation(LinearLayout.HORIZONTAL);
        detailHeader.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton backButton = iconButton(android.R.drawable.ic_media_previous, "Back to playlists", false);
        backButton.setOnClickListener(v -> {
            playlistDetailOpen = false;
            sourceToolsExpanded = false;
            saveNavigationState();
            applyBottomTabVisibility();
        });
        detailHeader.addView(backButton, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPadding(dp(10), 0, dp(8), 0);
        playlistDetailTitle = text("Playlist", 19, textColor, true);
        playlistDetailMeta = text("", 13, muted, false);
        titleBlock.addView(playlistDetailTitle);
        titleBlock.addView(playlistDetailMeta);
        detailHeader.addView(titleBlock, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        ImageButton deleteButton = iconButton(android.R.drawable.ic_menu_delete, "Delete playlist", false);
        deleteButton.setOnClickListener(v -> confirmDeleteCurrentPlaylist());
        detailHeader.addView(deleteButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        playlistControls.addView(detailHeader);
        playlistDetailViews.add(detailHeader);

        LinearLayout primaryActions = row();
        LinearLayout secondaryActions = row();
        Button playPlaylist = primaryButton("Play");
        Button scanDownloads = secondaryButton("Scan new");
        Button sourceTools = secondaryButton("Source");
        Button sort = secondaryButton("Sort");
        playPlaylist.setOnClickListener(v -> playCurrentPlaylist());
        scanDownloads.setOnClickListener(v -> scanDownloadsIntoCurrentPlaylist());
        sourceTools.setOnClickListener(v -> {
            if (sourceToolsPanel != null) {
                sourceToolsExpanded = !sourceToolsExpanded;
                sourceToolsPanel.setVisibility(sourceToolsExpanded ? View.VISIBLE : View.GONE);
            }
        });
        sort.setOnClickListener(v -> showSortDialog());
        primaryActions.addView(playPlaylist, weightParams());
        primaryActions.addView(scanDownloads, weightParams());
        secondaryActions.addView(sourceTools, weightParams());
        secondaryActions.addView(sort, weightParams());
        playlistControls.addView(primaryActions);
        playlistControls.addView(secondaryActions);
        playlistDetailViews.add(primaryActions);
        playlistDetailViews.add(secondaryActions);

        sourceToolsPanel = new LinearLayout(this);
        sourceToolsPanel.setOrientation(LinearLayout.VERTICAL);
        sourceToolsPanel.setPadding(0, dp(8), 0, 0);
        sourceToolsPanel.setVisibility(View.GONE);
        sourceToolsPanel.addView(text("Episode source", 14, muted, false));
        sourceLinkInput = input("");
        sourceLinkInput.setHint("Episode page link");
        sourceLinkInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                saveCurrentSourceFromInput();
            }
        });
        sourceToolsPanel.addView(sourceLinkInput, fullParams());

        LinearLayout sourceActions = row();
        Button openSourceButton = primaryButton("Open");
        Button previousEpisodeButton = secondaryButton("Previous page");
        Button nextEpisodeButton = secondaryButton("Next page");
        openSourceButton.setOnClickListener(v -> openSourceEpisode(0));
        previousEpisodeButton.setOnClickListener(v -> openSourceEpisode(-1));
        nextEpisodeButton.setOnClickListener(v -> openSourceEpisode(1));
        sourceActions.addView(openSourceButton, weightParams());
        sourceActions.addView(previousEpisodeButton, weightParams());
        sourceActions.addView(nextEpisodeButton, weightParams());
        sourceToolsPanel.addView(sourceActions);

        LinearLayout matchActions = row();
        Button matchSeriesButton = primaryButton("Find same series");
        Button scanAgainButton = secondaryButton("Scan downloads");
        matchSeriesButton.setOnClickListener(v -> addMatchingSeriesFromFirstVideo());
        scanAgainButton.setOnClickListener(v -> scanDownloadsIntoCurrentPlaylist());
        matchActions.addView(matchSeriesButton, weightParams());
        matchActions.addView(scanAgainButton, weightParams());
        sourceToolsPanel.addView(matchActions);
        playlistControls.addView(sourceToolsPanel);
        playlistDetailViews.add(sourceToolsPanel);

        TextView playlistTitle = sectionTitle("Up next");
        rootLayout.addView(playlistTitle);
        playlistPageViews.add(playlistTitle);
        playlistDetailViews.add(playlistTitle);
        playlistList = new LinearLayout(this);
        playlistList.setOrientation(LinearLayout.VERTICAL);
        rootLayout.addView(playlistList);
        playlistPageViews.add(playlistList);
        playlistDetailViews.add(playlistList);
    }

    private void buildLibraryPage(int textColor) {
        LinearLayout actions = row();
        Button pickButton = primaryButton("Pick files");
        Button scanButton = secondaryButton("Scan device");
        pickButton.setOnClickListener(v -> openVideoPicker());
        scanButton.setOnClickListener(v -> loadLibraryVideos());
        actions.addView(pickButton, weightParams());
        actions.addView(scanButton, weightParams());
        rootLayout.addView(actions);
        downloadsPageViews.add(actions);

        searchInput = input("");
        downloadsSection = searchInput;
        searchInput.setHint("Search videos");
        searchInput.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0);
        searchInput.setCompoundDrawablePadding(dp(8));
        searchInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                refreshLibrary();
            }
        });
        rootLayout.addView(searchInput, fullParams());
        downloadsPageViews.add(searchInput);

        TextView libraryTitle = sectionTitle("Videos on this device");
        rootLayout.addView(libraryTitle);
        downloadsPageViews.add(libraryTitle);
        libraryList = new LinearLayout(this);
        libraryList.setOrientation(LinearLayout.VERTICAL);
        rootLayout.addView(libraryList);
        downloadsPageViews.add(libraryList);
    }

    private void buildHistoryPage() {
        TextView historyTitle = sectionTitle("Continue watching");
        historySection = historyTitle;
        rootLayout.addView(historyTitle);
        historyPageViews.add(historyTitle);
        Button resetPlaylistHistory = secondaryButton("Reset current playlist history");
        resetPlaylistHistory.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_revert, 0, 0, 0);
        resetPlaylistHistory.setCompoundDrawablePadding(dp(8));
        resetPlaylistHistory.setOnClickListener(v -> confirmResetPlaylistHistory());
        rootLayout.addView(resetPlaylistHistory, fullParams());
        historyPageViews.add(resetPlaylistHistory);
        historyList = new LinearLayout(this);
        historyList.setOrientation(LinearLayout.VERTICAL);
        rootLayout.addView(historyList);
        historyPageViews.add(historyList);
    }

    private void buildDiagnosticsPage(int muted) {
        TextView debugTitle = sectionTitle("Diagnostics");
        debugSection = debugTitle;
        rootLayout.addView(debugTitle);
        morePageViews.add(debugTitle);
        LinearLayout debugPanel = panel();
        debugPanel.setOrientation(LinearLayout.VERTICAL);
        debugPanel.setPadding(dp(14), dp(12), dp(14), dp(12));
        debugText = text("", 13, muted, false);
        Button refreshDebug = secondaryButton("Refresh diagnostics");
        refreshDebug.setOnClickListener(v -> refreshDebugInfo());
        debugPanel.addView(debugText);
        debugPanel.addView(refreshDebug, fullParams());
        rootLayout.addView(debugPanel);
        morePageViews.add(debugPanel);
    }

    private LinearLayout buildMiniPlayer(int textColor, int muted) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(8), dp(6), dp(8), dp(5));
        container.setBackgroundResource(R.drawable.mini_player_bg);
        container.setVisibility(View.GONE);
        container.setClickable(true);
        container.setFocusable(true);
        container.setContentDescription("Open now playing");
        container.setOnClickListener(v -> showBottomTab("video", bottomVideoButton));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        miniPlayerThumbnail = thumbnailView(48, 48);
        content.addView(miniPlayerThumbnail, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(10), 0, dp(8), 0);
        miniPlayerTitle = text("", 14, textColor, true);
        miniPlayerTitle.setSingleLine(true);
        miniPlayerTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        miniPlayerMeta = text("", 12, muted, false);
        miniPlayerMeta.setSingleLine(true);
        miniPlayerMeta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(miniPlayerTitle);
        labels.addView(miniPlayerMeta);
        content.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        miniPlayerPlayPause = iconButton(android.R.drawable.ic_media_play, "Play", true);
        miniPlayerPlayPause.setOnClickListener(v -> togglePlayback());
        content.addView(miniPlayerPlayPause, new LinearLayout.LayoutParams(dp(48), dp(48)));
        container.addView(content);

        miniPlayerProgress = horizontalProgressBar();
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(3)
        );
        progressParams.setMargins(0, dp(5), 0, 0);
        container.addView(miniPlayerProgress, progressParams);
        return container;
    }

    private LinearLayout buildBottomNavigation() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(4), dp(6), dp(4), dp(6));
        nav.setBackgroundResource(R.drawable.bottom_nav_bg);

        bottomVideoButton = bottomNavButton("Player", android.R.drawable.ic_menu_slideshow);
        bottomPlaylistButton = bottomNavButton("Playlists", android.R.drawable.ic_menu_agenda);
        bottomDownloadsButton = bottomNavButton("Library", android.R.drawable.ic_menu_search);
        bottomHistoryButton = bottomNavButton("History", android.R.drawable.ic_menu_recent_history);
        bottomMoreButton = bottomNavButton("Settings", android.R.drawable.ic_menu_manage);

        bottomVideoButton.setOnClickListener(v -> showBottomTab("video", bottomVideoButton));
        bottomPlaylistButton.setOnClickListener(v -> {
            playlistDetailOpen = false;
            sourceToolsExpanded = false;
            showBottomTab("playlist", bottomPlaylistButton);
        });
        bottomDownloadsButton.setOnClickListener(v -> showBottomTab("downloads", bottomDownloadsButton));
        bottomHistoryButton.setOnClickListener(v -> showBottomTab("history", bottomHistoryButton));
        bottomMoreButton.setOnClickListener(v -> showBottomTab("more", bottomMoreButton));

        nav.addView(bottomVideoButton, navParams());
        nav.addView(bottomPlaylistButton, navParams());
        nav.addView(bottomDownloadsButton, navParams());
        nav.addView(bottomHistoryButton, navParams());
        nav.addView(bottomMoreButton, navParams());
        return nav;
    }

    private void showBottomTab(String tab, Button activeButton) {
        activeBottomTab = tab;
        if (!"playlist".equals(activeBottomTab)) {
            playlistDetailOpen = false;
            sourceToolsExpanded = false;
        }
        setBottomNavActive(activeButton);
        applyBottomTabVisibility();
        updateTopBarSubtitle();
        saveNavigationState();
        if (mainScrollView != null) {
            mainScrollView.post(() -> mainScrollView.smoothScrollTo(0, 0));
        }
    }

    private Button bottomButtonForTab(String tab) {
        if ("playlist".equals(tab)) return bottomPlaylistButton;
        if ("downloads".equals(tab)) return bottomDownloadsButton;
        if ("history".equals(tab)) return bottomHistoryButton;
        if ("more".equals(tab)) return bottomMoreButton;
        return bottomVideoButton;
    }

    private void loadNavigationState() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        activeBottomTab = prefs.getString(KEY_ACTIVE_TAB, "video");
        if (!"video".equals(activeBottomTab)
                && !"playlist".equals(activeBottomTab)
                && !"downloads".equals(activeBottomTab)
                && !"history".equals(activeBottomTab)
                && !"more".equals(activeBottomTab)) {
            activeBottomTab = "video";
        }
        playlistDetailOpen = prefs.getBoolean(KEY_PLAYLIST_DETAIL_OPEN, false) && "playlist".equals(activeBottomTab);
    }

    private void saveNavigationState() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_ACTIVE_TAB, activeBottomTab)
                .putBoolean(KEY_PLAYLIST_DETAIL_OPEN, playlistDetailOpen)
                .apply();
    }

    private void applyBottomTabVisibility() {
        if (videoFullscreen || inPictureInPicture) return;
        boolean videoActive = "video".equals(activeBottomTab);
        boolean playlistActive = "playlist".equals(activeBottomTab);
        boolean downloadsActive = "downloads".equals(activeBottomTab);
        boolean historyActive = "history".equals(activeBottomTab);
        boolean moreActive = "more".equals(activeBottomTab);
        boolean playlistDetailActive = playlistActive && playlistDetailOpen;
        setPageVisible(videoPageViews, videoActive);
        setPageVisible(playlistPageViews, playlistActive);
        setPageVisible(downloadsPageViews, downloadsActive);
        setPageVisible(historyPageViews, historyActive);
        setPageVisible(morePageViews, moreActive);
        setPageVisible(playlistHubViews, playlistActive && !playlistDetailOpen);
        setPageVisible(playlistDetailViews, playlistDetailActive);
        setPageVisible(playbackViews, videoActive);
        setPageVisible(settingsViews, videoActive || moreActive);
        if (sourceToolsPanel != null) {
            sourceToolsPanel.setVisibility(playlistDetailActive && sourceToolsExpanded ? View.VISIBLE : View.GONE);
        }
        updateTopBarSubtitle();
    }

    private void setPageVisible(ArrayList<View> pageViews, boolean visible) {
        for (View view : pageViews) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void applySystemBarPadding(View screenLayout) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT_WATCH || bottomNavBar == null) return;
        screenLayout.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottomInset;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bottomInset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            } else {
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            bottomNavBar.setPadding(dp(4), dp(6), dp(4), dp(6) + bottomInset);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(70) + bottomInset
            );
            bottomNavBar.setLayoutParams(params);
            return insets;
        });
        screenLayout.requestApplyInsets();
    }

    private Button bottomNavButton(String label, int iconResource) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(11);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, 0, 0, 0);
        button.setMinHeight(dp(52));
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setCompoundDrawablesWithIntrinsicBounds(0, iconResource, 0, 0);
        button.setCompoundDrawablePadding(dp(2));
        button.setContentDescription(label);
        return button;
    }

    private void setBottomNavActive(Button activeButton) {
        Button[] buttons = {bottomVideoButton, bottomPlaylistButton, bottomDownloadsButton, bottomHistoryButton, bottomMoreButton};
        for (Button button : buttons) {
            if (button == null) continue;
            boolean active = button == activeButton;
            int color = active ? Color.rgb(47, 209, 181) : Color.rgb(170, 182, 179);
            button.setTextColor(color);
            button.setCompoundDrawableTintList(ColorStateList.valueOf(color));
            button.setTypeface(active ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        }
    }

    private void updateTopBarSubtitle() {
        if (topBarSubtitle == null) return;
        if ("playlist".equals(activeBottomTab)) {
            topBarSubtitle.setText(playlistDetailOpen ? currentPlaylistName : "Playlists");
        } else if ("downloads".equals(activeBottomTab)) {
            topBarSubtitle.setText("Library");
        } else if ("history".equals(activeBottomTab)) {
            topBarSubtitle.setText("History");
        } else if ("more".equals(activeBottomTab)) {
            topBarSubtitle.setText("Settings");
        } else {
            topBarSubtitle.setText("Player");
        }
    }

    private void scrollToSection(View section) {
        if (mainScrollView == null || section == null) return;
        mainScrollView.post(() -> mainScrollView.smoothScrollTo(0, section.getTop()));
    }

    private void refreshDebugInfo() {
        if (debugText == null) return;
        int playlistCount = playerService == null ? 0 : playerService.getPlaylist().size();
        int libraryCount = libraryVideos.size();
        int historyCount = playerService == null ? 0 : playerService.getProgressSnapshot().size();
        String current = playerService == null || playerService.getCurrentItem() == null ? "None" : playerService.getCurrentItem().name;
        debugText.setText(
                "Version: 1.0.0\n"
                        + "Current playlist: " + currentPlaylistName + "\n"
                        + "Current video: " + current + "\n"
                        + "Playlist videos: " + playlistCount + "\n"
                        + "Phone videos scanned: " + libraryCount + "\n"
                        + "History entries: " + historyCount + "\n"
                        + "Media permission: " + mediaPermissionStatus() + "\n"
                        + "Last scan: " + lastScanMessage
        );
    }

    private String mediaPermissionStatus() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED) return "Granted";
            if (Build.VERSION.SDK_INT >= 34
                    && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED) {
                return "Selected videos";
            }
            return "Missing";
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ? "Granted" : "Missing";
    }

    private void requestNeededPermissions() {
        ArrayList<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            if (Build.VERSION.SDK_INT >= 34) {
                permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
            }
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
        playlistSourceLinks.clear();
        loadHistoryCatalog();
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
        try {
            JSONObject sources = new JSONObject(prefs.getString(KEY_PLAYLIST_SOURCES, "{}"));
            JSONArray names = sources.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String name = names.getString(i);
                    String link = sources.optString(name, "");
                    if (!link.trim().isEmpty()) {
                        playlistSourceLinks.put(name, link.trim());
                    }
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
        updateSourceInputForCurrentPlaylist();
        refreshPlaylistTabs();
    }

    private void loadCurrentPlaylistIntoService() {
        loadCurrentPlaylistIntoService(true);
    }

    private void loadCurrentPlaylistIntoService(boolean force) {
        if (playerService == null) return;
        if (!force && !playerService.getPlaylist().isEmpty()) return;
        ArrayList<VideoItem> playlist = savedPlaylists.get(currentPlaylistName);
        if (playlist == null || playlist.isEmpty()) {
            if (force) playerService.clearPlaylist();
            return;
        }
        playerService.replacePlaylist(playlist);
    }

    private void saveActivePlaylistFromService() {
        saveActivePlaylistFromService(false);
    }

    private void saveActivePlaylistFromService(boolean synchronous) {
        if (playerService == null) return;
        ArrayList<VideoItem> playlist = new ArrayList<>(playerService.getPlaylist());
        ArrayList<VideoItem> saved = savedPlaylists.get(currentPlaylistName);
        if (!synchronous && saved != null && saved.equals(playlist)) {
            return;
        }
        savedPlaylists.put(currentPlaylistName, playlist);
        saveAllPlaylists(synchronous);
    }

    private void saveAllPlaylists() {
        saveAllPlaylists(false);
    }

    private void saveAllPlaylists(boolean synchronous) {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, ArrayList<VideoItem>> entry : savedPlaylists.entrySet()) {
                JSONArray items = new JSONArray();
                for (VideoItem item : entry.getValue()) {
                    items.put(videoToJson(item));
                }
                root.put(entry.getKey(), items);
            }
            JSONObject sources = new JSONObject();
            for (Map.Entry<String, String> entry : playlistSourceLinks.entrySet()) {
                if (!entry.getValue().trim().isEmpty()) {
                    sources.put(entry.getKey(), entry.getValue().trim());
                }
            }
            SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_PLAYLISTS, root.toString())
                    .putString(KEY_PLAYLIST_SOURCES, sources.toString())
                    .putString(KEY_CURRENT_PLAYLIST, currentPlaylistName);
            if (synchronous) {
                editor.commit();
            } else {
                editor.apply();
            }
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

    private void loadHistoryCatalog() {
        historyCatalog.clear();
        try {
            JSONArray items = new JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_HISTORY_ITEMS, "[]"));
            for (int i = 0; i < items.length(); i++) {
                VideoItem item = videoFromJson(items.getJSONObject(i));
                historyCatalog.put(item.key(), item);
            }
        } catch (JSONException ignored) {
        }
    }

    private void recordHistoryCatalog() {
        if (playerService == null) return;
        boolean changed = false;
        for (VideoItem item : playerService.getPlaylist()) {
            if (!historyCatalog.containsKey(item.key())) {
                historyCatalog.put(item.key(), item);
                changed = true;
            }
        }
        VideoItem current = playerService.getCurrentItem();
        if (current != null && !historyCatalog.containsKey(current.key())) {
            historyCatalog.put(current.key(), current);
            changed = true;
        }
        if (changed) saveHistoryCatalog();
    }

    private void saveHistoryCatalog() {
        JSONArray items = new JSONArray();
        try {
            for (VideoItem item : historyCatalog.values()) {
                items.put(videoToJson(item));
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_HISTORY_ITEMS, items.toString())
                    .apply();
        } catch (JSONException ignored) {
        }
    }

    private void saveHistory() {
        saveHistory(false);
    }

    private void saveHistory(boolean synchronous) {
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
            SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_PROGRESS, progress.toString())
                    .putString(KEY_WATCHED, watched.toString());
            if (synchronous) {
                editor.commit();
            } else {
                editor.apply();
            }
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
        saveCurrentSourceFromInput();
        if (!savedPlaylists.containsKey(name)) {
            savedPlaylists.put(name, new ArrayList<>());
        }
        currentPlaylistName = name;
        updateSourceInputForCurrentPlaylist();
        loadCurrentPlaylistIntoService();
        saveAllPlaylists();
        playlistDetailOpen = true;
        sourceToolsExpanded = false;
        saveNavigationState();
        refreshAll();
        showBottomTab("playlist", bottomPlaylistButton);
    }

    private void deleteCurrentPlaylist() {
        if (savedPlaylists.size() <= 1) return;
        savedPlaylists.remove(currentPlaylistName);
        playlistSourceLinks.remove(currentPlaylistName);
        currentPlaylistName = savedPlaylists.keySet().iterator().next();
        playlistNameInput.setText(currentPlaylistName);
        updateSourceInputForCurrentPlaylist();
        loadCurrentPlaylistIntoService();
        saveAllPlaylists();
        playlistDetailOpen = false;
        saveNavigationState();
        refreshAll();
    }

    private void openPlaylistDetail(String name) {
        saveActivePlaylistFromService();
        saveCurrentSourceFromInput();
        if (!savedPlaylists.containsKey(name)) return;
        currentPlaylistName = name;
        playlistNameInput.setText(name);
        updateSourceInputForCurrentPlaylist();
        loadCurrentPlaylistIntoService();
        saveAllPlaylists();
        playlistDetailOpen = true;
        sourceToolsExpanded = false;
        saveNavigationState();
        refreshAll();
        showBottomTab("playlist", bottomPlaylistButton);
    }

    private void updateSourceInputForCurrentPlaylist() {
        if (sourceLinkInput == null) return;
        String link = playlistSourceLinks.get(currentPlaylistName);
        sourceLinkInput.setText(link == null ? "" : link);
    }

    private void saveCurrentSourceFromInput() {
        if (sourceLinkInput == null) return;
        String link = sourceLinkInput.getText().toString().trim();
        if (link.isEmpty()) {
            playlistSourceLinks.remove(currentPlaylistName);
        } else {
            playlistSourceLinks.put(currentPlaylistName, link);
        }
        saveAllPlaylists();
    }

    private String currentSourceLink() {
        if (sourceLinkInput != null) {
            String typed = sourceLinkInput.getText().toString().trim();
            if (!typed.isEmpty()) return typed;
        }
        String saved = playlistSourceLinks.get(currentPlaylistName);
        return saved == null ? "" : saved.trim();
    }

    private void openSourceEpisode(int offset) {
        String link = currentSourceLink();
        if (link.isEmpty()) return;
        if (offset != 0 && isDynamicEpisodeSource(link)) {
            Toast.makeText(this, "This site changes the episode link ID. Open source and use the site's next episode button.", Toast.LENGTH_LONG).show();
            openSourceEpisode(0);
            return;
        }
        String target = offset == 0 ? link : shiftEpisodeInLink(link, offset);
        if (target.isEmpty()) return;
        if (!target.equals(link) && sourceLinkInput != null) {
            sourceLinkInput.setText(target);
            saveCurrentSourceFromInput();
        }
        Intent intent = new Intent(this, WebSourceActivity.class);
        intent.putExtra("playlist", currentPlaylistName);
        intent.putExtra("url", target);
        startActivity(intent);
    }

    private void reloadPlaylistSourceLinks() {
        playlistSourceLinks.clear();
        try {
            JSONObject sources = new JSONObject(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PLAYLIST_SOURCES, "{}"));
            JSONArray names = sources.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String name = names.getString(i);
                    String link = sources.optString(name, "");
                    if (!link.trim().isEmpty()) {
                        playlistSourceLinks.put(name, link.trim());
                    }
                }
            }
        } catch (JSONException ignored) {
        }
    }

    private boolean isDynamicEpisodeSource(String link) {
        try {
            String host = Uri.parse(link).getHost();
            return host != null && host.toLowerCase(Locale.US).contains("movies.do");
        } catch (Exception ignored) {
            return false;
        }
    }

    private String shiftEpisodeInLink(String link, int offset) {
        Matcher pathMatcher = Pattern.compile("(?i)(/episode-)(\\d+)(?=/?(?:[?#].*)?$)").matcher(link);
        if (pathMatcher.find()) {
            int episode = parseInt(pathMatcher.group(2), -1);
            if (episode >= 0) {
                int shifted = Math.max(1, episode + offset);
                return link.substring(0, pathMatcher.start(2)) + shifted + link.substring(pathMatcher.end(2));
            }
        }

        Matcher queryMatcher = Pattern.compile("(?i)([?&][^=]*episode[^=]*=)(\\d+)").matcher(link);
        StringBuffer output = new StringBuffer();
        boolean found = false;
        while (queryMatcher.find()) {
            int episode = parseInt(queryMatcher.group(2), -1);
            if (episode >= 0) {
                int shifted = Math.max(1, episode + offset);
                queryMatcher.appendReplacement(output, Matcher.quoteReplacement(queryMatcher.group(1) + shifted));
                found = true;
            }
        }
        if (found) {
            queryMatcher.appendTail(output);
            return output.toString();
        }
        return link;
    }

    private void scanDownloadsIntoCurrentPlaylist() {
        if (playerService == null) return;
        reloadLibraryVideos();
        ArrayList<String> sourceTokens = sourceTokensForCurrentPlaylist();
        List<VideoItem> playlist = playerService.getPlaylist();
        ArrayList<String> playlistTokens = playlistTokens(playlist);
        int minimumEpisode = scanMinimumEpisode(playlist);
        Set<String> existing = new HashSet<>();
        for (VideoItem item : playlist) {
            existing.add(item.key());
        }

        ArrayList<VideoItem> candidates = new ArrayList<>();
        for (VideoItem item : libraryVideos) {
            if (existing.contains(item.key())) continue;
            int episode = episodeNumber(item.name);
            if (episode < 0) continue;
            if (minimumEpisode >= 0 && episode <= minimumEpisode) continue;
            if (matchesSeries(item.name, sourceTokens, playlistTokens)) {
                candidates.add(item);
            }
        }
        candidates.sort((first, second) -> {
            int episodeCompare = Integer.compare(episodeNumber(first.name), episodeNumber(second.name));
            return episodeCompare != 0 ? episodeCompare : first.name.compareToIgnoreCase(second.name);
        });

        refreshLibrary();
        if (candidates.isEmpty()) {
            lastScanMessage = minimumEpisode >= 0
                    ? "No newer downloaded episodes found after episode " + minimumEpisode
                    : "No new matching downloaded episodes found";
            Toast.makeText(this, lastScanMessage, Toast.LENGTH_SHORT).show();
            refreshDebugInfo();
            return;
        }
        showScanPreview(candidates);
    }

    private void showScanPreview(ArrayList<VideoItem> candidates) {
        StringBuilder message = new StringBuilder();
        int limit = Math.min(20, candidates.size());
        for (int i = 0; i < limit; i++) {
            VideoItem item = candidates.get(i);
            int episode = episodeNumber(item.name);
            message.append(episode >= 0 ? "Episode " + episode + ": " : "")
                    .append(item.name)
                    .append('\n');
        }
        if (candidates.size() > limit) {
            message.append("...and ").append(candidates.size() - limit).append(" more");
        }

        new AlertDialog.Builder(this)
                .setTitle("Add downloaded episodes?")
                .setMessage(message.toString())
                .setPositiveButton("Add", (dialog, which) -> addScanCandidates(candidates))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addScanCandidates(ArrayList<VideoItem> candidates) {
        if (playerService == null) return;
        for (VideoItem item : candidates) {
            playerService.addToPlaylist(item);
        }
        sortCurrentPlaylistByWatchOrder();
        saveActivePlaylistFromService();
        lastScanMessage = "Added " + candidates.size() + " downloaded episodes";
        refreshPlaylist();
        refreshPlaylistTabs();
        refreshLibrary();
        refreshDebugInfo();
        Toast.makeText(this, lastScanMessage, Toast.LENGTH_SHORT).show();
    }

    private void addMatchingSeriesFromFirstVideo() {
        if (playerService == null) return;
        List<VideoItem> playlist = playerService.getPlaylist();
        if (playlist.isEmpty()) {
            Toast.makeText(this, "Add one video first so the app knows the series name.", Toast.LENGTH_LONG).show();
            return;
        }
        reloadLibraryVideos();
        ArrayList<String> tokens = playlistTokens(playlist);
        if (tokens.isEmpty()) {
            Toast.makeText(this, "Could not detect a series name from the first video.", Toast.LENGTH_LONG).show();
            return;
        }
        Set<String> existing = new HashSet<>();
        for (VideoItem item : playlist) {
            existing.add(item.key());
        }

        ArrayList<VideoItem> candidates = new ArrayList<>();
        for (VideoItem item : libraryVideos) {
            if (existing.contains(item.key())) continue;
            if (matchesSeries(item.name, new ArrayList<>(), tokens)) {
                candidates.add(item);
            }
        }
        candidates.sort((first, second) -> {
            int firstEpisode = episodeNumber(first.name);
            int secondEpisode = episodeNumber(second.name);
            if (firstEpisode >= 0 && secondEpisode >= 0 && firstEpisode != secondEpisode) {
                return Integer.compare(firstEpisode, secondEpisode);
            }
            if (firstEpisode >= 0 && secondEpisode < 0) return -1;
            if (firstEpisode < 0 && secondEpisode >= 0) return 1;
            return first.name.compareToIgnoreCase(second.name);
        });

        refreshLibrary();
        if (candidates.isEmpty()) {
            lastScanMessage = "No matching files found for " + playlist.get(0).name;
            Toast.makeText(this, lastScanMessage, Toast.LENGTH_SHORT).show();
            refreshDebugInfo();
            return;
        }
        showScanPreview(candidates);
    }

    private ArrayList<String> sourceTokensForCurrentPlaylist() {
        ArrayList<String> tokens = new ArrayList<>();
        String link = currentSourceLink();
        if (link.isEmpty()) return tokens;
        String segment = "";
        try {
            String path = Uri.parse(link).getPath();
            if (path != null) {
                for (String part : path.split("/")) {
                    String normalized = part.toLowerCase(Locale.US);
                    if (normalized.contains("-") && !normalized.startsWith("episode")) {
                        segment = part;
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (segment.isEmpty()) segment = link;
        return significantTokens(segment);
    }

    private ArrayList<String> playlistTokens(List<VideoItem> playlist) {
        if (playlist == null || playlist.isEmpty()) return new ArrayList<>();
        return significantTokens(playlist.get(0).name);
    }

    private boolean matchesSeries(String fileName, ArrayList<String> sourceTokens, ArrayList<String> playlistTokens) {
        return EpisodeNameParser.matchesSeries(fileName, sourceTokens, playlistTokens);
    }

    private ArrayList<String> significantTokens(String value) {
        return EpisodeNameParser.significantTokens(value);
    }

    private String normalizeForMatch(String value) {
        return EpisodeNameParser.normalize(value);
    }

    private int episodeNumber(String value) {
        return EpisodeNameParser.episodeNumber(value);
    }

    private int scanMinimumEpisode(List<VideoItem> playlist) {
        VideoItem current = playerService == null ? null : playerService.getCurrentItem();
        int currentEpisode = current == null ? -1 : episodeNumber(current.name);
        if (currentEpisode >= 0) return currentEpisode;

        int highest = -1;
        if (playlist != null) {
            for (VideoItem item : playlist) {
                highest = Math.max(highest, episodeNumber(item.name));
            }
        }
        return highest;
    }

    private void sortCurrentPlaylistByWatchOrder() {
        if (playerService == null) return;
        List<VideoItem> sorted = playerService.getPlaylist();
        if (sorted.size() < 2) return;
        sorted.sort((first, second) -> {
            int firstEpisode = episodeNumber(first.name);
            int secondEpisode = episodeNumber(second.name);
            if (firstEpisode >= 0 && secondEpisode >= 0 && firstEpisode != secondEpisode) {
                return Integer.compare(firstEpisode, secondEpisode);
            }
            if (firstEpisode >= 0 && secondEpisode < 0) return -1;
            if (firstEpisode < 0 && secondEpisode >= 0) return 1;
            return first.name.compareToIgnoreCase(second.name);
        });
        playerService.replacePlaylist(sorted);
    }

    private void sortPlaylist(String mode) {
        if (playerService == null) return;
        List<VideoItem> sorted = playerService.getPlaylist();
        if (sorted.size() < 2) return;
        if ("name".equals(mode)) {
            sorted.sort((first, second) -> first.name.compareToIgnoreCase(second.name));
        } else if ("date".equals(mode)) {
            reloadLibraryVideos();
            sorted.sort((first, second) -> Integer.compare(libraryOrder(first), libraryOrder(second)));
        } else {
            sorted.sort((first, second) -> {
                int firstEpisode = episodeNumber(first.name);
                int secondEpisode = episodeNumber(second.name);
                if (firstEpisode >= 0 && secondEpisode >= 0 && firstEpisode != secondEpisode) {
                    return Integer.compare(firstEpisode, secondEpisode);
                }
                if (firstEpisode >= 0 && secondEpisode < 0) return -1;
                if (firstEpisode < 0 && secondEpisode >= 0) return 1;
                return first.name.compareToIgnoreCase(second.name);
            });
        }
        playerService.replacePlaylist(sorted);
        saveActivePlaylistFromService();
        refreshPlaylist();
        refreshDebugInfo();
    }

    private int libraryOrder(VideoItem target) {
        for (int i = 0; i < libraryVideos.size(); i++) {
            if (libraryVideos.get(i).equals(target)) return i;
        }
        return Integer.MAX_VALUE;
    }

    private void movePlaylistItem(int fromIndex, int direction) {
        if (playerService == null) return;
        List<VideoItem> items = playerService.getPlaylist();
        int toIndex = fromIndex + direction;
        if (fromIndex < 0 || fromIndex >= items.size() || toIndex < 0 || toIndex >= items.size()) return;
        VideoItem moved = items.remove(fromIndex);
        items.add(toIndex, moved);
        playerService.replacePlaylist(items);
        saveActivePlaylistFromService();
        refreshPlaylist();
    }

    private void buildVideoOverlay(int textColor) {
        videoControlsOverlay = new LinearLayout(this);
        videoControlsOverlay.setOrientation(LinearLayout.VERTICAL);
        videoControlsOverlay.setGravity(Gravity.CENTER_VERTICAL);
        videoControlsOverlay.setPadding(dp(8), dp(6), dp(8), dp(8));
        videoControlsOverlay.setBackgroundColor(Color.argb(190, 0, 0, 0));
        videoControlsOverlay.setVisibility(View.GONE);

        LinearLayout overlayButtonRow = row();
        overlayButtonRow.setGravity(Gravity.CENTER_VERTICAL);

        overlayPlayPauseButton = iconButton(android.R.drawable.ic_media_play, "Play", true);
        overlayPlayPauseButton.setOnClickListener(v -> togglePlayback());
        overlayButtonRow.addView(overlayPlayPauseButton, overlayButtonParams());

        overlayPipButton = iconButton(android.R.drawable.ic_menu_slideshow, "Picture in picture", false);
        overlayPipButton.setOnClickListener(v -> enterVideoPictureInPicture());
        overlayButtonRow.addView(overlayPipButton, overlayButtonParams());

        overlayAudioButton = iconButton(android.R.drawable.ic_lock_silent_mode_off, "Audio only", false);
        overlayAudioButton.setOnClickListener(v -> enterAudioOnlyMode());
        overlayButtonRow.addView(overlayAudioButton, overlayButtonParams());

        overlayLockButton = iconButton(android.R.drawable.ic_lock_lock, "Lock controls", false);
        overlayLockButton.setOnClickListener(v -> setControlsLocked(!controlsLocked));
        overlayButtonRow.addView(overlayLockButton, overlayButtonParams());
        videoControlsOverlay.addView(overlayButtonRow, fullParams());

        videoProgressBar = new SeekBar(this);
        videoProgressBar.setMax(0);
        videoProgressBar.setProgress(0);
        videoProgressBar.setProgressTintList(ColorStateList.valueOf(Color.rgb(47, 209, 181)));
        videoProgressBar.setThumbTintList(ColorStateList.valueOf(Color.rgb(47, 209, 181)));
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

        LinearLayout overlayProgressRow = row();
        overlayProgressRow.setGravity(Gravity.CENTER_VERTICAL);
        overlayProgressRow.setPadding(0, dp(6), 0, 0);
        overlayProgressRow.addView(videoProgressBar, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        ));

        videoProgressText = text("0:00 / 0:00", 12, textColor, false);
        videoProgressText.setGravity(Gravity.CENTER_VERTICAL);
        overlayProgressRow.addView(videoProgressText);
        videoControlsOverlay.addView(overlayProgressRow, fullParams());

        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        videoContainer.addView(videoControlsOverlay, overlayParams);
    }

    private LinearLayout.LayoutParams overlayButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        );
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private void setupVideoGestures() {
        videoGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent event) {
                if (controlsLocked) {
                    setVideoControlsVisible(true);
                    Toast.makeText(MainActivity.this, "Controls locked", Toast.LENGTH_SHORT).show();
                    return true;
                }
                toggleVideoControls();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent event) {
                if (controlsLocked) return true;
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

        textureView.setContentDescription("Video player");
        videoContainer.setContentDescription("Video player");
        textureView.setOnTouchListener((view, event) -> {
            boolean handled = videoGestureDetector.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_UP) view.performClick();
            return handled;
        });
        videoContainer.setOnTouchListener((view, event) -> {
            boolean handled = videoGestureDetector.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_UP) view.performClick();
            return handled;
        });
    }

    private void setVideoFullscreen(boolean fullscreen) {
        videoFullscreen = fullscreen;
        int height = fullscreen ? getResources().getDisplayMetrics().heightPixels : dp(218);
        videoContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height
        ));

        if (fullscreen) {
            videoContainer.setVisibility(View.VISIBLE);
            for (int i = 0; i < rootLayout.getChildCount(); i++) {
                View child = rootLayout.getChildAt(i);
                if (child != videoContainer) {
                    child.setVisibility(View.GONE);
                }
            }
        } else {
            applyBottomTabVisibility();
        }

        rootLayout.setPadding(
                fullscreen ? 0 : dp(14),
                fullscreen ? 0 : dp(8),
                fullscreen ? 0 : dp(14),
                fullscreen ? 0 : dp(22)
        );
        if (bottomNavBar != null) {
            bottomNavBar.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        }
        if (topBar != null) topBar.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        if (miniPlayer != null) {
            miniPlayer.setVisibility(fullscreen || playerService == null || playerService.getCurrentItem() == null ? View.GONE : View.VISIBLE);
        }

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

    private void enterVideoPictureInPicture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || videoContainer == null) return;
        setVideoControlsVisible(false);
        Rational aspect = pictureInPictureAspect();
        Rect sourceRect = new Rect();
        videoContainer.getGlobalVisibleRect(sourceRect);
        PictureInPictureParams params = new PictureInPictureParams.Builder()
                .setAspectRatio(aspect)
                .setSourceRectHint(sourceRect)
                .build();
        enterPictureInPictureMode(params);
    }

    private Rational pictureInPictureAspect() {
        int width = playerService == null ? 0 : playerService.getVideoWidth();
        int height = playerService == null ? 0 : playerService.getVideoHeight();
        if (width <= 0 || height <= 0) {
            width = Math.max(1, videoContainer.getWidth());
            height = Math.max(1, videoContainer.getHeight());
        }
        float ratio = width / (float) height;
        if (ratio > 2.39f) {
            width = 239;
            height = 100;
        } else if (ratio < 0.42f) {
            width = 42;
            height = 100;
        }
        return new Rational(width, height);
    }

    private void applyPictureInPictureUi() {
        if (videoContainer == null || rootLayout == null) return;
        if (inPictureInPicture) {
            videoContainer.setVisibility(View.VISIBLE);
            for (int i = 0; i < rootLayout.getChildCount(); i++) {
                View child = rootLayout.getChildAt(i);
                if (child != videoContainer) {
                    child.setVisibility(View.GONE);
                }
            }
        } else {
            applyBottomTabVisibility();
        }
        videoControlsOverlay.setVisibility(View.GONE);
        videoContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                inPictureInPicture ? LinearLayout.LayoutParams.MATCH_PARENT : (videoFullscreen ? getResources().getDisplayMetrics().heightPixels : dp(218))
        ));
        rootLayout.setPadding(
                inPictureInPicture || videoFullscreen ? 0 : dp(14),
                inPictureInPicture || videoFullscreen ? 0 : dp(8),
                inPictureInPicture || videoFullscreen ? 0 : dp(14),
                inPictureInPicture || videoFullscreen ? 0 : dp(22)
        );
        if (bottomNavBar != null) {
            bottomNavBar.setVisibility(inPictureInPicture || videoFullscreen ? View.GONE : View.VISIBLE);
        }
        if (topBar != null) topBar.setVisibility(inPictureInPicture || videoFullscreen ? View.GONE : View.VISIBLE);
        if (miniPlayer != null) {
            miniPlayer.setVisibility(inPictureInPicture || videoFullscreen || playerService == null || playerService.getCurrentItem() == null ? View.GONE : View.VISIBLE);
        }
        videoContainer.post(this::applyVideoAspectTransform);
        if (!inPictureInPicture) {
            applyOrientationFullscreen();
        }
    }

    private void applyOrientationFullscreen() {
        if (inPictureInPicture) return;
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

    private void setPlaybackSpeed(float speed) {
        if (playerService == null) return;
        playerService.setPlaybackSpeed(speed);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(KEY_PLAYBACK_SPEED, speed).apply();
        if (speedMenuButton != null) speedMenuButton.setText(speedText(speed));
        Toast.makeText(this, "Speed " + speedText(speed), Toast.LENGTH_SHORT).show();
        refreshPlayerHeader();
    }

    private void showSpeedDialog() {
        float[] speeds = {0.75f, 1f, 1.25f, 1.5f, 2f};
        String[] labels = new String[speeds.length];
        int selected = 0;
        float current = playerService == null
                ? getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(KEY_PLAYBACK_SPEED, 1f)
                : playerService.getPlaybackSpeed();
        for (int i = 0; i < speeds.length; i++) {
            labels[i] = speedText(speeds[i]);
            if (Math.abs(speeds[i] - current) < 0.01f) selected = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Playback speed")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    setPlaybackSpeed(speeds[which]);
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSortDialog() {
        String[] labels = {"Episode number", "Name", "Recently downloaded"};
        new AlertDialog.Builder(this)
                .setTitle("Sort playlist")
                .setItems(labels, (dialog, which) -> {
                    if (which == 0) sortPlaylist("episode");
                    else if (which == 1) sortPlaylist("name");
                    else sortPlaylist("date");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void playCurrentPlaylist() {
        if (playerService == null || playerService.getPlaylist().isEmpty()) return;
        startPlaybackServiceIfNeeded();
        int target = Math.max(0, playerService.getCurrentIndex());
        List<VideoItem> items = playerService.getPlaylist();
        if (target >= items.size() || playerService.isWatched(items.get(target))) {
            for (int i = 0; i < items.size(); i++) {
                if (!playerService.isWatched(items.get(i))) {
                    target = i;
                    break;
                }
            }
        }
        if (target == playerService.getCurrentIndex()) playerService.play();
        else playerService.playIndex(target);
        showBottomTab("video", bottomVideoButton);
    }

    private void confirmDeleteCurrentPlaylist() {
        if (savedPlaylists.size() <= 1) {
            Toast.makeText(this, "Keep at least one playlist", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete " + currentPlaylistName + "?")
                .setMessage("Videos stay on your phone. Only this playlist is removed.")
                .setPositiveButton("Delete", (dialog, which) -> deleteCurrentPlaylist())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmResetPlaylistHistory() {
        new AlertDialog.Builder(this)
                .setTitle("Reset playlist progress?")
                .setMessage("Every episode in " + currentPlaylistName + " will start from the saved intro-skip time again.")
                .setPositiveButton("Reset", (dialog, which) -> resetCurrentPlaylistHistory())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAudioTrackDialog() {
        if (playerService == null) return;
        List<String> tracks = playerService.getAudioTrackLabels();
        if (tracks.isEmpty()) {
            Toast.makeText(this, "No alternate audio tracks found", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = tracks.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Choose audio track")
                .setItems(labels, (dialog, which) -> {
                    playerService.selectAudioTrack(which);
                    savePreferredAudioTrack();
                    Toast.makeText(this, "Saved " + labels[which] + " for next episodes", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Use default", (dialog, which) -> {
                    playerService.clearPreferredAudioTrack();
                    savePreferredAudioTrack();
                    Toast.makeText(this, "Audio preference cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String speedText(float speed) {
        if (Math.abs(speed - Math.round(speed)) < 0.01f) {
            return String.format(Locale.US, "%.0fx", speed);
        }
        return String.format(Locale.US, "%.2fx", speed).replace("0x", "x");
    }

    private void enterAudioOnlyMode() {
        audioOnlyMode = true;
        setVideoControlsVisible(false);
        detachPlaybackSurface();
        if (playerService != null && playerService.isPlaying()) {
            startPlaybackServiceIfNeeded();
        }
        moveTaskToBack(true);
    }

    private void setControlsLocked(boolean locked) {
        controlsLocked = locked;
        if (overlayLockButton != null) {
            overlayLockButton.setImageResource(android.R.drawable.ic_lock_lock);
            overlayLockButton.setColorFilter(locked ? Color.rgb(255, 180, 91) : Color.rgb(244, 248, 247));
            overlayLockButton.setContentDescription(locked ? "Unlock controls" : "Lock controls");
        }
        if (videoProgressBar != null) {
            videoProgressBar.setEnabled(!locked);
        }
        if (overlayPlayPauseButton != null) {
            overlayPlayPauseButton.setEnabled(!locked);
        }
        Toast.makeText(this, locked ? "Controls locked" : "Controls unlocked", Toast.LENGTH_SHORT).show();
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
        boolean playing = playerService.isPlaying();
        overlayPlayPauseButton.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        overlayPlayPauseButton.setContentDescription(playing ? "Pause" : "Play");
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
        if (!audioOnlyMode && playerService != null && playbackSurface != null && playbackSurface.isValid()) {
            playerService.setOutputSurface(playbackSurface);
        }
    }

    private void detachPlaybackSurface() {
        if (playerService != null && playbackSurface != null) {
            playerService.clearOutputSurface(playbackSurface);
        }
    }

    private void releasePlaybackSurface() {
        if (playbackSurface != null) {
            playbackSurface.release();
            playbackSurface = null;
        }
    }

    private void loadLibraryVideos() {
        if (libraryList != null) {
            libraryList.removeAllViews();
            libraryList.addView(emptyText("Scanning device..."));
        }
        libraryExecutor.execute(() -> {
            ArrayList<VideoItem> loaded = queryLibraryVideos();
            progressHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                libraryVideos.clear();
                libraryVideos.addAll(loaded);
                lastScanMessage = "Found " + loaded.size() + " videos";
                refreshLibrary();
                refreshDebugInfo();
            });
        });
    }

    private void reloadLibraryVideos() {
        ArrayList<VideoItem> loaded = queryLibraryVideos();
        libraryVideos.clear();
        libraryVideos.addAll(loaded);
    }

    private ArrayList<VideoItem> queryLibraryVideos() {
        ArrayList<VideoItem> loaded = new ArrayList<>();
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
                return loaded;
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
                loaded.add(new VideoItem(contentUri, name == null ? "Video" : name, duration, size));
            }
        } catch (SecurityException ignored) {
            // Permission was denied; users can still use Pick videos.
        }
        return loaded;
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
        askPlaylistForVideo(new VideoItem(uri, name, 0, 0));
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
                .apply();
    }

    private int loadPreferredAudioTrackIndex() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_AUDIO_TRACK_INDEX, -1);
    }

    private String loadPreferredAudioTrackLanguage() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_AUDIO_TRACK_LANGUAGE, "");
    }

    private void savePreferredAudioTrack() {
        if (playerService == null) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_AUDIO_TRACK_INDEX, playerService.getPreferredAudioTrackIndex())
                .putString(KEY_AUDIO_TRACK_LANGUAGE, playerService.getPreferredAudioLanguage())
                .apply();
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

    private int parseInt(String raw, int fallback) {
        try {
            return raw == null ? fallback : Integer.parseInt(raw);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private void refreshAll() {
        refreshPlayerHeader();
        refreshPlaylistTabs();
        refreshLibrary();
        refreshPlaylist();
        refreshHistory();
        refreshDebugInfo();
    }

    private void refreshPlayerHeader() {
        if (playerService == null) return;
        VideoItem current = playerService.getCurrentItem();
        nowPlayingText.setText(current == null ? "Nothing loaded" : current.name);
        int index = playerService.getCurrentIndex();
        int total = playerService.getPlaylist().size();
        counterText.setText(total == 0
                ? "Choose a playlist or add videos from Library"
                : String.format(Locale.US, "%s  |  Episode %d of %d", currentPlaylistName, index + 1, total));
        boolean playing = playerService.isPlaying();
        playPauseButton.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        playPauseButton.setContentDescription(playing ? "Pause" : "Play");
        refreshMiniPlayer(current, playing);
        updateVideoControls();
        updateScreenAwakeState();
    }

    private void refreshMiniPlayer(VideoItem current, boolean playing) {
        if (miniPlayer == null) return;
        if (current == null || inPictureInPicture || videoFullscreen) {
            miniPlayer.setVisibility(View.GONE);
            return;
        }
        miniPlayer.setVisibility(View.VISIBLE);
        miniPlayerTitle.setText(displayEpisodeTitle(current));
        miniPlayerMeta.setText(currentPlaylistName + "  |  " + formatTime(playerService.getCurrentPositionMs())
                + " / " + formatTime(playerService.getDurationMs()));
        miniPlayerPlayPause.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        miniPlayerPlayPause.setContentDescription(playing ? "Pause" : "Play");
        int duration = Math.max(0, playerService.getDurationMs());
        int position = Math.max(0, playerService.getCurrentPositionMs());
        miniPlayerProgress.setMax(Math.max(1, duration));
        miniPlayerProgress.setProgress(Math.min(position, Math.max(1, duration)));
        bindThumbnail(miniPlayerThumbnail, current);
    }

    private void refreshLibrary() {
        libraryList.removeAllViews();
        String query = searchInput == null ? "" : searchInput.getText().toString().trim().toLowerCase(Locale.US);
        int shown = 0;
        for (VideoItem item : libraryVideos) {
            if (!query.isEmpty() && !item.name.toLowerCase(Locale.US).contains(query)) continue;
            libraryList.addView(videoRow(item, "Add", false, "", v -> askPlaylistForVideo(item)));
            shown++;
            if (shown >= 120) break;
        }
        if (shown == 0) {
            libraryList.addView(emptyText("No scanned videos found. Use Pick videos to choose files manually."));
        }
    }

    private void askPlaylistForVideo(VideoItem item) {
        if (item == null) return;
        if (savedPlaylists.isEmpty()) {
            savedPlaylists.put("Default", new ArrayList<>());
        }
        String[] names = savedPlaylists.keySet().toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Add to playlist")
                .setItems(names, (dialog, which) -> addVideoToNamedPlaylist(names[which], item))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addVideoToNamedPlaylist(String playlistName, VideoItem item) {
        if (playlistName == null || item == null) return;
        ArrayList<VideoItem> playlist = savedPlaylists.get(playlistName);
        if (playlist == null) {
            playlist = new ArrayList<>();
            savedPlaylists.put(playlistName, playlist);
        }

        boolean added = false;
        if (playlistName.equals(currentPlaylistName) && playerService != null) {
            if (!playerService.getPlaylist().contains(item)) {
                playerService.addToPlaylist(item);
                added = true;
            }
            saveActivePlaylistFromService();
        } else if (!playlist.contains(item)) {
            playlist.add(item);
            added = true;
            saveAllPlaylists();
        }

        refreshPlaylist();
        refreshPlaylistTabs();
        refreshLibrary();
        Toast.makeText(this, added ? "Added to " + playlistName : "Already in " + playlistName, Toast.LENGTH_SHORT).show();
    }

    private void refreshPlaylist() {
        playlistList.removeAllViews();
        if (playerService == null || playerService.getPlaylist().isEmpty()) {
            playlistList.addView(emptyText("No episodes in this playlist."));
            refreshPlayerHeader();
            return;
        }

        List<VideoItem> playlist = playerService.getPlaylist();
        int current = playerService.getCurrentIndex();
        for (int i = 0; i < playlist.size(); i++) {
            int index = i;
            VideoItem item = playlist.get(i);
            boolean watched = playerService.isWatched(item);
            playlistList.addView(playlistEpisodeRow(item, watched, index, index == current));
        }
        refreshPlayerHeader();
    }

    private void refreshPlaylistTabs() {
        if (playlistTabs == null) return;
        playlistTabs.removeAllViews();
        if (playlistDetailTitle != null) {
            playlistDetailTitle.setText(currentPlaylistName);
        }
        if (playlistDetailMeta != null) {
            playlistDetailMeta.setText(playlistSummary(savedPlaylists.get(currentPlaylistName)));
        }
        updateTopBarSubtitle();
        for (String name : savedPlaylists.keySet()) {
            playlistTabs.addView(playlistCard(name, savedPlaylists.get(name)));
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
        for (VideoItem item : historyCatalog.values()) known.put(item.key(), item);
        for (ArrayList<VideoItem> playlist : savedPlaylists.values()) {
            for (VideoItem item : playlist) known.put(item.key(), item);
        }
        for (VideoItem item : playerService.getPlaylist()) known.put(item.key(), item);
        for (VideoItem item : libraryVideos) known.put(item.key(), item);

        int shown = 0;
        for (VideoItem item : known.values()) {
            int progress = progressFor(item);
            if (!playerService.isWatched(item) && progress <= 0) continue;
            historyList.addView(historyVideoRow(item));
            shown++;
        }

        if (shown == 0) {
            historyList.addView(emptyText("Nothing watched yet."));
        }
    }

    private void resetVideoHistory(VideoItem item) {
        if (playerService == null || item == null) return;
        playerService.clearHistory(item);
        historyCatalog.remove(item.key());
        saveHistoryCatalog();
        saveHistory();
        refreshPlaylist();
        refreshHistory();
        refreshLibrary();
        refreshPlayerHeader();
    }

    private void resetCurrentPlaylistHistory() {
        if (playerService == null) return;
        for (VideoItem item : playerService.getPlaylist()) {
            playerService.clearHistory(item);
            historyCatalog.remove(item.key());
        }
        saveHistoryCatalog();
        saveHistory();
        refreshPlaylist();
        refreshHistory();
        refreshDebugInfo();
        Toast.makeText(this, "Playlist history reset", Toast.LENGTH_SHORT).show();
    }

    private LinearLayout videoRow(VideoItem item, String actionText, boolean watched, String prefix, View.OnClickListener action) {
        LinearLayout row = panel();
        if (watched) {
            row.setBackgroundResource(R.drawable.panel_watched_bg);
        }
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));

        ImageView thumbnail = thumbnailView(82, 52);
        bindThumbnail(thumbnail, item);
        row.addView(thumbnail, new LinearLayout.LayoutParams(dp(82), dp(52)));

        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        textBlock.setPadding(dp(10), 0, dp(8), 0);
        TextView name = text(prefix + displayEpisodeTitle(item), 14, watched ? Color.rgb(158, 166, 176) : Color.rgb(244, 248, 247), true);
        name.setMaxLines(2);
        TextView meta = text(watched ? "Watched  |  " + formatMeta(item) : formatMeta(item), 12, Color.rgb(170, 182, 179), false);
        textBlock.addView(name);
        textBlock.addView(meta);
        row.addView(textBlock, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        ImageButton actionButton = iconButton(android.R.drawable.ic_menu_add, actionText, true);
        actionButton.setOnClickListener(action);
        row.addView(actionButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return row;
    }

    private LinearLayout playlistCard(String name, ArrayList<VideoItem> playlist) {
        LinearLayout card = panel();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription("Open playlist " + name);
        card.setOnClickListener(v -> openPlaylistDetail(name));

        ImageView cover = thumbnailView(88, 88);
        if (playlist != null && !playlist.isEmpty()) bindThumbnail(cover, playlist.get(0));
        card.addView(cover, new LinearLayout.LayoutParams(dp(88), dp(88)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, dp(8), 0);
        TextView nameView = text(name, 18, name.equals(currentPlaylistName) ? Color.rgb(47, 209, 181) : Color.rgb(244, 248, 247), true);
        TextView metaView = text(playlistSummary(playlist), 13, Color.rgb(170, 182, 179), false);
        labels.addView(nameView);
        labels.addView(metaView);
        if (name.equals(currentPlaylistName)) {
            TextView current = text("Current playlist", 11, Color.rgb(47, 209, 181), true);
            current.setBackgroundResource(R.drawable.badge_current_bg);
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            badgeParams.setMargins(0, dp(6), 0, 0);
            labels.addView(current, badgeParams);
        }
        card.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        ImageButton play = iconButton(android.R.drawable.ic_media_play, "Play playlist " + name, true);
        play.setOnClickListener(v -> {
            openPlaylistDetail(name);
            if (playerService != null && !playerService.getPlaylist().isEmpty()) {
                startPlaybackServiceIfNeeded();
                playerService.playIndex(0);
                showBottomTab("video", bottomVideoButton);
            }
        });
        card.addView(play, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return card;
    }

    private String playlistSummary(ArrayList<VideoItem> playlist) {
        int count = playlist == null ? 0 : playlist.size();
        long duration = 0;
        if (playlist != null) {
            for (VideoItem item : playlist) {
                duration += Math.max(0, item.durationMs);
            }
        }
        String episodeLabel = count == 1 ? "1 episode" : count + " episodes";
        return duration > 0 ? episodeLabel + "  |  " + formatDuration(duration) : episodeLabel;
    }

    private LinearLayout playlistEpisodeRow(VideoItem item, boolean watched, int index, boolean current) {
        LinearLayout card = panel();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(8), dp(8), dp(6), dp(8));
        if (watched) card.setBackgroundResource(R.drawable.panel_watched_bg);
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription((current ? "Current episode. " : "") + displayEpisodeTitle(item));
        card.setOnClickListener(v -> playPlaylistIndex(index));

        ImageView thumbnail = thumbnailView(94, 60);
        bindThumbnail(thumbnail, item);
        card.addView(thumbnail, new LinearLayout.LayoutParams(dp(94), dp(60)));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), 0, dp(6), 0);
        TextView title = text(displayEpisodeTitle(item), 14,
                watched ? Color.rgb(158, 166, 176) : Color.rgb(244, 248, 247), true);
        title.setMaxLines(2);
        content.addView(title);

        String state = current
                ? (playerService != null && playerService.isPlaying() ? "Now playing" : "Current")
                : (watched ? "Watched" : progressLabel(item));
        if (state == null || state.isEmpty()) state = formatMeta(item);
        TextView meta = text(state + "  |  " + mediaDurationLabel(item), 12,
                current ? Color.rgb(47, 209, 181) : Color.rgb(170, 182, 179), false);
        content.addView(meta);

        int duration = durationFor(item);
        int progress = progressFor(item);
        if (progress > 0 && duration > 0) {
            ProgressBar progressBar = horizontalProgressBar();
            progressBar.setMax(duration);
            progressBar.setProgress(Math.min(progress, duration));
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(3)
            );
            progressParams.setMargins(0, dp(6), 0, 0);
            content.addView(progressBar, progressParams);
        }
        card.addView(content, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        ImageButton menu = iconButton(android.R.drawable.ic_menu_more, "Episode options", false);
        menu.setOnClickListener(v -> showEpisodeMenu(menu, index, item));
        card.addView(menu, new LinearLayout.LayoutParams(dp(46), dp(46)));
        return card;
    }

    private LinearLayout historyVideoRow(VideoItem item) {
        LinearLayout card = panel();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(8), dp(8), dp(6), dp(8));
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription("Resume " + displayEpisodeTitle(item));
        card.setOnClickListener(v -> playHistoryItem(item));

        ImageView thumbnail = thumbnailView(94, 60);
        bindThumbnail(thumbnail, item);
        card.addView(thumbnail, new LinearLayout.LayoutParams(dp(94), dp(60)));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), 0, dp(6), 0);
        TextView title = text(displayEpisodeTitle(item), 14, Color.rgb(244, 248, 247), true);
        title.setMaxLines(2);
        content.addView(title);
        String state = playerService.isWatched(item) ? "Watched" : progressLabel(item);
        content.addView(text(state + "  |  " + mediaDurationLabel(item), 12, Color.rgb(170, 182, 179), false));
        int duration = durationFor(item);
        int progress = progressFor(item);
        if (progress > 0 && duration > 0) {
            ProgressBar progressBar = horizontalProgressBar();
            progressBar.setMax(duration);
            progressBar.setProgress(Math.min(progress, duration));
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(3)
            );
            progressParams.setMargins(0, dp(6), 0, 0);
            content.addView(progressBar, progressParams);
        }
        card.addView(content, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        ImageButton reset = iconButton(android.R.drawable.ic_menu_revert, "Reset progress", false);
        reset.setOnClickListener(v -> resetVideoHistory(item));
        card.addView(reset, new LinearLayout.LayoutParams(dp(46), dp(46)));
        return card;
    }

    private void showEpisodeMenu(View anchor, int index, VideoItem item) {
        if (playerService == null) return;
        PopupMenu popup = new PopupMenu(this, anchor);
        boolean current = index == playerService.getCurrentIndex();
        popup.getMenu().add(current && playerService.isPlaying() ? "Pause" : "Play");
        if (index > 0) popup.getMenu().add("Move up");
        if (index + 1 < playerService.getPlaylist().size()) popup.getMenu().add("Move down");
        popup.getMenu().add("Reset progress");
        popup.getMenu().add("Remove from playlist");
        popup.setOnMenuItemClickListener(menuItem -> {
            String action = menuItem.getTitle().toString();
            if ("Pause".equals(action)) {
                playerService.pause();
            } else if ("Play".equals(action)) {
                playPlaylistIndex(index);
            } else if ("Move up".equals(action)) {
                movePlaylistItem(index, -1);
            } else if ("Move down".equals(action)) {
                movePlaylistItem(index, 1);
            } else if ("Reset progress".equals(action)) {
                resetVideoHistory(item);
            } else if ("Remove from playlist".equals(action)) {
                playerService.removeFromPlaylist(index);
                saveActivePlaylistFromService();
                refreshPlaylist();
            }
            return true;
        });
        popup.show();
    }

    private void playPlaylistIndex(int index) {
        if (playerService == null || index < 0 || index >= playerService.getPlaylist().size()) return;
        startPlaybackServiceIfNeeded();
        if (index == playerService.getCurrentIndex()) {
            if (!playerService.isPlaying()) playerService.play();
        } else {
            playerService.playIndex(index);
        }
        showBottomTab("video", bottomVideoButton);
    }

    private void playHistoryItem(VideoItem item) {
        if (item == null || playerService == null) return;
        for (Map.Entry<String, ArrayList<VideoItem>> entry : savedPlaylists.entrySet()) {
            int index = entry.getValue().indexOf(item);
            if (index >= 0) {
                openPlaylistDetail(entry.getKey());
                playPlaylistIndex(index);
                return;
            }
        }
        if (!playerService.getPlaylist().contains(item)) playerService.addToPlaylist(item);
        saveActivePlaylistFromService();
        playPlaylistIndex(playerService.getPlaylist().indexOf(item));
    }

    private String displayEpisodeTitle(VideoItem item) {
        if (item == null) return "Video";
        String clean = item.name
                .replaceAll("(?i)\\.(mp4|mkv|avi|mov|webm|m4v)$", "")
                .replace('_', ' ')
                .replace('.', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        int episode = episodeNumber(item.name);
        return episode >= 0 ? "Episode " + episode + "  |  " + clean : clean;
    }

    private String mediaDurationLabel(VideoItem item) {
        int duration = durationFor(item);
        return duration > 0 ? formatTime(duration) : "Unknown length";
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

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(0, millis / 1000);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%dh %02dm %02ds", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%dm %02ds", minutes, seconds);
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
        editText.setMinHeight(dp(48));
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
        button.setMinHeight(dp(48));
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
        button.setMinHeight(dp(48));
        return button;
    }

    private Button commandButton(String label, int iconResource) {
        Button button = secondaryButton(label);
        button.setTextSize(12);
        button.setCompoundDrawablesWithIntrinsicBounds(0, iconResource, 0, 0);
        button.setCompoundDrawablePadding(dp(4));
        button.setCompoundDrawableTintList(ColorStateList.valueOf(Color.rgb(244, 248, 247)));
        return button;
    }

    private ImageButton iconButton(int iconResource, String description, boolean primary) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconResource);
        button.setContentDescription(description);
        button.setBackgroundResource(primary ? R.drawable.button_primary : R.drawable.button_secondary);
        button.setColorFilter(primary ? Color.rgb(8, 46, 40) : Color.rgb(244, 248, 247));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(13), dp(13), dp(13), dp(13));
        button.setMinimumWidth(dp(46));
        button.setMinimumHeight(dp(46));
        return button;
    }

    private LinearLayout.LayoutParams transportButtonParams(boolean primary) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                primary ? dp(62) : dp(52),
                primary ? dp(58) : dp(50)
        );
        params.setMargins(dp(8), 0, dp(8), 0);
        return params;
    }

    private ImageView thumbnailView(int widthDp, int heightDp) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundResource(R.drawable.thumbnail_placeholder_bg);
        image.setClipToOutline(true);
        image.setMinimumWidth(dp(widthDp));
        image.setMinimumHeight(dp(heightDp));
        return image;
    }

    private ProgressBar horizontalProgressBar() {
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(ColorStateList.valueOf(Color.rgb(47, 209, 181)));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(57, 70, 74)));
        return progress;
    }

    private void bindThumbnail(ImageView image, VideoItem item) {
        if (image == null || item == null) return;
        String key = item.key();
        image.setTag(key);
        Bitmap cached = thumbnailCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            image.setImageBitmap(cached);
            return;
        }
        image.setImageDrawable(null);
        thumbnailExecutor.execute(() -> {
            Bitmap thumbnail = loadThumbnail(item);
            if (thumbnail == null) return;
            thumbnailCache.put(key, thumbnail);
            progressHandler.post(() -> {
                if (key.equals(image.getTag())) image.setImageBitmap(thumbnail);
            });
        });
    }

    private Bitmap loadThumbnail(VideoItem item) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return getContentResolver().loadThumbnail(item.uri, new Size(320, 180), new CancellationSignal());
            }
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(this, item.uri);
                Bitmap frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame == null) return null;
                return Bitmap.createScaledBitmap(frame, 320, 180, true);
            } finally {
                retriever.release();
            }
        } catch (Exception ignored) {
            return null;
        }
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

    private LinearLayout.LayoutParams navParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
        );
        params.setMargins(dp(1), 0, dp(1), 0);
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

    private static final class AccessibleTextureView extends TextureView {
        AccessibleTextureView(Context context) {
            super(context);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }

    private static final class AccessibleFrameLayout extends FrameLayout {
        AccessibleFrameLayout(Context context) {
            super(context);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
