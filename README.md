# CutLab — Native Android Video Editor

CutLab is a native Android video editing and motion-graphics studio built with **Kotlin**, **Jetpack Compose (Material 3)**, **Room**, **DataStore**, **AndroidX Media3**, and a custom **OpenGL ES 2.0 / GLSL + MediaCodec + MediaMuxer** rendering pipeline.

It unifies **Quick Edit** and **Advanced Edit** workspaces on top of one shared, non-destructive `ProjectDocument` schema so switching modes never discards edits.

## Key Features (Phases 1 – 10)
- **Quick & Advanced Workspaces**: Adaptive Portrait & Landscape Compose layouts preserving playhead, zoom, and selection across orientation changes.
- **Multi-Track Timeline Engine**: Command-based 60-step Undo/Redo, drag-and-drop track placement, magnetic snapping (to clips, playhead, and beat markers), trim handles, split, duplicate, ripple delete, track mute/lock/reorder, and frame stepping.
- **OpenGL ES 2.0 + GLSL Multi-Layer Compositor**: Ping-pong FBO rendering, dual concurrent hardware OES video decoders, 7 blend modes, SDF alpha masks, adjustment layers, Chroma Key (YCbCr + spill suppression), 3D LUT color grading (`.cube` importer + 7 cinema presets), and custom GLSL shaders (`Chromatic Aberration`, `RGB Split`, `Film Grain`, `Bloom Glow`, `VHS Scanlines`, `Pixelate`, `Crisp Edge`, `Zoom Blur`, `Lens Warp`, `Halation`, `Neon Edge`).
- **Animation & Motion Graphics Engine**: Multi-property keyframes, Newton-Raphson Cubic Bezier curves, interactive zoomable Graph Editor with draggable tangent handles, overshoot/bounce/elastic presets, and SAD optical motion tracking + camera stabilization.
- **Multi-Track Audio Engine**: Background PCM waveform/beat extraction (`MediaExtractor` + `MediaCodec`), 4-slot synchronized playback, sample-rate-converted stereo PCM mixing (`AudioTrack` audition + export mix), Biquad Low/High Shelf EQ, Echo, and equal-power Stereo Panning.
- **Dual-Path Export Engine**:
  - **Path A**: `Media3TransformerExporter` for fast hardware sequential exports.
  - **Path B**: `CustomGlVideoExporter` for layered GLSL compositing, masks, LUTs, keyframes, text rendering, and mixed DSP audio.
  - Validates every exported MP4 via `MediaExtractor` & `MediaMetadataRetriever` before reporting success, and supports Gallery (`MediaStore` API 29+ / SAF API 28) & `FileProvider` sharing.
- **Optimization, Profiling & Release Hardening (Phase 10)**:
  - API 29+ thermal monitoring (`PowerManager`) with safe API 28 fallback.
  - `Application.onTrimMemory` cache coordinator pruning Coil memory cache, GL/LUT textures, and in-memory PCM waveform caches.
  - Adaptive GL preview scaling (`100%` / `75%` / `50%`) that responds dynamically to thermal/memory pressure without degrading full-resolution export quality.
  - Live GL preview FPS tracking, real export throughput telemetry, on-device multi-stage pipeline benchmarking (`Settings -> Performance & Thermal Engine`), crash diagnostic recovery, and R8/ProGuard keep rules (`app/proguard-rules.pro`).

## Compatibility & Build Configuration
- **Package / Application ID**: `com.aistudio.cutlab.vdfqkm` (`namespace = "com.example"`)
- **Minimum SDK**: Android 9.0 Pie (**API 28**)
- **Target / Compile SDK**: Android 16 (**API 36**)
- **Version**: `2.0` (`versionCode = 2`, Studio `v1.0.0`)
- **Permissions**: Zero-permission Android Photo Picker (`PickMultipleVisualMedia`) + Storage Access Framework (`OpenMultipleDocuments` / `OpenDocument`). No broad storage permissions requested.

## Build & Test Instructions
1. **Debug APK Build**:
   ```bash
   ./gradlew assembleDebug
   ```
   Output APK: `app/build/outputs/apk/debug/app-debug.apk`
2. **Unit & Robolectric Test Suite** (verified across Android SDK 28 & SDK 36):
   ```bash
   ./gradlew testDebugUnitTest
   ```
3. **Release Build Verification** (with signing env vars `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD` configured):
   ```bash
   ./gradlew assembleRelease
   ```
   ProGuard/R8 rules are maintained in `app/proguard-rules.pro`.

## Documentation Index
- `PROJECT_ARCHITECTURE.md` — Layered package boundaries (`feature -> engine -> core`).
- `TECHNICAL_DECISIONS.md` — Dual-path rendering, zero-permission media vault, thermal/memory scaling decisions.
- `DEVELOPMENT_ROADMAP.md` — Phase 1–10 milestone completion status.
- `FEATURE_STATUS.md` — Implemented capabilities vs deferred future enhancements.
