#requires -Version 7.0
<#
    Mavenize queue: pre-decompiles the Minecraft artifacts for every declared node
    by running `:<node>:compileJava` sequentially.

    The first build of a node takes 1-15 minutes (Loom / ForgeGradle / NeoFormRuntime
    decompile); afterwards the caches make builds fast. Run this unattached via
    scripts/launch-mavenize-queue.cmd and let it work in the background.

    Examples:
        pwsh -File scripts/mavenize-queue.ps1 -ListOnly
        pwsh -File scripts/mavenize-queue.ps1
        pwsh -File scripts/mavenize-queue.ps1 -Loaders neoforge -TimeoutMinutes 45
#>
param(
    [string[]]$Nodes = @(),
    [ValidateSet("fabric", "forge", "neoforge")][string[]]$Loaders = @(),
    [string]$BuildJdk = "",
    [switch]$NoRetry,
    [switch]$SkipMinecraftPause,
    [switch]$ListOnly,
    [int]$TimeoutMinutes = 60
)

$ErrorActionPreference = "Continue"
$Root = Split-Path $PSScriptRoot -Parent
Set-Location $Root
$SettingsPath = Join-Path $Root "settings.gradle.kts"
$LogPath = Join-Path $Root "build\mavenize-queue.log"
New-Item -ItemType Directory -Force -Path (Join-Path $Root "build") | Out-Null

if (-not $BuildJdk) {
    $preferred = "C:\Program Files\Java\jdk-25.0.2"
    if (Test-Path $preferred) { $BuildJdk = $preferred }
    elseif ($env:JAVA_HOME) { $BuildJdk = $env:JAVA_HOME }
    else { throw "No JDK found: pass -BuildJdk or set JAVA_HOME." }
}

function Write-Log {
    param([string]$Message, [string]$Level = "INFO")
    $color = switch ($Level) {
        "OK" { "Green" } "WARN" { "Yellow" } "ERROR" { "Red" }
        "STEP" { "Magenta" } "DIM" { "DarkGray" } default { "Cyan" }
    }
    $tag = switch ($Level) {
        "OK" { "[ OK ]" } "WARN" { "[WARN]" } "ERROR" { "[FAIL]" }
        "STEP" { "[STEP]" } "DIM" { "[ -- ]" } default { "[INFO]" }
    }
    Write-Host ("  {0} {1} {2}" -f (Get-Date -Format "HH:mm:ss"), $tag, $Message) -ForegroundColor $color
    Add-Content -LiteralPath $LogPath -Value ("{0} {1} {2}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $tag, $Message) -ErrorAction SilentlyContinue
}

function Get-DeclaredNodes {
    $result = @()
    foreach ($line in Get-Content -LiteralPath $SettingsPath) {
        $m = [regex]::Match($line, 'match\("([^"]+)",\s*"([^"]+)"')
        if ($m.Success) {
            $result += [pscustomobject]@{
                Mc     = $m.Groups[1].Value
                Loader = $m.Groups[2].Value
                Node   = "$($m.Groups[1].Value)-$($m.Groups[2].Value)"
            }
        }
    }
    return $result
}

function Wait-ForMinecraftWindow {
    if ($SkipMinecraftPause) { return }
    $announced = $false
    while (Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like "Minecraft*" }) {
        if (-not $announced) {
            Write-Log "A Minecraft window is open - pausing until it closes (use -SkipMinecraftPause to override)" "WARN"
            $announced = $true
        }
        Start-Sleep -Seconds 15
    }
    if ($announced) { Write-Log "Minecraft window closed - resuming" "OK" }
}

$MarkerPattern = 'decompile|Decompil|Minecraft Maven|Downloading library|Compiling \d+ source|Total runtime|createMinecraftArtifacts|configureClient|BUILD SUCCESSFUL|BUILD FAILED|error:'

function Invoke-NodeBuild {
    param([string]$Node, [string]$NodeLog, [int]$TimeoutMinutes)
    $gradleArgs = @("--no-daemon", "--no-configuration-cache", ":${Node}:compileJava", "--console=plain")
    $p = Start-Process -FilePath (Join-Path $Root "gradlew.bat") -ArgumentList $gradleArgs -WorkingDirectory $Root `
        -RedirectStandardOutput $NodeLog -RedirectStandardError "$NodeLog.err" -PassThru -WindowStyle Hidden
    $read = 0
    $deadline = (Get-Date).AddMinutes($TimeoutMinutes)
    while (-not $p.HasExited) {
        Start-Sleep -Seconds 3
        if (Test-Path $NodeLog) {
            $lines = @(Get-Content -LiteralPath $NodeLog -ErrorAction SilentlyContinue)
            if ($lines.Count -gt $read) {
                for ($i = $read; $i -lt $lines.Count; $i++) {
                    if ($lines[$i] -match $MarkerPattern) {
                        Write-Log ("    " + $lines[$i].Trim().Substring(0, [Math]::Min(150, $lines[$i].Trim().Length))) "DIM"
                    }
                }
                $read = $lines.Count
            }
        }
        if ((Get-Date) -gt $deadline) {
            Write-Log "Timed out after $TimeoutMinutes minutes - killing build" "ERROR"
            & taskkill /PID $p.Id /T /F 2>$null | Out-Null
            return $false
        }
    }
    return ($p.ExitCode -eq 0)
}

# ------------------------------------------------------------------- main ----
$allNodes = @(Get-DeclaredNodes)
$queue = @($allNodes | Where-Object {
    ($Nodes.Count -eq 0 -or $Nodes -contains $_.Node) -and
    ($Loaders.Count -eq 0 -or $Loaders -contains $_.Loader)
})

Write-Host ""
Write-Host ("  " + ("=" * 74)) -ForegroundColor DarkMagenta
Write-Host "   Mavenize queue - pre-decompile every declared node" -ForegroundColor Magenta
Write-Host ("  " + ("=" * 74)) -ForegroundColor DarkMagenta
Write-Log "build JDK: $BuildJdk"
Write-Log "nodes: $($queue.Count) of $($allNodes.Count) declared"

if ($ListOnly) {
    foreach ($n in $queue) { Write-Log ("  " + $n.Node) "STEP" }
    return
}
if ($queue.Count -eq 0) { Write-Log "Nothing to do." "WARN"; return }

$results = @()
$total = [System.Diagnostics.Stopwatch]::StartNew()
$index = 0

foreach ($entry in $queue) {
    $index++
    Wait-ForMinecraftWindow
    Write-Log ("[{0}/{1}] {2}" -f $index, $queue.Count, $entry.Node) "STEP"
    $nodeLog = Join-Path $Root "build\mavenize-$($entry.Node).log"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $env:JAVA_HOME = $BuildJdk

    $ok = Invoke-NodeBuild -Node $entry.Node -NodeLog $nodeLog -TimeoutMinutes $TimeoutMinutes
    if (-not $ok -and -not $NoRetry) {
        Write-Log "build failed - cleaning node caches and retrying once" "WARN"
        Remove-Item (Join-Path $Root "versions\$($entry.Node)\build\tmp\neoformruntime") -Recurse -Force -ErrorAction SilentlyContinue
        Remove-Item (Join-Path $Root "versions\$($entry.Node)\build\tmp\createMinecraftArtifacts") -Recurse -Force -ErrorAction SilentlyContinue
        $ok = Invoke-NodeBuild -Node $entry.Node -NodeLog $nodeLog -TimeoutMinutes $TimeoutMinutes
    }

    $sw.Stop()
    if ($ok) {
        Write-Log ("done in {0:hh\:mm\:ss}" -f $sw.Elapsed) "OK"
    } else {
        Write-Log ("FAILED in {0:hh\:mm\:ss} - see build\mavenize-$($entry.Node).log" -f $sw.Elapsed) "ERROR"
    }
    $results += [pscustomobject]@{ Node = $entry.Node; Ok = $ok; Elapsed = $sw.Elapsed }
}

$total.Stop()
Write-Host ""
Write-Host ("  " + ("=" * 74)) -ForegroundColor DarkMagenta
$okCount = @($results | Where-Object { $_.Ok }).Count
Write-Log ("queue complete: {0} ok, {1} failed, total {2:hh\:mm\:ss}" -f $okCount, ($results.Count - $okCount), $total.Elapsed) $(if ($okCount -eq $results.Count) { "OK" } else { "ERROR" })
foreach ($r in $results) {
    Write-Log ("  {0,-18} {1}" -f $r.Node, $(if ($r.Ok) { "ok" } else { "FAILED" })) $(if ($r.Ok) { "OK" } else { "ERROR" })
}
Write-Log "full log: $LogPath" "DIM"
