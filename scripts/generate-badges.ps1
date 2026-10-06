#requires -Version 7.0
<#
    Generates the badge bitmap-font sheet from pixel maps.

    The art is white/gray on purpose: the game tints each glyph with the chat
    style color, so one glyph serves every platform color (the mod sword is
    Twitch purple on Twitch and Kick green on Kick). Pixel values:
      '#' = white (full tint color)   '+' = 205 gray   '-' = 150 gray   '.' = transparent

    Cell order matches the glyph chars in badges.json: \uE000..\uE004.
#>
param(
    [string]$Out = "src/main/resources/assets/streamchatbridge/textures/font/badges.png"
)

Add-Type -AssemblyName System.Drawing

$icons = [ordered]@{
    # \uE000 broadcaster - red circle
    broadcaster = @(
        "...###...",
        "..#####..",
        ".#######.",
        "#########",
        "#########",
        "#########",
        ".#######.",
        "..#####..",
        "...###..."
    )
    # \uE001 mod - Minecraft-style sword (bright blade edge, perpendicular guard, handle, pommel)
    mod = @(
        "........#",
        ".......#+",
        "......#+.",
        ".-...#+..",
        "..-.#+...",
        "...##....",
        "..-.-....",
        ".-...-...",
        "--......."
    )
    # \uE002 vip - pink diamond (darker lower facets)
    vip = @(
        "....#....",
        "...#+#...",
        "..#+++#..",
        ".#+++++#.",
        "#++---++#",
        ".#+---+#.",
        "..#---#..",
        "...#+#...",
        "....#...."
    )
    # \uE003 sub - star
    sub = @(
        "....#....",
        "...###...",
        "..#####..",
        "#########",
        ".#######.",
        "..#####..",
        "..##.##..",
        ".##...##.",
        ".#.....#."
    )
    # \uE004 founder - shiny diamond (white shine streak, darker lower facets)
    founder = @(
        "....#....",
        "...#+#...",
        "..#+##...",
        ".#+##++#.",
        "#+#+++++#",
        ".#+---+#.",
        "..#---#..",
        "...#+#...",
        "....#...."
    )
}

$cell = 9
$width = $cell * $icons.Count
$height = $cell
$bmp = New-Object System.Drawing.Bitmap -ArgumentList @([int]$width, [int]$height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
try {
    $transparent = [System.Drawing.Color]::FromArgb(0, 0, 0, 0)
    $white = [System.Drawing.Color]::FromArgb(255, 255, 255, 255)
    $light = [System.Drawing.Color]::FromArgb(255, 205, 205, 205)
    $mid = [System.Drawing.Color]::FromArgb(255, 150, 150, 150)

    $x = 0
    foreach ($name in $icons.Keys) {
        $rows = $icons[$name]
        if ($rows.Count -ne $cell) { throw "$name has $($rows.Count) rows, expected $cell" }
        for ($y = 0; $y -lt $cell; $y++) {
            $row = $rows[$y]
            if ($row.Length -ne $cell) { throw "$name row $y has $($row.Length) columns, expected $cell" }
            for ($i = 0; $i -lt $cell; $i++) {
                $color = switch ($row[$i]) {
                    '#' { $white }
                    '+' { $light }
                    '-' { $mid }
                    default { $transparent }
                }
                $bmp.SetPixel($x + $i, $y, $color)
            }
        }
        $x += $cell
    }

    $dir = Split-Path $Out -Parent
    if ($dir) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $bmp.Save((Join-Path (Get-Location) $Out), [System.Drawing.Imaging.ImageFormat]::Png)
    "wrote $Out ($width x $height, $($icons.Count) cells)"
} finally {
    $bmp.Dispose()
}
