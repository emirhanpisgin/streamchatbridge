#requires -Version 7.0
<#
    Legacy (pre-1.20) end-to-end client test for Fabric nodes that predate quick-play.

    Starts a vanilla server matching the node's Minecraft version (JDK 17), launches
    the Fabric dev client with `-PscbServerJoin=localhost:<port>`, waits for the join,
    then injects chat input with PostMessage (no window focus needed) and checks:
      1. the probe message never reaches the server (chat interception);
      2. the probe comes back through the platform echo (outgoing send worked);
      3. `/scb status` output is in the client log.

    Examples:
        pwsh -File scripts/e2e-legacy.ps1 -Node 1.19-fabric
        pwsh -File scripts/e2e-legacy.ps1 -Node 1.19.1-fabric -Focus
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Node,

    [string]$ServerJar = "",
    [int]$Port = 25565,
    [string]$ServerJdk = "C:\Program Files\Java\jdk-17",
    [string]$BuildJdk = "",
    [string]$Prefix = "",
    [int]$StartTimeoutSec = 300,
    [int]$JoinTimeoutSec = 300,
    [int]$EchoTimeoutSec = 45,
    [switch]$Focus,
    [switch]$LeaveOpen
)

$ErrorActionPreference = "Continue"
$Root = Split-Path $PSScriptRoot -Parent
Set-Location $Root

$Mc = $Node.Split('-')[0]
if (-not $BuildJdk) {
    $preferred = "C:\Program Files\Java\jdk-25.0.2"
    if (Test-Path $preferred) { $BuildJdk = $preferred }
    elseif ($env:JAVA_HOME) { $BuildJdk = $env:JAVA_HOME }
    else { throw "No JDK found: pass -BuildJdk or set JAVA_HOME." }
}
$env:JAVA_HOME = $BuildJdk

$RunDir = Join-Path $Root "run"
$ClientLog = Join-Path $RunDir "logs\latest.log"
$ServerDir = Join-Path $Root "build\e2e\server-$Mc"
$ServerLog = Join-Path $ServerDir "logs\latest.log"
$OutLog = Join-Path $Root "build\e2e-$Node.out.log"
$ErrLog = Join-Path $Root "build\e2e-$Node.err.log"
$ServerOut = Join-Path $Root "build\e2e\server-$Mc.out.log"
$ReportPath = Join-Path $Root "build\e2e\$Node.log"
New-Item -ItemType Directory -Force -Path (Split-Path $ReportPath -Parent) | Out-Null

if (-not (Test-Path $ServerJar)) {
    $ServerJar = Join-Path $Root "build\.cache\server-$Mc.jar"
    if (-not (Test-Path $ServerJar)) {
        Write-Host "downloading vanilla server $Mc..."
        $manifest = Invoke-RestMethod "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json" -TimeoutSec 30
        $entry = $manifest.versions | Where-Object { $_.id -eq $Mc } | Select-Object -First 1
        $json = Invoke-RestMethod $entry.url -TimeoutSec 30
        Invoke-WebRequest $json.downloads.server.url -OutFile $ServerJar -TimeoutSec 900
    }
}

if (-not (Test-Path (Join-Path $ServerJdk "bin\java.exe"))) { throw "Server JDK not found at $ServerJdk" }
$ServerJava = Join-Path $ServerJdk "bin\java.exe"

if (-not $Prefix) {
    $cfgPath = Join-Path $RunDir "config\streamchatbridge.json"
    $Prefix = "!t "
    if (Test-Path $cfgPath) {
        try { $Prefix = (Get-Content $cfgPath -Raw | ConvertFrom-Json).twitchOutgoingPrefix } catch { }
    }
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
}
"@
Add-Type -AssemblyName System.Windows.Forms

function Get-GameWindow {
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $found = Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like "*Minecraft*" } | Select-Object -First 1
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

function Send-ChatLine {
    param([IntPtr]$Handle, [string]$Text)
    if ($Focus) {
        Force-Foreground -Handle $Handle | Out-Null
        [System.Windows.Forms.SendKeys]::SendWait("t")
        Start-Sleep -Milliseconds 700
        foreach ($ch in $Text.ToCharArray()) {
            if ("+^%~(){}[]".Contains($ch)) { [System.Windows.Forms.SendKeys]::SendWait("{" + $ch + "}") }
            else { [System.Windows.Forms.SendKeys]::SendWait([string]$ch) }
            Start-Sleep -Milliseconds 25
        }
        Start-Sleep -Milliseconds 250
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

Write-Report "=== e2e-legacy $Node (mc=$Mc, port=$Port, prefix='$Prefix', probe=$Probe, input=$(if ($Focus) { 'sendkeys' } else { 'postmessage' })) ==="

# --- vanilla server -----------------------------------------------------------
New-Item -ItemType Directory -Force -Path $ServerDir | Out-Null
Set-Content -LiteralPath (Join-Path $ServerDir "eula.txt") -Value "eula=true" -NoNewline
Set-Content -LiteralPath (Join-Path $ServerDir "server.properties") -Value @"
online-mode=false
enforce-secure-profile=false
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
    if ($srv.HasExited) { break }
}
Write-Report "server: $(if ($serverUp) { 'up' } else { 'FAILED' })"
if (-not $serverUp) {
    if (Test-Path $ServerOut) { Get-Content $ServerOut -Tail 15 | ForEach-Object { Write-Report ("  " + $_) } }
    Write-Report "RESULT FAIL (server did not start)"
    exit 1
}

# --- dev client ---------------------------------------------------------------
Remove-Item -LiteralPath $ClientLog -Force -ErrorAction SilentlyContinue
$p = Start-Process -FilePath (Join-Path $Root "gradlew.bat") `
    -ArgumentList @("--no-daemon", "--no-configuration-cache", ":${Node}:runClient", "-PscbServerJoin=localhost:$Port", "--console=plain") `
    -WorkingDirectory $Root -RedirectStandardOutput $OutLog -RedirectStandardError $ErrLog -PassThru -WindowStyle Hidden

$joined = $false
$deadline = (Get-Date).AddSeconds($JoinTimeoutSec)
while (-not $p.HasExited -and (Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 4
    if (Test-Path $ServerLog) {
        if (Select-String -Path $ServerLog -Pattern "joined the game" -Quiet -ErrorAction SilentlyContinue) { $joined = $true; break }
    }
}
Write-Report "join: $(if ($joined) { 'ok' } else { 'TIMEOUT' })"

if ($joined) {
    # The server logs the join before the client finishes "Downloading terrain";
    # wait until the window title carries the world suffix before injecting keys.
    $inWorld = $false
    $titleDeadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $titleDeadline) {
        $w = Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like "*Minecraft*" } | Select-Object -First 1
        if ($w -and $w.MainWindowTitle -match "Multiplayer|Singleplayer") { $inWorld = $true; break }
        Start-Sleep -Seconds 2
    }
    Write-Report "in-world: $inWorld"
    Start-Sleep -Seconds 3

    $game = Get-GameWindow
    if (-not $game) { Write-Report "FAIL no Minecraft window found" }
    else {
        Write-Report "window: $($game.MainWindowTitle)"

        Send-ChatLine -Handle $game.MainWindowHandle -Text "$Prefix$Probe"
        Write-Report "sent: $Prefix$Probe"

        Send-ChatLine -Handle $game.MainWindowHandle -Text "/scb status"
        Write-Report "sent: /scb status"

        $echoDeadline = (Get-Date).AddSeconds($EchoTimeoutSec)
        $echo = $false
        while ((Get-Date) -lt $echoDeadline) {
            Start-Sleep -Seconds 3
            if (Select-String -Path $ClientLog -Pattern "\[Twitch\].*$Probe" -Quiet -ErrorAction SilentlyContinue) { $echo = $true; break }
        }

        $serverGot = [bool](Select-String -Path $ServerLog -Pattern ([regex]::Escape($Probe)) -Quiet -ErrorAction SilentlyContinue)
        $status = [bool](Select-String -Path $ClientLog -Pattern "Minecraft . Twitch: ON|Minecraft → Twitch: ON" -Quiet -ErrorAction SilentlyContinue)

        Write-Report "checks: intercepted=$( -not $serverGot ) platform-echo=$echo scb-status=$status"
        if ((-not $serverGot) -and $echo -and $status) {
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
