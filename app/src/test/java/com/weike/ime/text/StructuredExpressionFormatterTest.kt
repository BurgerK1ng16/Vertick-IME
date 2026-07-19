package com.weike.ime.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredExpressionFormatterTest {
    @Test
    fun `numbered spoken tasks become a title and numbered lines`() {
        val source = "今天要做三件事，第一睡觉，第二刷牙，第三洗澡"

        assertTrue(StructuredExpressionFormatter.needsStructure(source))
        assertEquals(
            "今天要做三件事：\n1.睡觉\n2.刷牙\n3.洗澡",
            StructuredExpressionFormatter.enforce(source, "今天要做三件事 第一 睡觉 第二 刷牙 第三 洗澡")
        )
    }

    @Test
    fun `existing numbered output remains unchanged`() {
        val formatted = "今天要做三件事：\n1.睡觉\n2.刷牙\n3.洗澡"
        assertEquals(formatted, StructuredExpressionFormatter.enforce("今天要做三件事，第一睡觉，第二刷牙，第三洗澡", formatted))
    }

    @Test
    fun `numbered markers with shi are parsed as one marker`() {
        assertEquals(
            "今天要做三件事：\n1.洗澡\n2.睡觉\n3.洗车",
            StructuredExpressionFormatter.enforce(
                "今天要做三件事，第一洗澡，第二睡觉，第三洗车",
                "今天要做三件事 第一是洗澡 第二是睡觉 第三是洗车"
            )
        )
    }

    @Test
    fun `ordinary sentence is not restructured`() {
        assertEquals("今天睡觉后洗澡。", StructuredExpressionFormatter.enforce("今天睡觉后洗澡", "今天睡觉后洗澡。"))
    }

    @Test
    fun `delimited causes become a list`() {
        val source = "原因有三点：价格、速度、稳定性"
        assertTrue(StructuredExpressionFormatter.needsStructure(source))
        assertEquals(
            "原因有三点：\n1.价格\n2.速度\n3.稳定性",
            StructuredExpressionFormatter.enforce(source, source)
        )
    }
}
