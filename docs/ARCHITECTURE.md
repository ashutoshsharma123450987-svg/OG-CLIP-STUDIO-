# OG CLIP STUDIO — Architecture & Feasibility

Status: written against the real OG STREAM STUDIO source (v1.5, `com.ogstudio.streamer`). Phase 1 and the Phase 2 code exist (see README and `STREAM_APP_PATCH.md`); neither has been run on a device yet.
Nothing in this document has been run on a device. Items marked **UNTESTED** must be
verified on the iQOO Z6 Lite 5G before anyone relies on them.

---

## 1. Feasibility verdict

| Feature | Verdict | Notes |
|---|---|---|
| Rolling replay buffer of H.264/AAC | **Feasible** | Standard technique: keep encoded frames in RAM, evict by GOP, mux to MP4 on demand. No re-encode. |
| Zero-re-encode clips from OG STREAM STUDIO | **Feasible, needs a patch to the stream app** | The stream app already produces the final H.264 + AAC. The buffer must live *inside* it (section 3). |
| Independent capture (fallback) | **Possible, not guaranteed while streaming** | Own MediaProjection + second encoder. Vendor-dependent, see section 4. Implemented in Phase 1 as standalone mode. |
| Keep buffering while streaming | **Feasible** via integration mode | Buffer is a by-product of the stream encoder, near-zero extra CPU. |
| Floating CLIP button | Feasible | `SYSTEM_ALERT_WINDOW` overlay. The stream app already does this (`FloatingControls`). Overlays appear in screen capture (stream README notes the same). |
| Keyboard shortcut (Ctrl+Shift+C) | **Feasible only via an AccessibilityService** | Android delivers key events to the focused window only (the game). An accessibility service with key-event filtering is the only non-root global route. User must enable it manually. **UNTESTED** against Free Fire. |
| External mouse buttons | **Partly** | Mouse Back/Forward buttons usually arrive as `KEYCODE_BACK/FORWARD` key events and may be seen by the same service. Middle/side buttons are pointer events delivered only to a window, not globally. **UNTESTED**; Phase 2 will report what really works. |
| Voice commands | Feasible but **mic-contested** | Android allows one real microphone consumer; Free Fire voice chat and the stream app already fight over it (stream README). A second always-listening app would worsen this. Better design: run keyword spotting on the PCM the stream app already captures (Phase 5), or push-to-talk. |
| Trim / 9:16 crop / text / watermark | Feasible | MediaCodec + OpenGL (or Media3 Transformer). Phase 3. |
| YouTube upload | Feasible | YouTube Data API v3 resumable upload. See caveats in section 7. Phase 4. |
| AI highlight detection | **Research item** | Not promised. Needs real Free Fire recordings to test. Phase 5. |

---

## 2. What the stream app does today (from source)

```
ScreenCapture (MediaProjection -> ImageReader -> Bitmap)
        |
SceneRenderer --draw--> VideoPipeline.renderLoop --> encoder input Surface
                                                       |
                         MediaCodec "video/avc" (CBR, 2 s GOP) 
                                                       |
                    VideoPipeline.drainLoop -> handleFrame(annexB, key, ptsUs) --> RtmpClient

AudioRecord(mic) + AudioRecord(playback capture, USAGE_MEDIA/GAME/UNKNOWN/VOICE_COMMUNICATION)
        |  PcmRing x3 (mic, game, BRB music)
   AudioMixer (steady clock) -> AAC-LC 48 kHz stereo 160 kbps
        |
   AudioPipeline.drainLoop -> rtmp.sendAudioFrame(aac, ts)
```

Facts that drive the design:

* Video and audio timestamps are both relative to the same `t0Us` (`System.nanoTime()/1000`
  taken in `StreamService.startPipeline`). Sync between streams is already correct; we only
  have to keep encoder `presentationTimeUs` untouched.
* The audio is **already a single mixed track** (game + mic + teammates when the game allows
  it). Clips will have exactly that one mixed track. Separate tracks are impossible without
  changing the stream app's mixer and are **not promised**.
* Encoded frames exist as `byte[]` in `VideoPipeline.handleFrame` (line ~287) and
  `AudioPipeline.drainLoop` (line ~394). That is where to tap.
* `handleFrame` drops frames when the network is slow. The tap must sit **before** that
  logic so the clip buffer stays complete even when the upload link is struggling.
* The encoder uses a 2-second key-frame interval, so a clip can only start on a key frame.
  Saving "60 s" yields 60-62 s.
* Clip quality == stream quality (resolution, fps, bitrate, overlays, BRB screen, floating bubble
  if visible). At the default 480p / 24 fps / 1200 kbps clips are modest. Raise the stream
  quality if you want better clips.

---

## 3. Recommended architecture: buffer lives where the encoder lives

```
+------------------- OG STREAM STUDIO (existing app, patched) ------------------+
|  VideoPipeline.handleFrame ---+                                               |
|                               +--> ReplayRing (RAM, GOP-aligned) --+          |
|  AudioPipeline.drainLoop -----+                                    |          |
|                                                                    v          |
|  ClipControlService (bound, Messenger)  <--- CLIP / STATUS ---+  ClipMuxer    |
|  ClipProvider (ContentProvider) <-- MP4 file ------------------------------+  |
+------------------------------------------------------------------------|-----+
        signature permission  com.ogstudio.streamer.permission.CLIP_CONTROL
+------------------------------------------------------------------------|-----+
|  OG CLIP STUDIO (this app)                                             |     |
|  Floating button / hotkey / voice / notification --> ClipClient        |     |
|  ClipClient receives content:// URI --> copies into MediaStore ------- +     |
|  Library, editor, YouTube upload                                              |
+-------------------------------------------------------------------------------+
```

Why "pull on demand" instead of streaming frames to the clip app continuously:

* Zero extra encoding, zero extra copying while gaming. A continuous IPC feed would double
  RAM use and keep a second process busy for no benefit.
* The clip app can be closed or killed at no cost; the buffer is not lost.
* A clip is one short IPC round trip plus one file copy.

Protocol (Messenger over a bound service; all messages carry `Bundle`s):

| Message | Direction | Payload |
|---|---|---|
| `STATUS` | clip -> stream | reply: `buffering`, `bufferedSeconds`, `width`, `height`, `fps`, `kbps`, `audio` |
| `SET_BUFFER` | clip -> stream | `windowSeconds` (15-180), `enabled` |
| `CLIP` | clip -> stream | `seconds`; reply: `uri` (content://), `durationMs`, `error` |

Security: custom permission `com.ogstudio.streamer.permission.CLIP_CONTROL` with
`protectionLevel="signature"`. **Both apps must be signed with the same key.** See section 6.
The clip app declares `<queries><package android:name="com.ogstudio.streamer"/></queries>`
(Android 11+ package visibility).

### Required changes to OG STREAM STUDIO (nothing is modified yet)

| # | Change | Where |
|---|---|---|
| 1 | Add `ReplayRing` + `EncodedSample` (copy from this project, Java port) | `engine/` |
| 2 | Tap video: call `ring.addVideo(copy, key, ptsUs)` at the start of `handleFrame`, before the drop logic; keep SPS/PPS to build `csd-0/csd-1` for the muxer | `VideoPipeline` |
| 3 | Tap audio: `ring.addAudio(copy, ptsUs)` before `rtmp.sendAudioFrame`; keep the AudioSpecificConfig | `AudioPipeline` |
| 4 | `ClipMuxer`: `MediaMuxer` stream-copy of a ring snapshot to MP4 | `engine/` |
| 5 | `ClipControlService` (Messenger) + `ClipProvider` (read-only ContentProvider for the produced MP4) | `engine/`, manifest |
| 6 | Manifest: permission (signature), service + provider with `android:permission` | `AndroidManifest.xml` |
| 7 | Settings toggle "Replay buffer for OG CLIP STUDIO" (default OFF) and window length | `Prefs`, `SettingsActivity` |
| 8 | Ring lifecycle: create in `StreamService.startPipeline`, clear/release in `teardown` | `StreamService` |

Implemented as designed (about 450 lines of Java), no new libraries (matches the stream app's
"no third-party libraries" rule). This is Phase 2. It will be done against the source you
uploaded and delivered as a patch, not silently.

RAM cost (encoded data only, estimates, not measurements):

| Stream setting | Video+audio | 60 s | 120 s | 180 s |
|---|---|---|---|---|
| 480p, 1200 kbps (default) | ~0.17 MB/s | 10 MB | 20 MB | 31 MB |
| 720p30, 4000 kbps | ~0.52 MB/s | 31 MB | 62 MB | 94 MB |
| 1080p60, 8000 kbps | ~1.0 MB/s | 61 MB | 123 MB | 184 MB (too large, see cap) |

The ring enforces a hard byte cap (default 192 MB) and drops the oldest GOP first; the
dashboard always shows the *actual* buffered seconds, so a clipped window is visible, not hidden.

---

## 4. Android MediaProjection limitations (and what they mean here)

1. **Consent every session.** Android 14 requires the user to approve each capture session;
   the result `Intent` is single-use. A projection cannot be saved and replayed after a
   reboot or after the service ends. Android 14 also requires the foreground service of type
   `mediaProjection` to be running *before* `getMediaProjection()` is called, and a
   `MediaProjection.Callback` must be registered *before* `createVirtualDisplay()`.
   Both are implemented in this project.
2. **Two apps capturing the same screen at once is not guaranteed.** Whether a second
   projection session is allowed, and whether it silently degrades the first, is
   vendor/OS dependent. **UNTESTED on iQOO Z6 Lite 5G.** The design therefore does *not*
   depend on it: integration mode uses one capture (the stream app's).
3. **Two heavy encoders on a Snapdragon 4 Gen 1** would cost CPU/heat even if (2) works.
4. **Audio playback capture** needs `RECORD_AUDIO`, only captures usages we list, and any app
   can opt out (`allowAudioPlaybackCapture=false`). The stream app already proves Free Fire is
   capturable on your device; the clip app must be re-verified.
5. **Microphone is exclusive-ish.** A second app recording the mic while the game or the stream
   app holds it gets silence. Standalone mode therefore records **game audio only**. Your voice and
   teammates only arrive in integration mode, because the stream app already mixes them.
6. `FLAG_SECURE` surfaces render black. The user can end capture from the system chip at any time;
   the service handles this (`MediaProjection.Callback.onStop`).
7. Orientation: capture size is fixed at start (same approach as the stream app's
   Landscape/Portrait setting).

---

## 5. Project structure

```
og-clip-studio/
|-- build.gradle / settings.gradle / gradle.properties / gradlew
|-- .github/workflows/
|   |-- build-apk.yml            debug APK + unit tests on every push
|   |-- release-apk.yml          signed release APK (GitHub Secrets)
|   `-- create-signing-key.yml   one-time: generates the shared keystore
|-- docs/ARCHITECTURE.md
`-- app/src/
    |-- main/java/com/ogclip/studio/
    |   |-- core/      Prefs, ReplayState (live status), Fmt
    |   |-- buffer/    EncodedSample, ReplayRing          (pure Kotlin, unit-tested)
    |   |-- engine/    ReplayEngine (capture + encode)    [Phase 1: standalone source]
    |   |-- clips/     ClipRecord, ClipStore, ClipWriter   (index + MediaStore + MP4 mux)
    |   |-- service/   ReplayService (foreground, notification actions)
    |   `-- ui/        Welcome, Dashboard, Settings, Library, Player, About
    `-- test/          ReplayRingTest
```

Later phases add: `integration/` (ClipClient for the stream app, Phase 2), `overlay/` and
`input/` (floating button, accessibility hotkeys, Phase 2), `editor/` (Phase 3),
`youtube/` (Phase 4), `voice/` and `detect/` (Phase 5).

The ring and the MP4 writer are written independent of where frames come from
(`EncodedSample` in, MP4 out). Phase 1 feeds them from this app's own capture; Phase 2 reuses
the same logic inside the stream app.

---

## 6. Signing: why both apps must share a key

* Signature permissions only work between apps signed with the same certificate.
* GitHub-hosted runners generate a **new random debug key on every run**. Without a fixed key,
  each new APK also refuses to install over the previous one ("signature conflict").
* Solution: create one keystore once (workflow `create-signing-key.yml`), store it as GitHub
  Secrets in **both** repos. Debug and release builds then use it automatically.

---

## 7. Roadmap with acceptance tests

| Phase | Deliverable | Done only when (on the real phone) |
|---|---|---|
| **1** | Project + CI, Dashboard, replay buffer (standalone capture), clip saving, library, playback | A 60 s buffer saves a playable MP4 with correct audio/video sync while Free Fire runs; buffer keeps going after a save; RAM/heat measured |
| **2** | Stream-app patch + `ClipClient`; floating button; accessibility hotkeys; sync verification | Clip taken *during a live stream* contains game + mic audio in sync; stream not disturbed; hotkey/mouse results documented honestly |
| **3** | Trim, 9:16 with adjustable crop, text, watermark, export | Exported Short plays correctly on YouTube |
| **4** | OAuth + resumable upload + history + auto-upload modes | Real private upload appears in YouTube Studio |
| **5** | Voice commands, then AI detection | Detection precision/recall measured on real recordings before any claim |

YouTube caveats to plan for now: an OAuth client must be created in *your* Google Cloud project
(no secret in the APK: use the Android client type with PKCE and your signing-key SHA-1);
unverified API projects can have uploads locked to private until the project passes Google's
compliance audit; daily quota applies (an upload costs a large share of the default quota).
Check Google's current policy text when Phase 4 starts.

---

## 8. What Phase 1 deliberately does not do

* No integration with the stream app yet (Phase 2).
* No mic or teammate audio (standalone mode: game audio only).
* No floating button / hotkeys (Phase 2). Triggers available now: dashboard button and the
  notification's **CLIP NOW** action.
* No editor, no upload. These screens are not present rather than shown as dead buttons.
