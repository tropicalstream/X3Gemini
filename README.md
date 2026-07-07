# X3Gemini

A minimal **Gemini Live voice HUD** for the RayNeo X3 Pro AR glasses, carved out
of TapInsight's unipanel lineage (`beta6-gold`). One Activity, one foreground
Service, **no browser** — under the HUD strip is black space (transparent on the
waveguide), shared by the pin board and the camera preview.

- **Model:** `gemini-2.5-flash-native-audio-preview-12-2025`, hardcoded. Default voice.
- **Barge-in:** you can talk over Gemini; queued audio is flushed the moment the
  server signals `interrupted`.
- **Auto-end:** the session closes after **5 seconds of mutual silence**
  (neither you nor Gemini speaking, no tool running).
- **No persistence:** chat history lives in RAM only — enough for follow-up
  questions while the app runs, gone on restart. Pins (notes/pictures/live
  cards) DO persist (SharedPreferences), as before.

## HUD

Top strip: **local time · date · battery % · network (Wi-Fi/Cell/⊘) · G badge ·
avatar orb** (+ red camera glyph while the camera streams).

- **G badge:** green = Gemini API healthy/idle, amber = connecting, red = error.
- **Orb glow:** dim steel ring = idle, bold **red** = listening to you, bold
  **green** = Gemini speaking (pulses with loudness).

## Controls

| Gesture | Action |
|---|---|
| Right trackpad slide | Move cursor (auto-hides after 6 s) |
| **Right arm single tap (empty space, idle)** | **Start Gemini** — tap anywhere to talk |
| Right arm single tap (on a widget) | Click it (pins, orb, chat card) |
| **Right arm double tap** | **Toggle Gemini** — start / full exit (session + camera + chat card) |
| Right arm double tap, cursor **on a pin** | Pin modify mode: next tap moves it, ✕ deletes |
| **Left arm single tap** | **Toggle camera** (4:3 preview under the HUD, frames stream to Gemini). Opening it also auto-starts a Gemini session. |
| Tap avatar orb | Also toggles Gemini |
| Tap chat card | Expand / collapse the reply reader |
| Tap a picture pin | Fullscreen viewer (tap again to dismiss) |
| Tap a live card | Refresh it now |

### Voice commands

- **Notes:** *"make a note that the router password is hunter2."*
- **Pictures:** *"pin that picture of the whiteboard"* (captures the current
  camera frame; the camera must be on).
- **Photos:** *"take a photo"* / *"save this"* (writes to `DCIM/X3Gemini`).
- **Manage pins:** *"remove the Warriors pin"*, *"what's pinned?"*, *"clear my
  pins."*

**Live-update widgets** (auto-refreshing HUD cards) — say what *changing*
information to watch:

- *"Add a live card watching the World Cup scores."*
- *"Pin a live widget for the top AI headline, refresh every 2 minutes."*
- *"Keep me posted on the weather in Oakland on my HUD."*
- *"Track the price of Bitcoin on my HUD."*
- *"Make a live HUD card for new trending Rust repos."*

Live widgets are only for information that **changes over time** (scores,
news, prices, weather). Asking to pin a static thing (a link, a video, a
station) is declined — there's no browser on this build. Default refresh is
every 5 minutes; add *"refresh every N minutes"* to change it (1–180). Tap a
live card to refresh it now; move/delete it with the double-tap modify mode
like any pin. A **red outline with a `!`** means the last refresh failed or
returned nothing (the card keeps showing its previous value, dimmed).

## API key via adb (no login screen, no companion app)

Two ways — if both are set, the **file wins**:

**1. Broadcast** (persists to app prefs; survives restarts). The app must be
**running** — a context-registered receiver picks it up (a manifest receiver
would NOT, because Android 8+ blocks implicit broadcasts to manifest
receivers, which is why the plain command silently no-ops when the app is
cold):

```bash
adb shell am start -n com.x3gemini.app/.MainActivity
adb shell am broadcast -a com.x3gemini.app.SET_API_KEY --es key "AIza...your-key..."
```

If the app happens to be cold, target the receiver explicitly instead (an
explicit broadcast bypasses the implicit-broadcast limit):

```bash
adb shell am broadcast -n com.x3gemini.app/.core.config.ApiKeyBroadcastReceiver \
  -a com.x3gemini.app.SET_API_KEY --es key "AIza...your-key..."
```

**2. File push** (works once the app has been launched at least once — the
first launch creates the target dir; adb can't create it on a fresh install,
you'd get `secure_mkdirs failed: Operation not permitted`):

```bash
echo "AIza...your-key..." > /Users/me/Projects/X3Gemini/gemini_api_key.txt
adb push /Users/me/Projects/X3Gemini/gemini_api_key.txt \
  /sdcard/Android/data/com.x3gemini.app/files/gemini_api_key.txt
```

> Either way the key is re-read within ~10 s (no restart needed).
>
> **Key type matters.** This app talks directly to the Gemini Developer API
> Live endpoint (`generativelanguage.googleapis.com`), so it needs a **Gemini
> API key from [aistudio.google.com](https://aistudio.google.com)** — the
> classic ones look like `AIzaSy…`. If a session opens and immediately closes,
> or the "G" badge goes red, the key is being rejected by that endpoint —
> confirm and read the exact reason in logcat:
>
> ```bash
> adb logcat -s GeminiLiveClient GeminiVoicePipe
> ```
>
> A `403` / `API_KEY_INVALID` / auth close code there means wrong key *type*
> for this endpoint (e.g. a Vertex-AI express key rather than an AI Studio
> Gemini key), not a wrong app.

## Build + install (Mars runs these)

```bash
cd ~/Projects/X3Gemini
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The `&&` matters — a failed build must not install a stale APK.

First launch asks for microphone + camera permissions on-device (temple-tap the
Allow buttons with the cursor, or pre-grant over adb):

```bash
adb shell pm grant com.x3gemini.app android.permission.RECORD_AUDIO
adb shell pm grant com.x3gemini.app android.permission.CAMERA
```

## Architecture (single module, `com.x3gemini.app`)

```
X3GeminiApp                     — owns ChatSessionModel, starts LiveCardEngine
MainActivity                    — HUD strip, cursor + gestures, chat card,
                                  pin board host, camera preview positioning
ui/BinocularSbsLayout           — 640×480 logical viewport drawn twice (L+R)
ui/HudPinBoardController        — notes/pictures/live cards under the HUD;
                                  move/delete via double-tap modify mode
core/session/…FgService         — FGS (mic|camera) hosting the pipeline + CameraX
core/session/GeminiVoicePipeline— mic 16 kHz → Live WS → AudioTrack 24 kHz;
                                  5 s mutual-silence watchdog; barge-in flush
core/network/GeminiLiveClient   — Live WebSocket, tools: camera_action, hud_pin,
                                  googleSearch; parses `interrupted`
core/live/LiveCardEngine        — refreshes live cards (page fetch or
                                  search-grounded gemini-2.5-flash REST)
core/tools/…                    — CameraTool (save_photo → DCIM/X3Gemini),
                                  HudPinTool (add_note/add_picture/add_live/…)
core/bridge/…                   — HudStateBridge, ChatCardBridge,
                                  CameraStateBridge, HudPinStore, VoiceServiceApi
core/config/ApiKeyStore         — adb-pushed key file / broadcast receiver
```

Ground rules inherited from the TapInsight guide: pure-black canvas, no
`ar_mode` meta-data, geometry lives in the layout (never mutate
`DisplayMetrics`), right-arm clicks are KEY events, camera frames rotate 90°,
Mercury/RayNeo AARs ride along for launcher visibility.
