#requires -Version 7.0
<#
    Static boundary audit for the permanent node list.

    Extracts every version threshold from Stonecutter directives in src/ and checks
    it against the permanent nodes in stonecutter.properties.toml:

      - MISSING boundary: a threshold T falls strictly inside a node's range
        (N_i < T < N_{i+1}); the N_i-built jar would run the wrong branch on some
        covered version. Example caught in practice: Forge's ClientRegistry package
        move at 1.18.
      - UNJUSTIFIED node: a permanent node (not the loader's floor/newest) has no
        threshold in (previous node, this node]; the node could be dropped.
      - FUTURE threshold: a threshold above the newest node - fine for now, flagged
        so the next version bump remembers to add a node.

    Only src/ directives are considered: build-script version checks are dev-run
    plumbing and do not change shipped jars.

    Usage: pwsh -File scripts/audit-boundaries.ps1
#>
param()

$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
Set-Location $Root

$loaders = @("fabric", "forge", "neoforge")

# Versions where a loader has no usable build (threshold lands on a gap; the next
# existing build becomes the boundary). Keep in sync with AGENTS section 6.
$knownLoaderGaps = @{
    forge    = @("1.21.2", "1.20.5")
    neoforge = @("1.20.5")
}

# Build-script thresholds that change the SHIPPED jar (not just dev plumbing).
# Source directives cannot express these, so they are special-cased here.
$jarAffectingThresholds = @{
    forge = @(
        @{ Version = "1.20.6"; Why = "Forge reobf switch (SRG -> official runtime names)" }
    )
}

# --- permanent nodes -----------------------------------------------------------
$nodeMap = @{ fabric = @(); forge = @(); neoforge = @() }
$currentLoader = $null
foreach ($line in Get-Content -LiteralPath (Join-Path $Root "stonecutter.properties.toml")) {
    if ($line -match '^\[(fabric|forge|neoforge)\.\"([^\"]+)\"\]') {
        $currentLoader = $Matches[1]
        $nodeMap[$currentLoader] = @($nodeMap[$currentLoader] + $Matches[2])
        continue
    }
    if ($line -match '^\[') { $currentLoader = $null }
}
foreach ($l in $loaders) { $nodeMap[$l] = @($nodeMap[$l] | Sort-Object { [version]$_ }) }

# --- thresholds ----------------------------------------------------------------
$thresholds = [System.Collections.Generic.List[object]]::new()
foreach ($file in (Get-ChildItem (Join-Path $Root "src") -Recurse -File -Filter *.java)) {
    $rel = $file.FullName.Substring($Root.Length + 1)
    $lines = Get-Content -LiteralPath $file.FullName

    $fileScope = @($loaders)
    foreach ($line in $lines) {
        if ($line -match '^//\?\s*if\s+(fabric|forge|neoforge)\s*\{\s*$') { $fileScope = @($Matches[1]); break }
    }

    $lineNo = 0
    foreach ($line in $lines) {
        $lineNo++
        $m = [regex]::Match($line, '//\?\s*\}?\s*(?:else\s+if|if)\s+(.+?)\s*\{\s*$')
        if (-not $m.Success) { continue }

        $condition = $m.Groups[1].Value.Trim()
        $atoms = @($condition -split '&&' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
        $loaderAtoms = @($atoms | Where-Object { $loaders -contains $_ })
        $scope = if ($loaderAtoms.Count -gt 0) { $loaderAtoms } else { $fileScope }

        foreach ($atom in $atoms) {
            if ($atom -match '^[<>]=?\s*(\d+(?:\.\d+)*)$') {
                foreach ($l in $scope) {
                    $thresholds.Add([pscustomobject]@{
                        Loader   = $l
                        Version  = $Matches[1]
                        Location = "$rel`:$lineNo"
                        Condition = $condition
                    })
                }
            }
        }
    }
}

# --- checks --------------------------------------------------------------------
function Compare-Version {
    param([string]$A, [string]$B)
    return ([version]$A).CompareTo([version]$B)
}

$failures = 0
foreach ($loader in $loaders) {
    $nodes = @($nodeMap[$loader])
    $floor = $nodes[0]
    $newest = $nodes[-1]
    $own = @($thresholds | Where-Object { $_.Loader -eq $loader })
    Write-Host ""
    Write-Host "== $loader =="
    Write-Host ("nodes: {0}" -f ($nodes -join ", "))

    # missing boundaries
    $missing = 0
    foreach ($t in ($own | Sort-Object { [version]$_.Version } | Group-Object Version | ForEach-Object { $_.Group[0] })) {
        if ((Compare-Version $t.Version $newest) -gt 0) {
            Write-Host ("  FUTURE  threshold {0} ({1}) - add a node when {0} ships" -f $t.Version, $t.Location)
            continue
        }
        if ((Compare-Version $t.Version $floor) -le 0) { continue }
        if ($knownLoaderGaps.ContainsKey($loader) -and ($knownLoaderGaps[$loader] -contains $t.Version)) {
            Write-Host ("  GAP     threshold {0} ({1}) - loader has no build at {0}" -f $t.Version, $t.Location)
            continue
        }

        for ($i = 0; $i -lt $nodes.Count - 1; $i++) {
            if (((Compare-Version $nodes[$i] $t.Version) -lt 0) -and ((Compare-Version $t.Version $nodes[$i + 1]) -lt 0)) {
                Write-Host ("  MISSING boundary {0} between {1} and {2} ({3}: {4})" -f $t.Version, $nodes[$i], $nodes[$i + 1], $t.Location, $t.Condition)
                $missing++
                $failures++
                break
            }
        }
    }

    # jar-affecting build thresholds (e.g. the Forge reobf switch at 1.20.6)
    if ($jarAffectingThresholds.ContainsKey($loader)) {
        foreach ($jt in $jarAffectingThresholds[$loader]) {
            $tVer = $jt.Version
            if ((Compare-Version $tVer $newest) -gt 0) {
                Write-Host ("  FUTURE  jar threshold {0} ({1}) - add a node when it ships" -f $tVer, $jt.Why)
                continue
            }
            if ((Compare-Version $tVer $floor) -le 0) { continue }
            for ($i = 0; $i -lt $nodes.Count - 1; $i++) {
                if (((Compare-Version $nodes[$i] $tVer) -lt 0) -and ((Compare-Version $tVer $nodes[$i + 1]) -lt 0)) {
                    Write-Host ("  MISSING boundary {0} between {1} and {2} (jar threshold: {3})" -f $tVer, $nodes[$i], $nodes[$i + 1], $jt.Why)
                    $missing++
                    $failures++
                    break
                }
            }
        }
    }
    if ($missing -eq 0) { Write-Host "  missing boundaries: none" }

    # unjustified nodes
    for ($i = 1; $i -lt $nodes.Count - 1; $i++) {
        $node = $nodes[$i]
        $prev = $nodes[$i - 1]
        $justified = $null
        foreach ($t in $own) {
            if (((Compare-Version $prev $t.Version) -lt 0) -and ((Compare-Version $t.Version $node) -le 0)) { $justified = $t; break }
        }
        if (-not $justified -and $jarAffectingThresholds.ContainsKey($loader)) {
            foreach ($jt in $jarAffectingThresholds[$loader]) {
                if (((Compare-Version $prev $jt.Version) -lt 0) -and ((Compare-Version $jt.Version $node) -le 0)) { $justified = $jt; break }
            }
        }
        if ($justified) {
            Write-Host ("  justified {0,-8} <- {1} {2} ({3})" -f $node, $justified.Version, $(if ($justified.Version -ne $node) { "(first node at/after threshold)" } else { "" }), $(if ($justified.PSObject.Properties["Location"]) { $justified.Location } else { $justified.Why }))
        } else {
            Write-Host ("  UNJUSTIFIED node {0}: no threshold in ({1}, {0}]" -f $node, $prev)
            $failures++
        }
    }
    Write-Host ("  floor/newest kept as support bounds: {0} / {1}" -f $floor, $newest)
}

Write-Host ""
if ($failures -gt 0) {
    Write-Host "AUDIT FAIL: $failures issue(s)"
    exit 1
} else {
    Write-Host "AUDIT PASS: every permanent node justified, no missing boundaries"
}
