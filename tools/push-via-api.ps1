# =====================================================================
#  通过 GitHub REST API 把整个项目上传到仓库
#
#  为什么需要它：
#    本项目在部分网络环境（校园网限制、代理只放行 api.github.com 等）下，
#    git 到 github.com:443 的连接会不定时重置或 TLS 握手超时，
#    但 api.github.com 一直正常。这时可以改用 Contents API 逐个创建文件，
#    效果等同于 git push。
#
#  用法：
#     powershell -ExecutionPolicy Bypass -File tools/push-via-api.ps1 `
#         -Token <github_token> -Owner <用户名> [-Repo homework-sentinel] [-Branch main]
#
#     # 顺便发一个 Release（把 APK 挂上去，README 里的下载链接才能用）
#     powershell -ExecutionPolicy Bypass -File tools/push-via-api.ps1 `
#         -Token <github_token> -Owner Jeansmr `
#         -ReleaseTag v1.0.3 -ApkPath homework-sentinel-v1.0.3.apk
#
#  它做四件事：
#    1. 逐个文件提交（新增的 create，改动过的用远端 sha 覆盖）
#    2. 在最新的那一次提交上打 tag
#    3. 建立 Release 并上传 APK 资产
#    4. 回读仓库信息确认
#
#  性质说明：
#    这是「一个文件一个提交」，历史会比较碎。网络正常时请优先用 git push：
#        git remote add origin https://github.com/<用户名>/homework-sentinel.git
#        git push -u origin main
#
#  安全说明：
#    token 只读进内存，不写入任何文件，也不写进 git 配置。
#    用完之后请到 GitHub 设置里把这个 token 删掉。
# =====================================================================
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Token,
    [Parameter(Mandatory = $true)][string]$Owner,
    [string]$Repo = 'homework-sentinel',
    [string]$Branch = 'main',
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$Message = 'feat: 作业哨兵 —— 作业截止提醒应用（安卓 APK + PWA 双形态）',
    # 发了这两个参数就顺便建 Release
    [string]$ReleaseTag = '',
    [string]$ReleaseName = '',
    [string]$ReleaseNotes = '',
    [string]$ApkPath = ''
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
# 注意：分支刚由某次提交建立时，这个接口可能有一两秒延迟，重试几次更稳
for ($try = 1; $try -le 5; $try++) {
    $tree = Invoke-GitHub -Method Get -Uri "$api/git/trees/$Branch`?recursive=1" -AllowFail
    if ($tree -and $tree.tree) { break }
    Start-Sleep -Seconds 2
}
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
$unchanged = 0
$failed = New-Object System.Collections.ArrayList
$lastSha = $null

foreach ($dir in $orderedDirs) {
    foreach ($gitPath in $groups[$dir]) {
        $localPath = Join-Path $Root $gitPath.Replace('/', '\')
        if (-not (Test-Path -LiteralPath $localPath)) { continue }

        $bytes = [System.IO.File]::ReadAllBytes($localPath)
        # git blob 的 sha1 就是 git 的对象哈希：先自己算一遍，
        # 和远端一致就跳过 —— 不必先把整仓库传一遍再发现没变化。
        $header = [System.Text.Encoding]::ASCII.GetBytes("blob $($bytes.Length)`0")
        $sha1 = [System.Security.Cryptography.SHA1]::Create()
        $localSha = -join ($sha1.ComputeHash(($header + $bytes)) | ForEach-Object { $_.ToString('x2') })

        $body = @{ message = $Message; content = [System.Convert]::ToBase64String($bytes); branch = $Branch }
        if ($remoteFiles.ContainsKey($gitPath)) {
            if ($remoteFiles[$gitPath] -eq $localSha) { $unchanged++; continue }
            # 覆盖已存在的文件必须带上它当前的 blob sha，否则 API 返回 409
            $body['sha'] = $remoteFiles[$gitPath]
        }

        $encoded = ($gitPath -split '/' | ForEach-Object { [uri]::EscapeDataString($_) }) -join '/'
        $uri = "$api/contents/$encoded"

        $res = Invoke-GitHub -Method Put -Uri $uri -Body $body -AllowFail

        if ($res -and $res.commit) {
            $uploaded++
            $lastSha = $res.commit.sha
            if (-not $branchExists) {
                $branchExists = $true
                Write-Host "  初始提交已建立: $gitPath"
            }
        } else {
            [void]$failed.Add($gitPath)
        }

        if (($uploaded + $unchanged) % 15 -eq 0) {
            Write-Host "  进度: 更新 $uploaded，未变 $unchanged，失败 $($failed.Count) / 共 $($paths.Count)"
        }
    }
}

Write-Host ''
Write-Host "完成。更新 $uploaded 个，未变 $unchanged 个，失败 $($failed.Count) 个。"
if ($failed.Count -gt 0) {
    Write-Host '失败清单：'
    $failed | ForEach-Object { Write-Host "  - $_" }
}

# ---------------------------------------------------------------
# 4. 打 tag + 发 Release（可选）
# ---------------------------------------------------------------
if ($ReleaseTag) {
    Write-Host ''
    Write-Host "--- 发布 $ReleaseTag ---"

    if (-not $lastSha) {
        # 这一次没有任何文件变化：退而求其次，tag 打在当前分支头上
        $head = Invoke-GitHub -Method Get -Uri "$api/git/ref/heads/$Branch" -AllowFail
        if ($head) { $lastSha = $head.object.sha }
    }
    if (-not $lastSha) { throw '拿不到提交 sha，无法打 tag。' }

    # 4.1 tag 对象（不建 ref 也能被 Release 引用，但两者都建更规范）
    $tagger = @{
        name  = $Owner
        email = "$Owner@users.noreply.github.com"
        date  = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    }
    $tagObj = Invoke-GitHub -Method Post -Uri "$api/git/tags" -Body @{
        tag     = $ReleaseTag
        message = if ($ReleaseName) { $ReleaseName } else { $ReleaseTag }
        object  = $lastSha
        type    = 'commit'
        tagger  = $tagger
    } -AllowFail
    if ($tagObj) {
        Write-Host "  tag 对象已建立: $($tagObj.sha)"
        $ref = Invoke-GitHub -Method Post -Uri "$api/git/refs" -Body @{
            ref = "refs/tags/$ReleaseTag"
            sha = $tagObj.sha
        } -AllowFail
        if ($ref) { Write-Host "  tag ref 已建立: $ReleaseTag" }
        else { Write-Host "  tag ref 已存在或建立失败（不致命，Release 仍会指向 tag 对象）" }
    } else {
        Write-Host '  tag 对象建立失败，改为直接按分支头发布 Release'
    }

    # 4.2 Release 本身
    $name = if ($ReleaseName) { $ReleaseName } else { $ReleaseTag }
    $release = Invoke-GitHub -Method Post -Uri "$api/releases" -Body @{
        tag_name   = $ReleaseTag
        name       = $name
        body       = $ReleaseNotes
        draft      = $false
        prerelease = $false
    } -AllowFail
    if ($release) {
        Write-Host "  Release 已建立: $($release.html_url)"

        # 4.3 上传 APK 资产
        if ($ApkPath) {
            $apkFull = Join-Path $Root $ApkPath
            if (-not (Test-Path -LiteralPath $apkFull)) {
                Write-Host "  找不到 APK: $apkFull（跳过上传资产）"
            } else {
                $apkName = Split-Path -Leaf $apkFull
                $uploadUri = ($release.upload_url -replace '\{.*$', '') + "?name=$([uri]::EscapeDataString($apkName))"
                $apkBytes = [System.IO.File]::ReadAllBytes($apkFull)
                $asset = $null
                for ($try = 1; $try -le 4; $try++) {
                    try {
                        $asset = Invoke-RestMethod -Method Post -Uri $uploadUri -Headers @{
                            'Authorization'        = "Bearer $Token"
                            'Accept'               = 'application/vnd.github+json'
                            'User-Agent'           = 'homework-sentinel-push'
                            'X-GitHub-Api-Version' = '2022-11-28'
                        } -Body $apkBytes -ContentType 'application/vnd.android.package-archive' -TimeoutSec 300
                        break
                    } catch {
                        Write-Host ("    第 $try 次上传失败: " + $_.Exception.Message)
                        Start-Sleep -Seconds (2 * $try)
                    }
                }
                if ($asset) {
                    Write-Host "  APK 已上传: $($asset.name)  $($asset.size) 字节"
                    Write-Host "  下载地址  : $($asset.browser_download_url)"
                } else {
                    Write-Host '  APK 上传失败，可稍后手工拖到 Release 页面上'
                }
            }
        }
    } else {
        Write-Host '  Release 建立失败（可能同名 tag 已存在），请到网页上手工建一次'
    }
}

# ---------------------------------------------------------------
# 5. 校验
# ---------------------------------------------------------------
$repoInfo = Invoke-GitHub -Method Get -Uri $api
Write-Host ''
Write-Host "仓库名  : $($repoInfo.full_name)"
Write-Host "默认分支: $($repoInfo.default_branch)"
Write-Host "大小    : $($repoInfo.size) KB"
Write-Host "地址    : $($repoInfo.html_url)"
