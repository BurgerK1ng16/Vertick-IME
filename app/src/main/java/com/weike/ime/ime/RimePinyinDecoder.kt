package com.weike.ime.ime

import android.content.Context
import com.osfans.trime.core.Rime
import com.weike.ime.data.LexiconTerm
import com.weike.ime.data.TypingDictionaryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer

data class PinyinCandidate(
    val text: String,
    val score: Double = 0.0,
    val index: Int = -1,
    val directCommit: Boolean = false
)

data class PinyinSessionState(
    val preedit: String = "",
    val candidates: List<PinyinCandidate> = emptyList(),
    val committedText: String? = null
)

/** A serialized librime session. Candidate ordering is owned by librime. */
class RimePinyinDecoder(context: Context) {
    private val appContext = context.applicationContext
    private val sharedDir = File(appContext.filesDir, "rime/shared")
    private val userDir = File(appContext.filesDir, "rime/user")
    private val sessionMutex = Mutex()

    @Volatile var isReady: Boolean = false
        private set

    @Volatile var statusText: String = "词典准备中"
        private set

    suspend fun startSession(
        terms: List<LexiconTerm>,
        typingDictionary: List<TypingDictionaryEntry> = emptyList()
    ): Boolean = withContext(Dispatchers.Default) {
        sessionMutex.withLock {
            if (isReady) return@withLock true
            statusText = "词典准备中"
            val needsDeployment = prepareFiles(terms, typingDictionary)
            val ready = runCatching { startAndVerify(needsDeployment) }.getOrElse { false }
            isReady = ready
            statusText = if (ready) "Rime-Ice 完整词典已就绪" else "词典准备失败"
            ready
        }
    }

    suspend fun clear(): PinyinSessionState = nativeState {
        if (!isReady) return@nativeState PinyinSessionState()
        Rime.clearRimeComposition()
        snapshot()
    }

    suspend fun input(value: String): PinyinSessionState = nativeState {
        if (!isReady || value.length != 1) return@nativeState PinyinSessionState()
        Rime.processRimeKey(value[0].code, 0)
        snapshot()
    }

    suspend fun backspace(): PinyinSessionState = nativeState {
        if (!isReady) return@nativeState PinyinSessionState()
        Rime.processRimeKey(KEY_BACKSPACE, 0)
        snapshot()
    }

    suspend fun selectCandidate(index: Int): PinyinSessionState = nativeState {
        if (!isReady || index < 0) return@nativeState PinyinSessionState()
        Rime.selectRimeCandidate(index, true)
        snapshot()
    }

    suspend fun commitFirst(): PinyinSessionState = selectCandidate(0)

    suspend fun currentState(): PinyinSessionState = nativeState {
        if (!isReady) PinyinSessionState() else snapshot()
    }

    /** Uses the same reading lookup as the generated Rime custom dictionary. */
    fun pinyinCodeForTerm(term: String, hint: String): String =
        normalizePinyin(hint).ifBlank { readingsForTerm(term.trim()) }

    suspend fun syncProfessionalTerms(
        terms: List<LexiconTerm>,
        typingDictionary: List<TypingDictionaryEntry> = emptyList()
    ): Boolean = withContext(Dispatchers.Default) {
        sessionMutex.withLock {
            val termsChanged = writeTerms(terms, typingDictionary)
            if (!isReady) return@withLock false
            if (termsChanged) restart("正在更新专业词") else true
        }
    }

    /** Writes terms for the next native deployment without restarting a live session. */
    suspend fun stageProfessionalTerms(
        terms: List<LexiconTerm>,
        typingDictionary: List<TypingDictionaryEntry> = emptyList()
    ): Boolean = withContext(Dispatchers.Default) {
        sessionMutex.withLock {
            val changed = writeTerms(terms, typingDictionary)
            if (changed) File(userDir, TERMS_PENDING_FILE).apply {
                parentFile?.mkdirs()
                writeText("1")
            }
            changed
        }
    }

    suspend fun clearUserLearning(): Boolean = withContext(Dispatchers.Default) {
        sessionMutex.withLock {
            isReady = false
            statusText = "正在清除学习数据"
            runCatching {
                Rime.exitRime()
                userDir.deleteRecursively()
                userDir.mkdirs()
                startAndVerify(deploy = true)
            }.getOrDefault(false).also { ready ->
                isReady = ready
                statusText = if (ready) "Rime-Ice 完整词典已就绪" else "词典准备失败"
            }
        }
    }

    suspend fun shutdown() = withContext(Dispatchers.Default) {
        sessionMutex.withLock {
            if (isReady) runCatching { Rime.exitRime() }
            isReady = false
            statusText = "首次使用时准备"
        }
    }

    private suspend fun restart(progress: String): Boolean {
        isReady = false
        statusText = progress
        return runCatching {
            Rime.exitRime()
            startAndVerify(deploy = true)
        }.getOrDefault(false).also { ready ->
            isReady = ready
            statusText = if (ready) "Rime-Ice 完整词典已就绪" else "词典更新失败"
        }
    }

    private suspend fun <T> nativeState(block: () -> T): T = withContext(Dispatchers.Default) {
        sessionMutex.withLock { block() }
    }

    private suspend fun startAndVerify(deploy: Boolean): Boolean {
        // librime's full check owns its deployment worker. Calling deploySchemaFile as
        // well starts a second compiler thread and crashes on the full Rime-Ice table.
        Rime.startupRime(sharedDir.absolutePath, userDir.absolutePath, ENGINE_VERSION, deploy)
        return waitForSchemaAndHealth()
    }

    /** Selecting a schema only proves that a yaml file was found. Query real words too. */
    private suspend fun waitForSchemaAndHealth(): Boolean {
        repeat(180) {
            if (Rime.selectRimeSchema(SCHEMA_ID) && verifyDictionaryHealth()) return true
            delay(250)
        }
        return false
    }

    private fun verifyDictionaryHealth(): Boolean = runCatching {
        val status = Rime.getRimeStatus()
        if (status.schemaId != SCHEMA_ID || status.isDisabled) return@runCatching false
        HEALTH_QUERIES.all { query ->
            Rime.clearRimeComposition()
            query.forEach { character -> Rime.processRimeKey(character.code, 0) }
            Rime.getRimeCandidates(0, 8).isNotEmpty()
        }.also { Rime.clearRimeComposition() }
    }.getOrDefault(false)

    private fun snapshot(): PinyinSessionState {
        val context = Rime.getRimeContext()
        val nativeCandidates = Rime.getRimeCandidates(0, MAX_CANDIDATES).mapIndexed { index, candidate ->
            PinyinCandidate(candidate.text, MAX_CANDIDATES - index.toDouble(), index)
        }
        val rawComposition = context.input.ifBlank { context.composition.preedit.orEmpty() }
        // Single-letter initials favor characters; full syllables and phrases retain Rime order.
        val candidates = if (rawComposition.length == 1 && rawComposition[0].isLetter()) {
            nativeCandidates.sortedWith(compareBy<PinyinCandidate> { it.text.length }.thenByDescending { it.score })
        } else {
            nativeCandidates
        }
        return PinyinSessionState(
            preedit = rawComposition,
            candidates = candidates,
            committedText = Rime.getRimeCommit().text?.takeIf(String::isNotBlank)
        )
    }

    private fun prepareFiles(terms: List<LexiconTerm>, typingDictionary: List<TypingDictionaryEntry>): Boolean {
        sharedDir.mkdirs()
        userDir.mkdirs()
        val versionFile = File(sharedDir, ".weike_version")
        val assetsChanged = versionFile.readTextOrEmpty() != ENGINE_VERSION
        if (assetsChanged) {
            ASSET_FILES.forEach { asset -> copyAsset("rime/$asset", File(sharedDir, asset)) }
            // Preserve userdb learning but force compilation of the complete dictionary set.
            File(userDir, "build").deleteRecursively()
            versionFile.writeText(ENGINE_VERSION)
        }
        val termsChanged = writeTerms(terms, typingDictionary) || File(userDir, TERMS_PENDING_FILE).delete()
        val compiledTable = File(userDir, "build/$SCHEMA_ID.table.bin")
        return assetsChanged || termsChanged || !compiledTable.exists() || compiledTable.length() < MIN_TABLE_BYTES
    }

    private fun writeTerms(terms: List<LexiconTerm>, typingDictionary: List<TypingDictionaryEntry>): Boolean {
        sharedDir.mkdirs()
        val header = "# SPDX-License-Identifier: GPL-3.0-or-later\n---\nname: weike_terms\nversion: '$ENGINE_VERSION'\nsort: by_weight\n...\n"
        val customByTerm = typingDictionary.associateBy { it.term.trim() }
        val allTerms = (terms.map { it.term.trim() to it.hint } + typingDictionary.map { it.term.trim() to it.hint })
            .filter { it.first.isNotBlank() }
            .associateBy({ it.first }, { it.second })
        val rows = allTerms.asSequence().mapNotNull { (termText, hint) ->
            val term = LexiconTerm(termText, hint)
            val text = term.term.trim().replace(Regex("[\\t\\r\\n]+"), " ")
            val code = pinyinCodeForTerm(text, term.hint)
            val weight = if (customByTerm.containsKey(text)) 9_999_999 else 1_000_000
            if (text.isBlank() || code.isBlank()) null else "$text\t$code\t$weight"
        }.distinct().joinToString("\n")
        val destination = File(sharedDir, TERMS_FILE)
        val content = header + if (rows.isBlank()) "" else "$rows\n"
        if (destination.readTextOrEmpty() == content) return false
        destination.writeText(content)
        return true
    }

    private fun normalizePinyin(value: String): String = value.lowercase()
        .replace(Regex("[^a-z' ]"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    // This lookup is only for professional terms without an explicit pinyin hint.
    private fun readingsForTerm(text: String): String {
        val readings = termReadings()
        return text.mapNotNull { readings[it] }.joinToString(" ")
    }

    private fun termReadings(): Map<Char, String> {
        cachedTermReadings?.let { return it }
        val values = HashMap<Char, String>()
        appContext.assets.open("pinyin/pinyin.txt").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val match = CHAR_READING.matchEntire(line.substringBefore('#').trim()) ?: return@forEach
                val character = match.groupValues[1].toIntOrNull(16)?.toChar() ?: return@forEach
                val reading = Normalizer.normalize(match.groupValues[2].substringBefore(',').lowercase(), Normalizer.Form.NFD)
                    .filter { it in 'a'..'z' }
                if (reading.isNotBlank()) values[character] = reading
            }
        }
        return values.also { cachedTermReadings = it }
    }

    private fun copyAsset(asset: String, destination: File) {
        destination.parentFile?.mkdirs()
        appContext.assets.open(asset).use { input -> destination.outputStream().use(input::copyTo) }
    }

    companion object {
        const val SCHEMA_ID = "weike_pinyin"
        const val DICTIONARY_VERSION = "Rime-Ice Full Chinese 2026.07.07"
        const val ENGINE_VERSION_TEXT = "librime 1.17.0"
        private const val ENGINE_VERSION = "weike-rime-ice-full-2026.07.14.2"
        private const val TERMS_FILE = "weike_terms.dict.yaml"
        private const val TERMS_PENDING_FILE = ".weike_terms_pending"
        private const val KEY_BACKSPACE = 0xff08
        private const val MAX_CANDIDATES = 96
        private const val MIN_TABLE_BYTES = 1_000_000L
        private val HEALTH_QUERIES = listOf("d", "dui", "shi", "zhong", "womenjintian")
        private val CHAR_READING = Regex("^U\\+([0-9A-Fa-f]+):\\s*(.+)$")
        private val ASSET_FILES = listOf(
            "default.yaml",
            "weike_pinyin.schema.yaml",
            "weike_pinyin.dict.yaml",
            "weike_terms.dict.yaml",
            "rime_ice.dict.yaml",
            "cn_dicts/8105.dict.yaml",
            "cn_dicts/base.dict.yaml",
            "cn_dicts/ext.dict.yaml",
            "cn_dicts/tencent.dict.yaml",
            "cn_dicts/others.dict.yaml"
        )

        @Volatile private var cachedTermReadings: Map<Char, String>? = null

        fun requestClearLearnedData(context: Context) {
            File(context.applicationContext.filesDir, "rime/.clear_learning").apply {
                parentFile?.mkdirs()
                writeText("1")
            }
        }

        fun consumeClearRequest(context: Context): Boolean =
            File(context.applicationContext.filesDir, "rime/.clear_learning").delete()

        fun deploymentStatus(context: Context): String = if (
            File(context.applicationContext.filesDir, "rime/user/build/weike_pinyin.table.bin").exists()
        ) "已就绪" else "首次使用时准备"
    }
}

private fun File.readTextOrEmpty(): String = if (exists()) readText() else ""
