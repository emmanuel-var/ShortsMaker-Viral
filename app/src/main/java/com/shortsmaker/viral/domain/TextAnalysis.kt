package com.shortsmaker.viral.domain

import java.text.Normalizer
import java.util.Locale

/** Análisis de texto básico (sin IA de lenguaje): palabras clave, ganchos y emojis. */
object TextAnalysis {

    private val STOP_ES = setOf(
        "para", "pero", "como", "cómo", "esto", "esta", "este", "estos", "estas", "eso", "esa", "ese", "esos", "esas",
        "porque", "cuando", "donde", "dónde", "entonces", "también", "tambien", "muy", "más", "mas", "menos", "sobre",
        "entre", "desde", "hasta", "hacia", "todo", "toda", "todos", "todas", "tiene", "tienen", "tengo", "tener",
        "hace", "hacer", "hay", "ser", "son", "está", "están", "estoy", "estamos", "fue", "era", "eran", "van", "voy",
        "vamos", "puede", "pueden", "puedo", "dice", "digo", "dijo", "pues", "así", "asi", "aquí", "aqui", "allí",
        "ahora", "bien", "solo", "sólo", "algo", "alguien", "otro", "otra", "otros", "otras", "mismo", "misma", "cada",
        "qué", "que", "quien", "quién", "cual", "cuál", "nos", "les", "sus", "mis", "tus", "una", "unos", "unas",
        "del", "los", "las", "con", "sin", "por", "ver", "cosa", "cosas", "vez", "veces", "luego", "después", "antes",
        "siempre", "nunca", "mucho", "mucha", "muchos", "muchas", "poco", "poca", "creo", "sea", "sean", "tipo",
    )
    private val STOP_EN = setOf(
        "this", "that", "these", "those", "with", "from", "have", "has", "had", "been", "being", "were", "was", "are",
        "for", "and", "but", "not", "you", "your", "yours", "they", "them", "their", "there", "here", "then", "than",
        "what", "when", "where", "which", "who", "whom", "why", "how", "will", "would", "could", "should", "just",
        "like", "really", "very", "much", "more", "most", "some", "any", "all", "can", "cant", "dont", "its", "it's",
        "about", "into", "over", "also", "because", "going", "gonna", "know", "think", "thing", "things", "get", "got",
        "out", "our", "ours", "his", "her", "she", "him", "yeah", "okay", "right", "well", "now", "one", "two", "way",
        "say", "said", "see", "make", "made", "want", "need", "come", "back", "even", "still", "every", "other",
    )
    private val STOP_PT = setOf(
        "para", "mas", "como", "isso", "essa", "esse", "esta", "este", "porque", "quando", "onde", "então", "também",
        "muito", "mais", "menos", "sobre", "entre", "desde", "todo", "toda", "todos", "tem", "têm", "tenho", "fazer",
        "tinha", "você", "voce", "vocês", "eles", "elas", "aqui", "agora", "bem", "algo", "outro", "outra", "cada",
        "uma", "uns", "umas", "dos", "das", "com", "sem", "por", "coisa", "coisas", "vez", "então", "pois", "ser",
    )
    private val STOP_FR = setOf(
        "pour", "mais", "comme", "cette", "celui", "ceux", "parce", "quand", "alors", "aussi", "très", "plus", "moins",
        "sur", "entre", "depuis", "tout", "toute", "tous", "toutes", "avoir", "être", "faire", "dans", "avec", "sans",
        "chose", "choses", "fois", "vous", "nous", "ils", "elles", "leur", "leurs", "voilà", "donc", "puis", "bien",
    )
    private val STOP_DE = setOf(
        "und", "aber", "wie", "dass", "dies", "diese", "dieser", "weil", "wenn", "dann", "auch", "sehr", "mehr",
        "weniger", "über", "zwischen", "seit", "alle", "haben", "sein", "machen", "nicht", "mit", "ohne", "sich",
        "eine", "einen", "einem", "einer", "noch", "schon", "nur", "oder", "wir", "ihr", "sie", "das", "der", "die",
    )
    private val STOP_IT = setOf(
        "per", "ma", "come", "questo", "questa", "questi", "queste", "perché", "perche", "quando", "dove", "allora",
        "anche", "molto", "più", "meno", "sopra", "tra", "fra", "tutto", "tutta", "tutti", "tutte", "avere", "essere",
        "fare", "con", "senza", "cosa", "cose", "volta", "volte", "noi", "voi", "loro", "dunque", "poi", "bene",
    )

    private val HOOK_ES = setOf(
        "secreto", "secretos", "nunca", "jamás", "error", "errores", "increíble", "increible", "verdad", "dinero",
        "gratis", "mejor", "peor", "miedo", "éxito", "exito", "truco", "trucos", "nadie", "importante", "cuidado",
        "atención", "atencion", "descubre", "mira", "imagina", "historia", "millones", "millón", "clave", "fácil",
        "facil", "rápido", "rapido", "impresionante", "locura", "brutal", "peligro", "ganar", "perder", "cambió",
        "cambio", "vida", "poderoso", "prohibido", "urgente", "ojo", "escucha", "dato", "razón", "razon",
    )
    private val HOOK_EN = setOf(
        "secret", "secrets", "never", "mistake", "mistakes", "amazing", "truth", "money", "free", "best", "worst",
        "fear", "success", "trick", "tricks", "nobody", "important", "careful", "attention", "discover", "look",
        "imagine", "story", "million", "millions", "key", "easy", "fast", "insane", "crazy", "shocking", "biggest",
        "danger", "win", "lose", "changed", "life", "powerful", "banned", "urgent", "listen", "fact", "reason",
    )
    private val QUESTION_STARTERS = setOf(
        "cómo", "como", "qué", "que", "por", "cuál", "cual", "quién", "quien", "cuándo", "cuando", "dónde", "donde",
        "why", "what", "how", "who", "when", "where", "which", "pourquoi", "comment", "warum", "wie", "perché",
        "porquê", "por que",
    )
    private val EMOTION = setOf(
        "amor", "odio", "miedo", "feliz", "triste", "llorar", "reír", "risa", "enojo", "rabia", "sorpresa",
        "love", "hate", "fear", "happy", "sad", "cry", "laugh", "angry", "surprise", "wow", "dios", "god",
    )

    private val EMOJIS = mapOf(
        "dinero" to "💰", "money" to "💰", "plata" to "💰", "fuego" to "🔥", "fire" to "🔥", "amor" to "❤️",
        "love" to "❤️", "increíble" to "🤯", "increible" to "🤯", "amazing" to "🤯", "éxito" to "🏆", "exito" to "🏆",
        "success" to "🏆", "idea" to "💡", "tiempo" to "⏰", "time" to "⏰", "feliz" to "😊", "happy" to "😊",
        "miedo" to "😱", "fear" to "😱", "gratis" to "🎁", "free" to "🎁", "importante" to "⚠️", "important" to "⚠️",
        "secreto" to "🤫", "secret" to "🤫", "mira" to "👀", "look" to "👀", "rápido" to "⚡", "rapido" to "⚡",
        "fast" to "⚡", "mundo" to "🌍", "world" to "🌍", "trabajo" to "💼", "work" to "💼", "mejor" to "⭐",
        "best" to "⭐", "error" to "❌", "mistake" to "❌", "verdad" to "✅", "truth" to "✅", "crecer" to "📈",
        "grow" to "📈", "pensar" to "🤔", "think" to "🤔", "millones" to "🤑", "million" to "🤑", "peligro" to "🚨",
        "danger" to "🚨", "ganar" to "🏅", "win" to "🏅", "risa" to "😂", "laugh" to "😂", "triste" to "😢",
        "sad" to "😢", "casa" to "🏠", "home" to "🏠", "música" to "🎵", "music" to "🎵",
    )

    fun stopWords(lang: Language): Set<String> = when (lang) {
        Language.ES -> STOP_ES
        Language.EN -> STOP_EN
        Language.PT -> STOP_PT
        Language.FR -> STOP_FR
        Language.DE -> STOP_DE
        Language.IT -> STOP_IT
    }

    private fun hooks(lang: Language): Set<String> = when (lang) {
        Language.EN -> HOOK_EN
        Language.ES, Language.PT, Language.IT, Language.FR, Language.DE -> HOOK_ES + HOOK_EN
    }

    /** Limpia puntuación y pasa a minúsculas, conservando letras acentuadas y dígitos. */
    fun normalize(word: String): String =
        word.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '\'' }

    fun stripAccents(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** Palabras más repetidas (≥4 letras, sin palabras vacías), ordenadas por frecuencia y primera aparición. */
    fun topKeywords(words: List<String>, lang: Language, limit: Int = 3): List<String> {
        val stop = stopWords(lang) + stopWords(Language.ES).takeIf { lang == Language.PT || lang == Language.IT }.orEmpty()
        val counts = LinkedHashMap<String, Int>()
        for (raw in words) {
            val w = normalize(raw)
            if (w.length < 4 || w in stop || w.all { it.isDigit() }) continue
            counts[w] = (counts[w] ?: 0) + 1
        }
        return counts.entries
            .withIndex()
            .sortedWith(compareByDescending<IndexedValue<Map.Entry<String, Int>>> { it.value.value }.thenBy { it.index })
            .map { it.value.key }
            .take(limit)
    }

    /** Número de palabras "gancho" / emocionales en la lista. */
    fun hookHits(words: List<String>, lang: Language): Int {
        val hooks = hooks(lang)
        return words.count {
            val n = normalize(it)
            n in hooks || n in EMOTION || n.any(Char::isDigit) || n == "mil" || n == "cien" || n == "millones"
        }
    }

    /** ¿La ventana empieza con pregunta o con un gancho fuerte en sus primeras palabras? */
    fun startsWithHook(words: List<String>, lang: Language): Boolean {
        val head = words.take(6).map(::normalize)
        if (head.isEmpty()) return false
        if (head.first() in QUESTION_STARTERS) return true
        val hooks = hooks(lang)
        return head.any { it in hooks }
    }

    fun emojiFor(word: String): String? = EMOJIS[normalize(word)]

    fun capitalize(s: String): String =
        if (s.isEmpty()) s else s.substring(0, 1).uppercase(Locale.ROOT) + s.substring(1)
}
