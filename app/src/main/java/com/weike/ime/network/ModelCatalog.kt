package com.weike.ime.network

import com.weike.ime.data.ModelEndpointConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fetches only model identifiers from an OpenAI-compatible `/v1/models` endpoint. */
class ModelCatalog(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
) {
    suspend fun list(config: ModelEndpointConfig): Result<List<String>> {
        if (config.url.isBlank() || config.apiKey.isBlank()) {
            return Result.failure(IllegalArgumentException("请先填写接口地址和接口密钥"))
        }
        return try {
            val endpoint = MimoApiConfig.modelsEndpoint(config.url)
            val request = Request.Builder()
                .url(endpoint)
                .apply {
                    if (MimoApiConfig.usesMimoApiKeyHeader(endpoint)) header("api-key", config.apiKey)
                    else header("Authorization", "Bearer ${config.apiKey}")
                }
                .get()
                .build()
            Result.success(client.newCall(request).awaitBody { response ->
                check(response.isSuccessful) { MimoApiConfig.responseError(response, "读取模型失败") }
                val data = JSONObject(response.body?.string().orEmpty()).optJSONArray("data")
                buildList {
                    if (data != null) {
                        for (index in 0 until data.length()) {
                            data.optJSONObject(index)?.optString("id")?.trim()
                                ?.takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                }.distinct().take(MAX_MODELS).ifEmpty { error("接口未返回可选模型") }
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
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

    private companion object {
        const val MAX_MODELS = 128
    }
}
