CUTLAB — TASK 002.2

FINAL CORE SAFETY AND BUILD VERIFICATION

Continue from the latest CutLab source.

Do not rewrite the architecture or introduce unrelated features.

1. Gradle Wrapper

Restore a complete, reproducible Gradle Wrapper.

Requirements:

- Include gradlew.
- Include gradlew.bat.
- Include the matching gradle-wrapper.jar.
- Keep the Gradle distribution version compatible with the existing project.
- Verify wrapper execution from a clean checkout.

Do not claim successful builds unless commands have actually executed.

2. Future Schema API Safety

Review ProjectEngine.deserializeWithReport() and deserialize().

Ensure that future-schema projects cannot accidentally enter ordinary editable workflows.

Separate:

- Supported editable project decoding.
- Read-only future-schema preview decoding.

Add tests for:

- Future schema detection.
- Unsupported editable decoding.
- Serialization rejection.
- Repository write protection.
- Raw JSON preservation.

Do not silently downgrade future schemas.

3. Overlap Repair UX

Improve the legacy overlap repair workflow.

Before applying a repair, display:

- Affected track.
- Affected clip.
- Original timeline position.
- Proposed destination track.
- Whether timing will change.

Require explicit user confirmation before applying a repair.

Keep MOVE_TO_OVERFLOW_TRACK as the default strategy.

4. Regression Tests

Verify:

- Legacy overlap timing preservation.
- Multiple active clips on one track.
- Future schema raw JSON preservation.
- Corrupt project protection.
- Existing timeline editing operations.
- Existing preview and export tests.

5. Build Verification

Run the actual commands:

./gradlew assembleDebug
./gradlew testDebugUnitTest

If available, also run relevant instrumentation tests.

Report the actual exit status and failures.

Do not report tests as passing simply because test source files exist.

6. Deliverables

- Updated source code.
- Gradle Wrapper files.
- Regression tests.
- Updated documentation.
- Build and test output.
- Remaining limitations.

Preserve all existing working functionality.

After completion, stop and report. Do not start Auto Caption or Auto Lyric yet.