# =====================================================================
#  安卓端编译自检（不需要 Gradle，不需要联网）
#
#  它做三件事：
#    1. 检查网页资源能不能正常同步进 assets（白屏最常见的原因之一）
#    2. 用 aapt 编译资源、生成 R.java —— 资源和清单有问题会在这里暴露
#    3. 用 javac 对着真正的 android.jar 编译全部 Java —— 语法/API 错误在这里暴露
#
#  它不会打包 APK。正式出包请用 tools\build-android.ps1（走 Gradle）。
#  之所以单独做这么一个脚本：Gradle 需要联网下载 AGP，而日常改代码时
#  只想快速确认「还能不能编过」，这个脚本几秒钟就能给答案。
#
#  用法：
#    powershell -ExecutionPolicy Bypass -File tools\check-android.ps1
# =====================================================================
[CmdletBinding()]
param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$BuildToolsVersion = '34.0.0',
    [string]$Platform = 'android-34'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$androidDir = Join-Path $root 'android'
$appDir = Join-Path $androidDir 'app\src\main'

# ---- 定位 SDK ----
if (-not $SdkRoot -or -not (Test-Path $SdkRoot)) {
    foreach ($c in @((Join-Path $env:LOCALAPPDATA 'Android\Sdk'), 'D:\Android\Sdk', 'C:\Android\Sdk')) {
        if (Test-Path $c) { $SdkRoot = $c; break }
    }
}
if (-not $SdkRoot -or -not (Test-Path $SdkRoot)) {
    throw '找不到 Android SDK。请设置 ANDROID_HOME，或先运行 tools\install-android-sdk.ps1。'
}
$bt = Join-Path $SdkRoot "build-tools\$BuildToolsVersion"
$androidJar = Join-Path $SdkRoot "platforms\$Platform\android.jar"
if (-not (Test-Path $bt)) { throw "找不到 build-tools $BuildToolsVersion：$bt" }
if (-not (Test-Path $androidJar)) { throw "找不到 $androidJar" }

if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\javac.exe'))) {
    $JavaHome = $env:JAVA_HOME
}
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\javac.exe'))) {
    throw '找不到 JDK。请设置 JAVA_HOME 指向 JDK 17 或更高版本。'
}
$javac = Join-Path $JavaHome 'bin\javac.exe'
$aapt = Join-Path $bt 'aapt.exe'

$work = Join-Path $env:TEMP ('hw-check-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path "$work\gen", "$work\classes" | Out-Null

$fail = 0
function Step($name) { Write-Host ''; Write-Host "== $name ==" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "   OK   $msg" -ForegroundColor Green }
function Bad($msg)  { Write-Host "   FAIL $msg" -ForegroundColor Red; $script:fail++ }

# 外部程序（javac / aapt）会把警告写到 stderr，而 PowerShell 在
# ErrorActionPreference=Stop 下会把 stderr 当成致命错误 ——
# 于是「编译成功、只有一条废弃 API 警告」会变成脚本中断。
# 因此这一段统一降到 Continue，真正的失败由退出码判断。
$ErrorActionPreference = 'Continue'

try {
    # ------------------------------------------------------------------
    Step '1/3  检查网页资源能否同步进 assets'

    & (Join-Path $root 'scripts\sync-web-to-android.ps1') -Root $root | Out-Null
    $www = Join-Path $appDir 'assets\www'
    $need = @('index.html', 'app.js', 'styles.css')
    $missing = @()
    foreach ($n in $need) { if (-not (Test-Path (Join-Path $www $n))) { $missing += $n } }
    if ($missing.Count) {
        Bad ("assets\www 里缺少: " + ($missing -join ', '))
    } else {
        $i = Get-Item (Join-Path $www 'index.html')
        # 入口文件必须完整，否则 WebView 只会显示白屏，且不会报错
        $html = Get-Content $i.FullName -Raw
        if ($html -notmatch '</html>') { Bad 'index.html 不是完整文件（找不到 </html>）' }
        elseif ($html -notmatch 'id="boot"') { Bad 'index.html 里没有启动占位层（id="boot"），白屏时用户将看不到任何提示' }
        else { Ok ("入口文件完整，{0} 字节（含启动占位层）" -f $i.Length) }
    }

    # 网页脚本语法
    $node = Get-Command node -ErrorAction SilentlyContinue
    if ($node) {
        $jsFiles = @(Get-ChildItem $www -Filter *.js -File)
        foreach ($f in $jsFiles) {
            $null = & node --check $f.FullName 2>&1
            if ($LASTEXITCODE -ne 0) { Bad "$($f.Name) 语法错误" } else { Ok "$($f.Name) 语法正确" }
        }
    } else {
        Write-Host '   --   没装 Node，跳过 JS 语法检查'
    }

    # ------------------------------------------------------------------
    Step '2/3  编译安卓资源（aapt）'

    # AGP 会把 namespace 写进 manifest 的 package 属性，aapt 需要它
    $manifest = Get-Content (Join-Path $appDir 'AndroidManifest.xml') -Raw
    $manifest = $manifest -replace '(<manifest xmlns:android="[^"]+")',
        '$1 package="com.homesentinel.app"'
    $manifestPath = Join-Path $work 'AndroidManifest.xml'
    [System.IO.File]::WriteAllText($manifestPath, $manifest, (New-Object System.Text.UTF8Encoding($false)))

    $resApk = Join-Path $work 'resources.apk'
    $aaptOut = & $aapt package -f -m -M $manifestPath -S (Join-Path $appDir 'res') `
        -A (Join-Path $appDir 'assets') -J "$work\gen" -I $androidJar -F $resApk 2>&1
    if ($LASTEXITCODE -ne 0) {
        Bad 'aapt 编译资源失败'
        $aaptOut | Select-Object -First 20 | ForEach-Object { Write-Host "       $_" }
    } elseif (-not (Test-Path (Join-Path $work 'gen\com\homesentinel\app\R.java'))) {
        Bad 'aapt 没有生成 R.java'
    } else {
        Ok ("资源与清单正常，资源包 {0} 字节" -f (Get-Item $resApk).Length)
    }

    # ------------------------------------------------------------------
    Step '3/3  编译 Java（javac + android.jar）'

    $sources = @(Get-ChildItem (Join-Path $appDir 'java') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
    $sources += @(Get-ChildItem "$work\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
    Write-Host ("   源文件 {0} 个" -f $sources.Count)

    # 注意：用 -source 8 而不是 17。
    # 安卓的 d8 在个别版本的 build-tools 上处理 Java 11+ 的 Nest 属性会崩，
    # 而这个工程用到的语法（含匿名内部类）Java 8 就够，编成 8 兼容面最广。
    $javacOut = & $javac -nowarn -encoding UTF-8 -source 8 -target 8 `
        -classpath $androidJar -d "$work\classes" $sources 2>&1
    if ($LASTEXITCODE -ne 0) {
        Bad 'javac 编译失败'
        $javacOut | Where-Object { $_ -notmatch 'bootstrap class path|警告|warning' } |
            Select-Object -First 25 | ForEach-Object { Write-Host "       $_" }
    } else {
        $n = (Get-ChildItem "$work\classes" -Recurse -Filter *.class).Count
        Ok ("编译通过，生成 $n 个 class")
    }
} finally {
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ''
if ($fail -eq 0) {
    Write-Host '安卓端自检全部通过。' -ForegroundColor Green
    exit 0
} else {
    Write-Host "安卓端自检有 $fail 项没通过。" -ForegroundColor Red
    exit 1
}
