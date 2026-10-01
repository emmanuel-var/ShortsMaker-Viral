package com.shortsmaker.viral.domain

/** Genera títulos sugeridos con el conteo de palabras más repetidas (lógica simple, sin LLM). */
object TitleGenerator {

    private val TEMPLATES: Map<Language, List<String>> = mapOf(
        Language.ES to listOf("Todo sobre %s", "El secreto de %s", "%s: lo que nadie te cuenta", "Por qué %s lo cambia todo"),
        Language.EN to listOf("The truth about %s", "Everything about %s", "%s: what nobody tells you", "Why %s changes everything"),
        Language.PT to listOf("Tudo sobre %s", "O segredo de %s", "%s: o que ninguém conta", "Por que %s muda tudo"),
        Language.FR to listOf("Tout sur %s", "Le secret de %s", "%s : ce que personne ne dit", "Pourquoi %s change tout"),
        Language.DE to listOf("Alles über %s", "Das Geheimnis von %s", "%s: was dir keiner sagt", "Warum %s alles ändert"),
        Language.IT to listOf("Tutto su %s", "Il segreto di %s", "%s: quello che nessuno dice", "Perché %s cambia tutto"),
    )
    private val AND = mapOf(
        Language.ES to "y", Language.EN to "and", Language.PT to "e",
        Language.FR to "et", Language.DE to "und", Language.IT to "e",
    )

    fun generate(clipWords: List<String>, keywords: List<String>, lang: Language, seed: Int, fallbackIndex: Int): String {
        if (keywords.isEmpty()) {
            val head = clipWords.take(6).joinToString(" ").trim()
            return if (head.isNotEmpty()) TextAnalysis.capitalize(head) + "…" else "Clip $fallbackIndex"
        }
        val kws = keywords.take(2).map(TextAnalysis::capitalize)
        val joined = if (kws.size == 2) "${kws[0]} ${AND.getValue(lang)} ${kws[1]}" else kws[0]
        val templates = TEMPLATES.getValue(lang)
        return templates[Math.floorMod(seed, templates.size)].format(joined)
    }
}
