# 把 MaaPVZ 的素材同步到本项目。
#
# 为什么不直接把素材提交进仓库：
#   MaaPVZ 采用 MIT 协议，但它的 README 明确指出：游戏截图素材的著作权归游戏厂商所有，
#   且仅限非商业用途。因此本仓库刻意不再分发这些图片，改为从你本地的 MaaPVZ 安装目录同步。
#
# 用法：
#   pwsh -File tools/sync-maapvz-assets.ps1 -MaaPvzRoot "C:\path\to\MaaPVZ-win-x86_64-..."
#
# 坐标表（coords.json）是 MaaPVZ 自己的数据而非游戏美术素材，这里一并按同种方式处理，
# 只是为了统一；这些东西都不是编译所必需的。

param(
    [Parameter(Mandatory = $true)]
    [string]$MaaPvzRoot
)

$ErrorActionPreference = 'Stop'

# 让控制台能正确显示中文
# 注意：这里必须用 New-Object，不能写 [Text.Encoding]::UTF8 ——
# 在 try 块里这种写法会被当成泛型参数解析，导致语法错误。
try { [Console]::OutputEncoding = (New-Object System.Text.UTF8Encoding $false) } catch { }

$projectRoot = Split-Path -Parent $PSScriptRoot
$assetsDir   = Join-Path $projectRoot 'app\src\main\assets'
$resourceDir = Join-Path $MaaPvzRoot 'resource'

if (-not (Test-Path $resourceDir)) {
    throw "找不到 MaaPVZ 的 resource 目录：$resourceDir"
}

# 复制单个文件或目录，并打印结果
function Copy-Into($source, $destination, $label) {
    if (-not (Test-Path $source)) {
        Write-Warning "跳过 $label（源不存在）：$source"
        return
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
    Copy-Item $source $destination -Recurse -Force
    Write-Host "完成 $label -> $destination"
}

# --- 1. 坐标表 -----------------------------------------------------------------
Copy-Into (Join-Path $MaaPvzRoot 'agent\assets\resource\coords.json') `
          (Join-Path $assetsDir 'coords.json') `
          '坐标表 coords.json'

# --- 2. 无尽挑战流程用到的 11 张模板图 -------------------------------------------
$templates = @(
    'create_yard/竞技.png',
    'Endless/frame/boss/功夫僵王.png',
    'Endless/frame/boss/僵王的头.png',
    'Endless/frame/僵尸头像.png',
    'Endless/out.png',
    'Endless/yes.png',
    'Endless/继续挑战_恭喜过关.png',
    'Endless/无尽挑战.png',
    'General/能量豆.png',
    'General/暂停.png',
    'Start_game/叉叉.png'
)
foreach ($t in $templates) {
    $src = Join-Path $resourceDir "image\$t"
    if (Test-Path $src) {
        Copy-Into $src (Join-Path $assetsDir "maapvz\$t") "模板 $t"
    } else {
        Write-Warning "跳过模板（源不存在）：$t"
    }
}

# --- 3. OCR 模型（PaddleOCR 的 det + rec + 字典） -------------------------------
Copy-Into (Join-Path $resourceDir 'model\ocr') (Join-Path $assetsDir 'ocr') 'OCR 模型'

# --- 4. 作业集（无尽挑战的策略数据） ---------------------------------------------
Copy-Into (Join-Path $resourceDir 'jobs') (Join-Path $assetsDir 'jobs') '作业集'

Write-Host ''
Write-Host '全部完成。这些路径都已加入 .gitignore，素材只保留在本机，不会被提交。'
