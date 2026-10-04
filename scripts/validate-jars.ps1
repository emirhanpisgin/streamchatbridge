#requires -Version 7.0
<#
    Validates every collected permanent-node jar in build/libs/<Version>/ against
    stonecutter.properties.toml:

      - jar + sources jar exist
      - embedded minecraft range == mod.mc_compat (no unresolved ${...})
      - loader metadata file name/format for the era
      - client-side only (fabric environment=client / toml side=CLIENT)
      - pack.mcmeta pack_format == pack_format
      - expected entrypoint class present
      - class-file major version matches the era's Java release
      - fabric.mod.json module deps resolved for the node

    Usage: pwsh -File scripts/validate-jars.ps1 [-Version 1.0.0]
#>
param(
    [string]$Version = "1.0.0"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
Set-Location $Root
Add-Type -AssemblyName System.IO.Compression.FileSystem

$propsPath = Join-Path $Root "stonecutter.properties.toml"
$lines = Get-Content -LiteralPath $propsPath

$sections = [System.Collections.Generic.List[object]]::new()
$current = $null
foreach ($line in $lines) {
    if ($line -match '^\[(fabric|forge|neoforge)\.\"([^\"]+)\"\]') {
        if ($current) { $sections.Add($current) }
        $current = [pscustomobject]@{ Loader = $Matches[1]; Mc = $Matches[2]; Props = @{} }
        continue
    }
    if ($line -match '^\[([^\]]+)\]') { if ($current) { $sections.Add($current) }; $current = $null; continue }
    if ($current -and $line -match '^\s*([A-Za-z0-9_.]+)\s*=\s*(.+?)\s*(#.*)?$') {
        $current.Props[$Matches[1]] = $Matches[2].Trim('"')
    }
}
if ($current) { $sections.Add($current) }

function Get-ClassMajor {
    param([System.IO.Compression.ZipArchive]$Zip, [string]$EntryName)
    $e = $Zip.Entries | Where-Object { $_.FullName -eq $EntryName } | Select-Object -First 1
    if (-not $e) { return $null }
    $s = $e.Open()
    try {
        $buf = New-Object byte[] 8
        $read = 0
        while ($read -lt 8) { $n = $s.Read($buf, $read, 8 - $read); if ($n -le 0) { break }; $read += $n }
        if ($read -lt 8) { return $null }
        return [int]$buf[6] * 256 + [int]$buf[7]
    } finally { $s.Dispose() }
}

function Read-ZipText {
    param([System.IO.Compression.ZipArchive]$Zip, [string]$EntryName)
    $e = $Zip.Entries | Where-Object { $_.FullName -eq $EntryName } | Select-Object -First 1
    if (-not $e) { return $null }
    $r = New-Object System.IO.StreamReader($e.Open())
    try { return $r.ReadToEnd() } finally { $r.Dispose() }
}

$majorToJava = @{ 52 = 8; 61 = 17; 65 = 21; 69 = 25 }
$failures = 0
$warnings = 0

foreach ($s in ($sections | Sort-Object Loader, Mc)) {
    $fail = [System.Collections.Generic.List[string]]::new()
    $warn = [System.Collections.Generic.List[string]]::new()
    $mc = $s.Mc
    $range = $s.Props["mod.mc_compat"]
    $pack = $s.Props["pack_format"]

    $jarName = "streamchatbridge-$($s.Loader)-$Version+$mc.jar"
    $jarPath = Join-Path $Root "build\libs\$Version\$jarName"
    $srcPath = Join-Path $Root "build\libs\$Version\streamchatbridge-$($s.Loader)-$Version+$mc-sources.jar"
    if (-not (Test-Path $jarPath)) { $fail.Add("missing $jarName"); Write-Host ("{0,-9} {1,-8} FAIL  {2}" -f $s.Loader, $mc, ($fail -join "; ")); $failures++; continue }
    if (-not (Test-Path $srcPath)) { $fail.Add("missing sources jar") }

    $zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        $entryPoint = "com/kryp/streamchatbridge/StreamChatBridge.class"
        $classMajor = Get-ClassMajor -Zip $zip -EntryName $entryPoint
        if ($classMajor -eq $null) { $fail.Add("missing StreamChatBridge.class") }
        elseif (-not $majorToJava.ContainsKey($classMajor)) { $warn.Add("unknown class major $classMajor") }
        else {
            $java = $majorToJava[$classMajor]
            $expectedJava = if ($s.Loader -eq "fabric") { 17 } elseif ($mc -ge [version]"26.1") { 25 } elseif ($mc -ge [version]"1.20.5") { 21 } else { 17 }
            if ($java -ne $expectedJava) { $warn.Add("java $java (expected bytecode $expectedJava)") }
        }

        $packText = Read-ZipText -Zip $zip -EntryName "pack.mcmeta"
        if ($packText) {
            try { $packObj = ($packText | ConvertFrom-Json).pack } catch { $packObj = $null }
            if (-not $packObj) { $fail.Add("pack.mcmeta does not parse") }
            elseif ([int]$pack -ge 65) {
                if (-not $packObj.min_format) { $fail.Add("min_format missing") }
                if (-not $packObj.max_format) { $fail.Add("max_format missing") }
                if ($packObj.PSObject.Properties["pack_format"]) { $fail.Add("pack_format not allowed with new schema") }
            } else {
                if ("$($packObj.pack_format)" -ne "$pack") { $fail.Add("pack_format=$($packObj.pack_format) want $pack") }
            }
        } else { $fail.Add("missing pack.mcmeta") }

        if ($s.Loader -eq "fabric") {
            $meta = Read-ZipText -Zip $zip -EntryName "fabric.mod.json"
            if (-not $meta) { $fail.Add("missing fabric.mod.json") }
            else {
                if ($meta -match '\$\{') { $fail.Add("unresolved macro in fabric.mod.json") }
                $j = $meta | ConvertFrom-Json
                if ($j.environment -ne "client") { $fail.Add("environment=$($j.environment)") }
                if ($j.depends.minecraft -ne $range) { $fail.Add("range='$($j.depends.minecraft)' want '$range'") }
                $cmd = if ($mc -ge [version]"1.19") { "fabric-command-api-v2" } else { "fabric-command-api-v1" }
                $key = if ($mc -ge [version]"26.1") { "fabric-key-mapping-api-v1" } else { "fabric-key-binding-api-v1" }
                foreach ($dep in @("fabricloader", "fabric-api-base", $cmd, $key, "fabric-lifecycle-events-v1", "minecraft", "java")) {
                    if (-not $j.depends.PSObject.Properties[$dep]) { $fail.Add("dep missing: $dep") }
                }
                if (-not ($j.entrypoints.client -contains "com.kryp.streamchatbridge.StreamChatBridgeFabricClient")) { $fail.Add("entrypoint missing") }
                if ($zip.Entries | Where-Object { $_.FullName -eq "META-INF/mods.toml" }) { $fail.Add("leaked mods.toml") }
            }
        }
        else {
            $tomlName = if ($s.Loader -eq "neoforge" -and $mc -lt [version]"1.20.5") { "META-INF/mods.toml" } elseif ($s.Loader -eq "neoforge") { "META-INF/neoforge.mods.toml" } else { "META-INF/mods.toml" }
            $meta = Read-ZipText -Zip $zip -EntryName $tomlName
            if (-not $meta) { $fail.Add("missing $tomlName") }
            else {
                if ($meta -match '\$\{') { $fail.Add("unresolved macro in $tomlName") }
                if ($meta -notmatch [regex]::Escape($range)) { $fail.Add("range '$range' not in $tomlName") }
                $requiredSides = if ($s.Loader -eq "forge") { 2 } else { 1 }
                if (([regex]::Matches($meta, 'side\s*=\s*"CLIENT"')).Count -lt $requiredSides) { $fail.Add("side=CLIENT missing") }
                if ($meta -notmatch 'license\s*=\s*"MIT"') { $fail.Add("license missing") }
                $entryClass = if ($s.Loader -eq "forge") { "com/kryp/streamchatbridge/StreamChatBridgeForge.class" } else { "com/kryp/streamchatbridge/StreamChatBridgeNeoForge.class" }
                if (-not ($zip.Entries | Where-Object { $_.FullName -eq $entryClass })) { $fail.Add("entrypoint class missing") }
                if ($zip.Entries | Where-Object { $_.FullName -eq "fabric.mod.json" }) { $fail.Add("leaked fabric.mod.json") }
                if ($s.Loader -eq "forge") {
                    $fml = $s.Props["deps.forge_fml"]
                    if ($fml -and $meta -notmatch "loaderVersion`?=\s*`"\[$fml,") { $fail.Add("loaderVersion floor != $fml") }
                }
            }
        }
    } finally { $zip.Dispose() }

    $status = if ($fail.Count) { "FAIL" } else { "PASS" }
    if ($fail.Count) { $failures++ }
    if ($warn.Count) { $warnings++ }
    $extra = if ($fail.Count) { "  " + ($fail -join "; ") } elseif ($warn.Count) { "  (" + ($warn -join "; ") + ")" } else { "" }
    Write-Host ("{0,-9} {1,-8} {2}  range={3} pack={4}{5}" -f $s.Loader, $mc, $status, $range, $pack, $extra)
}

Write-Host ""
Write-Host "validated $($sections.Count) node jars: $($sections.Count - $failures) pass, $failures fail, $warnings warnings"
if ($failures -gt 0) { exit 1 }
