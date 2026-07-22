package com.weike.ime.network

import com.weike.ime.data.CloudProvider
import com.weike.ime.data.TextProviderProtocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** URL, protocol, and authentication rules for the configured text provider. */
object MimoApiConfig {
    fun chatCompletionsEndpoint(raw: String): String {
        val value = raw.trim().trimEnd('/')
        require(value.isNotBlank()) { "API URL is required" }
        val path = value.toHttpUrlOrNull()?.encodedPath.orEmpty().trim('/')
        val candidate = when {
            value.endsWith("/chat/completions", ignoreCase = true) -> value
            path.isEmpty() -> "$value/v1/chat/completions"
            else -> "$value/chat/completions"
        }
        return validatedUrl(candidate)
    }

    fun modelsEndpoint(raw: String): String =
        chatCompletionsEndpoint(raw).removeSuffix("/chat/completions") + "/models"

    fun modelsEndpoint(raw: String, provider: CloudProvider): String {
        if (provider.textProtocol != TextProviderProtocol.GEMINI_GENERATE_CONTENT) return modelsEndpoint(raw)
        val value = raw.trim().trimEnd('/').removeSuffix("/openai")
        val path = value.toHttpUrlOrNull()?.encodedPath.orEmpty().trim('/')
        return validatedUrl(if (path.isEmpty()) "$value/v1beta/models" else "$value/models")
    }

    fun audioTranscriptionsEndpoint(raw: String): String {
        val value = raw.trim().trimEnd('/')
        require(value.isNotBlank()) { "API URL is required" }
        val path = value.toHttpUrlOrNull()?.encodedPath.orEmpty().trim('/')
        val candidate = when {
            value.endsWith("/audio/transcriptions", ignoreCase = true) -> value
            path.isEmpty() -> "$value/v1/audio/transcriptions"
            else -> "$value/audio/transcriptions"
        }
        return validatedUrl(candidate)
    }

    /** Converts a DashScope HTTPS base endpoint into its realtime WebSocket endpoint. */
    fun dashScopeRealtimeEndpoint(raw: String, model: String): String {
        val source = raw.trim().trimEnd('/')
        require(source.isNotBlank()) { "API URL is required" }
        val parsed = source.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid API URL")
        require(parsed.isHttps || parsed.scheme == "wss") { "ASR URL must use HTTPS or WSS" }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "API URL cannot contain credentials" }
        val host = parsed.host
        val selectedModel = model.trim().ifBlank { "qwen3-asr-flash-realtime" }
        require(selectedModel.length <= 128 && selectedModel.none(Char::isWhitespace)) { "Invalid ASR model" }
        val base = when {
            source.startsWith("wss://") -> source
            host.contains("dashscope.aliyuncs.com") -> "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"
            else -> source.replaceFirst("https://", "wss://")
        }
        val endpoint = if (base.contains("/realtime")) base else "$base/v1/realtime"
        val url = endpoint.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid ASR WebSocket URL")
        require(url.scheme == "wss") { "ASR URL must use WSS" }
        return url.newBuilder().addQueryParameter("model", selectedModel).build().toString()
    }

    fun anthropicMessagesEndpoint(raw: String): String = endpointFor(raw, "messages")

    fun geminiGenerateContentEndpoint(raw: String, model: String, stream: Boolean): String {
        val value = raw.trim().trimEnd('/').removeSuffix("/openai")
        val path = value.toHttpUrlOrNull()?.encodedPath.orEmpty().trim('/')
        val base = if (path.isEmpty()) "$value/v1beta" else value
        val selectedModel = model.trim().removePrefix("models/")
        require(selectedModel.isNotBlank()) { "Model is required" }
        val operation = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
        return validatedUrl("$base/models/$selectedModel:$operation", allowQuery = stream)
    }

    fun applyAuthorization(builder: Request.Builder, provider: CloudProvider, apiKey: String): Request.Builder = builder.apply {
        when (provider.textProtocol) {
            TextProviderProtocol.GEMINI_GENERATE_CONTENT -> header("x-goog-api-key", apiKey)
            TextProviderProtocol.ANTHROPIC_MESSAGES -> {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
            }
            TextProviderProtocol.OPENAI_CHAT -> {
                if (usesApiKeyHeader(provider)) header("api-key", apiKey)
                else header("Authorization", "Bearer $apiKey")
            }
        }
    }

    fun usesApiKeyHeader(provider: CloudProvider): Boolean = provider in setOf(
        CloudProvider.XIAOMI_MIMO,
        CloudProvider.XIAOMI_MIMO_PLAN,
        CloudProvider.AZURE
    )

    fun normalizedTextModel(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return "mimo-v2.5"
        return when (value.lowercase()) {
            "mimo-v2.5", "mimo-v2_5", "mimo_v2.5", "mimo_v2_5", "mimo-v2-5", "mimo-v25", "mimo25", "mimo v2.5" -> "mimo-v2.5"
            else -> value
        }
    }

    fun normalizedAsrModel(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return "mimo-v2.5-asr"
        return when (value.lowercase()) {
            "mimo-v2.5-asr", "mimo-v2_5-asr", "mimo_v2.5_asr", "mimo_v2_5_asr", "mimo-v2-5-asr", "mimo v2.5 asr" -> "mimo-v2.5-asr"
            else -> value
        }
    }

    fun usesMimoApiKeyHeader(endpoint: String): Boolean {
        val host = endpoint.toHttpUrlOrNull()?.host.orEmpty()
        return host == "xiaomimimo.com" || host.endsWith(".xiaomimimo.com")
    }

    fun responseError(response: Response, fallback: String): String {
        val endpoint = response.request.url.toString()
        val body = response.peekBody(MAX_ERROR_BODY_BYTES).string().trim()
        if (body.isBlank()) return "$fallback: ${response.code} ($endpoint)"
        val message = runCatching {
            val error = JSONObject(body).optJSONObject("error")
            error?.optString("message").orEmpty().ifBlank { error?.optString("type").orEmpty() }
        }.getOrDefault("")
            .replace(Regex("\\s+"), " ")
            .take(MAX_ERROR_MESSAGE_LENGTH)
        return if (message.isBlank()) "$fallback: ${response.code} ($endpoint)"
        else "$fallback: ${response.code} ($endpoint) - $message"
    }

    private fun endpointFor(raw: String, suffix: String): String {
        val value = raw.trim().trimEnd('/')
        require(value.isNotBlank()) { "API URL is required" }
        val path = value.toHttpUrlOrNull()?.encodedPath.orEmpty().trim('/')
        val candidate = when {
            value.endsWith("/$suffix", ignoreCase = true) -> value
            path.isEmpty() -> "$value/v1/$suffix"
            else -> "$value/$suffix"
        }
        return validatedUrl(candidate)
    }

    private fun validatedUrl(candidate: String, allowQuery: Boolean = false): String {
        val url = candidate.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid API URL")
        require(url.isHttps) { "API URL must use HTTPS" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "API URL cannot contain credentials" }
        require(allowQuery || url.query == null) { "API URL cannot contain a query string" }
        return url.toString().trimEnd('/')
    }

    private const val MAX_ERROR_BODY_BYTES = 64L * 1024L
    private const val MAX_ERROR_MESSAGE_LENGTH = 240
}
