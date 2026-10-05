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
- [ ] **SCB-10 · Secrets out of `config/`** → `%APPDATA%\streamchatbridge\`, with
  migration of existing files.
  *Done when:* `config/` contains no tokens or secrets.
- [ ] **SCB-11 · Revoke tokens on logout** (Twitch `/oauth2/revoke`, Kick revoke).
- [x] **SCB-12 · Drop `events:subscribe`** from Kick scopes (now
  `user:read channel:read chat:write`).
- [ ] **SCB-13 · SLF4J logging** instead of `System.out/err`; no auth-URL printing.
- [ ] **T-5 · Cap newest ranges** at `>=26.3 <26.4` (fabric/forge/neoforge).
  *Done when:* no node has an open-ended range.

## Parked

- [ ] **SCB-14…17 (P2)** — Kick chatroom lookup hardening, emote codes, richer chat
  lines, listing assets (changelog, screenshots, Kick setup guide).
- [ ] **T-2/T-3/T-4 (CI)** — not planned per user decision.
- [ ] **T-1 template part** — done as Batch 5 in `../mc-multiloader-template`
  (helps ItemFrames+; see `../itemframesplus/TODO-REVIEW.md`).
