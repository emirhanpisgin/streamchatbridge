param(
    [string]$Token = $env:MODRINTH_TOKEN,
    [string]$Version = "0.1.0",
    [ValidateSet("fabric", "forge", "neoforge")]
    [string]$Loader = "fabric",
    [string]$ProjectSlug = "examplemod",
    [string]$ArtifactsDir = "",
    [string]$Changelog = "",
    [switch]$IncludeSources,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$api = "https://api.modrinth.com/v2"
$fabricApiProjectId = "P7dR8mSH"

if (-not $ArtifactsDir) {
    $ArtifactsDir = Join-Path (Split-Path $PSScriptRoot -Parent) "build\libs\$Version"
}
$ArtifactsDir = (Resolve-Path $ArtifactsDir).Path

if (-not $Changelog) { $Changelog = "Release $Version" }

# game_versions each node's jar claims. Keep these in sync with mod.mc_compat in
# stonecutter.properties.toml and with the node list in settings.gradle.kts.
$targets = switch ($Loader) {
    "forge" { @(@{ Mc = "26.1"; Games = @("26.1", "26.1.1", "26.1.2", "26.2", "26.3") }) }
    "neoforge" { @(@{ Mc = "26.1"; Games = @("26.1", "26.1.1", "26.1.2", "26.2", "26.3") }) }
    default { @(@{ Mc = "26.2"; Games = @("26.2", "26.3") }) }
}

$headers = @{ "User-Agent" = "$ProjectSlug-publish/$Version" }
if ($Token) { $headers["Authorization"] = $Token }

function Get-Json([string]$path) {
    $r = Invoke-WebRequest "$api$path" -Headers $headers -UseBasicParsing
    return $r.Content | ConvertFrom-Json
}

"== Validating project =="
try {
    $project = Get-Json "/project/$ProjectSlug"
    "Project: $($project.title) ($($project.id))"
} catch {
    throw "Project '$ProjectSlug' not found or not accessible: $_"
}

$tags = @((Get-Json "/tag/game_version") | ForEach-Object { $_.version })
foreach ($t in $targets) {
    foreach ($g in $t.Games) {
        if ($tags -notcontains $g) { Write-Warning "Game version tag '$g' does not exist on Modrinth" }
    }
}

if (-not $Token) {
    Write-Warning "No token provided - running in dry-run mode. Set `$env:MODRINTH_TOKEN or pass -Token."
    $DryRun = $true
}

$allVersions = @(Get-Json "/project/$ProjectSlug/version")

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
        project_id       = $project.id
        file_parts       = @("file1")
        primary_file     = "file1"
        environment      = "server_only_client_optional"
        file_types       = @{}
    }
    if ($IncludeSources -and (Test-Path $srcPath)) {
        $data.file_parts = @("file1", "sources1")
        $data.file_types = @{ sources1 = "sources-jar" }
    }

    $dataJson = $data | ConvertTo-Json -Depth 6

    if ($DryRun) {
        "[DRY-RUN] would publish $versionNumber ($jarName, sha1=$sha1) games=[$($t.Games -join ',')] loaders=[$Loader] deps=$($deps.Count)"
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
