package com.weike.ime.network

import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

object MimoApiConfig {
    /**
     * MiMo Token Plan exposes OpenAI-compatible chat completions under this path.
     * The settings screen deliberately accepts the host, the /v1 base, or the full endpoint.
     */
    fun chatCompletionsEndpoint(raw: String): String {
        val value = raw.trim().trimEnd('/')
        require(value.isNotBlank()) { "接口地址不能为空" }
        val candidate = when {
            value.endsWith("/chat/completions", ignoreCase = true) -> value
            value.endsWith("/v1", ignoreCase = true) -> "$value/chat/completions"
            else -> "$value/v1/chat/completions"
        }
        val url = candidate.toHttpUrlOrNull() ?: throw IllegalArgumentException("接口地址无效")
        require(url.isHttps) { "接口地址必须使用 HTTPS" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "接口地址不能包含账号信息" }
        require(url.query == null) { "接口地址不能包含查询参数" }
        return url.toString().trimEnd('/')
    }

    fun normalizedTextModel(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return "mimo-v2.5"
        return when (value.lowercase()) {
            "mimo-v2.5",
            "mimo-v2_5",
            "mimo_v2.5",
            "mimo_v2_5",
            "mimo-v2-5",
            "mimo-v25",
            "mimo25",
            "mimo v2.5" -> "mimo-v2.5"
            else -> value.lowercase()
        }
    }

    fun usesMimoApiKeyHeader(endpoint: String): Boolean {
        val host = endpoint.toHttpUrlOrNull()?.host.orEmpty()
        return host == "xiaomimimo.com" || host.endsWith(".xiaomimimo.com")
    }

    fun normalizedAsrModel(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return "mimo-v2.5-asr"
        return when (value.lowercase()) {
            "mimo-v2.5-asr",
            "mimo-v2_5-asr",
            "mimo_v2.5_asr",
            "mimo_v2_5_asr",
            "mimo-v2-5-asr",
            "mimo v2.5 asr" -> "mimo-v2.5-asr"
            else -> value.lowercase()
        }
    }

    fun responseError(response: Response, fallback: String): String {
        val endpoint = response.request.url.toString()
        val body = response.peekBody(MAX_ERROR_BODY_BYTES).string().trim()
        if (body.isBlank()) return "$fallback: ${response.code} ($endpoint)"
        val message = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
            .replace(Regex("\\s+"), " ")
            .take(MAX_ERROR_MESSAGE_LENGTH)
        return if (message.isBlank()) "$fallback: ${response.code} ($endpoint)"
        else "$fallback: ${response.code} ($endpoint) - $message"
    }

    private const val MAX_ERROR_BODY_BYTES = 64L * 1024L
    private const val MAX_ERROR_MESSAGE_LENGTH = 240
}
