package com.example.engine.project

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

data class ProjectMigrationResult(
    val document: ProjectDocument,
    val sourceSchemaVersion: Int,
    val targetSchemaVersion: Int = CURRENT_PROJECT_SCHEMA_VERSION,
    val didMigrate: Boolean,
    val compatibility: SchemaCompatibility = SchemaCompatibility.CURRENT,
    val isReadOnly: Boolean = compatibility == SchemaCompatibility.UNSUPPORTED_FUTURE,
    val unknownTopLevelKeys: Set<String> = emptySet(),
) {
    val isUnsupportedFutureVersion: Boolean
        get() = compatibility == SchemaCompatibility.UNSUPPORTED_FUTURE
}

sealed interface ProjectOpenResult {
    data class Supported(
        val document: ProjectDocument,
        val migrationResult: ProjectMigrationResult,
    ) : ProjectOpenResult

    data class UnsupportedFutureVersion(
        val projectId: String?,
        val projectName: String?,
        val detectedSchemaVersion: Int,
        val supportedSchemaVersion: Int = CURRENT_PROJECT_SCHEMA_VERSION,
        val unknownTopLevelKeys: Set<String>,
        val rawJson: String,
        val readOnlyPreviewDocument: ProjectDocument?,
    ) : ProjectOpenResult

    data class Corrupt(
        val errorMessage: String,
        val cause: Throwable? = null,
    ) : ProjectOpenResult
}

class UnsupportedFutureSchemaException(
    val detectedSchemaVersion: Int,
    val supportedSchemaVersion: Int = CURRENT_PROJECT_SCHEMA_VERSION,
) : IllegalStateException(
    "Refusing to overwrite project schema v$detectedSchemaVersion (max supported v$supportedSchemaVersion)",
)

class ProjectEngine(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    },
) {
    fun createNewDocument(
        name: String,
        aspectRatio: AspectRatioPreset,
        fps: Int,
        editorMode: EditorMode,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        val projectId = UUID.randomUUID().toString()
        val sanitizedName = name.trim().ifEmpty { "Untitled Cut" }
        return ProjectDocument(
            schemaVersion = CURRENT_PROJECT_SCHEMA_VERSION,
            id = projectId,
            name = sanitizedName,
            aspectRatio = aspectRatio,
            canvasWidth = aspectRatio.width,
            canvasHeight = aspectRatio.height,
            fps = fps,
            editorMode = editorMode,
            tracks = defaultTracks(),
            exportSettings = ExportSettingsSpec(
                resolutionHeight = minOf(aspectRatio.width, aspectRatio.height).coerceAtLeast(720),
                fps = fps,
            ),
            createdAt = nowMs,
            updatedAt = nowMs,
        )
    }

    fun duplicateDocument(
        source: ProjectDocument,
        newName: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        val newId = UUID.randomUUID().toString()
        return source.copy(
            id = newId,
            name = newName,
            createdAt = nowMs,
            updatedAt = nowMs,
        )
    }

    fun switchMode(
        document: ProjectDocument,
        targetMode: EditorMode,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        if (document.editorMode == targetMode) return document
        return document.copy(
            editorMode = targetMode,
            updatedAt = nowMs,
        )
    }

    fun rename(
        document: ProjectDocument,
        newName: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        val sanitized = newName.trim().ifEmpty { document.name }
        return document.copy(name = sanitized, updatedAt = nowMs)
    }

    fun updateCanvas(
        document: ProjectDocument,
        aspectRatio: AspectRatioPreset,
        fps: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        return document.copy(
            aspectRatio = aspectRatio,
            canvasWidth = aspectRatio.width,
            canvasHeight = aspectRatio.height,
            fps = fps,
            exportSettings = document.exportSettings.copy(fps = fps),
            updatedAt = nowMs,
        )
    }

    fun appendAssetToFoundationTrack(
        document: ProjectDocument,
        asset: MediaAsset,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        val targetType = when (asset.type) {
            MediaType.VIDEO, MediaType.IMAGE -> TrackType.VIDEO
            MediaType.AUDIO -> TrackType.AUDIO
        }
        val targetTrack = document.tracks.firstOrNull { it.type == targetType && !it.isLocked }
            ?: document.tracks.firstOrNull { it.type == targetType }
            ?: return document
        if (targetTrack.isLocked) return document
        val clipStartMs = targetTrack.clips.maxOfOrNull { it.timelineStartMs + it.durationMs } ?: 0L
        val defaultDuration = when (asset.type) {
            MediaType.IMAGE -> 3_000L
            MediaType.VIDEO, MediaType.AUDIO -> asset.durationMs.coerceAtLeast(1_000L)
        }
        val sourceTotalDurationMs = when (asset.type) {
            MediaType.IMAGE -> 600_000L
            MediaType.VIDEO, MediaType.AUDIO -> asset.durationMs.coerceAtLeast(defaultDuration)
        }
        val newClip = TimelineClipRef(
            clipId = UUID.randomUUID().toString(),
            assetId = asset.id,
            trackId = targetTrack.id,
            timelineStartMs = clipStartMs,
            sourceStartMs = 0L,
            durationMs = defaultDuration,
            sourceTotalDurationMs = sourceTotalDurationMs,
        )
        val updatedTracks = document.tracks.map { track ->
            if (track.id == targetTrack.id) {
                track.copy(clips = (track.clips + newClip).sortedBy { it.timelineStartMs })
            } else {
                track
            }
        }
        return document.copy(
            tracks = updatedTracks,
            updatedAt = nowMs,
        )
    }

    fun removeAssetReferences(
        document: ProjectDocument,
        assetId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        val updatedTracks = document.tracks.map { track ->
            track.copy(clips = track.clips.filterNot { it.assetId == assetId })
        }
        return document.copy(
            tracks = updatedTracks,
            updatedAt = nowMs,
        )
    }

    fun serialize(document: ProjectDocument): String {
        if (document.schemaVersion > CURRENT_PROJECT_SCHEMA_VERSION) {
            throw UnsupportedFutureSchemaException(document.schemaVersion)
        }
        return json.encodeToString(document)
    }

    /**
     * Supported editable project decoding.
     * Rejects unsupported future schemas (> [CURRENT_PROJECT_SCHEMA_VERSION]) with
     * [UnsupportedFutureSchemaException] so future projects cannot enter editable workflows.
     */
    fun deserialize(rawJson: String): ProjectDocument = deserializeWithReport(rawJson).document

    fun deserializeEditable(rawJson: String): ProjectDocument = deserialize(rawJson)

    /**
     * Opens any project JSON and separates supported editable decoding from read-only future
     * preview decoding and corrupt inputs.
     */
    fun openProjectJson(rawJson: String): ProjectOpenResult {
        val migration = try {
            ProjectSchemaMigrator.migrateToCurrent(rawJson, json)
        } catch (t: Throwable) {
            return ProjectOpenResult.Corrupt(
                errorMessage = t.message ?: "Invalid project JSON",
                cause = t,
            )
        }

        if (migration.isUnsupportedFutureVersion) {
            val (parsedId, parsedName) = ProjectSchemaMigrator.extractBasicMetadata(migration.jsonObject)
            val previewDoc = runCatching {
                json.decodeFromJsonElement<ProjectDocument>(migration.jsonObject)
            }.getOrNull()?.let { decoded ->
                if (decoded.schemaVersion <= CURRENT_PROJECT_SCHEMA_VERSION) {
                    decoded.copy(schemaVersion = migration.sourceVersion)
                } else {
                    decoded
                }
            }
            return ProjectOpenResult.UnsupportedFutureVersion(
                projectId = parsedId ?: previewDoc?.id,
                projectName = parsedName ?: previewDoc?.name,
                detectedSchemaVersion = migration.sourceVersion,
                supportedSchemaVersion = CURRENT_PROJECT_SCHEMA_VERSION,
                unknownTopLevelKeys = migration.unknownTopLevelKeys,
                rawJson = rawJson,
                readOnlyPreviewDocument = previewDoc,
            )
        }

        return try {
            val decoded = json.decodeFromJsonElement<ProjectDocument>(migration.jsonObject)
            val report = ProjectMigrationResult(
                document = decoded,
                sourceSchemaVersion = migration.sourceVersion,
                targetSchemaVersion = migration.targetVersion,
                didMigrate = migration.didMigrate,
                compatibility = migration.compatibility,
                isReadOnly = false,
                unknownTopLevelKeys = migration.unknownTopLevelKeys,
            )
            ProjectOpenResult.Supported(decoded, report)
        } catch (t: Throwable) {
            ProjectOpenResult.Corrupt(
                errorMessage = t.message ?: "Failed decoding ProjectDocument",
                cause = t,
            )
        }
    }

    /**
     * Explicit read-only preview decoder for future-schema projects (`schemaVersion > CURRENT_PROJECT_SCHEMA_VERSION`).
     * Returns null when the payload is supported/legacy or corrupt.
     */
    fun decodeFutureSchemaReadOnlyPreview(rawJson: String): ProjectOpenResult.UnsupportedFutureVersion? {
        return openProjectJson(rawJson) as? ProjectOpenResult.UnsupportedFutureVersion
    }

    /**
     * Supported editable migration & decode entry point.
     * Rejects unsupported future schemas with [UnsupportedFutureSchemaException] instead of
     * returning a downgraded or partially stripped [ProjectDocument].
     */
    fun deserializeWithReport(rawJson: String): ProjectMigrationResult {
        val migration = ProjectSchemaMigrator.migrateToCurrent(rawJson, json)
        if (migration.isUnsupportedFutureVersion) {
            throw UnsupportedFutureSchemaException(migration.sourceVersion)
        }
        val decoded = json.decodeFromJsonElement<ProjectDocument>(migration.jsonObject)
        return ProjectMigrationResult(
            document = decoded,
            sourceSchemaVersion = migration.sourceVersion,
            targetSchemaVersion = migration.targetVersion,
            didMigrate = migration.didMigrate,
            compatibility = migration.compatibility,
            isReadOnly = false,
            unknownTopLevelKeys = migration.unknownTopLevelKeys,
        )
    }

    fun deserializeEditableWithReport(rawJson: String): ProjectMigrationResult =
        deserializeWithReport(rawJson)

    private fun defaultTracks(): List<TimelineTrack> = listOf(
        TimelineTrack(
            id = "track_v1",
            name = "V1 Main",
            type = TrackType.VIDEO,
            orderIndex = 0,
        ),
        TimelineTrack(
            id = "track_ov1",
            name = "OV1 Overlay",
            type = TrackType.OVERLAY,
            orderIndex = 1,
        ),
        TimelineTrack(
            id = "track_t1",
            name = "T1 Titles",
            type = TrackType.TEXT,
            orderIndex = 2,
        ),
        TimelineTrack(
            id = "track_a1",
            name = "A1 Audio",
            type = TrackType.AUDIO,
            orderIndex = 3,
        ),
    )
}
