package com.weike.ime.text

import android.content.Context
import com.weike.ime.data.LexiconTerm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class JiebaMode { ACCURATE, SEARCH }

data class JiebaToken(
    val text: String,
    val start: Int,
    val end: Int,
    val professional: Boolean
)

/** Native cppjieba (Jieba DAG + HMM) wrapper. It never persists input text. */
class JiebaSegmenter(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "jieba")
    private val userDict = File(root, "weike_user.dict.utf8")

    @Volatile var isReady: Boolean = false
        private set

    @Volatile private var professionalTerms: Set<String> = emptySet()

    suspend fun initialize(terms: List<LexiconTerm>): Boolean = withContext(Dispatchers.Default) {
        prepareAssets()
        syncTermsInternal(terms)
        isReady = JiebaNative.nativeInitialize(root.absolutePath, userDict.absolutePath)
        isReady
    }

    suspend fun syncProfessionalTerms(terms: List<LexiconTerm>): Boolean = withContext(Dispatchers.Default) {
        prepareAssets()
        syncTermsInternal(terms)
        isReady = JiebaNative.nativeInitialize(root.absolutePath, userDict.absolutePath)
        isReady
    }

    suspend fun release() = withContext(Dispatchers.Default) {
        if (isReady) JiebaNative.nativeRelease()
        isReady = false
    }

    suspend fun segment(text: String, mode: JiebaMode = JiebaMode.ACCURATE): List<JiebaToken> =
        withContext(Dispatchers.Default) {
            if (text.isBlank()) return@withContext emptyList()
            if (!isReady) return@withContext fallback(text)
            val words = runCatching { JiebaNative.nativeSegment(text, mode == JiebaMode.SEARCH).toList() }
                .getOrDefault(emptyList())
            toTokens(text, words, mode)
        }

    suspend fun hasStructuredExpression(text: String): Boolean {
        val tokens = segment(text, JiebaMode.ACCURATE).map { it.text }
        val markers = setOf("一是", "二是", "三是", "首先", "其次", "最后", "第一", "第二", "第三", "分为", "分别", "提纲", "框架", "待办", "步骤")
        return tokens.count { it in markers } >= 2 ||
            tokens.any { it in markers } && text.any { it == '：' || it == ':' || it == '、' }
    }

    private fun prepareAssets() {
        root.mkdirs()
        val version = File(root, ".version")
        if (version.takeIf { it.exists() }?.readText() == VERSION) return
        ASSETS.forEach { name ->
            appContext.assets.open("jieba/$name").use { input ->
                File(root, name).outputStream().use(input::copyTo)
            }
        }
        version.writeText(VERSION)
    }

    private fun syncTermsInternal(terms: List<LexiconTerm>) {
        val rows = terms.asSequence()
            .map { it.term.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length > 1 && it.any(Char::isLetter) }
            .distinct()
            .toList()
        professionalTerms = rows.toSet()
        userDict.writeText(rows.joinToString("\n") { "$it 2000000 nz" } + if (rows.isEmpty()) "" else "\n")
    }

    private fun toTokens(text: String, words: List<String>, mode: JiebaMode): List<JiebaToken> {
        if (words.isEmpty()) return fallback(text)
        var cursor = 0
        return words.mapNotNull { word ->
            if (word.isBlank()) return@mapNotNull null
            val start = if (mode == JiebaMode.SEARCH) text.indexOf(word, 0) else text.indexOf(word, cursor)
            if (start < 0) return@mapNotNull null
            if (mode == JiebaMode.ACCURATE) cursor = start + word.length
            JiebaToken(word, start, start + word.length, word in professionalTerms)
        }
    }

    private fun fallback(text: String): List<JiebaToken> =
        Regex("[\\p{IsHan}]+|[A-Za-z0-9][A-Za-z0-9._+-]*|\\S")
            .findAll(text)
            .map { match -> JiebaToken(match.value, match.range.first, match.range.last + 1, match.value in professionalTerms) }
            .toList()

    private companion object {
        const val VERSION = "cppjieba-5.4.0-2026.07.14"
        val ASSETS = listOf("jieba.dict.utf8", "hmm_model.utf8", "idf.utf8", "stop_words.utf8", "user.dict.utf8")
    }
}

internal object JiebaNative {
    init { System.loadLibrary("weike_jieba") }

    external fun nativeInitialize(dictDir: String, userDict: String): Boolean
    external fun nativeSegment(text: String, searchMode: Boolean): Array<String>
    external fun nativeRelease()
}
