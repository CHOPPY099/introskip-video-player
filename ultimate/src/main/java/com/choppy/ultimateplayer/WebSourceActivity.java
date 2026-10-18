package com.choppy.ultimateplayer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.security.NetworkSecurityPolicy;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

public class WebSourceActivity extends Activity {
    private static final String PREFS = "ultimate_store";
    private static final String KEY_PLAYLIST_SOURCES = "playlist_sources";
    private static final String STATE_WEB = "source_browser.web";
    private static final String STATE_VISITED = "source_browser.visited";
    private static final String STATE_DOWNLOADS = "source_browser.downloads";
    private static final String STATE_FAILED = "source_browser.failed";
    private static final int BACKGROUND = Color.rgb(18, 20, 22);
    private static final int SURFACE = Color.rgb(37, 40, 43);
    private static final int TEXT = Color.rgb(242, 245, 244);
    private static final int MUTED = Color.rgb(183, 192, 188);
    private static final int ACCENT = Color.rgb(100, 216, 163);

    private String playlistName;
    private String lastVisitedUrl;
    private String loadingUrl;
    private String downloadNavigationUrl;
    private EditText urlInput;
    private WebView webView;
    private ProgressBar pageProgress;
    private TextView errorText;
    private ImageButton backButton;
    private ImageButton forwardButton;
    private boolean pageLoading;
    private boolean pageFailed;
    private AlertDialog downloadDialog;
    private boolean receiverRegistered;
    private final Set<Long> pendingDownloads = new HashSet<>();
    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                checkDownload(intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        playlistName = getIntent().getStringExtra("playlist");
        if (playlistName == null || playlistName.trim().isEmpty()) playlistName = "Default";

        LinearLayout root = createBrowserLayout();
        configureWebView();
        setContentView(root);
        applySafeAreas(root);
        registerDownloadReceiver();

        if (savedInstanceState != null) {
            lastVisitedUrl = BrowserUrlPolicy.webUrl(savedInstanceState.getString(STATE_VISITED));
            pageFailed = savedInstanceState.getBoolean(STATE_FAILED);
            long[] downloads = savedInstanceState.getLongArray(STATE_DOWNLOADS);
            if (downloads != null) {
                for (long id : downloads) pendingDownloads.add(id);
            }
            Bundle webState = savedInstanceState.getBundle(STATE_WEB);
            if (lastVisitedUrl != null && webState != null && webView.restoreState(webState) != null) {
                if (BrowserUrlPolicy.webUrl(webView.getUrl()) != null) {
                    updateAddress(webView.getUrl());
                    updateHistoryButtons();
                    return;
                }
                webView.clearHistory();
            }
        }

        String startUrl = getIntent().getStringExtra("url");
        if (startUrl == null || startUrl.trim().isEmpty()) startUrl = readSourceUrl();
        if (startUrl != null && !startUrl.trim().isEmpty()) {
            urlInput.setText(startUrl);
            navigate(startUrl);
        }
        updateHistoryButtons();
    }

    private LinearLayout createBrowserLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        root.setFocusableInTouchMode(true);

        LinearLayout toolbar = horizontalBar();
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("Source browser");
        title.setTextColor(TEXT);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        TextView playlist = new TextView(this);
        playlist.setText(playlistName);
        playlist.setTextColor(MUTED);
        playlist.setTextSize(13);
        playlist.setSingleLine(true);
        playlist.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(title);
        labels.addView(playlist);
        toolbar.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton close = iconButton(R.drawable.ui_x, "Close browser");
        close.setOnClickListener(view -> finish());
        toolbar.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(toolbar);

        LinearLayout addressBar = horizontalBar();
        urlInput = new EditText(this);
        urlInput.setTextColor(TEXT);
        urlInput.setHintTextColor(MUTED);
        urlInput.setTextSize(15);
        urlInput.setSingleLine(true);
        urlInput.setSelectAllOnFocus(true);
        urlInput.setHint("https://example.com");
        urlInput.setContentDescription("Website address");
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        urlInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        urlInput.setBackground(roundedBackground(SURFACE));
        urlInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        urlInput.setMinimumHeight(dp(48));
        urlInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                openTypedAddress();
                return true;
            }
            return false;
        });
        addressBar.addView(urlInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button open = new Button(this);
        open.setText("Open");
        open.setTextSize(14);
        open.setAllCaps(false);
        open.setTextColor(BACKGROUND);
        open.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), roundedBackground(ACCENT), null));
        open.setMinWidth(0);
        open.setMinimumWidth(0);
        open.setMinHeight(dp(48));
        open.setPadding(dp(14), 0, dp(14), 0);
        open.setOnClickListener(view -> openTypedAddress());
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        openParams.setMarginStart(dp(8));
        addressBar.addView(open, openParams);
        root.addView(addressBar);

        pageProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pageProgress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        pageProgress.setProgressBackgroundTintList(ColorStateList.valueOf(SURFACE));
        pageProgress.setVisibility(View.INVISIBLE);
        root.addView(pageProgress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
        errorText = new TextView(this);
        errorText.setTextColor(TEXT);
        errorText.setTextSize(14);
        errorText.setPadding(dp(12), dp(8), dp(12), dp(8));
        errorText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        errorText.setVisibility(View.GONE);
        root.addView(errorText);

        webView = new WebView(this);
        webView.setBackgroundColor(BACKGROUND);
        root.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout navigation = horizontalBar();
        backButton = iconButton(R.drawable.ui_skip_back, "Back in web history");
        forwardButton = iconButton(R.drawable.ui_skip_forward, "Forward in web history");
        ImageButton reload = iconButton(R.drawable.ui_refresh_cw, "Reload page");
        ImageButton external = iconButton(R.drawable.ui_external_link, "Open in external browser");
        backButton.setOnClickListener(view -> goBack());
        forwardButton.setOnClickListener(view -> {
            if (webView.canGoForward()) {
                leaveAddressEntry();
                webView.goForward();
            }
        });
        reload.setOnClickListener(view -> {
            leaveAddressEntry();
            String current = BrowserUrlPolicy.webUrl(webView.getUrl());
            if (current != null && isPermittedByNetworkPolicy(current)) webView.reload();
            else navigate(urlInput.getText().toString());
        });
        external.setOnClickListener(view -> openExternalBrowser(urlInput.getText().toString()));
        for (ImageButton button : new ImageButton[]{backButton, forwardButton, reload, external}) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
            params.setMarginStart(dp(3));
            params.setMarginEnd(dp(3));
            navigation.addView(button, params);
        }
        root.addView(navigation);
        root.requestFocus();
        return root;
    }

    @SuppressWarnings("deprecation")
    private void applySafeAreas(View root) {
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                int keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom;
                view.setPadding(safe.left, safe.top, safe.right, Math.max(safe.bottom, keyboard));
                return WindowInsets.CONSUMED;
            }
            int left = insets.getSystemWindowInsetLeft();
            int top = insets.getSystemWindowInsetTop();
            int right = insets.getSystemWindowInsetRight();
            int bottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && insets.getDisplayCutout() != null) {
                left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft());
                top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
                right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
                bottom = Math.max(bottom, insets.getDisplayCutout().getSafeInsetBottom());
            }
            view.setPadding(left, top, right, bottom);
            WindowInsets consumed = insets.consumeSystemWindowInsets();
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? consumed.consumeDisplayCutout() : consumed;
        });
        root.requestApplyInsets();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSafeBrowsingEnabled(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                pageProgress.setProgress(progress);
                pageProgress.setVisibility(progress >= 100 || pageFailed ? View.INVISIBLE : View.VISIBLE);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String target = BrowserUrlPolicy.webUrl(request.getUrl().toString());
                if (target == null) {
                    if (request.isForMainFrame() && request.hasGesture()) {
                        showError("Only HTTP and HTTPS links can open here.");
                    }
                    return true;
                }
                if (!isPermittedByNetworkPolicy(target)) {
                    if (request.isForMainFrame()) {
                        updateAddress(target);
                        showError("This app does not allow this HTTP connection. Use HTTPS or open in an external browser.");
                    }
                    return true;
                }
                if (request.isForMainFrame()) loadingUrl = target;
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                pageLoading = true;
                pageFailed = false;
                downloadNavigationUrl = null;
                errorText.setVisibility(View.GONE);
                String target = BrowserUrlPolicy.webUrl(url);
                loadingUrl = target;
                if (target == null || !isPermittedByNetworkPolicy(target)) {
                    pageFailed = true;
                    view.stopLoading();
                    showError("This address cannot open here. Use an allowed HTTP or HTTPS address.");
                    return;
                }
                updateAddress(target);
                updateHistoryButtons();
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                rememberVisitedPage(view, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Ignore stale completions from a navigation that was superseded.
                if (url == null || !url.equals(view.getUrl())) return;
                pageLoading = false;
                rememberVisitedPage(view, url);
                pageProgress.setVisibility(View.INVISIBLE);
                updateHistoryButtons();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                // Same-document history changes do not necessarily finish another page load.
                if (!pageLoading && lastVisitedUrl != null) rememberVisitedPage(view, url);
                updateHistoryButtons();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                pageFailed = true;
                showError("The page could not load. Check your connection, reload, or open in an external browser.");
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (!request.isForMainFrame()) return;
                pageFailed = true;
                showError("The site returned an error (" + response.getStatusCode()
                        + "). Try reloading or opening it in an external browser.");
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                if (error.getUrl() != null && (error.getUrl().equals(view.getUrl()) || error.getUrl().equals(loadingUrl))) {
                    pageFailed = true;
                    showError("A secure connection could not be verified. Check the address or try again later.");
                }
            }
        });
        webView.setDownloadListener(downloadListener());
    }

    private void openTypedAddress() {
        String typed = urlInput.getText().toString();
        if (BrowserUrlPolicy.webUrl(typed) == null) {
            urlInput.setError("Enter a full HTTP or HTTPS address");
            return;
        }
        leaveAddressEntry();
        navigate(typed);
    }

    private void leaveAddressEntry() {
        urlInput.clearFocus();
        webView.requestFocus();
        InputMethodManager input = getSystemService(InputMethodManager.class);
        if (input != null) input.hideSoftInputFromWindow(urlInput.getWindowToken(), 0);
    }

    private void navigate(String rawUrl) {
        String url = BrowserUrlPolicy.webUrl(rawUrl);
        if (url == null) {
            showError("Enter a full HTTP or HTTPS address.");
            return;
        }
        updateAddress(url);
        if (!isPermittedByNetworkPolicy(url)) {
            showError("This app does not allow this HTTP connection. Use HTTPS or open in an external browser.");
            return;
        }
        urlInput.setError(null);
        loadingUrl = url;
        pageLoading = true;
        webView.loadUrl(url);
    }

    private boolean isPermittedByNetworkPolicy(String url) {
        Uri target = Uri.parse(url);
        return !"http".equalsIgnoreCase(target.getScheme())
                || NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(target.getHost());
    }

    private void rememberVisitedPage(WebView view, String url) {
        if (!BrowserUrlPolicy.canRememberVisit(url, view.getUrl(), pageFailed)
                || url.equals(downloadNavigationUrl)) return;
        lastVisitedUrl = url;
        updateAddress(url);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        try {
            // Read immediately before merging so other playlists are not replaced by a stale map.
            JSONObject sources = new JSONObject(prefs.getString(KEY_PLAYLIST_SOURCES, "{}"));
            if (url.equals(sources.optString(playlistName))) return;
            sources.put(playlistName, url);
            prefs.edit().putString(KEY_PLAYLIST_SOURCES, sources.toString()).apply();
        } catch (JSONException ignored) {
            showError("The page opened, but its address could not be saved.");
        }
    }

    private String readSourceUrl() {
        try {
            JSONObject sources = new JSONObject(getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_PLAYLIST_SOURCES, "{}"));
            return BrowserUrlPolicy.webUrl(sources.optString(playlistName));
        } catch (JSONException ignored) {
            return null;
        }
    }

    private DownloadListener downloadListener() {
        return (rawUrl, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (isFinishing() || isDestroyed()) return;
            downloadNavigationUrl = rawUrl;
            pageLoading = false;
            pageProgress.setVisibility(View.INVISIBLE);
            updateAddress(lastVisitedUrl);
            updateHistoryButtons();
            if (downloadDialog != null) return;
            String url = BrowserUrlPolicy.webUrl(rawUrl);
            if (url == null) {
                showError("This download is not an HTTP or HTTPS file. Open the page in an external browser to continue.");
                return;
            }
            String fileName = BrowserUrlPolicy.safeFileName(URLUtil.guessFileName(url, contentDisposition, mimeType));
            String referer = BrowserUrlPolicy.downloadReferer(lastVisitedUrl, url);
            String agent = webView.getSettings().getUserAgentString();
            String size = contentLength > 0 ? "\nSize: " + Formatter.formatFileSize(this, contentLength) : "";
            boolean cleartextAllowed = isPermittedByNetworkPolicy(url);
            boolean storageAllowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            String message = fileName + "\nFrom: " + Uri.parse(url).getHost() + size + "\nSave to: Downloads";
            if (!cleartextAllowed) {
                message += "\n\nThis app does not allow this HTTP connection. You can open the link in an external browser.";
            } else if (!storageAllowed) {
                message += "\n\nStorage access is unavailable on this Android version. Use an external browser to download.";
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setTitle("Download file?")
                    .setMessage(message)
                    .setNegativeButton("Cancel", null)
                    .setNeutralButton("Open browser", (dialog, which) -> openExternalBrowser(url));
            if (cleartextAllowed && storageAllowed) {
                builder.setPositiveButton("Download", (dialog, which) -> enqueueDownload(url, agent, referer, mimeType, fileName));
            }
            downloadDialog = builder.create();
            downloadDialog.setOnDismissListener(dialog -> downloadDialog = null);
            downloadDialog.show();
            if (downloadDialog.getWindow() != null) downloadDialog.getWindow().setBackgroundDrawable(roundedBackground(SURFACE));
            for (int button : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
                Button action = downloadDialog.getButton(button);
                if (action != null) action.setTextColor(ACCENT);
            }
        };
    }

    private void enqueueDownload(String url, String userAgent, String referer, String mimeType, String fileName) {
        if (BrowserUrlPolicy.webUrl(url) == null || !isPermittedByNetworkPolicy(url)) {
            showError("This download address is not allowed in this app.");
            return;
        }
        try {
            DownloadManager manager = getSystemService(DownloadManager.class);
            if (manager == null) {
                showError("Android Downloads is unavailable. Open the link in an external browser.");
                return;
            }
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url).normalizeScheme());
            request.setTitle(fileName);
            request.setDescription("Ultimate Player download");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            if (BrowserUrlPolicy.isSafeHeader(mimeType)) request.setMimeType(mimeType);
            if (BrowserUrlPolicy.isSafeHeader(userAgent)) request.addRequestHeader("User-Agent", userAgent);
            if (BrowserUrlPolicy.isSafeHeader(referer)) request.addRequestHeader("Referer", referer);
            // Use only this app's WebView cookie jar, scoped to the actual download URL.
            String cookies = CookieManager.getInstance().getCookie(url);
            if (BrowserUrlPolicy.isSafeHeader(cookies)) request.addRequestHeader("Cookie", cookies);
            long id = manager.enqueue(request);
            if (id < 0) {
                showError("The download could not start. Try again or open the link in an external browser.");
                return;
            }
            pendingDownloads.add(id);
            Toast.makeText(this, "Download started. Check Downloads for progress.", Toast.LENGTH_LONG).show();
        } catch (RuntimeException error) {
            // Never include exception messages: download URLs and headers can contain credentials.
            showError("The download could not start. Check storage space or open the link in an external browser.");
        }
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerDownloadReceiver() {
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
        // Older platforms have no exported/not-exported receiver contract; IDs are checked below.
        else registerReceiver(downloadReceiver, filter);
        receiverRegistered = true;
    }

    private void checkDownload(long id) {
        if (!pendingDownloads.contains(id)) return;
        DownloadManager manager = getSystemService(DownloadManager.class);
        if (manager == null) return;
        // Query our recorded request rather than trusting status supplied by a broadcast.
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) return;
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                pendingDownloads.remove(id);
                Toast.makeText(this, "File saved to Downloads", Toast.LENGTH_SHORT).show();
            } else if (status == DownloadManager.STATUS_FAILED) {
                pendingDownloads.remove(id);
                int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) {
                    showError("Download failed: not enough storage space. Free some space and try again.");
                } else if (reason == DownloadManager.ERROR_FILE_ALREADY_EXISTS) {
                    showError("A file with this name is already in Downloads. Rename or remove it before trying again.");
                } else {
                    showError("Download failed. The link may have expired or need sign-in. Try again from the site or use an external browser.");
                }
            }
        } catch (RuntimeException error) {
            showError("Download status is unavailable. Check Android Downloads.");
        }
    }

    private void openExternalBrowser(String rawUrl) {
        String url = BrowserUrlPolicy.webUrl(rawUrl);
        if (url == null) {
            showError("Enter an HTTP or HTTPS address before opening an external browser.");
            return;
        }
        try {
            Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            browser.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(Intent.createChooser(browser, "Open in browser"));
        } catch (ActivityNotFoundException | SecurityException error) {
            showError("No browser is available to open this address.");
        }
    }

    private void updateAddress(String url) {
        if (!urlInput.hasFocus() && url != null) urlInput.setText(url);
    }

    private void updateHistoryButtons() {
        backButton.setEnabled(webView.canGoBack());
        backButton.setAlpha(backButton.isEnabled() ? 1f : 0.35f);
        forwardButton.setEnabled(webView.canGoForward());
        forwardButton.setAlpha(forwardButton.isEnabled() ? 1f : 0.35f);
    }

    private boolean goBack() {
        if (webView == null || !webView.canGoBack()) return false;
        leaveAddressEntry();
        webView.goBack();
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!goBack()) super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        if (webView != null) {
            Bundle webState = new Bundle();
            webView.saveState(webState);
            state.putBundle(STATE_WEB, webState);
        }
        state.putString(STATE_VISITED, lastVisitedUrl);
        state.putBoolean(STATE_FAILED, pageFailed);
        long[] downloads = new long[pendingDownloads.size()];
        int index = 0;
        for (long id : pendingDownloads) downloads[index++] = id;
        state.putLongArray(STATE_DOWNLOADS, downloads);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        for (long id : new HashSet<>(pendingDownloads)) checkDownload(id);
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (downloadDialog != null) downloadDialog.dismiss();
        if (receiverRegistered) unregisterReceiver(downloadReceiver);
        if (webView != null) {
            webView.setDownloadListener(null);
            webView.setWebChromeClient(null);
            webView.setWebViewClient(new WebViewClient());
            webView.stopLoading();
            if (webView.getParent() instanceof ViewGroup) ((ViewGroup) webView.getParent()).removeView(webView);
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void showError(String message) {
        errorText.setText(message);
        errorText.setVisibility(View.VISIBLE);
        pageProgress.setVisibility(View.INVISIBLE);
    }

    private LinearLayout horizontalBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(6), dp(10), dp(6));
        bar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private GradientDrawable roundedBackground(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(6));
        return background;
    }

    private ImageButton iconButton(int iconResource, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconResource);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setColorFilter(ACCENT);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3364D8A3), roundedBackground(SURFACE), null));
        button.setPadding(dp(13), dp(13), dp(13), dp(13));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
