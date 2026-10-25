# ============================================================================
#  为演示商品生成封面图
# ============================================================================
#  用法： pwsh -File scripts/generate-product-images.ps1
#
#  【为什么需要它】
#  初始化数据里所有商品的 cover_url 都是空的，导致小程序与后台的商品图
#  全部显示 "No Image"。功能上没问题，但演示时看起来像没做完。
#
#  这里用 .NET 的 System.Drawing 直接画图，不依赖任何外部图床或网络 ——
#  演示环境常常没有外网，依赖 via.placeholder.com 这类服务的图片会加载失败。
#
#  【关于 URL 的存法】
#  数据库里存的是**相对路径** /files/product/1.png，而不是 http://ip:port/...。
#  原因：写死 IP 后换台机器、换个端口就全挂了。
#  小程序端需要的绝对地址由 utils/request.js 里的垫片在返回数据时补全
#  （见 request.js 的 rewriteAssetUrls）。
#
#  ⚠️ 编码提醒：本文件必须保存为「带 BOM 的 UTF-8」，
#     否则 Windows PowerShell 5.1 会把中文按 ANSI 解析导致语法错误。
#     详见 docs/06-踩坑记录.md 第 19 条。
# ============================================================================

param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$MySqlExe = 'D:\mysql\install\bin\mysql.exe',
    [string]$DbUser = 'root',
    [string]$DbPassword = '123456',
    [string]$DbName = 'nqx',
    [int]$Size = 400
)

$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

Add-Type -AssemblyName System.Drawing

$outDir = Join-Path $ProjectRoot 'uploads\product'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

Write-Output '================================================================'
Write-Output '  生成商品封面图'
Write-Output '================================================================'
Write-Output "  输出目录: $outDir"
Write-Output ''

# ---------------------------------------------------------------------------
# 配色：每个商品一个稳定的颜色（按 id 取模），保证同一商品每次生成颜色一致
# ---------------------------------------------------------------------------
$palette = @(
    @(38, 132, 255),  # 蓝
    @(76, 175, 80),   # 绿
    @(255, 152, 0),   # 橙
    @(156, 39, 176),  # 紫
    @(0, 150, 136),   # 青
    @(233, 30, 99),   # 玫红
    @(63, 81, 181),   # 靛蓝
    @(121, 85, 72),   # 棕
    @(96, 125, 139)   # 蓝灰
)

# 读取商品
$rows = & $MySqlExe "-u$DbUser" "-p$DbPassword" --batch --skip-column-names `
    --default-character-set=utf8mb4 `
    -e "SELECT id, name FROM $DbName.product WHERE deleted = 0 ORDER BY id;" 2>$null

if (-not $rows) {
    Write-Output '  没有查到商品，退出'
    exit 1
}

$count = 0
foreach ($row in $rows) {
    $parts = $row -split "`t"
    if ($parts.Count -lt 2) { continue }
    $id = [int]$parts[0]
    $name = $parts[1]

    $rgb = $palette[($id - 1) % $palette.Count]
    $file = Join-Path $outDir "$id.png"

    try {
        $bmp = New-Object System.Drawing.Bitmap $Size, $Size
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAlias

        # 背景：从左到右的轻微渐变，比纯色好看一点，且不依赖任何素材
        $c1 = [System.Drawing.Color]::FromArgb(255, $rgb[0], $rgb[1], $rgb[2])
        $c2 = [System.Drawing.Color]::FromArgb(255,
                [Math]::Min(255, $rgb[0] + 45),
                [Math]::Min(255, $rgb[1] + 45),
                [Math]::Min(255, $rgb[2] + 45))
        $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
            (New-Object System.Drawing.Point 0, 0),
            (New-Object System.Drawing.Point $Size, $Size), $c1, $c2)
        $g.FillRectangle($brush, 0, 0, $Size, $Size)

        # 商品名：优先用中文字体，取不到就退回默认字体
        $fontName = 'Microsoft YaHei'
        try {
            $testFont = New-Object System.Drawing.Font($fontName, 12)
            if ($testFont.Name -ne $fontName) { $fontName = 'SimHei' }
            $testFont.Dispose()
        } catch { $fontName = 'SimHei' }

        $fontSize = [Math]::Max(14, [Math]::Round($Size / 11))
        $font = New-Object System.Drawing.Font($fontName, $fontSize, [System.Drawing.FontStyle]::Bold)
        $white = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
        $fmt = New-Object System.Drawing.StringFormat
        $fmt.Alignment = [System.Drawing.StringAlignment]::Center
        $fmt.LineAlignment = [System.Drawing.StringAlignment]::Center

        # 名称过长时按字符断行（中文没有空格，用 MeasureString 逐字累加）
        $maxWidth = $Size - 30
        $lines = @()
        $current = ''
        foreach ($ch in $name.ToCharArray()) {
            $tryLine = $current + $ch
            $w = $g.MeasureString($tryLine, $font).Width
            if ($w -gt $maxWidth -and $current.Length -gt 0) {
                $lines += $current
                $current = [string]$ch
            } else {
                $current = $tryLine
            }
        }
        if ($current.Length -gt 0) { $lines += $current }

        $lineHeight = $g.MeasureString('测', $font).Height
        $totalHeight = $lines.Count * $lineHeight
        $startY = ($Size - $totalHeight) / 2

        for ($i = 0; $i -lt $lines.Count; $i++) {
            $rect = New-Object System.Drawing.RectangleF 0, ($startY + $i * $lineHeight), $Size, $lineHeight
            $g.DrawString($lines[$i], $font, $white, $rect, $fmt)
        }

        $bmp.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
        $g.Dispose(); $bmp.Dispose(); $brush.Dispose(); $font.Dispose(); $white.Dispose()

        # 数据库里存相对路径 —— 换机器、换端口都不用改数据
        $url = "/files/product/$id.png"
        & $MySqlExe "-u$DbUser" "-p$DbPassword" --default-character-set=utf8mb4 `
            -e "UPDATE $DbName.product SET cover_url = '$url' WHERE id = $id;" 2>$null | Out-Null

        $count++
        Write-Output ("  [{0,2}] {1,-28} -> {2}  ({3} 字节)" -f $id, $name, $url, (Get-Item $file).Length)
    } catch {
        Write-Output ("  [{0,2}] 生成失败: {1}" -f $id, $_.Exception.Message)
    }
}

Write-Output ''
Write-Output "  共生成 $count 张封面图"
Write-Output ''
Write-Output '  提示：小程序端需要的绝对地址由 utils/request.js 的 rewriteAssetUrls 补全，'
Write-Output '        数据库里保持相对路径，换环境无需改数据。'
