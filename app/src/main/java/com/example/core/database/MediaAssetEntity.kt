package com.example.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_assets",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId")],
)
data class MediaAssetEntity(
    @PrimaryKey val id: String,
    val projectId: String?,
    val mediaTypeName: String,
    val displayName: String,
    val sourceUri: String,
    val managedFilePath: String?,
    val mimeType: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Float,
    val bitrate: Long,
    val fileSizeBytes: Long,
    val statusName: String,
    val importedAt: Long,
    val proxyFilePath: String? = null,
)
