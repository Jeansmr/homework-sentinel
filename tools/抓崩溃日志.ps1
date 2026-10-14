# =====================================================================
#  应用打不开？用这个脚本抓一份崩溃日志
#
#  使用方法：
#     1. 手机：设置 → 关于手机 → 连续点「版本号」7 次，开启开发者选项
#     2. 手机：设置 → 开发者选项 → 打开「USB 调试」
#     3. 用数据线把手机连到电脑，手机上弹出「允许 USB 调试吗」→ 允许
#     4. 在本文件上右键 →「使用 PowerShell 运行」
#     5. 按提示，在手机上打开「作业哨兵」，让它闪退一次
#
#  脚本会在桌面生成一份 崩溃日志.txt，把它发给我即可。
# =====================================================================

$ErrorActionPreference = 'Continue'
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

function Write-Step($n, $text) {
    Write-Host ""
    Write-Host "[$n] $text" -ForegroundColor Cyan
}
function Write-Ok($text)   { Write-Host "    $text" -ForegroundColor Green }
function Write-Warn2($text){ Write-Host "    $text" -ForegroundColor Yellow }
function Write-Err2($text) { Write-Host "    $text" -ForegroundColor Red }

Write-Host ""
Write-Host "=====================================================" -ForegroundColor White
Write-Host "  作业哨兵 · 崩溃日志抓取工具" -ForegroundColor White
Write-Host "=====================================================" -ForegroundColor White

# ---------------------------------------------------------------
# 1. 找到 adb
# ---------------------------------------------------------------
Write-Step 1 "查找 adb 工具"

$adb = $null
$candidates = @(
    "$env:ANDROID_HOME\platform-tools\adb.exe",
    "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe",
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "D:\Android\Sdk\platform-tools\adb.exe",
    "$env:ProgramFiles\Android\Sdk\platform-tools\adb.exe"
)
foreach ($c in $candidates) {
    if ($c -and (Test-Path $c)) { $adb = $c; break }
}
if (-not $adb) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $adb = $cmd.Source }
}

if (-not $adb) {
    Write-Err2 "没有找到 adb。"
    Write-Host ""
    Write-Host "  两个办法："
    Write-Host "  A. 装一下 Android SDK 命令行工具（本项目 tools\install-android-sdk.ps1）"
    Write-Host "  B. 直接跳过这个脚本：打开应用，如果出现错误页，"
    Write-Host "     点上面的「复制错误信息」按钮，粘到聊天里发给我即可。"
    Write-Host ""
    Write-Host "  其实大多数情况 B 就够了。" -ForegroundColor Yellow
    exit 1
}
Write-Ok "找到: $adb"
& $adb version 2>&1 | Select-Object -First 1 | ForEach-Object { Write-Ok $_ }

# ---------------------------------------------------------------
# 2. 等手机连上
# ---------------------------------------------------------------
Write-Step 2 "等待手机连接"

& $adb start-server 2>&1 | Out-Null
$device = $null
for ($i = 1; $i -le 30; $i++) {
    $lines = & $adb devices 2>&1 | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' }
    if ($lines) {
        $device = ($lines[0] -split "`t")[0]
        break
    }
    $unauth = & $adb devices 2>&1 | Where-Object { $_ -match 'unauthorized' }
    if ($unauth -and $i % 5 -eq 0) {
        Write-Warn2 "手机提示「允许 USB 调试」了吗？请在手机上点「允许」。"
    } elseif ($i % 5 -eq 0) {
        Write-Warn2 "还没检测到设备，请确认：数据线已插好、USB 调试已打开（第 $i 次）"
    }
    Start-Sleep -Seconds 2
}

if (-not $device) {
    Write-Err2 "没有检测到手机。"
    Write-Host ""
    Write-Host "  请检查："
    Write-Host "  1. 数据线是不是只能充电的线（换一根试试）"
    Write-Host "  2. 手机开发者选项里的「USB 调试」是否打开"
    Write-Host "  3. 手机上是否弹出了「允许 USB 调试」的窗口，需要点允许"
    Write-Host "  4. 部分手机（小米/OPPO/vivo）还需要打开「USB 调试（安全设置）」"
    Write-Host ""
    Write-Host "  也可以不用这个脚本：应用出错时会显示错误页，" -ForegroundColor Yellow
    Write-Host "  点「复制错误信息」发给我就行。" -ForegroundColor Yellow
    exit 1
}
Write-Ok "已连接: $device"

# ---------------------------------------------------------------
# 3. 检查应用装没装
# ---------------------------------------------------------------
Write-Step 3 "检查应用是否已安装"

$pkg = (& $adb -s $device shell pm list packages 2>&1 | Select-String -Pattern 'homesentinel')
if ($pkg) {
    $pkg | ForEach-Object { Write-Ok $_.Line.Trim() }
} else {
    Write-Warn2 "没找到 com.homesentinel.app，请先安装 APK 再运行本脚本。"
}

# ---------------------------------------------------------------
# 4. 开始抓日志
# ---------------------------------------------------------------
Write-Step 4 "开始抓日志"

& $adb -s $device logcat -c 2>&1 | Out-Null
Write-Ok "已清空旧日志"

Write-Host ""
Write-Host "  ============================================" -ForegroundColor Yellow
Write-Host "   现在请拿起手机，点开「作业哨兵」" -ForegroundColor Yellow
Write-Host "   让它闪退一次（或者看看错误页写了什么）" -ForegroundColor Yellow
Write-Host "   然后回到电脑按任意键继续" -ForegroundColor Yellow
Write-Host "  ============================================" -ForegroundColor Yellow
Write-Host ""
$null = $Host.UI.RawUI.ReadKey('NoEcho,IncludeKeyDown')

Write-Host ""
Write-Ok "正在读取日志..."

$outDir = [Environment]::GetFolderPath('Desktop')
$outFile = Join-Path $outDir '崩溃日志.txt'

$log = & $adb -s $device logcat -d 2>&1

# 只保留有用的行，日志会小很多、也更好看
$keep = $log | Where-Object {
    $_ -match 'AndroidRuntime|FATAL|homesentinel|HomeworkSentinel|MainActivity|ReminderScheduler|NotificationHelper|ActivityManager.*homesentinel|WebView|chromium|libc.*homesentinel|DEBUG.*homesentinel'
}

$header = @()
$header += "作业哨兵 崩溃日志"
$header += "生成时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
$header += ""
$header += "===== 手机信息 ====="
$header += (& $adb -s $device shell getprop ro.product.manufacturer 2>&1) -join ''
$header += (& $adb -s $device shell getprop ro.product.model 2>&1) -join ''
$header += "Android " + ((& $adb -s $device shell getprop ro.build.version.release 2>&1) -join '') +
           " (API " + ((& $adb -s $device shell getprop ro.build.version.sdk 2>&1) -join '') + ")"
$header += ""
$header += "===== WebView 组件 ====="
$header += (& $adb -s $device shell dumpsys webviewupdate 2>&1 | Select-Object -First 25)
$header += ""
$header += "===== 应用安装信息 ====="
$header += (& $adb -s $device shell dumpsys package com.homesentinel.app 2>&1 |
            Select-String -Pattern 'versionName|versionCode|codePath|primaryCpuAbi|flags' |
            Select-Object -First 10)
$header += ""
$header += "===== 关键日志 ====="

$all = $header + ($keep | ForEach-Object { $_ })
[System.IO.File]::WriteAllLines($outFile, $all, [System.Text.UTF8Encoding]::new($false))

Write-Host ""
Write-Ok "日志已保存到: $outFile"
Write-Host "    共 $($keep.Count) 行关键日志" -ForegroundColor Green

# 顺手打印崩溃摘要
$fatal = $keep | Select-String -Pattern 'FATAL EXCEPTION|Caused by' | Select-Object -First 6
if ($fatal) {
    Write-Host ""
    Write-Host "  抓到崩溃信息了！摘要：" -ForegroundColor Green
    $fatal | ForEach-Object { Write-Host "    $($_.Line.Trim())" -ForegroundColor White }
} else {
    Write-Warn2 "没有抓到 FATAL EXCEPTION。可能应用这次没崩，或者崩溃发生在别的地方。"
    Write-Warn2 "把 崩溃日志.txt 发给我，我再看。"
}

Write-Host ""
Write-Host "  把这个文件发给我：$outFile" -ForegroundColor Cyan
Write-Host ""
