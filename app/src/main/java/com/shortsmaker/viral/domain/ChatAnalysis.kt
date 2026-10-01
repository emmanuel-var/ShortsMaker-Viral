package com.shortsmaker.viral.domain

import kotlin.math.max

data class ChatMessage(val offsetMs: Long, val text: String)

/** Palabras/emotes que indican "hype" en el chat (risas, asombro, victoria). */
object ChatHype {
    private val TOKENS = setOf(
        "lul", "lulw", "kekw", "kek", "omegalul", "pog", "poggers", "pogchamp", "pepelaugh", "lmao", "lmfao", "lol",
        "xd", "xdd", "f", "gg", "ggs", "w", "wtf", "omg", "clap", "monkas", "pogu", "sadge", "icant", "dead", "rip",
        "草", "笑", "哈哈", "哈哈哈", "555", "ahaha", "ржу", "хах", "ахах", "ахахах", "😂", "🤣", "🔥", "💀", "😭",
    )
    private val LAUGH = Regex("^(?:ja|je|ji|ha|he|hi|ka|ха|ах)+[jhxк]?$|^w{3,}$|^(?:lo)+l$")

    /** Peso de un mensaje: 2 si es de hype, 1 si no. */
    fun weight(text: String): Float {
        val words = text.lowercase().split(Regex("[\\s,.!?¡¿;:()\\[\\]]+")).filter { it.isNotEmpty() }
        return if (words.any { it in TOKENS || LAUGH.matches(it) }) 2f else 1f
    }
}

/**
 * Actividad del chat en el VOD: ráfagas de mensajes (y sobre todo de mensajes de hype) por ventanas de 5 s.
 * Si hay pocos mensajes (< [MIN_MESSAGES]) no se considera fiable y [from] devuelve null.
 */
class ChatActivity private constructor(private val binMs: Long, private val bins: FloatArray, private val reference: Float) {

    /** Puntaje 0..1 de la ráfaga más fuerte (media de las 3 mejores ventanas) dentro del tramo. */
    fun score(startMs: Long, endMs: Long): Float {
        val a = (startMs / binMs).toInt().coerceIn(0, bins.size)
        val b = ((endMs + binMs - 1) / binMs).toInt().coerceIn(a, bins.size)
        if (b <= a) return 0f
        val top = bins.slice(a until b).sortedDescending().take(3)
        return ((top.average().toFloat()) / reference).coerceIn(0f, 1f)
    }

    companion object {
        const val MIN_MESSAGES = 30
        private const val BIN_MS = 5_000L

        fun from(messages: List<ChatMessage>, durationMs: Long): ChatActivity? {
            if (messages.size < MIN_MESSAGES || durationMs <= 0) return null
            val bins = FloatArray((durationMs / BIN_MS).toInt() + 1)
            for (m in messages) {
                val i = (m.offsetMs / BIN_MS).toInt()
                if (i in bins.indices) bins[i] += ChatHype.weight(m.text)
            }
            val sorted = bins.sortedArray()
            val median = sorted[sorted.size / 2]
            val p95 = sorted[((sorted.size - 1) * 0.95f).toInt()]
            val reference = max(p95, median + 3f) // evita dividir por casi cero en chats uniformes
            return ChatActivity(BIN_MS, bins, reference)
        }
    }
}

/**
 * Lee mensajes del chat (repetición del VOD) desde el HTML/DOM ya renderizado. Es "best effort": las webs
 * virtualizan el chat y sólo pintan los mensajes visibles, y los selectores cambian con el tiempo. Si no se
 * encuentra nada devuelve una lista vacía y el análisis ignora el chat en silencio.
 */
object ChatDomParser {
    // Marca de tiempo del mensaje en la repetición (Twitch: data-a-target="chat-timestamp"; Kick: chat-entry-time).
    private val TIMESTAMP = Regex(
        "(?:data-a-target=\"chat-timestamp\"|chat-entry-time|vod-message__timestamp)[^>]*>\\s*(\\d{1,2}(?::\\d{2}){1,2})\\s*<",
    )
    private val TEXT = Regex("(?:data-a-target=\"chat-message-text\"|chat-entry-content)[^>]*>([^<]*)<")
    private val EMOTE_ALT = Regex("<img[^>]+(?:chat-image|chat-line__message--emote|chat-emote)[^>]*alt=\"([^\"]+)\"|<img[^>]+alt=\"([^\"]+)\"[^>]*(?:chat-image|chat-line__message--emote|chat-emote)")

    fun parse(html: String): List<ChatMessage> {
        val stamps = TIMESTAMP.findAll(html).toList()
        if (stamps.isEmpty()) return emptyList()
        val out = ArrayList<ChatMessage>(stamps.size)
        for ((i, m) in stamps.withIndex()) {
            val offset = parseClock(m.groupValues[1]) ?: continue
            val end = if (i + 1 < stamps.size) stamps[i + 1].range.first else html.length
            val chunk = html.substring(m.range.last, end)
            val parts = TEXT.findAll(chunk).map { it.groupValues[1].trim() }.toMutableList()
            EMOTE_ALT.findAll(chunk).forEach { e -> (e.groupValues[1].ifEmpty { e.groupValues[2] }).takeIf { it.isNotBlank() }?.let(parts::add) }
            val text = decode(parts.joinToString(" ")).trim()
            if (text.isNotEmpty()) out += ChatMessage(offset, text)
        }
        return out
    }

    /** "1:02:03" → ms; "12:34" → ms. */
    fun parseClock(clock: String): Long? {
        val p = clock.split(':').map { it.toLongOrNull() ?: return null }
        return when (p.size) {
            2 -> (p[0] * 60 + p[1]) * 1000
            3 -> ((p[0] * 60 + p[1]) * 60 + p[2]) * 1000
            else -> null
        }
    }

    private fun decode(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
}
