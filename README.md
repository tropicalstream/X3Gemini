# X3Gemini

> ## ⚠️ Experimental beta — not for consequential use
> This is an **unofficial, community-built, work-in-progress** app, not a
> RayNeo/Mercury product. It is provided for **testing and feedback only**.
>
> - **Do not rely on it for anything consequential** — medication, appointments,
>   safety-critical reminders, or any situation where a missed/incorrect
>   response would cause real harm or loss. Reminders can fail to fire (OS
>   killing the process, alarm-permission changes, clock drift); memory and
>   custom instructions can be mis-applied by the model; voice transcription
>   can mishear you.
> - Expect bugs, missing features, and breaking changes between commits.
> - It talks directly to Google's Gemini API using **your own API key** — you
>   are responsible for your own usage and any costs on your account.
> - No warranty of any kind. Use at your own risk, and verify anything
>   important through another source.

A minimal **Gemini Live voice HUD** for the RayNeo X3 Pro AR glasses, carved out
of TapInsight's unipanel lineage. One Activity, one foreground Service, **no
browser** — under the HUD strip is black space (transparent on the waveguide),
shared by the pin board and the camera preview.

- **Model:** `gemini-2.5-flash-native-audio-preview-12-2025`, hardcoded. Default voice.
- **Barge-in:** talk over Gemini; queued audio is flushed the instant the server signals an interruption.
- **Auto-end:** the session closes after **5 seconds of mutual silence**.
- **No login, no companion app:** the API key is pushed over **adb** (below).
- **No chat persistence:** history lives in RAM only (enough for follow-ups while running). Pins (notes / pictures / live cards) persist.

> 🔑 **Your Gemini API key is never stored in this repo.** It's pushed to the
> device over adb at runtime (see [Set your API key](#2-set-your-gemini-api-key-adb)).
> `gemini_api_key.txt` is git-ignored.

---

## Install

Needs Android SDK + JDK 17. Mars runs the build/install on his own machine.

```bash
git clone https://github.com/<you>/X3Gemini.git
cd X3Gemini
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The `&&` matters — a failed build must not install a stale APK.

### Enabling adb on the X3 Pro
Glasses Settings → General → swipe to the far left → trigger the
"wall-collision" bounce **10×** to toggle ADB on.

### Permissions
First launch prompts for microphone + camera on-device (tap Allow with the
cursor), or pre-grant over adb:

```bash
adb shell pm grant com.x3gemini.app android.permission.RECORD_AUDIO
adb shell pm grant com.x3gemini.app android.permission.CAMERA
```

---

## 2. Set your Gemini API key (adb)

Get a key from **[aistudio.google.com](https://aistudio.google.com)** (the
classic AI-Studio keys look like `AIzaSy…`). The app reads it within ~10 s — no
restart needed.

**Broadcast (recommended)** — launch the app, then send the key. It's picked up
by a receiver registered while the app is running and saved to the device:

```bash
adb shell am start -n com.x3gemini.app/.MainActivity
adb shell am broadcast -a com.x3gemini.app.SET_API_KEY --es key "AIza...your-key..."
```

If the app is **not** running, target the receiver explicitly instead (an
implicit broadcast won't reach a cold app on Android 8+):

```bash
adb shell am broadcast -n com.x3gemini.app/.core.config.ApiKeyBroadcastReceiver \
  -a com.x3gemini.app.SET_API_KEY --es key "AIza...your-key..."
```

**File push (alternative)** — launch the app once first so its data dir exists
(Android 12 won't let adb create it), then:

```bash
echo "AIza...your-key..." > gemini_api_key.txt
adb push gemini_api_key.txt \
  /sdcard/Android/data/com.x3gemini.app/files/gemini_api_key.txt
```

> **If a session opens then immediately closes / the "G" badge is red**, the
> key is being rejected by the Live endpoint (`generativelanguage.googleapis.com`).
> Confirm with `adb logcat -s GeminiLiveClient GeminiVoicePipe` — a `403` /
> `API_KEY_INVALID` means a wrong key *type* (e.g. a Vertex-AI express key
> rather than an AI-Studio Gemini key).

---

## Voice commands

Double-tap the right temple arm (or tap the avatar) to start talking; the HUD
card shows Gemini's reply. Common commands:

| Say | What happens |
|---|---|
| *"Make a note that the router password is hunter2."* | Pins a post-it note to the HUD. |
| *"Pin that picture of the whiteboard."* | Saves the current camera frame as a picture pin (camera must be on). |
| *"Take a photo."* / *"Save this."* | Writes a photo to `DCIM/X3Gemini`. |
| *"Remove the Warriors pin."* / *"What's pinned?"* / *"Clear my pins."* | Manage the pin board. |

### Live-update widgets
Auto-refreshing HUD cards — say what **changing** information to watch:

- *"Add a live card watching the World Cup scores."*
- *"Pin a live widget for the top AI headline, refresh every 2 minutes."*
- *"Keep me posted on the weather in Oakland on my HUD."*
- *"Track the price of Bitcoin on my HUD."*
- *"Make a live HUD card for new trending Rust repos."*

Live widgets are only for information that **changes over time** (scores, news,
prices, weather). Pinning a static thing (link/video/station) is declined —
there's no browser here. Default refresh is 5 min; add *"refresh every N
minutes"* to change it (1–180). Tap a card to refresh now; move/delete it with
the double-tap modify mode.

### Memory (persists across sessions)

| Say | What happens |
|---|---|
| *"Remember that my car is parked on level 3."* | Stored durably; available in every future conversation. |
| *"Remember my name is Mars and I live in Oakland."* | Same — the assistant also auto-remembers clearly durable facts you share. |
| *"What do you remember?"* / *"Forget the parking one."* | List / delete memories. |

Memory is injected into every session's system prompt (max 60 entries; oldest
drop first). It lives on-device in `assistant_store` prefs — no cloud.

### Custom instructions (personality)

Say *"From now on, always answer in one short sentence"*, *"call me Mars"*,
*"act like a formal butler"* — stored persistently and applied to every future
session (and immediately in the current one). *"Show your instructions"* /
*"clear your instructions"* to inspect or reset. Long text is easier over adb:

```bash
adb shell am broadcast -a com.x3gemini.app.SET_INSTRUCTIONS \
  --es text "Call me Mars. Be concise. Prefer metric units."
```

(While the app is cold, target the manifest receiver explicitly:
`-n com.x3gemini.app/.core.config.InstructionsBroadcastReceiver`.)

### Reminders (notification + HUD)

| Say | What happens |
|---|---|
| *"Remind me to take out the trash at 8pm."* | One-shot reminder. |
| *"Remind me in 20 minutes to flip the laundry."* | Relative time. |
| *"Remind me every morning at 7:30 to run my morning report."* | Daily repeating. |
| *"What are my reminders?"* / *"Cancel the trash one."* | List / cancel. |

At fire time the glasses show a **system notification** and pin a **⏰ note to
the HUD** (stays until you remove it). Reminders survive reboots — they're
re-armed on boot and app start; ones missed while powered off fire immediately.

### Custom commands (saved prompts)

- *"Save a command called **morning report** that tells me the weather in
  Oakland, my reminders for today, and the top three AI headlines."*
- *"Run my morning report."* — the saved prompt executes in full, using web
  search and any tools it needs.
- *"What commands do I have?"* / *"Delete the morning report command."*

Pair with a daily reminder (*"remind me every morning at 8 to run my morning
report"*) for a hands-free-ish daily briefing — say "run it" when the reminder
pops.

---

## Controls

| Gesture | Action |
|---|---|
| Right trackpad slide | Move cursor (auto-hides after 6 s) |
| **Right arm single tap** (empty space, idle) | **Start Gemini** — tap anywhere to talk |
| Right arm single tap (on a widget) | Click it (pins, orb) |
| **Right arm double tap** | **Toggle Gemini** — start / full exit (session + camera + chat card) |
| Right arm double tap, cursor **on a pin** | Pin modify mode: next tap moves it, ✕ deletes |
| **Left arm single tap** | **Toggle camera** (4:3 preview under the HUD, frames stream to Gemini). Opening it also auto-starts a Gemini session. |
| Tap avatar orb | Also toggles Gemini |
| Tap a picture pin | Fullscreen viewer (tap again to dismiss) |
| Tap a live card | Refresh it now |

### HUD strip
**local time · date · battery % · network (Wi-Fi/Cell/⊘) · G badge · avatar orb**
(+ a red camera glyph while the camera streams).

- **G badge:** green = Gemini API healthy/idle, amber = connecting, red = error.
- **Orb glow:** dim = idle, **red** = listening to you, **green** = Gemini speaking.
- Chat cards are read-only and disappear ~10 s after the session ends.

---

## Live-widget status & free-tier rate limits

Each live card shows a small status beside its title so you know why it isn't
current:

| Beside the title | Meaning |
|---|---|
| `· 5m` (dim) | Last successful refresh, minutes ago — all good. |
| `· rate-limited` (amber) | The Gemini **free-tier request quota is used up.** The card keeps its last value and auto-retries with a growing backoff (1 → 15 min). **Not broken.** |
| `· <reason>` + red outline | The source keeps failing (`no data`, `server error`, `error 4xx`…) after several tries; last value shown dimmed. |

**Why `rate-limited` happens:** X3Gemini uses the Gemini Developer API. A
**free** key has a low request cap (Google's free tier is roughly 20
`generateContent` requests/day, and **search-grounded** cards — news, scores,
anything without a fixed source URL — are the heaviest). Each live-widget
refresh is one request, so a few widgets on short intervals will exhaust the
free quota and refreshes come back **HTTP 429**.

**To avoid it:**
- Run **fewer** live widgets, and prefer **longer** intervals (15–30 min for news).
- Give heavy/grounded cards (news, scores) the longest intervals.
- Best fix: use an API key with **billing enabled** (paid tier) — the free-tier
  caps disappear. Push it the same way.

Confirm the cause any time with `adb logcat -s LiveCardEngine`; a `429` line
quotes Google's quota message verbatim.

---

## Architecture (single module, `com.x3gemini.app`)

```
X3GeminiApp                      — owns ChatSessionModel, starts LiveCardEngine,
                                   registers the adb API-key receiver
MainActivity                     — HUD strip, cursor + gestures, chat card,
                                   pin board host, camera preview positioning
ui/BinocularSbsLayout            — 640×480 logical viewport drawn twice (L+R)
ui/HudPinBoardController         — notes/pictures/live cards under the HUD;
                                   move/delete via double-tap modify mode
core/session/…FgService          — FGS (mic|camera) hosting the pipeline + CameraX
core/session/GeminiVoicePipeline — mic 16 kHz → Live WS → AudioTrack 24 kHz;
                                   5 s mutual-silence watchdog; barge-in flush
core/network/GeminiLiveClient    — Live WebSocket; tools: camera_action, hud_pin,
                                   googleSearch; parses interruptions
core/live/LiveCardEngine         — refreshes live cards (page fetch or
                                   search-grounded gemini-2.5-flash REST);
                                   429/stale handling + backoff
core/tools/…                     — CameraTool (save_photo → DCIM/X3Gemini),
                                   HudPinTool (add_note/add_picture/add_live/…)
core/bridge/…                    — HudStateBridge, ChatCardBridge,
                                   CameraStateBridge, HudPinStore, VoiceServiceApi
core/config/ApiKeyStore          — resolves the adb-pushed key (file or broadcast)
```

Platform ground rules (from the TapInsight X3 field guide): pure-black canvas
(black = transparent on the waveguide), no `ar_mode` meta-data, geometry lives
in the layout (never mutate `DisplayMetrics`), the right-arm click can arrive as
a KEY *or* a touch, camera frames need 90° rotation, and the Mercury/RayNeo AARs
ride along for launcher visibility.
