CUTLAB — TASK 002.1

DATA SAFETY AND MIGRATION CORRECTIONS

Continue from the current CutLab v2 source.

Do not rewrite existing architecture.

1. Future Schema Safety

Inspect ProjectSchemaMigrator and ProjectEngine.

Current implementation ignores unknown JSON fields during deserialization.

The existing test verifies decoding of a future schema but does not verify preservation of unknown properties after serialization.

Implement a safe policy:

- Detect project schema versions newer than CURRENT_PROJECT_SCHEMA_VERSION.
- Prevent unsupported future projects from being silently modified or overwritten.
- Provide a clear read-only or unsupported-version result.
- Ensure normal save operations cannot destroy unknown future schema data.
- Add tests proving that unsupported future projects are protected.

Do not claim unknown fields are preserved unless this is actually implemented and tested.

2. Legacy Overlap Repair

Inspect normalizeNonOverlappingTracks.

Current behavior can shift overlapping legacy clips forward.

Implement a migration/repair report that identifies:

- Affected tracks.
- Affected clips.
- Original positions.
- Proposed repaired positions.

Do not silently change existing project timing.

Provide a safe repair strategy and tests.

3. Timebase Documentation

Clarify the difference between:

- Millisecond-based project storage.
- RationalTime conversion utilities.
- Frame-snapped editing operations.
- Frame-based export timestamps.

Do not describe the entire timeline as frame-native.

4. Build Verification

Restore or provide the complete Gradle wrapper if appropriate for this repository.

Verify:

- Gradle configuration.
- Kotlin compilation.
- Unit tests.
- Instrumentation test configuration.

Do not claim build success without actual execution.

5. Deliverables

Provide:

- Source code changes.
- Regression tests.
- Updated documentation.
- Exact build and test commands.
- Actual test results.
- Known remaining limitations.

Preserve all working functionality.