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

    suspend fun transcribe(
        video: File,
        modelDir: File,
        onProgress: (Float) -> Unit,
    ): List<WordTiming> = withContext(Dispatchers.Default) {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val words = ArrayList<WordTiming>()
        Model(modelDir.absolutePath).use { model ->
            Recognizer(model, AudioDecoder.TARGET_RATE.toFloat()).use { recognizer ->
                recognizer.setWords(true)
                AudioDecoder.decode(
                    file = video,
                    onChunk = { bytes, length ->
                        if (recognizer.acceptWaveForm(bytes, length)) {
                            words += VoskResult.parseWords(recognizer.result)
                        }
                    },
                    onProgress = onProgress,
                )
                words += VoskResult.parseWords(recognizer.finalResult)
            }
        }
        words
    }
}
