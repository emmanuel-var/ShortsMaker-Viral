package com.shortsmaker.viral.data

import com.shortsmaker.viral.domain.VoskResult
import com.shortsmaker.viral.domain.WordTiming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/** Voz a texto 100 % local con Vosk (Kaldi), con tiempos por palabra. */
class VoskTranscriber {

    /** Transcribe todo el video. */
    suspend fun transcribe(video: File, modelDir: File, onProgress: (Float) -> Unit): List<WordTiming> =
        transcribeRanges(video, modelDir, listOf(0L to Long.MAX_VALUE)) { _, p -> onProgress(p) }

    /**
     * Transcribe sólo los tramos indicados (modo rápido): el modelo se carga UNA vez y se reutiliza en cada tramo.
     * Los tiempos devueltos son absolutos (del video completo). `onProgress(índiceDeTramo, fracciónDelTramo)`.
     */
    suspend fun transcribeRanges(
        video: File,
        modelDir: File,
        ranges: List<Pair<Long, Long>>,
        onProgress: (index: Int, fraction: Float) -> Unit,
    ): List<WordTiming> = withContext(Dispatchers.Default) {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val words = ArrayList<WordTiming>()
        Model(modelDir.absolutePath).use { model ->
            ranges.forEachIndexed { index, (startMs, endMs) ->
                Recognizer(model, AudioDecoder.TARGET_RATE.toFloat()).use { recognizer ->
                    recognizer.setWords(true)
                    AudioDecoder.decode(
                        file = video,
                        onChunk = { bytes, length ->
                            if (recognizer.acceptWaveForm(bytes, length)) {
                                words += VoskResult.parseWords(recognizer.result, offsetMs = startMs)
                            }
                        },
                        onProgress = { p -> onProgress(index, p) },
                        startMs = startMs,
                        endMs = endMs,
                    )
                    words += VoskResult.parseWords(recognizer.finalResult, offsetMs = startMs)
                }
            }
        }
        words
    }
}
