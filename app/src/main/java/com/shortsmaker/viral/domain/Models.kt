package com.shortsmaker.viral.domain

import kotlinx.serialization.Serializable

/**
 * Idiomas de la app: sirven tanto para la interfaz como para transcribir el video (modelos Vosk pequeños, Apache 2.0).
 * `spaced = false` para idiomas sin espacios entre palabras (chino, japonés).
 */
enum class Language(val code: String, val label: String, val voskModel: String, val spaced: Boolean = true) {
    ES("es", "Español", "vosk-model-small-es-0.42"),
    EN("en", "English", "vosk-model-small-en-us-0.15"),
    FR("fr", "Français", "vosk-model-small-fr-0.22"),
    DE("de", "Deutsch", "vosk-model-small-de-0.15"),
    PT("pt", "Português", "vosk-model-small-pt-0.3"),
    ZH("zh", "中文", "vosk-model-small-cn-0.22", spaced = false),
    JA("ja", "日本語", "vosk-model-small-ja-0.22", spaced = false),
    RU("ru", "Русский", "vosk-model-small-ru-0.22"),
    HI("hi", "हिन्दी", "vosk-model-small-hi-0.22");

    val modelUrl: String get() = "https://alphacephei.com/vosk/models/$voskModel.zip"

    companion object {
        fun fromCode(code: String?): Language? = entries.firstOrNull { it.code == code }

        /** Idioma del dispositivo si está soportado; si no, inglés. */
        fun fromDevice(deviceCode: String): Language = fromCode(deviceCode) ?: EN
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

@Serializable
data class TimeSpan(val startMs: Long, val endMs: Long) {
    fun covers(startMs: Long, endMs: Long) = this.startMs <= startMs && this.endMs >= endMs

    companion object {
        /** Une los tramos que se solapan o se tocan, ordenados. */
        fun merge(spans: List<TimeSpan>): List<TimeSpan> {
            val out = ArrayList<TimeSpan>()
            for (s in spans.sortedBy { it.startMs }) {
                val last = out.lastOrNull()
                if (last != null && s.startMs <= last.endMs) out[out.lastIndex] = TimeSpan(last.startMs, maxOf(last.endMs, s.endMs)) else out += s
            }
            return out
        }
    }
}

/** Posición normalizada (0..1) del rostro principal en un instante del video. */
@Serializable
data class FacePoint(val timeMs: Long, val x: Float, val y: Float, val size: Float = 0f)

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
    /** Señales multimedia 0..100 (0 = no disponible): audio, movimiento del rostro y chat. */
    val audioScore: Int = 0,
    val motionScore: Int = 0,
    val chatScore: Int = 0,
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
    /** Animación de entrada "pop-in" en cada palabra/bloque. */
    val popIn: Boolean = true,
    /** Pinta de color (rojo/dorado…) las palabras del diccionario local de énfasis (dinero, fuego…). */
    val keywordColors: Boolean = true,
)

object SubtitleTemplates {
    val all: List<SubtitleStyle> = listOf(
        SubtitleStyle(
            templateId = "hormozi", highlightColor = 0xFFFFE600.toInt(), textSize = 72f, mode = SubtitleMode.KARAOKE,
        ),
        SubtitleStyle(
            templateId = "karaoke", font = FontChoice.BOLD, textColor = 0xFFFFFFFF.toInt(),
            highlightColor = 0xFF00E5FF.toInt(), strokeRatio = 0.14f, textSize = 64f,
            uppercase = false, emojis = false, mode = SubtitleMode.KARAOKE,
        ),
        SubtitleStyle(
            templateId = "classic", font = FontChoice.BOLD, textColor = 0xFFFFFFFF.toInt(),
            highlightColor = 0xFFFFFFFF.toInt(), strokeRatio = 0.12f, textSize = 58f,
            uppercase = false, emojis = false, mode = SubtitleMode.STATIC, position = SubtitlePosition.BOTTOM,
            popIn = false, keywordColors = false,
        ),
        SubtitleStyle(
            templateId = "box", font = FontChoice.BOLD, textColor = 0xFFFFFFFF.toInt(),
            highlightColor = 0xFFFFEB3B.toInt(), strokeRatio = 0f, boxColor = 0xCC000000.toInt(),
            textSize = 56f, uppercase = false, emojis = false, mode = SubtitleMode.KARAOKE,
            popIn = false, keywordColors = false,
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
            popIn = false, keywordColors = false,
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
/** Composición del video final 9:16. */
enum class ComposeLayout {
    /** Un solo video recortado a 9:16 (con seguimiento del rostro). */
    FULL,
    /** Arriba el rostro (seguido por el detector de rostros) y abajo el video original completo (gameplay). */
    SPLIT_GAMEPLAY,
    /** Arriba el rostro y abajo un segundo video local (B-roll: "Subway Surfers", satisfying…), en bucle. */
    SPLIT_BROLL,
}

@Serializable
data class ClipEdit(
    val clipId: String,
    val startMs: Long,
    val endMs: Long,
    val style: SubtitleStyle = SubtitleStyle(),
    val framing: Framing = Framing(),
    val layout: ComposeLayout = ComposeLayout.FULL,
    /** Nombre (dentro de la carpeta del proyecto) del video B-roll para `SPLIT_BROLL`. */
    val brollFile: String? = null,
    /** Punch-in: zoom corto de 1-2 s en picos de audio y palabras clave. */
    val autoZoom: Boolean = false,
    /** Barra de progreso fina en el borde superior, quemada en el render. */
    val progressBar: Boolean = false,
    val progressColor: Int = 0xFFFFD60A.toInt(),
    /** Efectos de sonido locales (pop / whoosh) mezclados con el audio. */
    val sfx: Boolean = false,
) {
    /** En el modo dividido los subtítulos van en la costura central, entre el rostro y el video inferior. */
    fun effectiveStyle(): SubtitleStyle =
        if (layout == ComposeLayout.FULL) style else style.copy(position = SubtitlePosition.CENTER)
}

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
    /** Enlace de origen (YouTube/Twitch/Kick) si el usuario lo pegó; sólo informativo, nunca se descarga. */
    val sourceUrl: String? = null,
    val sourcePlatform: String? = null,
    val hasHeatmap: Boolean = false,
    val heatmap: List<HeatPoint> = emptyList(),
    val words: List<WordTiming> = emptyList(),
    val cues: List<SubtitleCue> = emptyList(),
    val clips: List<ClipSuggestion> = emptyList(),
    val edits: Map<String, ClipEdit> = emptyMap(),
    val faceTracks: Map<String, List<FacePoint>> = emptyMap(),
    /** Tramo del video (por clip) cuyo rostro ya se analizó; evita repetir la detección. */
    val faceCoverage: Map<String, TimeSpan> = emptyMap(),
    /** Procesado en modo rápido (video muy largo): sólo se analizaron las ventanas de `transcribedSpans`. */
    val fastMode: Boolean = false,
    /** Tramos cuya transcripción existe en `words`/`cues`. Vacío = todo el video. */
    val transcribedSpans: List<TimeSpan> = emptyList(),
    /** Existe `energy.bin` (Audio Radar) en la carpeta del proyecto. */
    val hasEnergy: Boolean = false,
    val hasChat: Boolean = false,
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
    /** Máximo por clip. 10 min cubre TikTok/Reels/Shorts; el editor parte del video completo recortado a este límite. */
    const val MAX_MS = 600_000L

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

/** Crea el clip del "Modo manual": el video completo (hasta el máximo permitido) para recortarlo en la línea de tiempo. */
object ManualClip {
    fun create(project: Project, nowMs: Long): ClipSuggestion {
        val end = minOf(project.durationMs, ClipRange.MAX_MS)
        val lang = Language.fromCode(project.language) ?: Language.ES
        val preview = project.words.takeWhile { it.startMs < end }.take(if (lang.spaced) 14 else 30)
            .joinToString(if (lang.spaced) " " else "") { it.text }
        return ClipSuggestion(
            id = "manual_$nowMs", title = "", startMs = 0, endMs = end, viralScore = 0, preview = preview, manual = true,
        )
    }
}
