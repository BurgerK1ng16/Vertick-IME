package com.weike.ime.network

import com.weike.ime.data.CloudProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MimoApiConfigTest {
    @Test
    fun `normalizes host to chat completions`() {
        assertEquals(
            "https://token-plan-cn.xiaomimimo.com/v1/chat/completions",
            MimoApiConfig.chatCompletionsEndpoint("https://token-plan-cn.xiaomimimo.com")
        )
    }

    @Test
    fun `normalizes v1 base to chat completions`() {
        assertEquals(
            "https://token-plan-cn.xiaomimimo.com/v1/chat/completions",
            MimoApiConfig.chatCompletionsEndpoint("https://token-plan-cn.xiaomimimo.com/v1/")
        )
    }

    @Test
    fun `keeps complete endpoint`() {
        assertEquals(
            "https://token-plan-cn.xiaomimimo.com/v1/chat/completions",
            MimoApiConfig.chatCompletionsEndpoint("https://token-plan-cn.xiaomimimo.com/v1/chat/completions")
        )
    }

    @Test
    fun `normalizes any accepted endpoint to models endpoint`() {
        assertEquals(
            "https://token-plan-cn.xiaomimimo.com/v1/models",
            MimoApiConfig.modelsEndpoint("https://token-plan-cn.xiaomimimo.com/v1/chat/completions")
        )
    }

    @Test
    fun `rejects insecure endpoint`() {
        assertFailsWith<IllegalArgumentException> {
            MimoApiConfig.chatCompletionsEndpoint("http://example.com")
        }
    }

    @Test
    fun `uses MiMo authentication only for MiMo hosts`() {
        assertEquals(true, MimoApiConfig.usesMimoApiKeyHeader("https://token-plan-cn.xiaomimimo.com/v1/chat/completions"))
        assertEquals(false, MimoApiConfig.usesMimoApiKeyHeader("https://api.openai.com/v1/chat/completions"))
    }

    @Test
    fun `keeps non v1 OpenAI compatible provider bases`() {
        assertEquals(
            "https://qianfan.baidubce.com/v2/chat/completions",
            MimoApiConfig.chatCompletionsEndpoint("https://qianfan.baidubce.com/v2")
        )
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            MimoApiConfig.chatCompletionsEndpoint("https://ark.cn-beijing.volces.com/api/v3")
        )
    }

    @Test
    fun `uses Gemini native models endpoint`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models",
            MimoApiConfig.modelsEndpoint("https://generativelanguage.googleapis.com/v1beta", CloudProvider.GEMINI)
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
            MimoApiConfig.geminiGenerateContentEndpoint(
                "https://generativelanguage.googleapis.com/v1beta",
                "gemini-2.5-flash",
                false
            )
        )
    }

    @Test
    fun `uses Anthropic messages endpoint`() {
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            MimoApiConfig.anthropicMessagesEndpoint("https://api.anthropic.com/v1")
        )
    }
}
