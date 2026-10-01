package com.shortsmaker.viral.data

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.shortsmaker.viral.domain.AudioEnergyAccumulator
import com.shortsmaker.viral.domain.AudioEnergyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder

class NoAudioTrackException : Exception("El archivo no contiene pista de audio")

/**
 * Decodifica la pista de audio de un video con los códecs de hardware del teléfono, en streaming (nunca carga
 * todo el audio en memoria). Dos salidas:
 *  - [decode]: PCM de 16 bits, mono y 16 kHz (formato de Vosk), opcionalmente sólo de un rango de tiempo.
 *  - [decodeEnergy]: sólo la energía por trozos de 100 ms (Audio Radar). Es mucho más rápido: no remuestrea.
 */
object AudioDecoder {
    const val TARGET_RATE = 16_000
    private const val TIMEOUT_US = 10_000L

    /** PCM 16 kHz mono para Vosk. `startMs`/`endMs` limitan el rango (los tiempos del resultado son relativos a `startMs`). */
    suspend fun decode(
        file: File,
        onChunk: (bytes: ByteArray, length: Int) -> Unit,
        onProgress: (Float) -> Unit,
        startMs: Long = 0,
        endMs: Long = Long.MAX_VALUE,
    ) {
        var resampler: LinearResampler? = null
        var lastRate = -1
        decodeRaw(file, startMs, endMs, onProgress) { mono, rate ->
            if (rate != lastRate) { resampler = LinearResampler(rate, TARGET_RATE); lastRate = rate }
            val resampled = resampler!!.process(mono)
            if (resampled.isNotEmpty()) {
                val bytes = ByteArray(resampled.size * 2)
                for (i in resampled.indices) {
                    bytes[2 * i] = (resampled[i].toInt() and 0xFF).toByte()
                    bytes[2 * i + 1] = ((resampled[i].toInt() shr 8) and 0xFF).toByte()
                }
                onChunk(bytes, bytes.size)
            }
        }
    }

    /** Energía de audio (Audio Radar) de todo el archivo, en trozos de `hopMs` ms. */
    suspend fun decodeEnergy(file: File, hopMs: Int = AudioEnergyAccumulator.DEFAULT_HOP_MS, onProgress: (Float) -> Unit): AudioEnergyTrack {
        var acc: AudioEnergyAccumulator? = null
        var lastRate = -1
        decodeRaw(file, 0, Long.MAX_VALUE, onProgress) { mono, rate ->
            if (acc == null || rate != lastRate) {
                // Si cambiara la frecuencia a mitad del archivo (muy raro) se conserva lo acumulado hasta ahora.
                acc = acc ?: AudioEnergyAccumulator(rate, hopMs)
                lastRate = rate
            }
            acc!!.addSamples(mono)
        }
        return acc?.finish() ?: throw NoAudioTrackException()
    }

    /** Núcleo: entrega PCM mono (promedio de canales) con la frecuencia nativa del decodificador. */
    private suspend fun decodeRaw(
        file: File,
        startMs: Long,
        endMs: Long,
        onProgress: (Float) -> Unit,
        sink: (mono: ShortArray, sampleRate: Int) -> Unit,
    ) = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw NoAudioTrackException()
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val fullDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            val startUs = startMs * 1000
            val endUs = if (endMs == Long.MAX_VALUE) Long.MAX_VALUE else endMs * 1000
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val spanUs = (if (endUs == Long.MAX_VALUE) fullDurationUs else minOf(endUs, fullDurationUs)) - startUs

            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                ensureActive()
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        val t = extractor.sampleTime
                        if (size < 0 || t >= endUs) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, t, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = decoder.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    outIndex >= 0 -> {
                        val outBuf = decoder.getOutputBuffer(outIndex)
                        // Tras el seek al sync previo hay que descartar lo anterior al inicio pedido.
                        if (outBuf != null && info.size > 0 && info.presentationTimeUs >= startUs) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val pcm = ShortArray(shorts.remaining())
                            shorts.get(pcm)
                            sink(downmix(pcm, channels), rate)
                        }
                        if (spanUs > 0) onProgress(((info.presentationTimeUs - startUs).toFloat() / spanUs).coerceIn(0f, 1f))
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            onProgress(1f)
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            extractor.release()
        }
    }

    internal fun downmix(pcm: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return pcm
        val frames = pcm.size / channels
        val out = ShortArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += pcm[f * channels + c]
            out[f] = (sum / channels).toShort()
        }
        return out
    }
}

/** Remuestreo lineal en streaming (suficiente para voz a 16 kHz). */
internal class LinearResampler(srcRate: Int, dstRate: Int) {
    private val passthrough = srcRate == dstRate
    private val step = srcRate.toDouble() / dstRate
    private var last = 0
    private var pos = 1.0

    fun process(input: ShortArray): ShortArray {
        if (passthrough || input.isEmpty()) return input
        val n = input.size
        val out = ShortArray(((n - pos) / step).toInt().coerceAtLeast(0) + 2)
        var count = 0
        while (pos < n) {
            val i = pos.toInt()
            val frac = pos - i
            val a = if (i == 0) last else input[i - 1].toInt()
            val b = input[i].toInt()
            out[count++] = (a + (b - a) * frac).toInt().toShort()
            pos += step
        }
        pos -= n
        last = input[n - 1].toInt()
        return if (count == out.size) out else out.copyOf(count)
    }
}
