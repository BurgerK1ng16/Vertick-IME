package com.weike.ime.ime

/**
 * Scores a typed Pinyin sequence against a candidate's syllables. Each syllable
 * can be represented by its complete spelling or its initial, so `zaigm` matches
 * `zai gan ma`. A bounded edit distance then absorbs a single mistyped letter.
 */
internal object PinyinFuzzyMatcher {
    fun bestDistance(query: String, syllables: List<String>, maximum: Int): Int? {
        if (query.isBlank() || syllables.isEmpty()) return null
        val normalized = syllables.filter(String::isNotBlank)
        if (normalized.isEmpty()) return null

        var best = boundedDistance(query, normalized.joinToString(""), maximum)
        if (best == 0) return 0
        // Mixed full-Pinyin/initial forms model normal abbreviated typing without
        // exploding for long sentence candidates.
        if (normalized.size <= MAX_MIXED_SYLLABLES) {
            val combinations = 1 shl normalized.size
            for (mask in 1 until combinations) {
                val encoded = buildString {
                    normalized.forEachIndexed { index, syllable ->
                        append(if ((mask and (1 shl index)) != 0) syllable else syllable[0])
                    }
                }
                val distance = boundedDistance(query, encoded, minOf(maximum, best))
                if (distance < best) best = distance
                if (best == 0) return 0
            }
        }
        return best.takeIf { it <= maximum }
    }

    private fun boundedDistance(left: String, right: String, maximum: Int): Int {
        if (kotlin.math.abs(left.length - right.length) > maximum) return maximum + 1
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        left.forEachIndexed { row, leftCharacter ->
            current[0] = row + 1
            var rowMinimum = current[0]
            right.forEachIndexed { column, rightCharacter ->
                current[column + 1] = minOf(
                    previous[column + 1] + 1,
                    current[column] + 1,
                    previous[column] + if (leftCharacter == rightCharacter) 0 else 1
                )
                rowMinimum = minOf(rowMinimum, current[column + 1])
            }
            if (rowMinimum > maximum) return maximum + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }

    private const val MAX_MIXED_SYLLABLES = 7
}
