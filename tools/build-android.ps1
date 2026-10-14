# =====================================================================
#  Windows 下打包安卓 APK
#
#  用法：
#     powershell -ExecutionPolicy Bypass -File tools\build-android.ps1
#     powershell -ExecutionPolicy Bypass -File tools\build-android.ps1 -Task assembleDebug
#
#  前提：
#     - 已安装 JDK 17+，并设置环境变量 JAVA_HOME
#     - 已安装 Android SDK（platform 34 + build-tools 34.0.0），并设置 ANDROID_HOME
#       没有的话先运行 tools\install-android-sdk.ps1
# =====================================================================
[CmdletBinding()]
param(
    [ValidateSet('assembleRelease', 'assembleDebug', 'clean', 'lint')]
    [string]$Task = 'assembleRelease',
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$androidDir = Join-Path $root 'android'

if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\java.exe'))) {
    throw '找不到 JDK。请设置环境变量 JAVA_HOME 指向 JDK 17 或更高版本。'
}

if (-not $SdkRoot -or -not (Test-Path $SdkRoot)) {
    $SdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
if (-not (Test-Path $SdkRoot)) {
    throw '找不到 Android SDK。请先运行 tools\install-android-sdk.ps1，或设置环境变量 ANDROID_HOME。'
}

$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot

Write-Host "JDK      : $JavaHome"
Write-Host "SDK      : $SdkRoot"
Write-Host "任务     : $Task"
Write-Host ''

# 让 Gradle 知道 SDK 在哪里
Set-Content -Path (Join-Path $androidDir 'local.properties') `
            -Value ('sdk.dir=' + $SdkRoot.Replace('\', '\\')) -Encoding utf8

# 1) 同步网页资源到 assets
& (Join-Path $root 'scripts\sync-web-to-android.ps1') -Root $root | Out-Host

# 2) 调用 Gradle
Push-Location $androidDir
try {
    Write-Host ''
    Write-Host '--- 执行 Gradle ---'

    # 优先用工程自带的 gradlew；没有再找本机安装的 Gradle
    $candidates = New-Object System.Collections.ArrayList
    [void]$candidates.Add((Join-Path $androidDir 'gradlew.bat'))
    if ($env:GRADLE_HOME) { [void]$candidates.Add((Join-Path $env:GRADLE_HOME 'bin\gradle.bat')) }
    [void]$candidates.Add((Join-Path (Split-Path -Parent $PSScriptRoot) 'gradle\gradle-8.9\bin\gradle.bat'))
    [void]$candidates.Add((Join-Path $env:USERPROFILE 'gradle\bin\gradle.bat'))

    $gradleExe = $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1

    if ($gradleExe) {
        Write-Host "使用: $gradleExe"
        & $gradleExe $Task --no-daemon
    } else {
        Write-Host '未找到 gradlew 或本地 Gradle，尝试 PATH 里的 gradle'
        & gradle $Task --no-daemon
    }

    if ($LASTEXITCODE -ne 0) { throw "构建失败，退出码 $LASTEXITCODE" }
} finally {
    Pop-Location
}

Write-Host ''
Write-Host '构建完成，产物：'
$apkDir = Join-Path $androidDir 'app\build\outputs\apk'
if (Test-Path $apkDir) {
    $apks = Get-ChildItem $apkDir -Recurse -Filter '*.apk'
    $apks | ForEach-Object {
        Write-Host ('  ' + $_.FullName + '  (' + [math]::Round($_.Length / 1MB, 2) + ' MB)')
    }

    # 顺手复制一份到仓库根目录，方便直接传到手机
    $release = $apks | Where-Object { $_.FullName -match 'release' } | Select-Object -First 1
    if ($release) {
        # 从 app/build.gradle.kts 读出版本号，保证文件名和版本一致
        $version = 'x.y.z'
        $gradleFile = Join-Path $androidDir 'app\build.gradle.kts'
        if (Test-Path $gradleFile) {
            $m = Select-String -Path $gradleFile -Pattern 'versionName\s*=\s*"([^"]+)"' | Select-Object -First 1
            if ($m) { $version = $m.Matches[0].Groups[1].Value }
        }
        $copyName = "homework-sentinel-v$version.apk"
        Copy-Item $release.FullName (Join-Path $root $copyName) -Force
        Write-Host ''
        Write-Host "已复制一份到 $copyName（直接传手机即可安装）"
    }
}
