package com.weike.ime.network

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
}
