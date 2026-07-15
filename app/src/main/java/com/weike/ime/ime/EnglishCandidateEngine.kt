package com.weike.ime.ime

import android.content.Context
import com.weike.ime.data.EnglishLearning
import com.weike.ime.data.TypingDictionaryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Offline English completion backed by Rime-Ice's 20k word list. */
object EnglishCandidateEngine {
    private val prefixCache = ConcurrentHashMap<String, List<PinyinCandidate>>()
    private val common = listOf(
        "the", "be", "to", "of", "and", "a", "in", "that", "have", "i", "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
        "this", "is", "hello", "please", "thanks", "thank", "we", "will", "can", "could", "should", "would", "project", "meeting", "message", "today", "tomorrow"
    ).withIndex().associate { it.value to (50_000 - it.index * 500) }
    private val commonPhrases = listOf(
        "thank you", "please let me know", "let me know", "as soon as possible",
        "for your reference", "best regards", "good morning", "good afternoon",
        "happy to help", "sounds good", "see you soon", "talk to you later"
    )

    @Volatile private var words: List<String> = emptyList()
    @Volatile private var learning: Map<String, Int> = emptyMap()
    @Volatile private var customWords: List<String> = emptyList()

    val isReady: Boolean get() = words.isNotEmpty()

    suspend fun initialize(
        context: Context,
        learned: List<EnglishLearning>,
        custom: List<TypingDictionaryEntry> = emptyList()
    ) = withContext(Dispatchers.Default) {
        if (words.isEmpty()) {
            words = (context.applicationContext.assets.open("english/en.dict.yaml").bufferedReader().useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("#") && it != "---" && it != "..." && !it.startsWith("name:") && !it.startsWith("version:") && !it.startsWith("sort:") }
                    .map { it.substringBefore('\t').trim() }
                    .filter { it.matches(Regex("[A-Za-z][A-Za-z'-]*")) }
                    .distinct()
                    .toList()
            } + commonPhrases).distinct()
        }
        learning = learned.associate { it.term.lowercase(Locale.US) to it.useCount }
        customWords = custom.map { it.term.trim() }
            .filter { it.matches(Regex("[A-Za-z][A-Za-z '-]*")) }
            .distinctBy { it.lowercase(Locale.US) }
        prefixCache.clear()
    }

    fun syncTypingDictionary(entries: List<TypingDictionaryEntry>) {
        customWords = entries.map { it.term.trim() }
            .filter { it.matches(Regex("[A-Za-z][A-Za-z '-]*")) }
            .distinctBy { it.lowercase(Locale.US) }
        prefixCache.clear()
    }

    fun candidates(composition: String): List<PinyinCandidate> {
        if (composition.isBlank()) return emptyList()
        val query = composition.lowercase(Locale.US)
        return prefixCache.getOrPut(query) { buildCandidates(composition, query) }
    }

    fun selected(value: String): EnglishLearning {
        val key = value.lowercase(Locale.US)
        val next = (learning[key] ?: 0) + 1
        learning = learning + (key to next)
        prefixCache.clear()
        return EnglishLearning(key, next, System.currentTimeMillis())
    }

    private fun buildCandidates(original: String, query: String): List<PinyinCandidate> {
        val customMatches = customWords.asSequence()
            .filter { it.lowercase(Locale.US).startsWith(query) }
            .toList()
        val prefixMatches = words.asSequence()
            .filter { it.lowercase(Locale.US).startsWith(query) }
            .sortedWith(compareByDescending<String> { score(it) }.thenBy { it.length }.thenBy { it })
            .take(15)
            .toList()
        val corrected = if (prefixMatches.isEmpty() && query.length >= 3) {
            words.asSequence()
                .filter { kotlin.math.abs(it.length - query.length) <= 1 }
                .map { it to editDistanceAtMostOne(query, it.lowercase(Locale.US)) }
                .filter { it.second <= 1 }
                .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenByDescending { score(it.first) })
                .map { it.first }
                .take(6)
                .toList()
        } else emptyList()
        return (customMatches + listOf(original) + prefixMatches + corrected)
            .distinctBy { it.lowercase(Locale.US) }
            .mapIndexed { index, word -> PinyinCandidate(word, score(word) - index) }
    }

    private fun score(word: String): Double {
        val key = word.lowercase(Locale.US)
        return (learning[key] ?: 0) * 100_000.0 + (common[key] ?: 0) - word.length * .05
    }

    private fun editDistanceAtMostOne(a: String, b: String): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > 1) return 2
        var left = 0
        var right = 0
        var changes = 0
        while (left < a.length && right < b.length) {
            if (a[left] == b[right]) { left++; right++; continue }
            if (++changes > 1) return changes
            if (a.length > b.length) left++ else if (b.length > a.length) right++ else { left++; right++ }
        }
        return changes + if (left < a.length || right < b.length) 1 else 0
    }
}
