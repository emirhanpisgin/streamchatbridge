#requires -Version 7.0
<#
    Removes a Minecraft version node: its `match(...)` line and properties section
    (including traversal markers), plus the generated versions/<node> directory.

    After dropping a node, close the previous node's range by hand (AGENTS.md
    section 10) and update scripts/publish-modrinth.ps1 game lists if needed.

    Examples:
        pwsh -File scripts/drop-node.ps1 -Node 1.18-fabric
        pwsh -File scripts/drop-node.ps1 -Node 1.20.1-forge -DryRun
#>
param(
    [Parameter(Mandatory = $true)][string]$Node,
    [switch]$KeepDir,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
Set-Location $Root
$SettingsPath = Join-Path $Root "settings.gradle.kts"
$PropertiesPath = Join-Path $Root "stonecutter.properties.toml"

if ($Node -notmatch "^(.+)-(fabric|forge|neoforge)$") { throw "Node must look like <mc>-<loader>, e.g. 1.18-fabric" }
$Mc = $Matches[1]
$Loader = $Matches[2]

function Read-Lines([string]$Path) { @(Get-Content -LiteralPath $Path) }
function Newline([string]$Path) { if ((Get-Content -LiteralPath $Path -Raw) -match "`r`n") { "`r`n" } else { "`n" } }

$settingsLines = Read-Lines $SettingsPath
$settingsNewline = Newline $SettingsPath
$propsLines = Read-Lines $PropertiesPath
$propsNewline = Newline $PropertiesPath

if (-not ($settingsLines -match "match\(""$([regex]::Escape($Mc))"",\s*""$Loader""\)")) {
    throw "Node $Node is not declared in settings.gradle.kts."
}

# ------------------------------------------------------------------- settings --
$out = New-Object System.Collections.Generic.List[string]
$skipping = $false
$removedMatch = $false
foreach ($line in $settingsLines) {
    if ($line -match "TRAVERSAL-BEGIN\s+$([regex]::Escape($Node))\s*$") { $skipping = $true; continue }
    if ($skipping) {
        if ($line -match "match\(""$([regex]::Escape($Mc))"",\s*""$Loader""\)") { $removedMatch = $true }
        if ($line -match "TRAVERSAL-END\s+$([regex]::Escape($Node))\s*$") { $skipping = $false }
        continue
    }
    if ($line -match "match\(""$([regex]::Escape($Mc))"",\s*""$Loader""\)") { $removedMatch = $true; continue }
    $out.Add($line)
}
$newSettings = ($out -join $settingsNewline)
if (-not $newSettings.EndsWith($settingsNewline)) { $newSettings += $settingsNewline }

# ---------------------------------------------------------------- properties --
$outProps = New-Object System.Collections.Generic.List[string]
$skipMarker = $false
for ($i = 0; $i -lt $propsLines.Count; $i++) {
    $line = $propsLines[$i]
    if ($line -match "TRAVERSAL-BEGIN\s+$([regex]::Escape($Node))\s*$") { $skipMarker = $true; continue }
    if ($skipMarker) {
        if ($line -match "TRAVERSAL-END\s+$([regex]::Escape($Node))\s*$") { $skipMarker = $false }
        continue
    }
    if ($line -match "^\[$Loader\.`"$([regex]::Escape($Mc))`"\]") {
        # No markers: drop the section body up to the next section/marker.
        while ($i + 1 -lt $propsLines.Count -and $propsLines[$i + 1] -notmatch "^\[" -and $propsLines[$i + 1] -notmatch "^# TRAVERSAL-") { $i++ }
        continue
    }
    $outProps.Add($line)
}

# Drop the ["<mc>"] version section if no loader uses that version anymore.
$remaining = $outProps -join $propsNewline
$stillUsed = ($remaining -match "\[(fabric|forge|neoforge)\.`"$([regex]::Escape($Mc))`"\]") -or ($newSettings -match "match\(""$([regex]::Escape($Mc))"",")
if (-not $stillUsed -and $remaining -match "(?m)^\[`"$([regex]::Escape($Mc))`"\]") {
    $outProps2 = New-Object System.Collections.Generic.List[string]
    $skipVersion = $false
    foreach ($line in $outProps) {
        if ($line -match "^\[`"$([regex]::Escape($Mc))`"\]") { $skipVersion = $true; continue }
        if ($skipVersion) {
            if ($line -match "^\[" -or $line -match "^# TRAVERSAL-") { $skipVersion = $false }
            else { continue }
        }
        $outProps2.Add($line)
    }
    $outProps = $outProps2
}
$newProps = (($outProps -join $propsNewline).TrimEnd()) + $propsNewline

# ---------------------------------------------------------------------- dirs --
$nodeDir = Join-Path $Root "versions\$Node"
$dirExists = Test-Path $nodeDir

Write-Host ""
Write-Host "  dropping: $Node"
Write-Host "  settings match removed: $removedMatch"
Write-Host "  versions/$Node exists:  $dirExists"
if ($DryRun) {
    Write-Host "  [DRY-RUN] no files changed"
    return
}

Set-Content -LiteralPath $SettingsPath -Value $newSettings -NoNewline
Set-Content -LiteralPath $PropertiesPath -Value $newProps -NoNewline
if ($dirExists -and -not $KeepDir) { Remove-Item $nodeDir -Recurse -Force }

Write-Host "  removed $Node" -ForegroundColor Green
Write-Host "  remember: close the previous node's mod.mc_compat range (AGENTS.md section 10) and sync publish-modrinth.ps1 game lists" -ForegroundColor Yellow
