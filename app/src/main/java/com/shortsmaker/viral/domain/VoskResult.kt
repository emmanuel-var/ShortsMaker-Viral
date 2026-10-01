package com.shortsmaker.viral.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Convierte el JSON de resultados de Vosk (`{"result":[{"word","start","end"}]}`) en palabras con tiempos. */
object VoskResult {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseWords(raw: String, offsetMs: Long = 0): List<WordTiming> {
        val root = try { json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null } ?: return emptyList()
        val arr = root["result"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el.jsonObject
            val word = o["word"]?.jsonPrimitive?.content?.trim().orEmpty()
            val s = o["start"]?.jsonPrimitive?.doubleOrNull
            val e = o["end"]?.jsonPrimitive?.doubleOrNull
            if (word.isEmpty() || s == null || e == null) null
            else WordTiming(word, offsetMs + (s * 1000).toLong(), offsetMs + (e * 1000).toLong().coerceAtLeast((s * 1000).toLong() + 1))
        }
    }
}
