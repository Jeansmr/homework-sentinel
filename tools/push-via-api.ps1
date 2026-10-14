# =====================================================================
#  通过 GitHub REST API 把整个项目上传到仓库（不需要 git push）
#
#  为什么这么做：
#    某些网络环境下 git 到 github.com:443 的连接不稳定，但 api.github.com 是通的。
#    这个脚本改用 Contents API 逐个创建文件，效果和 push 一样。
#
#  用法：
#     powershell -ExecutionPolicy Bypass -File tools/push-via-api.ps1 `
#         -Token <github_token> -Owner <用户名> -Repo homework-sentinel
#
#  安全说明：token 只作为参数使用，不写入任何文件，也不写进 git 配置。
# =====================================================================
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Token,
    [Parameter(Mandatory = $true)][string]$Owner,
    [string]$Repo = 'homework-sentinel',
    [string]$Branch = 'main',
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$Message = 'feat: 作业哨兵 —— 作业截止提醒应用（安卓 APK + PWA 双形态）'
)

$ErrorActionPreference = 'Stop'
$U8 = [System.Text.UTF8Encoding]::new($false)

$api = "https://api.github.com/repos/$Owner/$Repo"
$headers = @{
    'Authorization' = "Bearer $Token"
    'Accept'        = 'application/vnd.github+json'
    'User-Agent'    = 'homework-sentinel-push'
}

function Invoke-GitHub {
    param([string]$Method, [string]$Uri, $Body, [switch]$AllowFail)
    $lastError = $null
    for ($try = 1; $try -le 4; $try++) {
        try {
            if ($null -ne $Body) {
                $json = $Body | ConvertTo-Json -Depth 8 -Compress
                $bytes = $U8.GetBytes($json)
                return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $headers `
                       -Body $bytes -ContentType 'application/json; charset=utf-8' -TimeoutSec 90
            }
            return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $headers -TimeoutSec 90
        } catch {
            $lastError = $_
            if ($AllowFail) { return $null }
            if ($try -lt 4) {
                Write-Host ("    第 $try 次失败，重试: " + $_.Exception.Message)
                Start-Sleep -Seconds (2 * $try)
            }
        }
    }
    if ($AllowFail) { return $null }
    throw $lastError
}

# ---------------------------------------------------------------
# 1. 列出要上传的文件（用 git ls-files -z，避免中文路径被转义）
# ---------------------------------------------------------------
Push-Location $Root
try {
    $raw = & git ls-files -z
    $paths = ($raw -join '') -split "`0" | Where-Object { $_ -ne '' }
} finally {
    Pop-Location
}

if ($paths.Count -eq 0) { throw 'git ls-files 没有返回文件，确认已经 git add 过。' }
Write-Host "待上传文件: $($paths.Count) 个"

# ---------------------------------------------------------------
# 2. 看看远端已有哪些文件（用于增量更新）
# ---------------------------------------------------------------
$remoteFiles = @{}
$branchExists = $false
$tree = Invoke-GitHub -Method Get -Uri "$api/git/trees/$Branch`?recursive=1" -AllowFail
if ($tree -and $tree.tree) {
    $branchExists = $true
    foreach ($item in $tree.tree) {
        if ($item.type -eq 'blob') { $remoteFiles[$item.path] = $item.sha }
    }
    Write-Host "远端已有 $($remoteFiles.Count) 个文件"
} else {
    Write-Host '远端还是空的（首次上传）'
}

# ---------------------------------------------------------------
# 3. 按目录层级上传（先父目录，后子目录）
# ---------------------------------------------------------------
$groups = @{}
foreach ($rel in $paths) {
    $gitPath = $rel.Replace('\', '/')
    $dir = ''
    $idx = $gitPath.LastIndexOf('/')
    if ($idx -ge 0) { $dir = $gitPath.Substring(0, $idx) }
    if (-not $groups.ContainsKey($dir)) { $groups[$dir] = New-Object System.Collections.ArrayList }
    [void]$groups[$dir].Add($gitPath)
}

$orderedDirs = $groups.Keys | Sort-Object { ($_ -split '/').Count }, { $_ }

$uploaded = 0
$skipped = 0
$failed = New-Object System.Collections.ArrayList

foreach ($dir in $orderedDirs) {
    foreach ($gitPath in $groups[$dir]) {
        $localPath = Join-Path $Root $gitPath.Replace('/', '\')
        if (-not (Test-Path -LiteralPath $localPath)) { continue }

        if ($remoteFiles.ContainsKey($gitPath)) { $skipped++; continue }

        $bytes = [System.IO.File]::ReadAllBytes($localPath)
        $b64 = [System.Convert]::ToBase64String($bytes)

        $encoded = ($gitPath -split '/' | ForEach-Object { [uri]::EscapeDataString($_) }) -join '/'
        $uri = "$api/contents/$encoded"

        $res = Invoke-GitHub -Method Put -Uri $uri -Body @{
            message = $Message
            content = $b64
            branch  = $Branch
        } -AllowFail

        if ($res) {
            $uploaded++
            if (-not $branchExists) {
                $branchExists = $true
                Write-Host "  初始提交已建立: $gitPath"
            }
        } else {
            [void]$failed.Add($gitPath)
        }

        if (($uploaded + $skipped) % 15 -eq 0) {
            Write-Host "  进度: 上传 $uploaded，跳过 $skipped，失败 $($failed.Count) / 共 $($paths.Count)"
        }
    }
}

Write-Host ''
Write-Host "完成。上传 $uploaded 个，跳过 $skipped 个，失败 $($failed.Count) 个。"
if ($failed.Count -gt 0) {
    Write-Host '失败清单：'
    $failed | ForEach-Object { Write-Host "  - $_" }
}

# ---------------------------------------------------------------
# 4. 校验
# ---------------------------------------------------------------
$repo = Invoke-GitHub -Method Get -Uri $api
Write-Host ''
Write-Host "仓库名  : $($repo.full_name)"
Write-Host "默认分支: $($repo.default_branch)"
Write-Host "大小    : $($repo.size) KB"
Write-Host "地址    : $($repo.html_url)"
