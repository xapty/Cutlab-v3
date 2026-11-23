TASK 003.1 — sherpa-onnx Whisper Migration + Universal Text Transform

Objective

Upgrade CUTLAB in two areas:

A. ASR Engine Migration
Replace the existing Vosk-based Auto Caption backend with sherpa-onnx using multilingual Whisper ONNX models.

B. Universal Text Transform
Replace fixed-position text rendering with a transform system that allows users to freely position, scale, and rotate all text-based clips.

Supported clip types:

- Text
- Caption
- Lyric

This is an extension of the existing TASK 003 implementation, not a redesign of CUTLAB.

Preserve project schema v3 and existing functionality wherever possible.

---

PART A — ASR ENGINE MIGRATION

1. Mandatory Pre-Implementation Audit

Before editing anything:

1. Inspect the current source.

2. Locate all Vosk dependencies, recognizer implementations, model managers, PCM processing, and ASR-related ViewModels.

3. Identify the existing ASR provider abstraction.

4. Trace the complete Auto Caption pipeline:
   
   Audio extraction → PCM preparation → ASR → segmentation → timestamp conversion → caption clip creation → timeline insertion.

5. Inspect existing text clip models and rendering architecture.

6. Identify existing transform properties, if any, before introducing new ones.

Produce a short implementation plan.

Do not rewrite unrelated modules.

2. Replace Vosk with sherpa-onnx + Whisper

Use:

- sherpa-onnx Android runtime.
- Multilingual Whisper ONNX models.
- Offline recognition.

Official references:

- https://github.com/k2-fsa/sherpa-onnx
- https://k2-fsa.github.io/sherpa/onnx/android/index.html
- https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/index.html
- https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/export-onnx.html

Requirements:

- Remove Vosk from the active recognition pipeline.
- Remove obsolete Vosk dependencies and code when no longer required.
- Preserve the existing ASR provider abstraction where practical.
- Implement a dedicated sherpa-onnx Whisper provider.
- Keep recognition off the main thread.
- Support coroutine cancellation.
- Dispose native resources safely.
- Prevent concurrent recognizer instances from exhausting memory.
- Handle missing, corrupt, and incompatible models.

Do not introduce a second caption-generation pipeline.

3. Whisper Model Management

Initial supported models:

Whisper Tiny — Multilingual INT8

- Default model.
- Designed for broad Android compatibility.
- Supports Indonesian, English, and Japanese.
- Download on demand.

Whisper Base — Multilingual INT8

- Optional higher-accuracy model.
- Download on demand.
- Do not load both models simultaneously without evaluating memory requirements.

Requirements:

- Use compatible official sherpa-onnx model releases.
- Verify actual model archive structure and filenames.
- Verify downloaded files before activation.
- Support progress, cancellation, retry, and deletion.
- Store models in app-private storage.
- Prevent duplicate downloads.
- Never load incomplete model files.
- Display model name, size, and download status.

Do not bundle large model files into the APK.

4. Language Support

The initial language requirements are:

Language| Code| Requirement
Indonesian| id| Required
English| en| Required
Japanese| ja| Required
Automatic detection| Auto| Required where supported

Important:

- Use multilingual Whisper models, not language-specific English models.
- Japanese must be a first-class supported language, not an experimental option.
- Pass the correct language configuration to the recognizer.
- Preserve Japanese characters and punctuation through the entire caption pipeline.
- Ensure the UI, text model, and rendering system handle Japanese Unicode correctly.
- Do not corrupt Japanese text during serialization, editing, export, or project reopening.
- Handle unsupported language selections gracefully.

Test natural Japanese speech, including sentences with mixed Kanji, Hiragana, and Katakana.

Do not assume that a language appearing in the UI means it has been verified.

5. Audio Processing

Preserve the existing audio extraction pipeline unless a concrete incompatibility requires modification.

Requirements:

- Chunked audio extraction.
- Correct mono/stereo handling.
- Correct sample-rate conversion where necessary.
- Preserve original audio timeline offsets.
- Avoid loading entire long recordings into RAM.
- Process long recordings in manageable chunks.
- Provide progress and cancellation.
- Prevent duplicated or missing audio at chunk boundaries.

Verify sample-rate handling against the actual sherpa-onnx API.

6. Timestamp and Word Alignment

Preserve existing word-level timing support wherever Whisper provides it.

Investigate:

- Segment timestamps.
- Token timestamps.
- Cross-attention alignment and DTW.
- ONNX model export requirements for timestamp support.

Do not claim precise word timing if the selected model does not provide it.

If reliable word-level timestamps are unavailable:

1. Preserve segment-level timestamps.
2. Use existing estimated word timing where appropriate.
3. Mark estimated timing as estimated.
4. Never silently present estimated timing as precise recognition output.

Also fix the previously identified caption overlap issue.

When a cue is shifted to prevent overlap, its associated absolute word timestamps must be shifted by the same delta or corrected using another mathematically valid strategy.

Add regression tests.

7. Auto Caption UI

Keep the existing Auto Caption interface.

Expose:

- Recognition engine: sherpa-onnx Whisper.
- Model selection: Tiny / Base.
- Language: Auto Detect / Indonesian / English / Japanese.
- Model download status.
- Recognition progress.
- Cancel recognition.
- Clear error messages.

Clearly distinguish:

- Model not downloaded.
- Model downloading.
- Model ready.
- Model loading.
- Recognition in progress.
- Recognition completed.
- Recognition failed.

Do not redesign the entire caption panel.

---

PART B — UNIVERSAL TEXT TRANSFORM

8. Objective

Currently, text clips appear to use a fixed positioning system.

Replace this limitation with a reusable transform system shared by:

- Regular Text Clips.
- Auto Caption Clips.
- Lyric Clips.

All three clip types must support independent positioning and transformation.

The goal is to give CUTLAB a flexible text-editing experience closer to a lightweight After Effects workflow.

Do not create three separate transform implementations.

9. Transform Properties

Every text-based clip must support:

Position

- X position.
- Y position.
- Horizontal and vertical movement.
- Center alignment.
- Ability to place text anywhere inside or outside the visible canvas when appropriate.

Scale

- Uniform scaling.
- Independent X and Y scaling if supported by the rendering architecture.
- Reset scale.
- Preserve aspect ratio when uniform scaling is enabled.

Rotation

- Rotation in degrees.
- Support positive and negative rotation.
- Reset rotation.

Anchor / Pivot

- Support a configurable transform origin where practical.
- Default to the center of the text element.
- Ensure position and rotation behave predictably when the anchor changes.

Transform Defaults

New text clips should receive sensible default transform values.

Existing projects must retain their current visual appearance after migration or default initialization.

Do not silently change the position of existing captions or lyrics.

10. Interactive Preview Transformation

The preview must become interactive for selected text clips.

Required interactions:

- Select a text element in the preview.
- Drag it to change position.
- Display a selection outline or bounding box.
- Show transform handles where practical.
- Provide visual feedback while dragging.
- Reflect changes immediately in the preview.
- Keep the timeline clip selection synchronized with preview selection.

Do not allow accidental changes to the underlying video when manipulating text.

Support an explicit transform/edit mode if necessary to avoid conflicts with normal preview playback or selection.

The preview must remain responsive during interaction.

11. Transform Inspector UI

Add a reusable Transform section to the existing text editing interface.

Expose:

Property| Control
Position X| Numeric input
Position Y| Numeric input
Scale X| Numeric input / slider
Scale Y| Numeric input / slider
Rotation| Numeric input / slider
Reset| Reset transform values

Additional controls where practical:

- Uniform scale toggle.
- Center horizontally.
- Center vertically.
- Reset position.
- Reset rotation.
- Reset all transforms.

The controls must update the selected clip immediately.

Use the same inspector component for Text, Caption, and Lyric clips.

Do not build three separate UI panels with duplicated transform logic.

12. Transform Coordinate System

This section is critical.

Inspect the existing preview and export coordinate systems before implementation.

Define a consistent transform coordinate system.

Preferred approach:

- Store positions relative to the project canvas.
- Use normalized coordinates or another resolution-independent representation.
- Define a clear origin, preferably the canvas center.
- Keep position independent of device screen dimensions.
- Apply transforms consistently regardless of preview aspect ratio or export resolution.

Requirements:

- A text element positioned at a specific location in the preview must appear at the corresponding location in exported video.
- Changing preview device dimensions must not change the actual project position.
- Rotation and scaling must use consistent coordinate conventions.
- Avoid hardcoded screen pixel positions.

Do not mix screen coordinates, canvas coordinates, and export coordinates without explicit conversion functions.

13. Shared Rendering Integration

Integrate transform properties into the existing text rendering architecture.

Inspect and update the necessary components, including:

- UnifiedTextEngine.
- LyricEngine.
- TextLayerBitmapRenderer.
- GlCompositorRenderer.
- CompositorShaders.
- CustomGlVideoExporter.
- ExportPlanner.
- Compose preview rendering.

Use the existing rendering pipeline wherever possible.

Requirements:

- Text transforms work in preview.
- Text transforms work in export.
- Captions and lyrics use the same transform conventions as regular text.
- Rotation and scaling do not break text clipping or bounding-box calculations.
- Transform changes do not alter word timing or karaoke progression.
- Invert Lyric must continue working correctly with transformed text.

Do not create a separate export renderer just for transformed text.

14. Caption and Lyric Behavior

Every caption and lyric clip must support individual transforms.

Examples:

- Move a caption from the bottom to the top.
- Move one lyric line to the center.
- Scale a specific lyric clip.
- Rotate a single text clip.
- Create different visual positions for different caption clips.

Do not assume all captions or all lyrics must share one fixed position.

If a user changes one clip's transform, other clips must remain unchanged unless the user explicitly uses a future batch-edit operation.

Do not modify caption timing, lyric synchronization, or karaoke word progression when changing transforms.

---

PART C — ARCHITECTURE AND COMPATIBILITY

15. Data Model and Schema

Inspect the existing text clip model before making changes.

If suitable transform properties already exist, reuse them.

Otherwise, introduce a reusable transform data structure.

Example conceptual structure:

- positionX
- positionY
- scaleX
- scaleY
- rotation
- anchorX
- anchorY

This is a conceptual design, not a requirement to use these exact property names.

Requirements:

- Transform values must be persisted per clip.
- All text-based clip types must share the same transform representation.
- Existing projects must retain their previous appearance.
- Serialization and deserialization must preserve transform values.
- Save/reopen must preserve visual placement.

Only increment the project schema if a real persistent-data migration is necessary.

If schema migration is required:

- Provide a safe v3 → v4 migration.
- Assign backward-compatible defaults.
- Preserve all existing clip content and timing.
- Retain future-schema read-only protection.
- Add migration tests.

Do not increment the schema merely because the ASR engine changes.

16. Architecture and Lifecycle

Maintain CUTLAB's architecture:

Feature → Engine → Core.

Requirements:

- Keep native sherpa-onnx calls behind the ASR provider abstraction.
- Keep transform calculations in reusable engine/domain logic.
- Keep Compose UI separate from rendering calculations.
- Avoid duplicate transform implementations.
- Avoid blocking the main thread.
- Handle cancellation and resource cleanup safely.

Do not rewrite unrelated architecture.

17. Regression Protection

All existing functionality must continue working:

- Manual Lyric Studio.
- LRC import.
- Word-level timing.
- Karaoke modes.
- Invert Lyric.
- Caption segmentation.
- Timeline insertion.
- Undo/redo.
- Project save/reopen.
- Preview/export parity.

Transform changes must not break any of these features.

---

PART D — TESTING

18. ASR Tests

Test:

- Model configuration.
- Indonesian recognition.
- English recognition.
- Japanese recognition.
- Automatic language detection.
- Missing model handling.
- Invalid model handling.
- Recognition cancellation.
- Native resource lifecycle.

Use provider test doubles where native execution is unavailable.

Do not claim native recognition was tested when only mocks were used.

19. Transform Tests

Add tests for:

Transform calculations

- Default transform.
- Position conversion.
- Scale conversion.
- Rotation.
- Anchor behavior.
- Resolution-independent positioning.
- Reset behavior.

Clip compatibility

- Regular text transform.
- Caption transform.
- Lyric transform.
- Independent transforms across multiple clips.
- Save and reopen transform persistence.

Rendering

- Preview transform application.
- Export transform application.
- Rotated text bounds.
- Scaling behavior.
- Invert Lyric with transformed text.
- Karaoke highlighting with transformed text.

Regression

- Existing projects retain their previous text positions.
- Transform changes do not alter clip timing.
- Transform changes do not alter word-level timestamps.
- Undo/redo restores previous transform values.

20. Device Verification

If an Android device or emulator is available, test:

ASR

1. Download Whisper Tiny.
2. Recognize Indonesian speech.
3. Recognize English speech.
4. Recognize Japanese speech.
5. Test automatic language detection.
6. Generate captions from a real video.
7. Inspect timestamps.
8. Cancel recognition.
9. Test a longer recording.

Text Transform

1. Create a regular text clip.
2. Drag it around the preview.
3. Change X/Y position numerically.
4. Change scale.
5. Rotate the text.
6. Repeat with a caption clip.
7. Repeat with a lyric clip.
8. Place different clips at different positions.
9. Export the project.
10. Compare exported positions against the preview.
11. Save and reopen the project.
12. Verify that every transform remains intact.
13. Test Invert Lyric with rotation and scaling.
14. Test Karaoke highlighting after moving and scaling lyrics.

Record actual device model, Android version, model used, audio duration, recognition duration, and observed errors.

Do not fabricate device test results.

---

PART E — BUILD AND DELIVERY

21. Build Verification

Execute after the final source edit:

- "bash ./gradlew assembleDebug --console=plain"
- "bash ./gradlew testDebugUnitTest --console=plain"
- "bash ./gradlew :app:compileDebugAndroidTestKotlin --console=plain"

If the repository uses different valid task names, identify them and explain the difference.

Ensure:

- Gradle Wrapper files are present.
- "gradlew" retains executable permissions.
- No unrelated dependency upgrades.
- No unnecessary architecture rewrites.

Report exact commands and exit codes.

22. Documentation

Update:

- ASR architecture documentation.
- Whisper model management.
- Supported languages.
- Japanese Unicode handling.
- Offline recognition behavior.
- Transform data model.
- Transform coordinate system.
- Preview/export transform behavior.
- Schema migration, if applicable.
- Known limitations.

23. Final Delivery Report

Provide:

1. Files added, modified, and removed.
2. Vosk components removed.
3. sherpa-onnx integration details and version.
4. Exact Whisper model names and file sizes.
5. Indonesian, English, and Japanese verification results.
6. Timestamp implementation and limitations.
7. Transform architecture and data model.
8. Text, Caption, and Lyric transform verification.
9. Preview/export parity results.
10. Schema migration details, if applicable.
11. Tests added and results.
12. Build commands and exit codes.
13. Device testing results, if performed.
14. Remaining issues.
15. Final source ZIP.

Acceptance Criteria

The task is complete only when:

- Vosk is no longer the active Auto Caption engine.
- sherpa-onnx Whisper performs real offline transcription.
- Indonesian, English, and Japanese are supported and verified.
- Model downloads and lifecycle work safely.
- Existing caption segmentation and timeline insertion remain functional.
- Text, Caption, and Lyric clips support independent positioning.
- All three clip types support scale and rotation.
- Transform changes are persisted per clip.
- Preview and export use the same transform behavior.
- Existing projects retain their original visual appearance.
- Existing CUTLAB features remain intact.
- Build and test results are reported accurately.

Priority: Correctness, compatibility, stable on-device recognition, and consistent preview/export behavior over adding unrelated features.