package com.nous.aurora.util

import android.content.Context
import java.io.File
import java.nio.charset.Charset

object EncodingUtil {

    data class EncodingResult(val text: String, val charset: String, val score: Int = 0)

    val allEncodings = listOf(
        Charsets.UTF_8 to "UTF-8",
        Charset.forName("GB18030") to "GB18030",
        Charset.forName("GBK") to "GBK",
        Charset.forName("GB2312") to "GB2312",
        Charset.forName("Big5") to "Big5",
        Charset.forName("Big5-HKSCS") to "Big5-HKSCS",
        Charsets.UTF_16 to "UTF-16",
        Charsets.UTF_16BE to "UTF-16BE",
        Charsets.UTF_16LE to "UTF-16LE",
        Charset.forName("EUC-JP") to "EUC-JP",
        Charset.forName("Shift_JIS") to "Shift_JIS",
        Charset.forName("EUC-KR") to "EUC-KR",
        Charset.forName("windows-1252") to "Windows-1252",
        Charset.forName("ISO-8859-2") to "ISO-8859-2 (Latin-2)",
        Charset.forName("KOI8-R") to "KOI8-R",
        Charsets.ISO_8859_1 to "ISO-8859-1 (Latin-1)",
    )

    private val ENCODING_PREFS = "encoding_prefs"

    fun readWithEncodingDetection(file: File, ctx: Context? = null): EncodingResult {
        // Check saved encoding preference first
        if (ctx != null) {
            val saved = getSavedEncoding(ctx, file.absolutePath)
            if (saved != null) {
                try {
                    val text = String(file.readBytes(), Charset.forName(saved))
                    return EncodingResult(text, "$saved (已记忆)", 999)
                } catch (_: Exception) {
                    // Saved encoding failed, fall through to detection
                }
            }
        }

        val bytes = file.readBytes()
        if (bytes.isEmpty()) return EncodingResult("", "empty", 0)

        // Check BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return EncodingResult(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), "UTF-8 (BOM)", 1000)
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
                return EncodingResult(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), "UTF-16BE (BOM)", 1000)
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
                return EncodingResult(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), "UTF-16LE (BOM)", 1000)
        }

        var bestResult = tryUTF8(bytes)
        var bestScore = bestResult.score

        for ((enc, name) in allEncodings) {
            if (enc == Charsets.UTF_8) continue
            try {
                val text = String(bytes, enc)
                val score = scoreText(text, enc)
                if (score > bestScore) {
                    bestResult = EncodingResult(text, name, score)
                    bestScore = score
                }
            } catch (_: Exception) {}
        }

        return bestResult
    }

    private fun tryUTF8(bytes: ByteArray): EncodingResult {
        val text = String(bytes, Charsets.UTF_8)
        val score = scoreText(text, Charsets.UTF_8)
        return EncodingResult(text, "UTF-8", score)
    }

    fun readWithCharset(file: File, charsetName: String): String {
        val bytes = file.readBytes()
        return try {
            String(bytes, Charset.forName(charsetName))
        } catch (_: Exception) {
            String(bytes, Charsets.UTF_8)
        }
    }

    fun saveEncoding(ctx: Context, filePath: String, encoding: String) {
        ctx.getSharedPreferences(ENCODING_PREFS, Context.MODE_PRIVATE)
            .edit().putString(filePath, encoding).apply()
    }

    fun getSavedEncoding(ctx: Context, filePath: String): String? {
        return ctx.getSharedPreferences(ENCODING_PREFS, Context.MODE_PRIVATE)
            .getString(filePath, null)
    }

    /**
     * Score text quality. Higher = better.
     * Uses character frequency analysis for CJK detection,
     * penalizes replacement chars and control chars.
     */
    private fun scoreText(text: String, charset: Charset): Int {
        if (text.isEmpty()) return -1000
        val sample = text.take(1000)
        var score = 0
        var replacementCount = 0
        var cjkCount = 0
        var asciiCount = 0
        var jpCount = 0

        for (ch in sample) {
            when {
                ch == '\uFFFD' -> { replacementCount++; score -= 20 }
                ch in '\u0000'..'\u0008' -> score -= 10
                ch in '\u000B'..'\u001F' && ch != '\n' && ch != '\r' && ch != '\t' -> score -= 5
                ch.isLetterOrDigit() -> {
                    if (ch.code <= 0x7F) asciiCount++ else {
                        if (isCjk(ch)) cjkCount++ else if (isJapanese(ch)) jpCount++
                    }
                    score += 1
                }
                isCjkSymbol(ch) -> cjkCount++
                ch.isWhitespace() -> { /* neutral */ }
            }
        }

        // Boost for CJK encodings when CJK characters detected
        val charsetName = charset.name().lowercase()
        val isCjkEncoding = charsetName.startsWith("gb") || charsetName.startsWith("big5") ||
            charsetName.startsWith("euc") || charsetName.startsWith("shift") ||
            charsetName == "gb18030" || charsetName == "gbk" || charsetName == "gb2312"

        if (cjkCount > 0 && isCjkEncoding) score += cjkCount * 5
        if (jpCount > 0 && (charsetName.startsWith("euc") || charsetName.startsWith("shift"))) score += jpCount * 4

        // Heavily penalize if >20% replacement chars
        if (sample.isNotEmpty() && replacementCount.toFloat() / sample.length > 0.2f) score -= 5000

        // Penalize if CJK-encoded but no CJK characters found
        if (cjkCount == 0 && jpCount == 0 && isCjkEncoding) score -= 100

        // Boost UTF-8 if it has good CJK
        if (charsetName == "utf-8" && cjkCount > 0) score += cjkCount * 3

        return score
    }

    private fun isCjk(ch: Char): Boolean {
        val block = Character.UnicodeBlock.of(ch)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
    }

    private fun isCjkSymbol(ch: Char): Boolean {
        val block = Character.UnicodeBlock.of(ch)
        return block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_FORMS ||
            block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }

    private fun isJapanese(ch: Char): Boolean {
        val block = Character.UnicodeBlock.of(ch)
        return block == Character.UnicodeBlock.HIRAGANA || block == Character.UnicodeBlock.KATAKANA
    }
}
