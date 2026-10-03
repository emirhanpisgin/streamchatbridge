#requires -Version 7.0
<#
    Node queue: compiles every declared node (pre-warming decompiles) and can
    optionally launch each node's dev client to smoke-test it.

    Phases per node:
      1. compileJava          - finds API/era breaks, no game window.
      2. runClient (-Launch)  - starts the client, waits until the mod client entry
                                point ran and the title screen is up, then kills it.
                                This proves the loader metadata/mixins/classloading
                                work; it does not join a world.

    Run unattached via scripts/launch-mavenize-queue.cmd and let it work in the
    background. Runs are strictly sequential (Gradle project locks).

    Examples:
        pwsh -File scripts/mavenize-queue.ps1 -ListOnly
        pwsh -File scripts/mavenize-queue.ps1 -Loaders neoforge
        pwsh -File scripts/mavenize-queue.ps1 -Launch -From 1.20.4 -To 26.1
        pwsh -File scripts/mavenize-queue.ps1 -Nodes 26.2-fabric,26.1-forge -Launch
#>
param(
    [string[]]$Nodes = @(),
    [ValidateSet("fabric", "forge", "neoforge")][string[]]$Loaders = @(),
    [string]$From = "",
    [string]$To = "",
    [string[]]$Exclude = @(),
    [string]$BuildJdk = "",
    [switch]$NoRetry,
    [switch]$SkipMinecraftPause,
    [switch]$ListOnly,
    [switch]$Launch,
    [int]$TimeoutMinutes = 60,
    [int]$LaunchTimeoutMinutes = 6
)

$ErrorActionPreference = "Continue"
$Root = Split-Path $PSScriptRoot -Parent
Set-Location $Root
$SettingsPath = Join-Path $Root "settings.gradle.kts"

# Allow comma-separated values in the list parameters.
$Nodes = @($Nodes | ForEach-Object { $_ -split "," } | Where-Object { $_ -ne "" })
$Loaders = @($Loaders | ForEach-Object { $_ -split "," } | Where-Object { $_ -ne "" })
$Exclude = @($Exclude | ForEach-Object { $_ -split "," } | Where-Object { $_ -ne "" })
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

function Compare-McVersion {
    param([string]$Left, [string]$Right)
    $a = @($Left -split '\.' | ForEach-Object { [int]($_ -replace '[^0-9].*$', '') })
    $b = @($Right -split '\.' | ForEach-Object { [int]($_ -replace '[^0-9].*$', '') })
    for ($i = 0; $i -lt [Math]::Max($a.Count, $b.Count); $i++) {
        $x = if ($i -lt $a.Count) { $a[$i] } else { 0 }
        $y = if ($i -lt $b.Count) { $b[$i] } else { 0 }
        if ($x -ne $y) { return $x - $y }
    }
    return 0
}

function Get-FirstFailure {
    param([string]$File)
    if (-not (Test-Path $File)) { return "" }
    $hit = Select-String -Path $File -Pattern "error:|Caused by:|FAILED TO BIND|Exception" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($hit) { return $hit.Line.Trim() }
    return ""
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

function Invoke-NodeLaunch {
    param([string]$Node, [string]$Loader, [string]$NodeLog, [int]$TimeoutMinutes)
    $gameLog = if ($Loader -eq "neoforge") { Join-Path $Root "versions\$Node\run\logs\latest.log" } else { Join-Path $Root "run\logs\latest.log" }
    Remove-Item -LiteralPath $gameLog -Force -ErrorAction SilentlyContinue

    $gradleArgs = @("--no-daemon", "--no-configuration-cache", ":${Node}:runClient", "--console=plain")
    $p = Start-Process -FilePath (Join-Path $Root "gradlew.bat") -ArgumentList $gradleArgs -WorkingDirectory $Root `
        -RedirectStandardOutput $NodeLog -RedirectStandardError "$NodeLog.err" -PassThru -WindowStyle Hidden

    $modLoaded = $false
    $titleScreen = $false
    $deadline = (Get-Date).AddMinutes($TimeoutMinutes)
    while (-not $p.HasExited -and (Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 4
        if (-not $modLoaded -and (Test-Path $NodeLog)) {
            if (Select-String -Path $NodeLog -Pattern "\[Stream Chat Bridge\] Loaded" -Quiet -ErrorAction SilentlyContinue) {
                $modLoaded = $true
                Write-Log "    mod client initialized" "DIM"
            }
        }
        if (-not $titleScreen -and (Test-Path $gameLog)) {
            if (Select-String -Path $gameLog -Pattern "Sound engine started|OpenAL initialized" -Quiet -ErrorAction SilentlyContinue) {
                $titleScreen = $true
            }
        }
        if ($modLoaded -and $titleScreen) { break }
    }

    $timedOut = ((Get-Date) -ge $deadline)
    if (-not $p.HasExited) { & taskkill /PID $p.Id /T /F 2>$null | Out-Null }
    Start-Sleep -Seconds 2

    if ($timedOut) {
        Write-Log "    launch timed out after $TimeoutMinutes minutes" "ERROR"
        return $false
    }
    return ($modLoaded -and $titleScreen)
}

# ------------------------------------------------------------------- main ----
$allNodes = @(Get-DeclaredNodes)
$queue = @($allNodes | Where-Object {
    ($Nodes.Count -eq 0 -or $Nodes -contains $_.Node) -and
    ($Loaders.Count -eq 0 -or $Loaders -contains $_.Loader) -and
    ($From -eq "" -or (Compare-McVersion $_.Mc $From) -ge 0) -and
    ($To -eq "" -or (Compare-McVersion $_.Mc $To) -le 0) -and
    ($Exclude -notcontains $_.Node)
})

Write-Host ""
Write-Host ("  " + ("=" * 74)) -ForegroundColor DarkMagenta
Write-Host "   Node queue - compile (and optionally launch) every declared node" -ForegroundColor Magenta
Write-Host ("  " + ("=" * 74)) -ForegroundColor DarkMagenta
Write-Log "build JDK: $BuildJdk"
Write-Log "mode: $(if ($Launch) { 'compile + launch' } else { 'compile' })"
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

    $launchOk = $null
    $errorLine = ""
    if ($ok -and $Launch) {
        $launchLog = Join-Path $Root "build\mavenize-$($entry.Node)-launch.log"
        Write-Log "    launching client..." "DIM"
        $launchOk = Invoke-NodeLaunch -Node $entry.Node -Loader $entry.Loader -NodeLog $launchLog -TimeoutMinutes $LaunchTimeoutMinutes
        if (-not $launchOk) { $errorLine = Get-FirstFailure "$launchLog.err" }
    } elseif (-not $ok) {
        $errorLine = Get-FirstFailure $nodeLog
    }

    $sw.Stop()
    if ($ok -and ($launchOk -ne $false)) {
        Write-Log ("done in {0:hh\:mm\:ss}" -f $sw.Elapsed) "OK"
    } else {
        Write-Log ("FAILED in {0:hh\:mm\:ss} - see build\mavenize-$($entry.Node)*.log" -f $sw.Elapsed) "ERROR"
    }
    $results += [pscustomobject]@{ Node = $entry.Node; Compile = $ok; Launch = $launchOk; Error = $errorLine; Elapsed = $sw.Elapsed }
}

$total.Stop()
Write-Host ""
Write-Host ("  " + ("=" * 74)) -ForegroundColor DarkMagenta
$okCount = @($results | Where-Object { $_.Compile -and ($_.Launch -ne $false) }).Count
Write-Log ("queue complete: {0} ok, {1} failed, total {2:hh\:mm\:ss}" -f $okCount, ($results.Count - $okCount), $total.Elapsed) $(if ($okCount -eq $results.Count) { "OK" } else { "ERROR" })
foreach ($r in $results) {
    $verdict = if (-not $r.Compile) { "compile-fail" } elseif ($r.Launch -eq $false) { "launch-fail" } elseif ($r.Launch -eq $true) { "ok (built+launched)" } else { "ok (built)" }
    Write-Log ("  {0,-18} {1}" -f $r.Node, $verdict) $(if ($verdict -like "ok*") { "OK" } else { "ERROR" })
    if ($r.Error) { Write-Log ("      " + $r.Error) "DIM" }
}
Write-Log "full log: $LogPath" "DIM"
