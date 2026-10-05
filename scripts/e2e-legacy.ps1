#requires -Version 7.0
<#
    Server-join end-to-end client test for any Fabric/Forge/NeoForge node, with
    in-game screen check. Works on versions without quick-play by starting a
    matching vanilla server (era JDK) and joining via `-PscbServerJoin`.

    Steps:
      1. start vanilla server for the node's Minecraft version
      2. launch the dev client, auto-join localhost (quickPlayMultiplayer on >=1.20)
      3. maximize the game window and force guiScale:2 in options.txt beforehand
      4. inject chat with PostMessage: probe (must NOT reach the server), /scb status
      5. press F8 (open Stream Chat Bridge dashboard), then F2 to screenshot the
         game window; the PNG is copied to build/e2e/screenshots/<node>.png

    Examples:
        pwsh -File scripts/e2e-legacy.ps1 -Node 1.19-fabric
        pwsh -File scripts/e2e-legacy.ps1 -Node 26.3-fabric -NoScreen
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Node,

    [int]$Port = 25565,
    [string]$BuildJdk = "",
    [string]$Prefix = "",
    [string]$BaseWorld = "",
    [int]$StartTimeoutSec = 300,
    [int]$JoinTimeoutSec = 300,
    [int]$EchoTimeoutSec = 60,
    [switch]$Focus,
    [switch]$NoScreen,
    [switch]$ServerJoin,
    [switch]$LeaveOpen,
    [switch]$SkipEcho,

    # Production-jar mode: run a prebuilt .cmd instead of a Gradle dev client,
    # with the game directory and report/screenshot names given explicitly.
    [string]$ClientCmdFile = "",
    [string]$RunDirOverride = "",
    [string]$Label = "",

    # Range tests run a node's jar on a different MC version than the node's; the
    # server must match the *client* version.
    [string]$ServerMc = ""
)

$ErrorActionPreference = "Continue"
$Root = Split-Path $PSScriptRoot -Parent
Set-Location $Root

$nodeParts = $Node -split '-'
$Loader = $nodeParts[-1]
if (@("fabric", "forge", "neoforge") -notcontains $Loader) { throw "Cannot derive loader from node '$Node'" }
$Mc = ($nodeParts[0..($nodeParts.Count - 2)] -join '-')
$McV = [version]$Mc

# >=1.20 clients can jump straight into a (scratch copy of a) world; older ones
# join a matching vanilla server instead. `-ServerJoin` forces the server path
# (needed on Forge 26.x, whose dev runtime crashes on multiplayer joins).
$UseQuickPlay = ($McV -ge [version]"1.20") -and (-not $ServerJoin)
$WorldName = "ScbE2E"
if (-not $BaseWorld) { $BaseWorld = Join-Path $Root "build\e2e\base-world" }
if ($UseQuickPlay -and -not (Test-Path (Join-Path $BaseWorld "level.dat"))) {
    throw "Base world missing: $BaseWorld - generate it with a 1.19 server test first"
}

if (-not $BuildJdk) {
    $preferred = "C:\Program Files\Java\jdk-25.0.2"
    if (Test-Path $preferred) { $BuildJdk = $preferred }
    elseif ($env:JAVA_HOME) { $BuildJdk = $env:JAVA_HOME }
    else { throw "No JDK found: pass -BuildJdk or set JAVA_HOME." }
}
$env:JAVA_HOME = $BuildJdk

if (-not $ServerMc) { $ServerMc = $Mc }
$ServerMcV = [version]$ServerMc
$ServerJdk = if ($ServerMcV -ge [version]"26.1") { "C:\Program Files\Java\jdk-25.0.2" }
    elseif ($ServerMcV -ge [version]"1.20.5") { "C:\Program Files\Java\jdk-21" }
    else { "C:\Program Files\Java\jdk-17" }
if (-not (Test-Path (Join-Path $ServerJdk "bin\java.exe"))) { throw "Server JDK not found at $ServerJdk" }
$ServerJava = Join-Path $ServerJdk "bin\java.exe"

if (-not $Label) { $Label = $Node }
$RunDir = if ($RunDirOverride) { $RunDirOverride } elseif ($Loader -eq "neoforge") { Join-Path $Root "versions\$Node\run" } else { Join-Path $Root "run" }
$ClientLog = Join-Path $RunDir "logs\latest.log"
$ServerDir = Join-Path $Root "build\e2e\server-$ServerMc"
$ServerLog = Join-Path $ServerDir "logs\latest.log"
$OutLog = Join-Path $Root "build\e2e-$Label.out.log"
$ErrLog = Join-Path $Root "build\e2e-$Label.err.log"
$ServerOut = Join-Path $Root "build\e2e\server-$ServerMc.out.log"
$ReportPath = Join-Path $Root "build\e2e\$Label.log"
$ShotDir = Join-Path $Root "build\e2e\screenshots"
New-Item -ItemType Directory -Force -Path (Split-Path $ReportPath -Parent), $ShotDir | Out-Null

# --- run dir prep: GUI scale so the dashboard fits, no pause on focus loss -----
New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
$optPath = Join-Path $RunDir "options.txt"
$opt = @(if (Test-Path $optPath) { Get-Content -LiteralPath $optPath })
if ($opt -match '^guiScale:') { $opt = $opt -replace '^guiScale:.*$', 'guiScale:2' } else { $opt += 'guiScale:2' }
if ($opt -match '^pauseOnLostFocus:') { $opt = $opt -replace '^pauseOnLostFocus:.*$', 'pauseOnLostFocus:false' } else { $opt += 'pauseOnLostFocus:false' }
Set-Content -LiteralPath $optPath -Value $opt

# Neoforge nodes use per-node run dirs: seed config/token from the root run dir.
$rootCfg = Join-Path $Root "run\config"
$nodeCfg = Join-Path $RunDir "config"
New-Item -ItemType Directory -Force -Path $nodeCfg | Out-Null
foreach ($f in @("streamchatbridge.json")) {
    $src = Join-Path $rootCfg $f
    $dst = Join-Path $nodeCfg $f
    if ((Test-Path $src) -and ((-not (Test-Path $dst)) -or ((Get-Item $src).LastWriteTime -gt (Get-Item $dst).LastWriteTime))) {
        Copy-Item -LiteralPath $src -Destination $dst -Force
    }
}

if (-not $Prefix) {
    $cfgPath = Join-Path $nodeCfg "streamchatbridge.json"
    $Prefix = "!t "
    if (Test-Path $cfgPath) {
        try { $Prefix = (Get-Content $cfgPath -Raw | ConvertFrom-Json).twitchOutgoingPrefix } catch { }
    }
}

if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "Port $Port is already in use" }

$ServerJar = Join-Path $Root "build\.cache\server-$ServerMc.jar"
if (-not $UseQuickPlay -and -not (Test-Path $ServerJar)) {
    $manifest = Invoke-RestMethod "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json" -TimeoutSec 30
    $entry = $manifest.versions | Where-Object { $_.id -eq $ServerMc } | Select-Object -First 1
    $json = Invoke-RestMethod $entry.url -TimeoutSec 30
    Invoke-WebRequest $json.downloads.server.url -OutFile $ServerJar -TimeoutSec 900
}

$Stamp = Get-Date -Format "HHmmss"
$Probe = "e2e-$Stamp"

function Write-Report {
    param([string]$Message)
    Write-Host $Message
    Add-Content -LiteralPath $ReportPath -Value ("{0} {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Message) -ErrorAction SilentlyContinue
}

Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class ScbWin32 {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")] public static extern uint MapVirtualKey(uint uCode, uint uMapType);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);
    [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);
    [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr hWnd, out RECT lpRect);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
    [DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint esFlags);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hWnd, IntPtr hdcBlt, uint nFlags);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr hWndInsertAfter, int X, int Y, int cx, int cy, uint uFlags);
    [DllImport("user32.dll")] public static extern bool SystemParametersInfo(uint uiAction, uint uiParam, out RECT pvParam, uint fWinIni);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
}
"@
Add-Type -AssemblyName System.Windows.Forms

function Get-GameWindow {
    param([datetime]$After = [datetime]::MinValue)
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $found = Get-Process -ErrorAction SilentlyContinue | Where-Object {
            $_.MainWindowTitle -like "*Minecraft*" -and $_.MainWindowHandle -ne 0 -and $_.StartTime -gt $After
        } | Select-Object -First 1
        if ($found) { return $found }
        Start-Sleep -Seconds 1
    }
    return $null
}

function Force-Foreground {
    param([IntPtr]$Handle)
    [ScbWin32]::ShowWindow($Handle, 9) | Out-Null
    $foreground = [ScbWin32]::GetForegroundWindow()
    $foregroundPid = [uint32]0
    [ScbWin32]::GetWindowThreadProcessId($foreground, [ref]$foregroundPid) | Out-Null
    $currentThread = [ScbWin32]::GetCurrentThreadId()
    [ScbWin32]::AttachThreadInput($currentThread, $foregroundPid, $true) | Out-Null
    [ScbWin32]::SetForegroundWindow($Handle) | Out-Null
    [ScbWin32]::AttachThreadInput($currentThread, $foregroundPid, $false) | Out-Null
    Start-Sleep -Milliseconds 400
    return ([ScbWin32]::GetForegroundWindow() -eq $Handle)
}

function Post-Key {
    param([IntPtr]$Handle, [int]$Vk, [switch]$Extended)
    $scan = [ScbWin32]::MapVirtualKey([uint32]$Vk, 0)
    $down = [int64](1 -bor ($scan -shl 16))
    $up = [int64](0xC0000001 -bor ($scan -shl 16))
    if ($Extended) { $down = $down -bor 0x1000000; $up = $up -bor 0x1000000 }
    [ScbWin32]::PostMessage($Handle, 0x0100, [IntPtr]$Vk, [IntPtr]$down) | Out-Null
    Start-Sleep -Milliseconds 40
    [ScbWin32]::PostMessage($Handle, 0x0101, [IntPtr]$Vk, [IntPtr]$up) | Out-Null
    Start-Sleep -Milliseconds 40
}

function Send-KeyWithFakeFocus {
    param([IntPtr]$Handle, [int]$Vk, [string]$SendKeysKey)
    if ($Focus) {
        $focused = $false
        for ($attempt = 0; $attempt -lt 5 -and -not $focused; $attempt++) {
            $focused = Force-Foreground -Handle $Handle
            if (-not $focused) { Start-Sleep -Milliseconds 500 }
        }
        [System.Windows.Forms.SendKeys]::SendWait($SendKeysKey)
        Start-Sleep -Milliseconds 200
        return
    }
    [ScbWin32]::PostMessage($Handle, 0x0007, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null
    Start-Sleep -Milliseconds 300
    Post-Key -Handle $Handle -Vk $Vk
    Start-Sleep -Milliseconds 200
    [ScbWin32]::PostMessage($Handle, 0x0008, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null
}

function Capture-Window {
    param([IntPtr]$Handle, [string]$Dest)
    Add-Type -AssemblyName System.Drawing
    $r = New-Object ScbWin32+RECT
    [ScbWin32]::GetWindowRect($Handle, [ref]$r) | Out-Null
    $w = $r.Right - $r.Left
    $h = $r.Bottom - $r.Top
    if ($w -le 0 -or $h -le 0) { return $false }
    $bmp = New-Object System.Drawing.Bitmap($w, $h)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    try {
        $hdc = $g.GetHdc()
        try { $ok = [ScbWin32]::PrintWindow($Handle, $hdc, 2) } finally { $g.ReleaseHdc($hdc) }
    } finally { $g.Dispose() }
    if (-not $ok) { $bmp.Dispose(); return $false }
    $bmp.Save($Dest, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    return $true
}

function Send-ChatLine {
    param([IntPtr]$Handle, [string]$Text)
    if ($Focus) {
        $focused = $false
        for ($attempt = 0; $attempt -lt 5 -and -not $focused; $attempt++) {
            $focused = Force-Foreground -Handle $Handle
            if (-not $focused) { Start-Sleep -Milliseconds 500 }
        }
        if (-not $focused) { Write-Report "focus: could not foreground the game window" }
        # Per-char SendKeys sticks Shift on shifted characters (probe arrives as
        # T!E2E-...); paste the line from the clipboard instead.
        Set-Clipboard -Value $Text
        [System.Windows.Forms.SendKeys]::SendWait("t")
        Start-Sleep -Milliseconds 600
        [System.Windows.Forms.SendKeys]::SendWait("^v")
        Start-Sleep -Milliseconds 300
        [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
        Start-Sleep -Milliseconds 700
        return
    }

    [ScbWin32]::PostMessage($Handle, 0x0007, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null
    Start-Sleep -Milliseconds 300
    Post-Key -Handle $Handle -Vk 0x54
    Start-Sleep -Milliseconds 600
    foreach ($ch in $Text.ToCharArray()) {
        [ScbWin32]::PostMessage($Handle, 0x0102, [IntPtr]([int][char]$ch), [IntPtr]1) | Out-Null
        Start-Sleep -Milliseconds 20
    }
    Start-Sleep -Milliseconds 250
    Post-Key -Handle $Handle -Vk 0x0D
    Start-Sleep -Milliseconds 700
    [ScbWin32]::PostMessage($Handle, 0x0008, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null
}

# Keep the display/system awake: display sleep or lock makes the 26.3 render
# backend throw "Cannot acquire minimized window" mid-join on unattended runs.
[ScbWin32]::SetThreadExecutionState([uint32]2147483651) | Out-Null   # CONTINUOUS|SYSTEM|DISPLAY

Write-Report "=== e2e-legacy $Node (mc=$Mc, loader=$Loader, port=$Port, prefix='$Prefix', probe=$Probe, input=$(if ($Focus) { 'sendkeys' } else { 'postmessage' })) ==="

# --- world / server ------------------------------------------------------------
$srv = $null
if ($UseQuickPlay) {
    $savesDir = Join-Path $RunDir "saves"
    $worldDir = Join-Path $savesDir $WorldName
    New-Item -ItemType Directory -Force -Path $savesDir | Out-Null
    Remove-Item -LiteralPath $worldDir -Recurse -Force -ErrorAction SilentlyContinue
    Copy-Item -LiteralPath $BaseWorld -Destination $worldDir -Recurse -Force
    Write-Report "world: quick-play '$WorldName' from base world"
} else {
    New-Item -ItemType Directory -Force -Path $ServerDir | Out-Null
    Set-Content -LiteralPath (Join-Path $ServerDir "eula.txt") -Value "eula=true" -NoNewline
    Set-Content -LiteralPath (Join-Path $ServerDir "server.properties") -Value @"
online-mode=false
enforce-secure-profile=false
white-list=false
enforce-whitelist=false
difficulty=peaceful
level-name=world
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
max-players=2
view-distance=4
simulation-distance=4
sync-chunk-writes=false
motd=scb e2e
"@ -NoNewline

    Remove-Item -LiteralPath $ServerLog -Force -ErrorAction SilentlyContinue
    $srv = Start-Process -FilePath $ServerJava -ArgumentList @("-Xmx1G", "-jar", $ServerJar, "nogui") `
        -WorkingDirectory $ServerDir -RedirectStandardOutput $ServerOut -RedirectStandardError $ErrLog `
        -PassThru -WindowStyle Hidden

    $serverUp = $false
    $deadline = (Get-Date).AddSeconds($StartTimeoutSec)
    while (-not $srv.HasExited -and (Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 4
        if (Test-Path $ServerLog) {
            if (Select-String -Path $ServerLog -Pattern 'Done \(' -Quiet -ErrorAction SilentlyContinue) { $serverUp = $true; break }
        }
    }
    Write-Report "server: $(if ($serverUp) { 'up' } else { 'FAILED' })"
    if (-not $serverUp) {
        if (Test-Path $ServerOut) { Get-Content $ServerOut -Tail 15 | ForEach-Object { Write-Report ("  " + $_) } }
        Write-Report "RESULT FAIL (server did not start)"
        exit 1
    }
}

# --- dev client ----------------------------------------------------------------
Remove-Item -LiteralPath $ClientLog -Force -ErrorAction SilentlyContinue
$clientStart = Get-Date
if ($ClientCmdFile) {
    $p = Start-Process -FilePath "cmd.exe" -ArgumentList @("/c", "`"$ClientCmdFile`"") `
        -WorkingDirectory $Root -RedirectStandardOutput $OutLog -RedirectStandardError $ErrLog -PassThru -WindowStyle Hidden
} else {
    $clientArgs = @("--no-daemon", "--no-configuration-cache", ":${Node}:runClient", "--console=plain")
    if ($UseQuickPlay) { $clientArgs += "-PscbQuickPlay=$WorldName" } else { $clientArgs += "-PscbServerJoin=localhost:$Port" }
    $p = Start-Process -FilePath (Join-Path $Root "gradlew.bat") `
        -ArgumentList $clientArgs -WorkingDirectory $Root `
        -RedirectStandardOutput $OutLog -RedirectStandardError $ErrLog -PassThru -WindowStyle Hidden
}

$joined = $false
$joinLog = if ($UseQuickPlay) { $ClientLog } else { $ServerLog }
$deadline = (Get-Date).AddSeconds($JoinTimeoutSec)
while (-not $p.HasExited -and (Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 4
    # A minimized window stalls world loading on the new render backends and
    # makes the load screens unable to progress; restore it without focusing.
    try {
        $w = Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like "*Minecraft*" -and $_.MainWindowHandle -ne 0 -and $_.StartTime -gt $clientStart } | Select-Object -First 1
        if ($w -and $w.MainWindowHandle -ne 0 -and [ScbWin32]::IsIconic($w.MainWindowHandle)) {
            [ScbWin32]::ShowWindow($w.MainWindowHandle, 9) | Out-Null   # SW_RESTORE
            Write-Report "window: restored from minimized"
        }
    } catch { }
    if (Test-Path $joinLog) {
        if (Select-String -Path $joinLog -Pattern "joined the game" -Quiet -ErrorAction SilentlyContinue) { $joined = $true; break }
    }
}
Write-Report "join: $(if ($joined) { 'ok' } else { 'TIMEOUT' })"

$screenshot = $false
if ($joined) {
    $game = Get-GameWindow -After $clientStart
    if (-not $game) { Write-Report "FAIL no Minecraft window found" }
    else {
        $hwnd = $game.MainWindowHandle
        if ([ScbWin32]::IsIconic($hwnd)) { [ScbWin32]::ShowWindow($hwnd, 9) | Out-Null; Start-Sleep -Seconds 2 }
        # Fill the work area without activating (keeps the user's foreground app).
        $work = New-Object ScbWin32+RECT
        [ScbWin32]::SystemParametersInfo(0x0030, 0, [ref]$work, 0) | Out-Null
        [ScbWin32]::SetWindowPos($hwnd, [IntPtr]::Zero, $work.Left, $work.Top, ($work.Right - $work.Left), ($work.Bottom - $work.Top), 0x0054) | Out-Null
        Start-Sleep -Seconds 3
        $rect = New-Object ScbWin32+RECT
        [ScbWin32]::GetClientRect($hwnd, [ref]$rect) | Out-Null
        $width = $rect.Right - $rect.Left
        $height = $rect.Bottom - $rect.Top
        Write-Report "window: $($game.MainWindowTitle) ${width}x${height}"

        # wait until actually in the world (title gets the world suffix)
        $inWorld = $false
        $titleDeadline = (Get-Date).AddSeconds(120)
        while ((Get-Date) -lt $titleDeadline) {
            $w = Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like "*Minecraft*" -and $_.MainWindowHandle -ne 0 -and $_.StartTime -gt $clientStart } | Select-Object -First 1
            if ($w -and $w.MainWindowTitle -match "Multiplayer|Singleplayer") { $inWorld = $true; break }
            Start-Sleep -Seconds 2
        }
        Write-Report "in-world: $inWorld"
        Start-Sleep -Seconds 3

        Send-ChatLine -Handle $hwnd -Text "$Prefix$Probe"
        Write-Report "sent: $Prefix$Probe"

        Send-ChatLine -Handle $hwnd -Text "/scb status"
        Write-Report "sent: /scb status"

        $echo = $false
        if ($SkipEcho) {
            Write-Report "echo: skipped (Twitch session not required)"
            $echo = $true
        } else {
            $firstDeadline = (Get-Date).AddSeconds(30)
            while ((Get-Date) -lt $firstDeadline) {
                Start-Sleep -Seconds 3
                if (Select-String -Path $ClientLog -Pattern "\[Twitch\].*$Probe" -Quiet -ErrorAction SilentlyContinue) { $echo = $true; break }
            }
            if (-not $echo) {
                # Input/Twitch hiccups happen (26.3 focus races); retry the probe once.
                Write-Report "echo: no reply after 30s, resending probe"
                Send-ChatLine -Handle $hwnd -Text "$Prefix$Probe"
            }
            $echoDeadline = (Get-Date).AddSeconds($EchoTimeoutSec)
            while (-not $echo -and (Get-Date) -lt $echoDeadline) {
                Start-Sleep -Seconds 3
                if (Select-String -Path $ClientLog -Pattern "\[Twitch\].*$Probe" -Quiet -ErrorAction SilentlyContinue) { $echo = $true; break }
            }
        }

        if ($UseQuickPlay) {
            # A leaked message shows up as a chat line ("> ..." on some versions,
            # "<Player> ..." on others); the Twitch echo is expected, ignore it.
            $serverGot = [bool](Select-String -Path $ClientLog -Pattern ([regex]::Escape($Probe)) -ErrorAction SilentlyContinue | Where-Object { $_.Line -notmatch "\[Twitch\]" } | Select-Object -First 1)
        } else {
            $serverGot = [bool](Select-String -Path $ServerLog -Pattern ([regex]::Escape($Probe)) -Quiet -ErrorAction SilentlyContinue)
        }
        $status = [bool](Select-String -Path $ClientLog -Pattern "Minecraft . Twitch: ON|Minecraft → Twitch: ON" -Quiet -ErrorAction SilentlyContinue)

        Write-Report "checks: intercepted=$( -not $serverGot ) platform-echo=$(if ($SkipEcho) { 'skipped' } else { $echo }) scb-status=$status"

        if (-not $NoScreen) {
            $shotBefore = @(Get-ChildItem (Join-Path $RunDir "screenshots") -Filter *.png -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1)
            $lastBefore = if ($shotBefore) { $shotBefore[0].LastWriteTime } else { [datetime]::MinValue }
            Send-KeyWithFakeFocus -Handle $hwnd -Vk 0x77 -SendKeysKey "{F8}"   # F8 opens the dashboard
            Start-Sleep -Seconds 3
            Send-KeyWithFakeFocus -Handle $hwnd -Vk 0x71 -SendKeysKey "{F2}"   # F2 screenshots
            Start-Sleep -Seconds 2
            $shots = @(Get-ChildItem (Join-Path $RunDir "screenshots") -Filter *.png -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -gt $lastBefore } | Sort-Object LastWriteTime -Descending)
            $dest = Join-Path $ShotDir "$Label.png"
            if ($shots.Count -gt 0) {
                Copy-Item -LiteralPath $shots[0].FullName -Destination $dest -Force
                $size = [math]::Round((Get-Item $dest).Length / 1KB)
                Write-Report "screen: F8 screenshot captured via F2 (${size} KB) -> $dest"
                $screenshot = $size -gt 30
            } elseif (Capture-Window -Handle $hwnd -Dest $dest) {
                # 26.3's input/render stack can ignore F2; capture the composed
                # window directly instead.
                $size = [math]::Round((Get-Item $dest).Length / 1KB)
                Write-Report "screen: captured via PrintWindow fallback (${size} KB) -> $dest"
                $screenshot = $size -gt 30
            } else {
                Write-Report "screen: FAIL no screenshot produced by F2 or PrintWindow"
            }
        }

        if ((-not $serverGot) -and $echo -and $status -and ($NoScreen -or $screenshot)) {
            Write-Report "RESULT PASS"
        } else {
            Write-Report "RESULT FAIL"
            Select-String -Path $ClientLog -Pattern "$Probe|Twitch|Stream Chat Bridge" -ErrorAction SilentlyContinue | Select-Object -Last 15 | ForEach-Object { Write-Report ("  " + $_.Line) }
        }
    }
} else {
    Write-Report "RESULT FAIL (never joined)"
    if (Test-Path $ClientLog) {
        Select-String -Path $ClientLog -Pattern "Exception|ERROR|FATAL|Mixin|Can't connect|Failed" -ErrorAction SilentlyContinue | Select-Object -First 15 | ForEach-Object { Write-Report ("  " + $_.Line) }
    }
}

if (-not $LeaveOpen) {
    if ($p -and -not $p.HasExited) { taskkill /PID $($p.Id) /T /F | Out-Null; Write-Report "client stopped" }
    if ($srv -and -not $srv.HasExited) { taskkill /PID $($srv.Id) /T /F | Out-Null; Write-Report "server stopped" }
} else {
    Write-Report "client and server left running"
}

[ScbWin32]::SetThreadExecutionState([uint32]2147483648) | Out-Null   # ES_CONTINUOUS (reset)
