package com.example.engine.project

import android.net.Uri
import android.util.Log
import com.example.core.database.MediaAssetDao
import com.example.core.database.MediaAssetEntity
import com.example.core.database.ProjectDao
import com.example.core.database.ProjectEntity
import com.example.core.media.MediaImportResult
import com.example.core.media.MediaMetadataExtractor
import com.example.core.storage.MediaStorageManager
import com.example.engine.timeline.TimelineEngine
import com.example.engine.timeline.TimelineOverlapRepairMode
import com.example.engine.timeline.TimelineOverlapRepairOutcome
import com.example.engine.timeline.TimelineOverlapRepairReport
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BatchImportReport(
    val importedAssets: List<MediaAsset>,
    val failures: List<MediaImportResult.Failure>,
    val blockedByUnsupportedFutureSchema: Boolean = false,
)

sealed interface ProjectRepositoryLoadState {
    data class Ready(
        val document: ProjectDocument,
        val sourceSchemaVersion: Int,
        val didMigrateSchema: Boolean,
        val overlapRepairReport: TimelineOverlapRepairReport? = null,
    ) : ProjectRepositoryLoadState

    data class UnsupportedFutureVersion(
        val projectId: String,
        val projectName: String,
        val detectedSchemaVersion: Int,
        val supportedSchemaVersion: Int = CURRENT_PROJECT_SCHEMA_VERSION,
        val unknownTopLevelKeys: Set<String> = emptySet(),
        val previewDocument: ProjectDocument,
    ) : ProjectRepositoryLoadState

    data class Corrupt(
        val projectId: String,
        val fallbackDocument: ProjectDocument,
        val errorMessage: String,
    ) : ProjectRepositoryLoadState
}

sealed interface ProjectSaveOutcome {
    data class Saved(val document: ProjectDocument) : ProjectSaveOutcome
    data class BlockedUnsupportedFutureVersion(
        val projectId: String,
        val detectedSchemaVersion: Int,
        val supportedSchemaVersion: Int = CURRENT_PROJECT_SCHEMA_VERSION,
    ) : ProjectSaveOutcome
    data class BlockedCorruptData(
        val projectId: String,
        val errorMessage: String,
    ) : ProjectSaveOutcome
    data class ProjectNotFound(val projectId: String) : ProjectSaveOutcome
}

class ProjectRepository(
    private val projectDao: ProjectDao,
    private val mediaAssetDao: MediaAssetDao,
    private val projectEngine: ProjectEngine,
    private val storageManager: MediaStorageManager,
    private val metadataExtractor: MediaMetadataExtractor,
    private val timelineEngine: TimelineEngine = TimelineEngine(),
) {
    private val projectWriteLocks = ConcurrentHashMap<String, Mutex>()

    private fun mutexFor(projectId: String): Mutex =
        projectWriteLocks.computeIfAbsent(projectId) { Mutex() }

    fun observeProjectSummaries(): Flow<List<ProjectSummary>> {
        return combine(
            projectDao.observeAllProjects(),
            mediaAssetDao.observeAllAssets(),
        ) { projectEntities, assetEntities ->
            val assetsByProject = assetEntities.groupBy { it.projectId }
            projectEntities.map { entity ->
                val loadState = evaluateEntityLoadState(entity)
                val doc = when (loadState) {
                    is ProjectRepositoryLoadState.Ready -> loadState.document
                    is ProjectRepositoryLoadState.UnsupportedFutureVersion -> loadState.previewDocument
                    is ProjectRepositoryLoadState.Corrupt -> loadState.fallbackDocument
                }
                val isUnsupported = loadState is ProjectRepositoryLoadState.UnsupportedFutureVersion
                val detectedVer = when (loadState) {
                    is ProjectRepositoryLoadState.Ready -> loadState.sourceSchemaVersion
                    is ProjectRepositoryLoadState.UnsupportedFutureVersion -> loadState.detectedSchemaVersion
                    is ProjectRepositoryLoadState.Corrupt -> entity.schemaVersion
                }
                val hasPendingRepair = (loadState as? ProjectRepositoryLoadState.Ready)
                    ?.overlapRepairReport != null
                val projectAssets = assetsByProject[entity.id].orEmpty().map { it.toDomain() }
                val cover = projectAssets.firstOrNull {
                    it.type != MediaType.AUDIO && it.status == MediaStatus.READY
                }?.playbackUri ?: entity.coverMediaUri
                ProjectSummary(
                    document = doc,
                    mediaCount = projectAssets.size,
                    videoCount = projectAssets.count { it.type == MediaType.VIDEO },
                    imageCount = projectAssets.count { it.type == MediaType.IMAGE },
                    audioCount = projectAssets.count { it.type == MediaType.AUDIO },
                    totalMediaDurationMs = projectAssets.sumOf { it.durationMs },
                    coverMediaUri = cover,
                    isUnsupportedFutureVersion = isUnsupported,
                    detectedSchemaVersion = detectedVer,
                    hasPendingOverlapRepair = hasPendingRepair,
                )
            }
        }
    }

    fun observeProjectLoadState(projectId: String): Flow<ProjectRepositoryLoadState?> {
        return projectDao.observeProjectById(projectId).map { entity ->
            entity?.let(::evaluateEntityLoadState)
        }
    }

    fun observeProject(projectId: String): Flow<ProjectDocument?> {
        return observeProjectLoadState(projectId).map { state ->
            when (state) {
                is ProjectRepositoryLoadState.Ready -> state.document
                is ProjectRepositoryLoadState.UnsupportedFutureVersion -> state.previewDocument
                is ProjectRepositoryLoadState.Corrupt -> state.fallbackDocument
                null -> null
            }
        }
    }

    fun observeProjectAssets(projectId: String): Flow<List<MediaAsset>> {
        return mediaAssetDao.observeAssetsForProject(projectId).map { list ->
            list.map { it.toDomain() }
        }
    }

    fun observeAllMediaAssets(): Flow<List<MediaAsset>> {
        return mediaAssetDao.observeAllAssets().map { list ->
            list.map { it.toDomain() }
        }
    }

    suspend fun createProject(
        name: String,
        aspectRatio: AspectRatioPreset,
        fps: Int,
        editorMode: EditorMode,
    ): ProjectDocument {
        val doc = projectEngine.createNewDocument(
            name = name,
            aspectRatio = aspectRatio,
            fps = fps,
            editorMode = editorMode,
        )
        mutexFor(doc.id).withLock {
            projectDao.upsertProject(doc.toEntity(coverMediaUri = null))
        }
        return doc
    }

    suspend fun switchEditorMode(projectId: String, targetMode: EditorMode): ProjectSaveOutcome {
        return mutexFor(projectId).withLock {
            val entity = projectDao.getProjectById(projectId)
                ?: return@withLock ProjectSaveOutcome.ProjectNotFound(projectId)
            val futureVer = detectStoredFutureVersion(entity)
            if (futureVer != null) {
                return@withLock ProjectSaveOutcome.BlockedUnsupportedFutureVersion(projectId, futureVer)
            }
            val currentDoc = decodeDocumentStrictOrNull(entity)
                ?: return@withLock ProjectSaveOutcome.BlockedCorruptData(projectId, "Unreadable project JSON")
            val updated = projectEngine.switchMode(currentDoc, targetMode)
            projectDao.upsertProject(updated.toEntity(coverMediaUri = entity.coverMediaUri))
            ProjectSaveOutcome.Saved(updated)
        }
    }

    suspend fun renameProject(projectId: String, newName: String): ProjectSaveOutcome {
        return mutexFor(projectId).withLock {
            val entity = projectDao.getProjectById(projectId)
                ?: return@withLock ProjectSaveOutcome.ProjectNotFound(projectId)
            val futureVer = detectStoredFutureVersion(entity)
            if (futureVer != null) {
                return@withLock ProjectSaveOutcome.BlockedUnsupportedFutureVersion(projectId, futureVer)
            }
            val currentDoc = decodeDocumentStrictOrNull(entity)
                ?: return@withLock ProjectSaveOutcome.BlockedCorruptData(projectId, "Unreadable project JSON")
            val updated = projectEngine.rename(currentDoc, newName)
            projectDao.upsertProject(updated.toEntity(coverMediaUri = entity.coverMediaUri))
            ProjectSaveOutcome.Saved(updated)
        }
    }

    suspend fun updateProjectCanvas(
        projectId: String,
        aspectRatio: AspectRatioPreset,
        fps: Int,
    ): ProjectSaveOutcome {
        return mutexFor(projectId).withLock {
            val entity = projectDao.getProjectById(projectId)
                ?: return@withLock ProjectSaveOutcome.ProjectNotFound(projectId)
            val futureVer = detectStoredFutureVersion(entity)
            if (futureVer != null) {
                return@withLock ProjectSaveOutcome.BlockedUnsupportedFutureVersion(projectId, futureVer)
            }
            val currentDoc = decodeDocumentStrictOrNull(entity)
                ?: return@withLock ProjectSaveOutcome.BlockedCorruptData(projectId, "Unreadable project JSON")
            val updated = projectEngine.updateCanvas(currentDoc, aspectRatio, fps)
            projectDao.upsertProject(updated.toEntity(coverMediaUri = entity.coverMediaUri))
            ProjectSaveOutcome.Saved(updated)
        }
    }

    suspend fun updateProjectExportSettings(
        projectId: String,
        settings: ExportSettingsSpec,
    ): ProjectSaveOutcome {
        return mutexFor(projectId).withLock {
            val entity = projectDao.getProjectById(projectId)
                ?: return@withLock ProjectSaveOutcome.ProjectNotFound(projectId)
            val futureVer = detectStoredFutureVersion(entity)
            if (futureVer != null) {
                return@withLock ProjectSaveOutcome.BlockedUnsupportedFutureVersion(projectId, futureVer)
            }
            val currentDoc = decodeDocumentStrictOrNull(entity)
                ?: return@withLock ProjectSaveOutcome.BlockedCorruptData(projectId, "Unreadable project JSON")
            val updated = currentDoc.copy(
                exportSettings = settings,
                updatedAt = System.currentTimeMillis(),
            )
            projectDao.upsertProject(updated.toEntity(coverMediaUri = entity.coverMediaUri))
            ProjectSaveOutcome.Saved(updated)
        }
    }

    suspend fun duplicateProject(projectId: String): ProjectDocument? {
        return mutexFor(projectId).withLock {
            val entity = projectDao.getProjectById(projectId) ?: return@withLock null
            if (detectStoredFutureVersion(entity) != null) return@withLock null
            val sourceDoc = decodeDocumentStrictOrNull(entity) ?: return@withLock null
            val newDoc = projectEngine.duplicateDocument(
                source = sourceDoc,
                newName = "${sourceDoc.name} Copy",
            )
            val sourceAssets = mediaAssetDao.getAssetsForProject(projectId)
            val assetIdMap = mutableMapOf<String, String>()
            val copiedAssetEntities = sourceAssets.map { asset ->
                val newAssetId = UUID.randomUUID().toString()
                assetIdMap[asset.id] = newAssetId
                val copiedPath = asset.managedFilePath?.let { path ->
                    storageManager.copyFileForDuplicateProject(
                        sourcePath = path,
                        targetProjectId = newDoc.id,
                        newAssetId = newAssetId,
                    )
                }
                asset.copy(
                    id = newAssetId,
                    projectId = newDoc.id,
                    managedFilePath = copiedPath,
                    proxyFilePath = null,
                    statusName = if (copiedPath != null || asset.managedFilePath == null) {
                        asset.statusName
                    } else {
                        MediaStatus.MISSING.name
                    },
                    importedAt = System.currentTimeMillis(),
                )
            }

            val remappedTracks = newDoc.tracks.map { track ->
                track.copy(
                    clips = track.clips.map { clip ->
                        clip.copy(
                            clipId = UUID.randomUUID().toString(),
                            assetId = assetIdMap[clip.assetId] ?: clip.assetId,
                        )
                    },
                )
            }
            val finalDoc = newDoc.copy(tracks = remappedTracks)
            projectDao.upsertProject(finalDoc.toEntity(coverMediaUri = entity.coverMediaUri))
            if (copiedAssetEntities.isNotEmpty()) {
                mediaAssetDao.insertAssets(copiedAssetEntities)
            }
            finalDoc
        }
    }

    suspend fun deleteProject(projectId: String) {
        val proxyPaths = mediaAssetDao.getAssetsForProject(projectId).mapNotNull { it.proxyFilePath }
        mutexFor(projectId).withLock {
            projectDao.deleteProjectById(projectId)
        }
        projectWriteLocks.remove(projectId)
        storageManager.deleteProjectDirectory(projectId)
        storageManager.deleteFiles(proxyPaths)
    }

    suspend fun importMediaToProject(
        projectId: String?,
        uris: List<Uri>,
        hintType: MediaType?,
        appendToTimeline: Boolean = true,
    ): BatchImportReport {
        if (projectId != null) {
            val existingEntity = projectDao.getProjectById(projectId)
            if (existingEntity != null && detectStoredFutureVersion(existingEntity) != null) {
                return BatchImportReport(
                    importedAssets = emptyList(),
                    failures = emptyList(),
                    blockedByUnsupportedFutureSchema = true,
                )
            }
        }

        val imported = mutableListOf<MediaAsset>()
        val failures = mutableListOf<MediaImportResult.Failure>()

        for (uri in uris) {
            when (val result = metadataExtractor.importAndInspect(uri, projectId, hintType)) {
                is MediaImportResult.Success -> {
                    val asset = result.asset
                    mediaAssetDao.insertAsset(asset.toEntity())
                    imported += asset
                }
                is MediaImportResult.Failure -> {
                    failures += result
                }
            }
        }

        if (projectId != null && imported.isNotEmpty()) {
            mutexFor(projectId).withLock {
                val entity = projectDao.getProjectById(projectId)
                if (entity != null && detectStoredFutureVersion(entity) == null) {
                    val existingDoc = decodeDocumentStrictOrNull(entity)
                    if (existingDoc != null) {
                        var doc: ProjectDocument = existingDoc
                        if (appendToTimeline) {
                            for (asset in imported) {
                                val (updated, inserted) = timelineEngine.insertAssetAsClip(doc, asset)
                                if (inserted != null) {
                                    doc = updated
                                }
                            }
                        } else {
                            doc = doc.copy(updatedAt = System.currentTimeMillis())
                        }
                        val newCover = entity.coverMediaUri
                            ?: imported.firstOrNull { it.type != MediaType.AUDIO }?.playbackUri
                        projectDao.upsertProject(doc.toEntity(coverMediaUri = newCover))
                    }
                }
            }
        }

        return BatchImportReport(importedAssets = imported, failures = failures)
    }

    suspend fun deleteMediaAsset(assetId: String): Boolean {
        val entity = mediaAssetDao.getAssetById(assetId) ?: return false
        entity.projectId?.let { pid ->
            var blocked = false
            mutexFor(pid).withLock {
                val projectEntity = projectDao.getProjectById(pid)
                if (projectEntity != null) {
                    if (detectStoredFutureVersion(projectEntity) != null) {
                        blocked = true
                    } else {
                        val currentDoc = decodeDocumentStrictOrNull(projectEntity)
                        if (currentDoc != null) {
                            val updatedDoc = projectEngine.removeAssetReferences(
                                currentDoc,
                                assetId,
                            )
                            val remainingAssets = mediaAssetDao.getAssetsForProject(pid)
                                .filterNot { it.id == assetId }
                            val updatedCover = remainingAssets.firstOrNull {
                                it.mediaTypeName != MediaType.AUDIO.name
                            }?.let { remaining ->
                                remaining.managedFilePath?.let { "file://$it" } ?: remaining.sourceUri
                            }
                            projectDao.upsertProject(updatedDoc.toEntity(coverMediaUri = updatedCover))
                        }
                    }
                }
            }
            if (blocked) return false
        }
        mediaAssetDao.deleteAssetById(assetId)
        storageManager.deleteFile(entity.managedFilePath)
        storageManager.deleteFile(entity.proxyFilePath)
        return true
    }

    suspend fun updateAssetProxyPath(assetId: String, proxyFilePath: String?) {
        mediaAssetDao.updateProxyFilePath(assetId, proxyFilePath)
    }

    suspend fun verifyMediaIntegrity() {
        val paths = mediaAssetDao.getAllManagedFilePaths().toSet()
        val proxyPaths = mediaAssetDao.getAllProxyFilePaths().toSet()
        storageManager.pruneOrphanedFiles(paths, proxyPaths)
    }

    suspend fun getManagedStorageFootprintBytes(): Long =
        storageManager.calculateManagedStorageBytes()

    suspend fun pruneOrphanedMedia(): Int {
        val paths = mediaAssetDao.getAllManagedFilePaths().toSet()
        val proxyPaths = mediaAssetDao.getAllProxyFilePaths().toSet()
        return storageManager.pruneOrphanedFiles(paths, proxyPaths)
    }

    suspend fun addTrack(projectId: String, type: TrackType): TimelineTrack? {
        return mutexFor(projectId).withLock {
            val entity = projectDao.getProjectById(projectId) ?: return@withLock null
            val currentDoc = decodeDocumentStrictOrNull(entity) ?: return@withLock null
            val (updated, createdTrack) = timelineEngine.addTrack(currentDoc, type)
            projectDao.upsertProject(updated.toEntity(coverMediaUri = entity.coverMediaUri))
            createdTrack
        }
    }

    suspend fun toggleTrackMute(projectId: String, trackId: String) {
        mutexFor(projectId).withLock {
            val entity = projectDao.getProjectById(projectId) ?: return
            val currentDoc = decodeDocumentStrictOrNull(entity) ?: return
            val updated = timelineEngine.toggleTrackMute(currentDoc, trackId)
            projectDao.upsertProject(updated.toEntity(coverMediaUri = entity.coverMediaUri))
        }
    }

    suspend fun toggleTrackLock(projectId: String, trackId: String) {
        mutexFor(projectId).withLock {
            val entity = projectDao.getProjectById(projectId) ?: return
            val currentDoc = decodeDocumentStrictOrNull(entity) ?: return
            val updated = timelineEngine.toggleTrackLock(currentDoc, trackId)
            projectDao.upsertProject(updated.toEntity(coverMediaUri = entity.coverMediaUri))
        }
    }

    suspend fun removeTrack(projectId: String, trackId: String) {
        mutexFor(projectId).withLock {
            val entity = projectDao.getProjectById(projectId) ?: return
            val currentDoc = decodeDocumentStrictOrNull(entity) ?: return
            val updated = timelineEngine.removeTrack(currentDoc, trackId)
            projectDao.upsertProject(updated.toEntity(coverMediaUri = entity.coverMediaUri))
        }
    }

    suspend fun insertAssetClip(
        projectId: String,
        assetId: String,
        targetTrackId: String? = null,
        atTimelineMs: Long? = null,
    ): TimelineClipRef? {
        return mutexFor(projectId).withLock {
            val entity = projectDao.getProjectById(projectId) ?: return@withLock null
            val currentDoc = decodeDocumentStrictOrNull(entity) ?: return@withLock null
            val assetEntity = mediaAssetDao.getAssetById(assetId) ?: return@withLock null
            val (updated, newClip) = timelineEngine.insertAssetAsClip(
                document = currentDoc,
                asset = assetEntity.toDomain(),
                targetTrackId = targetTrackId,
                atTimelineMs = atTimelineMs,
            )
            if (newClip != null) {
                projectDao.upsertProject(updated.toEntity(coverMediaUri = entity.coverMediaUri))
            }
            newClip
        }
    }

    suspend fun removeClip(projectId: String, clipId: String) {
        mutexFor(projectId).withLock {
            val entity = projectDao.getProjectById(projectId) ?: return
            val currentDoc = decodeDocumentStrictOrNull(entity) ?: return
            val updated = timelineEngine.removeClip(currentDoc, clipId)
            projectDao.upsertProject(updated.toEntity(coverMediaUri = entity.coverMediaUri))
        }
    }

    suspend fun reorderTrack(projectId: String, trackId: String, delta: Int) {
        mutexFor(projectId).withLock {
            val entity = projectDao.getProjectById(projectId) ?: return
            val currentDoc = decodeDocumentStrictOrNull(entity) ?: return
            val updated = timelineEngine.reorderTrack(currentDoc, trackId, delta)
            projectDao.upsertProject(updated.toEntity(coverMediaUri = entity.coverMediaUri))
        }
    }

    suspend fun saveDocument(document: ProjectDocument): ProjectSaveOutcome {
        if (document.schemaVersion > CURRENT_PROJECT_SCHEMA_VERSION) {
            return ProjectSaveOutcome.BlockedUnsupportedFutureVersion(
                projectId = document.id,
                detectedSchemaVersion = document.schemaVersion,
            )
        }
        return mutexFor(document.id).withLock {
            val existingEntity = projectDao.getProjectById(document.id)
            if (existingEntity != null) {
                val futureVer = detectStoredFutureVersion(existingEntity)
                if (futureVer != null) {
                    Log.w(
                        "CutLabRepo",
                        "Blocked saveDocument for ${document.id}: stored schema v$futureVer exceeds supported v$CURRENT_PROJECT_SCHEMA_VERSION",
                    )
                    return@withLock ProjectSaveOutcome.BlockedUnsupportedFutureVersion(
                        projectId = document.id,
                        detectedSchemaVersion = futureVer,
                    )
                }
            }
            val safeDoc = if (document.schemaVersion < CURRENT_PROJECT_SCHEMA_VERSION) {
                document.copy(schemaVersion = CURRENT_PROJECT_SCHEMA_VERSION)
            } else {
                document
            }
            projectDao.upsertProject(safeDoc.toEntity(coverMediaUri = existingEntity?.coverMediaUri))
            ProjectSaveOutcome.Saved(safeDoc)
        }
    }

    suspend fun applyOverlapRepair(
        projectId: String,
        mode: TimelineOverlapRepairMode = TimelineOverlapRepairMode.MOVE_TO_OVERFLOW_TRACK,
    ): TimelineOverlapRepairOutcome? {
        return mutexFor(projectId).withLock {
            val entity = projectDao.getProjectById(projectId) ?: return@withLock null
            if (detectStoredFutureVersion(entity) != null) return@withLock null
            val currentDoc = decodeDocumentStrictOrNull(entity) ?: return@withLock null
            val outcome = timelineEngine.repairOverlappingTracks(currentDoc, mode)
            if (outcome.document != currentDoc) {
                projectDao.upsertProject(outcome.document.toEntity(coverMediaUri = entity.coverMediaUri))
            }
            outcome
        }
    }

    private fun detectStoredFutureVersion(entity: ProjectEntity): Int? {
        val jsonVersion = ProjectSchemaMigrator.inspectSchemaVersion(entity.projectJson) ?: 0
        val maxVersion = maxOf(entity.schemaVersion, jsonVersion)
        return maxVersion.takeIf { it > CURRENT_PROJECT_SCHEMA_VERSION }
    }

    /**
     * Strict decode used by write paths so corrupted project JSON or unsupported future schemas
     * are never silently overwritten on disk. Does NOT silently mutate clip timing.
     */
    private fun decodeDocumentStrictOrNull(entity: ProjectEntity): ProjectDocument? {
        val futureVersion = detectStoredFutureVersion(entity)
        if (futureVersion != null) {
            Log.w(
                "CutLabRepo",
                "Refusing write on unsupported future project schema v$futureVersion (${entity.id})",
            )
            return null
        }
        return when (val open = projectEngine.openProjectJson(entity.projectJson)) {
            is ProjectOpenResult.Supported -> open.document
            is ProjectOpenResult.UnsupportedFutureVersion -> {
                Log.w(
                    "CutLabRepo",
                    "Refusing write on future schema v${open.detectedSchemaVersion} (${entity.id})",
                )
                null
            }
            is ProjectOpenResult.Corrupt -> {
                Log.e(
                    "CutLabRepo",
                    "Refusing destructive write on unreadable project JSON (${entity.id})",
                    open.cause,
                )
                null
            }
        }
    }

    private fun evaluateEntityLoadState(entity: ProjectEntity): ProjectRepositoryLoadState {
        val entityFutureVersion = entity.schemaVersion.takeIf { it > CURRENT_PROJECT_SCHEMA_VERSION }
        return when (val open = projectEngine.openProjectJson(entity.projectJson)) {
            is ProjectOpenResult.Supported -> {
                if (entityFutureVersion != null) {
                    ProjectRepositoryLoadState.UnsupportedFutureVersion(
                        projectId = entity.id,
                        projectName = open.document.name,
                        detectedSchemaVersion = entityFutureVersion,
                        supportedSchemaVersion = CURRENT_PROJECT_SCHEMA_VERSION,
                        unknownTopLevelKeys = open.migrationResult.unknownTopLevelKeys,
                        previewDocument = open.document.copy(schemaVersion = entityFutureVersion),
                    )
                } else {
                    val repairReport = timelineEngine.inspectTrackOverlaps(open.document)
                        .takeIf { it.hasOverlaps }
                    ProjectRepositoryLoadState.Ready(
                        document = open.document,
                        sourceSchemaVersion = open.migrationResult.sourceSchemaVersion,
                        didMigrateSchema = open.migrationResult.didMigrate,
                        overlapRepairReport = repairReport,
                    )
                }
            }
            is ProjectOpenResult.UnsupportedFutureVersion -> {
                val detected = maxOf(open.detectedSchemaVersion, entity.schemaVersion)
                val preview = open.readOnlyPreviewDocument
                    ?: buildFallbackDocument(entity, schemaVersion = detected)
                ProjectRepositoryLoadState.UnsupportedFutureVersion(
                    projectId = entity.id,
                    projectName = open.projectName ?: entity.name,
                    detectedSchemaVersion = detected,
                    supportedSchemaVersion = open.supportedSchemaVersion,
                    unknownTopLevelKeys = open.unknownTopLevelKeys,
                    previewDocument = preview,
                )
            }
            is ProjectOpenResult.Corrupt -> {
                Log.e("CutLabRepo", "Failed decoding project ${entity.id}; returning read-only fallback", open.cause)
                ProjectRepositoryLoadState.Corrupt(
                    projectId = entity.id,
                    fallbackDocument = buildFallbackDocument(entity, schemaVersion = entity.schemaVersion),
                    errorMessage = open.errorMessage,
                )
            }
        }
    }

    private fun buildFallbackDocument(entity: ProjectEntity, schemaVersion: Int): ProjectDocument {
        return projectEngine.createNewDocument(
            name = entity.name,
            aspectRatio = runCatching { AspectRatioPreset.valueOf(entity.aspectRatioName) }
                .getOrDefault(AspectRatioPreset.VERTICAL_9_16),
            fps = entity.fps,
            editorMode = runCatching { EditorMode.valueOf(entity.editorModeName) }
                .getOrDefault(EditorMode.QUICK),
        ).copy(
            schemaVersion = schemaVersion,
            id = entity.id,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )
    }

    private fun ProjectDocument.toEntity(coverMediaUri: String?): ProjectEntity = ProjectEntity(
        id = id,
        name = name,
        aspectRatioName = aspectRatio.name,
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
        fps = fps,
        editorModeName = editorMode.name,
        schemaVersion = schemaVersion,
        projectJson = projectEngine.serialize(this),
        coverMediaUri = coverMediaUri,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun MediaAssetEntity.toDomain(): MediaAsset {
        val fileHealthy = managedFilePath == null || storageManager.fileExists(managedFilePath)
        val resolvedStatus = if (!fileHealthy) {
            MediaStatus.MISSING
        } else {
            runCatching { MediaStatus.valueOf(statusName) }.getOrDefault(MediaStatus.READY)
        }
        val healthyProxy = proxyFilePath?.takeIf { storageManager.fileExists(it) }
        return MediaAsset(
            id = id,
            projectId = projectId,
            type = runCatching { MediaType.valueOf(mediaTypeName) }.getOrDefault(MediaType.VIDEO),
            displayName = displayName,
            sourceUri = sourceUri,
            managedFilePath = managedFilePath,
            mimeType = mimeType,
            durationMs = durationMs,
            width = width,
            height = height,
            fps = fps,
            bitrate = bitrate,
            fileSizeBytes = fileSizeBytes,
            status = resolvedStatus,
            importedAt = importedAt,
            proxyFilePath = healthyProxy,
        )
    }

    private fun MediaAsset.toEntity(): MediaAssetEntity = MediaAssetEntity(
        id = id,
        projectId = projectId,
        mediaTypeName = type.name,
        displayName = displayName,
        sourceUri = sourceUri,
        managedFilePath = managedFilePath,
        mimeType = mimeType,
        durationMs = durationMs,
        width = width,
        height = height,
        fps = fps,
        bitrate = bitrate,
        fileSizeBytes = fileSizeBytes,
        statusName = status.name,
        importedAt = importedAt,
        proxyFilePath = proxyFilePath,
    )
}
