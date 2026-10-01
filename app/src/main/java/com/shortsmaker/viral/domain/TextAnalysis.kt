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
    private val STOP_RU = setOf(
        "этот", "эта", "это", "эти", "того", "этом", "который", "которая", "которые", "когда", "потому", "почему",
        "очень", "тоже", "также", "если", "чтобы", "только", "после", "перед", "через", "между", "всего", "всех",
        "есть", "быть", "было", "была", "были", "будет", "могу", "может", "можно", "надо", "нужно", "тогда", "сейчас",
        "здесь", "там", "вот", "как", "что", "все", "его", "она", "они", "мне", "тебя", "нас", "вас", "для", "при",
        "свой", "своё", "свои", "ещё", "уже", "просто", "такой", "такая", "такие", "типа", "короче", "значит",
    )
    private val STOP_ZH = setOf(
        "这个", "那个", "这些", "那些", "我们", "你们", "他们", "她们", "自己", "什么", "怎么", "因为", "所以", "但是",
        "如果", "就是", "还是", "已经", "可以", "没有", "一个", "一些", "不是", "然后", "现在", "这样", "那样", "时候",
        "知道", "觉得", "可能", "应该", "非常", "比较", "其实", "真的", "东西", "这里", "那里", "一下",
    )
    private val STOP_JA = setOf(
        "これ", "それ", "あれ", "この", "その", "あの", "ここ", "そこ", "あそこ", "ます", "です", "ある", "いる", "する",
        "なる", "ない", "こと", "もの", "ため", "よう", "という", "から", "ので", "けど", "でも", "しかし", "そして",
        "まあ", "ちょっと", "とても", "やはり", "ですが", "でした", "ました", "ません",
    )
    private val STOP_HI = setOf(
        "यह", "वह", "ये", "वे", "हैं", "था", "थी", "थे", "होता", "होती", "होते", "करना", "करते", "करती", "किया", "लिए",
        "साथ", "बहुत", "लेकिन", "क्योंकि", "अगर", "तो", "भी", "ही", "कि", "में", "से", "पर", "को", "का", "की", "के",
        "और", "या", "नहीं", "कुछ", "सब", "अपने", "अपना", "जब", "तब", "यहाँ", "वहाँ", "अब", "फिर", "बस", "हम", "आप",
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
    private val HOOK_RU = setOf(
        "секрет", "секреты", "никогда", "ошибка", "ошибки", "невероятно", "правда", "деньги", "бесплатно", "лучший",
        "худший", "страх", "успех", "лайфхак", "никто", "важно", "осторожно", "внимание", "узнай", "смотри",
        "представь", "история", "миллион", "миллионы", "ключ", "легко", "быстро", "шок", "опасно", "выиграть", "жизнь",
    )
    private val HOOK_ZH = setOf(
        "秘密", "永远", "从不", "错误", "不可思议", "真相", "金钱", "免费", "最好", "最差", "恐惧", "成功", "技巧",
        "没有人", "重要", "小心", "注意", "发现", "想象", "故事", "百万", "关键", "简单", "快速", "震惊", "危险", "改变", "人生",
    )
    private val HOOK_JA = setOf(
        "秘密", "絶対", "決して", "失敗", "驚き", "真実", "お金", "無料", "最高", "最悪", "恐怖", "成功", "コツ",
        "誰も", "重要", "注意", "発見", "想像", "物語", "百万", "鍵", "簡単", "速い", "衝撃", "危険", "人生", "変わる",
    )
    private val HOOK_HI = setOf(
        "राज", "राज़", "रहस्य", "कभी", "गलती", "गलतियाँ", "अविश्वसनीय", "सच", "पैसा", "पैसे", "मुफ्त", "सबसे", "बेहतरीन",
        "डर", "सफलता", "तरीका", "कोई", "ज़रूरी", "जरूरी", "सावधान", "ध्यान", "जानिए", "देखो", "कल्पना", "कहानी",
        "करोड़", "लाख", "आसान", "तेज़", "खतरा", "जीतो", "ज़िंदगी", "जिंदगी",
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
        Language.RU -> STOP_RU
        Language.ZH -> STOP_ZH
        Language.JA -> STOP_JA
        Language.HI -> STOP_HI
    }

    private fun hooks(lang: Language): Set<String> = when (lang) {
        Language.EN -> HOOK_EN
        Language.RU -> HOOK_RU
        Language.ZH -> HOOK_ZH
        Language.JA -> HOOK_JA
        Language.HI -> HOOK_HI
        Language.ES, Language.PT, Language.FR, Language.DE -> HOOK_ES + HOOK_EN
    }

    /** Limpia puntuación y pasa a minúsculas, conservando letras (también CJK/devanagari con sus signos) y dígitos. */
    fun normalize(word: String): String =
        word.lowercase(Locale.ROOT).filter {
            it.isLetterOrDigit() || it == '\'' ||
                Character.getType(it).let { t ->
                    t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()
                }
        }

    /** Longitud mínima de una palabra clave: en chino/japonés las palabras son cortas. */
    private fun minKeywordLength(lang: Language) = when (lang) {
        Language.ZH, Language.JA -> 2
        Language.HI -> 3
        else -> 4
    }

    fun stripAccents(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** Palabras más repetidas (≥4 letras, sin palabras vacías), ordenadas por frecuencia y primera aparición. */
    fun topKeywords(words: List<String>, lang: Language, limit: Int = 3): List<String> {
        val stop = stopWords(lang) + stopWords(Language.ES).takeIf { lang == Language.PT }.orEmpty()
        val minLen = minKeywordLength(lang)
        val counts = LinkedHashMap<String, Int>()
        for (raw in words) {
            val w = normalize(raw)
            if (w.length < minLen || w in stop || w.all { it.isDigit() }) continue
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
            n in hooks || n in EMOTION || n.any(Char::isDigit) || n == "mil" || n == "cien" || n == "millones" || n == "миллион" || n == "百万" || n == "万"
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
