param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)][string]$ModId,
    [Parameter(Mandatory = $true)][string]$ModName,
    [Parameter(Mandatory = $true)][string]$Package,
    [string]$ClassPrefix = "",
    [string]$Author = "Your Name",
    [string]$RepoUrl = "",
    [string]$ModrinthSlug = "",
    [switch]$NoGit
)

$ErrorActionPreference = "Stop"

if ($ModId -notmatch '^[a-z][a-z0-9_]*$') { throw "ModId must be lowercase letters/digits/underscores, starting with a letter." }
if ($Package -notmatch '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$') { throw "Package must be a dotted lowercase package path, e.g. com.example.mymod" }
if (-not $ClassPrefix) {
    $ClassPrefix = ($ModId -split '_' | ForEach-Object { $_.Substring(0, 1).ToUpper() + $_.Substring(1) }) -join ''
}
if (-not $ModrinthSlug) { $ModrinthSlug = $ModId }
if (-not $RepoUrl) { $RepoUrl = "https://github.com/yourname/$ModId" }

$template = $PSScriptRoot
if (Test-Path $Path) { throw "Target already exists: $Path" }
New-Item -ItemType Directory -Path $Path | Out-Null

$exclude = @('.git', 'build', '.gradle', '.kotlin', '.idea', 'run')
Get-ChildItem $template -Force | Where-Object { $exclude -notcontains $_.Name } | ForEach-Object {
    Copy-Item $_.FullName -Destination $Path -Recurse -Force
}
$generated = @(
    "$Path\build", "$Path\.gradle", "$Path\.kotlin", "$Path\run",
    "$Path\buildSrc\build", "$Path\buildSrc\.gradle", "$Path\buildSrc\.kotlin"
)
Get-ChildItem "$Path\versions" -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    $generated += "$($_.FullName)\build", "$($_.FullName)\.gradle", "$($_.FullName)\run"
}
foreach ($g in $generated) {
    if (Test-Path $g) { Remove-Item $g -Recurse -Force }
}

$group = $Package.Substring(0, $Package.LastIndexOf('.'))
$replacements = [System.Collections.Specialized.OrderedDictionary]::new([System.StringComparer]::Ordinal)
$replacements.Add('com.example.examplemod', $Package)
$replacements.Add('com.example', $group)
$replacements.Add('ExampleMod', $ClassPrefix)
$replacements.Add('Example Mod', $ModName)
$replacements.Add('examplemod', $ModId)
$replacements.Add('Your Name', $Author)
$replacements.Add('https://github.com/yourname/examplemod', $RepoUrl)
$replacements.Add('https://modrinth.com/mod/examplemod', "https://modrinth.com/mod/$ModrinthSlug")

$textExtensions = @('.kts', '.kt', '.toml', '.json', '.md', '.ps1', '.java', '.properties', '.mcmeta', '.txt', '.yml', '.yaml', '.xml')
Get-ChildItem $Path -Recurse -File -Force | Where-Object {
    $textExtensions -contains $_.Extension -or $_.Name -eq '.gitignore'
} | ForEach-Object {
    $content = [string](Get-Content -LiteralPath $_.FullName -Raw)
    $updated = $content
    foreach ($key in $replacements.Keys) { $updated = $updated.Replace($key, $replacements[$key]) }
    if ($updated -ne $content) { Set-Content -LiteralPath $_.FullName -Value $updated -NoNewline -Encoding utf8 }
}

Get-ChildItem $Path -Recurse -File -Force | Where-Object { $_.FullName -notmatch '\\\.git\\' } | ForEach-Object {
    $newName = $_.Name
    foreach ($key in $replacements.Keys) { $newName = $newName.Replace($key, $replacements[$key]) }
    if ($newName -ne $_.Name) { Rename-Item -LiteralPath $_.FullName -NewName $newName }
}

$packagePath = $Package -replace '\.', '/'
$oldPkg = "$Path\src\main\java\com\example\examplemod"
$newPkg = "$Path\src\main\java\$packagePath"
if (Test-Path $oldPkg) {
    New-Item -ItemType Directory -Force -Path (Split-Path $newPkg) | Out-Null
    Move-Item $oldPkg $newPkg
    Get-ChildItem "$Path\src\main\java" -Directory -Recurse -Force |
        Sort-Object { $_.FullName.Length } -Descending |
        Where-Object { (Get-ChildItem $_.FullName -Force | Measure-Object).Count -eq 0 } |
        Remove-Item -Force -Recurse
}

$oldAssets = "$Path\src\main\resources\assets\examplemod"
$newAssets = "$Path\src\main\resources\assets\$ModId"
if (Test-Path $oldAssets) { Move-Item $oldAssets $newAssets }

if (-not $NoGit) {
    Push-Location $Path
    git init | Out-Null
    git add -A
    git commit -m "Initial commit for $ModName" | Out-Null
    Pop-Location
}

"Created $ModName in $Path"
"  mod id:   $ModId"
"  package:  $Package"
"  classes:  ${ClassPrefix}*"
"  repo:     $RepoUrl"
"  modrinth: https://modrinth.com/mod/$ModrinthSlug"
""
"Next: cd `"$Path`"; .\gradlew.bat --no-daemon --no-configuration-cache :26.2-fabric:build"
