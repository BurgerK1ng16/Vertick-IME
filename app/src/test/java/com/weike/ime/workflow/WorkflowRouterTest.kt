package com.weike.ime.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowRouterTest {
    @Test
    fun `exact replacement remains local`() {
        val intent = WorkflowRouter.route("把你好改成您好")

        assertEquals(WorkflowIntent.ExactReplace("你好", "您好"), intent)
    }

    @Test
    fun `named parenthesized content is a targeted local replacement`() {
        assertEquals(
            WorkflowIntent.TargetedReplace(WorkflowReplaceTarget.PARENTHESES_CONTENT, "国外"),
            WorkflowRouter.route("把括号里的内容换成国外")
        )
    }

    @Test
    fun `named quoted and selected content are targeted replacements`() {
        assertEquals(
            WorkflowIntent.TargetedReplace(WorkflowReplaceTarget.QUOTED_CONTENT, "您好"),
            WorkflowRouter.route("把引号里的内容改成您好")
        )
        assertEquals(
            WorkflowIntent.TargetedReplace(WorkflowReplaceTarget.SELECTED_TEXT, "您好"),
            WorkflowRouter.route("把选中的内容改成您好")
        )
    }

    @Test
    fun `polishing commands choose the polishing workflow`() {
        val intent = WorkflowRouter.route("把这段话改得专业一点")

        assertTrue(intent is WorkflowIntent.Polish)
    }

    @Test
    fun `continuation command does not fall back to question answering`() {
        assertEquals(WorkflowIntent.Continue("续写"), WorkflowRouter.route("续写"))
    }

    @Test
    fun `common text commands route to dedicated workflows`() {
        assertTrue(WorkflowRouter.route("总结这段内容") is WorkflowIntent.Summarize)
        assertTrue(WorkflowRouter.route("扩写得详细一点") is WorkflowIntent.Expand)
        assertTrue(WorkflowRouter.route("翻译成英文") is WorkflowIntent.Translate)
        assertTrue(WorkflowRouter.route("校对错别字") is WorkflowIntent.Proofread)
        assertTrue(WorkflowRouter.route("整理格式为待办列表") is WorkflowIntent.Format)
        assertTrue(WorkflowRouter.route("提取所有时间") is WorkflowIntent.Extract)
        assertTrue(WorkflowRouter.route("帮我礼貌回复") is WorkflowIntent.Reply)
    }

    @Test
    fun `ordinary questions stay in answer workflow`() {
        assertEquals(WorkflowIntent.Answer("什么是 Rime"), WorkflowRouter.route("什么是 Rime"))
    }

    @Test
    fun `ambiguous edit language is deferred to the model classifier`() {
        assertEquals(WorkflowIntent.Ambiguous("帮我处理一下"), WorkflowRouter.route("帮我处理一下"))
    }
}
