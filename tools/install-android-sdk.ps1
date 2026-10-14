# =====================================================================
#  安装安卓构建所需的 SDK 组件（只需执行一次）
#
#  用法：
#     powershell -ExecutionPolicy Bypass -File tools\install-android-sdk.ps1
#
#  默认装到 D:\Android\Sdk，可用 -SdkRoot 指定别的位置。
# =====================================================================
[CmdletBinding()]
param(
    [string]$SdkRoot = 'D:\Android\Sdk',
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'

if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\java.exe'))) {
    throw "找不到 JDK。请先安装 JDK 17 或更高版本，并设置环境变量 JAVA_HOME。"
}

$sdkmanager = Join-Path $SdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $sdkmanager)) { throw "找不到 sdkmanager: $sdkmanager" }

$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot

Write-Host "SDK 目录 : $SdkRoot"
Write-Host "JDK 目录 : $JavaHome"
Write-Host ""

# 1) 接受许可协议
Write-Host '--- 接受 SDK 许可协议 ---'
$yes = ("y`n" * 60)
$yes | & $sdkmanager --sdk_root=$SdkRoot --licenses 2>&1 | Select-Object -Last 3

# 2) 安装组件
$packages = @(
    'platform-tools',
    'platforms;android-34',
    'build-tools;34.0.0'
)
Write-Host ''
Write-Host '--- 安装组件 ---'
foreach ($p in $packages) {
    Write-Host "  * $p"
    & $sdkmanager --sdk_root=$SdkRoot $p 2>&1 | Select-Object -Last 2
}

Write-Host ''
Write-Host '完成。已安装的组件：'
& $sdkmanager --sdk_root=$SdkRoot --list_installed 2>&1 | Select-Object -First 20
