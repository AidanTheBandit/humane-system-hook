# Hook configuration — music

The music hooks add real playback to the Ai Pin by replacing the dead Tidal path:
"play <song>" is recognized natively (no cloud), and audio is streamed through the
Pin's own ExoPlayer from NewPipe/PipePipe (YouTube / YouTube Music / SoundCloud) or a
bundled librespot (Spotify). Configuration lives in JSON files on the device's
`/sdcard`, read at runtime by the hook — no value is compiled into the APK, and
credentials/tokens are read from the device at runtime only.

| File | Purpose |
|------|---------|
| `/sdcard/aipin_voice_config.json` | Music source/quality + radio toggle |
| `/sdcard/aipin_spotify.json` | Spotify API credentials + tokens |
| `/sdcard/aipin_playlists.json` | Imported playlists |
| `/sdcard/aipin_ytm.json` | YouTube Music account cookie (personalized radio) |

Toggles are read through [`VoiceConfig.kt`](src/main/kotlin/com/penumbraos/hook/VoiceConfig.kt),
which caches the file with a 2s TTL. A missing file or key falls back to the listed default.

---

## 1. `aipin_voice_config.json` — music toggles

```json
{
  "music_source":   "youtube",
  "stream_quality": 2,
  "radio_autoplay": true
}
```

| Key | Type | Default | Effect | Settable by voice? |
|-----|------|---------|--------|--------------------|
| `music_source` | string | `"youtube"` | Search/playback backend: `youtube`, `ytmusic`, `soundcloud`, `spotify`. | **Yes** — see voice commands below |
| `stream_quality` | int 0–2 | `2` | Audio bitrate for NewPipe/PipePipe streams: `0`=low (fastest), `1`=medium, `2`=high. | No — edit file |
| `radio_autoplay` | bool | `true` | After a requested song ends, continue into a radio of similar tracks (YouTube Music radio when available — see §4). | No — edit file |

> This build ships the **music** hooks only. The `announce_caller`, `announce_text`,
> and `auto_listen` keys some tooling also writes here are parsed but have no effect.

---

## 2. `aipin_spotify.json` — Spotify (API key + auth)

Spotify uses **your own** Spotify Developer app for the Web API (search, device, play,
now-playing). Audio itself is streamed by a bundled **librespot**; the Web API only
controls it. Set `music_source` to `spotify` (by voice or config) to use it.

```json
{
  "client_id":     "<your Spotify app client id>",
  "client_secret": "<your Spotify app client secret>",
  "refresh_token": "<OAuth refresh token>",
  "access_token":  "<filled in / refreshed automatically>",

  "name":    "Ai Pin",
  "bitrate":  320,
  "cache":   "/sdcard/aipin_spotify_cache",
  "binary":  ""
}
```

**Required keys** (Web API auth):

| Key | Notes |
|-----|-------|
| `client_id` / `client_secret` | From your Spotify Developer app |
| `refresh_token` | From a one-time OAuth authorization-code flow (Premium required) |
| `access_token` | Filled/refreshed automatically by the hook |

**Optional librespot keys** (audio engine — [`SpotifyPlayer.kt`](src/main/kotlin/com/penumbraos/hook/SpotifyPlayer.kt); all have defaults):

| Key | Default | Notes |
|-----|---------|-------|
| `name` | `"Ai Pin"` | Spotify Connect device name librespot advertises |
| `bitrate` | `320` | librespot stream bitrate (kbps): 96 / 160 / 320 |
| `cache` | `/sdcard/aipin_spotify_cache` | librespot cache dir (auto-created) |
| `binary` | — | Absolute path to a cross-compiled arm64 **librespot** binary in an exec-capable dir. See note below. |

> **librespot binary required for Spotify.** The Spotify audio engine execs a native
> `librespot` binary. This build does **not** ship one. To enable Spotify, either drop a
> cross-compiled arm64 `libspotify.so` into `hook/src/main/jniLibs/arm64-v8a/` before
> building (Android extracts it, executable, to the hook's native lib dir where
> [`SpotifyPlayer`](src/main/kotlin/com/penumbraos/hook/SpotifyPlayer.kt) looks for it), or
> push a `librespot` binary to the device and point the `binary` key at it. Without it,
> Spotify logs an error and does nothing — `music_source` stays on YouTube/SoundCloud, which
> need no binary.

**Setup (one-time):**
1. Create an app at the Spotify Developer Dashboard → copy its **Client ID** and **Client Secret**.
2. Complete the OAuth **authorization-code** flow once to obtain a **refresh token**.
   Premium is required for playback control.
3. Write all four keys to `/sdcard/aipin_spotify.json`.

**How auth works at runtime** ([`SpotifyApi.kt`](src/main/kotlin/com/penumbraos/hook/SpotifyApi.kt)):
- Requests use the `access_token` as a Bearer token.
- On a `401`, the hook POSTs to `https://accounts.spotify.com/api/token` with
  `grant_type=refresh_token` (HTTP Basic auth of `client_id:client_secret`), gets a fresh
  `access_token`, **writes it back** to the file, and retries the request once.
- Both the ironman process (`SpotifyControl`) and the music process (`MusicHooks`) read the
  file independently, so token refresh is shared via the file.

---

## 3. `aipin_playlists.json` — playlists

Imported playlists, played by voice: `"play <name> playlist"`. The hook
([`MusicHooks.loadPlaylist`](src/main/kotlin/com/penumbraos/hook/MusicHooks.kt))
matches the spoken name against the keys (case-insensitive, with substring fallback)
and plays the tracks; each track is resolved lazily via search at play time.

**Format** — a map of `playlist name → array of tracks`, where each track is
`{ "t": <title>, "a": <artist> }`:

```json
{
  "Workout": [
    { "t": "Stronger", "a": "Kanye West" },
    { "t": "Till I Collapse", "a": "Eminem" }
  ],
  "Chill": [
    { "t": "Redbone", "a": "Childish Gambino" }
  ]
}
```

The hook only *reads* this file — write it by hand or with your own export tooling.

---

## 4. `aipin_ytm.json` — YouTube Music account cookie (personalized radio)

When `radio_autoplay` is on, starting a song continues into a **YouTube Music radio**
(the same "start radio" queue the real app builds) via
[`ytm-kt`](https://gitlab.com/syk.sh/ytm-kt) running in-process
([`YtmRadio.kt`](src/main/kotlin/com/penumbraos/hook/YtmRadio.kt)). Song radio works
**without any config**. To get radio/recommendations personalized to your account, log
in with your browser cookie:

```json
{
  "cookie":     "<full Cookie header from music.youtube.com>",
  "authuser":   "0",
  "channel_id": "",
  "radio_limit": 25
}
```

| Key | Required | Notes |
|-----|----------|-------|
| `cookie` | for auth | The full `Cookie` request header from a logged-in `music.youtube.com` session. Must contain `SAPISID` (or `__Secure-3PAPISID`). |
| `authuser` | optional | `X-Goog-AuthUser` account index (default `"0"`); use `"1"`, `"2"`, … for secondary Google accounts. |
| `channel_id` | optional | Your own channel id, if known. |
| `radio_limit` | optional | Max radio tracks to queue (default 25). |

**How to get the cookie:**
1. On a desktop browser, log into `https://music.youtube.com`.
2. Open DevTools → **Network**, then click any track so a request to
   `/youtubei/v1/…` appears.
3. Select that request → **Headers** → copy the value of the `cookie` request header.
4. Paste it as `"cookie"` in `/sdcard/aipin_ytm.json`.

You only need the cookie — the hook derives the time-based `Authorization` (`SAPISIDHASH`)
from it per request ([`YtmAuth.kt`](src/main/kotlin/com/penumbraos/hook/YtmAuth.kt)), so it
keeps working without re-copying headers. If auth is missing or fails, radio falls back to
unauthenticated song radio, and then to NewPipe related tracks.

---

## Voice commands

Spoken commands are recognized natively (regex, no cloud) and intercepted before they reach
the device's dead SYNAPSE/LLM path. Music commands live in
[`MusicIntentHook.kt`](src/main/kotlin/com/penumbraos/hook/MusicIntentHook.kt).

### Switch music source (settable by voice)
> "switch to spotify" · "change source to soundcloud" · "use youtube music" · "go to youtube"

Writes `music_source` on-device and confirms aloud ("Switched music to SoundCloud."). No app needed.

### Play
> "play <song>" · "play songs by <artist>" · "play music" (top-hits radio)
> "play radio of <song>" · "play more like this" / "play similar"

### Play a playlist
Plays an imported playlist from `aipin_playlists.json` (see §3). Accepted phrasings:
> "play <name> playlist" · "play my <name> playlist" · "start the <name> playlist"
> "put on <name> playlist" · "play playlist <name>"

Checked **before** song-name matching, so "play workout playlist" is never treated as a track search.

### Playback controls
> pause · resume / continue · next / skip · previous / go back
> repeat one / repeat all / repeat off · shuffle on / shuffle off
