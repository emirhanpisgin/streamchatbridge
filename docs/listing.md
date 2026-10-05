# StreamChatBridge — Modrinth listing kit

Everything needed to fill in the Modrinth project page. Update this file when the
feature set changes.

## Description (suggested body)

```
Bridges Minecraft chat with Twitch and Kick.

- Incoming platform chat appears in Minecraft chat (and in the F8 dashboard).
- Messages you type with a prefix are sent to the platform instead of the server.
  Defaults: `!t <message>` for Twitch, `!k <message>` for Kick.
- `/scb status` shows the connection state; `/scb` (or F8) opens the dashboard
  where you can log in/out, pick channels and configure prefixes.

Supported loaders and versions
- Fabric 1.17.1 – 26.3 (requires Fabric API)
- Forge 1.17.1 – 26.3
- NeoForge 1.20.4 – 26.3

The mod is client-side only. It works on any server; the server does not need it.

Extras
- Twitch user colors, badges (broadcaster/mod/VIP/sub/founder), emote-name
  cleanup for Kick, mention highlight + sound, and an ignore list.
- Secrets (tokens, Kick client secret) are stored per user outside `config/`
  (see "What is stored where").
```

## Changelog (template)

```
## 1.0.0
- First multi-loader release: Fabric, Forge and NeoForge, 1.17.1 through 26.3.
- Twitch and Kick chat bridging, outgoing prefixes, dashboard (F8) and commands.
- Twitch token refresh + hourly validation; EventSub keepalive watchdog.
- Kick chat over `java.net.http.WebSocket` (no bundled libraries).
- User colors, badges, mention highlight/sound, ignore list, emote cleanup.
- Secrets moved to a per-user folder; tokens are revoked on logout.
```

## Screenshots

`docs/screenshots/` contains captured dashboard screenshots (with in-game chat
visible behind them). Before publishing, add:
- a GIF of chat arriving in-game (Twitch + Kick),
- a before/after of the F8 dashboard,
- optionally a `/scb status` close-up.

## Kick setup guide (for the listing / wiki)

1. Create an app at the Kick developer portal.
2. Add the redirect URI `http://localhost:17564/kick/callback`.
3. In Minecraft, open the F8 dashboard → **Kick Settings** and paste the app's
   Client ID and Client Secret.
4. Click **Set Up Kick** and finish the browser flow.

Kick chatroom lookup uses Kick's unofficial `kick.com/api/v2/channels/<slug>/chatroom`
endpoint; the ID is cached per channel. If the lookup is blocked, set
`kickChatroomIdOverride` in the config (see below) to the numeric chatroom ID.

## What is stored where

| Data | Location |
|---|---|
| Settings (channels, prefixes, formats, ignore list, chatroom cache) | `config/streamchatbridge.json` |
| Twitch tokens (`accessToken`, `refreshToken`, `expiresAt`) | `%APPDATA%\streamchatbridge\streamchatbridge-twitch.json` (mac: `~/Library/Application Support/streamchatbridge`, Linux: `$XDG_CONFIG_HOME/streamchatbridge` or `~/.config/streamchatbridge`) |
| Kick app credentials + tokens | same folder, `streamchatbridge-kick.json` |

The per-user folder is shared between all Minecraft instances and mod loaders, so
one login works everywhere. Owner-only permissions are applied where the OS
supports POSIX attributes. Logging out revokes the tokens at Twitch/Kick before
deleting the local copies.
