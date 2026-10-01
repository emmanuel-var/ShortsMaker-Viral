package com.shortsmaker.viral.domain

/** Agrupa las palabras transcritas en bloques de subtítulo cortos, legibles en pantalla vertical. */
object CueBuilder {
    private const val MAX_WORDS = 4
    private const val MAX_CHARS = 26
    private const val MAX_CHARS_WIDE = 12 // chino/japonés: cada carácter ocupa ~el doble
    private const val MAX_GAP_MS = 650L
    private const val MAX_CUE_MS = 2800L
    private const val TAIL_PADDING_MS = 180L

    fun build(words: List<WordTiming>, wide: Boolean = false): List<SubtitleCue> {
        val maxChars = if (wide) MAX_CHARS_WIDE else MAX_CHARS
        if (words.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<WordTiming>>()
        var current = mutableListOf<WordTiming>()
        for (w in words) {
            if (current.isNotEmpty()) {
                val last = current.last()
                val chars = current.sumOf { it.text.length + 1 } + w.text.length
                val gap = w.startMs - last.endMs
                val tooLong = w.endMs - current.first().startMs > MAX_CUE_MS
                val endsSentence = last.text.endsWith(".") || last.text.endsWith("?") || last.text.endsWith("!")
                if (gap >= MAX_GAP_MS || chars > maxChars || current.size >= MAX_WORDS || tooLong || endsSentence) {
                    groups += current
                    current = mutableListOf()
                }
            }
            current += w
        }
        if (current.isNotEmpty()) groups += current

        return groups.mapIndexed { i, g ->
            val start = g.first().startMs
            val nextStart = groups.getOrNull(i + 1)?.first()?.startMs ?: Long.MAX_VALUE
            val end = minOf(g.last().endMs + TAIL_PADDING_MS, nextStart).coerceAtLeast(g.last().endMs)
            SubtitleCue(start, end, g)
        }
    }
}
