#requires -Version 7.0
<#
    Runs the server-join/quick-play E2E + F8 screenshot test over every permanent
    node and prints a summary. Heavy: launches one Minecraft client per node
    (about 3-4 minutes each, ~2.5 h for all 36) - do not run while using the PC.

    Modes:
      - server join: a matching vanilla server + dev client (-ServerJoin); used
        everywhere except Forge 26.x, whose dev runtime crashes on multiplayer
        joins (Forge's own config guard).
      - quick-play: scratch copy of a world the node can load (Forge 26.x only).

    Examples:
        pwsh -File scripts/e2e-sweep.ps1
        pwsh -File scripts/e2e-sweep.ps1 -Nodes 26.3-fabric,1.17.1-forge
        pwsh -File scripts/e2e-sweep.ps1 -Loader forge
#>
param(
    [string[]]$Nodes = @(),
    [ValidateSet("", "fabric", "forge", "neoforge")]
    [string]$Loader = "",
    [int]$TimeoutMinutes = 15,
    [string]$LogDir = "build/e2e"
)

$ErrorActionPreference = "Continue"
$Root = Split-Path $PSScriptRoot -Parent
Set-Location $Root
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

$matrix = @(
    # fabric: all versions run a matching vanilla server (works on every era)
    @{ Node = "1.17.1-fabric";  Mode = "server" }
    @{ Node = "1.19-fabric";    Mode = "server" }
    @{ Node = "1.19.1-fabric";  Mode = "server" }
    @{ Node = "1.19.3-fabric";  Mode = "server" }
    @{ Node = "1.19.4-fabric";  Mode = "server" }
    @{ Node = "1.20-fabric";    Mode = "server" }
    @{ Node = "1.21-fabric";    Mode = "server" }
    @{ Node = "1.21.2-fabric";  Mode = "server" }
    @{ Node = "1.21.9-fabric";  Mode = "server" }
    @{ Node = "1.21.11-fabric"; Mode = "server" }
    @{ Node = "26.1-fabric";    Mode = "server" }
    @{ Node = "26.2-fabric";    Mode = "server" }
    @{ Node = "26.3-fabric";    Mode = "server" }

    # forge: server joins below 26.1; 26.x uses quick-play (dev multiplayer crash)
    @{ Node = "1.17.1-forge";   Mode = "server" }
    @{ Node = "1.18-forge";     Mode = "server" }
    @{ Node = "1.19-forge";     Mode = "server" }
    @{ Node = "1.19.3-forge";   Mode = "server" }
    @{ Node = "1.19.4-forge";   Mode = "server" }
    @{ Node = "1.20-forge";     Mode = "server" }
    @{ Node = "1.21-forge";     Mode = "server" }
    @{ Node = "1.21.3-forge";   Mode = "server" }
    @{ Node = "1.21.6-forge";   Mode = "server" }
    @{ Node = "1.21.9-forge";   Mode = "server" }
    @{ Node = "1.21.11-forge";  Mode = "server" }
    @{ Node = "26.1-forge";     Mode = "quick"; World = "run\saves\TestWorld26" }
    @{ Node = "26.2-forge";     Mode = "quick"; World = "run\saves\TestWorld" }
    @{ Node = "26.3-forge";     Mode = "quick"; World = "run\saves\TestWorld" }

    # neoforge: server joins are fine on all nodes
    @{ Node = "1.20.4-neoforge";  Mode = "server" }
    @{ Node = "1.20.6-neoforge";  Mode = "server" }
    @{ Node = "1.21-neoforge";    Mode = "server" }
    @{ Node = "1.21.2-neoforge";  Mode = "server" }
    @{ Node = "1.21.9-neoforge";  Mode = "server" }
    @{ Node = "1.21.11-neoforge"; Mode = "server" }
    @{ Node = "26.1-neoforge";    Mode = "server" }
    @{ Node = "26.2-neoforge";    Mode = "server" }
    @{ Node = "26.3-neoforge";    Mode = "server" }
)

$selected = @($matrix)
if ($Loader) { $selected = @($selected | Where-Object { $_.Node -like "*-$Loader" }) }
if ($Nodes.Count -gt 0) { $selected = @($selected | Where-Object { $Nodes -contains $_.Node }) }
if ($selected.Count -eq 0) { throw "No nodes selected" }

$results = [System.Collections.Generic.List[object]]::new()
$started = Get-Date
foreach ($entry in $selected) {
    $args = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "scripts/e2e-legacy.ps1", "-Node", $entry.Node)
    if ($entry.Mode -eq "server") { $args += "-ServerJoin" }
    if ($entry.ContainsKey("World")) { $args += @("-BaseWorld", $entry.World) }

    $out = Join-Path $LogDir "sweep-$($entry.Node).log"
    $err = Join-Path $LogDir "sweep-$($entry.Node).err"
    Write-Host ("[{0}] {1} ({2})..." -f (Get-Date -Format HH:mm:ss), $entry.Node, $entry.Mode)

    $p = Start-Process -FilePath "pwsh" -PassThru -WindowStyle Hidden -ArgumentList $args `
        -WorkingDirectory $Root -RedirectStandardOutput $out -RedirectStandardError $err
    $deadline = (Get-Date).AddMinutes($TimeoutMinutes)
    while (-not $p.HasExited -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 10 }
    if (-not $p.HasExited) { taskkill /PID $($p.Id) /T /F | Out-Null }

    $pass = [bool](Select-String -Path $out -Pattern "RESULT PASS" -Quiet -ErrorAction SilentlyContinue)
    $results.Add([pscustomobject]@{ Node = $entry.Node; Pass = $pass })
    Write-Host ("[{0}] {1}: {2}" -f (Get-Date -Format HH:mm:ss), $entry.Node, $(if ($pass) { "PASS" } else { "FAIL" }))
}

$passed = @($results | Where-Object { $_.Pass }).Count
$failed = @($results | Where-Object { -not $_.Pass })
Write-Host ""
Write-Host ("sweep complete: {0}/{1} pass, duration {2:hh\:mm\:ss}" -f $passed, $results.Count, ((Get-Date) - $started))
foreach ($f in $failed) { Write-Host ("  FAIL {0} (log: {1})" -f $f.Node, (Join-Path $LogDir "sweep-$($f.Node).log")) }
if ($failed.Count -gt 0) { exit 1 }
