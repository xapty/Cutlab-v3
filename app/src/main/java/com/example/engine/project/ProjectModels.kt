package com.example.engine.project

import kotlinx.serialization.Serializable

const val CURRENT_PROJECT_SCHEMA_VERSION = 3

@Serializable
enum class EditorMode(val label: String, val subtitle: String) {
    QUICK("Quick Edit", "Streamlined cuts, presets & social ratios"),
    ADVANCED("Advanced Edit", "Multi-track layers, keyframes & graph curves"),
}

@Serializable
enum class AspectRatioPreset(
    val label: String,
    val width: Int,
    val height: Int,
    val tag: String,
) {
    VERTICAL_9_16("9:16", 1080, 1920, "Reels / TikTok"),
    LANDSCAPE_16_9("16:9", 1920, 1080, "YouTube / AMV"),
    SQUARE_1_1("1:1", 1080, 1080, "Square Feed"),
    PORTRAIT_4_5("4:5", 1080, 1350, "Portrait Feed"),
    CINEMA_21_9("21:9", 2560, 1080, "Anamorphic"),
}

@Serializable
enum class FrameRateOption(val fps: Int, val label: String) {
    FPS_24(24, "24 FPS"),
    FPS_25(25, "25 FPS"),
    FPS_30(30, "30 FPS"),
    FPS_50(50, "50 FPS"),
    FPS_60(60, "60 FPS"),
}

@Serializable
enum class MediaType(val label: String) {
    VIDEO("Video"),
    IMAGE("Image"),
    AUDIO("Audio"),
}

@Serializable
enum class MediaStatus(val label: String) {
    READY("Ready"),
    MISSING("Missing"),
    UNSUPPORTED("Unsupported"),
}

@Serializable
enum class TrackType(val shortPrefix: String, val label: String) {
    VIDEO("V", "Video"),
    OVERLAY("OV", "Overlay"),
    TEXT("T", "Text"),
    AUDIO("A", "Audio"),
    ADJUSTMENT("ADJ", "Adjustment"),
}

@Serializable
enum class ClipKind {
    MEDIA,
    TEXT,
    ADJUSTMENT,
}

@Serializable
enum class TransitionType(val label: String) {
    NONE("None"),
    CROSS_DISSOLVE("Dissolve"),
    FADE_BLACK("Fade Black"),
    ZOOM_IN("Zoom"),
    SLIDE_LEFT("Slide Left"),
    SLIDE_UP("Slide Up"),
    WIPE_RIGHT("Wipe"),
    BLUR("Blur"),
    SPIN("Spin"),
    GLITCH("Glitch"),
}

@Serializable
enum class EasingPreset(val label: String) {
    LINEAR("Linear"),
    EASE_IN("Ease In"),
    EASE_OUT("Ease Out"),
    EASE_IN_OUT("Ease In-Out"),
}

@Serializable
data class ClipTransition(
    val type: TransitionType = TransitionType.CROSS_DISSOLVE,
    val durationMs: Long = 500L,
    val easing: EasingPreset = EasingPreset.EASE_IN_OUT,
)

@Serializable
enum class FilterPreset(val label: String) {
    NONE("Original"),
    CINEMATIC("Cinematic"),
    VIVID("Vivid"),
    WARM_VINTAGE("Warm Film"),
    COOL_SLATE("Cool Slate"),
    MONO("Mono B&W"),
    DREAMY("Dreamy"),
    CYBER_NEON("Cyber Neon"),
}

@Serializable
data class ClipAdjustments(
    val filterPreset: FilterPreset = FilterPreset.NONE,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val exposure: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val vignette: Float = 0f,
    val blurRadius: Float = 0f,
    val sharpen: Float = 0f,
) {
    val isDefault: Boolean
        get() = filterPreset == FilterPreset.NONE &&
            brightness == 0f &&
            contrast == 1f &&
            saturation == 1f &&
            exposure == 0f &&
            temperature == 0f &&
            tint == 0f &&
            vignette == 0f &&
            blurRadius == 0f &&
            sharpen == 0f
}

@Serializable
enum class BlendModePreset(val label: String, val shaderId: Int) {
    NORMAL("Normal", 0),
    ADD("Add", 1),
    MULTIPLY("Multiply", 2),
    SCREEN("Screen", 3),
    OVERLAY("Overlay", 4),
    DARKEN("Darken", 5),
    LIGHTEN("Lighten", 6),
}

@Serializable
data class LayerTransform(
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDeg: Float = 0f,
    val opacity: Float = 1f,
    val anchorX: Float = 0.5f,
    val anchorY: Float = 0.5f,
    val uniformScale: Boolean = true,
) {
    val isDefault: Boolean
        get() = positionX == 0f &&
            positionY == 0f &&
            scaleX == 1f &&
            scaleY == 1f &&
            rotationDeg == 0f &&
            opacity == 1f &&
            anchorX == 0.5f &&
            anchorY == 0.5f
}

@Serializable
enum class MaskShape(val label: String, val shaderId: Int) {
    NONE("None", 0),
    RECTANGLE("Rectangle", 1),
    ELLIPSE("Ellipse", 2),
    LINEAR_SPLIT("Linear Split", 3),
}

@Serializable
data class LayerMask(
    val shape: MaskShape = MaskShape.NONE,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val width: Float = 0.65f,
    val height: Float = 0.65f,
    val rotationDeg: Float = 0f,
    val feather: Float = 0.08f,
    val cornerRadius: Float = 0.08f,
    val inverted: Boolean = false,
) {
    val isActive: Boolean
        get() = shape != MaskShape.NONE
}

@Serializable
enum class ShaderEffectType(val label: String, val shaderId: Int) {
    NONE("None", 0),
    CHROMATIC_ABERRATION("Chromatic", 1),
    RGB_SPLIT("RGB Split", 2),
    FILM_GRAIN("Film Grain", 3),
    GLOW("Bloom Glow", 4),
    VHS_SCANLINES("VHS Scan", 5),
    PIXELATE("Pixelate", 6),
    SHARPEN_EDGE("Crisp Edge", 7),
    ZOOM_BLUR("Zoom Blur", 8),
    FISHEYE("Lens Warp", 9),
    HALATION("Halation", 10),
    NEON_EDGE("Neon Edge", 11),
}

@Serializable
data class ClipShaderEffect(
    val type: ShaderEffectType = ShaderEffectType.NONE,
    val intensity: Float = 0.5f,
) {
    val isActive: Boolean
        get() = type != ShaderEffectType.NONE && intensity > 0.01f
}

@Serializable
enum class SpeedRampPreset(val label: String) {
    NONE("Uniform"),
    MONTAGE("Montage"),
    HERO_MOMENT("Hero"),
    BULLET_TIME("Bullet"),
    JUMP_CUT("Jump Cut"),
    FLASH_IN("Flash In"),
    FLASH_OUT("Flash Out"),
}

@Serializable
data class SpeedPoint(
    val normalizedTime: Float,
    val speed: Float,
)

@Serializable
data class ChromaKeySpec(
    val enabled: Boolean = false,
    val keyColorArgb: Long = 0xFF00D84AL,
    val similarity: Float = 0.34f,
    val smoothness: Float = 0.14f,
    val spillSuppression: Float = 0.45f,
)

@Serializable
enum class LutPreset(val label: String) {
    NONE("None"),
    TEAL_ORANGE("Teal & Orange"),
    KODAK_2383("Kodak 2383"),
    FUJI_PRO("Fuji Velvia"),
    BLEACH_BYPASS("Bleach Bypass"),
    NOIR_PUNCH("Noir Punch"),
    GOLDEN_HOUR("Golden Hour"),
    CYBER_MINT("Cyber Mint"),
    CUSTOM_CUBE("Custom .cube"),
}

@Serializable
data class ClipLutSpec(
    val preset: LutPreset = LutPreset.NONE,
    val intensity: Float = 0.85f,
    val customCubePath: String? = null,
    val customCubeName: String? = null,
) {
    val isActive: Boolean
        get() = preset != LutPreset.NONE &&
            intensity > 0.01f &&
            (preset != LutPreset.CUSTOM_CUBE || !customCubePath.isNullOrBlank())
}

@Serializable
data class StabilizationSpec(
    val enabled: Boolean = false,
    val strength: Float = 0.65f,
    val cropScale: Float = 1.12f,
)

@Serializable
enum class TextStylePreset(val label: String) {
    BOLD_TITLE("Bold Title"),
    MINIMAL_SUBTITLE("Subtitle"),
    NEON_POP("Neon Pop"),
    KINETIC_BADGE("Badge Pill"),
    CINEMA_SERIF("Cinema"),
    MONO_TYPEWRITER("Mono Code"),
}

@Serializable
enum class TextAnimationPreset(val label: String) {
    NONE("Static"),
    FADE_IN("Fade"),
    SLIDE_UP("Slide Up"),
    POP_OVERSHOOT("Pop"),
    TYPEWRITER("Typewriter"),
    GLITCH("Glitch"),
}

@Serializable
enum class TextAlignMode(val label: String) {
    LEFT("Left"),
    CENTER("Center"),
    RIGHT("Right"),
}

@Serializable
enum class TextContentType(val label: String) {
    TITLE("Title"),
    CAPTION("Caption"),
    LYRIC("Lyric"),
}

@Serializable
enum class TextRenderMode(val label: String) {
    NORMAL("Standard"),
    INVERT_VIDEO("Invert Lyric"),
}

@Serializable
enum class KaraokeHighlightMode(val label: String) {
    NONE("Off"),
    ACTIVE_WORD("Active Word"),
    PROGRESSIVE("Progressive"),
}

@Serializable
data class TimedTextWord(
    val id: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val isEstimated: Boolean = false,
    val confidence: Float = 1.0f,
)

@Serializable
data class TextOverlaySpec(
    val text: String = "Your Title",
    val stylePreset: TextStylePreset = TextStylePreset.BOLD_TITLE,
    val animationPreset: TextAnimationPreset = TextAnimationPreset.POP_OVERSHOOT,
    val fontScale: Float = 1.0f,
    val textColorArgb: Long = 0xFFFFFFFF,
    val backgroundColorArgb: Long = 0x00000000,
    val hasShadow: Boolean = true,
    val hasStroke: Boolean = false,
    val alignment: TextAlignMode = TextAlignMode.CENTER,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val contentType: TextContentType = TextContentType.TITLE,
    val renderMode: TextRenderMode = TextRenderMode.NORMAL,
    val timedWords: List<TimedTextWord> = emptyList(),
    val karaokeMode: KaraokeHighlightMode = KaraokeHighlightMode.NONE,
    val highlightColorArgb: Long = 0xFF38BDF8,
    val spokenColorArgb: Long = 0xFFE2E8F0,
    val wordSpacingEm: Float = 0f,
    val invertStrength: Float = 1.0f,
    val invertEdgeSoftness: Float = 0.08f,
    val invertIncludeBackground: Boolean = false,
    val sourceLanguage: String? = null,
    val sourceAssetId: String? = null,
    val isTimingEstimated: Boolean = false,
)

@Serializable
enum class KeyframeProperty(
    val label: String,
    val minValue: Float,
    val maxValue: Float,
    val defaultValue: Float,
    val unitSuffix: String,
) {
    POSITION_X("Pos X", -1f, 1f, 0f, "%"),
    POSITION_Y("Pos Y", -1f, 1f, 0f, "%"),
    SCALE_X("Scale X", 0.1f, 4.0f, 1f, "x"),
    SCALE_Y("Scale Y", 0.1f, 4.0f, 1f, "x"),
    ROTATION("Rotation", -360f, 360f, 0f, "°"),
    OPACITY("Opacity", 0f, 1f, 1f, "%"),
    ANCHOR_X("Anchor X", 0f, 1f, 0.5f, "%"),
    ANCHOR_Y("Anchor Y", 0f, 1f, 0.5f, "%"),
    VOLUME("Volume", 0f, 2f, 1f, "%"),
    BLUR("Blur", 0f, 24f, 0f, "px"),
    SHADER_INTENSITY("Shader FX", 0f, 1f, 0.5f, "%"),
    MASK_FEATHER("Feather", 0f, 0.5f, 0.08f, "%"),
    TEXT_FONT_SCALE("Text Size", 0.5f, 2.5f, 1f, "x"),
    TEXT_INVERT_STRENGTH("Invert Strength", 0f, 1f, 1f, "%"),
}

@Serializable
enum class KeyframeInterpolation(val label: String) {
    LINEAR("Linear"),
    EASE_IN("Ease In"),
    EASE_OUT("Ease Out"),
    EASE_IN_OUT("Ease In-Out"),
    CUBIC_BEZIER("Bezier"),
    HOLD("Hold"),
}

@Serializable
data class BezierCurveSpec(
    val x1: Float = 0.42f,
    val y1: Float = 0.0f,
    val x2: Float = 0.58f,
    val y2: Float = 1.0f,
)

@Serializable
data class PropertyKeyframe(
    val id: String,
    val timeMs: Long,
    val value: Float,
    val interpolation: KeyframeInterpolation = KeyframeInterpolation.EASE_IN_OUT,
    val bezier: BezierCurveSpec = BezierCurveSpec(),
)

@Serializable
data class KeyframeTrack(
    val property: KeyframeProperty,
    val keyframes: List<PropertyKeyframe> = emptyList(),
)

@Serializable
enum class MotionPreset(val label: String) {
    OVERSHOOT("Overshoot"),
    BOUNCE("Bounce"),
    ELASTIC("Elastic"),
    SHAKE("Shake"),
    SMOOTH_ZOOM("Smooth Zoom"),
    CAMERA_PAN("Camera Pan"),
    FADE_IN_OUT("Fade In/Out"),
    SLIDE_IN("Slide In"),
}

@Serializable
enum class AudioEffectPreset(val label: String) {
    NONE("Original"),
    WARM_BASS("Warm Bass"),
    VOCAL_CLEAR("Vocal Clear"),
    PHONE_RADIO("Radio"),
    ECHO_ROOM("Echo Room"),
    STUDIO_PUNCH("Studio Punch"),
}

@Serializable
data class ClipAudioEffect(
    val preset: AudioEffectPreset = AudioEffectPreset.NONE,
    val pan: Float = 0f,         // -1.0 (Left) .. +1.0 (Right)
    val bassDb: Float = 0f,      // -12.0 .. +12.0 dB
    val trebleDb: Float = 0f,    // -12.0 .. +12.0 dB
    val echoMix: Float = 0f,     // 0.0 .. 0.75
) {
    val isDefault: Boolean
        get() = preset == AudioEffectPreset.NONE &&
            pan == 0f &&
            bassDb == 0f &&
            trebleDb == 0f &&
            echoMix == 0f
}

@Serializable
data class TimelineMarker(
    val id: String,
    val timeMs: Long,
    val label: String = "Beat",
    val isBeatMarker: Boolean = true,
)

@Serializable
data class TimelineClipRef(
    val clipId: String,
    val assetId: String,
    val trackId: String,
    val timelineStartMs: Long,
    val sourceStartMs: Long,
    val durationMs: Long,
    val speed: Float = 1.0f,
    val volume: Float = 1.0f,
    val sourceTotalDurationMs: Long = 0L,
    val clipKind: ClipKind = ClipKind.MEDIA,
    val isMuted: Boolean = false,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val transitionIn: ClipTransition? = null,
    val adjustments: ClipAdjustments = ClipAdjustments(),
    val textSpec: TextOverlaySpec? = null,
    val blendMode: BlendModePreset = BlendModePreset.NORMAL,
    val transform: LayerTransform = LayerTransform(),
    val mask: LayerMask = LayerMask(),
    val shaderEffect: ClipShaderEffect = ClipShaderEffect(),
    val keyframeTracks: List<KeyframeTrack> = emptyList(),
    val audioEffect: ClipAudioEffect = ClipAudioEffect(),
    val speedRampPreset: SpeedRampPreset = SpeedRampPreset.NONE,
    val speedRampPoints: List<SpeedPoint> = emptyList(),
    val isReversed: Boolean = false,
    val chromaKey: ChromaKeySpec = ChromaKeySpec(),
    val lutSpec: ClipLutSpec = ClipLutSpec(),
    val stabilization: StabilizationSpec = StabilizationSpec(),
)

@Serializable
data class TimelineTrack(
    val id: String,
    val name: String,
    val type: TrackType,
    val orderIndex: Int,
    val isMuted: Boolean = false,
    val isLocked: Boolean = false,
    val clips: List<TimelineClipRef> = emptyList(),
)

@Serializable
data class ExportSettingsSpec(
    val resolutionHeight: Int = 1080,
    val fps: Int = 30,
    val codec: String = "H.264",
    val audioBitrateKbps: Int = 192,
)

@Serializable
data class ProjectDocument(
    val schemaVersion: Int = CURRENT_PROJECT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val aspectRatio: AspectRatioPreset,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val fps: Int,
    val editorMode: EditorMode,
    val tracks: List<TimelineTrack>,
    val exportSettings: ExportSettingsSpec = ExportSettingsSpec(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val markers: List<TimelineMarker> = emptyList(),
)

data class MediaAsset(
    val id: String,
    val projectId: String?,
    val type: MediaType,
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
    val status: MediaStatus,
    val importedAt: Long,
    val proxyFilePath: String? = null,
) {
    val playbackUri: String
        get() = managedFilePath?.let { "file://$it" } ?: sourceUri

    val hasReadyProxy: Boolean
        get() = !proxyFilePath.isNullOrBlank()

    fun previewUri(preferProxy: Boolean): String =
        if (preferProxy && !proxyFilePath.isNullOrBlank()) {
            "file://$proxyFilePath"
        } else {
            playbackUri
        }

    val resolutionLabel: String
        get() = if (width > 0 && height > 0) "${width}×${height}" else ""
}

data class ProjectSummary(
    val document: ProjectDocument,
    val mediaCount: Int,
    val videoCount: Int,
    val imageCount: Int,
    val audioCount: Int,
    val totalMediaDurationMs: Long,
    val coverMediaUri: String?,
    val isUnsupportedFutureVersion: Boolean = false,
    val detectedSchemaVersion: Int = document.schemaVersion,
    val hasPendingOverlapRepair: Boolean = false,
)
