param(
    [string]$Token = $env:MODRINTH_TOKEN,
    [string]$Version = "1.0.0",
    [ValidateSet("fabric", "forge", "neoforge")]
    [string]$Loader = "fabric",
    [string]$ProjectSlug = "streamchatbridge",
    [string]$ArtifactsDir = "",
    [string]$Changelog = "",
    [switch]$IncludeSources,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
Set-Location $Root
$api = "https://api.modrinth.com/v2"
$fabricApiProjectId = "P7dR8mSH"

if (-not $ArtifactsDir) {
    $ArtifactsDir = Join-Path $Root "build\libs\$Version"
}
$ArtifactsDir = (Resolve-Path $ArtifactsDir).Path

if (-not $Changelog) { $Changelog = "Release $Version" }

# ---------------------------------------------------------------------------
# Targets are derived from the permanent nodes in stonecutter.properties.toml:
# one Modrinth version per node jar, with the game versions its mod.mc_compat
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
    $tags = Invoke-RestMethod "$api/tag/game_version" -TimeoutSec 60
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
    $games = Get-GamesInRange $node.Compat $allTags $loaderHas
    if ($games.Count -eq 0) { Write-Warning "No game versions for $Loader $($node.Mc) ($($node.Compat))"; continue }
    $targets += [pscustomobject]@{ Mc = $node.Mc; Games = $games }
}

$headers = @{ "User-Agent" = "$ProjectSlug-publish/$Version" }
if ($Token) { $headers["Authorization"] = $Token }

function Get-Json([string]$path) {
    $r = Invoke-WebRequest "$api$path" -Headers $headers -UseBasicParsing
    return $r.Content | ConvertFrom-Json
}

"== Validating project =="
$project = $null
try {
    $project = Get-Json "/project/$ProjectSlug"
    "Project: $($project.title) ($($project.id))"
} catch {
    if ($DryRun) {
        Write-Warning "Project '$ProjectSlug' not found - dry run continues without it"
    } else {
        throw "Project '$ProjectSlug' not found or not accessible: $_"
    }
}

foreach ($t in $targets) {
    foreach ($g in $t.Games) {
        if ($allTags -notcontains $g) { Write-Warning "Game version tag '$g' does not exist on Modrinth" }
    }
}

if (-not $Token) {
    Write-Warning "No token provided - running in dry-run mode. Set `$env:MODRINTH_TOKEN or pass -Token."
    $DryRun = $true
}

$allVersions = if ($project) { @(Get-Json "/project/$ProjectSlug/version") } else { @() }

foreach ($t in $targets) {
    $mc = $t.Mc
    $jarName = "$ProjectSlug-$Loader-$Version+$mc.jar"
    $jarPath = Join-Path $ArtifactsDir $jarName
    $srcName = "$ProjectSlug-$Loader-$Version+$mc-sources.jar"
    $srcPath = Join-Path $ArtifactsDir $srcName
    $versionNumber = "$Version+$mc"

    if (-not (Test-Path $jarPath)) { throw "Missing artifact: $jarPath" }
    $jarBytes = [System.IO.File]::ReadAllBytes($jarPath)
    $sha1 = [System.BitConverter]::ToString([System.Security.Cryptography.SHA1]::HashData($jarBytes)).Replace("-", "").ToLower()

    $deps = @()
    if ($Loader -eq "fabric") {
        $fapiVersions = @(Get-Json "/project/$fabricApiProjectId/version?game_versions=%5B%22$mc%22%5D&loaders=%5B%22fabric%22%5D")
        if ($fapiVersions.Count -eq 0) { throw "No Fabric API version on Modrinth for $mc" }
        $deps = @(@{ project_id = $fabricApiProjectId; dependency_type = "required" })
    }

    $name = "$ProjectSlug $Version for $mc ($Loader)"

    if (@($allVersions | Where-Object { $_.version_number -eq $versionNumber -and $_.loaders -contains $Loader }).Count -gt 0) {
        "SKIP $versionNumber ($Loader already exists)"
        continue
    }

    $data = @{
        name             = $name
        version_number   = $versionNumber
        changelog        = $Changelog
        dependencies     = $deps
        game_versions    = $t.Games
        version_type     = "release"
        loaders          = @($Loader)
        featured         = $false
        status           = "listed"
        requested_status = "listed"
        project_id       = if ($project) { $project.id } else { "dry-run" }
        file_parts       = @("file1")
        primary_file     = "file1"
        environment      = "client_only"
        file_types       = @{}
    }
    if ($IncludeSources -and (Test-Path $srcPath)) {
        $data.file_parts = @("file1", "sources1")
        $data.file_types = @{ sources1 = "sources-jar" }
    }

    $dataJson = $data | ConvertTo-Json -Depth 6

    if ($DryRun) {
        "[DRY-RUN] would publish $versionNumber ($jarName, sha1=$sha1) games=[$($t.Games -join ',')] loaders=[$Loader] deps=$($deps.Count) env=client_only"
        continue
    }

    try {
        $client = [System.Net.Http.HttpClient]::new()
        $client.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", "$ProjectSlug-publish/$Version") | Out-Null
        $client.DefaultRequestHeaders.TryAddWithoutValidation("Authorization", $Token) | Out-Null

        $form = [System.Net.Http.MultipartFormDataContent]::new()
        $form.Add([System.Net.Http.StringContent]::new($dataJson, [System.Text.Encoding]::UTF8, "application/json"), "data")
        $jarPart = [System.Net.Http.ByteArrayContent]::new($jarBytes)
        $jarPart.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/java-archive")
        $form.Add($jarPart, "file1", $jarName)
        if ($IncludeSources -and (Test-Path $srcPath)) {
            $srcBytes = [System.IO.File]::ReadAllBytes($srcPath)
            $srcPart = [System.Net.Http.ByteArrayContent]::new($srcBytes)
            $srcPart.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/java-archive")
            $form.Add($srcPart, "sources1", $srcName)
        }

        $resp = $client.PostAsync("$api/version", $form).Result
        $body = $resp.Content.ReadAsStringAsync().Result
        if ($resp.IsSuccessStatusCode) {
            $created = $body | ConvertFrom-Json
            "PUBLISHED $versionNumber -> https://modrinth.com/mod/$ProjectSlug/version/$($created.id)"
        } else {
            Write-Warning "FAILED $versionNumber ($($resp.StatusCode)): $body"
        }
        $client.Dispose()
    } catch {
        Write-Warning "FAILED $versionNumber : $_"
    }
}
