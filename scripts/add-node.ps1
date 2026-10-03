#requires -Version 7.0
<#
    Adds a Minecraft version node for a loader: inserts the `match(...)` line into
    settings.gradle.kts (sorted) and appends the node's stonecutter.properties.toml
    section. Loader pins and the resource pack format are auto-fetched when possible
    (Forge promotions, NeoForge/Fabric maven metadata, Mojang version manifest), and
    can be overridden with parameters.

    Examples:
        pwsh -File scripts/add-node.ps1 -Mc 1.18 -Loader fabric
        pwsh -File scripts/add-node.ps1 -Mc 1.20.1 -Loader forge -PackFormat 15
        pwsh -File scripts/add-node.ps1 -Mc 1.21.4 -Loader neoforge -DryRun
#>
param(
    [Parameter(Mandatory = $true)][string]$Mc,
    [Parameter(Mandatory = $true)][ValidateSet("fabric", "forge", "neoforge")][string]$Loader,
    [string]$Compat = "",
    [string]$FabricApi = "",
    [string]$ForgeLoader = "",
    [string]$ForgeFml = "",
    [string]$NeoLoader = "",
    [int]$PackFormat = 0,
    [switch]$DryRun
)

$ErrorActionPreference = "Continue"
$Root = Split-Path $PSScriptRoot -Parent
Set-Location $Root
$SettingsPath = Join-Path $Root "settings.gradle.kts"
$PropertiesPath = Join-Path $Root "stonecutter.properties.toml"
$Node = "$Mc-$Loader"
$Warnings = @()

if (-not $Compat) { $Compat = ">=$Mc" }

function Get-Lines([string]$Path) { Get-Content -LiteralPath $Path }
function Get-Newline([string]$Raw) { if ($Raw -match "`r`n") { "`r`n" } else { "`n" } }

# --------------------------------------------------------------- auto-fetch --
function Fetch-PackFormat {
    param([string]$Version)
    try {
        $manifest = Invoke-RestMethod "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json" -TimeoutSec 30
        $entry = $manifest.versions | Where-Object { $_.id -eq $Version } | Select-Object -First 1
        if (-not $entry) { return 0 }
        $json = Invoke-RestMethod $entry.url -TimeoutSec 30
        $pv = $json.pack_version
        if ($pv) {
            if ($pv -is [int] -or $pv -is [long]) { return [int]$pv }
            if ($pv.resource) { return [int]$pv.resource }
            if ($pv.resource_major) { return [int]$pv.resource_major }
        }
        # Older version JSONs omit pack_version; it lives in the client jar's version.json.
        $cacheDir = Join-Path $Root "build\.cache"
        New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null
        $jarPath = Join-Path $cacheDir "client-$Version.jar"
        if (-not (Test-Path $jarPath)) {
            Invoke-WebRequest $json.downloads.client.url -OutFile $jarPath -TimeoutSec 600
        }
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
        try {
            $zipEntry = $zip.Entries | Where-Object { $_.FullName -eq "version.json" } | Select-Object -First 1
            if (-not $zipEntry) { return 0 }
            $reader = New-Object System.IO.StreamReader($zipEntry.Open())
            $inner = $reader.ReadToEnd() | ConvertFrom-Json
            $reader.Dispose()
            if ($inner.pack_version.resource) { return [int]$inner.pack_version.resource }
            if ($inner.pack_version.resource_major) { return [int]$inner.pack_version.resource_major }
            return 0
        } finally { $zip.Dispose() }
    } catch { return 0 }
}

function Fetch-ForgeLoader {
    param([string]$Version)
    try {
        $json = Invoke-RestMethod "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json" -TimeoutSec 30
        $promos = $json.promos
        foreach ($key in @("$Version-latest", "$Version-recommended")) {
            if ($promos.PSObject.Properties.Name -contains $key) { return [string]$promos.$key }
        }
        return ""
    } catch { return "" }
}

function Fetch-NeoLoader {
    param([string]$Version)
    try {
        $prefix = if ($Version -like "1.*") {
            $parts = $Version.Split(".")
            if ($parts.Count -ge 3) { "$($parts[1]).$($parts[2])" } else { "$($parts[1]).0" }
        } else { $Version }
        $xml = [xml](Invoke-WebRequest "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml" -UseBasicParsing -TimeoutSec 30).Content
        $versions = @($xml.metadata.versioning.versions.version) | Where-Object { $_ -match "^$([regex]::Escape($prefix))\." }
        if ($versions.Count -eq 0) { return "" }
        $release = $versions | Where-Object { $_ -notmatch "beta|alpha" } | Select-Object -First 1
        $pick = if ($release) { $versions | Where-Object { $_ -notmatch "beta|alpha" } | Sort-Object { [version](($_ -replace "-.*$", "")) } -Descending | Select-Object -First 1 } else { $versions | Sort-Object { [version](($_ -replace "-.*$", "")) } -Descending | Select-Object -First 1 }
        return [string]$pick
    } catch { return "" }
}

function Fetch-FabricApi {
    param([string]$Version)
    try {
        $xml = [xml](Invoke-WebRequest "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml" -UseBasicParsing -TimeoutSec 30).Content
        $all = @($xml.metadata.versioning.versions.version)
        $suffixes = @($Version)
        if ($Version -like "1.*") {
            $parts = $Version.Split(".")
            if ($parts.Count -ge 3) { $suffixes += "$($parts[0]).$($parts[1])" }
        }
        foreach ($suffix in $suffixes) {
            $matches = @($all | Where-Object { $_ -like "*+$suffix" })
            if ($matches.Count -gt 0) { return [string]$matches[-1] }
        }
        return ""
    } catch { return "" }
}

if ($Loader -eq "fabric") {
    if (-not $FabricApi) { $FabricApi = Fetch-FabricApi $Mc; if (-not $FabricApi) { $FabricApi = "TODO"; $Warnings += "Fabric API pin not found for $Mc - set deps.fabric_api manually" } }
} elseif ($Loader -eq "forge") {
    if (-not $ForgeLoader) { $ForgeLoader = Fetch-ForgeLoader $Mc; if (-not $ForgeLoader) { $ForgeLoader = "TODO"; $Warnings += "Forge loader pin not found for $Mc - set deps.forge_loader manually" } }
    if (-not $ForgeFml) {
        if ($ForgeLoader -match '^(\d+)') { $ForgeFml = $Matches[1] } else { $ForgeFml = "TODO"; $Warnings += "Forge FML major not derivable - set deps.forge_fml manually" }
    }
} else {
    if (-not $NeoLoader) { $NeoLoader = Fetch-NeoLoader $Mc; if (-not $NeoLoader) { $NeoLoader = "TODO"; $Warnings += "NeoForge pin not found for $Mc - set deps.neo_loader manually (needs a .module with neoforge-moddev-bundle)" } }
}
if ($PackFormat -le 0) { $PackFormat = Fetch-PackFormat $Mc; if ($PackFormat -le 0) { $Warnings += "pack_format not found for $Mc - set it manually from the client jar's version.json" } }

$commandApi = if ([version]($Mc.Split("-")[0]) -ge [version]"1.19") { "fabric-command-api-v2" } else { "fabric-command-api-v1" }

# ------------------------------------------------------------------ validate --
$settingsRaw = Get-Content -LiteralPath $SettingsPath -Raw
if ($settingsRaw -match [regex]::Escape("match(""$Mc"", ""$Loader"")")) {
    throw "Node $Node is already declared in settings.gradle.kts."
}

# ------------------------------------------------------------------- settings --
$lines = @(Get-Lines $SettingsPath)
$newline = Get-Newline $settingsRaw
$insertAt = -1
for ($i = 0; $i -lt $lines.Count; $i++) {
    $m = [regex]::Match($lines[$i], 'match\("([^"]+)",\s*"([^"]+)"')
    if (-not $m.Success -or $m.Groups[2].Value -ne $Loader) { continue }
    $other = $m.Groups[1].Value
    $a = @($other.Split(".") | ForEach-Object { [int]$_ })
    $b = @($Mc.Split(".") | ForEach-Object { [int]$_ })
    $cmp = 0
    for ($k = 0; $k -lt [Math]::Max($a.Count, $b.Count); $k++) {
        $x = if ($k -lt $a.Count) { $a[$k] } else { 0 }
        $y = if ($k -lt $b.Count) { $b[$k] } else { 0 }
        if ($x -ne $y) { $cmp = $x - $y; break }
    }
    if ($cmp -lt 0) {
        $end = $i
        if ($i + 1 -lt $lines.Count -and $lines[$i + 1] -match "TRAVERSAL-END $([regex]::Escape($other))-$Loader") { $end = $i + 1 }
        $insertAt = $end + 1
    }
}
if ($insertAt -lt 0) {
    # No lower sibling: insert before the first higher sibling, else after the last loader line.
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match "match\(""$Mc"",\s*""$Loader""\)") { $insertAt = $i; break }
    }
    if ($insertAt -lt 0) {
        $lastLoader = -1
        for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match "match\(""[^""]+"",\s*""$Loader""\)") { $lastLoader = $i } }
        $insertAt = if ($lastLoader -ge 0) { $lastLoader + 1 } else { $lines.Count }
        if ($lastLoader -ge 0 -and $lastLoader + 1 -lt $lines.Count -and $lines[$lastLoader + 1] -match "TRAVERSAL-END") { $insertAt = $lastLoader + 2 }
    }
}
$block = @("        // TRAVERSAL-BEGIN $Node", "        match(""$Mc"", ""$Loader"")", "        // TRAVERSAL-END $Node")
$newLines = @($lines[0..($insertAt - 1)]) + $block + @($lines[$insertAt..($lines.Count - 1)])
$newSettings = $newLines -join $newline
if (-not $newSettings.EndsWith($newline)) { $newSettings += $newline }

# ---------------------------------------------------------------- properties --
$propsRaw = Get-Content -LiteralPath $PropertiesPath -Raw
$deps = @()
switch ($Loader) {
    "fabric" {
        $deps += "deps.fabric_api = ""$FabricApi"""
        $deps += "deps.fabric_command_api = ""$commandApi"""
    }
    "forge" {
        $deps += "deps.forge_loader = ""$ForgeLoader"""
        $deps += "deps.forge_fml = ""$ForgeFml"""
    }
    "neoforge" {
        $deps += "deps.neo_loader = ""$NeoLoader"""
    }
}
$propBlock = @()
$propBlock += ""
$propBlock += "# TRAVERSAL-BEGIN $Node"
if ($propsRaw -notmatch "(?m)^\[`"$([regex]::Escape($Mc))`"\]") {
    $propBlock += "[`"$Mc`"]"
    $propBlock += ""
}
$propBlock += "[$Loader.`"$Mc`"]"
$propBlock += "mod.mc_compat = ""$Compat"""
$propBlock += $deps
$propBlock += "pack_format = $PackFormat"
$propBlock += "# TRAVERSAL-END $Node"
$newProps = $propsRaw.TrimEnd() + (Get-Newline $propsRaw) + ($propBlock -join (Get-Newline $propsRaw)) + (Get-Newline $propsRaw)

Write-Host ""
Write-Host "  Node:        $Node"
Write-Host "  compat:      $Compat"
Write-Host "  pack_format: $PackFormat"
foreach ($d in $deps) { Write-Host "  $d" }
foreach ($w in $Warnings) { Write-Host "  [WARN] $w" -ForegroundColor Yellow }

if ($DryRun) {
    Write-Host "  [DRY-RUN] no files changed"
    return
}

Set-Content -LiteralPath $SettingsPath -Value $newSettings -NoNewline
Set-Content -LiteralPath $PropertiesPath -Value $newProps -NoNewline

Write-Host ""
Write-Host "  added $Node" -ForegroundColor Green
Write-Host "  next: run .\gradlew.bat --no-daemon --no-configuration-cache :$Node`:compileJava" -ForegroundColor Cyan
