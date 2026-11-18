# OG CLIP STUDIO

Independent Android replay-buffer clipper for mobile gaming (Free Fire MAX target), separate from OG STREAM STUDIO.
Kotlin, no third-party runtime libraries, minSdk 29, built entirely with GitHub Actions: no PC needed.

> **Status: Phase 1 (standalone buffer) + Phase 2 (stream link, floating button, hotkeys).** The code was written and reviewed but **not yet compiled or run
> on a device** by its author. First step after upload is the build + the test checklist below.
> Read `docs/ARCHITECTURE.md` for feasibility, the integration design and limits.

## What works in Phase 1 (by design, pending your device test)

- Rolling replay buffer (15-180 s) of H.264 + AAC held in RAM, GOP-aligned, hard 192 MB cap.
- CLIP NOW from the dashboard or from the notification action; saves the previous N seconds to
  `Movies/OG CLIP STUDIO/OGCLIP_<date>.mp4` **without re-encoding**; the buffer keeps running.
- Pause / resume, start / stop, resolution (480/720/1080), 30/60 fps, bitrate, orientation settings.
- Clip library: thumbnail, duration, size, date, status, Play, Share, Delete.
- Game-audio capture (Android playback capture). **No microphone in standalone mode** (see architecture doc).

## Phase 2 (added, also untested on a device)

- **Stream link**: with the patched OG STREAM STUDIO v1.6 (see `docs/STREAM_APP_PATCH.md`) clips are cut from the stream
  encoder's own buffer: game + mic + teammates mix, no extra encoding, buffer keeps running while you stream.
  Choose it in **Controls > Clip source**.
- **Floating clip button**: tap = clip, long-press = menu, drag to move (Controls). Visible in screen capture.
- **Keyboard shortcut** (default Ctrl+Shift+C, changeable) through an accessibility service you enable in Android settings.
- **Mouse forward button** (experimental) and an **input tester** that shows what your keyboard/mouse really send,
  so the mouse question gets answered by your hardware, not by guessing.

## Phase 3 (added, untested on a device)

**Library > Edit** opens the editor: trim (0.1 s steps), **9:16 YouTube Short** with a crop window you slide left/right
(nothing is cropped automatically), or keep the original 16:9, text (top/centre/bottom, white/gold), channel logo
(picked with the system file picker, remembered), quality (original/720p/1080p). **Export** re-encodes into a
temporary file; you **Preview** it and then **Save to library** (as `OGSHORT_...mp4`) or **Discard**.
It uses Google's Media3 Transformer (the first library in this project, about +3 MB APK). Export is a real
hardware re-encode, so expect it to take a fraction of the clip length and warm the phone; keep the screen open.
Things to verify first: logo/text size and position look right in the exported file, and audio stays in sync.
If the build fails in `editor/ShortsExporter.kt`, send me the error: that file depends on Media3 API details I could
not compile-check here.

Not built yet: YouTube (4), voice and AI (5). They have no buttons in the app rather than dead ones.

## Build the APK from your phone

1. Create a **private** GitHub repo and upload everything in this folder (keep the `.github` folder).
2. Open the **Actions** tab. **Build APK** runs on push (or press *Run workflow*).
3. When finished, open the run, download artifact **OG-CLIP-STUDIO-debug-apk**, unzip, install `app-debug.apk`.
   A separate *unit-tests* job runs the ring-buffer tests and attaches a report; it does not block the APK.
4. If the build fails, copy the red error text and send it to me.

## Signing (do this once, before Phase 2)

Both apps must be signed with the **same key**, otherwise (a) Phase 2's signature-protected link to
OG STREAM STUDIO cannot work and (b) each new CI APK refuses to install over the previous one.

1. Actions -> **Create signing key (run once)** -> *Run workflow*.
2. Open the run, download artifact **signing-secrets-DELETE-AFTER-USE**, open `secrets.txt`.
3. Repo -> Settings -> Secrets and variables -> Actions -> add four secrets with these exact names, pasting the values:
   `OGCLIP_KEYSTORE_BASE64`, `OGCLIP_KEYSTORE_PASSWORD`, `OGCLIP_KEY_ALIAS`, `OGCLIP_KEY_PASSWORD`.
4. Delete the artifact from the run page. Add the **same four secrets** to the OG STREAM STUDIO repo
   (its workflow will be updated in Phase 2).
5. Debug builds now use this key automatically. For a release build: Actions -> **Release APK (signed)** ->
   *Run workflow* (or push a tag like `v0.1.0`); artifact **OG-CLIP-STUDIO-release-apk**.

Never commit a keystore or password. `.gitignore` excludes `*.jks`/`*.keystore`, and the Gradle file reads secrets only
from environment variables. Back up `secrets.txt` somewhere private: losing the key means users must uninstall to update.

## Using it

1. Open the app, accept permissions on the welcome screen.
2. **Buffer Settings**: pick clip length, resolution, bitrate. Start with 720p / 30 fps / 3000 kbps if the phone is
   hot or the game lags, then adjust.
3. **START BUFFER** -> Android asks to allow screen capture -> *Start now*. Open your game.
4. After a great play, pull the notification shade and tap **CLIP NOW** (or use the dashboard button).
5. **Clip Library** to play, share or delete clips.

Limits worth knowing: clips start on a key frame (60 s means 60-62 s); orientation is fixed when the buffer starts;
secure/DRM screens record black; some games block audio capture.

## Phase 1 acceptance checklist (please report results)

1. Build succeeds; APK installs on the iQOO Z6 Lite 5G.
2. Start buffer, play Free Fire MAX 2 minutes, CLIP NOW: file appears in the library and plays.
3. Clip length is about the configured value; buffer keeps running; a second clip 10 s later also works.
4. Game sound is present in the clip; lip-sync/gunshot-sync looks right.
5. Pause then resume then CLIP NOW: clip contains only post-resume footage.
6. Note: phone temperature after 15 min, game FPS drop (if any), and the RAM figure on the dashboard.
7. **Optional, informative:** start OG STREAM STUDIO live first, then this buffer. Report whether both keep working.
   (This is the unproven two-projection case; nothing depends on it.)

## Project layout

```
app/src/main/java/com/ogclip/studio/
  buffer/   EncodedSample, ReplayRing      pure Kotlin, unit-tested
  engine/   ReplayEngine                   MediaProjection -> encoder -> ring
  clips/    ClipWriter, ClipStore          MP4 muxing (no re-encode), MediaStore, JSON index
  service/  ReplayService                  foreground service + notification actions
  core/     Prefs, ReplayState, Fmt
  ui/       Welcome, Main (dashboard), Settings, Library, Player, About
```

## Roadmap

1 standalone buffer (this) -> 2 stream-app integration + floating button + hotkeys -> 3 editor/Shorts ->
4 YouTube OAuth + upload -> 5 voice + AI. See `docs/ARCHITECTURE.md`.
