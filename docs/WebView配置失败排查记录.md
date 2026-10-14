# WebView 配置失败（E140）排查记录

记录第三次真实问题的完整排查过程：**v1.0.2 装到手机上，每次打开都先弹一张
「E140 WebView 配置失败」的错误页，必须点一下「重新加载界面」才能进到登录页。**

这一次和前两次都不一样：

- v1.0.0 是**闪退** —— 有异常，但没人接住；
- v1.0.1 是**白屏** —— 一个异常都没有，代码里没人知道出事了；
- v1.0.2 是**有异常、也被接住了，但接得不是地方** —— 异常本身无关紧要，
  却把整个启动流程打断了。

---

## 现象

- 打开 App → 立刻出现错误页「E140 WebView 配置失败」
- 点「重新加载界面」→ 界面正常出来了，之后一直能用
- 下次冷启动 → 又来一遍
- 手机：OnePlus PLF110，Android 16 (API 36)，应用版本 1.0.2
- WebView 内核：`com.google.android.webview 152.0.7977.88`

错误页上的技术细节（用户截图提供）：

```
—— 运行环境 ——
型号: OnePlus PLF110
系统: Android 16 (API 36)
应用版本: 1.0.2
WebView 内核: com.google.android.webview 152.0.7977.88
界面文件: www/index.html
页面状态: 脚本未就绪，加载方式 file:///android_asset/www/index.html
时间: 2026-10-14 21:32:26

—— 异常 ——
java.lang.SecurityException: Permission denied - application missing
INTERNET permission
```

**注意最后一行。** 这句话已经把答案写出来了，而且它和我们自己写的
「这个应用不联网」的直觉正好相反。

---

## 排查过程

### 第 1 步：从异常原文反查是哪一行代码

异常类型是 `SecurityException`，信息是「缺少 INTERNET 权限」。
在安卓里，抛这句话的常见 API 就那么几个，最典型的是
`WebSettings.setBlockNetworkLoads(false)`：

> 该方法要求应用声明 `android.permission.INTERNET`，
> 否则抛 `SecurityException`。

对着代码找，`MainActivity.configureWebView()` 里确实有这么一行：

```java
s.setBlockNetworkLoads(false);   // 界面全来自安装包，所以主动禁止联网加载
```

再对一下清单，`AndroidManifest.xml` 里只有这六条权限：

```
POST_NOTIFICATIONS / SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM
RECEIVE_BOOT_COMPLETED / WAKE_LOCK / VIBRATE
```

**没有 INTERNET。** 核对上一版编译产物里的合并清单
（`android/app/build/intermediates/merged_manifest/.../AndroidManifest.xml`），
同样没有 —— 也就是说这一行从 v1.0.1 起就埋在那儿了，
只是它排在配置流程的后半段，前面还有十几个设置项，所以一直到
Android 16 + 新版 WebView 上才稳定地撞上。

### 第 2 步：为什么「点一下重新加载」就好了

这才是这次问题的关键，也是它看起来「莫名其妙」的原因。看 `onCreate` 的顺序：

```java
created = new WebView(this);
((FrameLayout) rootView).addView(created);   // ① WebView 已经进了视图树
web = created;

try {
    configureWebView();                      // ② 在这里抛异常
} catch (Throwable t) {
    showProblem("E140 WebView 配置失败", ...);
    return;                                  // ③ 直接返回，什么都不做了
}
```

`configureWebView()` 内部的实际顺序是：

```java
WebSettings s = web.getSettings();
s.setJavaScriptEnabled(true);     // ← 这一步其实成功了
s.setDomStorageEnabled(true);
...                                // 十几个设置项
s.setBlockNetworkLoads(false);    // ← 在这里抛 SecurityException
web.setWebViewClient(...);        // ← 没执行到
web.addJavascriptInterface(...);  // ← 没执行到
```

于是出现一个很别扭的状态：

| 部件 | 状态 |
| --- | --- |
| WebView 控件 | 在视图树里，但被错误页盖住（`setVisibility(GONE)`） |
| JavaScript 开关 | **已经打开了**（抛异常之前就设好了） |
| WebViewClient | **没挂上** —— 加载失败、渲染进程死亡都没人管 |
| JS 桥 `HWSentinel` | **没挂上** —— 网页层没法回报「我起来了」 |
| 页面 | 压根没加载（`loadEntryPage()` 在 return 后面） |

点「重新加载界面」时走的是 `retryLoad()` → `loadEntryPage()`，
它只做一件事：**调用 `loadUrl()`**。这一次 WebView 虽然仍没挂 WebViewClient，
但 JavaScript 是开的，页面自己跑起来、自己把界面画出来了，
安卓这边靠看门狗里的 `evaluateJavascript("window.__HWS_READY__ === true")`
也能问到「哦，起来了」，于是收起错误页。

**一句话总结**：异常本身只影响了一个无关紧要的设置项（禁止网络加载），
但因为它排在中间，把「挂 WebViewClient」这件要紧事一起带走了，
而错误页又让用户以为整个 WebView 都废了。

这是 v1.0.1 那次修复留下的后遗症：当时的目标是「任何失败都有人话可说」，
于是把所有东西都包进 try/catch 并且 `return`，
却没有区分「致命失败」和「不影响大局的失败」。

### 第 3 步：在模拟器上把故障做出来

换手机复现成本太高，用工程里现成的 AVD（`hwtest`，Android 14 / API 34）验证：

```
adb install -r homework-sentinel-v1.0.2.apk
adb shell am start -n com.homesentinel.app/.MainActivity
adb shell uiautomator dump /sdcard/ui.xml
adb shell screencap -p /sdcard/e140.png
```

复现出来的界面和用户截图一致（标题、五条建议、三个按钮、技术细节），
`adb logcat` 里的关键几行：

```
E MainActivity: WebView 配置失败
E MainActivity: java.lang.SecurityException: Permission denied - application missing INTERNET permission
```

确认根因无误。

---

## 修复方案（v1.0.3）

三层一起修，从「不让它出错」到「出错了也别烦用户」。

### 1. 清单补上 INTERNET 权限（治本）

```xml
<!-- WebView 的硬性要求，哪怕界面一个字都不联网 -->
<uses-permission android:name="android.permission.INTERNET" />
```

这里有个容易踩的认知坑：**只要用了 WebView，就应当声明 INTERNET 权限**，
哪怕所有页面都是 `file:///android_asset/` 里的本地文件。
WebView 内部会按「本应用有没有这个权限」来决定网络策略，
没有权限时有些设置项直接抛异常。

应用本身的行为没有变：界面仍然完全来自安装包，
并且仍然显式 `setBlockNetworkLoads(false)` —— 主动禁止联网加载，
这一点也写在了权限注释里。

### 2. 配置流程重排 + 逐项容错（治「一个坏设置拖垮全局」）

```java
private void configureWebView() {
    // 第一件事：打开 JavaScript。这条失败才是真的没救。
    web.getSettings().setJavaScriptEnabled(true);

    // 第二件事：先把 WebViewClient 挂上 —— 它决定「失败时有没有人说话」
    installWebViewClient();

    applyWebSettings();     // 剩余设置逐项应用
    disableSystemDarkening();
    web.addJavascriptInterface(new NativeBridge(), "HWSentinel");
}
```

`applyWebSettings()` 里每个设置项都单独包一层：

```java
private interface Setting { void apply(WebSettings s); }

private void eachSetting(String name, Setting setting, Setting fallback) {
    try {
        setting.apply(web.getSettings());
    } catch (Throwable t) {
        Log.w(TAG, "WebView 设置项 " + name + " 未能应用（不致命）: " + t);
        if (fallback != null) { /* 试一下备选写法，例如 setBlockNetworkLoads(true) */ }
    }
}
```

两条原则：

1. **先做「不做就没法用」的事**（JavaScript、WebViewClient），再做锦上添花的；
2. **每个可选项单独 try/catch**，坏一个不牵连其它，也不会中断启动。

### 3. E140 降级为「可恢复」，页面起来了就自动消失（治「不该打扰用户」）

以前 `E140` 是当场 `showProblem()`，属于「判死刑」。现在改成先记账：

```java
} catch (Throwable t) {
    Log.e(TAG, "WebView 配置未全部完成，继续尝试启动", t);
    setSoftProblem("E140 WebView 配置未全部完成", describe(t));
}
loadEntryPage();      // 继续加载，不再 return
```

裁决交给两个回调（8 秒宽限）：

- 页面自己起来了（`onPageUsable()`）→ `hideProblem()` + `clearSoftProblem()`，
  **用户什么都不用点**；
- 8 秒后页面仍没起来 → `flushSoftProblem()`，把原始原因（含堆栈）显示出来，
  错误页只留给「确实没救」的情况。

同理，`onResume` / 权限回调里那些「静默降级」也不会再把用户堵在错误页上。

### 4. 顺手补的

- `tools/build-android.ps1` 增加 Gradle 查找路径
  （`tools/install-android-sdk.ps1` 会把 Gradle 解压到 SDK 旁边，
  原来的候选路径里没有它，本机因此找不到 Gradle）
- 版本号 `versionCode 3 → 4`、`versionName 1.0.2 → 1.0.3`

---

## 验证

**一、安卓端编译自检**

```
== 1/3  检查网页资源能否同步进 assets ==
   OK   入口文件完整，14992 字节（含启动占位层）
   OK   app.js 语法正确
== 2/3  编译安卓资源（aapt）==
   OK   资源与清单正常
== 3/3  编译 Java（javac + android.jar）==
   OK   编译通过，生成 49 个 class
安卓端自检全部通过。
```

**二、模拟器上「修复前 → 修复后」对照（同一台 AVD、同样的操作）**

| 版本 | 启动后的界面 | 需要手动点「重新加载」吗 |
| --- | --- | --- |
| v1.0.2 | E140 错误页 | **需要** |
| v1.0.3 | 直接进登录页 | 不需要 |

**三、合并清单里确实有 INTERNET 了**

```
<uses-permission android:name="android.permission.INTERNET" />
```

---

## 经验总结

1. **「捕获了异常」不等于「处理好了异常」。**
   v1.0.2 把整段配置包在一个 try 里，等于宣布「这里任何一步出错都放弃全部」。
   正确的粒度是：**致命的事单独保证，可选的事各自容错**。

2. **初始化代码的顺序本身就是设计。**
   如果把 `setWebViewClient()` 放在设置项之前，这次的异常就只是一行日志，
   用户什么都不会察觉。**先挂回调，再调设置** —— 回调是「出事时谁来管」，
   它比任何一个设置项都重要。

3. **错误页要有「刑期」，不能一判到底。**
   能自愈的问题应该自愈。把失败分成「致命」和「可恢复」两类，
   可恢复的先记账、等结果，别第一时间把用户拦在门外。

4. **不要因为「我的应用不联网」就省掉 INTERNET 权限。**
   只要用 WebView 就声明它。网络行为该由 `setBlockNetworkLoads`、
   `shouldInterceptRequest` 这类代码去约束，而不是靠不给权限。

5. **异常原文往往就是答案。**
   `application missing INTERNET permission` 已经指出了唯一的原因，
   剩下要做的只是搞清楚「为什么点了重新加载又好了」——
   而这一问的答案，决定了修复要分三层，而不是加一行权限就完事。
