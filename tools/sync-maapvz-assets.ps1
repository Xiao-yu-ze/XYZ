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
#
# 注意 Copy-Item 的语义陷阱：`Copy-Item srcDir dstDir -Recurse` 在 dstDir **已存在**时
# 会把 srcDir 整个塞进去，得到 dstDir/srcDir/… 而不是覆盖内容。
# 之前 assets/ 里出现过 jobs/jobs/、ocr/ocr/ 这种嵌套重复（APK 里白白多出一份
# 46 MB 的 ONNX 模型），就是这个原因。所以先删掉目标，目录则展开内容再拷。
function Copy-Into($source, $destination, $label) {
    if (-not (Test-Path $source)) {
        Write-Warning "跳过 $label（源不存在）：$source"
        return
    }
    $parent = Split-Path -Parent $destination
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }

    if (Test-Path $destination) {
        Remove-Item $destination -Recurse -Force
    }

    if ((Get-Item $source).PSIsContainer) {
        New-Item -ItemType Directory -Force -Path $destination | Out-Null
        Copy-Item (Join-Path $source '*') $destination -Recurse -Force
    } else {
        Copy-Item $source $destination -Force
    }
    Write-Host "完成 $label -> $destination"
}

# --- 1. 坐标表 -----------------------------------------------------------------
Copy-Into (Join-Path $MaaPvzRoot 'agent\assets\resource\coords.json') `
          (Join-Path $assetsDir 'coords.json') `
          '坐标表 coords.json'

# --- 2. 模板图 -----------------------------------------------------------------
#
# 关键：直接铺到 assets 根下，**不加任何前缀**。
#
# 原因：pipeline JSON 里写的模板路径（`Endless/无尽挑战.png`、`General/能量豆.png`）
# 是 **相对 MaaPVZ 的 `resource/image/`** 的。只要让 `assets/` 与
# `resource/image/` 一一对应，这些路径原样就能打开，
# 既不用改 pipeline，也不用在代码里补前缀。
#
# 曾一度把目标设成 assets/maapvz/ 并打算在代码里补前缀 —— 那是错的：
# 补前缀是拿代码去迁就错误的目录布局，正确做法是让目录布局本身就是对的。
#
# 中文文件名不需要处理：实测 APK 内 `assets/Endless/无尽挑战.png` 的
# 文件名是正常 UTF-8（用设备上的 `unzip -l` 确认过），AssetManager.open 能直接打开。
# 注意别用 Windows 的 apkanalyzer 去核对中文名 —— 它输出会乱码，容易误判成
# 打包编码问题，从而做出一堆没必要的绕行。
#
# ★ 不能直接 Copy-Into 整个 image 目录到 $assetsDir ★
#   Copy-Into 会先 Remove-Item $destination -Recurse 再重建。
#   如果 $destination 就是 assetsDir，会把**前面已经复制好的 coords.json
#   一起删掉**（曾真实发生过：coords.json 静默消失，CoordsTable.point() 全崩）。
#   所以这里是遍历 image/ 的每个子项，逐个复制到 assetsDir 下 —— 只覆盖同名项，
#   不动 assetsDir 本身。
$imageRoot = Join-Path $resourceDir 'image'
if (Test-Path $imageRoot) {
    Get-ChildItem $imageRoot -Force | ForEach-Object {
        Copy-Into $_.FullName (Join-Path $assetsDir $_.Name) "模板 $($_.Name)"
    }
} else {
    Write-Warning "跳过模板图（源不存在）：$imageRoot"
}

# --- 2b. 清掉 image/ 根目录下的散图 --------------------------------------------
#
# MaaPVZ 的 `resource/image/` 根下还躺着几个不属于任何子目录的文件
# （神器一~四阶.png、以及它自带的一句「不要搬运这四个图片.txt」的提示）。
# 它们不被 pipeline 引用，属于作者的存放习惯，同步过来纯属噪音。
# 这里删掉 —— 保留会让 APK 里多出几个来路不明的资源。
$strayNames = @('神器一阶.png', '神器二阶.png', '神器三阶.png', '神器四阶.png', '不要搬运这四个图片.txt')
foreach ($n in $strayNames) {
    $p = Join-Path $assetsDir $n
    if (Test-Path $p) { Remove-Item $p -Force }
}
Write-Host '完成 清理 image/ 根目录散图'

# --- 3. OCR 模型（PaddleOCR 的 det + rec + 字典） -------------------------------
Copy-Into (Join-Path $resourceDir 'model\ocr') (Join-Path $assetsDir 'ocr') 'OCR 模型'

# --- 4. 作业集（无尽挑战的策略数据） ---------------------------------------------
Copy-Into (Join-Path $resourceDir 'jobs') (Join-Path $assetsDir 'jobs') '作业集'

# --- 5. 无尽挑战的 pipeline（声明式节点定义） -------------------------------------
# 作业集只提供「策略数据 + Custom 节点」，真正被 next 引用的识别节点
# （无尽局内_继续挑战 / 无尽局内_恭喜过关 / 无尽挑战_失败 …）定义在这里。
# 没有它，NodeGraph 从入口跑起来会因为 next 全部查不到而立刻结束。
#
# 只同步 Endless_ref 一棵树：其它流程（碎片挑战 / 双人对决 / 旅行原木 …）
# 我们目前不跑，同步过来只会白白增大 APK。
$pipelineSrc = Join-Path $resourceDir 'pipeline\Endless_ref'
if (Test-Path $pipelineSrc) {
    Copy-Into $pipelineSrc (Join-Path $assetsDir 'pipeline\Endless_ref') '无尽挑战 pipeline'
} else {
    Write-Warning "跳过 pipeline（源不存在）：$pipelineSrc —— 若你的版本没有 Endless_ref 目录，说明作业集与 pipeline 版本不配套"
}

Write-Host ''
Write-Host '全部完成。这些路径都已加入 .gitignore，素材只保留在本机，不会被提交。'
