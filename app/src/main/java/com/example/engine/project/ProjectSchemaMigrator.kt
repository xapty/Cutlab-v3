package com.example.engine.project

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class SchemaCompatibility {
    CURRENT,
    MIGRATED_FROM_LEGACY,
    UNSUPPORTED_FUTURE,
}

data class JsonSchemaMigrationOutput(
    val jsonObject: JsonObject,
    val sourceVersion: Int,
    val targetVersion: Int,
    val didMigrate: Boolean,
    val compatibility: SchemaCompatibility,
    val unknownTopLevelKeys: Set<String> = emptySet(),
) {
    val isUnsupportedFutureVersion: Boolean
        get() = compatibility == SchemaCompatibility.UNSUPPORTED_FUTURE
}

/**
 * Forward-compatible, stepwise JSON migration pipeline for [ProjectDocument].
 *
 * - Legacy versions (< [CURRENT_PROJECT_SCHEMA_VERSION]) are migrated step-by-step to current.
 * - Unsupported future versions (> [CURRENT_PROJECT_SCHEMA_VERSION]) are detected and flagged as
 *   [SchemaCompatibility.UNSUPPORTED_FUTURE] so callers can enforce read-only protection without
 *   overwriting unknown future fields on disk.
 */
object ProjectSchemaMigrator {

    private val KNOWN_PROJECT_DOCUMENT_KEYS = setOf(
        "schemaVersion",
        "id",
        "name",
        "aspectRatio",
        "canvasWidth",
        "canvasHeight",
        "fps",
        "editorMode",
        "tracks",
        "exportSettings",
        "createdAt",
        "updatedAt",
        "markers",
    )

    fun inspectSchemaVersion(
        rawJson: String,
        json: Json = Json { ignoreUnknownKeys = true },
    ): Int? {
        return runCatching {
            val root = json.parseToJsonElement(rawJson).jsonObject
            root["schemaVersion"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 1
        }.getOrNull()
    }

    fun extractBasicMetadata(
        root: JsonObject,
    ): Pair<String?, String?> {
        val id = root["id"]?.jsonPrimitive?.contentOrNull
        val name = root["name"]?.jsonPrimitive?.contentOrNull
        return id to name
    }

    fun migrateToCurrent(
        rawJson: String,
        json: Json = Json { ignoreUnknownKeys = true },
    ): JsonSchemaMigrationOutput {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val detectedVersion = root["schemaVersion"]
            ?.jsonPrimitive
            ?.intOrNull
            ?.coerceAtLeast(0)
            ?: 1
        val unknownTopLevel = root.keys - KNOWN_PROJECT_DOCUMENT_KEYS

        if (detectedVersion > CURRENT_PROJECT_SCHEMA_VERSION) {
            return JsonSchemaMigrationOutput(
                jsonObject = root,
                sourceVersion = detectedVersion,
                targetVersion = CURRENT_PROJECT_SCHEMA_VERSION,
                didMigrate = false,
                compatibility = SchemaCompatibility.UNSUPPORTED_FUTURE,
                unknownTopLevelKeys = unknownTopLevel,
            )
        }

        if (detectedVersion == CURRENT_PROJECT_SCHEMA_VERSION) {
            return JsonSchemaMigrationOutput(
                jsonObject = root,
                sourceVersion = detectedVersion,
                targetVersion = CURRENT_PROJECT_SCHEMA_VERSION,
                didMigrate = false,
                compatibility = SchemaCompatibility.CURRENT,
                unknownTopLevelKeys = unknownTopLevel,
            )
        }

        var current = root
        var version = detectedVersion
        while (version < CURRENT_PROJECT_SCHEMA_VERSION) {
            current = when (version) {
                0, 1 -> migrateV1ToV2(current)
                2 -> migrateV2ToV3(current)
                else -> current
            }
            version = when {
                version < 1 -> 2
                else -> version + 1
            }
        }

        return JsonSchemaMigrationOutput(
            jsonObject = current,
            sourceVersion = detectedVersion,
            targetVersion = CURRENT_PROJECT_SCHEMA_VERSION,
            didMigrate = true,
            compatibility = SchemaCompatibility.MIGRATED_FROM_LEGACY,
            unknownTopLevelKeys = current.keys - KNOWN_PROJECT_DOCUMENT_KEYS,
        )
    }

    private fun migrateV1ToV2(v1: JsonObject): JsonObject {
        val fps = v1["fps"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 240) ?: 30
        val map = v1.toMutableMap()
        map["schemaVersion"] = JsonPrimitive(2)

        if (map["markers"] == null || map["markers"] !is JsonArray) {
            map["markers"] = buildJsonArray { }
        }

        if (map["exportSettings"] == null || map["exportSettings"] !is JsonObject) {
            map["exportSettings"] = buildJsonObject {
                put("resolutionHeight", JsonPrimitive(1080))
                put("fps", JsonPrimitive(fps))
                put("codec", JsonPrimitive("H.264"))
                put("audioBitrateKbps", JsonPrimitive(192))
            }
        }

        val tracksElem = map["tracks"]
        if (tracksElem is JsonArray) {
            val migratedTracks = tracksElem.mapIndexed { idx, elem ->
                val obj = elem as? JsonObject ?: return@mapIndexed elem
                val tMap = obj.toMutableMap()
                if (tMap["orderIndex"] == null) {
                    tMap["orderIndex"] = JsonPrimitive(idx)
                }
                val clipsElem = tMap["clips"]
                if (clipsElem !is JsonArray) {
                    tMap["clips"] = JsonArray(emptyList())
                } else {
                    val trackId = tMap["id"]?.jsonPrimitive?.content.orEmpty()
                    val normalizedClips = clipsElem.map { clipElem ->
                        val cObj = clipElem as? JsonObject ?: return@map clipElem
                        val cMap = cObj.toMutableMap()
                        if (cMap["trackId"] == null && trackId.isNotEmpty()) {
                            cMap["trackId"] = JsonPrimitive(trackId)
                        }
                        if (cMap["clipKind"] == null) {
                            cMap["clipKind"] = JsonPrimitive(
                                when {
                                    cMap["textSpec"] != null && cMap["textSpec"]?.toString() != "null" -> ClipKind.TEXT.name
                                    else -> ClipKind.MEDIA.name
                                },
                            )
                        }
                        JsonObject(cMap)
                    }
                    tMap["clips"] = JsonArray(normalizedClips)
                }
                JsonObject(tMap)
            }
            map["tracks"] = JsonArray(migratedTracks)
        } else {
            map["tracks"] = JsonArray(emptyList())
        }

        return JsonObject(map)
    }

    private fun migrateV2ToV3(v2: JsonObject): JsonObject {
        val map = v2.toMutableMap()
        map["schemaVersion"] = JsonPrimitive(3)

        val tracksElem = map["tracks"]
        if (tracksElem is JsonArray) {
            val normalizedTracks = tracksElem.map { trackElem ->
                val tObj = trackElem as? JsonObject ?: return@map trackElem
                val tMap = tObj.toMutableMap()
                val clipsElem = tMap["clips"]
                if (clipsElem is JsonArray) {
                    val normalizedClips = clipsElem.map { clipElem ->
                        val cObj = clipElem as? JsonObject ?: return@map clipElem
                        val textElem = cObj["textSpec"] as? JsonObject ?: return@map clipElem
                        val specMap = textElem.toMutableMap()
                        if (specMap["contentType"] == null) {
                            specMap["contentType"] = JsonPrimitive(TextContentType.TITLE.name)
                        }
                        if (specMap["renderMode"] == null) {
                            specMap["renderMode"] = JsonPrimitive(TextRenderMode.NORMAL.name)
                        }
                        if (specMap["karaokeMode"] == null) {
                            specMap["karaokeMode"] = JsonPrimitive(KaraokeHighlightMode.NONE.name)
                        }
                        if (specMap["timedWords"] == null) {
                            specMap["timedWords"] = JsonArray(emptyList())
                        }
                        val cMap = cObj.toMutableMap()
                        cMap["textSpec"] = JsonObject(specMap)
                        JsonObject(cMap)
                    }
                    tMap["clips"] = JsonArray(normalizedClips)
                }
                JsonObject(tMap)
            }
            map["tracks"] = JsonArray(normalizedTracks)
        }

        return JsonObject(map)
    }
}
