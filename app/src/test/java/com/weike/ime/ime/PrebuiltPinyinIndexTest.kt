package com.weike.ime.ime

import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel
import kotlin.test.Test
import kotlin.test.assertTrue

class PrebuiltPinyinIndexTest {
    private val indexFile = File("build/generated/pinyinAssets/pinyin/full_pinyin_index.bin")

    private fun index(): PrebuiltPinyinIndex {
        assertTrue(indexFile.isFile, "Run generateFullPinyinIndex before unit tests")
        FileInputStream(indexFile).channel.use { channel ->
            val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            return PrebuiltPinyinIndex.fromBuffer(mapped)
        }
    }

    @Test
    fun fullPinyinIncludesEverySourceFamily() {
        val dictionary = index()
        assertTrue(dictionary.full("dui").any { it.text == "对" })
        assertTrue(dictionary.full("afeizhengzhuan").any { it.text == "阿飞正传" })
        assertTrue(dictionary.full("yiwanqiqian").any { it.text == "一万七千" })
        assertTrue(dictionary.full("afanggongfu").any { it.text == "阿房宫赋" })
    }

    @Test
    fun initialsAndT9UsePrebuiltIndexes() {
        val dictionary = index()
        assertTrue(dictionary.initials("wsm").first().text == "为什么")
        assertTrue(dictionary.t9("64").first().text == "你")
    }
}
