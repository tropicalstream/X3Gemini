# X3Gemini

X3Gemini is a minimal voice-driven heads-up assistant for the RayNeo X3 Pro AR glasses, built on Google's Gemini Live API with no companion app or login screen. Speaking to it opens a live voice session with barge-in (you can talk over its replies) and a HUD that pins whatever you ask it to remember: text notes, camera snapshots, and auto-refreshing live cards for things like scores, prices, or weather that update on their own schedule. It also supports durable memory across sessions, custom personality instructions, spoken reminders that survive a reboot, and saved custom commands that chain several of these together into a single spoken trigger — all running as a lightweight foreground service with the display kept to a black, transparent HUD strip.

## Controls

| Gesture | Action |
|---|---|
| Right trackpad slide | Move cursor (auto-hides after 6 s) |
| Right arm single tap (empty space, idle) | Start Gemini — tap anywhere to talk |
| Right arm single tap (on a widget) | Click it (pins, orb) |
| Right arm double tap | Toggle Gemini — start / full exit (session + camera + chat card) |
| Right arm double tap, cursor on a pin | Pin modify mode: next tap moves it, ✕ deletes |
| Left arm single tap | Toggle camera (frames stream to Gemini); also auto-starts a session |
| Tap avatar orb | Also toggles Gemini |
| Tap a picture pin | Fullscreen viewer (tap again to dismiss) |
| Tap a live card | Refresh it now |

## Download

[X3Gemini.apk](X3Gemini.apk)
