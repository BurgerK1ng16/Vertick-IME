package com.weike.ime.ime

import kotlin.test.Test
import kotlin.test.assertEquals

class PinyinFuzzyMatcherTest {
    private val zaiGanMa = listOf("zai", "gan", "ma")

    @Test
    fun mixedFullPinyinAndInitialsMatch() {
        assertEquals(0, PinyinFuzzyMatcher.bestDistance("zaigm", zaiGanMa, maximum = 1))
    }

    @Test
    fun oneMistypedLetterStillMatchesCompletePinyin() {
        assertEquals(1, PinyinFuzzyMatcher.bestDistance("zaigamma", zaiGanMa, maximum = 1))
    }

    @Test
    fun exactSpellingStaysExact() {
        assertEquals(0, PinyinFuzzyMatcher.bestDistance("zaiganma", zaiGanMa, maximum = 1))
    }

}
