# CutLab Technical Decisions

1. **Zero-Permission Media Access + Managed Local Copies**:
   - CutLab uses `ActivityResultContracts.PickMultipleVisualMedia` (Photo Picker) for video/photos and `OpenMultipleDocuments` / `OpenDocument` (SAF) for audio and `.cube` LUT files, avoiding broad storage permissions (`READ_EXTERNAL_STORAGE`, `READ_MEDIA_*`) for Google Play policy compliance.
   - Because Photo Picker URIs are temporary by default, `MediaStorageManager` streams imported media into app-private storage (`filesDir/cutlab_media/<projectId>/`) on `Dispatchers.IO` with a 64KB buffer so projects reopen reliably across restarts.

2. **Four-Layer Timebase Over Millisecond Storage (`RationalTime` & `TimelineTimebase`)**:
   - **Millisecond-Based Project Storage**: `ProjectDocument` persists clip positions, trims, durations, keyframes, transitions, and markers as `Long` milliseconds for backward compatibility; the stored timeline model is intentionally **millisecond-based, not frame-native**.
   - **Conversion Utilities**: `RationalTime` and `TimelineTimebase` (`core/common/TimeFormatting.kt`) supply half-up conversions between milliseconds, frame indices, nanoseconds, audio sample counts, and speed-scaled spans to prevent cumulative truncation drift.
   - **Frame-Snapped Editing Operations**: Transport stepping (`-1F` / `+1F`) quantizes to `fps` via `TimelineTimebase.stepFrameMillis`, while speed changes, reverse trims, and beat placements round through `scaleSourceToTimelineMs` / `scaleTimelineToSourceMs`; free drag/trim gestures still commit `Long` millisecond timestamps.
   - **Frame-Based Export Timestamps**: Path B (`CustomGlVideoExporter`) steps integer output frame indices (`0 until totalFrames`) and drives `EGLExt.eglPresentationTimeANDROID` directly from `TimelineTimebase.frameToPtsNs(frameIdx, fps)` and AAC chunk sizing from cumulative `TimelineTimebase.audioFramesDelta`.

3. **Non-Overlapping New Edits + Confirmation-Gated Legacy Overlap Inspection & Repair**:
   - All new timeline edits (`insertAssetAsClip`, drag-and-drop `moveClip`, `trimClipLeft`/`trimClipRight`, `splitClipAt`, `duplicateClip`) keep tracks non-overlapping; multi-layer compositing and multi-track audio belong on separate tracks (`OVERLAY`, `AUDIO`, `TEXT`, `ADJUSTMENT`).
   - Rather than silently shifting overlapping legacy clips forward on load/save (which breaks A/V and beat-marker synchronization), `ProjectRepository` leaves timing untouched, runs `TimelineEngine.inspectTrackOverlaps()` to produce a `TimelineOverlapRepairReport` (affected tracks, clips, original timing, conflicting IDs, and proposed repair positions), and exposes all active clips on unrepaired tracks in both `TimelineEngine.evaluateFrame()` and `MultiTrackAudioMixer`.
   - Applying repair in `ProjectWorkspaceScreen` requires an explicit user confirmation dialog showing each affected track, affected clip, original timeline position, proposed destination track, and whether timing will change, with `MOVE_TO_OVERFLOW_TRACK` selected by default (`SHIFT_FORWARD_ON_TRACK` remains an explicit opt-in option).
   - `ExportPlanner` strictly inspects tracks for inter-clip/leading gaps (`> 0ms`), muted track clips, and non-unity clip volumes so edited timelines never accidentally route to linear sequential Path A concatenation.

4. **Versioned Schema Migration (`v1 -> v2`), Separated Editable vs. Read-Only Future-Schema Decoding, & Corrupt-JSON Safety**:
   - `CURRENT_PROJECT_SCHEMA_VERSION = 2` alongside `ProjectSchemaMigrator`, which performs stepwise non-destructive JSON upgrades (`v0/v1 -> v2`: filling missing track `clips`/`orderIndex`, clip `trackId`/`clipKind`, `markers`, `exportSettings`) prior to `kotlinx.serialization` decoding.
   - Because `kotlinx.serialization` with `ignoreUnknownKeys = true` drops unrecognized JSON keys upon re-serialization (which would destroy future fields such as upcoming `captionTracks` / `lyricAlignmentMode`), `ProjectEngine` strictly separates **supported editable decoding** (`deserialize()`, `deserializeWithReport()`, which throw `UnsupportedFutureSchemaException` whenever `schemaVersion > CURRENT_PROJECT_SCHEMA_VERSION`) from **read-only future preview decoding** (`openProjectJson()` returning `UnsupportedFutureVersion` / `decodeFutureSchemaReadOnlyPreview()`). `ProjectEngine.serialize()` also throws `UnsupportedFutureSchemaException` for `schemaVersion > 2`, and `ProjectRepository` blocks all writes/imports/deletes against future-schema rows, preserving original JSON intact on disk.
   - `ProjectRepository` serializes document writes per project with a `Mutex` and refuses to overwrite unparseable existing `projectJson` rows with an empty blank project.

5. **Session-Scoped Undo/Redo with Metadata Rebase & Asset Pruning**:
   - Undo/Redo stacks (`TimelineCommandHistory` inside `WorkspaceEditSession`) remain bounded (60 steps) and in-memory for fast responsive scrubbing/editing, while every committed edit, undo, or redo immediately saves the active `ProjectDocument` snapshot to Room.
   - Storing full serialized history stacks in SQLite was deliberately avoided to keep Room transactions small and avoid stale asset/schema bloat; instead, `rebaseMetadata()` propagates non-history metadata updates (rename, canvas FPS/aspect, mode, export settings) across both stacks, and `pruneDeletedAsset()` strips tombstoned media references cleanly.

6. **Shared GLSL / Pure-Kotlin Composition & Coordinator Separation**:
   - Both live GL preview (`GlCompositorRenderer` + `StudioMediaPlayer`) and Path B export (`CustomGlVideoExporter`) call `GlCompositorMath` (aspect-fit & anchor-pivoted MVP matrices, transition curves, color matrix uniforms), `CompositorShaders` (including unified 9-tap blur & unsharp sharpen), `CompositorEngine`, `AnimationEngine`, and proportional `0.068f * minDim` text sizing.
   - `ProjectWorkspaceViewModel` delegates long-running analysis, PCM auditioning, export orchestration, and edit session state to focused coordinators (`WorkspaceEditSession`, `WorkspaceMediaAnalysisCoordinator`, `WorkspaceAudioCoordinator`, `WorkspaceExportCoordinator`) using strongly typed state bundles instead of unchecked collection casts.

7. **Hybrid Dual-Path Rendering Strategy & Adaptive Thermal/Memory Scaling (API 28–36)**:
   - Unmodified sequential timelines route to **Path A (`Media3TransformerExporter`)**, while composited/FX/gap/audio-mixed timelines route to **Path B (`CustomGlVideoExporter`)**.
   - `DevicePerformanceMonitor` monitors thermal status (API 29+ with API 28 fallback) and `onTrimMemory` callbacks to downscale live preview FBOs (`100%` -> `75%` -> `50%`) and prune texture/PCM caches without compromising full-resolution exports.
