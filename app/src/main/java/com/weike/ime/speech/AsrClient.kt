package com.weike.ime.speech

import android.util.Base64
import com.weike.ime.data.AsrProtocol
import com.weike.ime.data.ModelEndpointConfig
import com.weike.ime.data.asrProtocol
import com.weike.ime.network.MimoApiConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** A provider-neutral ASR boundary used by the IME. */
interface AsrClient {
    suspend fun transcribe(pcm: ByteArray): Result<String>
    suspend fun testConnection(): Result<Unit>
}

class RoutedAsrClient(
    private val endpointProvider: () -> ModelEndpointConfig
) : AsrClient {
    override suspend fun transcribe(pcm: ByteArray): Result<String> {
        val config = endpointProvider()
        return when (config.provider.asrProtocol()) {
            AsrProtocol.MIMO_MULTIMODAL_HTTP, AsrProtocol.CUSTOM ->
                MimoAsrClient(endpointProvider = { config }).transcribe(pcm)
            AsrProtocol.OPENAI_AUDIO_TRANSCRIPTION ->
                OpenAiTranscriptionAsrClient(endpointProvider = { config }).transcribe(pcm)
            AsrProtocol.DASHSCOPE_REALTIME_WEBSOCKET ->
                DashScopeRealtimeAsrClient(endpointProvider = { config }).transcribe(pcm)
            AsrProtocol.VOLCENGINE_REALTIME_WEBSOCKET -> Result.failure(
                IllegalStateException("火山引擎实时 ASR 需要 App ID、Access Token 和 Resource ID；请在自定义 ASR 中使用已适配的服务端网关。")
            )
        }
    }

    override suspend fun testConnection(): Result<Unit> {
        val config = endpointProvider()
        return when (config.provider.asrProtocol()) {
            AsrProtocol.MIMO_MULTIMODAL_HTTP, AsrProtocol.CUSTOM -> MimoAsrClient(endpointProvider = { config }).testConnection()
            AsrProtocol.OPENAI_AUDIO_TRANSCRIPTION -> OpenAiTranscriptionAsrClient(endpointProvider = { config }).testConnection()
            AsrProtocol.DASHSCOPE_REALTIME_WEBSOCKET -> DashScopeRealtimeAsrClient(endpointProvider = { config }).testConnection()
            AsrProtocol.VOLCENGINE_REALTIME_WEBSOCKET -> Result.failure(
                IllegalStateException("火山引擎实时 ASR 尚需专用凭据配置，不能用普通 API Key 测试。")
            )
        }
    }
}

/** OpenAI Audio Transcriptions-compatible ASR, including SiliconFlow. */
class OpenAiTranscriptionAsrClient(
    private val endpointProvider: () -> ModelEndpointConfig,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()
) : AsrClient {
    override suspend fun transcribe(pcm: ByteArray): Result<String> = runCatching {
        val config = endpointProvider()
        require(config.isComplete()) { "请先配置 ASR 接口" }
        val wav = MimoAsrClient.wav(pcm)
        val temp = File.createTempFile("vertick-asr-", ".wav")
        try {
            temp.writeBytes(wav)
            val endpoint = MimoApiConfig.audioTranscriptionsEndpoint(config.url)
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", config.model.trim())
                .addFormDataPart("file", "speech.wav", temp.asRequestBody("audio/wav".toMediaType()))
                .build()
            client.newCall(Request.Builder().url(endpoint)
                .header("Authorization", "Bearer ${config.apiKey.trim()}")
                .post(body).build()).await { response ->
                check(response.isSuccessful) { MimoApiConfig.responseError(response, "ASR 请求失败") }
                JSONObject(response.body?.string().orEmpty()).optString("text").trim()
                    .takeIf { it.isNotBlank() } ?: error("ASR 未返回识别文本")
            }
        } finally {
            temp.delete()
        }
    }

    override suspend fun testConnection(): Result<Unit> = transcribe(ByteArray(AudioRecorder.SAMPLE_RATE * 2)).map { Unit }
}

/** DashScope Qwen realtime ASR uses an OpenAI-Realtime-compatible WebSocket. */
class DashScopeRealtimeAsrClient(
    private val endpointProvider: () -> ModelEndpointConfig,
    private val client: OkHttpClient = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
) : AsrClient {
    override suspend fun transcribe(pcm: ByteArray): Result<String> = try {
        Result.success(transcribeInternal(pcm))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }

    override suspend fun testConnection(): Result<Unit> = transcribe(ByteArray(AudioRecorder.SAMPLE_RATE * 2)).map { Unit }

    private suspend fun transcribeInternal(pcm: ByteArray): String = suspendCancellableCoroutine { continuation ->
        val config = endpointProvider()
        if (!config.isComplete()) {
            continuation.resumeWith(Result.failure(IllegalStateException("请先配置 ASR 接口")))
            return@suspendCancellableCoroutine
        }
        val endpoint = runCatching { MimoApiConfig.dashScopeRealtimeEndpoint(config.url, config.model) }
            .getOrElse { error ->
                continuation.resumeWith(Result.failure(error))
                return@suspendCancellableCoroutine
            }
        val transcript = StringBuilder()
        lateinit var socket: WebSocket
        fun complete(error: Throwable? = null) {
            if (!continuation.isActive) return
            if (error != null) continuation.resumeWith(Result.failure(error))
            else transcript.toString().trim().takeIf { it.isNotBlank() }
                ?.let { continuation.resumeWith(Result.success(it)) }
                ?: continuation.resumeWith(Result.failure(IllegalStateException("ASR 未返回识别文本")))
        }
        socket = client.newWebSocket(
            Request.Builder().url(endpoint).header("Authorization", "Bearer ${config.apiKey.trim()}").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    val session = JSONObject().put("type", "session.update").put("session", JSONObject()
                        .put("modalities", org.json.JSONArray().put("text"))
                        .put("input_audio_format", "pcm16")
                        .put("input_audio_transcription", JSONObject().put("model", config.model.trim()))
                    )
                    if (!webSocket.send(session.toString())) return complete(IllegalStateException("无法初始化 ASR 会话"))
                    pcm.asList().chunked(3200).forEach { chunk ->
                        val bytes = ByteArray(chunk.size) { index -> chunk[index] }
                        webSocket.send(JSONObject().put("type", "input_audio_buffer.append")
                            .put("audio", Base64.encodeToString(bytes, Base64.NO_WRAP)).toString())
                    }
                    webSocket.send(JSONObject().put("type", "input_audio_buffer.commit").toString())
                    webSocket.send(JSONObject().put("type", "response.create").toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val payload = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when (payload.optString("type")) {
                        "conversation.item.input_audio_transcription.completed" -> {
                            transcript.clear()
                            transcript.append(payload.optString("transcript"))
                            complete()
                            webSocket.close(1000, null)
                        }
                        "response.output_text.delta", "response.audio_transcript.delta" -> transcript.append(payload.optString("delta"))
                        "response.done" -> {
                            complete()
                            webSocket.close(1000, null)
                        }
                        "error" -> complete(IllegalStateException(payload.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "ASR WebSocket 请求失败" }))
                    }
                }

                override fun onFailure(webSocket: WebSocket, error: Throwable, response: okhttp3.Response?) {
                    complete(IllegalStateException(response?.let { MimoApiConfig.responseError(it, "ASR WebSocket 请求失败") } ?: error.message ?: "ASR WebSocket 连接失败", error))
                }
            }
        )
        continuation.invokeOnCancellation { socket?.cancel() }
    }
}

private suspend fun <T> okhttp3.Call.await(transform: (okhttp3.Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, error: java.io.IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
        }

        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            val result = response.use { runCatching { transform(it) } }
            if (continuation.isActive) continuation.resumeWith(result)
        }
    })
}
