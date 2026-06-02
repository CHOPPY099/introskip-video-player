package com.introskip.player;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.DownloadListener;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

public class WebSourceActivity extends Activity {
    private static final String PREFS = "introskip_store";
    private static final String KEY_PLAYLIST_SOURCES = "playlist_sources";

    private String playlistName;
    private TextView urlText;
    private WebView webView;

    @Override
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
        toolbar.setPadding(dp(8), dp(8), dp(8), dp(8));

        Button back = button("Back");
        Button close = button("Close");
        urlText = new TextView(this);
        urlText.setTextColor(Color.rgb(244, 247, 251));
        urlText.setTextSize(12);
        urlText.setSingleLine(true);
        toolbar.addView(back);
        toolbar.addView(urlText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        toolbar.addView(close);
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                saveSourceUrl(request.getUrl().toString());
                return false;
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
        urlText.setText(url);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        try {
            JSONObject sources = new JSONObject(prefs.getString(KEY_PLAYLIST_SOURCES, "{}"));
            sources.put(playlistName, url.trim());
            prefs.edit().putString(KEY_PLAYLIST_SOURCES, sources.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(Color.rgb(244, 247, 251));
        button.setTextSize(13);
        button.setBackgroundColor(Color.rgb(35, 41, 51));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
