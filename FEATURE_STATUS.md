# CutLab Feature Status

## Verified Implemented Capabilities

### Platform & Persistence
- **Platform Compatibility**: Android 9.0 Pie (`minSdk = 28`) through Android 16 (`targetSdk = 36`), `applicationId = com.aistudio.cutlab.vdfqkm`, `versionCode = 2`, `versionName = "2.0"`.
- **Project Management**: Create, open, rename, duplicate, switch Quick/Advanced editor modes without losing state, search, filter, and delete projects.
- **Persistent Schema & Safe Serialization**:
  - Versioned `ProjectDocument` JSON schema (`CURRENT_PROJECT_SCHEMA_VERSION = 2`) stored inside Room (`CutLabDatabase` v2 with `MIGRATION_1_2` adding `proxy_file_path` on `media_assets`) and DataStore studio defaults (`CutLabPreferencesRepository`).
  - `ProjectSchemaMigrator` migrates legacy `v0`/`v1` project JSON forward to `v2`.
  - **Future Schema Read-Only Protection (`schemaVersion > 2`)**: Editable decode APIs (`ProjectEngine.deserialize()`, `ProjectEngine.deserializeWithReport()`) throw `UnsupportedFutureSchemaException` on `schemaVersion > CURRENT_PROJECT_SCHEMA_VERSION`, keeping future schemas strictly separated from editable workflows. Read-only inspection (`ProjectEngine.openProjectJson()`, `decodeFutureSchemaReadOnlyPreview()`, `ProjectRepository.observeProjectLoadState()`) emits `UnsupportedFutureVersion` with preserved raw JSON, marks the workspace read-only (`future_schema_readonly_banner` + disabled edits/rename/mode switch), and blocks `ProjectEngine.serialize()` and all repository writes so unknown future fields cannot be stripped or overwritten on disk.
  - Per-project `Mutex` write serialization and corrupt-JSON non-overwrite protection (`decodeDocumentStrictOrNull`) prevent malformed JSON rows from being overwritten with blank timelines.

### Media Import (Zero-Permission)
- Video & Photo import via Android Photo Picker (`PickMultipleVisualMedia`); Audio & `.cube` LUT import via Storage Access Framework (`OpenMultipleDocuments` / `OpenDocument`).
- 64KB buffered local copies into app-private storage (`filesDir/cutlab_media/<projectId>/`) with real `MediaMetadataRetriever` + bounds-only `BitmapFactory` validation.

### Adaptive Workspace & Playback
- Adaptive Portrait & Landscape Compose editor preserving playhead, selection, zoom, and draft state across configuration changes.
- Drift-free preview playback loop anchored against elapsed `System.nanoTime()`, Jump Start/End, and rational frame stepping (`-1F` / `+1F` via `TimelineTimebase.stepFrameMillis`).
- Modular coordinator separation behind `ProjectWorkspaceViewModel` (`WorkspaceEditSession`, `WorkspaceMediaAnalysisCoordinator`, `WorkspaceAudioCoordinator`, `WorkspaceExportCoordinator`) with type-safe flow state combination.

### Non-Destructive Timeline Engine
- **Session-Scoped Undo / Redo**: Bounded in-memory 60-step command stacks (`TimelineCommandHistory` managed by `WorkspaceEditSession`); each committed edit, undo, or redo snapshot is persisted immediately to Room (`ProjectRepository.saveDocument`). Undo/Redo does not survive full process death/reopen, and external project metadata edits (rename, FPS, aspect ratio, mode, export settings) plus deleted-asset pruning are rebased cleanly onto existing history entries.
- **Overlap Prevention + Confirmation-Gated Legacy Overlap Repair**:
  - New timeline mutations (`insertAssetAsClip`, drag-and-drop `moveClip`, `trimClipLeft`/`trimClipRight`, `splitClipAt`, `duplicateClip`) prevent same-track overlaps; multi-layering uses separate tracks ordered bottom-to-top, with `0..60ms` adjacent outgoing transition evaluation.
  - Loading or saving legacy projects does **not** silently shift overlapping clips. Instead, `TimelineEngine.inspectTrackOverlaps()` builds a `TimelineOverlapRepairReport` and `TimelineEngine.evaluateFrame()` / `MultiTrackAudioMixer` evaluate all active clips on unrepaired tracks without silent drops. From the workspace banner (`Review & Repair`), an explicit confirmation dialog displays affected track, affected clip, original timeline position, proposed destination track, and whether timing will change before applying `MOVE_TO_OVERFLOW_TRACK` (default) or `SHIFT_FORWARD_ON_TRACK`.
- **Timebase Architecture (4 Distinct Layers)**:
  - *Millisecond-based project storage*: Clip positions, trims, durations, keyframes, and markers persist as `Long` millisecond fields in `ProjectDocument` (the timeline storage model is **millisecond-based, not frame-native**).
  - *`RationalTime` & `TimelineTimebase` conversion utilities*: Half-up rounding converters (`RationalTime`, `frameToMillis`, `millisToNearestFrame`, `frameToPtsNs`, `audioFramesDelta`, `scaleSourceToTimelineMs`, `scaleTimelineToSourceMs`) prevent cumulative truncation drift.
  - *Frame-snapped editing operations*: Transport frame stepping (`-1F` / `+1F`) snaps to the nearest project-FPS frame grid via `TimelineTimebase.stepFrameMillis()`, while speed edits, beat quantization, and source trim limits use rounded rational scaling; free drag/trim gestures still store `Long` milliseconds.
  - *Frame-based export timestamps*: Path B (`CustomGlVideoExporter`) iterates integer output frame indices (`0 until totalFrames`), generating exact nanosecond EGL presentation timestamps (`TimelineTimebase.frameToPtsNs`) and cumulative sample-accurate AAC audio chunk counts (`TimelineTimebase.audioFramesDelta`).

### Compositor, Effects & Animation (Live Preview + Path B Export)
- **OpenGL ES 2.0 + GLSL Multi-Layer Pipeline**: Dedicated `GlCompositorRenderer` (ping-pong FBOs, dual `SurfaceTexture` OES video decoders, LRU image/LUT texture caches) and `CustomGlVideoExporter` (offscreen EGL surface encoder) sharing `GlCompositorMath`, `CompositorShaders`, `CompositorEngine`, and `AnimationEngine`.
- **Transforms, Blend Modes & SDF Masks**: `Position X/Y`, `Scale X/Y`, `Rotation`, `Anchor X/Y`, and `Opacity`; 7 GLSL blend modes (`Normal`, `Add`, `Multiply`, `Screen`, `Overlay`, `Darken`, `Lighten`); `Rectangle` (with rounded corners), `Ellipse`, and `Linear Split` SDF masks aligned between `CompositorEngine` and GLSL.
- **Adjustments, Filters, Shaders & 3D LUTs**: 8 filter presets, `Brightness`, `Contrast`, `Saturation`, `Exposure`, `Temperature`, `Tint`, `Vignette`, GLSL 9-tap `Soft Blur` (`blurRadius`), and GLSL unsharp `Sharpen`; 11 GPU shaders (`Chromatic Aberration`, `RGB Split`, `Film Grain`, `Bloom Glow`, `VHS Scanlines`, `Pixelate`, `Crisp Edge`, `Zoom Blur`, `Lens Warp`, `Halation`, `Neon Edge`); YCbCr Chroma Key + spill suppression; 7 procedural 3D LUTs + SAF `.cube` importer.
- **Adjustment Layers**: Cascading `ADJUSTMENT` tracks accumulate color grading bottom-to-top (`EffectsEngine.mergeAdjustments`), while the highest active adjustment track above a visual layer supplies the active GPU shader/LUT slot.
- **Text & Kinetic Typography**: `TEXT` clips with 6 style presets, 6 animations (`None`, `Fade`, `Slide Up`, `Pop`, `Typewriter`, `Glitch`), stroke/shadow/background pill styling, stage-proportional font sizing (`0.068 * minDim`), and anchor-pivoted transforms across both live Compose overlay preview and Path B `TextLayerBitmapRenderer`.
- **Keyframing & Optical Analysis**: 13 keyframable properties evaluated via Linear, Ease In/Out/In-Out, Hold, and Newton-Raphson Cubic Bezier curves, interactive zoomable Graph Editor, SAD optical motion tracking, median-consensus stabilization, and piecewise-linear speed ramping (`SpeedRampEngine`) + reverse playback.

### Audio, Dual-Path Export & Performance
- **Audio Engine**: Background `MediaExtractor` + `MediaCodec` PCM waveform/beat extraction, sample-rate-converted stereo PCM mixing (`MultiTrackAudioMixer`) with keyframed volume, fades, biquad EQ presets, echo, equal-power pan (`AudioDsp`), and `AudioTrack` 2.5s PCM mix audition (`WorkspaceAudioCoordinator`).
- **Dual-Path Export (`ExportPlanner` + `CutLabExportCoordinator`)**: Dispatches sequential un-edited/simple clips to Path A (`Media3TransformerExporter`) and routes gaps, non-unity volume, muted audio, multi-track overlays, text, adjustment layers, transitions, blend modes, masks, shaders, LUTs, keyframes, speed ramps, or DSP effects cleanly to Path B (`CustomGlVideoExporter`), with automatic fallback and post-export MP4 container validation.
- **Optimization & Telemetry**: `DevicePerformanceMonitor` (API 29+ thermal listener with API 28 fallback, memory trim listener, GL frame timing, crash log capture, and persistent pipeline benchmark runner) + 540p proxy media workflow.

## Known Limitations & Verified Caveats
- **Live Audio Preview vs. Path B Export**: During normal timeline playback, `StudioMediaPlayer` uses 4 `ExoPlayer` instances (supporting speed, keyframed volume/fades, and muting) and does **not** run real-time `AudioDsp` FX (EQ, Echo, Stereo Pan) or reversed audio (`player.volume = 0f` when reversed). Full DSP FX and reversed audio are heard in **PCM Audition (2.5s mix preview)** and baked cleanly into **Path B Exports**.
- **Text Layer Blend Mode Composition Boundary**: In live preview, `TEXT` overlays render in Compose above the GL `TextureView`, whereas Path B export renders text bitmaps inside the GL FBO ping-pong chain (`blendLayerFboIntoBase`). Standard `NORMAL` alpha blending matches closely; exotic blend modes (`ADD`, `MULTIPLY`, etc.) on text clips depend on Compose view compositing during live preview vs GL framebuffer blending on export.
- **Path B Video Decode Throughput**: `CustomGlVideoExporter` samples video frames through `MediaMetadataRetriever` (`OPTION_CLOSEST` / `OPTION_CLOSEST_SYNC`) per output frame for broad format compatibility across arbitrary speed ramps and reverse playback, which trades decode speed for accuracy relative to Path A hardware transcoding.
- **In-Memory Undo/Redo Session**: Command history (`maxEntries = 60`) resets when closing and reopening the editor workspace; committed project snapshots remain persisted in Room.
- **Unsupported Future Schema Handling**: Projects saved with `schemaVersion > 2` open read-only; mutating actions and saves are blocked rather than round-tripping unknown keys through `kotlinx.serialization`.
- **Millisecond Drag vs. Frame Stepping**: Free drag-and-drop moves and trim handles adjust persisted `Long` millisecond positions (with clip/marker magnetic snapping); frame-grid quantization is enforced on transport `-1F`/`+1F` steps, speed/source calculations, and Path B frame export timestamps.
- **Not Yet Implemented (Future Milestones)**: Auto Caption, Auto Lyric, Invert Lyric, freeform Bezier pen masks, and on-device neural background segmentation.
