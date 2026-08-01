# Generates app icons for Arynox (Electron + Android).
# Run from the repo root:  powershell -File tools/build_icons.ps1
Add-Type -AssemblyName System.Drawing

$root = Split-Path $PSScriptRoot -Parent
$bgColor = [System.Drawing.Color]::FromArgb(18, 18, 18)     # #121212
$fgColor = [System.Drawing.Color]::FromArgb(138, 180, 248)  # #8AB4F8

function New-GlyphBitmap {
    param([int]$Size, [bool]$TransparentBg = $false, [double]$GlyphPct = 0.62)
    $bmp = New-Object System.Drawing.Bitmap($Size, $Size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    if ($TransparentBg) {
        $g.Clear([System.Drawing.Color]::Transparent)
    } else {
        $g.Clear($bgColor)
    }
    $fontSize = [math]::Max(8, $Size * $GlyphPct)
    $font = New-Object System.Drawing.Font('Segoe UI', $fontSize, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
    $sf = New-Object System.Drawing.StringFormat
    $sf.Alignment = [System.Drawing.StringAlignment]::Center
    $sf.LineAlignment = [System.Drawing.StringAlignment]::Center
    $brush = New-Object System.Drawing.SolidBrush($fgColor)
    $rect = New-Object System.Drawing.RectangleF(0, -($Size * 0.06), $Size, $Size)
    $g.DrawString('A', $font, $brush, $rect, $sf)
    $g.Dispose()
    $brush.Dispose()
    $font.Dispose()
    return $bmp
}

function Save-PngBytes {
    param($bmp)
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $ms.ToArray()
}

function Save-Png {
    param([string]$Path, $bmp)
    $dir = Split-Path $Path -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
}

function New-Ico {
    param([string]$Path)
    $dir = Split-Path $Path -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $sizes = 16, 32, 48, 64, 128, 256
    $pngs = @()
    foreach ($s in $sizes) {
        $bmp = New-GlyphBitmap $s
        $pngs += , @($s, (Save-PngBytes $bmp))
        $bmp.Dispose()
    }
    $count = $pngs.Count
    $offset = 6 + 16 * $count
    $bytes = New-Object System.Collections.Generic.List[byte]
    $bytes.AddRange([byte[]](0, 0, 1, 0, $count, 0))
    foreach ($p in $pngs) {
        $s = $p[0]; $data = $p[1]
        $wh = if ($s -ge 256) { 0 } else { $s }
        $bytes.AddRange([byte[]]($wh, $wh, 0, 0, 1, 0, 32, 0))
        $bytes.AddRange([BitConverter]::GetBytes([int]$data.Length))
        $bytes.AddRange([BitConverter]::GetBytes([int]$offset))
        $offset += $data.Length
    }
    foreach ($p in $pngs) { $bytes.AddRange([byte[]]$p[1]) }
    [System.IO.File]::WriteAllBytes($Path, $bytes.ToArray())
}

# --- Electron ---
$deskBuild = Join-Path $root 'desktop\build'
New-Ico (Join-Path $deskBuild 'icon.ico')
Save-Png (Join-Path $deskBuild 'icon.png') (New-GlyphBitmap 512)

# --- Android ---
$res = Join-Path $root 'mobile\app\src\main\res'
Save-Png (Join-Path $res 'drawable\ic_launcher_foreground.png') (New-GlyphBitmap 432 $true 0.42)

$mipmap = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
foreach ($k in $mipmap.Keys) {
    $bmp = New-GlyphBitmap $mipmap[$k]
    Save-Png (Join-Path $res "mipmap-$k\ic_launcher.png") $bmp
    Save-Png (Join-Path $res "mipmap-$k\ic_launcher_round.png") $bmp
    $bmp.Dispose()
}

$anydpi = Join-Path $res 'mipmap-anydpi-v26'
if (-not (Test-Path $anydpi)) { New-Item -ItemType Directory -Path $anydpi -Force | Out-Null }
$adaptive = @'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
'@
[System.IO.File]::WriteAllText((Join-Path $anydpi 'ic_launcher.xml'), $adaptive)
[System.IO.File]::WriteAllText((Join-Path $anydpi 'ic_launcher_round.xml'), $adaptive)

$colors = Join-Path $res 'values\colors.xml'
if (-not (Test-Path $colors)) {
    @'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#121212</color>
</resources>
'@ | Set-Content -Path $colors -Encoding UTF8
}

Write-Output 'Icons generated.'
