#requires -Version 7.0
<#
    End-to-end client test for one loader node, background-friendly.

    Launches the dev client with quick-play into a prepared world, waits for the
    player to join, then injects chat input with PostMessage (no window focus
    needed) and checks:
      1. the probe message never reaches the server chat (chat interception);
      2. the probe comes back through the platform echo (outgoing send worked);
      3. `/scb status` output is in the log.

    The client process is killed at the end. Results are printed and appended to
    build/e2e/<node>.log.

    Examples:
        pwsh -File scripts/e2e-test.ps1 -Loader fabric
        pwsh -File scripts/e2e-test.ps1 -Loader neoforge -World TestWorld26 -Prefix "t! "
        pwsh -File scripts/e2e-test.ps1 -Loader forge -Focus      # fall back to SendKeys
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("fabric", "forge", "neoforge")]
    [string]$Loader,

    [string]$Node = "",
    [string]$World = "TestWorld",
    [string]$Prefix = "",
    [string]$ProbeText = "",
    [string]$BuildJdk = "",
    [int]$JoinTimeoutSec = 300,
    [int]$EchoTimeoutSec = 45,
    [switch]$Focus,
    [switch]$Minimize,
    [switch]$Attach,
    [switch]$LeaveOpen
)

$ErrorActionPreference = "Continue"
$Root = Split-Path $PSScriptRoot -Parent
Set-Location $Root

if (-not $Node) {
    $Node = switch ($Loader) {
        "fabric" { "26.2-fabric" }
        "forge" { "26.1-forge" }
        "neoforge" { "26.1-neoforge" }
    }
}
if (-not $BuildJdk) {
    $preferred = "C:\Program Files\Java\jdk-25.0.2"
    if (Test-Path $preferred) { $BuildJdk = $preferred }
    elseif ($env:JAVA_HOME) { $BuildJdk = $env:JAVA_HOME }
    else { throw "No JDK found: pass -BuildJdk or set JAVA_HOME." }
}
$env:JAVA_HOME = $BuildJdk

$RunDir = if ($Loader -eq "neoforge") { Join-Path $Root "versions\$Node\run" } else { Join-Path $Root "run" }
$LogPath = Join-Path $RunDir "logs\latest.log"
$OutLog = Join-Path $Root "build\e2e-$Node.out.log"
$ErrLog = Join-Path $Root "build\e2e-$Node.err.log"
$ReportPath = Join-Path $Root "build\e2e\$Node.log"
New-Item -ItemType Directory -Force -Path (Split-Path $ReportPath -Parent) | Out-Null

if (-not $Prefix) {
    $cfgPath = Join-Path $RunDir "config\streamchatbridge.json"
    $Prefix = "!t "
    if (Test-Path $cfgPath) {
        try { $Prefix = (Get-Content $cfgPath -Raw | ConvertFrom-Json).twitchOutgoingPrefix } catch { }
    }
}

$Stamp = Get-Date -Format "HHmmss"
$Probe = "e2e-$Stamp"
if ($ProbeText) { $Probe = $ProbeText }

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
    param([IntPtr]$Handle, [string]$Text, [switch]$Command)
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

    # Fake focus so GLFW forwards WM_CHAR, inject keys/chars, then release focus.
    [ScbWin32]::PostMessage($Handle, 0x0007, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null
    Start-Sleep -Milliseconds 300
    Post-Key -Handle $Handle -Vk 0x54          # VK_T -> chat
    Start-Sleep -Milliseconds 600
    foreach ($ch in $Text.ToCharArray()) {
        [ScbWin32]::PostMessage($Handle, 0x0102, [IntPtr]([int][char]$ch), [IntPtr]1) | Out-Null
        Start-Sleep -Milliseconds 20
    }
    Start-Sleep -Milliseconds 250
    Post-Key -Handle $Handle -Vk 0x0D          # VK_RETURN
    Start-Sleep -Milliseconds 700
    [ScbWin32]::PostMessage($Handle, 0x0008, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null
}

# Platform tokens live in the shared per-user secret dir
# (%APPDATA%\streamchatbridge on Windows), so every instance is authenticated
# with the same session and no per-run-dir syncing is needed.

Write-Report "=== e2e $Node (world=$World, prefix='$Prefix', probe=$Probe, input=$(if ($Focus) { 'sendkeys' } else { 'postmessage' })) ==="

$p = $null
$joined = $false
if ($Attach) {
    $joined = $true
    Write-Report "attach mode: using running client"
} else {
    Remove-Item -LiteralPath $LogPath -Force -ErrorAction SilentlyContinue

    $p = Start-Process -FilePath (Join-Path $Root "gradlew.bat") `
        -ArgumentList @("--no-daemon", "--no-configuration-cache", ":${Node}:runClient", "-PscbQuickPlay=$World", "--console=plain") `
        -WorkingDirectory $Root -RedirectStandardOutput $OutLog -RedirectStandardError $ErrLog -PassThru -WindowStyle Hidden

    $deadline = (Get-Date).AddSeconds($JoinTimeoutSec)
    while (-not $p.HasExited -and (Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 4
        if (Test-Path $LogPath) {
            if (Select-String -Path $LogPath -Pattern "joined the game" -Quiet -ErrorAction SilentlyContinue) { $joined = $true; break }
        }
    }
}
Write-Report "join: $(if ($joined) { 'ok' } else { 'TIMEOUT' })"

if ($joined) {
    Start-Sleep -Seconds 4
    $game = Get-GameWindow
    if (-not $game) { Write-Report "FAIL no Minecraft window found" }
    else {
        Write-Report "window: $($game.MainWindowTitle)"
        if ($Minimize) {
            [ScbWin32]::ShowWindow($game.MainWindowHandle, 6) | Out-Null   # SW_MINIMIZE
            Start-Sleep -Seconds 2
            Write-Report "window minimized"
        }

        Send-ChatLine -Handle $game.MainWindowHandle -Text "$Prefix$Probe"
        Write-Report "sent: $Prefix$Probe"

        Send-ChatLine -Handle $game.MainWindowHandle -Text "/scb status" -Command
        Write-Report "sent: /scb status"

        $echoDeadline = (Get-Date).AddSeconds($EchoTimeoutSec)
        $echo = $false
        while ((Get-Date) -lt $echoDeadline) {
            Start-Sleep -Seconds 3
            if (Select-String -Path $LogPath -Pattern "\[Twitch\].*$Probe" -Quiet -ErrorAction SilentlyContinue) { $echo = $true; break }
        }

        $serverEcho = [bool](Select-String -Path $LogPath -Pattern "> $([regex]::Escape($Prefix))$Probe" -Quiet -ErrorAction SilentlyContinue)
        $status = [bool](Select-String -Path $LogPath -Pattern "Minecraft . Twitch: ON|Minecraft → Twitch: ON" -Quiet -ErrorAction SilentlyContinue)

        Write-Report "checks: intercepted=$( -not $serverEcho ) platform-echo=$echo scb-status=$status"
        if ((-not $serverEcho) -and $echo -and $status) {
            Write-Report "RESULT PASS"
        } else {
            Write-Report "RESULT FAIL"
            Select-String -Path $LogPath -Pattern "$Probe|Twitch|Stream Chat Bridge" -ErrorAction SilentlyContinue | Select-Object -Last 15 | ForEach-Object { Write-Report ("  " + $_.Line) }
        }
    }
} else {
    Write-Report "RESULT FAIL (never joined)"
    if (Test-Path $LogPath) {
        Select-String -Path $LogPath -Pattern "Exception|ERROR|FATAL|Mixin" -ErrorAction SilentlyContinue | Select-Object -First 15 | ForEach-Object { Write-Report ("  " + $_.Line) }
    }
}

if ($Attach) {
    Write-Report "attach mode: client left running"
} elseif (-not $LeaveOpen -and $p -and -not $p.HasExited) {
    taskkill /PID $($p.Id) /T /F | Out-Null
    Write-Report "client stopped"
} else {
    Write-Report "client left open"
}
