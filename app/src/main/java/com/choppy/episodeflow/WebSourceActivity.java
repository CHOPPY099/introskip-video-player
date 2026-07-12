package com.choppy.episodeflow;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

public class WebSourceActivity extends Activity {
    private static final String PREFS = "episodeflow_store";
    private static final String KEY_PLAYLIST_SOURCES = "playlist_sources";

    private String playlistName;
    private TextView urlText;
    private WebView webView;
    private ProgressBar pageProgress;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        playlistName = getIntent().getStringExtra("playlist");
        String startUrl = getIntent().getStringExtra("url");
        if (playlistName == null || playlistName.trim().isEmpty()) playlistName = "Default";
        if (startUrl == null || startUrl.trim().isEmpty()) {
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(17, 19, 23));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(10), dp(8), dp(10), dp(8));

        ImageButton back = iconButton(android.R.drawable.ic_media_previous, "Back");
        ImageButton close = iconButton(android.R.drawable.ic_menu_close_clear_cancel, "Close browser");
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(10), 0, dp(10), 0);
        TextView title = new TextView(this);
        title.setText("Episode source");
        title.setTextColor(Color.rgb(244, 248, 247));
        title.setTextSize(17);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        urlText = new TextView(this);
        urlText.setTextColor(Color.rgb(170, 182, 179));
        urlText.setTextSize(11);
        urlText.setSingleLine(true);
        labels.addView(title);
        labels.addView(urlText);
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        toolbar.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        toolbar.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        pageProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pageProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(47, 209, 181)));
        pageProgress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(48, 59, 63)));
        root.addView(pageProgress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                pageProgress.setProgress(newProgress);
                pageProgress.setVisibility(newProgress >= 100 ? ProgressBar.GONE : ProgressBar.VISIBLE);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl();
                String scheme = target.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    saveSourceUrl(target.toString());
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, target));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                saveSourceUrl(url);
            }
        });
        webView.setDownloadListener(downloadListener());
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        back.setOnClickListener(v -> {
            if (webView.canGoBack()) {
                webView.goBack();
            } else {
                finish();
            }
        });
        close.setOnClickListener(v -> finish());

        setContentView(root);
        webView.loadUrl(startUrl);
        saveSourceUrl(startUrl);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            saveSourceUrl(webView.getUrl());
            webView.destroy();
        }
        super.onDestroy();
    }

    private DownloadListener downloadListener() {
        return (url, userAgent, contentDisposition, mimeType, contentLength) -> {
            saveSourceUrl(webView == null ? url : webView.getUrl());
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception error) {
                Toast.makeText(this, "Could not open download link", Toast.LENGTH_SHORT).show();
            }
        };
    }

    private void saveSourceUrl(String url) {
        if (url == null || url.trim().isEmpty() || playlistName == null) return;
        String host = Uri.parse(url).getHost();
        urlText.setText(host == null || host.isEmpty() ? url : host);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        try {
            JSONObject sources = new JSONObject(prefs.getString(KEY_PLAYLIST_SOURCES, "{}"));
            sources.put(playlistName, url.trim());
            prefs.edit().putString(KEY_PLAYLIST_SOURCES, sources.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    private ImageButton iconButton(int iconResource, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconResource);
        button.setContentDescription(description);
        button.setColorFilter(Color.rgb(244, 248, 247));
        button.setBackgroundResource(R.drawable.button_secondary);
        button.setPadding(dp(13), dp(13), dp(13), dp(13));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
