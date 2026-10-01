# AGENTS.md — working on this repository with an AI agent

This repository is a **Stonecutter-based multi-loader Minecraft mod template**.
One shared codebase builds for **Fabric, Forge and NeoForge**, each at one Minecraft
version node. Read this file before changing anything — it explains the moving parts,
the long-running commands, and the traps that cost real time to discover.

---

## 1. Layout

```
src/main/java/<package>/        shared sources (Stonecutter comment directives)
src/main/resources/             shared resources (fabric.mod.json, mods.toml, ...)
build.fabric.gradle.kts         per-loader build script (Fabric / Loom)
build.forge.gradle.kts          per-loader build script (Forge / ForgeGradle 7)
build.neoforge.gradle.kts       per-loader build script (NeoForge / ModDevGradle)
buildSrc/                       Gradle plugins: forge-mutex, neoforge-mutex
settings.gradle.kts             Stonecutter setup + node list
stonecutter.gradle.kts          active node + per-node source override wiring
stonecutter.properties.toml     mod identity + per-node loader pins/ranges/pack formats
scripts/publish-modrinth.ps1    Modrinth release helper
new-mod.ps1                     scaffolding: copy this template into a new mod
```

Nodes currently declared (one per loader, newest Minecraft):

| Node             | Loader   | Minecraft | Loader pin       |
|------------------|----------|-----------|------------------|
| `26.2-fabric`    | Fabric   | 26.2      | Fabric API 0.156 |
| `26.1-forge`     | Forge    | 26.1      | Forge 62.0.9     |
| `26.1-neoforge`  | NeoForge | 26.1      | NeoForge 26.1.0.19-beta |

---

## 2. Build commands

```powershell
# Convention for every Gradle invocation in this repo:
#   --no-daemon --no-configuration-cache
# The daemon flag avoids stale-plugin issues; the config cache is not safe with
# Loom/ForgeGradle/ModDevGradle.
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.2"   # daemon JVM (all nodes)

.\gradlew.bat --no-daemon --no-configuration-cache :26.2-fabric:compileJava
.\gradlew.bat --no-daemon --no-configuration-cache :26.1-forge:build
.\gradlew.bat --no-daemon --no-configuration-cache :26.1-neoforge:buildAndCollect
```

- `buildAndCollect` copies `jar` + `sourcesJar` into `build/libs/<mod version>/`.
- Game runs use per-era JDKs automatically (Gradle toolchains, foojay downloads them):
  Java 8 for <1.17, 17 for 1.18–1.20.4, 21 for 1.20.5–1.21.x, 25 for 26.x.
  Do **not** try to run the game with the Gradle daemon's JVM; the build scripts
  already select the right launcher.
- One representative node compiles in ~30–60 s once its Minecraft artifacts are cached.

---

## 3. Stonecutter in 30 seconds

Shared sources contain version/loader directives:

```java
//? if fabric {
import net.fabricmc.api.ModInitializer;
//?}

//? if forge && >=1.21.6 {
/*...*/
//?}
```

- The **active node** (`stonecutter active "<node>"` in `stonecutter.gradle.kts`)
  decides which branches are un-commented in the working tree.
- `vcsVersion` in `settings.gradle.kts` is the state Git should see at commit time.
- Switch the active node (this rewrites comment states across `src/`):

```powershell
.\gradlew.bat --no-daemon --no-configuration-cache "Set active project to 26.1-forge"
.\gradlew.bat --no-daemon --no-configuration-cache "Reset active project"   # back to 26.2-fabric
.\gradlew.bat --no-daemon --no-configuration-cache "Refresh active project" # fix comment states
```

**When editing shared code**: edit the *active* state, then run `Refresh active project`
if comments end up in a wrong state. Never leave `src/` in a mixed state before committing —
run `Reset active project` first.
- Per-node source overrides are possible: files under `versions/<node>/src/main/java`
  replace the shared file of the same path. `stonecutter.gradle.kts` excludes the shared
  copy for that node to avoid duplicate classes. (No overrides exist right now.)

---

## 4. Long-running commands, "stuck" builds, timestamps (read this)

### 4.1 First build of a node is a decompile

The first time a node is built, its Minecraft artifacts must be produced:

| Loader   | Pipeline                        | Typical first run | Cache location |
|----------|---------------------------------|-------------------|----------------|
| Fabric   | Loom (yarn + remap)             | 1–3 min           | `~/.gradle/caches/fabric-loom` |
| Forge    | ForgeGradle 7 mavenizer         | 3–10 min          | `~/.gradle/caches/minecraftforge` |
| NeoForge | NeoFormRuntime (vineflower/jst) | 5–15 min          | `<gradle home>/caches/neoformruntime` |

Later builds of the same node reuse the cache and take seconds. Pre-warm every node
in the background with `scripts/launch-mavenize-queue.cmd` (see section 10) so later
builds never wait on a decompile.

### 4.2 Recognizing progress vs. a real stall

Progress markers to look for in the output:

- NeoForge: `*** Started working on decompile`, `Running external tool org.vineflower...`,
  `✓ Completed decompile in 36.58s`, `Compiling 6882 source files`, `Total runtime:`
- Forge: `Minecraft Maven is up-to-date`, `Downloading library at:`, task names like
  `createMcp2Srg`, `applyPatches`, `setupLayers`
- Fabric: `Fabric Loom: 1.17.21`, mapping downloads, `:configureClient`
- Gradle: `> Task :<node>:createMinecraftArtifacts`

Signs of an actual stall:

- No new output for **5+ minutes** during a phase that normally prints progress.
- `Waiting for lock on <resource>` for minutes → **another Gradle build is running**
  on this project. Never run two builds at once (see 4.4). Wait, or kill the other one.
- `FAILED TO BIND TO PORT` on a dev server → port 25565 is already taken by another
  running dev server; stop it first.

### 4.3 Running long commands from an agent safely

Prefer detaching the build and polling a log with timestamps instead of blocking on a
single shell call that may time out:

```powershell
$p = Start-Process -FilePath ".\gradlew.bat" -PassThru -WindowStyle Hidden `
     -ArgumentList @("--no-daemon","--no-configuration-cache",":26.1-neoforge:compileJava","--console=plain") `
     -RedirectStandardOutput "build-out.log" -RedirectStandardError "build-err.log"
while (-not $p.HasExited) {
    Start-Sleep -Seconds 10
    "{0} {1}" -f (Get-Date -Format HH:mm:ss), (Get-Content build-out.log -Tail 1)
}
```

Add timestamps to any stream when the tool output has none (Gradle `--console=plain`
does not print timestamps):

```powershell
& .\gradlew.bat --no-daemon --no-configuration-cache :26.1-forge:build --console=plain 2>&1 |
  ForEach-Object { "{0} {1}" -f (Get-Date -Format HH:mm:ss), $_ } |
  Tee-Object -FilePath build.log
```

Game logs already carry timestamps (`[HH:mm:ss] [thread/LEVEL] [logger]: message`) and
are written to the run directory's `logs/latest.log` (+ `debug.log` at DEBUG level).

### 4.4 Parallelism, mutexes and memory

- `gradle.properties` sets `org.gradle.jvmargs=-Xmx4G` and `org.gradle.parallel=true`.
  A single build can therefore run several tasks at once and peak around **~5 GB**
  (Gradle daemon + Kotlin daemon for `buildSrc` + decompiler JVMs). This is normal.
- **Every build configures every node** in the project. That is why building
  `:26.2-fabric:build` can print unrelated work like a Forge mavenizer check —
  it is configuration, not your task.
- `forge-mutex` and `neoforge-mutex` (in `buildSrc/`) are Gradle build services that
  serialize Minecraft recompilation (`createMinecraftArtifacts`, `createMcp2Srg`, ...)
  **within one build**. They do NOT protect against two separate Gradle processes —
  do not start a second build while one is running.
- Avoid running an IDE Gradle sync and a CLI build at the same time for the same reason.

### 4.5 Killing and retrying

```powershell
taskkill /PID <gradle-or-java-pid> /T /F
```

- Killing a build mid-decompile is safe; the next run resumes from its caches.
- If a decompile cache is genuinely corrupt (rare), delete the node's
  `build/tmp/neoformruntime` (NeoForge) or the shared `caches/neoformruntime`
  in the Gradle home — this forces a full re-decompile (5–15 min).

---

## 5. Dev runs

```powershell
.\gradlew.bat --no-daemon --no-configuration-cache :26.2-fabric:runClient
.\gradlew.bat --no-daemon --no-configuration-cache :26.1-forge:runServer
```

- **Run directories**: Fabric and Forge share the root `run/`; NeoForge (ModDevGradle)
  uses `versions/<node>/run`. `server.properties` persists per run directory —
  set `online-mode=false`, `enable-rcon=true` etc. for local testing.
- **NeoForge without Gradle**: `gradlew :<node>:createLaunchScripts` generates
  `versions/<node>/build/moddev/runClient.cmd` / `runServer.cmd`. Use these to run
  server and client simultaneously (no Gradle lock contention).
- **Forge mixins in dev** are registered via a `--mixin.config=<modid>.mixins.json`
  argument in `build.forge.gradle.kts`; production uses the `MixinConfigs` jar manifest.
- **NeoForge mixins** are declared in `META-INF/neoforge.mods.toml` (`[[mixins]]`);
  for 1.20.4 (and only 1.20.4) the file must be named `META-INF/mods.toml` —
  `build.neoforge.gradle.kts` renames it automatically for that node.
- **Fabric mixins** are declared in `fabric.mod.json`.
- A dev server that fails with `FAILED TO BIND TO PORT` means another dev server is
  still running — find and stop it before retrying.

---

## 6. Adding a Minecraft version node

1. `settings.gradle.kts`: add `match("<mc>", "<loader>")` (or
   `match("<project>", "<loader>", version = "<mc>")` for a different build target).
2. `stonecutter.properties.toml`: add a `["<mc>"]` section and a
   `[<loader>."<mc>"]` section with:
   - `mod.mc_compat` — the version range the jar claims (e.g. `>=1.21.6 <1.21.7`)
   - loader pin (`deps.fabric_api`, `deps.forge_loader` + `deps.forge_fml`,
     `deps.neo_loader`)
   - `pack_format` — **the resource pack format of the lowest Minecraft version in
     the range**. It must never exceed any covered client's format or the game shows
     raw translation keys. Extract it from the client jar's `version.json`
     (`pack_version.resource` / `resource_major`).
3. NeoForge pins **must** publish Gradle module metadata with the
   `neoforge-moddev-bundle` capability, or ModDevGradle cannot resolve them:
   `https://maven.neoforged.net/releases/net/neoforged/neoforge/<ver>/neoforge-<ver>.module`
   must contain `"neoforge-moddev-bundle"`. (The whole 20.2.x/20.3.x/20.5.x lines
   publish no `.module` file at all and cannot be used.)
4. Run `gradlew :<node>:compileJava` and fix era-specific branches with
   `//? if` conditions. The existing sources/build scripts already demonstrate most
   era splits (see section 7).
5. Build: `gradlew :<node>:buildAndCollect`.

Removing a node: delete its `match(...)` line and its properties sections, then delete
the `versions/<node>` directory. Nothing else references nodes by name.

---

## 7. Era splits already handled (do not rediscover)

- **NeoForge metadata**: 20.4 uses `META-INF/mods.toml` (FML 2.x), 20.5+ uses
  `META-INF/neoforge.mods.toml`; `license` must be a **root-level** TOML key.
- **NeoForge dev runs** need the mod registered in `neoForge { mods { ... } }`
  (`build.neoforge.gradle.kts`) or the mod silently never loads.
- **NeoForge `EventBusSubscriber`**: 20.4 → nested `Mod.EventBusSubscriber`;
  20.5–1.21.5 → `bus = Bus.GAME`; 1.21.6+ → no `bus` parameter.
- **NeoForge networking**: `PacketDistributor.sendToServer` exists from 20.5 up to
  21.6; from **21.7** it moved to `ClientPacketDistributor.sendToServer`.
- **MC 1.21.11** renamed `ResourceLocation` to `Identifier`.
- **Forge**: `[[mixins]]` in `mods.toml` is NOT read — use the `MixinConfigs`
  manifest attribute (production) and `--mixin.config` (dev).
- **Forge 1.20.2/1.20.3** dev runs need the legacy `MOD_CLASSES` environment variable;
  **Forge 1.20.4+** needs resources merged into the classes output directory
  (both handled in `build.forge.gradle.kts`).
- **ForgeGradle 7** recompiles Minecraft with the JVM running the build. Some old
  nodes need a specific session JDK — declare `session_jdk = "<major>"` in the node's
  `stonecutter.properties.toml` section; the build script fails fast if mismatched.
- **Forge < 1.17** needs `-noverify` and `launchwrapper` (handled).
- **SecureModules 2.2.24** is forced for Forge 1.21.1–1.21.6 to avoid dev-run
  Guava classloading crashes (handled).

---

## 8. Publishing

```powershell
$env:MODRINTH_TOKEN = "<token>"
.\scripts\publish-modrinth.ps1 -Loader neoforge -IncludeSources            # real publish
.\scripts\publish-modrinth.ps1 -Loader fabric -DryRun                     # no token needed
```

- Reads jars from `build/libs/<Version>/`; version/loader/game lists are defined in
  the script and must stay in sync with `stonecutter.properties.toml`.
- Modrinth edits (e.g. changing loaders) must use the **v3** route
  (`PATCH https://api.modrinth.com/v3/version/{id}`); v2 rejects them.

---

## 9. Scaffolding a new mod from this template

```powershell
.\new-mod.ps1 -Path C:\dev\projects\mymod -ModId mymod -ModName "My Mod" -Package com.example.mymod
```

Copies the template, renames package/module/class prefixes, updates URLs, and runs
`git init`. See `README.md` for all parameters.

---

## 10. Gradual version development (finding era boundaries)

How to support a wide range of Minecraft versions without guessing which ones need
their own compilation target. Start at the bottom and walk upward; the code is
shared the whole time.

1. **Pick the floor** — the lowest Minecraft version the mod should support
   (e.g. `1.16.5` for Forge, `1.20.4` for NeoForge). Add it as a permanent node
   (section 6) and make the mod work there. This node is the first era boundary.
2. **Walk upward one version at a time.** For the next Minecraft version, add a
   *temporary* node for the same loader and run the same shared code on it.
3. **Pass** → delete the temporary node. The version is covered by the current
   permanent node's range; continue with the next version.
4. **Fail** → the version needs its own compilation target. Keep the node as a new
   *permanent* era boundary, close the previous permanent node's range just below it
   (`>=prev <this`), add the required `//? if` era branches to the shared code, and
   continue from this node.
5. **Gap** — no usable loader build for that version (e.g. NeoForge 1.20.5) →
   close the current range below the gap; the next testable version starts a new node.
6. Stop at the newest version (or wherever the user says). The last permanent node
   keeps an open range (`>=X`).

Rules of the road:

- One version at a time, one loader at a time. Gradle project locks do not allow
  concurrent builds or dev runs.
- Pre-warm decompiles with `scripts/launch-mavenize-queue.cmd` so each version's
  build is minutes instead of tens of minutes.
- Commit after every accepted boundary (settings + properties + code changes).
- Human-test the floor, every permanent boundary, and ideally the newest version of
  each range — those are what ship.
- A passing temporary node proves the *source* compiles and runs on that version.
  It does not prove the boundary node's built jar runs there; if that matters for
  the mod, verify the final ranges with a prod-style check of the built jar.
- The bookkeeping (adding/removing temporary nodes, closing ranges) is mechanical
  and can be automated later; until then do it by hand.

---

## 11. Notes for AI agents executing commands

- Prefer **detached process + log polling with timestamps** for anything that can take
  minutes (see 4.3). Don't assume a quiet terminal means a hang.
- Give long commands generous timeouts; decompiles regularly take 5–15 minutes on a
  cold cache.
- Never start a second Gradle build (or IDE sync) while one is running — lock waits
  look like hangs.
- Before committing, run `Reset active project` so the committed sources match
  `vcsVersion`, and check `git status` for unexpected comment-state churn in `src/`.
- When a build fails, read the first `error:`/`Caused by:` in the log, not the last —
  cascading errors follow.
