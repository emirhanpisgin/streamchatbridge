# StreamChatBridge — implementation plan (from the to-do review)

Companion to `todo.md`. Scope: the StreamChatBridge items of the
"ItemFrames+ & StreamChatBridge" to-do list. ItemFrames+ is covered separately in
`../itemframesplus/TODO-REVIEW.md` and is not touched here.

Decisions already made with the user:
- SCB-1 / SCB-5 / SCB-6 / SCB-7 / SCB-9 and the StreamChatBridge half of T-1 are
  **already done** (evidence in `todo.md`).
- Cap the newest nodes at `>=26.3 <26.4` (T-5).
- Secrets move to `%APPDATA%\streamchatbridge\` (Windows); `config/` keeps only
  non-secret settings.
- CI (T-2/T-3/T-4) is **not planned** for now.
- Template fix (T-1 for ItemFrames+) is done as the last step here, in
  `../mc-multiloader-template`.

## Batches

### Batch 1 — small fixes
- **SCB-4**: strip `§` from incoming usernames and messages before building the
  chat components (`MinecraftChatBridge.appendPart` and the username append).
- **SCB-8**: add `displayTest="IGNORE_ALL_VERSION"` to `META-INF/mods.toml` and
  `META-INF/neoforge.mods.toml`.
- **SCB-12**: remove `events:subscribe` from `KickAuth.SCOPES`.

### Batch 2 — Twitch resilience
- **SCB-2**: persist `expiresAt` in the token file; refresh when < 5 min remain
  and on any 401 from Helix sends; call `/oauth2/validate` at startup and hourly.
- **SCB-3**: track the last received EventSub message; if nothing arrives within
  `keepalive_timeout_seconds` (+ margin), force a reconnect.

### Batch 3 — privacy & logging
- **SCB-10**: move Twitch/Kick token + Kick client secret to
  `%APPDATA%\streamchatbridge\` (owner-only permissions where supported); migrate
  existing `config/` files on first run and delete the old copies.
- **SCB-11**: revoke Twitch (`/oauth2/revoke`) and Kick tokens on logout before
  deleting local files.
- **SCB-13**: replace `System.out/err` with the mod's SLF4J logger; stop printing
  the full Kick authorization URL.
- Update the E2E harnesses (`e2e-legacy.ps1`, `e2e-prod.ps1`) to sync token files
  from the new location.

### Batch 4 — version ranges
- **T-5**: set the three `26.3` nodes to `>=26.3 <26.4`
  (`fabric`, `forge`, `neoforge`) and re-run `audit-boundaries.ps1`.

### Batch 5 — template fix (T-1 for ItemFrames+)
- In `../mc-multiloader-template/build.forge.gradle.kts`: apply
  `net.minecraftforge.renamer` for nodes `< 1.20.6`, map via
  `minecraft.dependency(...).toSrgFile`, and add the mixin-refmap pieces
  (`enableMixinRefmaps` + `org.spongepowered:mixin:0.8.7:processor`) only when the
  mod ships a mixins config. Verify by building one old Forge node in the template
  and checking the jar is SRG-mapped (`getKey` gone, `m_…` present).

## Verification (every batch)

1. Compile the touched nodes.
2. `scripts/validate-jars.ps1` after any `buildAndCollect`.
3. One real-install smoke with `scripts/e2e-prod.ps1` (the "T-2 in every change"
   rule) — e.g. `1.20.6-forge` or `26.3-fabric` when the change touches shared code.
4. Commit per batch; tick `todo.md`.

Final pass: rebuild all 37 jars, validator, prod smoke on `26.3-fabric` +
`1.20.6-forge`, dev E2E with live Twitch echo, update `AGENTS.md`.

## Notes / risks

- SCB-10 changes where secrets live: the test harnesses copy token files between
  run dirs, so they must copy the new app-data paths too or tests lose auth.
- SCB-2/3 touch networking only; no game-version code changes expected, but the
  prod smoke is still cheap insurance.
- SCB-4 is user-visible in every loader: verify with a `§c§l` probe in the dev E2E.
