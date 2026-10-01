package com.shortsmaker.viral.domain

/** Genera títulos sugeridos con el conteo de palabras más repetidas (lógica simple, sin LLM). */
object TitleGenerator {

    private val TEMPLATES: Map<Language, List<String>> = mapOf(
        Language.ES to listOf("Todo sobre %s", "El secreto de %s", "%s: lo que nadie te cuenta", "Por qué %s lo cambia todo"),
        Language.EN to listOf("The truth about %s", "Everything about %s", "%s: what nobody tells you", "Why %s changes everything"),
        Language.PT to listOf("Tudo sobre %s", "O segredo de %s", "%s: o que ninguém conta", "Por que %s muda tudo"),
        Language.FR to listOf("Tout sur %s", "Le secret de %s", "%s : ce que personne ne dit", "Pourquoi %s change tout"),
        Language.DE to listOf("Alles über %s", "Das Geheimnis von %s", "%s: was dir keiner sagt", "Warum %s alles ändert"),
        Language.RU to listOf("Всё о теме: %s", "Секрет: %s", "%s: о чём молчат", "Почему %s меняет всё"),
        Language.ZH to listOf("关于%s的一切", "%s的秘密", "%s：没人告诉你的事", "为什么%s改变一切"),
        Language.JA to listOf("%sのすべて", "%sの秘密", "%s：誰も教えてくれないこと", "なぜ%sがすべてを変えるのか"),
        Language.HI to listOf("%s के बारे में सब कुछ", "%s का राज़", "%s: जो कोई नहीं बताता", "%s सब कुछ क्यों बदल देता है"),
    )
    private val AND = mapOf(
        Language.ES to "y", Language.EN to "and", Language.PT to "e",
        Language.FR to "et", Language.DE to "und", Language.RU to "и",
        Language.ZH to "和", Language.JA to "と", Language.HI to "और",
    )

    fun generate(clipWords: List<String>, keywords: List<String>, lang: Language, seed: Int, fallbackIndex: Int): String {
        if (keywords.isEmpty()) {
            val head = clipWords.take(6).joinToString(if (lang.spaced) " " else "").trim()
            return if (head.isNotEmpty()) TextAnalysis.capitalize(head) + "…" else "Clip $fallbackIndex"
        }
        val kws = keywords.take(2).map(TextAnalysis::capitalize)
        val sep = if (lang.spaced) " " else ""
        val joined = if (kws.size == 2) "${kws[0]}$sep${AND.getValue(lang)}$sep${kws[1]}" else kws[0]
        val templates = TEMPLATES.getValue(lang)
        return templates[Math.floorMod(seed, templates.size)].format(joined)
    }
}
