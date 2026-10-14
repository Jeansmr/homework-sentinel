package com.homesentinel.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * 应用主界面：一个承载网页界面的 WebView，外加一座连接手机系统能力的桥。
 *
 * 桥（window.HWSentinel）暴露给网页层的方法：
 *   syncReminders(json)  —— 把提醒计划交给系统闹钟，App 关掉也能提醒
 *   notify(title, body)  —— 立刻发一条通知
 *   hasPermission()      —— 是否已获得通知权限
 *   requestPermission()  —— 申请通知权限（首次启动时已自动申请）
 *   sourceName()/sourceDesc() —— 数据来源说明
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    public static final String EXTRA_FOCUS_KEY = "focus_key";

    private static final String ASSET_DIR = "www";
    private static final int REQ_NOTIFICATION = 1001;

    private WebView web;
    private String pendingFocusKey;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        NotificationHelper.ensureChannels(this);

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(Color.parseColor("#F5F6FB"));
        root.setFitsSystemWindows(true);

        web = new WebView(this);
        web.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.setBackgroundColor(Color.parseColor("#F5F6FB"));
        root.addView(web);
        setContentView(root);

        configureWebView();

        // 通知权限：安卓 13 起需要用户点头。首次启动就问一次。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !NotificationHelper.canPost(this)) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        }

        handleIntent(getIntent());

        web.loadUrl("file:///android_asset/" + ASSET_DIR + "/index.html");

        // 把上次保存的提醒重新登记一遍（防止系统清理掉已登记的闹钟）
        ReminderScheduler.rescheduleAll(getApplicationContext());
    }

    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        // 本地账号密码存在 localStorage 里，必须打开
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadsImagesAutomatically(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        // 界面完全在本地，不需要联网
        s.setBlockNetworkLoads(false);

        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 让网页知道当前是浅色主题，表单控件才不会变黑
            web.getSettings().setForceDark(WebSettings.FORCE_DARK_OFF);
        }

        web.addJavascriptInterface(new NativeBridge(), "HWSentinel");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (pendingFocusKey != null) {
                    String key = pendingFocusKey;
                    pendingFocusKey = null;
                    view.evaluateJavascript(
                            "window.HWSentinelFocus && window.HWSentinelFocus(" + jsString(key) + ")", null);
                }
            }
        });
    }

    /** 站内跳转留在 WebView，外部链接交给系统浏览器 */
    private boolean handleUrl(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        if ("file".equals(scheme) || "about".equals(scheme) || "data".equals(scheme)
                || "blob".equals(scheme) || "javascript".equals(scheme)) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(this, "打不开这个链接", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String key = intent.getStringExtra(EXTRA_FOCUS_KEY);
        if (key != null && !key.isEmpty()) pendingFocusKey = key;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        if (pendingFocusKey != null && web != null) {
            String key = pendingFocusKey;
            pendingFocusKey = null;
            web.evaluateJavascript(
                    "window.HWSentinelFocus && window.HWSentinelFocus(" + jsString(key) + ")", null);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_NOTIFICATION) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (web != null) {
                web.evaluateJavascript("window.HWSentinelWeb && window.HWSentinelWeb.reload()", null);
            }
            Toast.makeText(this, granted ? "通知已开启，作业到期前会提醒你" : "未开启通知，可在系统设置里打开",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
            web.evaluateJavascript("window.HWSentinelWeb && window.HWSentinelWeb.reload()", null);
        }
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    /** 把字符串安全地嵌进 JS 代码里 */
    private static String jsString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }

    /* =====================================================================
     * 网页 ↔ 原生 的桥
     * ===================================================================== */

    public class NativeBridge {

        /** 网页层把提醒计划交过来：登记系统闹钟 */
        @JavascriptInterface
        public void syncReminders(final String planJson) {
            Log.i(TAG, "收到提醒计划，长度 " + (planJson == null ? 0 : planJson.length()));
            ReminderStore.save(planJson);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ReminderScheduler.rescheduleAll(getApplicationContext());
                }
            });
        }

        /** 立刻发一条通知 */
        @JavascriptInterface
        public void notify(final String title, final String body, final String tag) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ReminderScheduler.notifyNow(MainActivity.this,
                            title == null ? "作业提醒" : title,
                            body == null ? "" : body);
                }
            });
        }

        @JavascriptInterface
        public boolean hasPermission() {
            return NotificationHelper.canPost(MainActivity.this);
        }

        @JavascriptInterface
        public boolean requestPermission() {
            final boolean granted = NotificationHelper.canPost(MainActivity.this);
            if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        requestPermissions(
                                new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
                    }
                });
            }
            return granted;
        }

        @JavascriptInterface
        public String sourceName() {
            return "本机手动录入";
        }

        @JavascriptInterface
        public String sourceDesc() {
            return "作业由你自己添加，数据只存在这台手机上，不上传服务器。";
        }

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, msg == null ? "" : msg, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
}
