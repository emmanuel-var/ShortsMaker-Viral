package com.shortsmaker.viral.domain

import kotlinx.serialization.Serializable

/** Idiomas soportados para la transcripción local (modelos Vosk pequeños, licencia Apache 2.0). */
enum class Language(val code: String, val label: String, val voskModel: String) {
    ES("es", "Español", "vosk-model-small-es-0.42"),
    EN("en", "English", "vosk-model-small-en-us-0.15"),
    PT("pt", "Português", "vosk-model-small-pt-0.3"),
    FR("fr", "Français", "vosk-model-small-fr-0.22"),
    DE("de", "Deutsch", "vosk-model-small-de-0.15"),
    IT("it", "Italiano", "vosk-model-small-it-0.22");

    val modelUrl: String get() = "https://alphacephei.com/vosk/models/$voskModel.zip"

    companion object {
        fun fromCode(code: String?): Language = entries.firstOrNull { it.code == code } ?: ES
    }
}

enum class ExportResolution(val label: String, val width: Int, val height: Int, val bitrate: Int) {
    P720("720p", 720, 1280, 5_000_000),
    P1080("1080p", 1080, 1920, 9_000_000);

    companion object {
        fun fromName(name: String?): ExportResolution = entries.firstOrNull { it.name == name } ?: P1080
    }
}

@Serializable
data class WordTiming(val text: String, val startMs: Long, val endMs: Long)

@Serializable
data class SubtitleCue(val startMs: Long, val endMs: Long, val words: List<WordTiming>) {
    val text: String get() = words.joinToString(" ") { it.text }

    /**
     * Devuelve una copia con el texto corregido por el usuario. Si el número de palabras no cambia se conserva
     * el tiempo de cada palabra; si cambia, se reparte uniformemente dentro del rango del bloque.
     */
    fun withText(newText: String): SubtitleCue {
        val tokens = newText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return copy(words = emptyList())
        if (tokens.size == words.size) {
            return copy(words = words.mapIndexed { i, w -> w.copy(text = tokens[i]) })
        }
        val total = (endMs - startMs).coerceAtLeast(tokens.size.toLong())
        val step = total / tokens.size
        return copy(
            words = tokens.mapIndexed { i, t ->
                val s = startMs + step * i
                val e = if (i == tokens.lastIndex) endMs else s + step
                WordTiming(t, s, e)
            },
        )
    }
}

/** Punto del "heatmap" de YouTube (momentos más repetidos). `value` ∈ [0,1]. */
@Serializable
data class HeatPoint(val startMs: Long, val endMs: Long, val value: Float)

/** Posición normalizada (0..1) del rostro principal en un instante del video. */
@Serializable
data class FacePoint(val timeMs: Long, val x: Float, val y: Float)

@Serializable
data class ClipSuggestion(
    val id: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val viralScore: Int,
    val heatScore: Int = 0,
    val preview: String = "",
    val keywords: List<String> = emptyList(),
    val manual: Boolean = false,
) {
    val durationMs: Long get() = endMs - startMs
}

enum class SubtitleMode { STATIC, KARAOKE, WORD_BY_WORD }

enum class SubtitlePosition(val centerYFraction: Float) {
    TOP(0.20f),
    CENTER(0.50f),
    BOTTOM(0.70f),
}

enum class FontChoice(val family: String, val bold: Boolean, val label: String) {
    IMPACT("sans-serif-black", false, "Impact"),
    BOLD("sans-serif", true, "Bold"),
    CONDENSED("sans-serif-condensed", true, "Condensed"),
    SERIF("serif", true, "Serif"),
    MONO("monospace", true, "Mono"),
    HAND("cursive", true, "Hand"),
}

@Serializable
data class SubtitleStyle(
    val templateId: String = "hormozi",
    val font: FontChoice = FontChoice.IMPACT,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val highlightColor: Int = 0xFFFFD60A.toInt(),
    val strokeColor: Int = 0xFF000000.toInt(),
    /** Grosor del contorno relativo al tamaño del texto (0..0.3). */
    val strokeRatio: Float = 0.16f,
    /** Color de la caja de fondo (ARGB). 0 = sin caja. */
    val boxColor: Int = 0,
    /** Tamaño del texto en píxeles sobre un fotograma de 1080 px de ancho. */
    val textSize: Float = 76f,
    val uppercase: Boolean = true,
    val emojis: Boolean = true,
    val mode: SubtitleMode = SubtitleMode.WORD_BY_WORD,
    val position: SubtitlePosition = SubtitlePosition.BOTTOM,
)

object SubtitleTemplates {
    val all: List<SubtitleStyle> = listOf(
        SubtitleStyle(templateId = "hormozi"),
        SubtitleStyle(
            templateId = "karaoke", font = FontChoice.BOLD, textColor = 0xFFFFFFFF.toInt(),
            highlightColor = 0xFF00E5FF.toInt(), strokeRatio = 0.14f, textSize = 64f,
            uppercase = false, emojis = false, mode = SubtitleMode.KARAOKE,
        ),
        SubtitleStyle(
            templateId = "classic", font = FontChoice.BOLD, textColor = 0xFFFFFFFF.toInt(),
            highlightColor = 0xFFFFFFFF.toInt(), strokeRatio = 0.12f, textSize = 58f,
            uppercase = false, emojis = false, mode = SubtitleMode.STATIC, position = SubtitlePosition.BOTTOM,
        ),
        SubtitleStyle(
            templateId = "box", font = FontChoice.BOLD, textColor = 0xFFFFFFFF.toInt(),
            highlightColor = 0xFFFFEB3B.toInt(), strokeRatio = 0f, boxColor = 0xCC000000.toInt(),
            textSize = 56f, uppercase = false, emojis = false, mode = SubtitleMode.KARAOKE,
        ),
        SubtitleStyle(
            templateId = "neon", font = FontChoice.CONDENSED, textColor = 0xFFFF2D95.toInt(),
            highlightColor = 0xFF39FF14.toInt(), strokeColor = 0xFF1A0033.toInt(), strokeRatio = 0.2f,
            textSize = 80f, uppercase = true, emojis = true, mode = SubtitleMode.WORD_BY_WORD,
            position = SubtitlePosition.CENTER,
        ),
        SubtitleStyle(
            templateId = "minimal", font = FontChoice.SERIF, textColor = 0xFFFFFFFF.toInt(),
            highlightColor = 0xFFFFC107.toInt(), strokeRatio = 0.08f, textSize = 52f,
            uppercase = false, emojis = false, mode = SubtitleMode.STATIC, position = SubtitlePosition.BOTTOM,
        ),
    )

    fun byId(id: String): SubtitleStyle = all.firstOrNull { it.templateId == id } ?: all.first()
}

/** Encuadre vertical 9:16. Valores manuales en [-1,1] relativos al recorrido disponible. */
@Serializable
data class Framing(
    val autoTrack: Boolean = true,
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

/** Estado de edición de un clip (se guarda en el proyecto para poder re-editar). */
@Serializable
data class ClipEdit(
    val clipId: String,
    val startMs: Long,
    val endMs: Long,
    val style: SubtitleStyle = SubtitleStyle(),
    val framing: Framing = Framing(),
)

@Serializable
data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceFile: String,
    val thumbnailFile: String? = null,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val language: String,
    val youtubeUrl: String? = null,
    val hasHeatmap: Boolean = false,
    val heatmap: List<HeatPoint> = emptyList(),
    val words: List<WordTiming> = emptyList(),
    val cues: List<SubtitleCue> = emptyList(),
    val clips: List<ClipSuggestion> = emptyList(),
    val edits: Map<String, ClipEdit> = emptyMap(),
    val faceTracks: Map<String, List<FacePoint>> = emptyMap(),
) {
    fun toSummary() = ProjectSummary(
        id = id, name = name, createdAt = createdAt, updatedAt = updatedAt,
        thumbnailFile = thumbnailFile, durationMs = durationMs, clipCount = clips.size,
    )
}

/** Resumen ligero para la cuadrícula del historial (evita cargar toda la transcripción). */
@Serializable
data class ProjectSummary(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val thumbnailFile: String? = null,
    val durationMs: Long,
    val clipCount: Int,
)

/** Reglas para el recorte del clip en la línea de tiempo. */
object ClipRange {
    const val MIN_MS = 2_000L
    const val MAX_MS = 180_000L

    /** Ajusta (start, end) respetando límites del video y duración mínima/máxima. `movedStart` = qué extremo arrastró el usuario. */
    fun clamp(start: Long, end: Long, movedStart: Boolean, durationMs: Long): Pair<Long, Long> {
        var s = start.coerceIn(0, durationMs)
        var e = end.coerceIn(0, durationMs)
        val minLen = minOf(MIN_MS, durationMs)
        if (e - s < minLen) { if (movedStart) s = (e - minLen).coerceAtLeast(0) else e = (s + minLen).coerceAtMost(durationMs) }
        if (e - s > MAX_MS) { if (movedStart) s = e - MAX_MS else e = s + MAX_MS }
        return s to e
    }
}
