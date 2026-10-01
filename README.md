# Example Mod — multi-loader Minecraft template

One shared codebase that builds for **Fabric, Forge and NeoForge** across Minecraft
versions, powered by [Stonecutter](https://stonecutter.kikugie.dev/). This template was
extracted from a production mod, so it carries all the build fixes and workarounds that
were needed to make every loader and era compile and run.

Read **[AGENTS.md](AGENTS.md)** for the full operational guide — it covers the node
system, long-running decompile builds, dev-run directories, known traps, and notes for
AI coding agents.

## Requirements

- **JDK 25** to run the Gradle daemon (all nodes)
- Game run JDKs (8 / 17 / 21 / 25, depending on the Minecraft era) are downloaded
  automatically via Gradle toolchains

## Quick start

```powershell
.\gradlew.bat --no-daemon --no-configuration-cache :26.2-fabric:build
.\gradlew.bat --no-daemon --no-configuration-cache :26.1-forge:build
.\gradlew.bat --no-daemon --no-configuration-cache :26.1-neoforge:buildAndCollect
```

`buildAndCollect` copies the jar and sources jar into `build/libs/<mod version>/`.

## Nodes

| Node             | Loader   | Minecraft |
|------------------|----------|-----------|
| `26.2-fabric`    | Fabric   | 26.2      |
| `26.1-forge`     | Forge    | 26.1      |
| `26.1-neoforge`  | NeoForge | 26.1      |

## Creating a new mod from this template

```powershell
.\new-mod.ps1 -Path C:\dev\projects\mymod -ModId mymod -ModName "My Mod" -Package com.example.mymod
```

The script copies the template, renames the mod id / package / class prefixes /
URLs, and initializes a git repository. Optional parameters: `-ClassPrefix`,
`-Author`, `-RepoUrl`, `-ModrinthSlug`, `-NoGit`.

## Adding Minecraft versions

Add a node in `settings.gradle.kts` and a matching section in
`stonecutter.properties.toml`. The checklist (including the NeoForge
`neoforge-moddev-bundle` requirement and the `pack_format` rule) is in
[AGENTS.md section 6](AGENTS.md#6-adding-a-minecraft-version-node).

## Dev runs

```powershell
.\gradlew.bat --no-daemon --no-configuration-cache :26.2-fabric:runClient
.\gradlew.bat --no-daemon --no-configuration-cache :26.1-neoforge:runServer
```

Fabric and Forge share the root `run/` directory; NeoForge nodes use
`versions/<node>/run`. See AGENTS.md section 5 for details.

## Publishing

```powershell
$env:MODRINTH_TOKEN = "<token>"
.\scripts\publish-modrinth.ps1 -Loader neoforge -IncludeSources
```

Dry-run without a token by adding `-DryRun`.

## License

MIT, see [LICENSE](LICENSE).
