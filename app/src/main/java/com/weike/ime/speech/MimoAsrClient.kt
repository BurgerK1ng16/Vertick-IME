package com.weike.ime.speech

import android.util.Base64
import com.weike.ime.data.ModelEndpointConfig
import com.weike.ime.network.MimoApiConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
            val resolvedEndpoint = MimoApiConfig.chatCompletionsEndpoint(endpoint.url)
            val request = Request.Builder()
                .url(resolvedEndpoint)
                .apply {
                    if (MimoApiConfig.usesMimoApiKeyHeader(resolvedEndpoint)) header("api-key", endpoint.apiKey)
                    else header("Authorization", "Bearer ${endpoint.apiKey}")
                }
                .post(body)
                .build()
            Result.success(client.newCall(request).awaitBody { response ->
                check(response.isSuccessful) { MimoApiConfig.responseError(response, "MiMo ASR 请求失败") }
                cleanTranscript(extractMessageContent(
                    JSONObject(response.body?.string().orEmpty())
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                ))
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    /** Streams OpenAI-compatible SSE ASR deltas when the configured provider supports them. */
    fun transcribeStream(pcm: ByteArray): Flow<String> = callbackFlow {
        val endpoint = endpointProvider()
        if (!endpoint.isComplete()) {
            close(IllegalStateException("请先配置 ASR 接口"))
            return@callbackFlow
        }
        val audioData = Base64.encodeToString(wav(pcm), Base64.NO_WRAP)
        val content = JSONArray().put(
            JSONObject()
                .put("type", "input_audio")
                .put("input_audio", JSONObject().put("data", "data:audio/wav;base64,$audioData"))
        )
        val body = JSONObject()
            .put("model", MimoApiConfig.normalizedAsrModel(endpoint.model))
            .put("stream", true)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val resolvedEndpoint = MimoApiConfig.chatCompletionsEndpoint(endpoint.url)
        val call = client.newCall(
            Request.Builder()
                .url(resolvedEndpoint)
                .apply {
                    if (MimoApiConfig.usesMimoApiKeyHeader(resolvedEndpoint)) header("api-key", endpoint.apiKey)
                    else header("Authorization", "Bearer ${endpoint.apiKey}")
                }
                .header("Accept", "text/event-stream")
                .post(body)
                .build()
        )
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: java.io.IOException) {
                close(error)
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use { result ->
                    if (!result.isSuccessful) {
                        close(IllegalStateException(MimoApiConfig.responseError(result, "MiMo ASR 请求失败")))
                        return
                    }
                    runCatching {
                        val source = result.body?.source() ?: return@runCatching
                        val pending = StringBuilder()
                        val plainResponse = StringBuilder()
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) {
                                plainResponse.append(line)
                                continue
                            }
                            val data = line.removePrefix("data:").trim()
                            if (data.isBlank() || data == "[DONE]") continue
                            val json = JSONObject(data)
                            val delta = extractStreamDelta(json)
                            if (delta.isNotBlank()) {
                                pending.append(delta)
                                trySend(cleanTranscript(delta))
                            }
                        }
                        // Some compatible servers ignore stream=true and return one JSON object.
                        if (pending.isEmpty() && plainResponse.isNotBlank()) {
                            trySend(cleanTranscript(extractMessageContent(
                                JSONObject(plainResponse.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                            )))
                        }
                    }.onFailure(::close)
                    close()
                }
            }
        })
        awaitClose { call.cancel() }
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
        val direct = message.optText("content")
        if (direct.isNotEmpty()) return direct
        val content = message.optJSONArray("content") ?: return ""
        return buildString {
            repeat(content.length()) { index ->
                val item = content.optJSONObject(index) ?: return@repeat
                val text = item.optText("text").ifBlank { item.optText("content") }
                if (text.isNotBlank()) append(text)
            }
        }.trim()
    }

    private fun extractStreamDelta(payload: JSONObject): String {
        val choice = payload.optJSONArray("choices")?.optJSONObject(0) ?: return ""
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return ""
        return extractMessageContent(delta)
    }

    /** JSONObject.optString renders JSON null as the literal text "null". */
    private fun JSONObject.optText(name: String): String = when (val value = opt(name)) {
        null, JSONObject.NULL -> ""
        is String -> value.trim().takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()
        else -> value.toString().trim().takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()
    }

    private fun cleanTranscript(value: String): String = value
        .replace(Regex("""(?i)\s*\bnull\b\s*$"""), "")
        .trim()

    companion object {
        fun wav(pcm: ByteArray): ByteArray {
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
}
