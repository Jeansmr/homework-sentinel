package com.homesentinel.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 应用主界面：承载网页界面的 WebView，外加一座连接手机系统能力的桥。
 *
 * 设计要点（踩过的坑都在这）：
 *  - 启动过程全程有异常保护。国产 ROM 上 WebView 可能缺失或被禁用，
 *    直接 new WebView() 会抛异常导致「一点开就闪退」。
 *    现在改成：出任何问题都显示一个能看懂的错误页，并附上技术细节，
 *    而不是让用户面对一个莫名其妙的闪退。
 *  - 全局未捕获异常也会被接住，展示错误页（同时仍然写入系统日志）。
 *
 * 暴露给网页层的桥（window.HWSentinel）：
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
    /** 上一次崩溃的记录文件，下次启动时读出来给用户看 */
    private static final String CRASH_FILE = "last-crash.txt";

    private WebView web;
    private String pendingFocusKey;
    private View errorView;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        installCrashHandler();

        // 先做自检：系统缺 WebView 是这类「一打开就闪退」最常见的原因
        String preflight = preflightCheck();
        if (preflight != null) {
            Log.e(TAG, "自检未通过: " + preflight);
            showCrashScreen(null, buildPreflightDetail(preflight));
            return;
        }

        try {
            NotificationHelper.ensureChannels(this);
        } catch (Throwable t) {
            Log.w(TAG, "创建通知渠道失败（不致命）: " + t);
        }

        try {
            buildUi();
        } catch (Throwable t) {
            Log.e(TAG, "界面初始化失败", t);
            showCrashScreen(t);
            return;
        }

        // 上次崩溃过？先把原因摆出来，别让用户再面对一次闪退
        String lastCrash = readPendingCrash();
        if (lastCrash != null) {
            showCrashScreen(null, lastCrash);
            return;
        }

        handleIntent(getIntent());

        // 把上次保存的提醒重新登记一遍（防止系统清理掉已登记的闹钟）
        try {
            ReminderScheduler.rescheduleAll(getApplicationContext());
        } catch (Throwable t) {
            Log.w(TAG, "重新登记提醒失败（不致命）: " + t);
        }
    }

    // =====================================================================
    // 启动前的自检：先把「系统缺 WebView」这种硬伤查出来
    // =====================================================================

    /**
     * 检查系统的 WebView 组件是否可用。
     *
     * 国产 ROM 上 WebView 有可能被精简、禁用或替换成不完整的内核，
     * 这时 new WebView() 会直接抛异常，表现就是「一点开就闪退」。
     * 提前查一次，就能把原因说清楚，而不是让用户面对一个闪退。
     *
     * @return null 表示正常，否则返回给用户看的说明文字
     */
    private String preflightCheck() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;   // 26 以下查不了
        try {
            android.content.pm.PackageInfo pi = WebView.getCurrentWebViewPackage();
            if (pi == null) {
                return "系统里没有找到可用的 WebView 组件。";
            }
            Log.i(TAG, "WebView 内核: " + pi.packageName + " " + pi.versionName);
            return null;
        } catch (Throwable t) {
            return "检查 WebView 组件时出错：" + t;
        }
    }

    private String buildPreflightDetail(String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("系统: Android ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("型号: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("应用版本: ").append(APP_VERSION_NAME()).append("\n\n");
        sb.append("—— 自检结果 ——\n").append(reason).append('\n');
        sb.append("\n这个 App 的界面是用系统 WebView 渲染的，缺少它就无法启动。\n");
        sb.append("请按上面的步骤 1、2 恢复 WebView 后重新打开。");
        return sb.toString();
    }

    // =====================================================================
    // 界面搭建
    // =====================================================================

    /** 先放错误页，再尝试放 WebView。WebView 建不起来时，错误页就是最终界面。 */
    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(Color.parseColor("#F5F6FB"));

        errorView = buildErrorView();
        errorView.setVisibility(View.GONE);
        root.addView(errorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        // WebView 构造失败大多发生在这一步（系统缺少 WebView 组件、被 ROM 精简等）
        WebView created;
        try {
            created = new WebView(this);
        } catch (Throwable t) {
            Log.e(TAG, "创建 WebView 失败", t);
            showCrashScreen(t);
            return;
        }

        created.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        created.setBackgroundColor(Color.parseColor("#F5F6FB"));
        root.addView(created);
        web = created;

        try {
            configureWebView();
            web.loadUrl("file:///android_asset/" + ASSET_DIR + "/index.html");
        } catch (Throwable t) {
            Log.e(TAG, "初始化 WebView 设置失败", t);
            showCrashScreen(t);
            return;
        }

        // 通知权限：安卓 13 起需要用户点头。首次启动就问一次。
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && !NotificationHelper.canPost(this)) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
            }
        } catch (Throwable t) {
            Log.w(TAG, "申请通知权限失败（不致命）: " + t);
        }
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
        s.setBlockNetworkLoads(false);

        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 网页里已经自己做了浅色主题，这里关掉系统强制暗色，避免表单控件变黑
                web.getSettings().setForceDark(WebSettings.FORCE_DARK_OFF);
            }
        } catch (Throwable ignored) {
            // 个别 ROM 上这个方法不可用，忽略即可
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
                    evalJs("window.HWSentinelFocus && window.HWSentinelFocus(" + jsString(key) + ")");
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

    /** 安全地执行一段 JS：WebView 不存在或已销毁时静默跳过 */
    private void evalJs(String js) {
        if (web == null) return;
        try {
            web.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Log.w(TAG, "执行 JS 失败（不致命）: " + t);
        }
    }

    // =====================================================================
    // 生命周期
    // =====================================================================

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        if (pendingFocusKey != null) {
            String key = pendingFocusKey;
            pendingFocusKey = null;
            evalJs("window.HWSentinelFocus && window.HWSentinelFocus(" + jsString(key) + ")");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_NOTIFICATION) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            evalJs("window.HWSentinelWeb && window.HWSentinelWeb.reload()");
            Toast.makeText(this, granted ? "通知已开启，作业到期前会提醒你" : "未开启通知，可在系统设置里打开",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            try {
                web.onResume();
            } catch (Throwable ignored) {}
            evalJs("window.HWSentinelWeb && window.HWSentinelWeb.reload()");
        }
    }

    @Override
    protected void onPause() {
        if (web != null) {
            try {
                web.onPause();
            } catch (Throwable ignored) {}
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            try {
                web.destroy();
            } catch (Throwable ignored) {}
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web != null) {
            try {
                if (web.canGoBack()) {
                    web.goBack();
                    return;
                }
            } catch (Throwable ignored) {}
        }
        super.onBackPressed();
    }

    // =====================================================================
    // 出错时给用户看得懂的东西，而不是直接闪退
    // =====================================================================

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, final Throwable error) {
                final String detail = describe(error);

                // 关键一步：先把崩溃信息落到本地文件。
                // 这样即使进程随后被杀掉，下次打开也能把原因显示出来。
                boolean saved = false;
                try {
                    java.io.FileOutputStream fos =
                            openFileOutput(CRASH_FILE, MODE_PRIVATE);
                    fos.write(detail.getBytes("UTF-8"));
                    fos.close();
                    saved = true;
                    Log.e(TAG, "已记录崩溃信息到 " + CRASH_FILE);
                } catch (Throwable t) {
                    Log.e(TAG, "写入崩溃记录失败", t);
                }

                if (thread == android.os.Looper.getMainLooper().getThread()) {
                    // 主线程崩溃：自己把错误页画出来，并且不把异常交回系统，
                    // 否则进程会立刻被杀掉，用户根本来不及看到原因。
                    try {
                        showCrashScreen(error, detail);
                        return;
                    } catch (Throwable ignored) {
                        // 连错误页都建不起来，只能交给系统
                    }
                } else if (saved) {
                    // 子线程崩溃：进程还能活着，下次进前台时再展示
                    Log.e(TAG, "子线程未捕获异常，已记录", error);
                    return;
                }

                if (previous != null) previous.uncaughtException(thread, error);
            }
        });
    }

    /** 读取上次崩溃记录；没有就返回 null */
    private String readPendingCrash() {
        try {
            java.io.FileInputStream fis = openFileInput(CRASH_FILE);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            fis.close();
            String s = new String(bos.toByteArray(), "UTF-8");
            return s.trim().isEmpty() ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    private void clearPendingCrash() {
        try {
            deleteFile(CRASH_FILE);
        } catch (Throwable ignored) {}
    }

    /** 用户点「重新打开应用」：清掉崩溃记录，重新搭一次界面 */
    private void restartApp() {
        clearPendingCrash();
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void showCrashScreen(Throwable error) {
        showCrashScreen(error, describe(error));
    }

    private void showCrashScreen(Throwable error, String detail) {
        if (errorView == null) {
            errorView = buildErrorView();
            setContentView(errorView);
        }
        errorView.setVisibility(View.VISIBLE);

        TextView title = errorView.findViewWithTag("title");
        TextView desc = errorView.findViewWithTag("desc");
        TextView body = errorView.findViewWithTag("detail");
        if (title != null) title.setText("应用上次没能正常启动");
        if (desc != null) {
            desc.setText("这通常是因为手机系统缺少「Android System WebView」组件，"
                    + "或者系统限制了它。请按下面的办法试一次；"
                    + "如果还不行，点下面的按钮复制这段信息发给我。");
        }
        if (body != null) body.setText(detail);

        Log.e(TAG, "启动失败，已展示错误页", error);
    }

    private View buildErrorView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#F5F6FB"));
        scroll.setFillViewport(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(22);
        box.setPadding(pad, dp(48), pad, dp(32));
        scroll.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setTag("title");
        title.setText("应用没能正常启动");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#14162B"));
        box.addView(title);

        TextView desc = new TextView(this);
        desc.setTag("desc");
        desc.setTextSize(14);
        desc.setTextColor(Color.parseColor("#5A5F7D"));
        desc.setPadding(0, dp(10), 0, dp(18));
        desc.setLineSpacing(0, 1.35f);
        box.addView(desc);

        // 尝试的办法
        String[] tips = new String[]{
                "1. 到应用商店搜索并更新「Android System WebView」和「Chrome」",
                "2. 设置 → 应用管理 → 显示系统程序 → Android System WebView → 启用",
                "3. 设置 → 应用管理 → 作业哨兵 → 清除数据后重新打开",
                "4. 重启手机后再试一次"
        };
        for (String tip : tips) {
            TextView t = new TextView(this);
            t.setText(tip);
            t.setTextSize(13);
            t.setTextColor(Color.parseColor("#3730A3"));
            t.setPadding(0, dp(3), 0, dp(3));
            box.addView(t);
        }

        Button restart = new Button(this);
        restart.setText("重新打开应用");
        restart.setAllCaps(false);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(20);
        restart.setLayoutParams(bp);
        restart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartApp();
            }
        });
        box.addView(restart);

        Button copy = new Button(this);
        copy.setText("复制错误信息");
        copy.setAllCaps(false);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(8);
        copy.setLayoutParams(cp);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TextView body = errorView.findViewWithTag("detail");
                if (body == null) return;
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                            "作业哨兵错误信息", body.getText()));
                    Toast.makeText(MainActivity.this, "已复制，粘贴到聊天里发给我即可",
                            Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "复制失败，请直接截图", Toast.LENGTH_SHORT).show();
                }
            }
        });
        box.addView(copy);

        TextView detailTitle = new TextView(this);
        detailTitle.setText("技术细节");
        detailTitle.setTextSize(13);
        detailTitle.setTypeface(Typeface.DEFAULT_BOLD);
        detailTitle.setTextColor(Color.parseColor("#14162B"));
        detailTitle.setPadding(0, dp(26), 0, dp(8));
        box.addView(detailTitle);

        TextView body = new TextView(this);
        body.setTag("detail");
        body.setTextSize(11);
        body.setTextColor(Color.parseColor("#5A5F7D"));
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setBackgroundColor(Color.parseColor("#FFFFFF"));
        body.setPadding(dp(12), dp(12), dp(12), dp(12));
        box.addView(body);

        return scroll;
    }

    /** 把异常和设备信息整理成一段可以截图的文本 */
    private String describe(Throwable error) {
        StringBuilder sb = new StringBuilder();
        sb.append("型号: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("系统: Android ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("应用版本: ").append(APP_VERSION_NAME()).append('\n');
        sb.append("WebView: ");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                sb.append(WebView.getCurrentWebViewPackage() == null
                        ? "未检测到（系统缺少 WebView 组件）"
                        : WebView.getCurrentWebViewPackage().versionName);
            } else {
                sb.append("无法检测");
            }
        } catch (Throwable t) {
            sb.append("检测失败: ").append(t);
        }
        sb.append("\n\n—— 异常 ——\n");
        if (error != null) {
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            sb.append(sw.toString());
        }
        return sb.toString();
    }

    private String APP_VERSION_NAME() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
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
                    try {
                        ReminderScheduler.rescheduleAll(getApplicationContext());
                    } catch (Throwable t) {
                        Log.w(TAG, "登记闹钟失败: " + t);
                    }
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
