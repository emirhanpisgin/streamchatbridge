# StreamChatBridge — to-do checklist

Derived from the "ItemFrames+ & StreamChatBridge" list after a review against the
repo. `plan.md` describes how the pending items are implemented.

## Done (verified in this repo)

- [x] **SCB-1 · Kick chat via `java.net.http.WebSocket`** — `KickChatClient` speaks
  the Pusher protocol directly (`pusher:subscribe`, `pusher:ping`→pong, reconnect
  backoff); no `include()` / Java-WebSocket anywhere.
- [x] **SCB-5 · Fabric API dependency on Modrinth** — `publish-modrinth.ps1` adds
  the required Fabric API dependency to every Fabric version.
- [x] **SCB-6 · Version matrix** — 37 nodes, floors 1.17.1 Fabric/Forge + 1.20.4
  NeoForge, newest 26.3; all E2E + prod-smoke verified. Plain daemon threads (no
  virtual threads).
- [x] **SCB-7 · Core/loader split** — `twitch/`, `kick/`, `config/` have no loader
  imports; glue lives in per-loader client classes + `Scb*` shims.
- [x] **SCB-9 · UI version calls centralized** — screens have ~0 `//? if` blocks;
  era code lives in `ScbScreen`/`ScbGui`/`ScbButton`/`ScbScreens`.
- [x] **T-1 (StreamChatBridge half) · Forge reobf** — `net.minecraftforge.renamer`
  applied for `< 1.20.6`; jars verified SRG-mapped and prod-smoke tested on
  1.17.1/1.18/1.20 (note: effective threshold is 1.20.6, Forge has no 1.20.5).

## Pending — being implemented now

- [x] **SCB-4 · Strip `§` from incoming names/messages.** Verified live: a Twitch
  message containing `§c§l` rendered/echoed as `scb-sectest-cl-end` (no `§`).
- [x] **SCB-2 · Twitch token expiry.** `expiresAt` is persisted, refreshed within
  5 min of expiry and on any 401 (sends + EventSub subscribe retry),
  `/oauth2/validate` runs at startup and hourly. Verified live: a token file
  without `expiresAt` was refreshed and rewritten at startup, echo still passed.
  *Done when:* a long session keeps sending/receiving without restart.
- [x] **SCB-3 · Detect dead Twitch connections.** A per-connection watchdog forces a
  reconnect when nothing arrives within `keepalive_timeout_seconds` + 15 s
  (Twitch keepalives otherwise reset the timer). Verified by compile + code path;
  live Wi-Fi-toggle test is user-assisted.
  *Done when:* toggling Wi-Fi mid-session recovers within ~30 s.
- [x] **SCB-8 · `displayTest="IGNORE_ALL_VERSION"`** in both mods.toml files
  (verified present in the collected Forge jar).
  *Done when:* putting the jar on a server produces no version warning.
- [x] **SCB-10 · Secrets out of `config/`** → `%APPDATA%\streamchatbridge\` (mac:
  `~/Library/Application Support`, Linux: `$XDG_CONFIG_HOME`), owner-only perms
  where supported, legacy files migrated automatically. Verified live: `config/`
  keeps only `streamchatbridge.json`; both secret files moved; auth + echo pass.
  *Done when:* `config/` contains no tokens or secrets.
- [x] **SCB-11 · Revoke tokens on logout** (Twitch `/oauth2/revoke`, Kick
  `/oauth/revoke`, best-effort before deleting local copies).
- [x] **SCB-12 · Drop `events:subscribe`** from Kick scopes (now
  `user:read channel:read chat:write`).
- [x] **SCB-13 · Proper logging** — all 102 `System.out/err` sites now use the
  mod's logger; the full Kick authorization URL is no longer printed.
- [x] **T-5 · Cap newest ranges** at `>=26.3 <26.4` (fabric/forge/neoforge);
  boundary audit still passes.
  *Done when:* no node has an open-ended range.

## Parked

- [x] **SCB-14 · Kick chatroom lookup hardened** — cached per channel in the
  config, 3 retries with backoff, `kickChatroomIdOverride` for manual entry, and
  the failure log points at the override; callers already show the in-game error.
  E2E: second run connected with **no lookup call** (cache hit, config now has
  `kickChatroomId`/`kickChatroomChannel`).
- [x] **SCB-15 · Kick emote codes cleaned** — `[emote:ID:NAME]` renders as `NAME`.
  E2E: regex verified with the exact Java pattern (`hi [emote:123:KEKW] bye
  [emote:9:catJAM]` -> `hi KEKW bye catJAM`); live outgoing markup is blocked by
  Kick itself (HTTP 403), so this is incoming-only by design.
- [x] **SCB-16 · Richer chat lines** — Twitch/Kick user colors, badges
  (broadcaster/mod/vip/sub/founder), ignore list, mention highlight + sound
  (config toggles: `showUserColors`, `showBadges`, `highlightMentions`,
  `mentionSound`, `ignoredUsers`). E2E: live Kick echo rendered the broadcaster
  badge (`[Kick] [broadcaster] kryparnold: ...`), mention path exercised with
  "Dev"; ignore list verified live (send succeeded, echo suppressed for both
  platforms, case-insensitive); Twitch echo PASS with the new pipeline.
- [x] **SCB-17 · Listing kit** — `docs/listing.md` (description, changelog
  template, Kick app setup, "what is stored where") plus dashboard screenshots in
  `docs/screenshots/`; still to record before publishing: a chat GIF and a
  before/after dashboard GIF.
- [ ] **T-2/T-3/T-4 (CI)** — not planned per user decision.
- [x] **T-1 template part** — done: `mc-multiloader-template` now applies the
  renamer for `<1.20.6`, wires mixin refmaps when a mixins config exists, and its
  Forge `requiredJava` maps 1.17 to Java 17. (Changes left uncommitted in the
  template repo for you to review; ItemFrames+ stays untouched - see
  `../itemframesplus/TODO-REVIEW.md`.)
