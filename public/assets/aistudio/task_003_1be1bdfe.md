CUTLAB — TASK 003

INTELLIGENT TEXT, AUTO CAPTION, AUTO LYRIC & INVERT LYRIC ENGINE

Baseline: latest Cutlab repositories
Current project schema: v2
Target schema: v3
Platform: Native Android, Kotlin, Jetpack Compose, OpenGL ES 2.0 / GLSL
Architecture: "feature → engine → core"

---

0. ROLE & MISSION

You are the lead Android multimedia engineer continuing development of CUTLAB, a native Android video editor.

Your task is to implement CUTLAB's next major feature set:

1. Auto Caption — automatically transcribe speech from imported video/audio and generate editable timeline captions.
2. Auto Lyric — create lyric tracks with word-level timing, karaoke highlighting, and synchronization tools.
3. Invert Lyric — a GPU-powered text-mask effect that inverts the actual video pixels inside lyric glyphs.
4. Unified Text Engine — provide consistent text rendering and timing across Quick Edit, Advanced Edit, live preview, and exported video.

This is a continuation of an existing application, not a greenfield project.

Do not rewrite the existing architecture. Extend the existing engines and coordinators while preserving all currently working functionality.

Primary objective: deliver real editing functionality, not mock UI, placeholder buttons, simulated recognition, or documentation-only implementations.

---

1. MANDATORY SOURCE AUDIT

Before modifying code, inspect the actual rev-2 source.

Important existing components include:

- "ProjectModels.kt"
- "ProjectEngine.kt"
- "ProjectSchemaMigrator.kt"
- "ProjectRepository.kt"
- "TimelineEngine.kt"
- "TimelineCommand.kt"
- "ProjectWorkspaceViewModel.kt"
- "ProjectWorkspaceScreen.kt"
- "WorkspaceMediaAnalysisCoordinator.kt"
- "WorkspaceAudioCoordinator.kt"
- "StudioMediaPlayer.kt"
- "TextLayerBitmapRenderer.kt"
- "CompositorShaders.kt"
- "GlCompositorRenderer.kt"
- "CustomGlVideoExporter.kt"
- "ExportPlanner.kt"

Inspect the actual implementation before deciding where new responsibilities belong.

Existing capabilities to reuse:

- "TEXT" timeline tracks.
- "TimelineClipRef" and "TextOverlaySpec".
- Existing text style and animation presets.
- Existing text bitmap rendering for Path B export.
- Existing Compose text preview.
- Existing OpenGL framebuffer compositing.
- Existing audio extraction and PCM analysis.
- Existing waveform and beat analysis.
- Existing command history and undo/redo.
- Existing Room persistence and schema protection.
- Existing Quick Edit and Advanced Edit workspaces.

Do not duplicate functionality that already exists.

Before implementation, produce a concise internal implementation plan identifying:

- Files and modules to extend.
- New classes required.
- Existing interfaces to reuse.
- Project model changes.
- Preview and export integration points.
- Required migration and tests.

Then implement the task.

---

2. ARCHITECTURAL RULES

These rules are mandatory.

2.1 Preserve existing architecture

- Kotlin native Android implementation.
- Jetpack Compose UI.
- MVVM and existing coordinator architecture.
- Existing "feature → engine → core" dependency direction.
- Coroutines for background processing.
- Room remains the project persistence layer.
- Existing media import and app-private media storage remain unchanged.
- Existing dual-path export architecture remains intact.

Do not create a second timeline engine, audio engine, media repository, or export pipeline.

2.2 Preview/export consistency

Every new visual feature must work in:

1. Live preview.
2. Timeline scrubbing.
3. Frame stepping.
4. Path B export.
5. Reopened projects.

A feature is not complete if it works only in Compose preview or only in exported video.

2.3 Non-destructive editing

- Generated captions and lyrics must remain editable.
- Never permanently burn generated captions into imported source media.
- Every committed editing operation must use the existing edit-session/undo-redo architecture.
- Respect locked tracks.
- Preserve existing timeline overlap rules.
- Never silently move existing clips to accommodate generated content.
- Never modify imported source files.

2.4 Timebase

The current project timeline stores time in milliseconds.

Continue using the existing "TimelineTimebase" and "RationalTime" conversion utilities.

Do not claim the project is frame-native.

Generated timestamps must be converted and snapped appropriately when required by project FPS.

Do not introduce cumulative timing drift by repeatedly rounding individual durations.

2.5 Privacy

Prefer local on-device processing.

Never upload user media, audio, or transcripts to an external service without explicit user consent.

If a cloud recognition provider is considered:

- Make it an optional provider.
- Clearly disclose that audio leaves the device.
- Require explicit consent.
- Keep local processing available where technically possible.
- Never silently fall back to cloud processing.

---

3. PROJECT SCHEMA V3

The existing project schema is v2.

New persistent text and lyric features must be saved in the project document.

Implement a controlled migration:

"v2 → v3"

Requirements:

- Increment "CURRENT_PROJECT_SCHEMA_VERSION" to 3.
- Add a non-destructive v2-to-v3 migration.
- Preserve existing text clips and all existing project data.
- New fields must have backward-compatible defaults.
- Existing projects without captions or lyric metadata must open normally.
- Do not break v0/v1 migrations.
- Preserve the future-schema read-only protection.

Important: tests that previously used schema v3 as an unsupported future schema must now use schema v4 or higher.

Future-schema projects must still:

- Open through the read-only preview path.
- Reject editable decoding.
- Reject serialization.
- Reject repository writes.
- Preserve their original raw JSON.

Do not silently downgrade future projects.

3.1 Suggested data model

Design the final model based on the existing source.

Prefer extending the existing text model instead of introducing a parallel rendering system.

Consider adding:

- "TextContentType": TITLE, CAPTION, LYRIC.
- "TextRenderMode": NORMAL, INVERT_VIDEO.
- "TimedTextWord": stable ID, text, start time, end time.
- Karaoke highlight configuration.
- Lyric alignment metadata.
- Caption language and recognition metadata where appropriate.

Suggested conceptual structure:

@Serializable
data class TimedTextWord(
    val id: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

@Serializable
data class TextOverlaySpec(
    // Preserve every existing property.

    val contentType: TextContentType = TextContentType.TITLE,
    val renderMode: TextRenderMode = TextRenderMode.NORMAL,
    val timedWords: List<TimedTextWord> = emptyList(),
)

This is a conceptual starting point, not a requirement to copy blindly.

The final design must support:

- Existing ordinary text clips.
- Multi-line captions.
- Lyric segments.
- Word-level timing.
- Karaoke highlighting.
- Invert Lyric rendering.
- Editing, trimming, splitting, moving, and duplicating text clips.

Word timestamps should preferably be clip-local so moving a lyric clip does not invalidate its internal synchronization.

If text clips are trimmed or split, update word timing correctly. Do not leave word timestamps outside the resulting clip duration.

---

4. PHASE 1 — UNIFIED TEXT & LYRIC ENGINE

Build the underlying text and lyric model before adding automatic recognition.

4.1 Unified text model

Extend the current "TextOverlaySpec" and text rendering pipeline.

Preserve all existing functionality:

- Six existing text style presets.
- Six existing text animation presets.
- Text color.
- Background color.
- Stroke.
- Shadow.
- Alignment.
- Font scaling.
- Position and transforms.
- Existing keyframe integration.

Add:

- Text content type.
- Word timing data.
- Karaoke highlighting.
- Text rendering mode.
- Optional lyric-specific styling.

Do not break existing text clips.

4.2 Word-level timing

Each lyric or caption segment can contain individual timed words.

Each word must have:

- Stable ID.
- Text.
- Start time.
- End time.

Requirements:

- Start time cannot be negative.
- End time must be greater than start time.
- Words must remain in a valid order.
- Overlapping word timing must be handled intentionally.
- Word timing must survive save/reopen.
- Moving a text clip must preserve its internal word timing.
- Trimming and splitting must correctly adjust or partition word timing.

Use the project's existing millisecond timebase.

4.3 Karaoke highlighting

Implement a real word-level karaoke rendering mode.

Required behavior:

- Inactive words use the base lyric color.
- The active word changes to the highlight color.
- Previously spoken/sung words may retain the highlighted color.
- Future words remain inactive.
- Highlight progress follows the actual timeline playhead.
- Scrubbing backward must update the highlight correctly.
- Playback must not depend on frame-count accumulation.

Provide controls for:

- Base text color.
- Highlight color.
- Font size.
- Alignment.
- Word spacing.
- Highlight mode.
- Optional outline and shadow.

The highlight must work during live playback, scrubbing, and export.

Do not fake karaoke highlighting by changing the entire sentence color.

---

5. PHASE 2 — MANUAL LYRIC EDITOR & SYNCHRONIZATION

Implement this before automatic lyric recognition.

This is the foundation for all lyric features.

5.1 Lyric creation

Allow users to:

- Create a new lyric track.
- Paste multiple lyric lines.
- Import a plain-text ".txt" lyric file.
- Edit individual lines.
- Split and merge lines.
- Reorder lines.
- Delete lines.
- Change text styles.
- Set lyric timing.

Use the existing "TEXT" track infrastructure where practical.

Do not create one timeline track per word.

A lyric line should be a manageable text clip containing its word timing data.

5.2 Synchronization workflow

Build a dedicated lyric synchronization interface.

Required workflow:

1. User imports or selects an audio/video source.
2. User enters or imports lyrics.
3. Lyrics are divided into editable lines.
4. User plays the audio.
5. User taps a line or word to assign its start time at the current playhead.
6. User assigns or adjusts its end time.
7. User can refine word timing individually.
8. User previews karaoke highlighting.
9. User commits the synchronized lyrics to the timeline.

Provide:

- Play/pause.
- Timeline playhead.
- Audio waveform reference.
- Current lyric line.
- Current word.
- Previous/next word navigation.
- Start/end timing adjustment.
- Frame stepping.
- Optional beat snapping.
- Manual offset adjustment.
- Undo/redo support.

Use existing audio analysis and waveform infrastructure.

Do not create a new audio playback engine.

5.3 Timing tools

Add:

- Shift selected lyrics forward/backward.
- Adjust selected word duration.
- Distribute timing across selected words as an explicitly labeled estimate.
- Snap timing to project frames.
- Optional beat snapping.
- Reset timing.

Estimated timing must be visibly distinguishable from manually confirmed timing.

Do not present estimated word timing as precise recognition output.

---

6. PHASE 3 — INVERT LYRIC GPU EFFECT

This is one of CUTLAB's signature effects.

Implement it as a real GPU compositor effect.

6.1 Exact visual behavior

The lyric text acts as a mask that reveals inverted video pixels inside the glyphs.

Conceptual result:

Original video:
[normal footage]

Invert Lyric:
[normal footage with inverted RGB pixels inside lyric letters]

Mathematically:

vec3 original = texture2D(uVideo, vTexCoord).rgb;
vec4 mask = texture2D(uTextMask, vTexCoord);

vec3 inverted = vec3(1.0) - original;

float amount = mask.a * uInvertStrength;

vec3 result = mix(original, inverted, amount);

This is conceptual shader logic. Adapt it to the actual compositor's coordinate system, texture orientation, blending, and color pipeline.

Critical distinction:

Do not simply invert the alpha mask.

Do not render black text on a white background and call it Invert Lyric.

Do not use a generic blend mode such as Difference as a substitute for actual RGB inversion.

The actual video RGB pixels must be inverted inside the text glyph mask.

6.2 GPU implementation

Implement:

- Text glyph mask generation.
- GPU texture upload and caching.
- Invert Lyric shader pass.
- Mask alpha/anti-aliasing.
- Correct framebuffer composition.
- Correct texture lifecycle and cleanup.
- Safe handling of empty text.
- Safe handling of missing textures.
- Proper rendering during scrubbing and playback.

Use the existing:

- "CompositorShaders".
- "GlCompositorRenderer".
- "GlCompositorMath".
- "CustomGlVideoExporter".
- Existing FBO/ping-pong architecture.

Do not create a completely separate GL renderer.

6.3 Live preview integration

Existing normal text preview uses Compose overlays above the GL "TextureView".

Invert Lyric requires access to the underlying video pixels.

Implement a proper GPU compositing path for invert text.

Possible approach:

1. Generate a transparent glyph mask.
2. Upload it as a GL texture.
3. Sample the already-composited video framebuffer.
4. Invert RGB only where the glyph mask has coverage.
5. Composite the result back into the active framebuffer.

Adapt this to the existing renderer.

Do not rely on Compose text overlay blending to simulate this effect.

6.4 Export integration

The same effect must work in Path B export.

Use the same shader logic and compatible mask generation.

Preview and export must agree on:

- Glyph position.
- Font scale.
- Alignment.
- Anchor.
- Transform.
- Rotation.
- Opacity.
- Animation.
- Mask dimensions.
- Word highlight state.
- Inversion strength.

Path A should not silently export an incorrect result.

Ensure "ExportPlanner" routes projects using Invert Lyric through the appropriate Path B compositor pipeline.

6.5 Effect controls

Provide:

- Invert Strength: 0–100%.
- Text Size.
- Position.
- Rotation.
- Opacity.
- Font.
- Alignment.
- Entrance/exit animation.
- Word highlight timing.
- Optional edge softness.

Default behavior:

- Strength: 100%.
- Glyph-only inversion.
- Original footage outside glyphs.
- No inversion of the entire frame.
- No inversion of the text background unless explicitly enabled.

6.6 Performance

- Cache glyph masks when text/style/size has not changed.
- Do not regenerate identical text bitmaps every frame.
- Reuse GL textures.
- Release resources when clips/projects are removed.
- Avoid unnecessary bitmap allocation during playback.
- Avoid blocking the UI thread.

---

7. PHASE 4 — AUTO CAPTION

Implement actual speech transcription from imported media.

7.1 Recognition engine selection

Before choosing a recognition SDK, investigate viable Android-compatible options for transcribing prerecorded local audio files.

Important:

Android's standard "SpeechRecognizer" is not automatically a prerecorded-file transcription engine. Do not assume it can directly transcribe arbitrary imported audio files.

Evaluate a suitable on-device engine that supports:

- Android.
- Kotlin/JVM integration or supported native integration.
- Prerecorded audio input.
- Timestamped transcription.
- Preferably word-level timestamps.
- Offline operation.
- Android API 28+ compatibility.

Possible technical directions may include an offline ASR runtime or another properly supported file-transcription engine.

Do not add a large native dependency without assessing:

- APK size.
- Model size.
- ABI support.
- Runtime memory.
- Processing speed.
- License.
- Android compatibility.
- Maintenance status.

Document the chosen engine and why it is appropriate.

If a suitable engine requires a downloadable model:

- Implement model availability checks.
- Implement model download/provisioning.
- Show download size.
- Show progress.
- Allow cancellation.
- Validate downloaded model files.
- Store models in app-private storage.
- Do not download a large model silently.

Do not fabricate successful recognition when the model is unavailable.

7.2 Audio extraction

Reuse existing media analysis infrastructure where possible.

The transcription pipeline must:

1. Read imported video/audio locally.
2. Extract audio using the existing MediaExtractor/MediaCodec infrastructure where appropriate.
3. Convert audio into the recognition engine's required PCM format.
4. Handle sample rate and channel conversion.
5. Process long audio in manageable chunks.
6. Preserve absolute timestamps across chunks.
7. Handle silence and failed decoding.
8. Release codecs, extractors, and buffers correctly.

Do not load an entire long audio track into RAM unnecessarily.

7.3 Auto Caption workflow

Provide a user-facing workflow:

1. Select a video or audio source.
2. Select recognition language.
3. Check model availability.
4. Start transcription.
5. Show progress.
6. Allow cancellation.
7. Display the transcription result.
8. Allow editing before insertion.
9. Generate caption clips on a TEXT track.
10. Preview and export.

Support:

- Language selection.
- Automatic language detection only if supported by the chosen engine.
- Caption segmentation.
- Punctuation where supported.
- Word timestamps where supported.
- Manual correction.
- Regeneration.
- Deletion.
- Repositioning.
- Caption style presets.

Do not claim support for languages that the selected model does not actually support.

7.4 Caption timing

Prefer actual word timestamps returned by the recognition engine.

If the engine returns only segment-level timestamps:

- Preserve the segment timestamps.
- Do not invent precise word timings.
- Any estimated word distribution must be marked as estimated.
- Allow users to correct it manually.

7.5 Caption generation

Generated captions must become editable text clips in the existing timeline.

Requirements:

- Each caption segment is independently editable.
- Text can be corrected.
- Start/end time can be changed.
- Caption clips can be split, merged, moved, duplicated, and deleted.
- Existing styles and transforms remain available.
- Captions must survive save/reopen.
- Generated captions must be undoable as a logical operation.

Avoid creating hundreds of timeline tracks.

Use the existing track and clip model.

7.6 Caption UI

Provide a dedicated caption panel with:

- Recognition language.
- Recognition model status.
- Start transcription.
- Progress.
- Cancel.
- Transcript review.
- Caption style.
- Maximum characters per line.
- Optional segmentation controls.
- Generate timeline captions.
- Regenerate selected range.

Keep the interface usable on portrait Android screens.

---

8. PHASE 5 — AUTO LYRIC

Auto Lyric is more difficult than ordinary speech recognition.

Singing, instrumental music, reverb, backing vocals, and overlapping vocals make automatic lyric transcription unreliable.

Do not present ordinary speech ASR as a reliable singing-lyrics engine.

8.1 Core Auto Lyric workflow

Implement:

- Manual lyric input.
- ".txt" lyric import.
- Lyric line segmentation.
- Word-level timing.
- Manual synchronization.
- Karaoke highlighting.
- Beat snapping.
- Offset adjustment.
- Timeline insertion.
- Editing and persistence.

This core workflow must work even if automatic singing recognition is unavailable.

8.2 Automatic lyric recognition

Investigate whether the selected recognition technology genuinely supports singing voice transcription.

If not, do not pretend it does.

Keep automatic singing transcription behind a clearly labeled experimental capability or provider interface.

If no reliable on-device implementation is available, deliver the manual lyric workflow completely and document automatic singing recognition as deferred.

Do not make the entire Auto Lyric feature depend on experimental ASR.

8.3 Lyric alignment

If a suitable alignment engine is available, support:

- Supplied lyrics.
- Audio reference.
- Word-level alignment.
- Confidence or reliability indication.
- Manual correction.

If alignment is estimated:

- Label it as estimated.
- Never claim exact synchronization.
- Keep the result editable.

---

9. PHASE 6 — QUICK EDIT & ADVANCED EDIT INTEGRATION

Both modes must use the same underlying project data and editing engine.

9.1 Quick Edit

Add accessible workflows for:

- Add Text.
- Auto Caption.
- Add Lyrics.
- Synchronize Lyrics.
- Karaoke Highlight.
- Invert Lyric preset.

Keep Quick Edit simple.

Do not expose every technical setting at once.

Provide practical presets and a focused editing interface.

9.2 Advanced Edit

Expose:

- Text/Caption/Lyric clips on the timeline.
- Word timing.
- Karaoke highlight controls.
- Invert Lyric effect controls.
- Keyframe animation.
- Transform controls.
- Text style controls.
- GPU effect settings.
- Caption/lyric synchronization tools.

Use existing advanced panels where possible.

Do not duplicate the timeline UI.

9.3 Adaptive UI

All new panels must work in:

- Portrait orientation.
- Landscape orientation.
- Small Android screens.
- Existing Quick Edit mode.
- Existing Advanced Edit mode.

Do not break existing selection, playhead, zoom, or workspace state restoration.

---

10. TIMELINE, UNDO/REDO & PERSISTENCE

This feature set introduces many timeline mutations.

Use the existing "WorkspaceEditSession" and "TimelineCommandHistory".

Requirements:

- Generate captions as one logical undoable operation.
- Generate lyrics as one logical undoable operation.
- Editing an individual caption remains undoable.
- Editing word timing remains undoable.
- Invert Lyric property changes remain undoable.
- Deleting generated captions must not delete imported media.
- Undo/redo must restore the expected text and timing.
- Save committed changes through the existing repository.
- Preserve project data after process restart.

Respect locked tracks and current overlap prevention.

If multiple caption segments are inserted, ensure their placement does not silently shift unrelated clips.

---

11. TESTING REQUIREMENTS

Add meaningful automated tests.

Do not merely add placeholder tests.

11.1 Schema tests

- v2 → v3 migration.
- Existing text clips survive migration.
- Default text model fields decode correctly.
- Timed word data serializes/deserializes.
- Future schema v4 remains read-only.
- Unknown future JSON fields remain preserved.
- Corrupt projects remain protected.

11.2 Timeline tests

- Insert caption clips.
- Insert lyric clips.
- Edit text.
- Edit word timings.
- Move text clips.
- Trim text clips.
- Split text clips.
- Duplicate text clips.
- Delete text clips.
- Word timing remains valid after trimming/splitting.
- Undo/redo restores correct text and timing.
- Locked track protection.

11.3 Karaoke tests

- Before word start.
- During word.
- After word end.
- Playback forward.
- Scrubbing backward.
- Frame stepping.
- Overlapping or invalid timing handling.
- Empty text handling.

11.4 Invert Lyric tests

Test the shader logic with deterministic input values.

At minimum:

- Strength 0% returns original video.
- Strength 100% returns inverted RGB inside glyphs.
- Outside glyphs remains unchanged.
- Partial glyph coverage blends correctly.
- Alpha channel is preserved.
- Empty mask produces unchanged video.

Add GL instrumentation tests where practical.

11.5 Auto Caption tests

- Audio extraction.
- PCM conversion.
- Recognition engine unavailable.
- Model unavailable.
- Model download cancellation.
- Recognition cancellation.
- Long audio chunk timestamp continuity.
- Empty/silent audio.
- Recognition error handling.
- Transcript editing.
- Caption generation.
- Persistence after reopen.

Use mocked recognition engine interfaces for deterministic unit tests.

Do not require a real model download for ordinary unit tests.

11.6 Preview/export tests

Verify:

- Normal text remains unchanged.
- Karaoke highlight appears in preview and export.
- Invert Lyric works in preview and Path B export.
- Text position and scaling match.
- Animation timing matches.
- Export planner selects the correct path.

Do not claim pixel-perfect preview/export parity without meaningful verification.

---

12. PERFORMANCE & RESOURCE MANAGEMENT

This is a video editor. Avoid unnecessary allocations and expensive work on the UI thread.

Requirements:

- Recognition runs on background dispatchers.
- Audio extraction is cancellable.
- Long transcription tasks process chunks.
- Bitmap generation is cached.
- GL textures are reused.
- GL resources are released safely.
- Text rendering does not block playback.
- No per-frame unnecessary object allocation.
- No repeated database writes for every playback frame.
- No unnecessary full-resolution PCM buffering.
- Handle low-memory devices.
- Respect existing thermal and memory monitoring.

Do not compromise full-resolution export quality to improve preview performance.

---

13. DOCUMENTATION

Update:

- "FEATURE_STATUS.md"
- "PROJECT_ARCHITECTURE.md"
- "TECHNICAL_DECISIONS.md"
- "README.md"
- "DEVELOPMENT_ROADMAP.md", if needed.

Clearly distinguish:

- Fully implemented.
- Implemented with limitations.
- Experimental.
- Deferred.

Document:

- Schema v3.
- Recognition engine and licensing.
- Model download size and storage.
- Supported recognition languages.
- Offline/online behavior.
- Word timestamp accuracy limitations.
- Singing recognition limitations.
- Invert Lyric rendering architecture.
- Preview/export caveats.

Never mark an incomplete feature as implemented.

---

14. IMPLEMENTATION ORDER

Implement in this order.

Stage A — Foundation

- Source audit.
- Schema v3 migration.
- Unified text model.
- Timed word model.
- Text clip timing behavior.
- Unit tests.

Stage B — Manual Lyric Engine

- Lyric input.
- Text import.
- Line segmentation.
- Word timing.
- Synchronization UI.
- Karaoke highlighting.
- Persistence.
- Undo/redo.

Stage C — Invert Lyric

- Glyph mask renderer.
- GLSL inversion pass.
- Live preview integration.
- Path B export integration.
- Export planner integration.
- GPU caching.
- Shader tests.

Stage D — Auto Caption

- Recognition engine selection.
- Model provisioning.
- Local audio extraction.
- Background transcription.
- Timestamp conversion.
- Transcript review UI.
- Timeline caption generation.
- Cancellation and error handling.

Stage E — Auto Lyric Recognition

- Investigate singing-compatible recognition.
- Implement only if technically viable.
- Otherwise keep manual lyric synchronization fully functional and document automatic singing recognition as deferred.

Stage F — Integration & Verification

- Quick Edit integration.
- Advanced Edit integration.
- Schema tests.
- Timeline tests.
- Rendering tests.
- Export tests.
- Build verification.
- Documentation.

Do not skip earlier stages to rush into recognition UI.

---

15. BUILD & VERIFICATION

After the final code edit, execute:

./gradlew assembleDebug --console=plain
./gradlew testDebugUnitTest --console=plain
./gradlew :app:compileDebugAndroidTestKotlin --console=plain

Run instrumentation tests on an available emulator/device if possible.

Report:

- Actual exit status.
- Build duration.
- Test count.
- Passed tests.
- Failed tests.
- Skipped tests.
- Instrumentation status.
- APK output path.
- Any known limitations.

Do not claim success without actual execution.

If the environment cannot download dependencies or models, state the limitation clearly.

Do not silently skip failing tests.

---

16. ACCEPTANCE CRITERIA

TASK 003 is complete only when:

1. Existing CUTLAB functionality remains intact.
2. Schema v3 migration works.
3. Existing text clips remain compatible.
4. Manual lyric editing works.
5. Word-level timing persists correctly.
6. Karaoke highlighting follows the timeline.
7. Invert Lyric performs real RGB inversion inside text glyphs.
8. Invert Lyric works in live preview and Path B export.
9. Auto Caption processes actual imported audio using a real recognition engine.
10. Generated captions are editable timeline clips.
11. Recognition can be cancelled safely.
12. Quick Edit and Advanced Edit share the same underlying features.
13. Undo/redo and project persistence work.
14. Regression tests pass.
15. Build and test commands complete successfully.

If any capability cannot be implemented reliably, report it as deferred instead of faking functionality.

---

17. STRICT PROHIBITIONS

- Do not rewrite the whole application.
- Do not replace the existing timeline engine.
- Do not create a second audio engine.
- Do not create a second export engine.
- Do not break existing text clips.
- Do not silently downgrade future project schemas.
- Do not upload media without explicit consent.
- Do not fake speech recognition.
- Do not claim singing recognition works if it does not.
- Do not simulate word timing and label it accurate.
- Do not implement Invert Lyric as a simple alpha inversion.
- Do not implement Invert Lyric as a generic Difference blend mode.
- Do not leave preview and export using fundamentally different effect logic.
- Do not add non-functional buttons.
- Do not hardcode fake progress.
- Do not remove existing tests to make the build pass.
- Do not claim tests pass without running them.

---

18. FINAL DELIVERY REPORT

When finished, provide:

1. Source files created.
2. Source files modified.
3. Architecture decisions.
4. Schema v3 migration details.
5. Auto Caption engine and language support.
6. Manual lyric synchronization workflow.
7. Auto Lyric recognition status.
8. Invert Lyric shader implementation.
9. Preview/export integration details.
10. Tests added and actual results.
11. Build verification results.
12. Known limitations.
13. Features deferred to future tasks.

Deliver the complete updated source project.

Stop after completing TASK 003. Do not begin TASK 004 without approval.

---

FINAL PRODUCT VISION

CUTLAB should not become another editor with a basic subtitle button.

The goal is a unified text and lyric engine suitable for:

- Anime AMV editing.
- Kinetic typography.
- Lyric videos.
- Karaoke-style subtitles.
- Cinematic captions.
- Motion graphics.
- Advanced text compositing.
- Signature GPU typography effects.

Build the actual engine first, then expose it through a polished editing experience.

Quality over shortcuts. Real functionality over feature-count inflation.