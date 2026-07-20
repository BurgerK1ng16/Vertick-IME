package com.weike.ime.ime

import android.content.Context
import android.util.Log
import com.osfans.trime.core.Rime
import com.weike.ime.data.ChineseKeyboardLayout
import com.weike.ime.data.DictionaryPackManager
import com.weike.ime.data.LexiconTerm
import com.weike.ime.data.PinyinLearning
import com.weike.ime.data.TypingDictionaryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.util.zip.ZipInputStream

data class PinyinCandidate(
    val text: String,
    val score: Double = 0.0,
    /** librime global candidate index; -1 means an application-owned candidate. */
    val index: Int = -1,
    val directCommit: Boolean = false
)

data class PinyinSessionState(
    val preedit: String = "",
    val candidates: List<PinyinCandidate> = emptyList(),
    val committedText: String? = null,
    val rawComposition: String = preedit
)

/** Serializes every mutation of the active Chinese composition. */
interface PinyinDecoder {
    val isReady: Boolean
    val statusText: String
    suspend fun startSession(terms: List<LexiconTerm>, typingDictionary: List<TypingDictionaryEntry>, learning: List<PinyinLearning>): Boolean
    suspend fun clear(): PinyinSessionState
    suspend fun input(value: String): PinyinSessionState
    suspend fun backspace(): PinyinSessionState
    suspend fun restoreComposition(value: String): PinyinSessionState
    suspend fun selectCandidate(index: Int): PinyinSessionState
    suspend fun commitFirst(): PinyinSessionState
    suspend fun currentState(): PinyinSessionState
    suspend fun selectChineseKeyboardLayout(layout: ChineseKeyboardLayout): Boolean
    suspend fun syncProfessionalTerms(terms: List<LexiconTerm>, typingDictionary: List<TypingDictionaryEntry>): Boolean
    suspend fun syncUserLearning(entries: List<PinyinLearning>): Boolean
    fun pinyinForDisplay(text: String): String
    suspend fun learnCandidate(text: String): Boolean
    suspend fun clearUserLearning(): Boolean
    suspend fun shutdown()
}

/**
 * The only runtime Chinese decoder. Tables are generated before release and are
 * placed in librime's shared prebuilt directory, so startup never deploys YAML
 * or compiles a dictionary on the device.
 */
class RimePinyinDecoder(context: Context) : PinyinDecoder {
    private val appContext = context.applicationContext
    private val sharedDir = File(appContext.filesDir, "rime/shared")
    private val userDir = File(appContext.filesDir, "rime/user")
    private val sessionMutex = Mutex()
    private var overlayEntries: List<OverlayEntry> = emptyList()
    private var migratedLearning: Map<String, PinyinLearning> = emptyMap()

    @Volatile override var isReady = false
        private set
    @Volatile override var statusText = "\u6b63\u5728\u52a0\u8f7d\u79bb\u7ebf\u8bcd\u5178"
        private set

    override suspend fun startSession(
        terms: List<LexiconTerm>,
        typingDictionary: List<TypingDictionaryEntry>,
        learning: List<PinyinLearning>
    ): Boolean = withContext(Dispatchers.Default) {
        sessionMutex.withLock {
            syncOverlay(terms, typingDictionary, learning)
            if (isReady) return@withLock true
            statusText = "\u6b63\u5728\u52a0\u8f7d\u79bb\u7ebf\u8bcd\u5178"
            val startup = runCatching {
                prepareFiles()
                startAndVerify()
            }
            startup.exceptionOrNull()?.let { error ->
                Log.e(TAG, "Unable to prepare or start the offline Rime bundle", error)
            }
            val ready = startup.getOrDefault(false)
            isReady = ready
            statusText = if (ready) {
                "Rime-Ice \u57fa\u7840\u8bcd\u5178\u5df2\u5c31\u7eea"
            } else {
                "\u79bb\u7ebf\u8bcd\u5178\u65e0\u6cd5\u52a0\u8f7d"
            }
            ready
        }
    }

    override suspend fun clear(): PinyinSessionState = nativeState {
        if (!isReady) return@nativeState PinyinSessionState()
        Rime.clearRimeComposition()
        snapshot()
    }

    override suspend fun input(value: String): PinyinSessionState = nativeState {
        if (!isReady || value.length != 1) return@nativeState PinyinSessionState()
        Rime.processRimeKey(value[0].code, 0)
        snapshot()
    }

    override suspend fun backspace(): PinyinSessionState = nativeState {
        if (!isReady) return@nativeState PinyinSessionState()
        Rime.processRimeKey(KEY_BACKSPACE, 0)
        snapshot()
    }

    override suspend fun restoreComposition(value: String): PinyinSessionState = nativeState {
        if (!isReady) return@nativeState PinyinSessionState()
        Rime.clearRimeComposition()
        value.lowercase().filter { it.isLetterOrDigit() || it == '\'' }.forEach {
            Rime.processRimeKey(it.code, 0)
        }
        snapshot()
    }

    override suspend fun selectCandidate(index: Int): PinyinSessionState = nativeState {
        if (!isReady || index < 0) return@nativeState PinyinSessionState()
        Rime.selectRimeCandidate(index, true)
        snapshot()
    }

    override suspend fun commitFirst(): PinyinSessionState = selectCandidate(0)

    override suspend fun currentState(): PinyinSessionState = nativeState {
        if (isReady) snapshot() else PinyinSessionState()
    }

    override suspend fun selectChineseKeyboardLayout(layout: ChineseKeyboardLayout): Boolean = nativeState {
        if (!isReady) return@nativeState false
        val schema = if (layout == ChineseKeyboardLayout.NINE_KEY) T9_SCHEMA_ID else SCHEMA_ID
        Rime.selectRimeSchema(schema).also { if (it) Rime.clearRimeComposition() }
    }

    override suspend fun syncProfessionalTerms(
        terms: List<LexiconTerm>,
        typingDictionary: List<TypingDictionaryEntry>
    ): Boolean = withContext(Dispatchers.Default) {
        sessionMutex.withLock {
            syncOverlay(terms, typingDictionary, migratedLearning.values.toList())
            true
        }
    }

    override suspend fun syncUserLearning(entries: List<PinyinLearning>): Boolean = withContext(Dispatchers.Default) {
        sessionMutex.withLock {
            migratedLearning = entries.associateBy { it.term }
            true
        }
    }

    /** librime userdb records native candidate selections. */
    override suspend fun learnCandidate(text: String): Boolean = false

    override suspend fun clearUserLearning(): Boolean = withContext(Dispatchers.Default) {
        sessionMutex.withLock {
            isReady = false
            statusText = "\u6b63\u5728\u6e05\u9664\u5b66\u4e60\u6570\u636e"
            val ready = runCatching {
                Rime.exitRime()
                userDir.deleteRecursively()
                userDir.mkdirs()
                // shared/build is immutable and stays available after the clear.
                startAndVerify()
            }.getOrDefault(false)
            isReady = ready
            statusText = if (ready) "Rime-Ice \u57fa\u7840\u8bcd\u5178\u5df2\u5c31\u7eea" else "\u8bcd\u5178\u52a0\u8f7d\u5931\u8d25"
            ready
        }
    }

    override suspend fun shutdown() = withContext(Dispatchers.Default) {
        sessionMutex.withLock {
            if (isReady) runCatching { Rime.exitRime() }
            isReady = false
            statusText = "\u79bb\u7ebf\u8bcd\u5178\u672a\u52a0\u8f7d"
        }
    }

    fun pinyinCodeForTerm(term: String, hint: String): String =
        normalizePinyin(hint).ifBlank { readingsForTerm(term.trim()) }

    override fun pinyinForDisplay(text: String): String = pinyinCodeForTerm(text, "")

    private suspend fun <T> nativeState(block: () -> T): T = withContext(Dispatchers.Default) {
        sessionMutex.withLock { block() }
    }

    private suspend fun startAndVerify(): Boolean {
        Rime.startupRime(sharedDir.absolutePath, userDir.absolutePath, ENGINE_VERSION, false)
        return waitForPrebuiltSchema()
    }

    private suspend fun waitForPrebuiltSchema(): Boolean {
        val compiledTable = File(sharedDir, "build/$SCHEMA_ID.table.bin")
        repeat(12) { attempt ->
            val tableReady = compiledTable.length() >= MIN_TABLE_BYTES
            val selected = tableReady && Rime.selectRimeSchema(SCHEMA_ID)
            // A selected schema can still point at a missing or incompatible
            // table. Query the prebuilt bundle before exposing the keyboard.
            val healthy = selected && Rime.processRimeKey('d'.code, 0) &&
                Rime.getRimeCandidates(0, 1).isNotEmpty()
            Rime.clearRimeComposition()
            if (healthy) {
                return true
            }
            val status = Rime.getRimeStatus()
            Log.w(
                TAG,
                "Rime startup attempt=${attempt + 1}; tableBytes=${compiledTable.length()}, " +
                    "selected=$selected, healthy=$healthy, schema=${status.schemaId}, disabled=${status.isDisabled}"
            )
            delay(100)
        }
        return false
    }

    private fun snapshot(): PinyinSessionState {
        val context = Rime.getRimeContext()
        val nativeCandidates = Rime.getRimeCandidates(0, MAX_CANDIDATES).mapIndexed { index, candidate ->
            PinyinCandidate(candidate.text, MAX_CANDIDATES - index.toDouble(), index)
        }
        val rawComposition = context.input.ifBlank { context.composition.preedit.orEmpty() }
        val overlay = overlayEntries.asSequence()
            .filter { it.matches(compactPinyin(rawComposition)) }
            .sortedByDescending { it.weight + migratedBoost(it.text) }
            .map { PinyinCandidate(it.text, it.weight.toDouble(), -1, directCommit = true) }
            .toList()
        val nativeOrdered = if (rawComposition.length == 1 && rawComposition[0].isLetter()) {
            nativeCandidates.sortedWith(compareBy<PinyinCandidate> { it.text.length }.thenByDescending { it.score })
        } else {
            nativeCandidates
        }
        return PinyinSessionState(
            preedit = rawComposition,
            candidates = (overlay + nativeOrdered).distinctBy { it.text }.take(MAX_CANDIDATES),
            committedText = Rime.getRimeCommit().text?.takeIf(String::isNotBlank)
        )
    }

    private fun prepareFiles() {
        sharedDir.mkdirs()
        userDir.mkdirs()
        val activeBundle = DictionaryPackManager(appContext).activeBundleDir()
        val bundleVersion = activeBundle?.let { "${it.parentFile?.name}:${it.name}:${it.lastModified()}" } ?: "builtin"
        val versionFile = File(sharedDir, ".weike_version")
        val expectedVersion = "$ENGINE_VERSION:$bundleVersion"
        val changed = versionFile.readTextOrEmpty() != expectedVersion
        if (changed) {
            sharedDir.deleteRecursively()
            sharedDir.mkdirs()
            // librime resolves prism/reverse data from user/build before its
            // shared fallback. Remove any tiny auto-generated prism so it
            // cannot shadow the release-built index shipped with the table.
            File(userDir, "build").deleteRecursively()
            if (activeBundle == null) {
                SHARED_FILES.forEach { copyAsset("rime/prebuilt/$it", File(sharedDir, it)) }
                // librime resolves deployed configs from shared/build before it
                // considers the user staging directory. Keeping these copies
                // alongside the table prevents automatic maintenance.
                SHARED_FILES.forEach { copyAsset("rime/prebuilt/$it", File(sharedDir, "build/$it")) }
                PREBUILT_BUILD_FILES.forEach { copyAsset("rime/prebuilt/$it", File(sharedDir, "build/$it")) }
                copyCompressedAsset("rime/prebuilt/weike_pinyin.table.bin.zip", File(sharedDir, "build/weike_pinyin.table.bin"))
            } else {
                DictionaryPackManager.ROOT_FILES.forEach { copyFile(File(activeBundle, it), File(sharedDir, it)) }
                DictionaryPackManager.ROOT_FILES.forEach { copyFile(File(activeBundle, it), File(sharedDir, "build/$it")) }
                DictionaryPackManager.BUILD_FILES.forEach {
                    copyFile(File(activeBundle, "build/$it"), File(sharedDir, "build/$it"))
                }
                DictionaryPackManager.AUXILIARY_FILES.forEach {
                    copyFile(File(activeBundle, it), File(sharedDir, it))
                }
            }
            versionFile.writeText(expectedVersion)
        }
        installUserBuildFiles(activeBundle, force = changed)
        DictionaryPackManager(appContext).restoreWanxiangSchemaIfEnabled()
        check(File(sharedDir, "build/$SCHEMA_ID.table.bin").length() >= MIN_TABLE_BYTES) {
            "Prebuilt Rime table is unavailable"
        }
    }

    /**
     * Rime gives user/build precedence for prism and reverse indexes. Its
     * maintenance can create a 512-byte empty prism before the first query;
     * always replace that placeholder with the matching release-built data.
     */
    private fun installUserBuildFiles(activeBundle: File?, force: Boolean) {
        val userBuild = File(userDir, "build")
        val prism = File(userBuild, "$SCHEMA_ID.prism.bin")
        if (!force && prism.length() >= MIN_PRISM_BYTES) return
        userBuild.mkdirs()
        if (activeBundle == null) {
            SHARED_FILES.forEach { copyAsset("rime/prebuilt/$it", File(userBuild, it)) }
            PREBUILT_BUILD_FILES.forEach { copyAsset("rime/prebuilt/$it", File(userBuild, it)) }
        } else {
            DictionaryPackManager.ROOT_FILES.forEach { copyFile(File(activeBundle, it), File(userBuild, it)) }
            DictionaryPackManager.BUILD_FILES.forEach {
                copyFile(File(activeBundle, "build/$it"), File(userBuild, it))
            }
        }
    }

    private fun syncOverlay(
        terms: List<LexiconTerm>,
        typingDictionary: List<TypingDictionaryEntry>,
        learning: List<PinyinLearning>
    ) {
        migratedLearning = learning.associateBy { it.term }
        val preferred = typingDictionary.map { OverlayEntry(it.term, pinyinCodeForTerm(it.term, it.hint), 10_000_000) }
        val imported = DictionaryPackManager(appContext).importedEntries().map { OverlayEntry(it.text, it.code, 5_000_000) }
        val professional = terms.map { OverlayEntry(it.term, pinyinCodeForTerm(it.term, it.hint), 1_000_000) }
        overlayEntries = (preferred + imported + professional).asSequence()
            .map { it.copy(text = it.text.trim(), code = compactPinyin(it.code)) }
            .filter { it.text.isNotBlank() && it.code.isNotBlank() }
            .distinctBy { it.text }
            .toList()
    }

    private fun migratedBoost(text: String): Int = migratedLearning[text]
        ?.let { kotlin.math.ln((it.useCount + 1).toDouble()).times(250_000).toInt() } ?: 0

    private fun normalizePinyin(value: String): String = value.lowercase()
        .replace(Regex("[^a-z' ]"), " ").trim().replace(Regex("\\s+"), " ")

    private fun compactPinyin(value: String): String = normalizePinyin(value).replace(" ", "").replace("'", "")

    private fun readingsForTerm(text: String): String = text.mapNotNull(termReadings()::get).joinToString(" ")

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

    private fun copyFile(source: File, destination: File) {
        require(source.isFile) { "Missing prebuilt dictionary file: ${source.name}" }
        destination.parentFile?.mkdirs()
        source.inputStream().use { input -> destination.outputStream().use(input::copyTo) }
    }

    private fun copyCompressedAsset(asset: String, destination: File) {
        destination.parentFile?.mkdirs()
        appContext.assets.open(asset).use { input ->
            ZipInputStream(input).use { zip ->
                check(zip.nextEntry != null) { "Prebuilt dictionary archive is corrupt" }
                destination.outputStream().use(zip::copyTo)
            }
        }
    }

    private data class OverlayEntry(val text: String, val code: String, val weight: Int) {
        fun matches(query: String) = query.isNotBlank() && (code == query || code.startsWith(query))
    }

    companion object {
        private const val TAG = "RimePinyinDecoder"
        const val SCHEMA_ID = "weike_pinyin"
        const val T9_SCHEMA_ID = "weike_t9"
        const val DICTIONARY_VERSION = "Rime-Ice base (8105 + base + others) 2026.07"
        const val ENGINE_VERSION_TEXT = "librime 1.17.0 + librime-octagram"
        // Bump whenever any table, prism, reverse database, or deployed config
        // is regenerated so existing installs atomically replace stale files.
        private const val ENGINE_VERSION = "weike-rime-ice-base-prebuilt-2026.07.20.6"
        private const val KEY_BACKSPACE = 0xff08
        private const val MAX_CANDIDATES = 96
        private const val MIN_TABLE_BYTES = 1_000_000L
        private const val MIN_PRISM_BYTES = 1_000L
        private val CHAR_READING = Regex("^U\\+([0-9A-Fa-f]+):\\s*(.+)$")
        private val SHARED_FILES = listOf("default.yaml", "weike_pinyin.schema.yaml", "weike_t9.schema.yaml")
        private val PREBUILT_BUILD_FILES = listOf(
            "weike_pinyin.prism.bin",
            "weike_t9.prism.bin",
            "weike_pinyin.reverse.bin"
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
            File(context.applicationContext.filesDir, "rime/shared/build/weike_pinyin.table.bin").exists()
        ) "\u5df2\u5c31\u7eea" else "\u672a\u52a0\u8f7d"
    }
}

private fun File.readTextOrEmpty(): String = if (exists()) readText() else ""
