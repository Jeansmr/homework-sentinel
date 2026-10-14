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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 应用主界面：承载网页界面的 WebView，外加一座连接手机系统能力的桥。
 *
 * ================== 这个文件的一条铁律 ==================
 * 用户绝对不应该看到一个「白屏、没反应」的界面。
 * 只要界面没起来，就必须显示一段人话，说明卡在哪一步、下一步怎么办。
 * ======================================================
 *
 * 历史上踩过的坑，以及现在怎么防：
 *
 *  1) v1.0.0「一点开就闪退」
 *     原因：onCreate 里 new WebView() 没有任何异常保护，WebView 被 ROM 精简时直接崩。
 *     现在：启动前自检 + 全程 try/catch + 崩溃落盘，下次打开时把原因显示出来。
 *
 *  2) v1.0.1「点开是白屏，没有任何提示」
 *     原因：只处理了「抛异常」这一种失败。而网页加载失败是不抛异常的——
 *           file:///android_asset/www/index.html 一旦读不到、或渲染进程挂掉，
 *           WebView 就安安静静地显示一片空白，代码里没有任何地方会知道。
 *     现在（v1.0.2）补齐了所有「不抛异常也会白屏」的路径：
 *           - 资源包完整性自检：index.html 读不出来就直接报错，不去加载
 *           - onReceivedError / onReceivedHttpError：加载失败给出明确原因
 *           - onRenderProcessGone：渲染进程被杀时重建 WebView，而不是留一片空白
 *           - 加载看门狗：页面没起来时先显示「正在启动…」+ 已等待秒数，绝不白屏
 *           - WebView 内核版本号直接写在界面上，一眼看出是不是内核的问题
 *           - 网页层 JS 出错也会通过 onJsError 上报到这里显示
 *
 *  3) 崩溃信息反复出现
 *     原因：崩溃落盘后只有「重新打开应用」按钮会清空，别的方式进来会一直看到错误页。
 *     现在：每次读取时顺手删掉，一个崩溃只展示一次，不会把应用永久卡死。
 *
 *  4) v1.0.2「每次打开都先弹 E140 WebView 配置失败，点一下重新加载才进得去」
 *     原因：AndroidManifest 里没有 INTERNET 权限，而 WebView 的
 *           getSettings().setBlockNetworkLoads(false) 在没有该权限时必抛
 *           SecurityException: Permission denied - application missing INTERNET permission。
 *           它排在配置流程的后半段，一抛异常就把整段配置打断：
 *           页面照样加载，但 JavaScript 开关没打开、WebViewClient 也没挂上，
 *           于是页面永远停在占位层，只能靠用户手动点「重新加载界面」重来一次。
 *     现在（v1.0.3）三层修：
 *           - 清单补上 INTERNET 权限（WebView 的硬性要求，哪怕界面不联网）；
 *           - 配置流程重排：先做「不做就没法用」的（JavaScript、WebViewClient），
 *             再做可选项，且每个可选项单独 try/catch，坏一个不牵连其它；
 *           - E140 降级为「可恢复」：不再当场把错误页推给用户，
 *             而是继续加载，页面起来了就自动消失，起不来才显示原因。
 *     详见 docs/WebView配置失败排查记录.md
 *
 * 调试用：已开启 WebView 远程调试，手机连电脑后可在 chrome://inspect 里直接看这个页面。
 *
 * 暴露给网页层的桥（window.HWSentinel）：
 *   syncReminders(json)  —— 把提醒计划交给系统闹钟，App 关掉也能提醒
 *   notify(title, body)  —— 立刻发一条通知
 *   hasPermission()      —— 是否已获得通知权限
 *   requestPermission()  —— 申请通知权限
 *   sourceName()/sourceDesc() —— 数据来源说明
 *   diag(json)           —— 网页层把运行环境自检结果交给原生层（白屏排查用）
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    public static final String EXTRA_FOCUS_KEY = "focus_key";

    private static final String ASSET_DIR = "www";
    private static final String ENTRY_HTML = ASSET_DIR + "/index.html";
    private static final String ENTRY_URL = "file:///android_asset/" + ENTRY_HTML;

    private static final int REQ_NOTIFICATION = 1001;
    /** 上一次崩溃的记录文件，下次启动时读出来给用户看 */
    private static final String CRASH_FILE = "last-crash.txt";

    /** 网页层初始化完毕后会置为 true；只有它为 true 才算「真的起来了」 */
    private static final String JS_READY_FLAG = "window.__HWS_READY__ === true";

    /**
     * 卡住时的自检探针：在 WebView 内部问一圈「你现在到底处于什么状态」。
     * 这些信息会直接显示在页面上，用户一截图就能定位问题。
     */
    private static final String PROBE_JS =
            "(function(){var o={};try{"
            + "o.readyState=document.readyState;"
            + "o.hasAppJs=!!window.HWSentinelWeb;"
            + "o.bootNode=!!document.getElementById('boot');"
            + "o.screenAuth=!!document.getElementById('screen-auth');"
            + "o.screenApp=!!document.getElementById('screen-app');"
            + "try{o.localStorage=(function(){localStorage.setItem('__p__','1');localStorage.removeItem('__p__');return 'ok'})()}catch(e){o.localStorage='fail:'+e}"
            + "}catch(e){o.fatal=String(e)}return JSON.stringify(o)})()";

    private static final long WATCHDOG_MS = 15000;

    /**
     * 给「配置降级」的宽限时间：这期间页面要是自己起来了，就当什么都没发生过。
     * 比看门狗短，因为这条路上界面本来就已经能加载了，只是有个设置项没写成功。
     */
    private static final long SOFT_PROBLEM_GRACE_MS = 8000;

    private WebView web;
    private View rootView;
    private View errorView;
    private LinearLayout loadingView;
    private TextView loadingText;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private String pendingFocusKey;
    /** 页面是否已经真正可用（JS 初始化完成） */
    private boolean pageReady;
    /** 是否已经为本次进程申请过通知权限，避免反复弹窗 */
    private boolean askedNotification;
    /** 加载看门狗的代号：每次重新加载 +1，旧的回调自动失效 */
    private int loadToken;
    private long loadStartedAt;
    private Runnable watchdog;
    /** 「启动不顺但还救得回来」时记下的问题：等页面起来再决定要不要打扰用户 */
    private String softProblemTitle;
    private String softProblemDetail;

    // =====================================================================
    // 启动
    // =====================================================================

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        installCrashHandler();

        // 1) 先把要展示的东西建出来。屏幕从第一帧起就必须有内容——
        //    即使后面每一步都失败，用户看到的也是「正在启动」或一段错误说明，而不是白屏。
        try {
            buildRoot();
        } catch (Throwable t) {
            Log.e(TAG, "界面初始化失败", t);
            showProblem("E100 界面初始化失败", describe(t));
            return;
        }
        showLoading("正在启动…");

        // 2) 上一次崩溃过？展示原因，但继续启动，不再把应用卡在错误页上。
        String lastCrash = readPendingCrash();
        if (lastCrash != null) {
            showProblem("上次没能正常启动",
                    lastCrash + "\n\n（应用已继续启动；如果界面正常，可以直接点「知道了」继续使用。）");
        }

        // 3) 系统 WebView 内核自检
        String preflight = preflightCheck();
        if (preflight != null) {
            Log.e(TAG, "自检未通过: " + preflight);
            showProblem("E110 系统缺少 WebView 组件", buildPreflightDetail(preflight));
            return;
        }

        // 4) 网页资源包自检：读不出来就别加载了，直接给出原因。
        //    这是「白屏」最常见的原因之一，而它本来不会抛任何异常。
        String assetProblem = checkWebAssets();
        if (assetProblem != null) {
            Log.e(TAG, "网页资源自检未通过: " + assetProblem);
            showProblem("E120 应用内网页资源异常", buildAssetDetail(assetProblem));
            return;
        }

        try {
            NotificationHelper.ensureChannels(this);
        } catch (Throwable t) {
            Log.w(TAG, "创建通知渠道失败（不致命）: " + t);
        }

        // 5) 建 WebView 并加载页面
        WebView created;
        try {
            created = new WebView(this);
        } catch (Throwable t) {
            Log.e(TAG, "创建 WebView 失败", t);
            showProblem("E130 无法创建 WebView", describe(t));
            return;
        }

        created.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        created.setBackgroundColor(Color.parseColor("#F5F6FB"));
        ((FrameLayout) rootView).addView(created);
        web = created;

        try {
            configureWebView();
        } catch (Throwable t) {
            // v1.0.3：走到这里不再直接判死刑。
            //
            // v1.0.2 在这里 showProblem 然后 return，等于放弃了这个 WebView：
            // 设置项只写了一半（JavaScript 开关可能还没打开），页面永远停在占位层，
            // 用户必须手动点「重新加载界面」才进得去。
            // 现在先记下问题、照常加载页面：页面要是自己起来了，用户什么都不用做；
            // 真的起不来，8 秒后错误页会带着这里的原始原因出现。
            Log.e(TAG, "WebView 配置未全部完成，继续尝试启动", t);
            setSoftProblem("E140 WebView 配置未全部完成", describe(t));
        }

        loadEntryPage();

        // 6) 重新登记提醒（防止系统清理掉已登记的闹钟）
        try {
            ReminderScheduler.rescheduleAll(getApplicationContext());
        } catch (Throwable t) {
            Log.w(TAG, "重新登记提醒失败（不致命）: " + t);
        }

        handleIntent(getIntent());
    }

    private void loadEntryPage() {
        if (web == null) return;
        loadStartedAt = System.currentTimeMillis();
        final int token = ++loadToken;
        pageReady = false;
        showLoading("正在启动…");

        try {
            web.loadUrl(ENTRY_URL);
        } catch (Throwable t) {
            Log.e(TAG, "加载页面失败", t);
            showProblem("E150 加载页面失败", describe(t));
            return;
        }

        // 看门狗：页面迟迟没起来就先给用户一句人话，别让他对着白屏猜。
        cancelWatchdog();
        watchdog = new Runnable() {
            @Override
            public void run() {
                if (token != loadToken || pageReady) return;
                if (web == null) return;
                final long waited = (System.currentTimeMillis() - loadStartedAt) / 1000;
                // 再问一次网页层到底起来没有，然后 tick 一下继续等
                try {
                    web.evaluateJavascript(JS_READY_FLAG, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            if ("true".equals(value)) {
                                onPageUsable();
                            } else if (token == loadToken) {
                                tickWatchdog(token, waited);
                            }
                        }
                    });
                } catch (Throwable t) {
                    tickWatchdog(token, waited);
                }
            }
        };
        ui.postDelayed(watchdog, WATCHDOG_MS);

        // 如果启动时发生过「配置降级」，等一小会儿再裁决：
        // 页面起来了就作废，起不来就把原因摆出来（而不是让用户对着占位层干等 15 秒）。
        scheduleSoftProblemCheck();
    }

    /** 看门狗没等到页面就绪：把「还在等」写到页面上，并继续等 */
    private void tickWatchdog(int token, long waited) {
        if (token != loadToken || pageReady) return;

        // 先做一次体检：拿到能说清楚问题的信息，再决定跟用户说什么
        final String[] facts = {null};
        try {
            web.evaluateJavascript(PROBE_JS, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    facts[0] = value;
                    afterProbe(token, waited, value);
                }
            });
        } catch (Throwable t) {
            afterProbe(token, waited, null);
        }
    }

    private void afterProbe(int token, long waited, String probeResult) {
        if (token != loadToken || pageReady) return;

        showLoading("正在启动…（已等待 " + waited + " 秒）");
        applyStuckNotice(waited, probeResult);

        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (watchdog != null) watchdog.run();
            }
        }, 5000);
    }

    /**
     * 页面起不来时，直接在页面上写清楚：卡在哪一步、可以怎么办。
     *
     * 为什么不改成显示原生错误页？
     *   因为这种情况下 WebView 本身往往是好的，问题多半在资源或脚本上。
     *   把说明画在页面里，用户能直接看到当前界面长什么样，
     *   我们也能同时拿到 WebView 内部的自检结果。
     */
    private void applyStuckNotice(long waited, String probeResult) {
        String summary = describeProbe(probeResult);
        String js = "(function(){try{"
                + "var b=document.getElementById('boot');"
                + "if(!b)return 'no-boot';"
                + "b.style.transform='translateY(' + Math.round(window.innerHeight*0.18) + 'px)';"
                + "var tip=b.querySelector('.boot__tip');"
                + "if(tip){tip.style.maxWidth='320px';tip.style.textAlign='left';tip.style.fontSize='12.5px';"
                + "tip.textContent=" + jsString(
                        "已等待 " + waited + " 秒，界面还没能显示出来。\n\n"
                        + summary + "\n\n"
                        + "可以试着：① 重新打开应用；② 到应用商店更新「Android System WebView」；"
                        + "③ 重启手机。若仍不行，请把这一屏截图发给开发者。") + ";}"
                + "return 'ok';}catch(e){return 'err:'+e}})()";
        try {
            web.evaluateJavascript(js, null);
        } catch (Throwable ignored) {}
    }

    /** 把 JS 探针返回的 JSON 变成一句人话 */
    private String describeProbe(String probeResult) {
        if (probeResult == null || probeResult.isEmpty() || "null".equals(probeResult)) {
            return "网页脚本没有运行起来（可能是安装包里的 app.js 读不到，或 WebView 内核异常）。";
        }
        // evaluateJavascript 返回的是 JS 字符串字面量，外面带一层引号，去掉更好读
        String s = probeResult;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        s = s.replace("\\\"", "\"").replace("\\\\", "\\");
        if (s.length() > 400) s = s.substring(0, 400) + "…";
        return "网页层自检：" + s;
    }

    private void cancelWatchdog() {
        if (watchdog != null) {
            ui.removeCallbacks(watchdog);
            watchdog = null;
        }
    }

    /** 网页层确认可用：收起加载提示，补上因页面没起来而延后的通知权限申请 */
    private void onPageUsable() {
        pageReady = true;
        cancelWatchdog();
        hideLoading();
        // 界面既然起来了，之前那个「启动不顺」的提示就该退场，
        // 不能让用户为了用应用先去点一下按钮。
        hideProblem();
        clearSoftProblem();
        maybeAskNotificationPermission();
        if (pendingFocusKey != null) {
            String key = pendingFocusKey;
            pendingFocusKey = null;
            evalJs("window.HWSentinelFocus && window.HWSentinelFocus(" + jsString(key) + ")");
        }
    }

    // =====================================================================
    // 启动前的自检
    // =====================================================================

    /**
     * 检查系统的 WebView 组件是否可用。
     *
     * 国产 ROM 上 WebView 有可能被精简、禁用或替换成不完整的内核，
     * 这时 new WebView() 会直接抛异常。提前查一次，把原因说清楚。
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

    /**
     * 网页资源包自检。
     *
     * 这一步专门对付「白屏」：assets/www/index.html 读不出来时，
     * WebView 不会抛异常、也不会报错，只会显示一片空白。
     * 与其让用户对着白屏，不如在这里就把它拦下来。
     *
     * @return null 表示正常，否则返回问题描述
     */
    private String checkWebAssets() {
        InputStream in = null;
        try {
            in = getAssets().open(ENTRY_HTML);
            byte[] buf = new byte[4096];
            int total = 0, n;
            StringBuilder head = new StringBuilder();
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (head.length() < 512) {
                    head.append(new String(buf, 0, Math.min(n, 512 - head.length()), "UTF-8"));
                }
            }
            if (total <= 0) return ENTRY_HTML + " 是空文件";

            String begin = head.toString();
            if (begin.indexOf("<html") < 0 && begin.indexOf("<!DOCTYPE") < 0) {
                return ENTRY_HTML + " 内容不完整（开头不是网页），可能安装包损坏";
            }
            return null;
        } catch (Throwable t) {
            return "读不到 " + ENTRY_HTML + "：" + t;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    private String buildPreflightDetail(String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("系统: Android ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("型号: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("应用版本: ").append(appVersionName()).append("\n\n");
        sb.append("—— 自检结果 ——\n").append(reason).append('\n');
        sb.append("\n这个 App 的界面是用系统 WebView 渲染的，缺少它就无法启动。\n");
        sb.append("请按上面的步骤 1、2 恢复 WebView 后重新打开。");
        return sb.toString();
    }

    private String buildAssetDetail(String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("系统: Android ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("型号: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("应用版本: ").append(appVersionName()).append("\n\n");
        sb.append("—— 自检结果 ——\n").append(reason).append('\n');
        sb.append("\n界面文件就在安装包里，读不到通常说明安装包没装完整。\n");
        sb.append("请卸载后重新安装这个 APK；如果还不行，把这段信息发给我。");
        return sb.toString();
    }

    // =====================================================================
    // 界面搭建
    // =====================================================================

    private void buildRoot() {
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(Color.parseColor("#F5F6FB"));
        rootView = root;

        errorView = buildErrorView();
        errorView.setVisibility(View.GONE);
        root.addView(errorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        loadingView = buildLoadingView();
        loadingView.setVisibility(View.GONE);
        root.addView(loadingView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    /** 「正在启动…」占位：保证任何时刻屏幕上都有东西 */
    private LinearLayout buildLoadingView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.parseColor("#F5F6FB"));

        android.widget.ProgressBar bar = new android.widget.ProgressBar(this);
        box.addView(bar);

        loadingText = new TextView(this);
        loadingText.setText("正在启动…");
        loadingText.setTextSize(14);
        loadingText.setTextColor(Color.parseColor("#5A5F7D"));
        loadingText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        loadingText.setLayoutParams(lp);
        box.addView(loadingText);

        return box;
    }

    private void showLoading(String message) {
        if (loadingView == null) return;
        if (loadingText != null) loadingText.setText(message);
        loadingView.setVisibility(View.VISIBLE);
    }

    private void hideLoading() {
        if (loadingView == null) return;
        loadingView.setVisibility(View.GONE);
    }

    // =====================================================================
    // WebView 配置
    // =====================================================================

    /**
     * 配置 WebView。
     *
     * ============ 这里的顺序和容错都不能随便改 ============
     * v1.0.2 的坑：整个配置过程被包在一个 try 里，任何一个设置项抛异常，
     * 后面的代码全都不会执行 —— 而抛异常的偏偏是排在很后面的
     * setBlockNetworkLoads(false)（没有 INTERNET 权限时必抛 SecurityException），
     * 于是排在前面的 JavaScript 开关也许打开了、但页面加载完却没人接管，
     * 表现就是「界面没起来 + 出错提示 + 点重新加载才能进」。
     *
     * 现在的两条原则：
     *   1) 先做「不做就没法用」的事（JavaScript、WebViewClient），再做锦上添花的设置；
     *   2) 每个可选项单独 try/catch，坏一个不影响其它，也不会中断启动。
     * =====================================================
     */
    private void configureWebView() {
        // 第一件事：打开 JavaScript。这条失败才是真的没救。
        web.getSettings().setJavaScriptEnabled(true);

        // 第二件事：先把 WebViewClient 挂上。
        // 它决定「加载失败会不会有人说话」，早挂早保险；
        // 放在设置之后的话，一个设置项出错就等于连它都没挂上。
        installWebViewClient();

        applyWebSettings();
        disableSystemDarkening();

        // 远程调试：手机连上电脑后 chrome://inspect 能直接看到这个页面。
        // 白屏这类问题，看一眼控制台往往就水落石出了。
        try {
            WebView.setWebContentsDebuggingEnabled(true);
        } catch (Throwable ignored) {}

        web.addJavascriptInterface(new NativeBridge(), "HWSentinel");
    }

    /**
     * 逐项应用 WebView 设置。
     *
     * 每个设置项都被单独包起来：某个 ROM 上不支持 / 被策略拒绝时，
     * 只丢这一个设置，不会把后面的设置和整个启动流程一起带走。
     */
    private void applyWebSettings() {
        WebSettings s = web.getSettings();

        // ---- 界面能不能用，靠这几项 ----
        eachSetting("domStorage", new Setting() {
            @Override public void apply(WebSettings s) { s.setDomStorageEnabled(true); }
        });
        eachSetting("database", new Setting() {
            @Override public void apply(WebSettings s) { s.setDatabaseEnabled(true); }
        });
        eachSetting("fileAccess", new Setting() {
            @Override public void apply(WebSettings s) { s.setAllowFileAccess(true); }
        });
        eachSetting("contentAccess", new Setting() {
            @Override public void apply(WebSettings s) { s.setAllowContentAccess(true); }
        });
        eachSetting("images", new Setting() {
            @Override public void apply(WebSettings s) { s.setLoadsImagesAutomatically(true); }
        });

        // ---- 外观：和网页层的 CSS 对齐，不要系统插手 ----
        eachSetting("zoom", new Setting() {
            @Override public void apply(WebSettings s) {
                s.setSupportZoom(false);
                s.setBuiltInZoomControls(false);
                s.setDisplayZoomControls(false);
            }
        });
        eachSetting("viewport", new Setting() {
            @Override public void apply(WebSettings s) {
                s.setUseWideViewPort(false);
                s.setLoadWithOverviewMode(false);
            }
        });
        eachSetting("media", new Setting() {
            @Override public void apply(WebSettings s) { s.setMediaPlaybackRequiresUserGesture(false); }
        });
        eachSetting("mixedContent", new Setting() {
            @Override public void apply(WebSettings s) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
                }
            }
        });
        eachSetting("cache", new Setting() {
            @Override public void apply(WebSettings s) { s.setCacheMode(WebSettings.LOAD_DEFAULT); }
        });

        // ---- 网络：这一步是 v1.0.2 崩溃的现场 ----
        // 界面完全来自安装包（file:///android_asset/），所以这里主动禁止联网加载，
        // 顺便证明「这个应用不往外发任何数据」。
        // setBlockNetworkLoads(false) 要求清单里有 INTERNET 权限（v1.0.3 已补上），
        // 万一某个 ROM 还是拒绝，就退一步允许网络加载 —— 界面照常能用，
        // 绝不能因为这一个设置项让用户看到错误页。
        eachSetting("blockNetworkLoads", new Setting() {
            @Override public void apply(WebSettings s) { s.setBlockNetworkLoads(false); }
        }, new Setting() {
            @Override public void apply(WebSettings s) { s.setBlockNetworkLoads(true); }
        });
    }

    /** 一个「只碰 WebSettings」的开关，方便逐项容错 */
    private interface Setting {
        void apply(WebSettings s);
    }

    /** 应用一项设置；失败了也不中断，最多试一下备选写法 */
    private void eachSetting(String name, Setting setting) {
        eachSetting(name, setting, null);
    }

    private void eachSetting(String name, Setting setting, Setting fallback) {
        try {
            setting.apply(web.getSettings());
        } catch (Throwable t) {
            Log.w(TAG, "WebView 设置项 " + name + " 未能应用（不致命）: " + t);
            if (fallback != null) {
                try {
                    fallback.apply(web.getSettings());
                } catch (Throwable t2) {
                    Log.w(TAG, "WebView 设置项 " + name + " 的备选写法也失败（不致命）: " + t2);
                }
            }
        }
    }

    /** 让加载失败、渲染进程被杀这些情况都有人说话 */
    private void installWebViewClient() {
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);

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
                // onPageFinished 只代表文档加载完了，脚本可能还没跑完；
                // 真正「能不能用」由网页层的就绪标记决定。
                try {
                    view.evaluateJavascript(JS_READY_FLAG, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            if ("true".equals(value)) onPageUsable();
                        }
                    });
                } catch (Throwable ignored) {}
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request == null || !request.isForMainFrame()) return;
                String reason = "";
                try {
                    reason = String.valueOf(error.getDescription()) + "（错误码 " + error.getErrorCode() + "）";
                } catch (Throwable ignored) {}
                failPage("E160 页面加载失败", reason);
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                // 旧接口：只有在没有调用新接口时才需要处理，这里去重一下
                if (failingUrl != null && failingUrl.startsWith("file:///android_asset/")) {
                    failPage("E160 页面加载失败", description + "（错误码 " + errorCode + "）");
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request == null || !request.isForMainFrame()) return;
                failPage("E161 页面返回错误状态", "HTTP " + (response == null ? "?" : response.getStatusCode()));
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // 渲染进程被系统杀掉（内存紧张、内核崩溃）——默认行为是整页变空白。
                boolean crashed = false;
                try { crashed = detail != null && detail.didCrash(); } catch (Throwable ignored) {}
                Log.e(TAG, "WebView 渲染进程结束，didCrash=" + crashed);

                if (web != null) {
                    try { ((FrameLayout) rootView).removeView(web); } catch (Throwable ignored) {}
                    try { web.destroy(); } catch (Throwable ignored) {}
                    web = null;
                }
                cancelWatchdog();
                pageReady = false;

                showProblem("E170 界面渲染进程被系统回收",
                        "系统回收了负责绘制界面的进程（常见于内存紧张）。\n"
                        + "didCrash=" + crashed + "\n\n"
                        + deviceReport() + "\n\n点下面的按钮可以重新加载界面。");

                // 返回 true 表示「我们自己处理了」，避免整个应用被系统结束
                return true;
            }
        });
    }

    /** 关掉系统的强制暗色，避免表单控件被反色成看不清的样子 */
    private void disableSystemDarkening() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+：旧接口已废弃，改用这个（部分 ROM 可能没有，反射调用）
                java.lang.reflect.Method m = WebSettings.class
                        .getMethod("setAlgorithmicDarkeningAllowed", boolean.class);
                m.invoke(web.getSettings(), false);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                web.getSettings().setForceDark(WebSettings.FORCE_DARK_OFF);
            }
        } catch (Throwable ignored) {
            // 个别 ROM 上这些方法不可用，忽略即可
        }
    }

    /**
     * 启动路上有个别环节没走通，但界面还有救。
     *
     * 和 showProblem 的区别：
     *   showProblem —— 「已经没救了」，立刻把错误页推到用户面前；
     *   softFail    —— 「这一步不顺，但后面还能站起来」，先只记下来。
     *
     * 记下来的问题会等页面加载完再裁决（见 scheduleSoftProblemCheck）：
     * 页面起来了就作废，起不来才显示。这样「能自己好」的情况
     * 就不会再变成一张要用户手动点掉的错误页。
     */
    private void setSoftProblem(String title, String detail) {
        softProblemTitle = title;
        softProblemDetail = detail;
        Log.w(TAG, "启动降级（可恢复）：" + title);
    }

    /** 页面加载完的裁决：起来了就把降级记录作废，没起来就把原因显示出来 */
    private void scheduleSoftProblemCheck() {
        if (softProblemTitle == null) return;
        final int token = loadToken;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (softProblemTitle == null || token != loadToken) return;
                if (pageReady) {
                    clearSoftProblem();
                    return;
                }
                flushSoftProblem();
            }
        }, SOFT_PROBLEM_GRACE_MS);
    }

    /** 把降级记下的问题显示出来，别让用户对着占位层猜 */
    private void flushSoftProblem() {
        if (softProblemTitle == null) return;
        final String title = softProblemTitle;
        final String detail = softProblemDetail;
        clearSoftProblem();
        showProblem(title, detail + "\n\n点下面的按钮可以重新加载界面。");
    }

    private void clearSoftProblem() {
        softProblemTitle = null;
        softProblemDetail = null;
    }

    /** 页面加载失败时的统一处理：把原因摆到用户面前 */
    private void failPage(final String title, final String reason) {
        if (pageReady) return;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (pageReady) return;
                cancelWatchdog();
                showProblem(title, reason + "\n\n" + deviceReport()
                        + "\n\n点下面的按钮可以重新加载界面。");
            }
        });
    }

    /** 把「加载失败」还原成「重试加载」 */
    private void retryLoad() {
        hideProblem();
        // 用户主动重试，之前记下的「启动不顺」不再重复提示
        clearSoftProblem();
        if (web == null) {
            // WebView 已经被销毁（渲染进程回收等），重建一个
            try {
                WebView created = new WebView(this);
                created.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                created.setBackgroundColor(Color.parseColor("#F5F6FB"));
                ((FrameLayout) rootView).addView(created);
                web = created;
                configureWebView();
            } catch (Throwable t) {
                showProblem("E130 无法创建 WebView", describe(t));
                return;
            }
        }
        if (web != null) {
            try { web.clearCache(false); } catch (Throwable ignored) {}
        }
        loadEntryPage();
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
        if (key == null || key.isEmpty()) return;
        if (pageReady) {
            evalJs("window.HWSentinelFocus && window.HWSentinelFocus(" + jsString(key) + ")");
        } else {
            pendingFocusKey = key;
        }
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
    // 通知权限
    // =====================================================================

    private void maybeAskNotificationPermission() {
        if (askedNotification) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        try {
            if (NotificationHelper.canPost(this)) return;
            askedNotification = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        } catch (Throwable t) {
            Log.w(TAG, "申请通知权限失败（不致命）: " + t);
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
        cancelWatchdog();
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
    // 出错时给用户看得懂的东西，而不是白屏
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
                    java.io.FileOutputStream fos = openFileOutput(CRASH_FILE, MODE_PRIVATE);
                    fos.write(detail.getBytes("UTF-8"));
                    fos.close();
                    saved = true;
                    Log.e(TAG, "已记录崩溃信息到 " + CRASH_FILE);
                } catch (Throwable t) {
                    Log.e(TAG, "写入崩溃记录失败", t);
                }

                if (thread == Looper.getMainLooper().getThread()) {
                    // 主线程崩溃：自己把错误页画出来，并且不把异常交回系统，
                    // 否则进程会立刻被杀掉，用户根本来不及看到原因。
                    try {
                        showProblem("E000 应用发生异常", detail);
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

    /** 读取上次崩溃记录；顺手删掉，保证同一个崩溃只展示一次，不会把应用永久卡住 */
    private String readPendingCrash() {
        try {
            java.io.FileInputStream fis = openFileInput(CRASH_FILE);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            fis.close();
            clearPendingCrash();   // 读过就删，避免每次打开都被这段信息挡住
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

    private void showProblem(String title, String detail) {
        if (errorView == null) {
            try {
                buildRoot();
            } catch (Throwable ignored) {}
        }
        if (errorView == null) {
            // 连错误页都建不出来，至少别让屏幕空着
            try {
                TextView tv = new TextView(this);
                tv.setText(title + "\n\n" + detail);
                tv.setTextColor(Color.parseColor("#14162B"));
                tv.setBackgroundColor(Color.parseColor("#F5F6FB"));
                tv.setPadding(dp(20), dp(40), dp(20), dp(20));
                setContentView(tv);
            } catch (Throwable ignored) {}
            return;
        }

        errorView.setVisibility(View.VISIBLE);
        // 让底下的 WebView 不再响应点击，避免用户点到看不见的界面
        if (web != null) {
            try { web.setVisibility(View.GONE); } catch (Throwable ignored) {}
        }
        hideLoading();

        TextView t = errorView.findViewWithTag("title");
        TextView d = errorView.findViewWithTag("desc");
        TextView b = errorView.findViewWithTag("detail");
        if (t != null) t.setText(title);
        if (d != null) d.setText("这一步没走通，界面没能显示出来。下面是可以照做的办法；"
                + "如果还不行，点「复制错误信息」把这整段发给我。");
        if (b != null) b.setText(detail);

        Log.e(TAG, "启动/加载失败：" + title + "\n" + detail);
    }

    private void hideProblem() {
        if (errorView != null) errorView.setVisibility(View.GONE);
        if (web != null) {
            try { web.setVisibility(View.VISIBLE); } catch (Throwable ignored) {}
        }
    }

    private View buildErrorView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#F5F6FB"));
        scroll.setFillViewport(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(22);
        box.setPadding(pad, dp(44), pad, dp(32));
        scroll.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setTag("title");
        title.setText("界面没能显示出来");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#14162B"));
        box.addView(title);

        TextView desc = new TextView(this);
        desc.setTag("desc");
        desc.setTextSize(14);
        desc.setTextColor(Color.parseColor("#5A5F7D"));
        desc.setPadding(0, dp(10), 0, dp(16));
        desc.setLineSpacing(0, 1.35f);
        box.addView(desc);

        // 尝试的办法
        String[] tips = new String[]{
                "1. 点下面的「重新加载界面」，多数情况一下就恢复",
                "2. 到应用商店搜索并更新「Android System WebView」和「Chrome」",
                "3. 设置 → 应用管理 → 显示系统程序 → Android System WebView → 启用",
                "4. 设置 → 应用管理 → 作业哨兵 → 清除数据后重新打开",
                "5. 重启手机后再试一次"
        };
        for (String tip : tips) {
            TextView t = new TextView(this);
            t.setText(tip);
            t.setTextSize(13);
            t.setTextColor(Color.parseColor("#3730A3"));
            t.setPadding(0, dp(3), 0, dp(3));
            box.addView(t);
        }

        Button retry = new Button(this);
        retry.setText("重新加载界面");
        retry.setAllCaps(false);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(20);
        retry.setLayoutParams(bp);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                retryLoad();
            }
        });
        box.addView(retry);

        Button restart = new Button(this);
        restart.setText("重新打开应用");
        restart.setAllCaps(false);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(8);
        restart.setLayoutParams(rp);
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
                copyReportToClipboard();
            }
        });
        box.addView(copy);

        TextView detailTitle = new TextView(this);
        detailTitle.setText("技术细节");
        detailTitle.setTextSize(13);
        detailTitle.setTypeface(Typeface.DEFAULT_BOLD);
        detailTitle.setTextColor(Color.parseColor("#14162B"));
        detailTitle.setPadding(0, dp(24), 0, dp(8));
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

    /** 把错误页上的内容（含网页层自检结果）复制到剪贴板 */
    private void copyReportToClipboard() {
        StringBuilder sb = new StringBuilder();
        TextView t = errorView == null ? null : errorView.findViewWithTag("title");
        TextView d = errorView == null ? null : errorView.findViewWithTag("detail");
        if (t != null) sb.append(t.getText()).append('\n');
        if (d != null) sb.append(d.getText()).append('\n');

        final String text = sb.toString() + "\n" + deviceReport();
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("作业哨兵错误信息", text));
            Toast.makeText(this, "已复制，粘贴到聊天里发给我即可", Toast.LENGTH_LONG).show();
        } catch (Throwable ex) {
            Toast.makeText(this, "复制失败，请直接截图", Toast.LENGTH_SHORT).show();
        }
    }

    /** 当前设备 + 内核环境的一段可截图文本 */
    private String deviceReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("—— 运行环境 ——\n");
        sb.append("型号: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("系统: Android ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("应用版本: ").append(appVersionName()).append('\n');
        sb.append("WebView 内核: ").append(webViewVersion()).append('\n');
        sb.append("界面文件: ").append(ENTRY_HTML).append('\n');
        sb.append("页面状态: ").append(pageReady ? "脚本已就绪" : "脚本未就绪")
          .append("，加载方式 ").append(ENTRY_URL).append('\n');
        sb.append("时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date())).append('\n');
        return sb.toString();
    }

    private String webViewVersion() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.content.pm.PackageInfo pi = WebView.getCurrentWebViewPackage();
                if (pi == null) return "未检测到（系统缺少 WebView 组件）";
                return pi.packageName + " " + pi.versionName;
            }
        } catch (Throwable t) {
            return "检测失败: " + t;
        }
        return "无法检测（系统版本过低）";
    }

    /** 把异常和设备信息整理成一段可以截图的文本 */
    private String describe(Throwable error) {
        StringBuilder sb = new StringBuilder();
        sb.append(deviceReport());
        sb.append("\n—— 异常 ——\n");
        if (error != null) {
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            sb.append(sw.toString());
        }
        return sb.toString();
    }

    private String appVersionName() {
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
                askedNotification = true;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            requestPermissions(
                                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
                        } catch (Throwable ignored) {}
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

        /**
         * 网页层进入可用状态时告诉我们一声。
         *
         * 为什么需要这个？只看 onPageFinished 是不够的：它只代表 HTML 下载完了，
         * 脚本有没有跑起来、界面有没有真的画出来，它是不知道的。
         * 白屏恰恰就藏在这个缝里。
         */
        @JavascriptInterface
        public void ready(final String detail) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "网页层已就绪: " + detail);
                    onPageUsable();
                }
            });
        }

        /** 网页层把自检结果（含 JS 报错）交给原生层，出错时直接展示出来 */
        @JavascriptInterface
        public void diag(final String json) {
            Log.i(TAG, "网页自检: " + json);
        }

        /** 网页层自己发现严重问题（例如界面渲染不出来）时调用 */
        @JavascriptInterface
        public void reportProblem(final String title, final String detail) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showProblem(title == null ? "E200 网页层报告异常" : title,
                            (detail == null ? "" : detail) + "\n\n" + deviceReport());
                }
            });
        }
    }
}
