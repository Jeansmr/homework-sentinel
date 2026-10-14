# =====================================================================
#  生成用于本地发布的签名密钥（只需执行一次）
#
#  用法：
#     powershell -ExecutionPolicy Bypass -File tools\make-keystore.ps1
#
#  说明：
#   - 这是「自签名」密钥，用来给 APK 签名，方便直接装到自己手机上。
#   - 密码是 hwsentinel（仅本地使用，方便你自己重新打包）。
#   - 如果以后要上架应用商店，请换成你自己的正式密钥，并妥善保管。
# =====================================================================
[CmdletBinding()]
param(
    [string]$KeystorePath = (Join-Path (Split-Path -Parent $PSScriptRoot) 'android\keystore\hw-sentinel.jks'),
    [string]$Password = 'hwsentinel',
    [string]$Alias = 'hwsentinel'
)

$ErrorActionPreference = 'Stop'

if (-not $env:JAVA_HOME) { throw '请先设置环境变量 JAVA_HOME 指向 JDK 目录。' }
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
if (-not (Test-Path $keytool)) { throw "找不到 keytool: $keytool" }

if (Test-Path $KeystorePath) {
    Write-Host "密钥已存在，跳过: $KeystorePath"
    exit 0
}

$dir = Split-Path -Parent $KeystorePath
New-Item -ItemType Directory -Force -Path $dir | Out-Null

Write-Host "生成签名密钥: $KeystorePath"
& $keytool -genkeypair `
    -keystore $KeystorePath `
    -alias $Alias `
    -keyalg RSA -keysize 2048 -validity 10000 `
    -storepass $Password -keypass $Password `
    -dname "CN=Homework Sentinel, OU=Student Project, O=Personal, L=Beijing, ST=Beijing, C=CN"

if ($LASTEXITCODE -ne 0) { throw "keytool 执行失败，退出码 $LASTEXITCODE" }
Write-Host '完成。'
