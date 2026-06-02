package com.introskip.player;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements BackgroundPlayerService.PlayerListener {
    private static final int REQUEST_PERMISSIONS = 100;
    private static final int REQUEST_PICK_VIDEOS = 101;

    private final ArrayList<VideoItem> libraryVideos = new ArrayList<>();
    private BackgroundPlayerService playerService;
    private boolean serviceBound = false;
    private boolean videoFullscreen = false;
    private TextureView textureView;
    private Surface playbackSurface;
    private GestureDetector videoGestureDetector;
    private LinearLayout rootLayout;
    private TextView nowPlayingText;
    private TextView counterText;
    private Button playPauseButton;
    private EditText searchInput;
    private EditText minutesInput;
    private EditText secondsInput;
    private CheckBox autoplayCheck;
    private LinearLayout libraryList;
    private LinearLayout playlistList;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            playerService = ((BackgroundPlayerService.LocalBinder) service).getService();
            playerService.setListener(MainActivity.this);
            attachPlaybackSurface();
            playerService.setAutoplayNext(autoplayCheck.isChecked());
            applySkipTime();
            serviceBound = true;
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
        Intent serviceIntent = new Intent(this, BackgroundPlayerService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        requestNeededPermissions();
    }

    @Override
    protected void onDestroy() {
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
        runOnUiThread(this::refreshPlaylist);
        runOnUiThread(this::refreshPlayerHeader);
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

        textureView = new TextureView(this);
        LinearLayout.LayoutParams videoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(210)
        );
        textureView.setBackgroundColor(Color.BLACK);
        rootLayout.addView(textureView, videoParams);
        setupVideoGestures();
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                releasePlaybackSurface();
                playbackSurface = new Surface(surfaceTexture);
                attachPlaybackSurface();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
                attachPlaybackSurface();
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
        playPauseButton.setOnClickListener(v -> {
            if (playerService == null) return;
            if (playerService.isPlaying()) {
                playerService.pause();
            } else {
                if (playerService.getPlaylist().isEmpty()) return;
                startPlaybackServiceIfNeeded();
                playerService.play();
            }
        });
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
        minutesInput = input("3");
        secondsInput = input("0");
        minutesInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        secondsInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        timeRow.addView(labeledInput("Min", minutesInput), weightParams());
        timeRow.addView(labeledInput("Sec", secondsInput), weightParams());
        options.addView(timeRow);

        TextWatcher timeWatcher = new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                applySkipTime();
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

        TextView playlistTitle = sectionTitle("Playlist - play order");
        playlistTitle.setPadding(0, dp(18), 0, dp(8));
        rootLayout.addView(playlistTitle);
        playlistList = new LinearLayout(this);
        playlistList.setOrientation(LinearLayout.VERTICAL);
        rootLayout.addView(playlistList);

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

    private void setupVideoGestures() {
        videoGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent event) {
                float x = event.getX();
                int width = Math.max(1, textureView.getWidth());
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
    }

    private void setVideoFullscreen(boolean fullscreen) {
        videoFullscreen = fullscreen;
        int height = fullscreen ? getResources().getDisplayMetrics().heightPixels : dp(210);
        textureView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height
        ));

        for (int i = 0; i < rootLayout.getChildCount(); i++) {
            View child = rootLayout.getChildAt(i);
            if (child != textureView) {
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
            textureView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            textureView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
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
        refreshLibrary();
        refreshPlaylist();
    }

    private void refreshPlayerHeader() {
        if (playerService == null) return;
        VideoItem current = playerService.getCurrentItem();
        nowPlayingText.setText(current == null ? "Nothing loaded" : current.name);
        int index = playerService.getCurrentIndex();
        int total = playerService.getPlaylist().size();
        counterText.setText(total == 0 ? "0 / 0" : String.format(Locale.US, "%d / %d", index + 1, total));
        playPauseButton.setText(playerService.isPlaying() ? "Pause" : "Play");
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
                    refreshPlaylist();
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
            LinearLayout row = videoRow(item, index == current ? "Playing" : "Play", watched, (index + 1) + ". ", v -> {
                if (playerService != null) {
                    startPlaybackServiceIfNeeded();
                    playerService.playIndex(index);
                }
            });
            Button remove = secondaryButton("Remove");
            remove.setOnClickListener(v -> {
                if (playerService != null) playerService.removeFromPlaylist(index);
            });
            row.addView(remove);
            playlistList.addView(row);
        }
        refreshPlayerHeader();
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
        String duration = item.durationMs > 0
                ? String.format(Locale.US, "%d:%02d", item.durationMs / 60000, (item.durationMs / 1000) % 60)
                : "Picked file";
        String size = item.sizeBytes > 0
                ? String.format(Locale.US, "%.1f MB", item.sizeBytes / 1024f / 1024f)
                : "";
        return size.isEmpty() ? duration : duration + " - " + size;
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
