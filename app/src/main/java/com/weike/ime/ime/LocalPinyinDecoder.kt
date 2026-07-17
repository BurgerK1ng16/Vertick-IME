package com.weike.ime.ime

import android.content.Context
import com.weike.ime.data.ChineseKeyboardLayout
import com.weike.ime.data.LexiconTerm
import com.weike.ime.data.TypingDictionaryEntry
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * The keyboard's always-available offline decoder.
 *
 * The full Rime-Ice-derived index is generated while building the APK and mapped
 * directly from assets. There is no on-device table compilation or YAML parsing.
 */
class LocalPinyinDecoder(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var composition = ""
    private var layout = ChineseKeyboardLayout.FULL
    private var customEntries: List<IndexedTerm> = emptyList()
    private val readingByCharacter = AtomicReference<Map<Char, String>>(FALLBACK_READINGS)
    private val index = AtomicReference<PrebuiltPinyinIndex?>(null)
    private val lookupCache = ConcurrentHashMap<String, List<RankedText>>()
    private val prefixCodeCache = ConcurrentHashMap<String, List<RankedCode>>()

    @Volatile var isReady: Boolean = true
        private set

    @Volatile var statusText: String = "离线拼音已就绪"
        private set

    suspend fun startSession(
        terms: List<LexiconTerm>,
        typingDictionary: List<TypingDictionaryEntry> = emptyList()
    ): Boolean {
        if (index.get() == null) {
            index.set(PrebuiltPinyinIndex.open(appContext))
            readingByCharacter.set(loadReadings())
        }
        syncProfessionalTerms(terms, typingDictionary)
        return true
    }

    suspend fun clear(): PinyinSessionState = synchronized(lock) {
        composition = ""
        PinyinSessionState()
    }

    suspend fun input(value: String): PinyinSessionState = synchronized(lock) {
        if (value.length == 1 && (value[0].isLetterOrDigit() || value[0] == '\'')) {
            composition += value.lowercase(Locale.ROOT)
        }
        stateLocked()
    }

    suspend fun backspace(): PinyinSessionState = synchronized(lock) {
        if (composition.isNotEmpty()) composition = composition.dropLast(1)
        stateLocked()
    }

    suspend fun currentState(): PinyinSessionState = synchronized(lock) { stateLocked() }

    /**
     * Rehydrates an uncommitted composition after Android recreates the IME view
     * for a configuration change. This is deliberately separate from input(): a
     * nine-key composition must retain its digit sequence rather than its visible
     * Pinyin spelling.
     */
    suspend fun restoreComposition(value: String): PinyinSessionState = synchronized(lock) {
        composition = value.lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() || it == '\'' }
        stateLocked()
    }

    suspend fun selectCandidate(index: Int): PinyinSessionState = synchronized(lock) {
        val selected = candidatesFor(composition).getOrNull(index)?.text
        composition = ""
        PinyinSessionState(committedText = selected)
    }

    suspend fun commitFirst(): PinyinSessionState = selectCandidate(0)

    suspend fun selectChineseKeyboardLayout(value: ChineseKeyboardLayout): Boolean {
        synchronized(lock) {
            // Re-selecting the active layout happens after a configuration change
            // and must not destroy a live composition. Only a user-initiated
            // layout switch clears the decoder state.
            if (layout != value) {
                layout = value
                composition = ""
            }
        }
        return true
    }

    suspend fun syncProfessionalTerms(
        terms: List<LexiconTerm>,
        typingDictionary: List<TypingDictionaryEntry> = emptyList()
    ): Boolean {
        val preferred = typingDictionary.map { IndexedTerm(it.term, it.hint, 10_000_000) }
        val general = terms.map { IndexedTerm(it.term, it.hint, 1_000_000) }
        customEntries = (preferred + general)
            .asSequence()
            .mapNotNull { entry ->
                val text = entry.text.trim()
                val code = pinyinCodeForTerm(text, entry.hint)
                if (text.isBlank() || code.isBlank()) null else entry.copy(text = text, code = normalizedCode(code))
            }
            .distinctBy { it.text }
            .toList()
        return true
    }

    suspend fun stageProfessionalTerms(
        terms: List<LexiconTerm>,
        typingDictionary: List<TypingDictionaryEntry> = emptyList()
    ): Boolean = syncProfessionalTerms(terms, typingDictionary)

    suspend fun clearUserLearning(): Boolean = true

    suspend fun shutdown() {
        lookupCache.clear()
        prefixCodeCache.clear()
    }

    fun pinyinCodeForTerm(term: String, hint: String): String {
        val explicit = normalizedCode(hint)
        if (explicit.isNotBlank()) return explicit
        val readings = readingByCharacter.get()
        return term.mapNotNull { character ->
            readings[character] ?: FALLBACK_READINGS[character]
        }.joinToString(" ")
    }

    private fun stateLocked(): PinyinSessionState = PinyinSessionState(
        // The editor must show a real composing spelling, never the internal T9
        // digit sequence. `64` is rendered as `ni`, while the decoder retains 64.
        preedit = displayPreedit(composition),
        candidates = candidatesFor(composition),
        rawComposition = composition
    )

    private fun candidatesFor(raw: String): List<PinyinCandidate> {
        val query = normalizedCode(raw)
        if (query.isBlank()) return emptyList()
        val results = ArrayList<RankedText>(MAX_CANDIDATES)
        val seen = HashSet<String>()
        fun add(text: String, score: Int) {
            val value = text.trim()
            if (value.isNotBlank() && seen.add(value) && results.size < MAX_CANDIDATES) {
                results += RankedText(value, score)
            }
        }

        customEntries.asSequence()
            .filter { matchesCode(query, it.code) || matchesCode(query, initialsFor(it.code)) }
            .sortedByDescending { it.weight }
            .forEach { add(it.text, it.weight) }

        CORE_PHRASES[query].orEmpty().forEach { add(it.text, it.weight) }
        fuzzyCorePhraseCandidates(query).forEach { add(it.text, it.weight) }
        fullCandidates(query).forEach { add(it.text, it.weight) }
        mixedInitialCandidates(query).forEach { add(it.text, it.weight) }
        correctedFullCandidates(query).forEach { add(it.text, it.weight) }
        // Exact keys are still first, but never make a single mistyped letter a
        // dead end. This is a bounded candidate-code search, not a full scan.
        fuzzyCandidates(query).forEach { add(it.text, it.weight) }
        // Initials are a separate index. A short form such as wsm must match
        // wei shen me (为什么) rather than being treated as an invalid syllable.
        buildInitialIndex(CORE_PHRASES)[query].orEmpty().forEach { add(it.text, it.weight) }
        initialCandidates(query).forEach { add(it.text, it.weight) }

        if (query.all(Char::isDigit)) {
            // T9 candidates must come from a segmented Pinyin sequence. Flattening
            // every syllable sharing the same digits made 64 rank unrelated words
            // above 你 (ni). Exact phrase codes are still promoted first.
            t9Candidates(query).forEach { add(it.text, it.weight) }
            buildT9Index(CORE_PHRASES)[query].orEmpty().forEach { add(it.text, it.weight) }
            decodeT9Syllables(query).forEach { parts ->
                combinations(parts, limit = 8).forEachIndexed { index, text ->
                    add(text, 1_500_000 - index)
                }
            }
            return results.mapIndexed { index, value ->
                PinyinCandidate(value.text, value.weight.toDouble(), index, directCommit = true)
            }
        }

        val decoded = decodeSyllables(query)
        sentenceCandidates(decoded).forEach { candidate -> add(candidate.text, candidate.weight) }
        decoded.forEach { parts ->
            combinations(parts, limit = 4).forEachIndexed { index, text ->
                add(text, 100_000 - index)
            }
        }

        if (query.length == 1) {
            initialCharacters(query[0]).forEach { add(it.text, it.weight) }
        }
        // Raw code is deliberately explicit and last. It keeps invalid or English
        // abbreviations typeable without pretending that it is a Chinese match.
        add(raw, -1)
        return results.mapIndexed { index, value ->
            PinyinCandidate(value.text, value.weight.toDouble(), index, directCommit = true)
        }
    }

    /**
     * A bounded Viterbi-style word search over one Pinyin segmentation. The
     * lexicon frequency is the language model; longer high-frequency words earn
     * a small boundary bonus so a continuous sentence is not reduced to chars.
     */
    private fun sentenceCandidates(segmentations: List<List<String>>): List<RankedText> {
        val results = ArrayList<RankedText>()
        segmentations.forEach { syllables ->
            if (syllables.isEmpty() || syllables.size > MAX_SENTENCE_SYLLABLES) return@forEach
            var beams = listOf(SentenceBeam(0, "", 0.0))
            while (beams.isNotEmpty()) {
                val next = ArrayList<SentenceBeam>()
                beams.forEach { beam ->
                    if (beam.offset == syllables.size) {
                        results += RankedText(beam.text, (beam.score * SCORE_SCALE).toInt())
                        return@forEach
                    }
                    val maxEnd = minOf(syllables.size, beam.offset + MAX_WORD_SYLLABLES)
                    for (end in maxEnd downTo beam.offset + 1) {
                        val code = syllables.subList(beam.offset, end).joinToString("")
                        fullCandidates(code).take(MAX_WORD_OPTIONS).forEach { word ->
                            val wordScore = kotlin.math.ln((word.weight.coerceAtLeast(1) + 1).toDouble()) +
                                (end - beam.offset) * WORD_LENGTH_BONUS
                            next += SentenceBeam(end, beam.text + word.text, beam.score + wordScore)
                        }
                    }
                }
                if (next.isEmpty()) break
                beams = next.sortedByDescending { it.score }.take(MAX_SENTENCE_BEAMS)
            }
        }
        return results.sortedByDescending { it.weight }.distinctBy { it.text }.take(MAX_CANDIDATES)
    }

    internal fun fuzzyCandidatesForTest(query: String): List<String> = synchronized(lock) {
        fuzzyCandidates(normalizedCode(query)).map { it.text }
    }

    private fun fuzzyCorePhraseCandidates(query: String): List<RankedText> {
        if (query.length < MIN_FUZZY_QUERY_LENGTH) return emptyList()
        val maximumDistance = when {
            query.length >= 9 -> 2
            query.length >= 5 -> 1
            else -> 0
        }
        return (CORE_PHRASES + COMMON_SPOKEN_PHRASES).entries.flatMap { (code, entries) ->
            val syllables = decodeCode(code, CORE_SYLLABLES)
            val distance = PinyinFuzzyMatcher.bestDistance(query, syllables, maximumDistance)
                ?: return@flatMap emptyList()
            entries.map { entry ->
                RankedText(entry.text, entry.weight + CORE_FUZZY_SCORE_BONUS - distance * CORE_FUZZY_DISTANCE_PENALTY)
            }
        }.sortedByDescending { it.weight }
            .take(MAX_CANDIDATES)
    }

    /** Supports mixed forms such as full `zai` followed by initial `g` + `m`. */
    private fun mixedInitialCandidates(query: String): List<RankedText> {
        if (query.length < MIN_FUZZY_QUERY_LENGTH || query.all(Char::isDigit)) return emptyList()
        val abbreviations = abbreviatedForms(query)
        if (abbreviations.isEmpty()) return emptyList()
        return abbreviations.flatMap { abbreviation ->
            initialCandidates(abbreviation).map { candidate ->
                RankedText(candidate.text, candidate.weight + MIXED_INITIAL_SCORE_BONUS)
            }
        }.sortedByDescending { it.weight }
            .distinctBy { it.text }
            .take(MAX_CANDIDATES)
    }

    /**
     * Corrects one key-level error before querying the exact index. This avoids
     * scanning the full dictionary and keeps each keystroke bounded. It covers
     * substitution (including m/n), an accidental extra letter, and one omitted
     * letter. Exact typed codes always rank above this recovery path.
     */
    private fun correctedFullCandidates(query: String): List<RankedText> {
        if (query.length !in MIN_CORRECTION_QUERY_LENGTH..MAX_CORRECTION_QUERY_LENGTH) return emptyList()
        val results = ArrayList<RankedText>(MAX_CANDIDATES)
        val seen = HashSet<String>()
        oneEditCodes(query).forEach { corrected ->
            uncachedFullCandidates(corrected).forEach { candidate ->
                if (seen.add(candidate.text)) {
                    results += RankedText(candidate.text, candidate.weight + CORRECTION_SCORE_BONUS)
                }
            }
        }
        return results.sortedByDescending { it.weight }.take(MAX_CANDIDATES)
    }

    internal fun abbreviatedFormsForTest(query: String): Set<String> = abbreviatedForms(query)

    private fun abbreviatedForms(query: String): Set<String> {
        val results = LinkedHashSet<String>()
        fun walk(offset: Int, output: StringBuilder) {
            if (results.size >= MAX_MIXED_INITIAL_FORMS) return
            if (offset == query.length) {
                if (output.length >= 2 && output.length < query.length) results += output.toString()
                return
            }
            // A single letter may be an abbreviation for the current syllable.
            output.append(query[offset])
            walk(offset + 1, output)
            output.setLength(output.length - 1)
            val end = minOf(query.length, offset + MAX_SYLLABLE_LENGTH)
            for (cursor in end downTo offset + 2) {
                val syllable = query.substring(offset, cursor)
                if (syllable !in CORE_SYLLABLES) continue
                output.append(syllable[0])
                walk(cursor, output)
                output.setLength(output.length - 1)
            }
        }
        walk(0, StringBuilder())
        return results
    }

    private fun oneEditCodes(query: String): List<String> {
        val values = ArrayList<String>(MAX_CORRECTION_CODES)
        val emitted = HashSet<String>()
        fun add(value: String) {
            if (values.size < MAX_CORRECTION_CODES && value != query && value.isNotBlank() && emitted.add(value)) {
                values += value
            }
        }
        // Deleting a character corrects an accidental extra key.
        query.indices.forEach { index -> add(query.removeRange(index, index + 1)) }
        // Substitution is limited to adjacent keys plus the common m/n swap.
        query.forEachIndexed { index, character ->
            keyboardNeighbors(character).forEach { replacement ->
                add(query.substring(0, index) + replacement + query.substring(index + 1))
            }
        }
        // Insertions repair an omitted nearby key without generating all 26^n forms.
        for (index in 0..query.length) {
            insertionNeighbors(query, index).forEach { inserted ->
                add(query.substring(0, index) + inserted + query.substring(index))
            }
        }
        return values
    }

    private fun keyboardNeighbors(character: Char): CharArray = when (character) {
        'a' -> charArrayOf('s', 'q', 'z')
        'b' -> charArrayOf('v', 'g', 'h', 'n')
        'c' -> charArrayOf('x', 'd', 'f', 'v')
        'd' -> charArrayOf('s', 'e', 'r', 'f', 'c', 'x')
        'e' -> charArrayOf('w', 'r', 'd')
        'f' -> charArrayOf('d', 'r', 't', 'g', 'v', 'c')
        'g' -> charArrayOf('f', 't', 'y', 'h', 'b', 'v')
        'h' -> charArrayOf('g', 'y', 'u', 'j', 'n', 'b')
        'i' -> charArrayOf('u', 'o', 'k')
        'j' -> charArrayOf('h', 'u', 'i', 'k', 'm', 'n')
        'k' -> charArrayOf('j', 'i', 'o', 'l', 'm')
        'l' -> charArrayOf('k', 'o', 'p')
        'm' -> charArrayOf('n', 'j', 'k')
        'n' -> charArrayOf('b', 'h', 'j', 'm')
        'o' -> charArrayOf('i', 'p', 'l', 'k')
        'p' -> charArrayOf('o', 'l')
        'q' -> charArrayOf('w', 'a')
        'r' -> charArrayOf('e', 't', 'f', 'd')
        's' -> charArrayOf('a', 'w', 'e', 'd', 'x', 'z')
        't' -> charArrayOf('r', 'y', 'g', 'f')
        'u' -> charArrayOf('y', 'i', 'j', 'h')
        'v' -> charArrayOf('c', 'f', 'g', 'b')
        'w' -> charArrayOf('q', 'e', 's', 'a')
        'x' -> charArrayOf('z', 's', 'd', 'c')
        'y' -> charArrayOf('t', 'u', 'h', 'g')
        'z' -> charArrayOf('a', 's', 'x')
        else -> charArrayOf()
    }

    private fun insertionNeighbors(query: String, index: Int): CharArray {
        val left = query.getOrNull(index - 1)
        val right = query.getOrNull(index)
        return ((left?.let(::keyboardNeighbors) ?: charArrayOf()) +
            (right?.let(::keyboardNeighbors) ?: charArrayOf()))
            .distinct()
            .take(MAX_INSERTION_NEIGHBORS)
            .toCharArray()
    }

    private fun fuzzyCandidates(query: String): List<RankedText> {
        if (query.length < MIN_FUZZY_QUERY_LENGTH || query.all(Char::isDigit)) return emptyList()
        val maximumDistance = when {
            query.length >= 9 -> 2
            query.length >= 5 -> 1
            else -> 0
        }
        val results = ArrayList<RankedText>(MAX_CANDIDATES)
        val seen = HashSet<String>()
        candidateCodesFor(query).forEach { entry ->
            val score = when {
                entry.code.startsWith(query) -> COMPLETION_SCORE_BONUS - (entry.code.length - query.length) * 1_000
                else -> {
                    val syllables = decodeCode(entry.code, CORE_SYLLABLES)
                    val distance = PinyinFuzzyMatcher.bestDistance(query, syllables, maximumDistance)
                        ?: return@forEach
                    FUZZY_SCORE_BONUS - distance * FUZZY_DISTANCE_PENALTY -
                        kotlin.math.abs(entry.code.length - query.length) * 250
                }
            }
            entry.candidates.forEach { candidate ->
                if (seen.add(candidate.text)) results += RankedText(candidate.text, candidate.weight + score)
            }
        }
        return results.sortedByDescending { it.weight }.take(MAX_CANDIDATES)
    }

    private fun candidateCodesFor(query: String): List<RankedCode> {
        val cacheKey = "p:$query"
        if (prefixCodeCache.size >= MAX_PREFIX_CACHE_ENTRIES) prefixCodeCache.clear()
        return prefixCodeCache.getOrPut(cacheKey) {
            val anchors = linkedSetOf<String>()
            // The whole query is the normal completion path. Removing a few tail
            // characters covers an error in the last syllable; the first complete
            // syllable keeps longer inputs recoverable after an earlier typo.
            for (trim in 0..MAX_PREFIX_BACKTRACK) {
                query.dropLast(trim).takeIf { it.length >= MIN_PREFIX_LENGTH }?.let(anchors::add)
            }
            longestLeadingSyllable(query)?.let(anchors::add)
            val unique = LinkedHashMap<String, RankedCode>()
            anchors.sortedByDescending(String::length).forEach { anchor ->
                fullPrefixCandidates(anchor, MAX_PREFIX_RECORDS_PER_ANCHOR).forEach { entry ->
                    unique.putIfAbsent(entry.code, entry)
                }
            }
            unique.values.take(MAX_FUZZY_CODE_RECORDS)
        }
    }

    private fun longestLeadingSyllable(query: String): String? =
        (minOf(query.length, MAX_SYLLABLE_LENGTH) downTo 1)
            .asSequence()
            .map { query.substring(0, it) }
            .firstOrNull { it in CORE_SYLLABLES }

    private fun fullPrefixCandidates(prefix: String, limit: Int): List<RankedCode> =
        index.get()?.fullPrefix(prefix, limit).orEmpty().map { record ->
            RankedCode(record.code, record.candidates.map { RankedText(it.text, it.weight) })
        }

    private fun matchesCode(query: String, code: String): Boolean {
        if (query.isBlank() || code.isBlank()) return false
        return query == keyboardCode(code)
    }

    private fun keyboardCode(value: String): String = if (layout != ChineseKeyboardLayout.NINE_KEY) {
        value
    } else value.map { character ->
        when (character) {
            in 'a'..'c' -> '2'
            in 'd'..'f' -> '3'
            in 'g'..'i' -> '4'
            in 'j'..'l' -> '5'
            in 'm'..'o' -> '6'
            in 'p'..'s' -> '7'
            in 't'..'v' -> '8'
            in 'w'..'z' -> '9'
            else -> character
        }
    }.joinToString("")

    private fun decodeSyllables(query: String): List<List<String>> {
        if (query.all(Char::isDigit)) return emptyList()
        val known = CORE_SYLLABLES
        val memo = HashMap<Int, List<List<String>>>()
        fun walk(offset: Int): List<List<String>> {
            if (offset == query.length) return listOf(emptyList())
            memo[offset]?.let { return it }
            val values = ArrayList<List<String>>()
            val end = minOf(query.length, offset + MAX_SYLLABLE_LENGTH)
            for (cursor in end downTo offset + 1) {
                val part = query.substring(offset, cursor)
                if (part !in known) continue
                for (tail in walk(cursor)) {
                    values += listOf(part) + tail
                    if (values.size >= MAX_SEGMENTATIONS) break
                }
                if (values.size >= MAX_SEGMENTATIONS) break
            }
            return values.also { memo[offset] = it }
        }
        return walk(0)
    }

    private fun decodeT9Syllables(query: String): List<List<String>> {
        val known = CORE_SYLLABLES
        val candidatesByDigits = known.asSequence()
            .filter { t9Code(it) in query }
            .groupBy(::t9Code)
            .mapValues { (_, values) ->
                values.sortedByDescending { syllableScore(it) }.take(MAX_T9_SYLLABLES_PER_KEY)
            }
        val memo = HashMap<Int, List<List<String>>>()
        fun walk(offset: Int): List<List<String>> {
            if (offset == query.length) return listOf(emptyList())
            memo[offset]?.let { return it }
            val values = ArrayList<List<String>>()
            for (end in minOf(query.length, offset + MAX_T9_DIGITS_PER_SYLLABLE) downTo offset + 1) {
                val digits = query.substring(offset, end)
                for (syllable in candidatesByDigits[digits].orEmpty()) {
                    for (tail in walk(end)) {
                        values += listOf(syllable) + tail
                        if (values.size >= MAX_SEGMENTATIONS) break
                    }
                    if (values.size >= MAX_SEGMENTATIONS) break
                }
                if (values.size >= MAX_SEGMENTATIONS) break
            }
            return values.also { memo[offset] = it }
        }
        return walk(0)
    }

    private fun displayPreedit(raw: String): String {
        if (raw.isBlank() || !raw.all(Char::isDigit)) return raw
        return decodeT9Syllables(raw).firstOrNull()?.joinToString("").orEmpty()
    }

    private fun syllableScore(syllable: String): Int = fullCandidates(syllable)
        .firstOrNull()
        ?.weight
        ?: 0

    private fun combinations(parts: List<String>, limit: Int): List<String> {
        if (parts.isEmpty() || parts.size > 6) return emptyList()
        var values = listOf("")
        for (part in parts) {
            val options = fullCandidates(part).filter { it.text.length == 1 }.take(3)
            if (options.isEmpty()) return emptyList()
            values = values.flatMap { prefix -> options.map { option -> prefix + option.text } }.take(limit)
        }
        return values
    }

    private fun initialCharacters(initial: Char): List<RankedText> = initialCandidates(initial.toString())
        .filter { it.text.length == 1 }
        .take(12)

    private fun initialsFor(code: String): String {
        return decodeCode(code, CORE_SYLLABLES).joinToString("") { it.take(1) }
    }

    private fun decodeCode(code: String, known: Set<String>): List<String> {
        if (code.isBlank()) return emptyList()
        val values = ArrayList<String>()
        var offset = 0
        while (offset < code.length) {
            val next = (minOf(code.length, offset + MAX_SYLLABLE_LENGTH) downTo offset + 1)
                .asSequence()
                .map { code.substring(offset, it) }
                .firstOrNull { it in known } ?: return emptyList()
            values += next
            offset += next.length
        }
        return values
    }

    private fun loadReadings(): Map<Char, String> {
        val readings = HashMap(readingByCharacter.get())
        appContext.assets.open("pinyin/pinyin.txt").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val match = PINYIN_RE.matchEntire(line.substringBefore('#').trim()) ?: return@forEach
                val codePoint = match.groupValues[1].toIntOrNull(16) ?: return@forEach
                if (codePoint !in Char.MIN_VALUE.code..Char.MAX_VALUE.code) return@forEach
                val character = codePoint.toChar()
                val reading = normalizedCode(match.groupValues[2].substringBefore(','))
                if (reading.isNotBlank()) readings[character] = reading
            }
        }
        return readings
    }

    private fun fullCandidates(code: String): List<RankedText> = cached("f:$code") {
        index.get()?.full(code).orEmpty().map { RankedText(it.text, it.weight) }
    }

    private fun uncachedFullCandidates(code: String): List<RankedText> =
        index.get()?.full(code).orEmpty().map { RankedText(it.text, it.weight) }

    private fun initialCandidates(code: String): List<RankedText> = cached("i:$code") {
        index.get()?.initials(code).orEmpty().map { RankedText(it.text, it.weight) }
    }

    private fun t9Candidates(code: String): List<RankedText> = cached("t:$code") {
        index.get()?.t9(code).orEmpty().map { RankedText(it.text, it.weight) }
    }

    private fun cached(key: String, load: () -> List<RankedText>): List<RankedText> =
        lookupCache.getOrPut(key, load)

    private fun buildInitialIndex(source: Map<String, List<RankedText>>): Map<String, List<RankedText>> {
        val values = HashMap<String, MutableList<RankedText>>()
        source.forEach { (code, entries) ->
            val initials = initialsForCode(code)
            if (initials.length < 2) return@forEach
            values.getOrPut(initials) { ArrayList() }.addAll(entries)
        }
        return values.mapValues { (_, entries) ->
            entries.sortedByDescending { it.weight }.distinctBy { it.text }.take(12)
        }
    }

    private fun buildT9Index(source: Map<String, List<RankedText>>): Map<String, List<RankedText>> {
        val values = HashMap<String, MutableList<RankedText>>()
        source.forEach { (code, entries) ->
            values.getOrPut(t9Code(code)) { ArrayList() }.addAll(entries)
        }
        return values.mapValues { (_, entries) ->
            entries.sortedByDescending { it.weight }.distinctBy { it.text }.take(12)
        }
    }

    private fun initialsForCode(code: String): String = decodeCode(code, CORE_SYLLABLES)
        .joinToString("") { it.take(1) }

    private fun normalizedCode(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .filter { it in 'a'..'z' || it in '0'..'9' }

    private fun t9Code(value: String): String = value.map { character ->
        when (character) {
            in 'a'..'c' -> '2'
            in 'd'..'f' -> '3'
            in 'g'..'i' -> '4'
            in 'j'..'l' -> '5'
            in 'm'..'o' -> '6'
            in 'p'..'s' -> '7'
            in 't'..'v' -> '8'
            in 'w'..'z' -> '9'
            else -> character
        }
    }.joinToString("")

    private data class IndexedTerm(val text: String, val hint: String, val weight: Int, val code: String = "")
    private data class RankedText(val text: String, val weight: Int)
    private data class RankedCode(val code: String, val candidates: List<RankedText>)
    private data class SentenceBeam(val offset: Int, val text: String, val score: Double)

    companion object {
        const val DICTIONARY_VERSION = "Full offline Pinyin index (base + ext + Tencent + 8105)"
        private const val MAX_CANDIDATES = 18
        private const val MIN_FUZZY_QUERY_LENGTH = 3
        private const val MIN_CORRECTION_QUERY_LENGTH = 4
        private const val MAX_CORRECTION_QUERY_LENGTH = 24
        private const val MAX_CORRECTION_CODES = 96
        private const val MAX_INSERTION_NEIGHBORS = 4
        private const val MAX_MIXED_INITIAL_FORMS = 64
        private const val MAX_PREFIX_CACHE_ENTRIES = 128
        private const val MIN_PREFIX_LENGTH = 2
        private const val MAX_PREFIX_BACKTRACK = 4
        private const val MAX_PREFIX_RECORDS_PER_ANCHOR = 180
        private const val MAX_FUZZY_CODE_RECORDS = 420
        private const val COMPLETION_SCORE_BONUS = 1_600_000
        private const val CORE_FUZZY_SCORE_BONUS = 2_000_000
        private const val CORE_FUZZY_DISTANCE_PENALTY = 450_000
        private const val MIXED_INITIAL_SCORE_BONUS = 1_050_000
        private const val CORRECTION_SCORE_BONUS = 650_000
        private const val FUZZY_SCORE_BONUS = 1_250_000
        private const val FUZZY_DISTANCE_PENALTY = 350_000
        private const val MAX_SEGMENTATIONS = 6
        private const val MAX_SYLLABLE_LENGTH = 6
        private const val MAX_T9_DIGITS_PER_SYLLABLE = 6
        private const val MAX_T9_SYLLABLES_PER_KEY = 8
        private const val MAX_SENTENCE_SYLLABLES = 10
        private const val MAX_WORD_SYLLABLES = 5
        private const val MAX_WORD_OPTIONS = 4
        private const val MAX_SENTENCE_BEAMS = 12
        private const val WORD_LENGTH_BONUS = 0.34
        private const val SCORE_SCALE = 10_000.0
        private val PINYIN_RE = Regex("^U\\+([0-9A-Fa-f]+):\\s*(.+)$")
        private val CORE_CHARACTERS: Map<String, List<RankedText>> = mapOf(
            "a" to listOf("啊", "阿", "呀"), "ai" to listOf("爱", "矮", "哎"),
            "an" to listOf("安", "按", "案"), "ba" to listOf("吧", "把", "八"),
            "bei" to listOf("被", "北", "倍"), "bu" to listOf("不", "步", "部"),
            "de" to listOf("的", "得", "地"), "d" to listOf("的", "对", "都"),
            "dui" to listOf("对", "队", "堆", "兑"), "en" to listOf("恩", "嗯"),
            "ge" to listOf("个", "给", "各"), "hao" to listOf("好", "号", "浩", "豪"),
            "hen" to listOf("很", "狠", "恨"), "ji" to listOf("及", "几", "即"),
            "jie" to listOf("接", "解", "界", "节", "捷", "杰"), "jin" to listOf("今", "进", "近"),
            "ke" to listOf("可", "科", "刻", "课", "客"), "le" to listOf("了", "乐", "勒"),
            "ma" to listOf("吗", "妈", "马"), "men" to listOf("们", "门", "闷"),
            "na" to listOf("那", "拿", "哪"), "ne" to listOf("呢", "那", "哪"),
            "n" to listOf("你", "呢", "那", "能"), "ni" to listOf("你", "呢", "尼", "妮", "拟"),
            "shi" to listOf("是", "时", "事", "市", "十"), "tian" to listOf("天", "田", "填"),
            "wei" to listOf("为", "维", "位", "未", "伟"), "wo" to listOf("我", "握", "窝"),
            "xiang" to listOf("想", "向", "相", "像"), "xin" to listOf("新", "心", "信"),
            "yi" to listOf("一", "以", "已"), "zhang" to listOf("张", "章", "长", "掌"),
            "zhong" to listOf("中", "种", "重", "终")
        ).mapValues { (_, values) -> values.mapIndexed { index, text -> RankedText(text, 1_000_000 - index) } }
        private val CORE_PHRASES: Map<String, List<RankedText>> = mapOf(
            "nihao" to listOf("你好", "你号"),
            "weixin" to listOf("微信"),
            "weike" to listOf("维刻"),
            "weishenme" to listOf("为什么"),
            "shenme" to listOf("什么"),
            "women" to listOf("我们"),
            "womenjintian" to listOf("我们今天"),
            "duibuqi" to listOf("对不起"),
            "zhangenjie" to listOf("张恩捷")
        ).mapValues { (_, values) -> values.mapIndexed { index, text -> RankedText(text, 2_000_000 - index) } }
        // A compact spoken-language model fills the gap between a source
        // dictionary (which optimizes coverage) and the phrases people expect
        // from an IME. Keep entries in code so they participate in fuzzy matching
        // even if a source table later drops a colloquial spelling.
        private val COMMON_SPOKEN_PHRASES: Map<String, List<RankedText>> = mapOf(
            "zaiganma" to listOf("在干嘛", "在干吗"),
            "zaiguomao" to listOf("在国贸")
        ).mapValues { (_, values) -> values.mapIndexed { index, text -> RankedText(text, 1_900_000 - index) } }
        private val FALLBACK_READINGS = mapOf(
            '张' to "zhang", '恩' to "en", '捷' to "jie", '维' to "wei", '刻' to "ke",
            '你' to "ni", '好' to "hao", '我' to "wo", '们' to "men", '今' to "jin", '天' to "tian"
        )
        private val CORE_SYLLABLES = (CORE_CHARACTERS.keys + listOf(
            "ba", "bai", "ban", "bang", "bao", "bei", "ben", "bi", "bian", "biao", "bie", "bin", "bing", "bo", "bu",
            "ca", "cai", "can", "cang", "cao", "ce", "cen", "ceng", "cha", "chai", "chan", "chang", "chao", "che", "chen", "cheng", "chi", "chong", "chou", "chu", "chuai", "chuan", "chuang", "chui", "chun", "chuo", "ci", "cong", "cou", "cu", "cuan", "cui", "cun", "cuo",
            "da", "dai", "dan", "dang", "dao", "de", "dei", "deng", "di", "dian", "diao", "die", "ding", "diu", "dong", "dou", "du", "duan", "dui", "dun", "duo",
            "e", "ei", "en", "eng", "er", "fa", "fan", "fang", "fei", "fen", "feng", "fo", "fou", "fu",
            "ga", "gai", "gan", "gang", "gao", "ge", "gei", "gen", "geng", "gong", "gou", "gu", "gua", "guai", "guan", "guang", "gui", "gun", "guo",
            "ha", "hai", "han", "hang", "hao", "he", "hei", "hen", "heng", "hong", "hou", "hu", "hua", "huai", "huan", "huang", "hui", "hun", "huo",
            "ji", "jia", "jian", "jiang", "jiao", "jie", "jin", "jing", "jiong", "jiu", "ju", "juan", "jue", "jun",
            "ka", "kai", "kan", "kang", "kao", "ke", "ken", "keng", "kong", "kou", "ku", "kua", "kuai", "kuan", "kuang", "kui", "kun", "kuo",
            "la", "lai", "lan", "lang", "lao", "le", "lei", "leng", "li", "lia", "lian", "liang", "liao", "lie", "lin", "ling", "liu", "lo", "long", "lou", "lu", "luan", "lue", "lun", "luo", "lv",
            "ma", "mai", "man", "mang", "mao", "me", "mei", "men", "meng", "mi", "mian", "miao", "mie", "min", "ming", "miu", "mo", "mou", "mu",
            "na", "nai", "nan", "nang", "nao", "ne", "nei", "nen", "neng", "ni", "nian", "niang", "niao", "nie", "nin", "ning", "niu", "nong", "nou", "nu", "nuan", "nue", "nuo", "nv",
            "o", "ou", "pa", "pai", "pan", "pang", "pao", "pei", "pen", "peng", "pi", "pian", "piao", "pie", "pin", "ping", "po", "pou", "pu",
            "qi", "qia", "qian", "qiang", "qiao", "qie", "qin", "qing", "qiong", "qiu", "qu", "quan", "que", "qun",
            "ran", "rang", "rao", "re", "ren", "reng", "ri", "rong", "rou", "ru", "ruan", "rui", "run", "ruo",
            "sa", "sai", "san", "sang", "sao", "se", "sen", "seng", "sha", "shai", "shan", "shang", "shao", "she", "shei", "shen", "sheng", "shi", "shou", "shu", "shua", "shuai", "shuan", "shuang", "shui", "shun", "shuo", "si", "song", "sou", "su", "suan", "sui", "sun", "suo",
            "ta", "tai", "tan", "tang", "tao", "te", "teng", "ti", "tian", "tiao", "tie", "ting", "tong", "tou", "tu", "tuan", "tui", "tun", "tuo",
            "wa", "wai", "wan", "wang", "wei", "wen", "weng", "wo", "wu",
            "xi", "xia", "xian", "xiang", "xiao", "xie", "xin", "xing", "xiong", "xiu", "xu", "xuan", "xue", "xun",
            "ya", "yan", "yang", "yao", "ye", "yi", "yin", "ying", "yo", "yong", "you", "yu", "yuan", "yue", "yun",
            "za", "zai", "zan", "zang", "zao", "ze", "zei", "zen", "zeng", "zha", "zhai", "zhan", "zhang", "zhao", "zhe", "zhei", "zhen", "zheng", "zhi", "zhong", "zhou", "zhu", "zhua", "zhuai", "zhuan", "zhuang", "zhui", "zhun", "zhuo", "zi", "zong", "zou", "zu", "zuan", "zui", "zun", "zuo"
        )).toSet()
    }
}
