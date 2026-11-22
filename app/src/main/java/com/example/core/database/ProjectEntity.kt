package com.example.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val aspectRatioName: String,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val fps: Int,
    val editorModeName: String,
    val schemaVersion: Int,
    val projectJson: String,
    val coverMediaUri: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
