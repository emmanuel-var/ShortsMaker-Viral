package com.shortsmaker.viral.domain

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Energía de audio por trozos de `hopMs` ms. Cada trozo guarda su nivel RMS en dB (−100..0 dBFS) cuantizado a
 * 1 byte (0.4 dB de resolución): 4 horas a 100 ms = 144 000 bytes, así que cabe en memoria y en disco.
 */
class AudioEnergyTrack(val hopMs: Int, private val levels: ByteArray) {
    val size: Int get() = levels.size
    val durationMs: Long get() = levels.size.toLong() * hopMs

    fun db(i: Int): Float = (levels[i].toInt() and 0xFF) / 2.5f - 100f

    fun indexAt(timeMs: Long): Int = (timeMs / hopMs).toInt().coerceIn(0, max(0, levels.size - 1))

    fun toBytes(): ByteArray = levels

    companion object {
        fun encodeDb(db: Float): Byte = ((db.coerceIn(-100f, 0f) + 100f) * 2.5f).roundToInt().toByte()
        fun fromDb(hopMs: Int, db: FloatArray) = AudioEnergyTrack(hopMs, ByteArray(db.size) { encodeDb(db[it]) })
    }
}

/**
 * Acumula muestras PCM de 16 bits **mono** (en streaming, sin guardar el audio) y produce el [AudioEnergyTrack].
 * No necesita remuestrear: sólo importa la frecuencia de muestreo para saber cuántas muestras hay en cada trozo.
 */
class AudioEnergyAccumulator(sampleRate: Int, private val hopMs: Int = DEFAULT_HOP_MS) {
    private val hopSamples = max(1, (sampleRate.toLong() * hopMs / 1000).toInt())
    private var sumSquares = 0.0
    private var count = 0
    private var out = ByteArray(1024)
    private var frames = 0

    fun addSamples(pcm: ShortArray, length: Int = pcm.size) {
        for (i in 0 until length) {
            val s = pcm[i].toDouble()
            sumSquares += s * s
            if (++count == hopSamples) flush()
        }
    }

    /** PCM16 little-endian mono. */
    fun addPcm16(bytes: ByteArray, length: Int = bytes.size) {
        var i = 0
        while (i + 1 < length) {
            val s = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort().toDouble()
            sumSquares += s * s
            if (++count == hopSamples) flush()
            i += 2
        }
    }

    private fun flush() {
        val rms = sqrt(sumSquares / count) / 32768.0
        val db = if (rms <= 1e-5) -100f else (20 * log10(rms)).toFloat()
        if (frames == out.size) out = out.copyOf(out.size * 2)
        out[frames++] = AudioEnergyTrack.encodeDb(db)
        sumSquares = 0.0
        count = 0
    }

    fun finish(): AudioEnergyTrack {
        if (count >= hopSamples / 2) flush()
        return AudioEnergyTrack(hopMs, out.copyOf(frames))
    }

    companion object {
        const val DEFAULT_HOP_MS = 100
    }
}

data class AudioPeak(val timeMs: Long, val db: Float)

/**
 * "Audio Radar": detecta en la energía del audio
 *  - **picos** (gritos, risas, golpes): tramos claramente por encima de la mediana y del percentil 95 del propio video;
 *  - **contrastes** "silencio seguido de ruido fuerte" (tensión → explosión).
 * Todo es relativo al video, así que funciona igual con una voz suave que con un streamer a gritos.
 */
class AudioProfile(val track: AudioEnergyTrack) {
    val floorDb: Float
    val medianDb: Float
    val peakDb: Float
    val topDb: Float
    private val peak: BooleanArray
    private val contrast: BooleanArray
    private val peakPrefix: IntArray
    private val contrastPrefix: IntArray

    init {
        val n = track.size
        val sorted = FloatArray(n) { track.db(it) }.also { it.sort() }
        fun pct(p: Float) = if (n == 0) -100f else sorted[((n - 1) * p).toInt()]
        floorDb = pct(0.10f)
        medianDb = pct(0.50f)
        peakDb = pct(0.95f)
        topDb = pct(0.99f)

        // Suavizado de 300 ms para no confundir un chasquido de 1 trozo con un grito.
        val smooth = FloatArray(n) { i ->
            var s = 0f; var c = 0
            for (k in i - 1..i + 1) if (k in 0 until n) { s += track.db(k); c++ }
            s / max(1, c)
        }
        val loudThreshold = max(peakDb, medianDb + PEAK_ABOVE_MEDIAN_DB)
        peak = BooleanArray(n) { smooth[it] >= loudThreshold }

        // Contraste: trozos silenciosos (≤ suelo+6 dB) durante ≥ 0.6 s y luego un salto ≥ 18 dB hacia un nivel fuerte.
        contrast = BooleanArray(n)
        // "Silencio" = claramente por debajo de la voz normal (mediana) además de cerca del suelo del video.
        val quietLimit = min(floorDb + 6f, medianDb - QUIET_BELOW_MEDIAN_DB)
        val quietFrames = max(3, 600 / track.hopMs)
        var lastEvent = -1_000_000
        val debounce = 2_000 / track.hopMs
        for (i in quietFrames until n) {
            if (i - lastEvent < debounce) continue
            var quiet = true
            var sum = 0f
            for (k in i - quietFrames until i) { sum += track.db(k); if (track.db(k) > quietLimit) { quiet = false; break } }
            if (!quiet) continue
            val loud = smooth[min(n - 1, i + 1)]
            if (loud >= medianDb + 10f && loud - sum / quietFrames >= CONTRAST_JUMP_DB) {
                contrast[i] = true
                lastEvent = i
            }
        }
        peakPrefix = IntArray(n + 1).also { for (i in 0 until n) it[i + 1] = it[i] + if (peak[i]) 1 else 0 }
        contrastPrefix = IntArray(n + 1).also { for (i in 0 until n) it[i + 1] = it[i] + if (contrast[i]) 1 else 0 }
    }

    /** Número de eventos "silencio → explosión" dentro del tramo. */
    fun contrastEvents(startMs: Long, endMs: Long): Int {
        val (a, b) = range(startMs, endMs)
        return contrastPrefix[b] - contrastPrefix[a]
    }

    /** Fracción de trozos "pico" dentro del tramo. */
    fun peakFraction(startMs: Long, endMs: Long): Float {
        val (a, b) = range(startMs, endMs)
        return if (b <= a) 0f else (peakPrefix[b] - peakPrefix[a]).toFloat() / (b - a)
    }

    /** Puntaje de audio 0..1 del tramo: densidad de picos (40 %), contrastes (35 %) y lo fuerte que es el pico máximo (25 %). */
    fun score(startMs: Long, endMs: Long): Float {
        val (a, b) = range(startMs, endMs)
        if (b <= a || track.size == 0) return 0f
        val density = (peakFraction(startMs, endMs) / PEAK_FRACTION_FULL).coerceIn(0f, 1f)
        val contrastScore = (contrastEvents(startMs, endMs) / 2f).coerceIn(0f, 1f)
        var maxDb = -100f
        for (i in a until b) maxDb = max(maxDb, track.db(i))
        val spike = ((maxDb - medianDb) / SPIKE_FULL_DB).coerceIn(0f, 1f)
        return 0.40f * density + 0.35f * contrastScore + 0.25f * spike
    }

    /** ¿El trozo que contiene `timeMs` es un pico de volumen? */
    fun isPeakAt(timeMs: Long): Boolean = track.size > 0 && peak[track.indexAt(timeMs)]

    /** Inicios de pico (flanco de subida), separados al menos `minSeparationMs`. */
    fun peakOnsets(startMs: Long, endMs: Long, minSeparationMs: Long): List<Long> {
        val (a, b) = range(startMs, endMs)
        val out = mutableListOf<Long>()
        for (i in max(a, 1) until b) {
            if (peak[i] && !peak[i - 1]) {
                val t = i.toLong() * track.hopMs
                if (out.isEmpty() || t - out.last() >= minSeparationMs) out += t
            }
        }
        return out
    }

    /**
     * Los `count` momentos más ruidosos de TODO el audio (volumen sostenido ~1 s), separados al menos
     * `minSeparationMs`. Es la base del modo rápido para videos larguísimos.
     */
    fun topPeaks(count: Int, minSeparationMs: Long): List<AudioPeak> {
        val n = track.size
        if (n == 0) return emptyList()
        val w = max(1, 1000 / track.hopMs)
        // media móvil de 1 s con sumas acumuladas
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + track.db(i)
        val level = FloatArray(n) { i ->
            val a = max(0, i - w / 2); val b = min(n, a + w)
            ((prefix[b] - prefix[a]) / (b - a)).toFloat()
        }
        val order = (0 until n).sortedByDescending { level[it] }
        val chosen = mutableListOf<AudioPeak>()
        for (i in order) {
            if (chosen.size >= count) break
            val t = i.toLong() * track.hopMs + track.hopMs / 2
            if (chosen.none { abs(it.timeMs - t) < minSeparationMs }) chosen += AudioPeak(t, level[i])
        }
        return chosen.sortedBy { it.timeMs }
    }

    private fun range(startMs: Long, endMs: Long): Pair<Int, Int> {
        val a = (startMs / track.hopMs).toInt().coerceIn(0, track.size)
        val b = ((endMs + track.hopMs - 1) / track.hopMs).toInt().coerceIn(a, track.size)
        return a to b
    }

    private companion object {
        const val PEAK_ABOVE_MEDIAN_DB = 10f
        const val CONTRAST_JUMP_DB = 18f
        const val QUIET_BELOW_MEDIAN_DB = 8f
        /** Un pico máximo 15 dB por encima de la mediana = puntaje máximo de "spike". */
        const val SPIKE_FULL_DB = 15f
        /** 30 % del tramo en pico = puntaje máximo de densidad. */
        const val PEAK_FRACTION_FULL = 0.30f
    }
}
