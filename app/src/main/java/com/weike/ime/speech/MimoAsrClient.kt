package com.weike.ime.speech

import android.util.Base64
import com.weike.ime.data.ModelEndpointConfig
import com.weike.ime.network.MimoApiConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
class MimoAsrClient(
    private val endpointProvider: () -> ModelEndpointConfig,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
) {
    suspend fun transcribe(pcm: ByteArray): Result<String> {
        val endpoint = endpointProvider()
        if (!endpoint.isComplete()) {
            return Result.failure(IllegalStateException("请先配置 ASR 接口"))
        }
        return try {
            val audioData = Base64.encodeToString(wav(pcm), Base64.NO_WRAP)
            val content = JSONArray().put(
                JSONObject()
                    .put("type", "input_audio")
                    .put("input_audio", JSONObject().put("data", "data:audio/wav;base64,$audioData"))
            )
            val body = JSONObject()
                .put("model", MimoApiConfig.normalizedAsrModel(endpoint.model))
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(MimoApiConfig.chatCompletionsEndpoint(endpoint.url))
                .header("api-key", endpoint.apiKey)
                .post(body)
                .build()
            Result.success(client.newCall(request).awaitBody { response ->
                check(response.isSuccessful) { MimoApiConfig.responseError(response, "MiMo ASR 请求失败") }
                extractMessageContent(
                    JSONObject(response.body?.string().orEmpty())
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                )
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend fun testConnection(): Result<Unit> = transcribe(ByteArray(AudioRecorder.SAMPLE_RATE * 2)).map { Unit }

    private suspend fun <T> Call.awaitBody(transform: (okhttp3.Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, error: java.io.IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    val result = response.use { runCatching { transform(it) } }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }

    private fun extractMessageContent(message: JSONObject): String {
        val direct = message.optString("content").trim()
        if (direct.isNotEmpty()) return direct
        val content = message.optJSONArray("content") ?: return ""
        return buildString {
            repeat(content.length()) { index ->
                val item = content.optJSONObject(index) ?: return@repeat
                val text = item.optString("text").ifBlank { item.optString("content") }
                if (text.isNotBlank()) append(text)
            }
        }.trim()
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(AudioRecorder.SAMPLE_RATE)
        header.putInt(AudioRecorder.SAMPLE_RATE * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(pcm.size)
        return header.array() + pcm
    }
}
