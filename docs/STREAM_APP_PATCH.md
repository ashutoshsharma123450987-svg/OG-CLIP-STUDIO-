# OG STREAM STUDIO v1.6 "clip link" patch

Source: your uploaded v1.5. Your original files are untouched; the patched copy is delivered as a full project
(`og-stream-studio-patched.zip`) and as a plain diff (`og-stream-studio-clip-link.patch`).

## What it adds (about 450 lines, no libraries)

| File | Change |
|---|---|
| `engine/ReplayRing.java` (new) | Rolling GOP-aligned buffer of encoded frames, 192 MB cap |
| `engine/ReplayTap.java` (new) | Static hook; does nothing unless the setting is ON |
| `engine/ClipMuxer.java` (new) | Writes a snapshot to MP4 by copying samples (no re-encode) |
| `engine/ClipControlService.java` (new) | Bound Messenger service: STATUS / CLIP / SET_WINDOW |
| `engine/ClipProvider.java` (new) | Read-only provider that hands the MP4 to the clip app |
| `engine/VideoPipeline.java` | 2 lines: store SPS/PPS; `ReplayTap.video(...)` before the network-drop logic |
| `engine/AudioPipeline.java` | store AAC config; `ReplayTap.audio(...)` before `sendAudioFrame` |
| `engine/StreamService.java` | `ReplayTap.start(...)` when the pipeline starts, `ReplayTap.stop()` in teardown |
| `model/Prefs.java` | `replayBuffer()` (default OFF), `replaySeconds()` |
| `ui/SettingsActivity.java` | "Replay buffer" checkbox |
| `AndroidManifest.xml` | signature permission `...CLIP_CONTROL`, the service and the provider (both protected by it) |
| `app/build.gradle`, workflow | optional shared signing key; version 1.6 |

Streaming behaviour is unchanged when the checkbox is OFF. When ON, the extra work per frame is one small object
and a lock; frames are shared, not copied.

## Install order (important)

1. Create the signing key and add the 4 secrets to **both** repos (clip-app README, "Signing").
2. Build and install the patched OG STREAM STUDIO **first**, then OG CLIP STUDIO. (Android grants a signature
   permission when the requesting app is installed while the defining app is present; if you installed in the
   other order, reinstall OG CLIP STUDIO.)
3. Uninstall any previous CI-signed build of either app once, because the signing key changes.

## Test checklist on the phone (please report)

1. OG STREAM STUDIO: Settings, tick "Replay buffer", Save. Go live as usual.
2. OG CLIP STUDIO: Controls, set source "OG STREAM STUDIO". Dashboard should show "BUFFERING (STREAM)" and a growing buffer.
3. Press CLIP NOW (dashboard, floating button or shortcut). A clip should appear in the Library within a few seconds.
4. Play it: game sound, your mic and teammates (if the game allowed capture in the stream) should be present and in sync with video.
5. Confirm the live stream itself did not stutter (check YouTube Studio stream health) and note phone temperature.
6. Clip quality equals your stream quality (default 480p/24fps/1200 kbps). Raise the stream quality if you want better clips.

## Known limits

* Buffer exists only while live (it is a by-product of the stream encoder).
* Start of a clip snaps to a key frame (2 s interval), so a 60 s request returns 60-62 s.
* BRB screen, overlays and the floating bubble are part of the stream picture and therefore of the clip.
* Not compiled or run on a device yet. The ring buffer was compiled and tested on a JVM (13 checks); everything
  that touches Android APIs is unverified until the first GitHub build and phone test.
