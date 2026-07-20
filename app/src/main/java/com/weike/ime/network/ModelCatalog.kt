package com.weike.ime.network

import com.weike.ime.data.CloudProvider
import com.weike.ime.data.ModelEndpointConfig
import com.weike.ime.data.TextProviderProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fetches models using the catalog protocol published by the selected provider. */
class ModelCatalog(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
) {
    suspend fun list(config: ModelEndpointConfig): Result<List<String>> {
        if (config.url.isBlank() || config.apiKey.isBlank()) {
            return Result.failure(IllegalArgumentException("Please enter an API URL and key first"))
        }
        if (config.provider in setOf(CloudProvider.AZURE, CloudProvider.CLOUDFLARE)) {
            return Result.failure(IllegalStateException("This provider does not expose a portable model list. Enter its deployment/model name manually."))
        }
        if (config.provider.textProtocol == TextProviderProtocol.ANTHROPIC_MESSAGES) {
            return Result.failure(IllegalStateException("Anthropic does not provide a public Models API for this key. Enter a Claude model name manually."))
        }
        return try {
            val endpoint = MimoApiConfig.modelsEndpoint(config.url, config.provider)
            val request = MimoApiConfig.applyAuthorization(
                Request.Builder().url(endpoint).get(), config.provider, config.apiKey
            ).build()
            Result.success(client.newCall(request).awaitBody { response ->
                check(response.isSuccessful) { MimoApiConfig.responseError(response, "Model list request failed") }
                parseModels(response.body?.string().orEmpty(), config.provider)
                    .distinct().take(MAX_MODELS).ifEmpty { error("The provider did not return selectable models") }
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    internal fun parseModels(body: String, provider: CloudProvider): List<String> {
        val document = JSONObject(body)
        if (provider.textProtocol == TextProviderProtocol.GEMINI_GENERATE_CONTENT) {
            val models = document.optJSONArray("models") ?: return emptyList()
            return buildList {
                for (index in 0 until models.length()) {
                    val item = models.optJSONObject(index) ?: continue
                    val methods = item.optJSONArray("supportedGenerationMethods")
                    if (methods != null && !(0 until methods.length()).any { methods.optString(it) == "generateContent" }) continue
                    item.optString("name").removePrefix("models/").trim().takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
        val data: JSONArray = document.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (index in 0 until data.length()) {
                data.optJSONObject(index)?.optString("id")?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private suspend fun <T> Call.awaitBody(transform: (okhttp3.Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    try {
                        if (continuation.isActive) continuation.resume(response.use(transform))
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }

    private companion object { const val MAX_MODELS = 128 }
}
