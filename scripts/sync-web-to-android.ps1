# =====================================================================
#  把 app/ 里的网页资源同步到安卓工程的 assets/www
#  网页版和安卓版共用同一份界面代码，避免两处改、改漏一处。
# =====================================================================
[CmdletBinding()]
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'

$src = Join-Path $Root 'app'
$dst = Join-Path $Root 'android\app\src\main\assets\www'

if (-not (Test-Path $src)) { throw "找不到网页资源目录: $src" }

if (Test-Path $dst) { Remove-Item $dst -Recurse -Force }
New-Item -ItemType Directory -Force -Path $dst | Out-Null

$files = Get-ChildItem -Path $src -Recurse -File | Where-Object {
    # 排除开发调试用的文件：_ 开头的临时页，以及界面自测页
    $rel = $_.FullName.Substring($src.Length).TrimStart('\', '/')
    if ($_.Name.StartsWith('_')) { return $false }
    if ($rel -match '(^|[\\/])selftest\.html$') { return $false }
    return $true
}
foreach ($f in $files) {
    $rel = $f.FullName.Substring($src.Length).TrimStart('\', '/')
    $target = Join-Path $dst $rel
    $dir = Split-Path -Parent $target
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    Copy-Item -LiteralPath $f.FullName -Destination $target -Force
}

Write-Host ("已同步 {0} 个文件到 assets\www" -f $files.Count)
$files | ForEach-Object { Write-Host ("  - " + $_.FullName.Substring($src.Length).TrimStart('\', '/')) }
