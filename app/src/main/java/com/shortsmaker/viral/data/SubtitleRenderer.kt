package com.shortsmaker.viral.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import com.shortsmaker.viral.domain.SubtitleCue
import com.shortsmaker.viral.domain.SubtitleMode
import com.shortsmaker.viral.domain.SubtitleStyle
import com.shortsmaker.viral.domain.WordEmphasis
import kotlin.math.ceil
import kotlin.math.max

/**
 * Dibuja un bloque de subtítulo en un Bitmap transparente. Lo usan la vista previa del editor y el
 * exportador (como overlay de Media3), de modo que lo que se ve en pantalla es lo que se exporta.
 * Todos los tamaños de `SubtitleStyle` están expresados sobre un fotograma de 1080 px de ancho.
 */
class SubtitleRenderer {
    private val fill = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeMiter = 2f
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emojiPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private class Piece(val text: String, val highlighted: Boolean, val colorOverride: Int? = null, var width: Float = 0f)

    /** Índice de la palabra pronunciada en `timeMs` (0 si aún no empezó la primera). */
    fun activeWord(cue: SubtitleCue, timeMs: Long): Int {
        var idx = 0
        for (i in cue.words.indices) if (cue.words[i].startMs <= timeMs) idx = i else break
        return idx
    }

    fun render(cue: SubtitleCue, activeWord: Int, style: SubtitleStyle, frameWidth: Int, spaced: Boolean = true): Bitmap? {
        if (cue.words.isEmpty()) return null
        fun piece(text: String, highlighted: Boolean): Piece {
            val color = if (style.keywordColors) WordEmphasis.of(text)?.color else null
            return Piece(display(text, style), highlighted, color)
        }
        val pieces: List<Piece> = when (style.mode) {
            SubtitleMode.STATIC -> cue.words.map { piece(it.text, false) }
            SubtitleMode.KARAOKE -> cue.words.mapIndexed { i, w -> piece(w.text, i == activeWord) }
            SubtitleMode.WORD_BY_WORD -> listOf(piece(cue.words[activeWord.coerceIn(0, cue.words.lastIndex)].text, true))
        }
        // Emoji automático ENCIMA del texto: el de la palabra activa (o, en modo estático, el de la primera palabra con emoji).
        val emojiAbove: String? = if (!style.emojis) null else when (style.mode) {
            SubtitleMode.STATIC -> cue.words.firstNotNullOfOrNull { WordEmphasis.of(it.text)?.emoji }
            else -> WordEmphasis.of(cue.words[activeWord.coerceIn(0, cue.words.lastIndex)].text)?.emoji
        }

        val scale = frameWidth / 1080f
        var textPx = style.textSize * scale
        val maxLineWidth = frameWidth * 0.86f
        val typeface = Typeface.create(style.font.family, if (style.font.bold) Typeface.BOLD else Typeface.NORMAL)
        fill.typeface = typeface
        stroke.typeface = typeface

        var lines: List<List<Piece>> = emptyList()
        var spaceWidth = 0f
        var attempts = 0
        while (true) {
            fill.textSize = textPx
            stroke.textSize = textPx
            spaceWidth = if (spaced) fill.measureText(" ") else 0f
            pieces.forEach { it.width = fill.measureText(it.text) }
            lines = wrap(pieces, spaceWidth, maxLineWidth)
            // Si una palabra no cabe en el ancho, se reduce el tamaño de todo el bloque.
            if (fits(lines, spaceWidth, maxLineWidth) || ++attempts >= 8) break
            textPx *= 0.9f
        }

        val strokeWidth = textPx * style.strokeRatio
        stroke.strokeWidth = strokeWidth
        val boxPad = if (style.boxColor != 0) textPx * 0.22f else 0f
        val pad = ceil(strokeWidth + boxPad + 2f * scale)
        val fm = fill.fontMetrics
        val lineHeight = (fm.descent - fm.ascent) * 1.04f

        val emojiPx = textPx * 1.35f
        val emojiRow = if (emojiAbove != null) emojiPx * 1.15f else 0f
        val width = ceil(max(lines.maxOf { lineWidth(it, spaceWidth) }, if (emojiAbove != null) emojiPx * 1.4f else 0f) + 2 * pad).toInt().coerceAtLeast(1)
        val height = ceil(emojiRow + lines.size * lineHeight + 2 * pad).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (emojiAbove != null) {
            emojiPaint.textSize = emojiPx
            emojiPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(emojiAbove, width / 2f, pad + emojiPx * 0.95f, emojiPaint)
        }

        lines.forEachIndexed { row, line ->
            val lw = lineWidth(line, spaceWidth)
            var x = (width - lw) / 2f
            val baseline = pad + emojiRow + row * lineHeight - fm.ascent
            if (style.boxColor != 0) {
                boxPaint.color = style.boxColor
                val r = RectF(x - boxPad, baseline + fm.ascent - boxPad * 0.4f, x + lw + boxPad, baseline + fm.descent + boxPad * 0.4f)
                canvas.drawRoundRect(r, boxPad, boxPad, boxPaint)
            }
            for (piece in line) {
                if (style.strokeRatio > 0f) {
                    stroke.color = style.strokeColor
                    canvas.drawText(piece.text, x, baseline, stroke)
                }
                fill.color = piece.colorOverride ?: if (piece.highlighted) style.highlightColor else style.textColor
                canvas.drawText(piece.text, x, baseline, fill)
                x += piece.width + spaceWidth
            }
        }
        return bmp
    }

    private fun fits(lines: List<List<Piece>>, space: Float, max: Float) = lines.all { lineWidth(it, space) <= max }

    private fun lineWidth(line: List<Piece>, space: Float): Float =
        line.sumOf { it.width.toDouble() }.toFloat() + space * max(0, line.size - 1)

    private fun wrap(pieces: List<Piece>, space: Float, maxWidth: Float): List<List<Piece>> {
        val lines = mutableListOf<MutableList<Piece>>()
        var current = mutableListOf<Piece>()
        var w = 0f
        for (p in pieces) {
            val add = if (current.isEmpty()) p.width else space + p.width
            if (current.isNotEmpty() && w + add > maxWidth) {
                lines += current
                current = mutableListOf()
                w = 0f
            }
            w += if (current.isEmpty()) p.width else space + p.width
            current += p
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    private fun display(text: String, style: SubtitleStyle): String = if (style.uppercase) text.uppercase() else text
}
