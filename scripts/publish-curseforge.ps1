param(
    [string]$Token = $env:CURSEFORGE_TOKEN,
    [string]$ProjectId = "1739963",
    [string]$Version = "1.0.0",
    [ValidateSet("fabric", "forge", "neoforge")]
    [string]$Loader = "fabric",
    [string]$ArtifactsDir = "",
    [string]$Changelog = "",
    [switch]$IncludeSources,
    [string]$OnlyMc = "",
    [switch]$RefreshVersions,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
Set-Location $Root
$uploadApi = "https://minecraft.curseforge.com/api"
$fabricApiProjectId = "306612"          # Fabric API on CurseForge (requiredDependency for fabric files)
$loaderIds = @{ fabric = 7499; forge = 7498; neoforge = 10150 }
$clientEnvId = 9638                      # "Client" environment token (mod is client-side)
$ua = "streamchatbridge-cf/$Version (emirhanpisgin/streamchatbridge)"

if (-not $ArtifactsDir) { $ArtifactsDir = Join-Path $Root "build\libs\$Version" }
$ArtifactsDir = (Resolve-Path $ArtifactsDir).Path

$defaultChangelog = @"
Initial release.

- Bridges Minecraft chat with Twitch and Kick (Fabric, Forge and NeoForge; MC 1.17.1 through 26.3)
- Send your in-game chat to your stream and see Twitch/Kick chat in-game
- Rich chat: platform colors, badges, mention highlights, configurable chat toggles
- F8 dashboard for accounts, channels and message settings
"@
if (-not $Changelog) { $Changelog = $defaultChangelog }

# ---------------------------------------------------------------------------
# Targets are derived from the permanent nodes in stonecutter.properties.toml:
# one CurseForge file per node jar, with the game versions its mod.mc_compat
# range covers (only versions where that loader has a build).
# ---------------------------------------------------------------------------

function Get-PermanentNodes([string]$loader) {
    $out = @()
    $mc = ""
    $inSection = $false
    foreach ($line in Get-Content (Join-Path $Root "stonecutter.properties.toml")) {
        if ($line -match ('^\[' + $loader + '\.\"([^\"]+)\"\]')) { $inSection = $true; $mc = $Matches[1]; continue }
        if ($line -match '^\[') { $inSection = $false }
        if ($inSection -and $line -match '^mod\.mc_compat\s*=\s*"([^"]+)"') {
            $out += [pscustomobject]@{ Mc = $mc; Compat = $Matches[1] }
        }
    }
    return $out
}

function Get-ModrinthReleaseTags() {
    $tags = Invoke-RestMethod "https://api.modrinth.com/v2/tag/game_version" -TimeoutSec 60
    return @($tags | Where-Object { $_.version_type -eq "release" } | ForEach-Object { $_.version })
}

function Get-ForgeMcVersions() {
    $xml = [xml](Invoke-WebRequest "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml" -UseBasicParsing -TimeoutSec 60).Content
    $set = @{}
    foreach ($v in $xml.metadata.versioning.versions.version) {
        if ($v -match '^([0-9][0-9.]*)-') { $set[$Matches[1]] = $true }
    }
    return $set
}

function Get-NeoForgeMcVersions() {
    $xml = [xml](Invoke-WebRequest "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml" -UseBasicParsing -TimeoutSec 60).Content
    $set = @{}
    $unusable = @("1.20.2", "1.20.3", "1.20.5")
    foreach ($v in $xml.metadata.versioning.versions.version) {
        if ($v -match '^(\d+)\.(\d+)\.(\d+)') {
            $major = [int]$Matches[1]; $minor = [int]$Matches[2]; $patch = [int]$Matches[3]
            $mc = if ($major -le 25) {
                if ($minor -eq 0) { "1.$major" } else { "1.$major.$minor" }
            } else {
                if ($patch -eq 0) { if ($minor -eq 0) { "$major" } else { "$major.$minor" } } else { "$major.$minor.$patch" }
            }
            if ($unusable -notcontains $mc) { $set[$mc] = $true }
        }
    }
    return $set
}

function Get-GamesInRange([string]$compat, [string[]]$tags, $loaderHas) {
    $lower = $null; $upper = $null
    if ($compat -match '>=\s*([0-9][0-9.]*)') { $lower = $Matches[1] }
    if ($compat -match '<\s*([0-9][0-9.]*)') { $upper = $Matches[1] }
    $games = @()
    foreach ($t in $tags) {
        try { $tv = [version]$t } catch { continue }
        if ($lower -and $tv -lt [version]$lower) { continue }
        if ($upper -and $tv -ge [version]$upper) { continue }
        if ($loaderHas -and -not $loaderHas.ContainsKey($t)) { continue }
        $games += $t
    }
    return $games
}

$allTags = Get-ModrinthReleaseTags
$loaderHas = switch ($Loader) {
    "forge" { Get-ForgeMcVersions }
    "neoforge" { Get-NeoForgeMcVersions }
    default { $null }
}

$targets = @()
foreach ($node in Get-PermanentNodes $Loader) {
    $games = @(Get-GamesInRange $node.Compat $allTags $loaderHas)
    if ($games.Count -eq 0) { Write-Warning "No game versions for $Loader $($node.Mc) ($($node.Compat))"; continue }
    $targets += [pscustomobject]@{ Mc = $node.Mc; Games = $games }
}

if (-not $Token) {
    Write-Warning "No token provided - running in dry-run mode. Set `$env:CURSEFORGE_TOKEN or pass -Token."
    $DryRun = $true
}

# --- CurseForge game version ids (name -> id), cached under build/.cache ---
$cache = Join-Path $Root "build\.cache\cf-game-versions.json"
$stale = (-not (Test-Path $cache)) -or ((Get-Item $cache).LastWriteTime -lt (Get-Date).AddDays(-2))
if ($RefreshVersions -or $stale) {
    New-Item -ItemType Directory -Force -Path (Split-Path $cache) | Out-Null
    $r = Invoke-WebRequest "$uploadApi/game/versions" -Headers @{ "X-Api-Token" = $Token; "User-Agent" = $ua } -UseBasicParsing -TimeoutSec 120
    $bytes = if ($r.Content -is [byte[]]) { $r.Content } else { [System.Text.Encoding]::UTF8.GetBytes($r.Content) }
    [System.IO.File]::WriteAllBytes($cache, $bytes)
    "fetched CurseForge game versions -> $cache"
}
$cfVersions = Get-Content $cache -Raw | ConvertFrom-Json
$idByName = @{}
foreach ($v in $cfVersions) {
    # Skip Bedrock Edition entries (gameVersionTypeID 615) - names collide with Java
    # ("1.20" exists as both) and the upload API rejects the Bedrock id.
    if ([int]$v.gameVersionTypeID -eq 615) { continue }
    if (-not $idByName.ContainsKey($v.name)) { $idByName[$v.name] = [int]$v.id }
}
"game versions loaded: $($idByName.Count) names (Fabric=$($idByName['Fabric']), Forge=$($idByName['Forge']), NeoForge=$($idByName['NeoForge']), Client=$($idByName['Client']))"

function Invoke-CfUpload {
    param([string]$MetadataJson, [byte[]]$FileBytes, [string]$FileName)
    $client = [System.Net.Http.HttpClient]::new()
    try {
        $client.DefaultRequestHeaders.TryAddWithoutValidation("X-Api-Token", $Token) | Out-Null
        $client.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", $ua) | Out-Null
        for ($attempt = 1; $attempt -le 3; $attempt++) {
            $form = [System.Net.Http.MultipartFormDataContent]::new()
            try {
                $form.Add([System.Net.Http.StringContent]::new($MetadataJson, [System.Text.Encoding]::UTF8, "application/json"), "metadata")
                $part = [System.Net.Http.ByteArrayContent]::new($FileBytes)
                $part.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/java-archive")
                $form.Add($part, "file", $FileName)
                $resp = $client.PostAsync("$uploadApi/projects/$ProjectId/upload-file", $form).Result
                $body = $resp.Content.ReadAsStringAsync().Result
                if ($resp.IsSuccessStatusCode) { return @{ Ok = $true; Body = $body } }
                $code = [int]$resp.StatusCode
                if ($code -in @(429, 500, 502, 503, 504) -and $attempt -lt 3) {
                    Write-Warning "  upload retry $attempt ($code): $($body.Substring(0, [Math]::Min(160, $body.Length)))"
                    Start-Sleep -Seconds (5 * $attempt)
                    continue
                }
                return @{ Ok = $false; Body = "HTTP $code $body" }
            } finally { $form.Dispose() }
        }
    } finally { $client.Dispose() }
    return @{ Ok = $false; Body = "unreachable" }
}

$results = @()
foreach ($t in $targets) {
    $mc = $t.Mc
    if ($OnlyMc -and $mc -ne $OnlyMc) { continue }
    $jarName = "streamchatbridge-$Loader-$Version+$mc.jar"
    $jarPath = Join-Path $ArtifactsDir $jarName
    $srcName = "streamchatbridge-$Loader-$Version+$mc-sources.jar"
    $srcPath = Join-Path $ArtifactsDir $srcName
    if (-not (Test-Path $jarPath)) { throw "Missing artifact: $jarPath" }

    $ids = @()
    foreach ($g in $t.Games) { if ($idByName.ContainsKey($g)) { $ids += $idByName[$g] } else { Write-Warning "[$mc] game version '$g' missing on CurseForge" } }
    $ids += $loaderIds[$Loader]
    $ids += $clientEnvId
    $ids = [int[]]$ids

    $displayName = "StreamChatBridge $Version for $mc ($Loader)"
    $meta = @{
        changelog     = $Changelog
        changelogType = "markdown"
        displayName   = $displayName
        gameVersions  = $ids
        releaseType   = "release"
    }
    if ($Loader -eq "fabric") {
        $meta.relations = @{ projects = @(@{ slug = "fabric-api"; projectID = [int]$fabricApiProjectId; type = "requiredDependency" }) }
    }

    if ($DryRun) {
        "[DRY-RUN] $displayName | file=$jarName | versions=[$($t.Games -join ',')] (+$Loader+Client) ids=$($ids.Count) | sources=$(Test-Path $srcPath)"
        continue
    }

    $jarBytes = [System.IO.File]::ReadAllBytes($jarPath)
    $up = Invoke-CfUpload -MetadataJson ($meta | ConvertTo-Json -Depth 6) -FileBytes $jarBytes -FileName $jarName
    if (-not $up.Ok) {
        Write-Warning "FAILED $displayName : $($up.Body)"
        $results += "FAIL $mc"
        Start-Sleep -Seconds 3
        continue
    }

    $fileId = $null
    try {
        $parsed = $up.Body | ConvertFrom-Json
        if ($parsed -is [int] -or $parsed -is [long]) { $fileId = [int]$parsed }
        elseif ($parsed.id) { $fileId = [int]$parsed.id }
    } catch { }
    if (-not $fileId) { try { $fileId = [int]$up.Body.Trim() } catch { } }
    "PUBLISHED $mc -> fileId=$fileId"

    if ($IncludeSources -and (Test-Path $srcPath) -and $fileId) {
        $srcMeta = @{
            changelog     = "Sources for $displayName"
            changelogType = "text"
            displayName   = "$displayName (sources)"
            parentFileID  = $fileId
            releaseType   = "release"
        }
        if ($Loader -eq "fabric") {
            $srcMeta.relations = @{ projects = @(@{ slug = "fabric-api"; projectID = [int]$fabricApiProjectId; type = "requiredDependency" }) }
        }
        $srcUp = Invoke-CfUpload -MetadataJson ($srcMeta | ConvertTo-Json -Depth 6) -FileBytes ([System.IO.File]::ReadAllBytes($srcPath)) -FileName $srcName
        if ($srcUp.Ok) { "  sources attached ($srcName)" } else { Write-Warning "  sources FAILED: $($srcUp.Body)" }
        Start-Sleep -Seconds 3
    }
    $results += "OK $mc"
    Start-Sleep -Seconds 3
}

if (-not $DryRun) {
    "== summary =="
    $results | ForEach-Object { $_ }
}
