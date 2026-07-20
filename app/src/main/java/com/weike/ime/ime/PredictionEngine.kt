package com.weike.ime.ime

import android.content.Context
import com.weike.ime.data.PredictionLearning
import com.weike.ime.text.JiebaMode
import com.weike.ime.text.JiebaSegmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.util.Locale
import kotlin.math.ln

enum class PredictionSource { BASE, ENHANCED, LEARNED, TEMPLATE }

data class PredictionCandidate(
    val text: String,
    val source: PredictionSource,
    val score: Double,
    val appendSpace: Boolean = false
)

/**
 * Local next-token prediction. Static indexes are built on the host from
 * redistributable Rime-Ice entries; this class only reads them on device.
 */
class PredictionEngine(context: Context, private val jieba: JiebaSegmenter) {
    private val appContext = context.applicationContext
    @Volatile private var base: Map<String, List<IndexedPrediction>> = emptyMap()
    @Volatile private var enhanced: Map<String, List<IndexedPrediction>> = emptyMap()

    suspend fun initialize() = withContext(Dispatchers.Default) {
        if (base.isEmpty()) base = readPack("prediction/base.bin")
        if (enhanced.isEmpty()) enhanced = readPack("prediction/enhanced.bin")
    }

    suspend fun tokenize(text: String): List<String> = withContext(Dispatchers.Default) {
        if (text.isBlank() || containsSensitiveValue(text)) return@withContext emptyList()
        val chinese = if (jieba.isReady) jieba.segment(text, JiebaMode.ACCURATE).map { it.text } else emptyList()
        val result = if (chinese.isEmpty()) fallbackTokens(text) else chinese
        result.map(::normalize).filter(::isLearnableToken).takeLast(MAX_CONTEXT_TOKENS)
    }

    suspend fun suggestions(
        text: String,
        learned: List<PredictionLearning>,
        useEnhanced: Boolean
    ): List<PredictionCandidate> = withContext(Dispatchers.Default) {
        initialize()
        suggestionsForTokens(tokenize(text), learned, useEnhanced)
    }

    suspend fun suggestionsForTokens(
        tokens: List<String>,
        learned: List<PredictionLearning>,
        useEnhanced: Boolean
    ): List<PredictionCandidate> = withContext(Dispatchers.Default) {
        initialize()
        val contexts = contextsFor(tokens)
        if (contexts.isEmpty()) return@withContext emptyList()
        val values = ArrayList<PredictionCandidate>()
        contexts.forEachIndexed { index, context ->
            val contextBoost = if (index == 0) 4_000_000.0 else 200_000.0
            base[context].orEmpty().forEach { item ->
                values += candidate(item.text, PredictionSource.BASE, contextBoost + item.weight)
            }
            if (useEnhanced) enhanced[context].orEmpty().forEach { item ->
                values += candidate(item.text, PredictionSource.ENHANCED, contextBoost + 15_000 + item.weight)
            }
            ENGLISH_PHRASES[context].orEmpty().forEachIndexed { phraseIndex, phrase ->
                values += candidate(phrase, PredictionSource.TEMPLATE, contextBoost + 500_000 - phraseIndex * 100)
            }
        }
        learned.forEach { item ->
            val position = contexts.indexOf(item.context)
            if (position >= 0 && isLearnableToken(item.target)) {
                val contextBoost = if (position == 0) 5_000_000.0 else 300_000.0
                values += candidate(item.target, PredictionSource.LEARNED,
                    contextBoost + ln((item.useCount + 1).toDouble()) * 100_000 + item.lastUsedAt / 1e11)
            }
        }
        templatePredictions(tokens.lastOrNull().orEmpty()).forEach { values += it }
        values.asSequence()
            .filter { it.text.isNotBlank() && !containsSensitiveValue(it.text) }
            .groupBy { it.text.lowercase(Locale.ROOT) }
            .map { (_, same) -> same.maxBy { it.score } }
            .sortedByDescending { it.score }
            .take(MAX_SUGGESTIONS)
            .toList()
    }

    fun transitions(tokens: List<String>): List<Pair<String, String>> {
        if (tokens.size < 2) return emptyList()
        return buildList {
            for (index in 1 until tokens.size) {
                add(tokens[index - 1] to tokens[index])
                if (index >= 2) add("${tokens[index - 2]}$SEPARATOR${tokens[index - 1]}" to tokens[index])
            }
        }.filter { (context, target) -> isLearnableToken(context.substringAfterLast(SEPARATOR)) && isLearnableToken(target) }
    }

    fun transitionsForAppend(previous: List<String>, appended: List<String>): List<Pair<String, String>> {
        if (appended.isEmpty()) return emptyList()
        val combined = (previous + appended).takeLast(MAX_CONTEXT_TOKENS)
        val firstNew = (combined.size - appended.size).coerceAtLeast(0)
        return buildList {
            for (index in maxOf(1, firstNew) until combined.size) {
                add(combined[index - 1] to combined[index])
                if (index >= 2) add("${combined[index - 2]}$SEPARATOR${combined[index - 1]}" to combined[index])
            }
        }
    }

    fun contextsFor(tokens: List<String>): List<String> = buildList {
        if (tokens.size >= 2) add("${tokens[tokens.lastIndex - 1]}$SEPARATOR${tokens.last()}")
        tokens.lastOrNull()?.let(::add)
        // Dictionary phrase prefixes provide useful fallback when Jieba groups
        // multiple Han characters into one token.
        tokens.lastOrNull()?.takeLast(4)?.let { token ->
            for (length in minOf(4, token.length) downTo 1) add(token.takeLast(length))
        }
    }.distinct()

    private fun templatePredictions(last: String): List<PredictionCandidate> = when (last.lowercase(Locale.ROOT)) {
        "http" -> listOf(PredictionCandidate("s://", PredictionSource.TEMPLATE, 900_000.0))
        "https" -> listOf(PredictionCandidate("://", PredictionSource.TEMPLATE, 900_000.0))
        "@" -> listOf(
            PredictionCandidate("qq.com", PredictionSource.TEMPLATE, 850_000.0),
            PredictionCandidate("gmail.com", PredictionSource.TEMPLATE, 840_000.0)
        )
        else -> emptyList()
    }

    private fun candidate(text: String, source: PredictionSource, score: Double): PredictionCandidate =
        PredictionCandidate(text, source, score, appendSpace = text.matches(ENGLISH_WORD))

    private fun readPack(asset: String): Map<String, List<IndexedPrediction>> = runCatching {
        appContext.assets.open(asset).use { input ->
            DataInputStream(input.buffered()).use { stream ->
                require(Integer.reverseBytes(stream.readInt()) == MAGIC) { "Invalid prediction index" }
                val count = Integer.reverseBytes(stream.readInt())
                buildMap(count) {
                    repeat(count) {
                        val context = readString(stream)
                        val entries = buildList {
                            repeat(stream.readUnsignedByte()) {
                                add(IndexedPrediction(readString(stream), Integer.reverseBytes(stream.readInt()).toDouble()))
                            }
                        }
                        put(context, entries)
                    }
                }
            }
        }
    }.getOrElse { emptyMap() }

    private fun readString(stream: DataInputStream): String {
        val length = java.lang.Short.toUnsignedInt(java.lang.Short.reverseBytes(stream.readShort()))
        val bytes = ByteArray(length)
        stream.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun fallbackTokens(text: String): List<String> =
        Regex("[\\p{IsHan}]{1,8}|[A-Za-z][A-Za-z0-9._+-]*|[^\\s]")
            .findAll(text).map { it.value }.toList()

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)

    private fun isLearnableToken(value: String): Boolean =
        value.length in 1..32 && !value.matches(ONLY_DIGITS) && !value.contains('@') && !value.contains("://")

    private fun containsSensitiveValue(value: String): Boolean =
        value.length > 64 || value.matches(ONLY_DIGITS) || value.contains('@') || value.contains("://") ||
            value.matches(OTP) || value.matches(LONG_MIXED_TOKEN)

    private data class IndexedPrediction(val text: String, val weight: Double)

    companion object {
        const val SEPARATOR = "\u001f"
        private const val MAGIC = 0x31505456
        private const val MAX_CONTEXT_TOKENS = 16
        private const val MAX_SUGGESTIONS = 6
        private val ONLY_DIGITS = Regex("^\\d+$")
        private val OTP = Regex("^\\d{6,8}$")
        private val LONG_MIXED_TOKEN = Regex("^[A-Za-z0-9_-]{16,}$")
        private val ENGLISH_WORD = Regex("^[A-Za-z][A-Za-z'-]*$")
        private val ENGLISH_PHRASES = mapOf(
            "hello" to listOf("there", "world"),
            "good" to listOf("morning", "afternoon", "evening"),
            "thank" to listOf("you"),
            "thank${SEPARATOR}you" to listOf("very much"),
            "please" to listOf("let me know"),
            "let" to listOf("me know"),
            "let${SEPARATOR}me" to listOf("know"),
            "see" to listOf("you soon"),
            "how" to listOf("are you"),
            "i" to listOf("think", "would", "will"),
            "we" to listOf("can", "will", "should")
        )
    }
}
