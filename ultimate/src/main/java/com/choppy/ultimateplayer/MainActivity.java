package com.choppy.ultimateplayer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.Rational;
import android.util.Size;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements BackgroundPlayerService.PlayerListener {
    private static final int BG = 0xff101112, PANEL = 0xff1b1d1f, TEXT = 0xfff3f4f4;
    private static final int MUTED = 0xffa8adaf, ACCENT = 0xff64d8a3, PREVIEW = 0xffffd27d;
    private static final int PICK = 101, PERMISSION = 102;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnails = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> cache = new LruCache<>(48);
    private final ArrayList<VideoItem> library = new ArrayList<>();
    private final Set<String> selection = new HashSet<>();
    private final Map<String, Integer> scrollPositions = new HashMap<>();
    private LibraryStore store;
    private BackgroundPlayerService service;
    private boolean bound, resumed, fullscreen, pip, audioOnly, dragging, locked, rendering;
    private boolean scanning, completedHistory, queueChanging;
    private String tab = "Player", playlistName = "", query = "", signature = "", renderedScreenKey = "";
    private Runnable afterPermission;
    private LinearLayout root, body, toolbar, nav, mini, overlay, transport;
    private FrameLayout video;
    private TextureView texture;
    private Surface surface;
    private TextView time, title, playerMeta, miniTitle, emptyVideo, libraryStatus;
    private ImageButton playButton, miniPlay;
    private SeekBar seek;
    private ListView list;
    private MediaAdapter mediaAdapter;
    private Button introButton, addSelected;
    private long lastSeek, lastHistoryTouch;
    private int outputWidth, outputHeight;
    private final Runnable hideControls = () -> { if (service != null && service.isPlaying() && !dragging) overlay.setVisibility(View.GONE); };
    private final Runnable searchRefresh = this::refreshLibraryRows;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed && !pip) return;
            updatePlaybackUi();
            main.postDelayed(this, 500);
        }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((BackgroundPlayerService.LocalBinder) binder).getService();
            bound = true;
            service.setListener(MainActivity.this);
            service.setStartOffsetMs(store.preferences.getInt("intro_ms", 180000));
            service.setAutoplayNext(store.preferences.getBoolean("autoplay", true));
            service.setPlaybackSpeed(store.preferences.getFloat("speed", 1f));
            syncQueue();
            render();
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; bound = false; }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        pip = isInPictureInPictureMode();
        store = new LibraryStore(this);
        tab = store.preferences.getString("screen", store.names().isEmpty() ? "Library" : "Player");
        playlistName = store.preferences.getString("open_playlist", "");
        buildShell();
        buildVideo();
        render();
        bindService(new Intent(this, BackgroundPlayerService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        pip = isInPictureInPictureMode();
        audioOnly = false;
        if (service != null) { syncQueue(); render(); }
        main.removeCallbacks(tick);
        main.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        saveNavigation();
        if (!pip) { detachSurface(); main.removeCallbacks(tick); }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (service != null) { service.setListener(null); detachSurface(); }
        if (bound) unbindService(connection);
        if (surface != null) surface.release();
        io.shutdownNow();
        thumbnails.shutdownNow();
        cache.evictAll();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (pip) return;
        boolean viewing = "Player".equals(tab) || ("Playlists".equals(tab) && activePlaylist());
        fullscreen = viewing && config.orientation == Configuration.ORIENTATION_LANDSCAPE;
        render();
    }

    @Override public void onPictureInPictureModeChanged(boolean value, Configuration config) {
        super.onPictureInPictureModeChanged(value, config);
        pip = value;
        if (!value && !resumed) detachSurface();
        render();
        root.requestApplyInsets();
    }

    @Override public void onBackPressed() {
        if (locked) { locked = false; showControls(); return; }
        if (fullscreen) { fullscreen = false; render(); return; }
        if ("Playlists".equals(tab) && !playlistName.isEmpty()) { playlistName = ""; render(); return; }
        if (!"Player".equals(tab) && service != null && service.getCurrentItem() != null) { switchTab("Player"); return; }
        moveTaskToBack(true);
    }

    private void buildShell() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        root = column();
        root.setBackgroundColor(BG);
        toolbar = row();
        toolbar.setPadding(dp(16), dp(4), dp(8), dp(4));
        TextView brand = label("ultimate player", 21, TEXT, true);
        toolbar.addView(brand, weighted(0));
        ImageButton more = icon(R.drawable.ui_ellipsis_vertical, "Player options", v -> appMenu(v));
        toolbar.addView(more, box(48, 48));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(56)));
        body = column();
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        mini = row();
        mini.setPadding(dp(16), dp(4), dp(8), dp(4));
        mini.setBackgroundColor(PANEL);
        miniTitle = label("", 14, TEXT, true);
        miniTitle.setMaxLines(2);
        miniTitle.setEllipsize(TextUtils.TruncateAt.END);
        mini.addView(miniTitle, weighted(0));
        miniPlay = icon(R.drawable.ui_play, "Play", v -> togglePlay());
        mini.addView(miniPlay, box(48, 48));
        mini.setOnClickListener(v -> switchTab("Player"));
        root.addView(mini, new LinearLayout.LayoutParams(-1, dp(64)));
        nav = row();
        nav.setBackgroundColor(PANEL);
        root.addView(nav, new LinearLayout.LayoutParams(-1, dp(64)));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (pip) root.setPadding(0, 0, 0, 0);
            else if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                int keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom;
                root.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard));
            } else {
                root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
    }

    private void buildVideo() {
        video = new FrameLayout(this);
        video.setBackgroundColor(Color.BLACK);
        texture = new TextureView(this) {
            @Override public boolean performClick() { super.performClick(); return true; }
        };
        texture.setContentDescription("Video: show or hide playback controls");
        texture.setOnClickListener(v -> {
            if (overlay.getVisibility() == View.VISIBLE && !locked) overlay.setVisibility(View.GONE); else showControls();
        });
        video.addView(texture, new FrameLayout.LayoutParams(-1, -1));
        emptyVideo = label("Select a video", 17, MUTED, false);
        emptyVideo.setGravity(Gravity.CENTER);
        video.addView(emptyVideo, new FrameLayout.LayoutParams(-1, -1));
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                surface = new Surface(st); attachSurface(); fitVideo();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { fitVideo(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                detachSurface(); if (surface != null) surface.release(); surface = null; return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        overlay = column();
        overlay.setPadding(dp(8), 0, dp(8), dp(4));
        overlay.setBackgroundColor(0xb8000000);
        LinearLayout controls = row();
        transport = controls;
        controls.addView(icon(R.drawable.ui_skip_back, "Previous episode", v -> { if (service != null) service.previous(); }), box(44, 44));
        playButton = icon(R.drawable.ui_play, "Play", v -> togglePlay());
        controls.addView(playButton, box(48, 48));
        controls.addView(icon(R.drawable.ui_skip_forward, "Next episode", v -> { if (service != null) service.next(); }), box(44, 44));
        View spacer = new View(this); controls.addView(spacer, weighted(0));
        controls.addView(icon(R.drawable.ui_fullscreen, "Fullscreen", v -> { fullscreen = !fullscreen; render(); }), box(44, 44));
        controls.addView(icon(R.drawable.ui_lock_keyhole, "Lock or unlock controls", v -> {
            locked = !locked; toast(locked ? "Controls locked" : "Controls unlocked"); showControls();
        }), box(44, 44));
        overlay.addView(controls, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout timeline = row();
        seek = new SeekBar(this);
        seek.setProgressTintList(ColorStateList.valueOf(ACCENT));
        seek.setThumbTintList(ColorStateList.valueOf(ACCENT));
        seek.setContentDescription("Video position");
        timeline.addView(seek, new LinearLayout.LayoutParams(0, dp(44), 1));
        time = label("0:00 / 0:00", 12, TEXT, false);
        time.setMinWidth(dp(88));
        time.setGravity(Gravity.CENTER);
        timeline.addView(time);
        overlay.addView(timeline, new LinearLayout.LayoutParams(-1, dp(44)));
        video.addView(overlay, new FrameLayout.LayoutParams(-1, dp(96), Gravity.BOTTOM));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (!user || locked) return;
                time.setText(clock(value) + " / " + clock(bar.getMax()));
                if (service != null && SystemClock.elapsedRealtime() - lastSeek > 180) {
                    lastSeek = SystemClock.elapsedRealtime(); service.seekTo(value);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; main.removeCallbacks(hideControls); }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (service != null && !locked) service.seekTo(bar.getProgress());
                dragging = false; showControls();
            }
        });
        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                texture.performClick();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (locked) return true;
                float position = e.getX() / Math.max(1, video.getWidth());
                if (position > .33f && position < .67f) { fullscreen = !fullscreen; render(); }
                else if (service != null) { service.seekBy(position < .33f ? -10000 : 10000); showControls(); }
                return true;
            }
        });
        texture.setOnTouchListener((v, event) -> gestures.onTouchEvent(event));
    }

    private void render() {
        if (root == null || rendering) return;
        rendering = true;
        rememberScroll();
        renderedScreenKey = tab + playlistName;
        boolean immersive = fullscreen || pip;
        boolean wantsVideo = immersive || (current() != null && ("Player".equals(tab)
                || ("Playlists".equals(tab) && activePlaylist() && store.contains(playlistName))));
        boolean keepSurface = wantsVideo && video.getParent() == body;
        if (!keepSurface && video.getParent() != null) ((ViewGroup) video.getParent()).removeView(video);
        // Rebuilding queue rows must not tear down VLC's output during an episode transition.
        for (int i = body.getChildCount() - 1; i >= 0; i--) {
            if (!keepSurface || body.getChildAt(i) != video) body.removeViewAt(i);
        }
        list = null; mediaAdapter = null; title = null; playerMeta = null; introButton = null;
        toolbar.setVisibility(immersive ? View.GONE : View.VISIBLE);
        nav.setVisibility(immersive ? View.GONE : View.VISIBLE);
        if (immersive) {
            placeVideo(new LinearLayout.LayoutParams(-1, -1));
            overlay.setVisibility(pip ? View.GONE : View.VISIBLE);
        } else {
            rebuildNav();
            switch (tab) {
                case "Playlists": renderPlaylists(); break;
                case "Library": renderLibrary(); break;
                case "History": renderHistory(); break;
                case "Settings": renderSettings(); break;
                default: renderPlayer();
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (immersive) controller.hide(WindowInsets.Type.systemBars()); else controller.show(WindowInsets.Type.systemBars());
            }
        }
        rendering = false;
        saveNavigation(); updatePlaybackUi();
        video.post(() -> { attachSurface(); fitVideo(); });
    }

    private void renderPlayer() {
        VideoItem current = current();
        if (current == null) {
            heading("Ready to watch", "Your videos, in your order");
            Button choose = button("Choose videos", true, v -> pickFiles());
            body.addView(choose, inset());
            Button playlists = button("Open playlists", false, v -> switchTab("Playlists"));
            body.addView(playlists, inset());
            return;
        }
        addPlayerSurface();
        addPlayerInfo();
        addPlaybackOptions();
        heading("Play queue", service.getQueueName() + "  /  " + service.getPlaylist().size() + " videos");
        showMediaList(service.getPlaylist(), "queue");
    }

    private void addPlayerSurface() {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = Math.min(dp(260), Math.max(dp(180), Math.round(width * 9f / 16f)));
        placeVideo(new LinearLayout.LayoutParams(-1, height));
    }

    private void placeVideo(LinearLayout.LayoutParams layout) {
        if (video.getParent() == body) {
            video.setLayoutParams(layout);
            body.bringChildToFront(video);
        } else body.addView(video, layout);
    }

    private void addPlayerInfo() {
        LinearLayout info = column(); info.setPadding(dp(16), dp(12), dp(16), dp(4));
        title = label(current() == null ? "" : clean(current()), 18, TEXT, true);
        title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END);
        playerMeta = label("", 12, MUTED, false);
        info.addView(title); info.addView(playerMeta);
        body.addView(info);
    }

    private void addPlaybackOptions() {
        LinearLayout actions = row(); actions.setPadding(dp(12), dp(4), dp(8), dp(4));
        introButton = button("Intro " + clock(store.preferences.getInt("intro_ms", 180000)), false, v -> introDialog());
        actions.addView(introButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        actions.addView(icon(R.drawable.ui_volume_2, "Audio language", v -> audioDialog()), box(48, 48));
        actions.addView(icon(R.drawable.ui_captions, "Subtitles", v -> subtitleDialog()), box(48, 48));
        actions.addView(icon(R.drawable.ui_ellipsis_vertical, "Playback options", this::playbackMenu), box(48, 48));
        body.addView(actions);
        if (service != null && service.isPreviewing()) {
            Button restore = button("Return to series", false, v -> { service.continueSeries(); render(); });
            restore.setTextColor(PREVIEW); body.addView(restore, inset());
        }
    }

    private void renderPlaylists() {
        if (playlistName.isEmpty() || !store.contains(playlistName)) {
            playlistName = "";
            LinearLayout head = row(); head.setPadding(dp(16), dp(12), dp(8), dp(12));
            head.addView(label("Playlists", 24, TEXT, true), weighted(0));
            head.addView(icon(R.drawable.ui_plus, "Create playlist", v -> createPlaylist(null)), box(48, 48));
            body.addView(head);
            ListView hub = listView();
            List<String> names = store.names();
            hub.setAdapter(new BaseAdapter() {
                @Override public int getCount() { return names.size(); }
                @Override public Object getItem(int i) { return names.get(i); }
                @Override public long getItemId(int i) { return i; }
                @Override public View getView(int i, View convert, ViewGroup parent) {
                    String name = names.get(i); List<VideoItem> items = store.get(name);
                    LinearLayout row = mediaLine(); ImageView cover = thumbnail();
                    if (!items.isEmpty()) bindThumbnail(cover, items.get(0));
                    row.addView(cover, box(80, 64));
                    LinearLayout text = column(); text.setPadding(dp(12), 0, dp(8), 0);
                    text.addView(label(name, 17, TEXT, true));
                    text.addView(label(items.size() + " videos  /  " + totalDuration(items), 12, MUTED, false));
                    row.addView(text, weighted(0));
                    row.addView(icon(items.isEmpty() ? R.drawable.ui_plus : R.drawable.ui_play,
                            items.isEmpty() ? "Add videos to " + name : "Continue " + name,
                            v -> { playlistName = name; if (items.isEmpty()) pickFiles(); else continuePlaylist(name); }), box(48, 48));
                    row.setOnClickListener(v -> { playlistName = name; render(); });
                    return row;
                }
            });
            body.addView(hub, new LinearLayout.LayoutParams(-1, 0, 1));
            if (names.isEmpty()) body.addView(empty("No playlists yet"), inset());
            return;
        }
        LinearLayout header = row(); header.setPadding(dp(8), dp(4), dp(8), dp(4));
        header.addView(icon(R.drawable.ui_skip_back, "Back to playlists", v -> { playlistName = ""; render(); }), box(48, 48));
        TextView name = label(playlistName, 20, TEXT, true); name.setMaxLines(2);
        header.addView(name, weighted(0));
        header.addView(icon(R.drawable.ui_ellipsis_vertical, "Playlist options", this::playlistMenu), box(48, 48));
        body.addView(header);
        if (activePlaylist() && current() != null) { addPlayerSurface(); addPlaybackOptions(); }
        LinearLayout actions = row(); actions.setPadding(dp(12), dp(4), dp(12), dp(8));
        actions.addView(button("Continue series", true, v -> continuePlaylist(playlistName)), weighted(0));
        actions.addView(icon(R.drawable.ui_search, "Find new episodes", v -> scanMatching(true)), box(48, 48));
        actions.addView(icon(R.drawable.ui_plus, "Add videos", v -> pickFiles()), box(48, 48));
        body.addView(actions);
        List<VideoItem> items = activePlaylist() ? service.getPlaylist() : store.get(playlistName);
        showMediaList(items, "playlist");
    }

    private void renderLibrary() {
        LinearLayout actions = row(); actions.setPadding(dp(16), dp(8), dp(8), dp(4));
        actions.addView(label("Library", 24, TEXT, true), weighted(0));
        actions.addView(icon(R.drawable.ui_plus, "Choose files", v -> pickFiles()), box(48, 48));
        actions.addView(icon(R.drawable.ui_refresh_cw, "Scan device", v -> scanDevice(null)), box(48, 48));
        body.addView(actions);
        EditText search = input("Search videos", query); search.setSingleLine(true);
        search.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ui_search, 0, 0, 0);
        search.setCompoundDrawablePadding(dp(8));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString(); main.removeCallbacks(searchRefresh); main.postDelayed(searchRefresh, 180);
            }
            public void afterTextChanged(Editable e) { }
        });
        body.addView(search, inset());
        libraryStatus = label(scanning ? "Scanning..." : library.size() + " videos", 12, MUTED, false);
        body.addView(libraryStatus, inset());
        showMediaList(filteredLibrary(), "library");
        addSelected = button("Add selected to playlist", true, v -> {
            ArrayList<VideoItem> items = new ArrayList<>();
            for (VideoItem item : library) if (selection.contains(item.key())) items.add(item);
            if (!items.isEmpty()) chooseDestination(items);
        });
        body.addView(addSelected, inset()); updateSelection();
        if (library.isEmpty() && !scanning) body.addView(button("Scan phone for videos", false, v -> scanDevice(null)), inset());
    }

    private void renderHistory() {
        heading("History", "");
        LinearLayout filters = row(); filters.setPadding(dp(12), 0, dp(12), dp(8));
        filters.addView(button("In progress", !completedHistory, v -> { completedHistory = false; render(); }), weighted(0));
        filters.addView(button("Watched", completedHistory, v -> { completedHistory = true; render(); }), weighted(0));
        body.addView(filters);
        ArrayList<VideoItem> items = store.catalog();
        items.removeIf(item -> service == null || (completedHistory ? !service.isWatched(item)
                : service.isWatched(item) || service.getSavedProgressMs(item) <= 0));
        items.sort((a, b) -> Long.compare(store.preferences.getLong("last:" + b.key(), 0), store.preferences.getLong("last:" + a.key(), 0)));
        showMediaList(items, "history");
    }

    private void renderSettings() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false);
        LinearLayout settings = column(); settings.setPadding(dp(16), dp(12), dp(16), dp(20));
        settings.addView(label("Playback", 24, TEXT, true));
        settings.addView(button("Start new videos after " + clock(store.preferences.getInt("intro_ms", 180000)), false, v -> introDialog()), spaced());
        Switch autoplay = new Switch(this); autoplay.setText("Auto-play next episode"); autoplay.setTextColor(TEXT);
        autoplay.setTextSize(16); autoplay.setMinHeight(dp(56)); autoplay.setChecked(store.preferences.getBoolean("autoplay", true));
        autoplay.setOnCheckedChangeListener((b, checked) -> {
            store.preferences.edit().putBoolean("autoplay", checked).apply(); if (service != null) service.setAutoplayNext(checked);
        }); settings.addView(autoplay);
        settings.addView(button("Playback speed  " + store.preferences.getFloat("speed", 1f) + "x", false, v -> speedDialog()), spaced());
        settings.addView(button("Audio language", false, v -> audioDialog()), spaced());
        settings.addView(button("Subtitles", false, v -> subtitleDialog()), spaced());
        settings.addView(button("Sleep timer", false, v -> sleepDialog()), spaced());
        settings.addView(button("Audio only", false, v -> audioOnly()), spaced());
        settings.addView(button("Picture in picture", false, v -> enterPip()), spaced());
        settings.addView(label("Application", 20, TEXT, true), spaced());
        settings.addView(button("About and open-source licenses", false, v -> about()), spaced());
        settings.addView(button("Playback diagnostics", false, v -> new AlertDialog.Builder(this).setTitle("Playback diagnostics")
                .setMessage("Ultimate Player 0.1.0\nLibVLC 3.7.7\nLibrary: " + library.size() + " videos\nQueue: "
                        + (service == null ? "Not connected" : service.getQueueName() + "\n" + service.getLastError()))
                .setPositiveButton("Close", null).show()), spaced());
        scroll.addView(settings); body.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
    }

    private void showMediaList(List<VideoItem> items, String mode) {
        list = listView(); mediaAdapter = new MediaAdapter(items, mode); list.setAdapter(mediaAdapter);
        body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        int saved = scrollPositions.getOrDefault(tab + playlistName, 0); list.setSelection(saved);
        if (items.isEmpty()) {
            TextView empty = empty("library".equals(mode) ? "No matching videos" : "Nothing here yet");
            body.addView(empty, inset()); list.setEmptyView(empty);
        }
    }

    private final class MediaAdapter extends BaseAdapter {
        private final List<VideoItem> items;
        private final String mode;
        MediaAdapter(List<VideoItem> values, String mode) { items = new ArrayList<>(values); this.mode = mode; }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return items.get(i).key().hashCode(); }
        @Override public View getView(int i, View convert, ViewGroup parent) {
            Row holder;
            if (convert == null || !(convert.getTag() instanceof Row)) { holder = new Row(); convert = holder.root; convert.setTag(holder); }
            else holder = (Row) convert.getTag();
            VideoItem item = items.get(i);
            holder.item = item;
            boolean watched = service != null && service.isWatched(item);
            boolean current = current() != null && current().equals(item);
            holder.name.setText(clean(item)); holder.name.setTextColor(watched ? MUTED : TEXT);
            holder.meta.setText(mediaMeta(item, watched, current));
            holder.root.setBackgroundColor(current ? 0xff1b3028 : BG);
            holder.progress.setVisibility(service != null && service.getSavedProgressMs(item) > 0 ? View.VISIBLE : View.GONE);
            holder.progress.setMax((int) Math.max(1, item.durationMs));
            holder.progress.setProgress(service == null ? 0 : service.getSavedProgressMs(item));
            bindThumbnail(holder.image, item);
            boolean picking = "library".equals(mode);
            holder.check.setVisibility(picking ? View.VISIBLE : View.GONE);
            holder.more.setVisibility(picking ? View.GONE : View.VISIBLE);
            holder.check.setOnCheckedChangeListener(null);
            holder.check.setChecked(selection.contains(item.key()));
            holder.check.setContentDescription("Select " + clean(item));
            holder.check.setOnCheckedChangeListener((b, checked) -> { if (checked) selection.add(item.key()); else selection.remove(item.key()); updateSelection(); });
            holder.more.setOnClickListener(v -> episodeMenu(v, item, mode));
            holder.root.setOnClickListener(v -> {
                if (picking) holder.check.setChecked(!holder.check.isChecked());
                else if ("history".equals(mode)) playHistory(item);
                else playItem("queue".equals(mode) ? service.getQueueName() : playlistName, item, false);
            });
            return convert;
        }
    }

    private final class Row {
        VideoItem item;
        final LinearLayout root = mediaLine();
        final ImageView image = thumbnail();
        final TextView name = label("", 15, TEXT, true), meta = label("", 12, MUTED, false);
        final ProgressBar progress = new ProgressBar(MainActivity.this, null, android.R.attr.progressBarStyleHorizontal);
        final CheckBox check = new CheckBox(MainActivity.this);
        final ImageButton more = icon(R.drawable.ui_ellipsis_vertical, "Video options", null);
        Row() {
            root.addView(image, box(80, 52));
            LinearLayout labels = column(); labels.setPadding(dp(12), 0, dp(4), 0);
            name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
            meta.setSingleLine(true); meta.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(name); labels.addView(meta);
            progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
            labels.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
            root.addView(labels, weighted(0)); root.addView(more, box(44, 48)); root.addView(check, box(44, 48));
        }
    }

    private void playItem(String name, VideoItem item, boolean preview) {
        if (service == null) return;
        syncQueue();
        if (!name.equals(service.getQueueName())) {
            queueChanging = true;
            try { service.setQueueName(name); service.replacePlaylist(store.get(name)); }
            finally { queueChanging = false; }
        }
        int index = service.getPlaylist().indexOf(item);
        if (index < 0) return;
        startService(new Intent(this, BackgroundPlayerService.class));
        audioOnly = false;
        if (preview) service.previewIndex(index); else service.playIndex(index);
        store.preferences.edit().putLong("last:" + item.key(), System.currentTimeMillis()).apply();
        attachSurface(); render();
    }

    private void continuePlaylist(String name) {
        if (service == null || store.get(name).isEmpty()) return;
        if (!name.equals(service.getQueueName())) {
            syncQueue();
            queueChanging = true;
            try { service.setQueue(name, store.get(name)); }
            finally { queueChanging = false; }
        }
        startService(new Intent(this, BackgroundPlayerService.class));
        service.continueSeries();
        audioOnly = false;
        tab = "Playlists"; playlistName = name; render();
    }

    private void playHistory(VideoItem item) {
        for (String name : store.names()) if (store.get(name).contains(item)) {
            playlistName = name; tab = "Playlists"; playItem(name, item, false); return;
        }
        String name = "Recently opened"; store.add(name, Collections.singletonList(item));
        playlistName = name; tab = "Player"; playItem(name, item, false);
    }

    private void syncQueue() {
        if (service == null || queueChanging) return;
        String name = service.getQueueName();
        if (name == null || name.isEmpty() || !store.contains(name)) return;
        List<VideoItem> queue = service.getPlaylist();
        if (!store.get(name).equals(queue)) store.put(name, queue);
    }

    @Override public void onPlayerChanged() {
        if (service == null || isFinishing() || queueChanging) return;
        syncQueue();
        VideoItem item = current();
        if (item != null && service.isPlaying() && SystemClock.elapsedRealtime() - lastHistoryTouch > 10000) {
            store.preferences.edit().putLong("last:" + item.key(), System.currentTimeMillis()).apply();
            lastHistoryTouch = SystemClock.elapsedRealtime();
        }
        String state = service.getQueueName() + ":" + service.getPlaylist().hashCode() + ":" + service.getCurrentIndex()
                + ":" + service.getWatchedSnapshot().size() + ":" + service.isPreviewing();
        if (!signature.equals(state)) {
            signature = state;
            main.removeCallbacks(refreshScreen); main.post(refreshScreen);
        } else updatePlaybackUi();
    }

    private final Runnable refreshScreen = () -> { if (resumed && !dragging) render(); };

    private void updatePlaybackUi() {
        if (video == null) return;
        VideoItem item = current(); boolean playing = service != null && service.isPlaying();
        boolean shown = video.getParent() == body;
        emptyVideo.setVisibility(item == null ? View.VISIBLE : View.GONE);
        playButton.setImageResource(playing ? R.drawable.ui_pause : R.drawable.ui_play);
        playButton.setContentDescription(playing ? "Pause" : "Play");
        miniPlay.setImageResource(playing ? R.drawable.ui_pause : R.drawable.ui_play);
        miniPlay.setContentDescription(playing ? "Pause" : "Play");
        mini.setVisibility(item == null || shown || fullscreen || pip ? View.GONE : View.VISIBLE);
        if (item != null) {
            int position = service.getCurrentPositionMs(), duration = service.getDurationMs();
            if (!dragging) { seek.setMax(Math.max(1, duration)); seek.setProgress(position); time.setText(clock(position) + " / " + clock(duration)); }
            miniTitle.setText(clean(item) + "\n" + clock(position) + " / " + clock(duration));
            if (title != null) title.setText(clean(item));
            if (playerMeta != null) {
                String error = service.getLastError();
                playerMeta.setText(error != null && !error.isEmpty() ? error : (service.isPreviewing() ? "Preview  /  " : "") + service.getQueueName());
                playerMeta.setTextColor(service.isPreviewing() ? PREVIEW : MUTED);
            }
        }
        seek.setEnabled(!locked);
        if (!pip && !playing && service != null && !service.isLoading()) overlay.setVisibility(View.VISIBLE);
        for (int i = 0; i < transport.getChildCount() - 1; i++) transport.getChildAt(i).setEnabled(!locked);
        if (list != null && service != null) {
            for (int i = 0; i < list.getChildCount(); i++) {
                View child = list.getChildAt(i);
                if (!(child.getTag() instanceof Row)) continue;
                Row row = (Row) child.getTag();
                if (row.item == null || !row.item.equals(item)) continue;
                row.meta.setText(mediaMeta(row.item, service.isWatched(row.item), true));
                row.progress.setMax(Math.max(1, service.getDurationMs()));
                row.progress.setProgress(service.getCurrentPositionMs());
                row.progress.setVisibility(View.VISIBLE);
            }
        }
        if (resumed && playing && shown && !audioOnly) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        fitVideo();
    }

    private void togglePlay() {
        if (service == null || current() == null || locked) return;
        startService(new Intent(this, BackgroundPlayerService.class));
        if (service.isPlaying()) service.pause(); else service.play(); showControls();
    }

    private void showControls() { overlay.setVisibility(View.VISIBLE); main.removeCallbacks(hideControls); if (!locked) main.postDelayed(hideControls, 4000); }
    private VideoItem current() { return service == null ? null : service.getCurrentItem(); }
    private boolean activePlaylist() { return service != null && !playlistName.isEmpty() && playlistName.equals(service.getQueueName()); }

    private void attachSurface() {
        if (service == null || surface == null || !surface.isValid() || audioOnly || (!resumed && !pip) || video.getParent() != body) return;
        service.setOutputSize(texture.getWidth(), texture.getHeight());
        service.setOutputSurface(surface);
    }
    private void detachSurface() { if (service != null && surface != null) service.clearOutputSurface(surface); }
    private void fitVideo() {
        if (service == null || texture.getWidth() == 0 || texture.getHeight() == 0) return;
        int width = texture.getWidth(), height = texture.getHeight();
        if (outputWidth != width || outputHeight != height) {
            outputWidth = width; outputHeight = height;
            // LibVLC's GLES output fits the image and subtitles into this viewport.
            service.setOutputSize(width, height);
            texture.setTransform(new Matrix());
        }
    }

    private void audioOnly() {
        if (service == null || current() == null) { toast("Open a video first"); return; }
        startService(new Intent(this, BackgroundPlayerService.class));
        audioOnly = true; detachSurface(); if (!service.isPlaying()) service.play(); moveTaskToBack(true);
    }
    private void enterPip() {
        if (current() == null || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) { toast("Picture in picture is unavailable"); return; }
        if (!("Player".equals(tab) || ("Playlists".equals(tab) && activePlaylist()))) tab = "Player";
        fullscreen = false;
        int w = Math.max(1, service.getVideoWidth()), h = Math.max(1, service.getVideoHeight());
        float aspect = w / (float) h;
        Rational ratio = aspect < .42f || aspect > 2.39f ? new Rational(16, 9) : new Rational(w, h);
        android.graphics.Rect bounds = new android.graphics.Rect();
        video.getGlobalVisibleRect(bounds);
        pip = true; render(); root.requestApplyInsets();
        try {
            if (!enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(ratio).setSourceRectHint(bounds).build())) {
                pip = false; render(); root.requestApplyInsets(); toast("Picture in picture is not available right now");
            }
        } catch (IllegalStateException | IllegalArgumentException error) {
            pip = false; render(); root.requestApplyInsets(); toast("Picture in picture is not available right now");
        }
    }

    private void introDialog() {
        int value = store.preferences.getInt("intro_ms", 180000) / 1000;
        LinearLayout form = row(); form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText minutes = input("Minutes", String.valueOf(value / 60)), seconds = input("Seconds", String.valueOf(value % 60));
        minutes.setInputType(InputType.TYPE_CLASS_NUMBER); seconds.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(minutes, weighted(0)); form.addView(label(" min  ", 14, MUTED, false));
        form.addView(seconds, weighted(0)); form.addView(label(" sec", 14, MUTED, false));
        new AlertDialog.Builder(this).setTitle("Start new videos after").setView(form)
                .setPositiveButton("Save", (d, which) -> {
                    int ms = (number(minutes, 0, 180) * 60 + number(seconds, 0, 59)) * 1000;
                    store.preferences.edit().putInt("intro_ms", ms).apply();
                    if (service != null) service.setStartOffsetMs(ms); render();
                }).setNegativeButton("Cancel", null).show();
    }

    private void audioDialog() {
        if (service == null) return;
        List<String> tracks = service.getAudioTrackLabels();
        if (tracks.isEmpty()) { toast("Start a video to see its audio tracks"); return; }
        new AlertDialog.Builder(this).setTitle("Audio language - remembered for this series")
                .setSingleChoiceItems(tracks.toArray(new String[0]), service.getSelectedAudioTrackIndex(), (d, which) -> { service.selectAudioTrack(which); d.dismiss(); })
                .setNeutralButton("Use default", (d, w) -> service.clearPreferredAudioTrack()).setNegativeButton("Cancel", null).show();
    }

    private void subtitleDialog() {
        if (service == null) return;
        List<String> tracks = service.getSubtitleTrackLabels();
        if (tracks.isEmpty()) { toast("No embedded subtitles in this video"); return; }
        new AlertDialog.Builder(this).setTitle("Subtitles")
                .setSingleChoiceItems(tracks.toArray(new String[0]), service.getSelectedSubtitleTrackIndex(), (d, which) -> { service.selectSubtitleTrack(which); d.dismiss(); })
                .setNegativeButton("Cancel", null).show();
    }

    private void speedDialog() {
        String[] labels = {"0.5x", "0.75x", "1x", "1.25x", "1.5x", "1.75x", "2x"};
        float[] rates = {.5f, .75f, 1, 1.25f, 1.5f, 1.75f, 2};
        new AlertDialog.Builder(this).setTitle("Playback speed").setItems(labels, (d, which) -> {
            store.preferences.edit().putFloat("speed", rates[which]).apply();
            if (service != null) service.setPlaybackSpeed(rates[which]); render();
        }).show();
    }
    private void sleepDialog() {
        if (service == null) return;
        String[] choices = {"Off", "15 minutes", "30 minutes", "45 minutes", "60 minutes"};
        new AlertDialog.Builder(this).setTitle("Sleep timer").setItems(choices, (d, which) -> { service.setSleepTimerMinutes(which * 15); toast(choices[which]); }).show();
    }

    private void playbackMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Audio only").setOnMenuItemClickListener(i -> { audioOnly(); return true; });
        menu.getMenu().add("Picture in picture").setOnMenuItemClickListener(i -> { enterPip(); return true; });
        menu.getMenu().add("Playback speed").setOnMenuItemClickListener(i -> { speedDialog(); return true; });
        menu.getMenu().add("Sleep timer").setOnMenuItemClickListener(i -> { sleepDialog(); return true; });
        menu.show();
    }
    private void appMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Choose videos").setOnMenuItemClickListener(i -> { pickFiles(); return true; });
        menu.getMenu().add("Settings").setOnMenuItemClickListener(i -> { switchTab("Settings"); return true; });
        menu.getMenu().add("About Ultimate Player").setOnMenuItemClickListener(i -> { about(); return true; }); menu.show();
    }

    private void playlistMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Find same series").setOnMenuItemClickListener(i -> { scanMatching(false); return true; });
        menu.getMenu().add("Scan new episodes").setOnMenuItemClickListener(i -> { scanMatching(true); return true; });
        menu.getMenu().add("Source website").setOnMenuItemClickListener(i -> { sourceDialog(); return true; });
        menu.getMenu().add("Sort by episode").setOnMenuItemClickListener(i -> { List<VideoItem> items = store.get(playlistName); LibraryStore.sort(items); updatePlaylist(items); return true; });
        menu.getMenu().add("Sort by name").setOnMenuItemClickListener(i -> { List<VideoItem> items = store.get(playlistName); items.sort((a,b) -> a.name.compareToIgnoreCase(b.name)); updatePlaylist(items); return true; });
        menu.getMenu().add("Delete playlist").setOnMenuItemClickListener(i -> {
            new AlertDialog.Builder(this).setTitle("Delete " + playlistName + "?").setMessage("Video files and watch history will stay on your phone.")
                    .setPositiveButton("Delete playlist", (d,w) -> { if (activePlaylist()) service.clearPlaylist(); store.delete(playlistName); playlistName = ""; render(); })
                    .setNegativeButton("Cancel", null).show(); return true;
        }); menu.show();
    }

    private void episodeMenu(View anchor, VideoItem item, String mode) {
        PopupMenu menu = new PopupMenu(this, anchor);
        String name = "queue".equals(mode) && service != null ? service.getQueueName() : playlistName;
        if (!"history".equals(mode)) {
            menu.getMenu().add("Preview episode").setOnMenuItemClickListener(i -> { playItem(name, item, true); return true; });
            menu.getMenu().add("Move up").setOnMenuItemClickListener(i -> { reorder(name, item, -1); return true; });
            menu.getMenu().add("Move down").setOnMenuItemClickListener(i -> { reorder(name, item, 1); return true; });
            menu.getMenu().add("Remove from playlist").setOnMenuItemClickListener(i -> {
                ArrayList<VideoItem> items = store.get(name); items.remove(item); store.put(name, items);
                if (service != null && name.equals(service.getQueueName())) service.removeFromPlaylist(service.getPlaylist().indexOf(item)); render(); return true;
            });
        }
        menu.getMenu().add("Add to another playlist").setOnMenuItemClickListener(i -> { chooseDestination(Collections.singletonList(item)); return true; });
        menu.getMenu().add("Reset this video's history").setOnMenuItemClickListener(i -> {
            new AlertDialog.Builder(this).setTitle("Reset watch history?").setMessage(clean(item))
                    .setPositiveButton("Reset", (d,w) -> { if (service != null) service.clearHistory(item); render(); })
                    .setNegativeButton("Cancel", null).show(); return true;
        });
        menu.getMenu().add("File details").setOnMenuItemClickListener(i -> {
            new AlertDialog.Builder(this).setTitle("File details").setMessage(item.name + "\n\n" + item.uri + "\n" + clock(item.durationMs))
                    .setPositiveButton("Close", null).show(); return true;
        }); menu.show();
    }

    private void reorder(String name, VideoItem item, int direction) {
        ArrayList<VideoItem> items = store.get(name); int at = items.indexOf(item), to = at + direction;
        if (at < 0 || to < 0 || to >= items.size()) return;
        Collections.swap(items, at, to); store.put(name, items);
        if (service != null && name.equals(service.getQueueName())) service.replacePlaylist(items); render();
    }
    private void updatePlaylist(List<VideoItem> items) { store.put(playlistName, items); if (activePlaylist()) service.replacePlaylist(items); render(); }

    private void sourceDialog() {
        EditText url = input("https://", store.source(playlistName)); url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this).setTitle("Playlist source").setView(url)
                .setPositiveButton("Open source", (d,w) -> {
                    String address = url.getText().toString().trim(); Uri uri = Uri.parse(address);
                    if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null) { toast("Enter a full website address"); return; }
                    store.source(playlistName, address);
                    startActivity(new Intent(this, WebSourceActivity.class).putExtra("playlist", playlistName).putExtra("url", address));
                }).setNeutralButton("Save", (d,w) -> store.source(playlistName, url.getText().toString().trim())).setNegativeButton("Cancel", null).show();
    }

    private void createPlaylist(List<VideoItem> toAdd) {
        EditText name = input("Playlist name", "");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("New playlist").setView(name)
                .setPositiveButton("Create", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty()) { name.setError("Enter a name"); return; }
            if (store.contains(value)) { name.setError("A playlist with this name already exists"); return; }
            store.put(value, toAdd == null ? Collections.emptyList() : toAdd);
            selection.clear(); playlistName = value; tab = "Playlists"; dialog.dismiss(); render();
        })); dialog.show();
    }

    private void chooseDestination(List<VideoItem> items) {
        List<String> names = store.names(); names.add("Create new playlist...");
        new AlertDialog.Builder(this).setTitle("Add " + items.size() + (items.size() == 1 ? " video" : " videos") + " to")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    if (which == names.size() - 1) { createPlaylist(items); return; }
                    String name = names.get(which); store.add(name, items);
                    if (service != null && name.equals(service.getQueueName())) service.replacePlaylist(store.get(name));
                    selection.clear(); playlistName = name; tab = "Playlists"; render();
                }).setNegativeButton("Cancel", null).show();
    }

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK || result != RESULT_OK || data == null) return;
        ArrayList<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i=0; i<data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        for (Uri uri : uris) try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) { }
        toast("Reading " + uris.size() + " videos");
        List<VideoItem> known = store.catalog();
        io.execute(() -> {
            ArrayList<VideoItem> items = new ArrayList<>();
            for (Uri uri : uris) items.add(readVideo(uri));
            List<VideoItem> unique = MediaIdentity.reconcile(this, items, known);
            LibraryStore.sort(unique); main.post(() -> { if (!isDestroyed()) chooseDestination(unique); });
        });
    }

    private VideoItem readVideo(Uri uri) {
        String name = "Video"; long size = 0, duration = 0;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (col >= 0) name = cursor.getString(col);
                col = cursor.getColumnIndex(OpenableColumns.SIZE); if (col >= 0) size = cursor.getLong(col);
                col = cursor.getColumnIndex(MediaStore.Video.Media.DURATION); if (col >= 0) duration = cursor.getLong(col);
            }
        } catch (RuntimeException ignored) { }
        if (duration <= 0) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(this, uri); String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (value != null) duration = Long.parseLong(value);
            } catch (RuntimeException ignored) { }
            finally { try { retriever.release(); } catch (Exception ignored) { } }
        }
        return new VideoItem(uri, name == null ? "Video" : name, duration, size);
    }

    private void scanDevice(Runnable done) {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_EXTERNAL_STORAGE;
        boolean partial = Build.VERSION.SDK_INT >= 34 && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED && !partial) {
            afterPermission = () -> scanDevice(done);
            requestPermissions(Build.VERSION.SDK_INT >= 34 ? new String[]{permission, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.POST_NOTIFICATIONS}
                    : new String[]{permission}, PERMISSION); return;
        }
        if (scanning) { toast("A scan is already running"); return; }
        scanning = true; if (libraryStatus != null) libraryStatus.setText("Scanning...");
        List<VideoItem> known = store.catalog();
        io.execute(() -> {
            ArrayList<VideoItem> result = new ArrayList<>(); String error = null;
            try (Cursor cursor = getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE},
                    null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")) {
                if (cursor != null) while (cursor.moveToNext()) result.add(new VideoItem(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)),
                        cursor.getString(1), cursor.getLong(2), cursor.getLong(3)));
                LibraryStore.sort(result);
            } catch (RuntimeException e) { error = "Could not scan videos. Check media permission."; }
            String failure = error;
            List<VideoItem> unique = MediaIdentity.reconcile(this, result, known);
            main.post(() -> {
                if (isDestroyed()) return;
                scanning = false;
                if (failure != null) { toast(failure); return; }
                library.clear(); library.addAll(unique);
                if ("Library".equals(tab)) render();
                if (done != null) done.run(); else toast("Found " + result.size() + " videos" + (partial ? " in selected access" : ""));
            });
        });
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != PERMISSION) return;
        boolean granted = false;
        for (int i=0; i<permissions.length; i++) if (!Manifest.permission.POST_NOTIFICATIONS.equals(permissions[i]) && results[i] == PackageManager.PERMISSION_GRANTED) granted = true;
        Runnable pending = afterPermission; afterPermission = null;
        if (granted && pending != null) pending.run(); else toast("Use Choose files to add videos without library access");
    }

    private void scanMatching(boolean newerOnly) {
        String target = playlistName;
        List<VideoItem> existing = store.get(target);
        if (existing.isEmpty()) { toast("Add the first episode to this playlist first"); pickFiles(); return; }
        scanDevice(() -> {
            if (!store.contains(target)) return;
            List<VideoItem> latest = store.get(target);
            List<String> sourceTokens = EpisodeNameParser.significantTokens(existing.get(0).name);
            List<String> playlistTokens = EpisodeNameParser.significantTokens(target);
            String anchor = service == null ? "" : service.getSeriesAnchorKey(target);
            long minimum = -1;
            if (newerOnly) {
                for (VideoItem item : store.catalog()) if (item.key().equals(anchor)) minimum = EpisodeNameParser.sortKey(item.name);
                if (minimum < 0) {
                    minimum = Long.MAX_VALUE;
                    for (VideoItem item : latest) minimum = Math.min(minimum, EpisodeNameParser.sortKey(item.name));
                }
            }
            if (minimum == Long.MAX_VALUE) minimum = -1;
            ArrayList<VideoItem> found = new ArrayList<>();
            for (VideoItem item : library) {
                if (latest.contains(item) || (service != null && service.isWatched(item))) continue;
                long order = EpisodeNameParser.sortKey(item.name);
                if (newerOnly && (order == Long.MAX_VALUE || order <= minimum)) continue;
                if (EpisodeNameParser.matchesSeries(item.name, sourceTokens, playlistTokens)) found.add(item);
            }
            LibraryStore.sort(found);
            if (found.isEmpty()) { toast("No new matching episodes found"); return; }
            boolean[] checked = new boolean[found.size()]; java.util.Arrays.fill(checked, true);
            String[] labels = new String[found.size()]; for (int i=0; i<labels.length; i++) labels[i] = clean(found.get(i));
            new AlertDialog.Builder(this).setTitle("Matching episodes (" + found.size() + ")")
                    .setMultiChoiceItems(labels, checked, (d, which, value) -> checked[which] = value)
                    .setPositiveButton("Add selected", (d,w) -> {
                        ArrayList<VideoItem> chosen = new ArrayList<>(); for (int i=0; i<checked.length; i++) if (checked[i]) chosen.add(found.get(i));
                        store.add(target, chosen);
                        if (service != null && target.equals(service.getQueueName())) service.replacePlaylist(store.get(target)); render();
                    }).setNegativeButton("Cancel", null).show();
        });
    }

    private ArrayList<VideoItem> filteredLibrary() {
        String match = EpisodeNameParser.normalize(query); ArrayList<VideoItem> result = new ArrayList<>();
        for (VideoItem item : library) if (EpisodeNameParser.normalize(item.name).contains(match)) result.add(item);
        return result;
    }
    private void refreshLibraryRows() {
        if (!"Library".equals(tab) || list == null) return;
        mediaAdapter = new MediaAdapter(filteredLibrary(), "library"); list.setAdapter(mediaAdapter);
        if (libraryStatus != null) libraryStatus.setText(mediaAdapter.getCount() + " videos");
    }
    private void updateSelection() { if (addSelected != null) { addSelected.setVisibility(selection.isEmpty() ? View.GONE : View.VISIBLE); addSelected.setText("Add " + selection.size() + " to playlist"); } }

    private void about() {
        new AlertDialog.Builder(this).setTitle("Ultimate Player 0.1.0")
                .setMessage("Independent episodic video player.\n\nPlayback powered by VideoLAN LibVLC 3.7.7 (LGPL 2.1 or later). Not affiliated with or endorsed by VideoLAN.\n\nThis preview installs separately from EpisodeFlow and IntroSkip. Their playlists stay in those apps.")
                .setPositiveButton("Close", null).setNeutralButton("Licenses", (d,w) -> showLicenses()).show();
    }
    private void showLicenses() {
        try (java.io.InputStream input = getAssets().open("OPEN_SOURCE_NOTICES.txt")) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096]; int count;
            while ((count = input.read(chunk)) != -1) buffer.write(chunk, 0, count);
            for (String license : new String[]{"Lucide-LICENSE.txt", "LGPL-2.1.txt", "Apache-2.0.txt"}) {
                buffer.write(("\n\n" + license + "\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                try (java.io.InputStream terms = getAssets().open(license)) {
                    while ((count = terms.read(chunk)) != -1) buffer.write(chunk, 0, count);
                }
            }
            String text = buffer.toString("UTF-8");
            TextView content = label(text, 13, TEXT, false); content.setTextIsSelectable(true); content.setPadding(dp(20), dp(16), dp(20), dp(16));
            ScrollView scroll = new ScrollView(this); scroll.addView(content);
            new AlertDialog.Builder(this).setTitle("Open-source licenses").setView(scroll).setPositiveButton("Close", null).show();
        } catch (java.io.IOException e) { toast("License information unavailable"); }
    }

    private void bindThumbnail(ImageView image, VideoItem item) {
        image.setTag(item.key()); image.setImageResource(R.drawable.ui_play);
        image.setColorFilter(MUTED); image.setScaleType(ImageView.ScaleType.CENTER);
        Bitmap ready = cache.get(item.key());
        if (ready != null) { image.clearColorFilter(); image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setImageBitmap(ready); return; }
        if (thumbnails.isShutdown()) return;
        thumbnails.execute(() -> {
            Bitmap bitmap = null;
            try {
                if (Build.VERSION.SDK_INT >= 29 && "content".equals(item.uri.getScheme())) bitmap = getContentResolver().loadThumbnail(item.uri, new Size(240, 144), null);
                else {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    try {
                        retriever.setDataSource(this, item.uri);
                        if (Build.VERSION.SDK_INT >= 27) bitmap = retriever.getScaledFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 144);
                        else {
                            Bitmap frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                            if (frame != null) {
                                int height = Math.max(1, Math.round(frame.getHeight() * (240f / frame.getWidth())));
                                bitmap = Bitmap.createScaledBitmap(frame, 240, height, true);
                                if (bitmap != frame) frame.recycle();
                            }
                        }
                    } finally { try { retriever.release(); } catch (Exception ignored) { } }
                }
            } catch (Exception ignored) { }
            if (bitmap == null) return;
            Bitmap result = bitmap; cache.put(item.key(), result);
            main.post(() -> { if (!isDestroyed() && item.key().equals(image.getTag())) { image.clearColorFilter(); image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setImageBitmap(result); } });
        });
    }

    private void switchTab(String target) { rememberScroll(); tab = target; fullscreen = false; render(); }
    private void saveNavigation() { if (store != null) store.preferences.edit().putString("screen", tab).putString("open_playlist", playlistName).apply(); }
    private void rememberScroll() { if (list != null) scrollPositions.put(renderedScreenKey, list.getFirstVisiblePosition()); }
    private void rebuildNav() {
        nav.removeAllViews();
        String[] tabs = {"Player", "Playlists", "Library", "History", "Settings"};
        int[] icons = {R.drawable.ui_play, R.drawable.ui_list_video, R.drawable.ui_search,
                R.drawable.ui_clock, R.drawable.ui_settings_2};
        for (int i=0; i<tabs.length; i++) {
            String name = tabs[i]; LinearLayout item = column(); item.setGravity(Gravity.CENTER); item.setContentDescription(name);
            ImageView symbol = new ImageView(this); symbol.setImageResource(icons[i]); symbol.setColorFilter(tab.equals(name) ? ACCENT : MUTED);
            item.addView(symbol, box(24, 24));
            TextView caption = label(name, 11, tab.equals(name) ? ACCENT : MUTED, tab.equals(name));
            caption.setGravity(Gravity.CENTER); caption.setSingleLine(true);
            item.addView(caption, new LinearLayout.LayoutParams(-1, -2));
            item.setOnClickListener(v -> switchTab(name)); item.setFocusable(true); item.setBackgroundResource(android.R.drawable.list_selector_background);
            nav.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        }
    }

    private String mediaMeta(VideoItem item, boolean watched, boolean current) {
        int position = service == null ? 0 : current ? service.getCurrentPositionMs() : service.getSavedProgressMs(item);
        String state = watched ? "Watched" : (position > 0 ? clock(position) + " / " + clock(item.durationMs) : clock(item.durationMs));
        if (current) state = (service.isPreviewing() ? "Preview" : service.isPlaying() ? "Playing" : "Paused") + "  /  " + state;
        return state;
    }
    private String clean(VideoItem item) { return EpisodeNameParser.displayTitle(item.name); }
    private String totalDuration(List<VideoItem> items) { long total=0; for (VideoItem i:items) total+=i.durationMs; return clock(total); }
    private String clock(long millis) { long s=Math.max(0,millis)/1000; return s>=3600 ? String.format(Locale.US,"%d:%02d:%02d",s/3600,s/60%60,s%60) : String.format(Locale.US,"%d:%02d",s/60,s%60); }
    private int number(EditText input, int min, int max) { try { return Math.max(min,Math.min(max,Integer.parseInt(input.getText().toString()))); } catch(NumberFormatException e) { return min; } }
    private void heading(String text, String subtitle) {
        LinearLayout heading=column(); heading.setPadding(dp(16),dp(12),dp(16),dp(8));
        heading.addView(label(text,20,TEXT,true)); if (!subtitle.isEmpty()) heading.addView(label(subtitle,12,MUTED,false)); body.addView(heading);
    }
    private LinearLayout column() { LinearLayout view=new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view=new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private LinearLayout mediaLine() { LinearLayout view=row(); view.setPadding(dp(16),dp(10),dp(8),dp(10)); view.setMinimumHeight(dp(80)); return view; }
    private TextView label(String value,int size,int color,boolean bold) { TextView view=new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setLetterSpacing(0); if(bold)view.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return view; }
    private TextView empty(String value) { TextView view=label(value,15,MUTED,false); view.setGravity(Gravity.CENTER); view.setPadding(0,dp(20),0,dp(20)); return view; }
    private EditText input(String hint,String value) { EditText view=new EditText(this); view.setText(value); view.setHint(hint); view.setTextSize(16); view.setTextColor(TEXT); view.setHintTextColor(MUTED); view.setSingleLine(); view.setPadding(dp(12),dp(10),dp(12),dp(10)); view.setBackground(shape(PANEL)); view.setMinHeight(dp(48)); return view; }
    private GradientDrawable shape(int color) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(6)); return d; }
    private Button button(String text,boolean primary,View.OnClickListener click) { Button button=new Button(this); button.setText(text); button.setTextSize(14); button.setAllCaps(false); button.setLetterSpacing(0); button.setTextColor(primary?BG:TEXT); button.setBackgroundTintList(ColorStateList.valueOf(primary?ACCENT:PANEL)); button.setMinHeight(dp(48)); button.setPadding(dp(12),dp(6),dp(12),dp(6)); button.setOnClickListener(click); return button; }
    private ImageButton icon(int resource,String description,View.OnClickListener click) { ImageButton view=new ImageButton(this); view.setImageResource(resource); view.setColorFilter(TEXT); view.setContentDescription(description); view.setTooltipText(description); view.setPadding(dp(12),dp(12),dp(12),dp(12)); view.setBackgroundResource(android.R.drawable.list_selector_background); view.setOnClickListener(click); return view; }
    private ImageView thumbnail() { ImageView view=new ImageView(this); view.setBackground(shape(PANEL)); view.setClipToOutline(true); view.setImageResource(R.drawable.ui_play); view.setColorFilter(MUTED); view.setScaleType(ImageView.ScaleType.CENTER); return view; }
    private ListView listView() { ListView view=new ListView(this); view.setDivider(new android.graphics.drawable.ColorDrawable(0xff282a2c)); view.setDividerHeight(dp(1)); view.setCacheColorHint(BG); return view; }
    private LinearLayout.LayoutParams box(int width,int height) { return new LinearLayout.LayoutParams(dp(width),dp(height)); }
    private LinearLayout.LayoutParams weighted(int unused) { return new LinearLayout.LayoutParams(0,-2,1); }
    private LinearLayout.LayoutParams inset() { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(dp(16),dp(4),dp(16),dp(4)); return p; }
    private LinearLayout.LayoutParams spaced() { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(8),0,dp(8)); return p; }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private void toast(String text) { Toast.makeText(this,text,Toast.LENGTH_SHORT).show(); }
}
