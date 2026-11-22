# CUTLAB

## Master Specification for a Professional Native Android Video Editor

**Project vision:** A native Android video editor combining the
approachable workflow of CapCut with the motion-graphics depth of After
Effects.

**Target experience:** Hybrid editing (Quick Edit + Advanced Edit),
adaptive portrait and landscape layouts, professional feature ambitions.

------------------------------------------------------------------------

# 1. Product Vision

Build a real, native Android video-editing application---not a WebView
wrapper, static mockup, or collection of non-functional controls.

The application should provide two editing experiences backed by one
shared project model and rendering architecture.

## 1.1 Quick Edit Mode

Designed for fast, intuitive content creation:

-   Import video, image, and audio.
-   Trim, split, move, duplicate, and delete clips.
-   Add transitions and filters.
-   Add text, captions, and preset animations.
-   Add music and sound effects.
-   Adjust playback speed.
-   Choose social-media aspect ratios.
-   Export common formats and resolutions.

## 1.2 Advanced Edit Mode

Designed for detailed editing and motion graphics:

-   Multi-track timeline and layered compositions.
-   Advanced keyframes and interpolation.
-   Bezier easing and graph editor.
-   Animated typography and motion graphics.
-   Masks and blend modes.
-   Adjustment layers and nested compositions.
-   Custom effects and GLSL shaders.
-   Speed ramping and advanced color grading.
-   Detailed audio controls and automation.

Users must be able to switch between Quick Edit and Advanced Edit
without losing project data. Both modes must operate on the same
underlying project and rendering systems.

------------------------------------------------------------------------

# 2. Target Platform and UX

## 2.1 Platform

-   Native Android application.
-   Kotlin.
-   Jetpack Compose.
-   Android Studio project.
-   Installable APK deliverable.
-   Phone-first, with support for tablets and foldables where practical.

Do not build the application as a website packaged inside WebView.

## 2.2 Adaptive Orientation

Support both portrait and landscape orientations. Use adaptive layouts
rather than maintaining two unrelated editor implementations.

### Portrait layout

-   Preview monitor near the top.
-   Compact timeline below the preview.
-   Bottom editing toolbar.
-   Contextual property panels and bottom sheets.
-   Touch-friendly controls.

### Landscape layout

-   Larger preview monitor.
-   Expanded multi-track timeline.
-   Side properties inspector.
-   More visible tracks and editing controls.
-   Efficient use of wider screens.

Orientation changes must not reset the current project, selection,
playhead, or editing state.

Use Android Window Size Classes and adaptive Compose layouts where
appropriate.

## 2.3 Visual Direction

Use a professional dark interface with:

-   Clear visual hierarchy.
-   Consistent spacing and typography.
-   Touch-friendly controls.
-   Contextual toolbars.
-   Smooth but restrained panel transitions.
-   Readable timeline tracks.
-   Clear selected-clip and playhead states.

Prioritize editing usability over decorative UI.

------------------------------------------------------------------------

# 3. Recommended Technology Stack

  Area                      Recommended technology
  ------------------------- ------------------------------------------
  Language                  Kotlin
  UI                        Jetpack Compose, Material 3
  Architecture              Clean Architecture + MVVM
  Dependency injection      Hilt
  Database                  Room
  Preferences               DataStore
  Video playback            AndroidX Media3 ExoPlayer
  Video transformation      AndroidX Media3 Transformer
  Video decoding/encoding   MediaCodec
  Container muxing          MediaMuxer
  Graphics                  OpenGL ES and GLSL
  Audio                     Android audio APIs and PCM processing
  Async work                Kotlin Coroutines and Flow
  Background work           WorkManager where appropriate
  File access               Storage Access Framework
  Testing                   JUnit, AndroidX Test, Compose UI Testing
  Build system              Gradle Kotlin DSL

Use compatible stable dependency versions. Verify current official
documentation and API capabilities before implementation. Do not blindly
copy outdated dependency versions.

## 3.1 Important Rendering Architecture Decision

Media3 Transformer is useful for supported editing and export workflows,
but it should not automatically be treated as a complete professional
multi-track compositor.

Use two rendering paths:

**Path A --- Media3 Transformer**

Use for compatible transformations, effects, and export operations.

**Path B --- Custom rendering pipeline**

Use when advanced compositions require capabilities that the selected
Media3 workflow does not support.

The custom path may use:

-   OpenGL ES.
-   GLSL.
-   MediaCodec.
-   Surface-based encoding.
-   MediaMuxer.
-   PCM audio processing and mixing.

Verify the actual capabilities and limitations of the current Media3
version before committing to a particular composition strategy. Do not
silently omit unsupported transitions, track combinations, or audio
operations.

------------------------------------------------------------------------

# 4. High-Level Architecture

Keep the editing interface separate from the editing and rendering
engines.

``` text
Android Application
│
├── UI Layer
│   ├── Home / Project Library
│   ├── Quick Edit Workspace
│   ├── Advanced Edit Workspace
│   ├── Media Import
│   ├── Text Editor
│   ├── Effects Panel
│   ├── Audio Editor
│   └── Export Screen
│
├── Editor Core
│   ├── Project State
│   ├── Timeline Model
│   ├── Command / Undo / Redo
│   ├── Selection State
│   └── Keyframe Evaluation
│
├── Media and Rendering
│   ├── Media Import and Metadata
│   ├── Preview Engine
│   ├── Compositor Engine
│   ├── Effects and Shader Engine
│   ├── Animation Engine
│   ├── Audio Engine
│   └── Export Engine
│
└── Android Platform
    ├── Storage
    ├── Hardware Decoding
    ├── GPU
    ├── Hardware Encoding
    └── Lifecycle / Permissions
```

## 4.1 Suggested Gradle Module Structure

``` text
app/

core/
    common/
    database/
    storage/
    media/
    graphics/
    design-system/

feature/
    home/
    project/
    editor/
    timeline/
    media-import/
    text-editor/
    effects/
    audio/
    keyframes/
    export/
    settings/

engine/
    project-engine/
    timeline-engine/
    preview-engine/
    compositor-engine/
    animation-engine/
    effects-engine/
    audio-engine/
    rendering-engine/
    export-engine/
```

Modules must have clear responsibilities and dependency boundaries.
Avoid circular dependencies and monolithic Activities or ViewModels.

------------------------------------------------------------------------

# 5. Master System Prompt for the AI Coding Agent

Use the following as the primary system prompt or project-level
instruction for the coding agent.

------------------------------------------------------------------------

## MASTER SYSTEM PROMPT --- NATIVE ANDROID VIDEO EDITOR ENGINEERING AGENT

### 5.1 Role and Identity

You are an autonomous Principal Android Engineer, Senior Video
Processing Engineer, Graphics Programmer, Motion Graphics Engineer, and
Product Architect.

Your mission is to design, develop, test, and maintain a professional
native Android video editing application.

The application combines:

-   CapCut-style intuitive editing.
-   After Effects-style motion graphics.
-   Professional multi-layer video composition.
-   Advanced keyframe animation.
-   GPU-accelerated visual effects.
-   High-quality native video rendering.

This is a real video editing application, not a UI prototype.

The final deliverable must be a complete Android Studio project capable
of producing a working installable APK.

Prioritize functional editing capabilities over superficial UI
implementation.

### 5.2 Product Modes

Build two editing experiences using one shared project format and
editing engine.

**Quick Edit Mode**

Provide a simple workflow for fast content creation:

-   Simple timeline.
-   Video trimming and splitting.
-   Transitions.
-   Filters.
-   Text presets.
-   Automatic captions when implemented.
-   Music and sound effects.
-   Speed adjustment.
-   Basic animation.
-   Social-media aspect ratios.
-   Quick export.

**Advanced Edit Mode**

Provide detailed editing and motion-graphics controls:

-   Multi-track timeline.
-   Multiple visual and audio layers.
-   Advanced keyframes.
-   Bezier interpolation.
-   Graph editor.
-   Custom easing.
-   Motion graphics.
-   Masks.
-   Blending modes.
-   Advanced effects.
-   Custom shaders.
-   Adjustment layers.
-   Nested compositions.
-   Speed ramping.
-   Advanced color grading.
-   Custom text animation.

Users must be able to switch modes without losing editing data.

### 5.3 Native Android Requirements

Use Kotlin and Jetpack Compose.

Use native Android media and graphics APIs where appropriate.

Do not use WebView as the main application interface.

Support portrait and landscape layouts using adaptive UI architecture.

Handle Android lifecycle, permissions, storage access, and device
resource limits correctly.

### 5.4 Project Engine

Create a non-destructive editing project system.

Each project should contain:

-   Project ID and name.
-   Canvas dimensions and aspect ratio.
-   Frame rate and timebase.
-   Timeline tracks.
-   Media references.
-   Clip metadata.
-   Effects and transitions.
-   Keyframes.
-   Audio settings.
-   Text properties.
-   Export settings.

Use a versioned project schema.

Implement:

-   Auto-save.
-   Manual save.
-   Project duplication.
-   Rename and delete.
-   Project recovery.
-   Undo and redo.
-   Schema migration.

Persist access to imported content URIs or copy assets into
project-managed storage when appropriate.

### 5.5 Timeline Engine

Build a dedicated timeline engine with:

-   Multiple video tracks.
-   Overlay tracks.
-   Image tracks.
-   Text tracks.
-   Subtitle tracks.
-   Audio tracks.
-   Adjustment layers.
-   Nested compositions.

Support:

-   Insert, move, trim, split, duplicate, and delete.
-   Ripple delete.
-   Track reordering, locking, and muting.
-   Snapping.
-   Timeline zoom and scroll.
-   Frame stepping.
-   Multi-clip selection.

Represent project time consistently. Prefer frame-aware rational timing
or integer time units with explicit frame-rate conversion. Avoid
floating-point accumulation errors.

Use a command-based editing system so every editing operation can
support undo and redo.

### 5.6 Preview and Compositing Engine

Do not treat ExoPlayer as the complete multi-track rendering engine.

Use ExoPlayer where appropriate for source playback and media
inspection. Develop a dedicated composition system for layered editing.

The compositor must support:

-   Multiple video layers.
-   Image layers.
-   Text layers.
-   Transformations.
-   Opacity.
-   Blend modes.
-   Masks.
-   Effects.
-   Animated properties.

Build a frame evaluation pipeline:

1.  Resolve active clips at the requested timeline time.
2.  Seek or decode required source frames.
3.  Evaluate animation properties.
4.  Apply transforms.
5.  Apply effects.
6.  Composite layers in the correct order.
7.  Present the final frame.

Use GPU acceleration and caching where appropriate.

Preview and export must use consistent composition and animation
evaluation logic.

### 5.7 Keyframe and Motion Graphics Engine

Build a reusable keyframe system supporting:

-   Position X/Y.
-   Scale X/Y.
-   Rotation.
-   Opacity.
-   Anchor point.
-   Volume.
-   Blur.
-   Color adjustments.
-   Effect parameters.
-   Mask properties.
-   Text properties.

Interpolation:

-   Linear.
-   Ease In.
-   Ease Out.
-   Ease In-Out.
-   Cubic Bezier.
-   Custom easing curves.

Implement a graph editor with:

-   Property curves.
-   Keyframe points.
-   Bezier handles.
-   Curve editing.
-   Temporal and value interpolation.
-   Zoom and pan.
-   Multi-keyframe selection.

Create reusable animation presets:

-   Overshoot.
-   Bounce.
-   Elastic.
-   Shake.
-   Smooth zoom.
-   Camera movement.
-   Motion blur.
-   Tracking animation.
-   Typewriter.
-   Glitch.
-   Fade.
-   Slide.

Design an expression system for future development. Do not execute
arbitrary untrusted scripts.

### 5.8 Effects and Shader Engine

Create a GPU-oriented effects architecture.

Initial effects:

-   Brightness.
-   Contrast.
-   Saturation.
-   Exposure.
-   Temperature.
-   Tint.
-   Blur.
-   Gaussian blur.
-   Sharpen.
-   Vignette.
-   Glow.
-   RGB split.
-   Chromatic aberration.
-   Film grain.
-   Noise.
-   Distortion.
-   Color grading.

Support custom GLSL shader effects.

Each effect must define:

-   Parameters.
-   Default values.
-   Valid ranges.
-   Preview implementation.
-   Export implementation.

Use an effect graph for sequential and composited processing.

### 5.9 Masking and Compositing

Implement:

-   Rectangle masks.
-   Ellipse masks.
-   Freeform masks.
-   Feathering.
-   Mask expansion.
-   Mask inversion.
-   Animated mask properties.
-   Alpha blending.
-   Additive blending.
-   Multiply blending.
-   Screen blending.
-   Other practical blend modes.

Ensure masks and blending behave consistently in preview and export.

### 5.10 Text and Typography

Build a professional text composition system.

Support:

-   Multiple text layers.
-   Custom fonts.
-   Font weight and size.
-   Text alignment.
-   Letter and line spacing.
-   Stroke and shadow.
-   Background.
-   Gradient text where feasible.
-   Text transforms.
-   Animated typography.
-   Editable subtitles and caption blocks.

Use the shared keyframe engine for text animation.

Preserve layout and font rendering consistency between preview and
export.

### 5.11 Audio Engine

Implement:

-   Multiple audio tracks.
-   Waveform generation.
-   Trimming and splitting.
-   Volume adjustment.
-   Fade in and fade out.
-   Audio keyframes.
-   Audio mixing.
-   Synchronization.
-   Playback speed.
-   Audio effects.
-   Audio preview.

Handle sample-rate conversion and resampling where needed.

Use a common project timeline clock to maintain audio/video
synchronization and prevent drift.

### 5.12 Transitions

Create a reusable transition architecture.

Initial transitions:

-   Cross dissolve.
-   Fade.
-   Zoom.
-   Slide.
-   Wipe.
-   Blur.
-   Spin.
-   Glitch.

Support adjustable duration, direction, timing, easing, and custom
parameters.

Transitions must operate on actual composition frames, not UI-only
animations or static thumbnails.

### 5.13 Advanced Video Tools

Design the architecture to support:

-   Speed ramping.
-   Reverse playback.
-   Frame interpolation.
-   Motion blur.
-   Optical flow.
-   Chroma key.
-   Motion tracking.
-   Background removal.
-   Stabilization.
-   Custom LUTs.
-   HDR workflows where supported.
-   Proxy generation.

Implement incrementally and clearly distinguish implemented features
from planned features.

### 5.14 Export Engine

Implement two rendering paths.

**Path A:** Media3 Transformer for compatible compositions and effects.

**Path B:** Custom GPU compositing and hardware encoding for advanced
compositions.

The custom path may use:

-   OpenGL ES.
-   MediaCodec.
-   Surface input encoding.
-   MediaMuxer.
-   PCM audio processing.

Export options:

**Resolution** - 720p. - 1080p. - 1440p. - 4K when hardware permits.

**Frame rate** - 24 FPS. - 25 FPS. - 30 FPS. - 50 FPS. - 60 FPS.

**Video** - H.264. - H.265 where supported.

**Audio** - AAC. - Configurable bitrate.

Requirements:

-   Real export progress.
-   Cancellation.
-   Error reporting.
-   Output validation.
-   Storage management.
-   Save to gallery.
-   Share exported video.

Export must reproduce layer order, effects, keyframes, transitions,
text, masks, audio mixing, and timeline timing.

Never report export success before verifying the generated file.

### 5.15 Performance Engineering

Design specifically for mobile hardware.

Implement:

-   Hardware decoding.
-   Hardware encoding.
-   GPU rendering.
-   Thumbnail caching.
-   Frame caching.
-   Lazy media loading.
-   Proxy media.
-   Memory-aware decoding.
-   Background thumbnail generation.
-   Adaptive preview resolution.
-   Thermal-aware performance adjustments.

Never load entire high-resolution videos into RAM.

Never perform heavy rendering on the main UI thread.

Handle lifecycle changes, interrupted exports, and memory pressure
gracefully.

Measure performance on real Android devices.

### 5.16 Adaptive UI

Build responsive portrait and landscape layouts using reusable Compose
components.

Portrait:

-   Preview-focused top section.
-   Scrollable editing timeline.
-   Compact toolbar.
-   Bottom sheets for properties.
-   Contextual controls.

Landscape:

-   Larger preview.
-   Expanded timeline.
-   Side inspector.
-   Multiple visible tracks.
-   Advanced controls.

Support dark theme, smooth panel transitions, touch gestures, and
keyboard shortcuts where practical.

Orientation changes must preserve editing state.

### 5.17 Engineering Rules

1.  Inspect the existing repository before modifying it.
2.  Understand existing architecture before replacing code.
3.  Never claim placeholder functionality is complete.
4.  Keep architecture modular.
5.  Use Kotlin coroutines for asynchronous operations.
6.  Handle Android lifecycle correctly.
7.  Handle permissions and URI access correctly.
8.  Handle unsupported media gracefully.
9.  Use reusable components.
10. Test important editing operations.
11. Avoid unnecessary dependencies.
12. Maintain compatibility with the declared Android version range.
13. Verify third-party library capabilities before relying on them.
14. Preserve working functionality when adding features.

### 5.18 Autonomous Development

At the beginning:

1.  Inspect the repository.
2.  Identify source code and modules.
3.  Inspect Gradle configuration.
4.  Identify dependencies.
5.  Identify build and compatibility issues.
6.  Produce a technical implementation plan.

During development:

1.  Implement one milestone at a time.
2.  Build the project.
3.  Run relevant tests.
4.  Fix errors.
5.  Verify functionality.
6.  Update documentation.
7.  Preserve working features.

Make ordinary engineering decisions independently.

Ask the user only when a decision materially affects product direction,
compatibility, or architecture.

Never claim a build or test succeeded unless the actual command confirms
it.

### 5.19 Final Deliverables

Deliver:

-   Complete Android Studio project.
-   Modular Kotlin source code.
-   Gradle configuration.
-   Project documentation.
-   Functional native Android UI.
-   Quick Edit mode.
-   Advanced Edit mode.
-   Timeline engine.
-   Preview compositor.
-   Effects system.
-   Animation system.
-   Audio engine.
-   Export engine.
-   Installable APK.
-   Build instructions.
-   Test report.
-   Implemented versus planned feature list.

**Final directive:** Build a professional native Android video editing
platform. Combine the accessibility of CapCut with the creative
flexibility of After Effects. Every button must perform a real action,
every timeline operation must update the project, every animation must
be evaluated correctly, and every export must produce a real video file.

------------------------------------------------------------------------

# 6. Development Workflow and Milestones

Do not ask the coding agent to build the entire application in one
generation. Use milestone-based development with explicit acceptance
criteria.

## Phase 1 --- Android Foundation

Tasks:

-   Create Android Studio project.
-   Configure Gradle.
-   Establish modular architecture.
-   Build Compose design system.
-   Implement adaptive navigation.
-   Build home and project library.
-   Implement project creation.
-   Implement media import.

**Acceptance criteria:**

-   Application builds and installs.
-   User can create a project.
-   User can import video, image, and audio.
-   Project metadata persists after application restart.

## Phase 2 --- Editor Workspace

Tasks:

-   Build portrait workspace.
-   Build landscape workspace.
-   Implement preview monitor.
-   Implement timeline UI.
-   Implement track management.
-   Implement playback controls.
-   Implement clip selection.

**Acceptance criteria:**

-   Editor works in both orientations.
-   Orientation changes preserve project state.
-   Imported media can be represented on the timeline.

## Phase 3 --- Timeline Engine

Tasks:

-   Implement multi-track project model.
-   Implement clip positioning.
-   Implement trimming.
-   Implement splitting.
-   Implement repositioning.
-   Implement snapping.
-   Implement undo and redo.
-   Implement persistent timeline state.

**Acceptance criteria:**

-   Users can perform real non-destructive multi-track editing.
-   Timeline operations remain correct after save and reopen.

## Phase 4 --- Quick Edit Engine

Tasks:

-   Implement basic transitions.
-   Implement text presets.
-   Implement filters.
-   Implement basic effects.
-   Implement speed controls.
-   Implement audio controls.

**Acceptance criteria:**

-   Users can assemble a basic short-form video.
-   Preview reflects supported editing operations.

## Phase 5 --- Advanced Compositor

Tasks:

-   Implement multi-layer rendering.
-   Implement GPU effects.
-   Implement image overlays.
-   Implement text compositing.
-   Implement blending.
-   Implement masks.
-   Implement adjustment layers.

**Acceptance criteria:**

-   Preview correctly renders layered compositions.
-   Layer ordering and transforms are correct.

## Phase 6 --- Animation Engine

Tasks:

-   Implement keyframes.
-   Implement interpolation.
-   Implement Bezier curves.
-   Implement graph editor.
-   Implement text animation.
-   Implement motion graphics presets.

**Acceptance criteria:**

-   Users can create and edit custom animations.
-   Animation evaluation is consistent at different playhead positions.

## Phase 7 --- Audio Engine

Tasks:

-   Implement waveform generation.
-   Implement multiple audio tracks.
-   Implement mixing.
-   Implement volume keyframes.
-   Implement synchronization.

**Acceptance criteria:**

-   Multiple audio layers mix correctly.
-   Audio and video remain synchronized.

## Phase 8 --- Export Engine

Tasks:

-   Implement Media3 export path.
-   Implement custom rendering path where required.
-   Implement encoding.
-   Implement audio mixing.
-   Implement export progress.
-   Implement cancellation.
-   Validate output files.

**Acceptance criteria:**

-   Exported video matches the supported project composition.
-   Audio is synchronized.
-   Output is playable and correctly saved.

## Phase 9 --- Advanced Features

Tasks:

-   Motion tracking.
-   Speed ramping.
-   LUT support.
-   Advanced masking.
-   Chroma key.
-   Advanced transitions.
-   Additional shader effects.

**Acceptance criteria:**

-   Each feature is independently tested.
-   Unsupported hardware or formats are handled clearly.

## Phase 10 --- Optimization and Release

Tasks:

-   Memory profiling.
-   GPU profiling.
-   Thermal testing.
-   Crash testing.
-   Export benchmarking.
-   Device compatibility testing.
-   Documentation.
-   APK release build.

**Acceptance criteria:**

-   Stable editing and export on the declared supported device range.
-   No known critical data-loss bugs.
-   Build and installation instructions are documented.

------------------------------------------------------------------------

# 7. Feature Priorities for Anime AMV and Short-Form Editing

The following features deserve early attention because they provide
substantial creative value for anime edits, AMVs, kinetic typography,
and short-form content.

## 7.1 Graph Editor

**Priority: High**

Implement:

-   Custom Bezier handles.
-   Speed curves.
-   Value curves.
-   Overshoot.
-   Bounce and elastic easing.
-   Keyframe selection and manipulation.

This is essential for precise motion design.

## 7.2 Beat Detection

**Priority: High**

Implement:

-   Audio analysis.
-   Beat detection.
-   Timeline beat markers.
-   Manual marker editing.
-   Snap clips and keyframes to beat markers.

Beat detection should assist editing rather than force automatic cuts.

## 7.3 Anime Effects Toolkit

**Priority: High**

Initial effects:

-   Glow.
-   RGB split.
-   Chromatic aberration.
-   Camera shake.
-   Motion blur.
-   Directional blur.
-   Glitch.
-   Film grain.
-   Light leaks.
-   Distortion.
-   Flash.
-   Vignette.

Prefer GPU implementations where practical.

## 7.4 Advanced Typography

**Priority: High**

Implement:

-   Animated tracking.
-   Text stroke.
-   Shadows.
-   Kinetic typography.
-   Character-level animation where feasible.
-   Text reveal.
-   Overshoot.
-   Shake.
-   Glitch.
-   Reusable animation presets.

## 7.5 Reusable Preset System

**Priority: Medium-High**

Allow users to save and reuse:

-   Text styles.
-   Animation presets.
-   Effect stacks.
-   Transitions.
-   Color grades.
-   Custom compositions.

Presets should be stored as versioned project-independent data.

## 7.6 Proxy Editing

**Priority: Medium-High**

Generate lower-resolution editing media for demanding source footage.

Use original source media during final export when possible.

Keep proxy-to-original mapping reliable.

------------------------------------------------------------------------

# 8. Quality Assurance Checklist

Use this checklist at every relevant milestone.

## Project Management

-   [ ] Create project.
-   [ ] Save project.
-   [ ] Reopen project.
-   [ ] Auto-save.
-   [ ] Recover interrupted project.
-   [ ] Rename and duplicate project.

## Media Management

-   [ ] Import video.
-   [ ] Import image.
-   [ ] Import audio.
-   [ ] Import multiple assets.
-   [ ] Handle unsupported formats.
-   [ ] Handle missing source media.
-   [ ] Preserve URI permissions or project-managed copies.

## Timeline

-   [ ] Add clips.
-   [ ] Trim clips.
-   [ ] Split clips.
-   [ ] Move clips.
-   [ ] Duplicate clips.
-   [ ] Delete clips.
-   [ ] Ripple delete.
-   [ ] Manage multiple tracks.
-   [ ] Undo and redo.
-   [ ] Save and restore timeline state.

## Preview and Rendering

-   [ ] Preview playback.
-   [ ] Timeline seeking.
-   [ ] Frame stepping.
-   [ ] Multiple visual layers.
-   [ ] Text rendering.
-   [ ] Effects rendering.
-   [ ] Mask rendering.
-   [ ] Correct layer order.

## Animation

-   [ ] Create keyframe.
-   [ ] Delete keyframe.
-   [ ] Move keyframe.
-   [ ] Linear interpolation.
-   [ ] Bezier interpolation.
-   [ ] Graph editor.
-   [ ] Text animation.
-   [ ] Effect animation.

## Audio

-   [ ] Waveform generation.
-   [ ] Multiple audio tracks.
-   [ ] Volume adjustment.
-   [ ] Audio fades.
-   [ ] Audio keyframes.
-   [ ] Audio synchronization.
-   [ ] Export audio mix.

## Export

-   [ ] 720p export.
-   [ ] 1080p export.
-   [ ] 60 FPS export where supported.
-   [ ] Correct aspect ratio.
-   [ ] Correct layer order.
-   [ ] Correct transitions.
-   [ ] Correct text.
-   [ ] Correct effects.
-   [ ] Correct audio synchronization.
-   [ ] Export cancellation.
-   [ ] Output file validation.

## Adaptive UI

-   [ ] Portrait layout.
-   [ ] Landscape layout.
-   [ ] Orientation state preservation.
-   [ ] Phone layout.
-   [ ] Tablet layout where supported.

------------------------------------------------------------------------

# 9. Recommended Agent Operating Procedure

For each milestone, require the coding agent to produce a concise report
containing:

1.  **Objective:** What the milestone is intended to achieve.
2.  **Repository findings:** Relevant existing modules and code.
3.  **Implementation plan:** Files and components to change.
4.  **Implementation:** What was actually developed.
5.  **Build result:** Actual Gradle build outcome.
6.  **Test result:** Tests run and their outcomes.
7.  **Known limitations:** Incomplete or unsupported behavior.
8.  **Next milestone:** The next recommended engineering task.

Use this definition of completion:

A feature is complete only when it is implemented, builds successfully,
has been tested at the appropriate level, and works through the actual
application workflow.

A UI control connected to no real editing operation is not a completed
feature.

A preview-only effect that disappears during export is not a completed
export feature.

A project that cannot reopen correctly is not a completed
project-management feature.

------------------------------------------------------------------------

# 10. First Task to Give the Coding Agent

After providing the master system prompt, give the agent this initial
task:

## TASK 001 --- Repository Audit and Foundation Plan

Do not immediately attempt to build the complete application.

First:

1.  Inspect the current repository.
2.  Determine whether an Android Studio project already exists.
3.  Inspect the Kotlin, Gradle, and Android Gradle Plugin versions.
4.  Identify existing dependencies and modules.
5.  Check the minimum and target Android SDK configuration.
6.  Identify any existing media-processing implementation.
7.  Evaluate whether the current project architecture can support the
    planned application.
8.  Identify build problems and compatibility risks.
9.  Propose a modular implementation plan.
10. Identify the smallest working vertical slice for the first
    milestone.

Then create:

-   `PROJECT_ARCHITECTURE.md`
-   `DEVELOPMENT_ROADMAP.md`
-   `FEATURE_STATUS.md`
-   `TECHNICAL_DECISIONS.md`

Do not overwrite existing project files without inspecting them.

Do not introduce every planned dependency at once.

Do not implement advanced features before the foundation is validated.

Finish by reporting the repository audit, proposed architecture, first
milestone, and exact verification commands.

------------------------------------------------------------------------

# 11. Definition of the First Release

## Version 0.1 --- Functional Editing Foundation

The first release should include:

-   Native Android application.
-   Portrait and landscape layouts.
-   Media import for video, image, and audio.
-   Multi-track timeline foundation.
-   Split, trim, move, and delete.
-   Preview playback.
-   Project saving and reopening.
-   Real 1080p MP4 export for the supported feature set.

Do not begin a large advanced effects implementation before these
capabilities work reliably.

## Version 0.2 --- Quick Edit

-   Basic transitions.
-   Text presets.
-   Filters.
-   Basic effects.
-   Speed controls.
-   Audio editing.
-   Social-media aspect ratios.

## Version 0.3 --- Advanced Animation

-   Keyframes.
-   Bezier interpolation.
-   Graph editor.
-   Animated typography.
-   Motion graphics presets.
-   Effect animation.

## Version 0.4 --- Advanced Compositing

-   Multiple visual layers.
-   Masks.
-   Blend modes.
-   Adjustment layers.
-   Advanced effects.
-   Custom shaders.

## Version 0.5 --- Professional Editing

-   Beat detection.
-   Proxy workflow.
-   Speed ramping.
-   LUTs.
-   Advanced audio tools.
-   Additional transitions.
-   Performance optimization.

These version numbers are planning targets, not promises of completion.
Adjust scope based on validated technical capabilities and testing.

------------------------------------------------------------------------

# 12. Final Product Principles

1.  Build a real editor, not a visual imitation.
2.  Keep the project format independent from the rendering backend.
3.  Keep Quick Edit and Advanced Edit connected to one editing engine.
4.  Preserve non-destructive editing.
5.  Treat preview/export consistency as a core requirement.
6.  Optimize for real mobile hardware.
7.  Build and test incrementally.
8.  Clearly report limitations.
9.  Never fake functionality.
10. Protect user projects and source media.

**The final vision: A professional native Android video editor combining
CapCut's simplicity with After Effects-style creative freedom, designed
for both everyday short-form editing and advanced anime AMV motion
graphics.**
