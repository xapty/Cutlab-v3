package com.example.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaAssetDao {
    @Query("SELECT * FROM media_assets ORDER BY importedAt DESC")
    fun observeAllAssets(): Flow<List<MediaAssetEntity>>

    @Query("SELECT * FROM media_assets WHERE projectId = :projectId ORDER BY importedAt DESC")
    fun observeAssetsForProject(projectId: String): Flow<List<MediaAssetEntity>>

    @Query("SELECT * FROM media_assets WHERE projectId = :projectId ORDER BY importedAt DESC")
    suspend fun getAssetsForProject(projectId: String): List<MediaAssetEntity>

    @Query("SELECT * FROM media_assets WHERE id = :assetId")
    suspend fun getAssetById(assetId: String): MediaAssetEntity?

    @Query("SELECT managedFilePath FROM media_assets WHERE managedFilePath IS NOT NULL")
    suspend fun getAllManagedFilePaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAsset(asset: MediaAssetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssets(assets: List<MediaAssetEntity>)

    @Query("UPDATE media_assets SET statusName = :statusName WHERE id = :assetId")
    suspend fun updateAssetStatus(assetId: String, statusName: String)

    @Query("UPDATE media_assets SET proxyFilePath = :proxyPath WHERE id = :assetId")
    suspend fun updateProxyFilePath(assetId: String, proxyPath: String?)

    @Query("SELECT proxyFilePath FROM media_assets WHERE proxyFilePath IS NOT NULL")
    suspend fun getAllProxyFilePaths(): List<String>

    @Query("DELETE FROM media_assets WHERE id = :assetId")
    suspend fun deleteAssetById(assetId: String)
}
