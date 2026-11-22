# CutLab Project Architecture

## Overview
CutLab is a native Android video editor built with Kotlin, Jetpack Compose, Material 3, Room, DataStore, Jetpack Media3 (`ExoPlayer` + `Transformer`), and a custom OpenGL ES 2.0 + GLSL + `MediaCodec` + `MediaMuxer` rendering pipeline. It separates UI workspaces and coordinators from non-destructive project models, timeline commands, GPU shaders, DSP audio, and export orchestration.

## Package Structure (`com.example`)
Layers follow Clean Architecture + MVVM with strict dependency direction (`feature -> engine -> core`):

- `core/common`: Timebase layer distinguishing (a) millisecond-based `ProjectDocument` storage from (b) `RationalTime` & `TimelineTimebase` half-up conversion utilities (`frameToMillis`, `millisToNearestFrame`, `frameToPtsNs`, `audioFramesDelta`, speed/ratio scaling), (c) frame-snapped stepping (`stepFrameMillis`) + rounded speed/trim math, and (d) frame-indexed export timestamps (`frameToPtsNs`); plus timecode and file size formatters.
- `core/designsystem` & `ui/theme`: Obsidian dark studio theme (`CutLabTheme`), `Space Grotesk` / `Inter` / `JetBrains Mono` typography, and reusable studio panels/badges.
- `core/database`: Room `CutLabDatabase` v2 (`projects`, `media_assets`, `MIGRATION_1_2` adding `proxy_file_path`), reactive DAOs, and DataStore `CutLabPreferencesRepository` (aspect ratio, FPS, editor mode, proxy toggle, preview quality mode).
- `core/storage`: `MediaStorageManager` for streaming 64KB buffered import copies into app-private storage (`filesDir/cutlab_media/<projectId>/`), orphan pruning, and storage diagnostics.
- `core/media`: `MediaMetadataExtractor` (`MediaMetadataRetriever` + bounds-only `BitmapFactory`) and `ProxyMediaManager` (Media3 Transformer 540p H.264 proxy transcoding).
- `core/graphics`:
  - `GlCompositorMath`: shared pure-Kotlin math for aspect-fit & anchor-pivoted layer/text MVP matrices, transition curves, outgoing layer curves, and GLSL 4×4 color matrix uniforms.
  - `CompositorShaders`: shared GLSL ES 2.0 shaders (9-tap blur, unsharp sharpen, 11 effect shaders, YCbCr chroma key + spill suppression, `256×16` 2D-strip 3D LUT interpolation, rounded-rect/ellipse/linear SDF masks, and 7 blend modes).
  - `GlCompositorRenderer`: live EGL14 window compositor with ping-pong FBOs, dual `SurfaceTexture` OES video decoders, LRU texture/LUT caches, adaptive preview FBO scaling, and memory trim handlers.
- `core/performance`: `DevicePerformanceMonitor` (API 29+ thermal listener with API 28 fallback, JVM heap monitor, `onTrimMemory` dispatch, GL frame latency tracker, crash diagnostic recorder, and persistent pipeline/export benchmark history).
- `engine/project`:
  - `ProjectModels`: Versioned `@Serializable` `ProjectDocument` schema (`CURRENT_PROJECT_SCHEMA_VERSION = 2`) using `Long` millisecond clip timing (`timelineStartMs`, `sourceStartMs`, `durationMs`), clip/track/keyframe/effect/LUT/speed-ramp definitions.
  - `ProjectSchemaMigrator`: explicit forward JSON migration pipeline (`v0/v1 -> v2`) and schema version inspection (`inspectSchemaVersion`). Projects with `schemaVersion > CURRENT_PROJECT_SCHEMA_VERSION` are detected before decoding and never migrated or re-serialized destructively.
  - `ProjectEngine`: JSON codec (`ignoreUnknownKeys = true` for forward tolerance on readable schemas, `coerceInputValues = true`), strict separation between supported editable decoding (`deserialize()`, `deserializeWithReport()`, which reject `schemaVersion > 2` with `UnsupportedFutureSchemaException`), read-only future preview decoding (`openProjectJson()`, `decodeFutureSchemaReadOnlyPreview()`), serializer guard (`UnsupportedFutureSchemaException` on `schemaVersion > CURRENT_PROJECT_SCHEMA_VERSION`), and foundation track creation.
  - `ProjectRepository`: per-project `Mutex`-serialized writes, strict read-only / non-overwrite guards for both unsupported future schema versions (`schemaVersion > 2`) and corrupt JSON (`decodeDocumentStrictOrNull`, `detectStoredFutureVersion`), non-mutating load-time overlap inspection (`ProjectRepositoryLoadState.Ready.overlapRepairReport`), explicit `applyOverlapRepair()`, and asset management.
- `engine/timeline`:
  - `TimelineEngine`: non-destructive timeline mutations enforcing non-overlapping intervals on **new** edits (insert, move/drag, trim, split, duplicate), multi-clip `evaluateFrame()` iteration (so unrepaired legacy tracks evaluate all active clips without silent drops), and non-destructive legacy overlap handling (`inspectTrackOverlaps()` -> `TimelineOverlapRepairReport` listing affected tracks/clips/original & proposed positions/timing impact; explicit `repairOverlappingTracks()` defaulting to timing-preserving `MOVE_TO_OVERFLOW_TRACK` or opt-in `SHIFT_FORWARD_ON_TRACK`, triggered only after explicit user confirmation in `ProjectWorkspaceScreen`).
  - `TimelineCommandHistory`: session-scoped bounded in-memory Undo/Redo stacks (`maxEntries = 60`) with `rebaseMetadata()` (preserving external project rename/FPS/mode/export settings across Undo/Redo) and `pruneDeletedAsset()`.
  - `SpeedRampEngine`: piecewise-linear integral source-time solver and reverse timing.
- `engine/compositor`: `CompositorEngine` (z-order visual stack evaluation, rounded-rectangle/ellipse/split SDF reference matching GLSL, active transition timing, bottom-to-top cascaded color adjustments with top-most adjustment shader/LUT precedence).
- `engine/animation`: `AnimationEngine` (Cubic Bezier Newton-Raphson solver, overshoot/bounce presets, safe expressions) and `MotionTrackingEngine` (SAD optical patch tracker + median-consensus trajectory stabilization).
- `engine/effects`: `EffectsEngine` (4×5 RGBA color matrices, filter presets, text presets, easing curves) and `Lut3DEngine` (Adobe `.cube` parser, 7 procedural cinema LUTs, trilinear resampling, `256×16` RGBA GL strip packer).
- `engine/audio`: `PcmAudioExtractor`, `AudioDsp` (biquad EQ, echo, equal-power pan), `MultiTrackAudioMixer` (sample-accurate rounded frame slicing, all-active clip evaluation, keyframed volume + fades + soft limiting), and `MixedAudioPreviewPlayer`.
- `engine/export`: Dual-path `ExportPlanner` (dispatching gaps, non-unity audio volume, track mute, overlays, text, masks, shaders, LUTs, speed ramps, or transitions to Path B), `CutLabExportCoordinator`, `Media3TransformerExporter` (Path A), `CustomGlVideoExporter` (Path B offscreen EGL + shared `GlCompositorMath`/`CompositorShaders` + sample-accurate PCM->AAC `MediaMuxer`), `TextLayerBitmapRenderer`, and `ExportOutputManager`.
- `feature/home`, `feature/mediaimport`, `feature/settings`: Project library, global Media Vault, and Studio Settings / Benchmark Console.
- `feature/project`: Adaptive Portrait & Landscape workspace (`ProjectWorkspaceScreen`), Slim MVVM ViewModel + extracted coordinators:
  - `ProjectWorkspaceViewModel` (UI state assembly using typed intermediate combine bundles, playback clock anchored cleanly on `System.nanoTime()`, and UI interaction dispatching),
  - `WorkspaceEditSession` (Undo/Redo session stacks + gesture draft staging),
  - `WorkspaceMediaAnalysisCoordinator` (waveform queue, `.cube` LUT import, optical motion tracking, stabilization, proxy transcode triggers),
  - `WorkspaceAudioCoordinator` (PCM mix audition playback),
  - `WorkspaceExportCoordinator` (capability probing, export launch/cancel, Gallery/SAF/Share delivery).
- `feature/timeline` & `feature/export`: Interactive multi-track timeline editor and Export Studio sheet.
