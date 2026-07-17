package com.weike.ime.ime

import android.content.Context
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Read-only VPI4 dictionary stored in the APK. It is mapped directly from the
 * uncompressed asset, so opening the IME does not parse YAML or build tables.
 */
class PrebuiltPinyinIndex private constructor(private val buffer: ByteBuffer) {
    private val poolOffset: Long
    private val sections: LongArray

    init {
        require(buffer.getInt(0) == MAGIC) { "Unsupported Pinyin index" }
        require(buffer.getInt(Int.SIZE_BYTES) == VERSION) { "Unsupported Pinyin index version" }
        poolOffset = buffer.getLong(POOL_OFFSET)
        sections = LongArray(SECTION_COUNT) { index ->
            buffer.getLong(SECTIONS_OFFSET + index * Long.SIZE_BYTES)
        }
    }

    fun full(code: String): List<IndexedCandidate> = find(FULL, code)

    /**
     * Returns nearby complete Pinyin codes for completion and typo recovery.
     * The records are sorted in the on-disk index, so this reads only the matching
     * range instead of scanning the 1.8M-entry text pool.
     */
    fun fullPrefix(code: String, maxRecords: Int): List<IndexedKeyCandidates> =
        findPrefix(FULL, code, maxRecords)

    fun initials(code: String): List<IndexedCandidate> = find(INITIALS, code)

    fun t9(code: String): List<IndexedCandidate> = find(T9, code)

    private fun find(section: Int, key: String): List<IndexedCandidate> {
        val start = sections[section].toInt()
        if (start <= 0 || start >= buffer.limit()) return emptyList()
        val count = buffer.getInt(start)
        val offsets = start + Int.SIZE_BYTES
        var low = 0
        var high = count - 1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val offset = buffer.getLong(offsets + middle * Long.SIZE_BYTES).toInt()
            val comparison = compareKey(offset, key)
            when {
                comparison < 0 -> low = middle + 1
                comparison > 0 -> high = middle - 1
                else -> return readCandidates(offset)
            }
        }
        return emptyList()
    }

    internal fun findPrefix(section: Int, prefix: String, maxRecords: Int): List<IndexedKeyCandidates> {
        if (prefix.isBlank() || maxRecords <= 0) return emptyList()
        val start = sections[section].toInt()
        if (start <= 0 || start >= buffer.limit()) return emptyList()
        val count = buffer.getInt(start)
        val offsets = start + Int.SIZE_BYTES
        var low = 0
        var high = count
        // Lower bound for the first key that is not lexicographically below prefix.
        while (low < high) {
            val middle = (low + high).ushr(1)
            val offset = buffer.getLong(offsets + middle * Long.SIZE_BYTES).toInt()
            if (compareKey(offset, prefix) < 0) low = middle + 1 else high = middle
        }
        val results = ArrayList<IndexedKeyCandidates>(minOf(maxRecords, 32))
        var index = low
        while (index < count && results.size < maxRecords) {
            val offset = buffer.getLong(offsets + index * Long.SIZE_BYTES).toInt()
            val key = readKey(offset)
            if (!key.startsWith(prefix)) break
            results += IndexedKeyCandidates(key, readCandidates(offset))
            index += 1
        }
        return results
    }

    private fun compareKey(offset: Int, expected: String): Int {
        return readKey(offset).compareTo(expected)
    }

    private fun readKey(offset: Int): String {
        val length = buffer.getShort(offset).toInt() and 0xffff
        val actual = ByteArray(length)
        val duplicate = buffer.duplicate().order(ByteOrder.BIG_ENDIAN)
        duplicate.position(offset + Short.SIZE_BYTES)
        duplicate.get(actual)
        return actual.toString(Charsets.UTF_8)
    }

    private fun readCandidates(offset: Int): List<IndexedCandidate> {
        val keyLength = buffer.getShort(offset).toInt() and 0xffff
        var cursor = offset + Short.SIZE_BYTES + keyLength
        val count = buffer.get(cursor).toInt() and 0xff
        cursor += 1
        return List(count) { readPoolEntry(buffer.getInt(cursor).also { cursor += Int.SIZE_BYTES }) }
    }

    private fun readPoolEntry(id: Int): IndexedCandidate {
        val pool = poolOffset.toInt()
        val count = buffer.getInt(pool)
        if (id !in 0 until count) return IndexedCandidate("", 0)
        val offsets = pool + Int.SIZE_BYTES
        val offset = buffer.getLong(offsets + id * Long.SIZE_BYTES).toInt()
        val weight = buffer.getInt(offset)
        val length = buffer.getShort(offset + Int.SIZE_BYTES).toInt() and 0xffff
        val value = ByteArray(length)
        val duplicate = buffer.duplicate().order(ByteOrder.BIG_ENDIAN)
        duplicate.position(offset + Int.SIZE_BYTES + Short.SIZE_BYTES)
        duplicate.get(value)
        return IndexedCandidate(value.toString(Charsets.UTF_8), weight)
    }

    data class IndexedCandidate(val text: String, val weight: Int)
    data class IndexedKeyCandidates(val code: String, val candidates: List<IndexedCandidate>)

    companion object {
        private const val MAGIC = 0x56504934 // VPI4
        private const val VERSION = 4
        private const val SECTION_COUNT = 3
        private const val POOL_OFFSET = Int.SIZE_BYTES * 2
        private const val SECTIONS_OFFSET = POOL_OFFSET + Long.SIZE_BYTES
        private const val FULL = 0
        private const val INITIALS = 1
        private const val T9 = 2

        fun open(context: Context): PrebuiltPinyinIndex {
            val descriptor = context.assets.openFd(ASSET_PATH)
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                val mapped = channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.length
                ).order(ByteOrder.BIG_ENDIAN)
                descriptor.close()
                return PrebuiltPinyinIndex(mapped)
            }
        }

        /** Exposed for deterministic build-time index tests. */
        fun fromBuffer(buffer: ByteBuffer): PrebuiltPinyinIndex =
            PrebuiltPinyinIndex(buffer.order(ByteOrder.BIG_ENDIAN))

        const val ASSET_PATH = "pinyin/full_pinyin_index.bin"
    }
}
