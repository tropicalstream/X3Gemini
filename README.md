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
| Right arm single tap | Click at cursor (pins, orb, chat card) |
| **Right arm double tap** | **Toggle Gemini** — start session / full exit (session + camera + chat card) |
| Right arm double tap, cursor **on a pin** | Pin modify mode: next tap moves it, ✕ deletes |
| **Left arm double tap** | **Toggle camera** (4:3 preview under the HUD, frames stream to Gemini) |
| Tap avatar orb | Also toggles Gemini |
| Tap chat card | Expand / collapse the reply reader |
| Tap a picture pin | Fullscreen viewer (tap again to dismiss) |
| Tap a live card | Refresh it now |

Voice: *"make a note that…"*, *"pin that picture of…"*, *"add a live card
watching the Warriors score"*, *"take a photo"*, *"remove the … pin"*,
*"what's pinned?"*, *"clear my pins"*.

## API key via adb (no login screen, no companion app)

Two ways — if both are set, the **file wins**:

**1. Broadcast** (recommended — works even before first launch; persists to
app prefs and survives restarts):

```bash
adb shell am start -n com.x3gemini.app/.MainActivity   # skip if already running
adb shell am broadcast -a com.x3gemini.app.SET_API_KEY --es key "AIza...your-key..."
```

**2. File push** (only after the app has been launched once — the target
directory is created by the app, and Android 12 scoped storage forbids adb
from creating it: you'll get `remote secure_mkdirs failed: Operation not
permitted` on a fresh install):

```bash
echo "AIza...your-key..." > gemini_api_key.txt
adb push gemini_api_key.txt /sdcard/Android/data/com.x3gemini.app/files/gemini_api_key.txt
```

> Either way the key is re-read within ~10 s (no restart needed).
> Expected key format: a Gemini Developer API key from
> [aistudio.google.com](https://aistudio.google.com) — it starts with `AIza`.
> Keys starting with `AQ.` are Vertex AI express-mode keys and will NOT
> authenticate against the `generativelanguage.googleapis.com` Live endpoint
> this app uses.

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
