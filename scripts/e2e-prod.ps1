#requires -Version 7.0
<#
    Production-jar smoke test: installs a real loader + the built mod jar into an
    isolated game dir (no Gradle dev runtime) and runs the E2E chat/screen test.

    Usage:
        pwsh -File scripts/e2e-prod.ps1 -Node 1.17.1-fabric -Mc 1.17.1
        pwsh -File scripts/e2e-prod.ps1 -Node 1.17.1-fabric -Mc 1.18.2   # range end
        pwsh -File scripts/e2e-prod.ps1 -Node 26.3-fabric -Mc 26.3
        pwsh -File scripts/e2e-prod.ps1 -Node 1.17.1-forge -Mc 1.17.1 -PrepareOnly
#>
param(
    [Parameter(Mandatory = $true)][string]$Node,
    [Parameter(Mandatory = $true)][string]$Mc,
    [int]$Port = 25565,
    [switch]$PrepareOnly,
    [switch]$LeaveOpen,
    [switch]$RequireEcho,
    [switch]$Focus
)

$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
Set-Location $Root

$parts = $Node -split '-'
$Loader = $parts[-1]
$NodeMc = ($parts[0..($parts.Count - 2)] -join '-')
$McV = [version]$Mc
$Label = "$Node@$Mc"

$java = if ($McV -ge [version]"26.1") { "C:\Program Files\Java\jdk-25.0.2\bin\java.exe" }
    elseif ($McV -ge [version]"1.20.5") { "C:\Program Files\Java\jdk-21\bin\java.exe" }
    else { "C:\Program Files\Java\jdk-17\bin\java.exe" }
if (-not (Test-Path $java)) { throw "Missing JDK: $java" }
$Dir = Join-Path $Root "build\prod\$Label"
$ModsDir = Join-Path $Dir "mods"
$NativesDir = Join-Path $Dir "natives"
$LibsDir = Join-Path $Dir "libraries"
New-Item -ItemType Directory -Force -Path $ModsDir, $NativesDir, $LibsDir, (Join-Path $Dir "config") | Out-Null

$script:modRoot = if (Test-Path "C:\Users\Kryp\scoop\persist\gradle\.gradle\caches\modules-2\files-2.1") {
    "C:\Users\Kryp\scoop\persist\gradle\.gradle\caches\modules-2\files-2.1"
} else { Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1" }
$script:modIndex = $null
$script:downloads = 0

function Get-ModuleJar([string]$fileName) {
    if (-not $script:modIndex) {
        $script:modIndex = @{}
        Get-ChildItem $script:modRoot -Recurse -File -Filter *.jar -ErrorAction SilentlyContinue | ForEach-Object {
            if (-not $script:modIndex.ContainsKey($_.Name)) { $script:modIndex[$_.Name] = $_.FullName }
        }
    }
    if ($script:modIndex.ContainsKey($fileName)) { return $script:modIndex[$fileName] }
    return $null
}

function Save-File([string]$url, [string]$target) {
    if (Test-Path $target) { return $target }
    New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
    Invoke-WebRequest $url -OutFile $target -TimeoutSec 900
    $script:downloads++
    return $target
}

function Get-FabricApiJar([string]$mc, [string]$modsDir) {
    # The maven `fabric-api` artifact is a stub bundle for old versions; fetch the
    # full jar users install from Modrinth (project P7dR8mSH).
    $query = 'https://api.modrinth.com/v2/project/P7dR8mSH/version?game_versions=%5B%22' + $mc + '%22%5D&loaders=%5B%22fabric%22%5D'
    $versions = Invoke-RestMethod $query -TimeoutSec 60
    if (-not $versions -or $versions.Count -eq 0) { throw "No Fabric API on Modrinth for $mc" }
    $v = $versions[0]
    $file = $v.files | Where-Object { $_.primary } | Select-Object -First 1
    if (-not $file) { $file = $v.files[0] }
    $target = Join-Path $modsDir $file.filename
    if (-not (Test-Path $target)) { Save-File $file.url $target | Out-Null }
    return $target
}

function Get-NodeProp([string]$loader, [string]$mc, [string]$key) {
    $section = $loader + '.' + [char]34 + $mc + [char]34
    $inSection = $false
    foreach ($l in Get-Content (Join-Path $Root "stonecutter.properties.toml")) {
        if ($l -match '^\[([^\]]+)\]') { $inSection = ($Matches[1] -eq $section); continue }
        if ($inSection -and $l -match ('^\s*' + [regex]::Escape($key) + '\s*=\s*"?([^"\s]+)"?\s*$')) { return $Matches[1] }
    }
    return $null
}

function Get-ForgeVersion([string]$mc) {
    $xml = [xml](Invoke-WebRequest "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml" -UseBasicParsing -TimeoutSec 60).Content
    $versions = @($xml.metadata.versioning.versions.version | Where-Object { $_ -match ('^' + [regex]::Escape($mc) + '-') })
    if ($versions.Count -eq 0) { throw "No Forge build for $mc" }
    return $versions[-1]
}

function Get-NeoForgeVersion([string]$mc) {
    $xml = [xml](Invoke-WebRequest "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml" -UseBasicParsing -TimeoutSec 60).Content
    $short = ($mc -split '\.')[1..2] -join '.'
    $versions = @($xml.metadata.versioning.versions.version | Where-Object { $_ -match ('^' + [regex]::Escape($short) + '(\.|$)') -and $_ -notmatch 'beta' })
    if ($versions.Count -eq 0) { throw "No NeoForge build for $mc" }
    return $versions[-1]
}

function Get-Prop([string]$name) {
    $line = Select-String -Path (Join-Path $Root "stonecutter.properties.toml") -Pattern ("^\s*" + [regex]::Escape($name) + "\s*=\s*""?([^""]+)""?\s*$") | Select-Object -First 1
    if ($line) { return ($line.Matches[0].Groups[1].Value) }
    return $null
}

function Get-VersionJson([string]$mc) {
    $cache = Join-Path $Root "build\.cache\version-$mc.json"
    if (-not (Test-Path $cache)) {
        $manifest = Invoke-RestMethod "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json" -TimeoutSec 30
        $entry = $manifest.versions | Where-Object { $_.id -eq $mc } | Select-Object -First 1
        if (-not $entry) { throw "Unknown Minecraft version $mc" }
        Save-File $entry.url $cache | Out-Null
    }
    return (Get-Content $cache -Raw | ConvertFrom-Json)
}

function Test-LibraryAllowed($lib) {
    if (-not $lib.rules) { return $true }
    $allowed = $false
    foreach ($rule in $lib.rules) {
        $match = $true
        if ($rule.os -and $rule.os.name -and $rule.os.name -ne "windows") { $match = $false }
        if ($match) { $allowed = ($rule.action -eq "allow") }
    }
    return $allowed
}

function Test-ArgAllowed($rules) {
    if (-not $rules) { return $true }
    $allowed = $false
    foreach ($rule in $rules) {
        $match = $true
        if ($rule.os -and $rule.os.name -and $rule.os.name -ne "windows") { $match = $false }
        if ($rule.features) { $match = $false }
        if ($match) { $allowed = ($rule.action -eq "allow") }
    }
    return $allowed
}

function Resolve-Artifact($artifact) {
    if (-not $artifact) { return $null }
    $fileName = [System.IO.Path]::GetFileName($artifact.path)
    $target = Join-Path $LibsDir $artifact.path
    if (Test-Path $target) { return $target }
    $local = Get-ModuleJar $fileName
    if ($local) {
        New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
        Copy-Item $local $target -Force
        return $target
    }
    return (Save-File $artifact.url $target)
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
function Expand-Natives([string]$zipPath, [string]$dest) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        foreach ($e in $zip.Entries) {
            if ($e.FullName -like "META-INF/*" -or $e.FullName.EndsWith("/")) { continue }
            $out = Join-Path $dest $e.FullName
            New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $out, $true)
        }
    } finally { $zip.Dispose() }
}

# --- built jar -----------------------------------------------------------------
$jar = Join-Path $Root "build\libs\1.0.0\streamchatbridge-$Loader-1.0.0+$NodeMc.jar"
if (-not (Test-Path $jar)) { throw "Missing built jar: $jar" }
Copy-Item $jar $ModsDir -Force

# --- Twitch config/token seed (same files the dev runs use) --------------------
foreach ($f in @("streamchatbridge.json", "streamchatbridge-twitch.json")) {
    $src = Join-Path $Root "run\config\$f"
    if (Test-Path $src) { Copy-Item $src (Join-Path $Dir "config\$f") -Force }
}

# --- vanilla -------------------------------------------------------------------
$vjson = Get-VersionJson $Mc
$clientJar = Join-Path $Root "build\.cache\client-$Mc.jar"
if (-not (Test-Path $clientJar)) { Save-File $vjson.downloads.client.url $clientJar | Out-Null }

$classpath = [System.Collections.Generic.List[string]]::new()
# The client jar goes on the classpath for Fabric only; Forge/NeoForge's FML adds
# the game jar itself (from the version dir), and a second copy collides as a
# duplicate module ("client" vs "minecraft").

foreach ($lib in $vjson.libraries) {
    if (-not (Test-LibraryAllowed $lib)) { continue }
    $path = Resolve-Artifact $lib.downloads.artifact
    if ($path) { $classpath.Add($path) }
    if ($lib.natives -and $lib.natives.windows) {
        $classifier = $lib.natives.windows -replace '\$\{arch\}', '64'
        $natArtifact = $lib.downloads.classifiers.$classifier
        $natPath = Resolve-Artifact $natArtifact
        if ($natPath) { Expand-Natives $natPath $NativesDir }
    }
}

# --- loader --------------------------------------------------------------------
if ($Loader -eq "fabric") {
    $classpath.Insert(0, $clientJar)
    $loaderVer = Get-Prop "deps.fabric_loader"
    $loaderJar = Get-ModuleJar "fabric-loader-$loaderVer.jar"
    if (-not $loaderJar) { $loaderJar = Save-File "https://maven.fabricmc.net/net/fabricmc/fabric-loader/$loaderVer/fabric-loader-$loaderVer.jar" (Join-Path $LibsDir "fabric-loader-$loaderVer.jar") }
    $classpath.Add($loaderJar)

    # Production profiles put the intermediary mappings artifact on the classpath;
    # without it the loader cannot remap mods and fails on access wideners.
    $intermediary = Get-ModuleJar "intermediary-$Mc.jar"
    if (-not $intermediary -and -not (Test-Path (Join-Path $LibsDir "intermediary-$Mc.jar"))) {
        try { Save-File "https://maven.fabricmc.net/net/fabricmc/intermediary/$Mc/intermediary-$Mc.jar" (Join-Path $LibsDir "intermediary-$Mc.jar") | Out-Null } catch { }
    }
    if (Test-Path (Join-Path $LibsDir "intermediary-$Mc.jar")) { $classpath.Add((Join-Path $LibsDir "intermediary-$Mc.jar")) }
    elseif ($intermediary) { $classpath.Add($intermediary) }

    # The loader POM is a stub; its runtime deps are resolved by Loom explicitly.
    $explicitDeps = @(
        "org.ow2.asm:asm:9.9.1",
        "org.ow2.asm:asm-analysis:9.9.1",
        "org.ow2.asm:asm-commons:9.9.1",
        "org.ow2.asm:asm-tree:9.9.1",
        "org.ow2.asm:asm-util:9.9.1",
        "net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7"
    )
    foreach ($dep in $explicitDeps) {
        $p = $dep -split ':'
        $fileName = "$($p[1])-$($p[2]).jar"
        $depJar = Get-ModuleJar $fileName
        if (-not $depJar) {
            $groupPath = $p[0] -replace '\.', '/'
            $base = if ($p[0] -like "net.fabricmc*") { "https://maven.fabricmc.net" } else { "https://repo1.maven.org/maven2" }
            $depJar = Save-File "$base/$groupPath/$($p[1])/$($p[2])/$fileName" (Join-Path $LibsDir $fileName)
        }
        $classpath.Add($depJar)
    }

    # Fabric API for the *target* MC (real users install the matching API).
    Get-ChildItem $ModsDir -Filter "fabric-api-*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
    Get-FabricApiJar $Mc $ModsDir | Out-Null
} else {
    # Forge / NeoForge: run the official installer, then launch from its json.
    # The loader pin must match the *target* MC version (range tests install the
    # target's Forge, not the node's).
    $loaderVer = if ($Mc -eq $NodeMc) { Get-NodeProp $Loader $NodeMc $(if ($Loader -eq "forge") { "deps.forge_loader" } else { "deps.neo_loader" }) } elseif ($Loader -eq "forge") { Get-ForgeVersion $Mc } else { Get-NeoForgeVersion $Mc }
    if ($Loader -eq "forge") {
        # Node pins omit the MC prefix; maven-metadata versions include it.
        $fullVer = if ($Mc -eq $NodeMc) { "$Mc-$loaderVer" } else { $loaderVer }
        $installerUrl = "https://maven.minecraftforge.net/net/minecraftforge/forge/$fullVer/forge-$fullVer-installer.jar"
        $versionId = "$Mc-forge-$($fullVer.Substring($Mc.Length + 1))"
    } else {
        $installerUrl = "https://maven.neoforged.net/releases/net/neoforged/neoforge/$loaderVer/neoforge-$loaderVer-installer.jar"
        $versionId = "neoforge-$loaderVer"
    }
    $installer = Save-File $installerUrl (Join-Path $Root "build\.cache\installer-$Label.jar")
    if (-not (Test-Path (Join-Path $Dir "launcher_profiles.json"))) { Set-Content -LiteralPath (Join-Path $Dir "launcher_profiles.json") -Value "{}" -NoNewline }
    $instJsonPath = Join-Path $Dir "versions\$versionId\$versionId.json"
    if (-not (Test-Path $instJsonPath)) {
        "running $Loader installer for $Label (downloads libraries)..."
        & $java -jar $installer --installClient $Dir 2>&1 | Select-Object -Last 4
        if (-not (Test-Path $instJsonPath)) { throw "Installer did not produce $instJsonPath" }
    }
    $fj = Get-Content $instJsonPath -Raw | ConvertFrom-Json

    foreach ($lib in $fj.libraries) {
        if (-not (Test-LibraryAllowed $lib)) { continue }
        $path = Resolve-Artifact $lib.downloads.artifact
        if ($path) { $classpath.Add($path) }
        if ($lib.natives -and $lib.natives.windows) {
            $classifier = $lib.natives.windows -replace '\$\{arch\}', '64'
            $natPath = Resolve-Artifact $lib.downloads.classifiers.$classifier
            if ($natPath) { Expand-Natives $natPath $NativesDir }
        }
    }
    # Classifier-named native libraries (modern LWJGL layout).
    foreach ($lib in @($vjson.libraries) + @($fj.libraries)) {
        if ($lib.name -match ':natives-windows$') {
            $path = Resolve-Artifact $lib.downloads.artifact
            if ($path) { Expand-Natives $path $NativesDir }
        }
    }
}

# --- assets --------------------------------------------------------------------
$userAssets = Join-Path $env:APPDATA ".minecraft\assets"
$assetIndex = $vjson.assetIndex.id
$indexPath = Join-Path $userAssets "indexes\$assetIndex.json"
if (-not (Test-Path $indexPath)) {
    Save-File $vjson.assetIndex.url $indexPath | Out-Null
}

# --- command line --------------------------------------------------------------
$cp = ($classpath | Select-Object -Unique) -join ';'
$baseJvm = "-Xmx2G --enable-native-access=ALL-UNNAMED -XX:StackShadowPages=32 -Djava.library.path=`"$NativesDir`" -Dorg.lwjgl.system.SharedLibraryExtractPath=`"$NativesDir\lwjgl`" -Djna.tmpdir=`"$NativesDir\jna`""

if ($Loader -eq "fabric") {
    $joinArgs = if ($McV -ge [version]"1.20") { "--quickPlayMultiplayer localhost:$Port" } elseif ($McV -ge [version]"1.19") { "--server localhost --port $Port" } else { "" }
    # 1.17/1.18: `--server` connects before the model bake and hangs the client; use
    # the mod's property-gated auto-join hook (works in the production jar too).
    $joinJvm = if ($McV -lt [version]"1.19") { "-Dscb.devJoin=localhost:$Port" } else { "" }
    $mainClass = "net.fabricmc.loader.impl.launch.knot.KnotClient"
    $cmd = @"
@echo off
cd /d "$Dir"
"$java" $baseJvm "-DFabricMcEmu= net.minecraft.client.main.Main" $joinJvm -cp "$cp" $mainClass --username Dev --version $Mc --gameDir "$Dir" --assetsDir "$userAssets" --assetIndex $assetIndex --uuid 00000000000000000000000000000000 --accessToken 0 --userType legacy --versionType release $joinArgs
"@
} else {
    # Forge/NeoForge: main class + fml args from the installed version json.
    $subs = @{
        '${natives_directory}' = $NativesDir
        '${launcher_name}' = 'scb-prod'
        '${launcher_version}' = '1.0'
        '${classpath}' = $cp
        '${classpath_separator}' = ';'
        '${library_directory}' = $LibsDir
        '${version_name}' = $versionId
        '${game_directory}' = $Dir
        '${assets_root}' = $userAssets
        '${assets_index_name}' = $assetIndex
        '${auth_player_name}' = 'Dev'
        '${auth_uuid}' = '00000000000000000000000000000000'
        '${auth_access_token}' = '0'
        '${auth_xuid}' = '0'
        '${clientid}' = '0'
        '${user_type}' = 'legacy'
        '${version_type}' = 'release'
    }
    function Resolve-Args($list) {
        $out = @()
        foreach ($a in $list) {
            if ($a -isnot [string]) {
                if (-not (Test-ArgAllowed $a.rules)) { continue }
                $vals = @($a.value)
            } else { $vals = @($a) }
            foreach ($v in $vals) {
                $s = $v
                foreach ($k in $subs.Keys) { $s = $s.Replace($k, $subs[$k]) }
                $out += $s
            }
        }
        return $out
    }
    $fjJvm = @(Resolve-Args $fj.arguments.jvm) | Where-Object { $_ -ne "-cp" -and $_ -notmatch '^\$\{classpath\}$' }
    $fjGame = @(Resolve-Args $fj.arguments.game)
    $mainClass = $fj.mainClass
    $joinArgs = if ($McV -ge [version]"1.20") { "--quickPlayMultiplayer localhost:$Port" } else { "--server localhost --port $Port" }
    $cmd = @"
@echo off
cd /d "$Dir"
"$java" $baseJvm $($fjJvm -join ' ') -cp "$cp" $mainClass --username Dev --version $versionId --gameDir "$Dir" --assetsDir "$userAssets" --assetIndex $assetIndex --uuid 00000000000000000000000000000000 --accessToken 0 --userType legacy --versionType release $joinArgs $($fjGame -join ' ')
"@
}
Set-Content -LiteralPath (Join-Path $Dir "run-client.cmd") -Value $cmd

"prepared $Label in $Dir (downloads: $script:downloads, classpath entries: $($classpath.Count))"
if ($PrepareOnly) { exit 0 }

# --- run the E2E harness against the prod client --------------------------------
$harnessArgs = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "scripts\e2e-legacy.ps1",
    "-Node", $Node, "-ServerJoin", "-ClientCmdFile", (Join-Path $Dir "run-client.cmd"),
    "-RunDirOverride", $Dir, "-Label", $Label, "-ServerMc", $Mc)
if ($LeaveOpen) { $harnessArgs += "-LeaveOpen" }
if (-not $RequireEcho) { $harnessArgs += "-SkipEcho" }
if ($Focus) { $harnessArgs += "-Focus" }
& pwsh @harnessArgs
