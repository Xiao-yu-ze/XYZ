# Syncs MaaPVZ assets into this project.
#
# Why this script exists instead of just committing the files:
#   MaaPVZ is MIT-licensed, but its README states that the game screenshot assets remain
#   the property of the game publisher and are granted for NON-COMMERCIAL USE ONLY.
#   Redistributing those images from this repository is therefore avoided on purpose.
#   Run this against your own local MaaPVZ install instead.
#
# Usage:
#   pwsh -File tools/sync-maapvz-assets.ps1 -MaaPvzRoot "C:\path\to\MaaPVZ-win-x86_64-..."
#
# The coordinate table (coords.json) is MaaPVZ's own data, not game artwork, so it is
# treated the same way only for convenience - nothing here is required for the build.

param(
    [Parameter(Mandatory = $true)]
    [string]$MaaPvzRoot
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$assetsDir   = Join-Path $projectRoot 'app\src\main\assets'
$resourceDir = Join-Path $MaaPvzRoot 'resource'

if (-not (Test-Path $resourceDir)) {
    throw "MaaPVZ resource directory not found: $resourceDir"
}

function Copy-Into($source, $destination, $label) {
    if (-not (Test-Path $source)) {
        Write-Warning "skip $label (missing): $source"
        return
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
    Copy-Item $source $destination -Recurse -Force
    Write-Host "ok   $label -> $destination"
}

# --- 1. coordinate table -------------------------------------------------------
Copy-Into (Join-Path $MaaPvzRoot 'agent\assets\resource\coords.json') `
          (Join-Path $assetsDir 'coords.json') `
          'coords.json'

# --- 2. the 11 template images the Endless nodes reference ---------------------
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
        Copy-Into $src (Join-Path $assetsDir "maapvz\$t") "template $t"
    } else {
        Write-Warning "skip template (missing): $t"
    }
}

# --- 3. OCR models (PaddleOCR det + rec + charset) -----------------------------
Copy-Into (Join-Path $resourceDir 'model\ocr') (Join-Path $assetsDir 'ocr') 'ocr models'

# --- 4. job sets (the Endless strategy data) ----------------------------------
Copy-Into (Join-Path $resourceDir 'jobs') (Join-Path $assetsDir 'jobs') 'job sets'

Write-Host ''
Write-Host 'Done. These paths are gitignored, so the assets stay on this machine only.'
