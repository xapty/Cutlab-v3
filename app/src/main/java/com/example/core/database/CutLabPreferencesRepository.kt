package com.example.core.database

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.performance.PreviewQualityMode
import com.example.engine.project.AspectRatioPreset
import com.example.engine.project.EditorMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.cutLabDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cutlab_preferences",
)

data class StudioPreferences(
    val defaultAspectRatio: AspectRatioPreset = AspectRatioPreset.VERTICAL_9_16,
    val defaultFps: Int = 30,
    val defaultEditorMode: EditorMode = EditorMode.QUICK,
    val autoAppendImportedToTimeline: Boolean = true,
    val preferProxyPlayback: Boolean = true,
    val previewQualityMode: PreviewQualityMode = PreviewQualityMode.AUTO,
)

class CutLabPreferencesRepository(private val context: Context) {
    private object Keys {
        val DEFAULT_ASPECT_RATIO = stringPreferencesKey("default_aspect_ratio")
        val DEFAULT_FPS = intPreferencesKey("default_fps")
        val DEFAULT_EDITOR_MODE = stringPreferencesKey("default_editor_mode")
        val AUTO_APPEND_IMPORTED = booleanPreferencesKey("auto_append_imported")
        val PREFER_PROXY_PLAYBACK = booleanPreferencesKey("prefer_proxy_playback")
        val PREVIEW_QUALITY_MODE = stringPreferencesKey("preview_quality_mode")
    }

    val preferencesFlow: Flow<StudioPreferences> = context.cutLabDataStore.data.map { prefs ->
        val ratio = prefs[Keys.DEFAULT_ASPECT_RATIO]
            ?.let { runCatching { AspectRatioPreset.valueOf(it) }.getOrNull() }
            ?: AspectRatioPreset.VERTICAL_9_16
        val fps = prefs[Keys.DEFAULT_FPS] ?: 30
        val mode = prefs[Keys.DEFAULT_EDITOR_MODE]
            ?.let { runCatching { EditorMode.valueOf(it) }.getOrNull() }
            ?: EditorMode.QUICK
        val autoAppend = prefs[Keys.AUTO_APPEND_IMPORTED] ?: true
        val preferProxy = prefs[Keys.PREFER_PROXY_PLAYBACK] ?: true
        val previewQuality = prefs[Keys.PREVIEW_QUALITY_MODE]
            ?.let { runCatching { PreviewQualityMode.valueOf(it) }.getOrNull() }
            ?: PreviewQualityMode.AUTO
        StudioPreferences(
            defaultAspectRatio = ratio,
            defaultFps = fps,
            defaultEditorMode = mode,
            autoAppendImportedToTimeline = autoAppend,
            preferProxyPlayback = preferProxy,
            previewQualityMode = previewQuality,
        )
    }

    suspend fun setDefaultAspectRatio(preset: AspectRatioPreset) {
        context.cutLabDataStore.edit { it[Keys.DEFAULT_ASPECT_RATIO] = preset.name }
    }

    suspend fun setDefaultFps(fps: Int) {
        context.cutLabDataStore.edit { it[Keys.DEFAULT_FPS] = fps }
    }

    suspend fun setDefaultEditorMode(mode: EditorMode) {
        context.cutLabDataStore.edit { it[Keys.DEFAULT_EDITOR_MODE] = mode.name }
    }

    suspend fun setAutoAppendImported(enabled: Boolean) {
        context.cutLabDataStore.edit { it[Keys.AUTO_APPEND_IMPORTED] = enabled }
    }

    suspend fun setPreferProxyPlayback(enabled: Boolean) {
        context.cutLabDataStore.edit { it[Keys.PREFER_PROXY_PLAYBACK] = enabled }
    }

    suspend fun setPreviewQualityMode(mode: PreviewQualityMode) {
        context.cutLabDataStore.edit { it[Keys.PREVIEW_QUALITY_MODE] = mode.name }
    }
}
