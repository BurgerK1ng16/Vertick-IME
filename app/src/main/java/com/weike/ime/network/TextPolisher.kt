package com.weike.ime.network

import com.weike.ime.data.LexiconTerm
import com.weike.ime.data.ModelEndpointConfig
import com.weike.ime.data.PunctuationPreference
import com.weike.ime.data.TextProviderProtocol
import com.weike.ime.data.WritingStyle
import com.weike.ime.text.StructuredExpressionFormatter
import com.weike.ime.workflow.WorkflowTaskType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface TextPolisher {
    suspend fun polishFast(
        text: String,
        style: WritingStyle,
        punctuation: PunctuationPreference,
        lexicon: List<LexiconTerm>
    ): Result<String>

    suspend fun polish(
        text: String,
        style: WritingStyle,
        punctuation: PunctuationPreference,
        lexicon: List<LexiconTerm>,
        optimizeExpression: Boolean,
        structureHint: Boolean
    ): Result<String>

    fun polishStream(
        text: String,
        style: WritingStyle,
        punctuation: PunctuationPreference,
        lexicon: List<LexiconTerm>,
        optimizeExpression: Boolean,
        structureHint: Boolean
    ): Flow<String>

    suspend fun answer(question: String): Result<String>
    fun answerStream(question: String): Flow<String>
    suspend fun classifyWorkflow(command: String): Result<WorkflowTaskType>
    suspend fun editWorkflowText(
        instruction: String,
        text: String,
        style: WritingStyle,
        punctuation: PunctuationPreference,
        lexicon: List<LexiconTerm>,
        optimizeExpression: Boolean
    ): Result<String>
    suspend fun translateToAmericanEnglish(text: String): Result<String>
}

class MimoTextPolisher(
    private val endpointProvider: () -> ModelEndpointConfig,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
) : TextPolisher {

    private companion object {
        const val MAX_LEXICON_TERMS = 96
        const val MAX_FAST_LEXICON_TERMS = 24
    }

    override suspend fun polishFast(
        text: String,
        style: WritingStyle,
        punctuation: PunctuationPreference,
        lexicon: List<LexiconTerm>
    ): Result<String> {
        if (style == WritingStyle.RAW) return Result.success(text)
        val endpoint = endpointProvider()
        if (!endpoint.isComplete()) {
            return Result.failure(IllegalStateException("请先配置文本模型接口"))
        }
        return try {
            val request = buildRequest(endpoint,
                systemPrompt = fastPrompt(style, punctuation, compactFastLexicon(lexicon)),
                userText = text,
                temperature = 0.1,
                stream = false
            )
            Result.success(client.newCall(request).awaitBody { response ->
                check(response.isSuccessful) { MimoApiConfig.responseError(response, "文本模型快速润色失败") }
                extractResponseContent(JSONObject(response.body?.string().orEmpty()), endpoint).ifBlank { text }
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    override suspend fun polish(
        text: String,
        style: WritingStyle,
        punctuation: PunctuationPreference,
        lexicon: List<LexiconTerm>,
        optimizeExpression: Boolean,
        structureHint: Boolean
    ): Result<String> {
        if (style == WritingStyle.RAW) return Result.success(text)
        val endpoint = endpointProvider()
        if (!endpoint.isComplete()) {
            return Result.failure(IllegalStateException("请先配置文本模型接口"))
        }
        return try {
            val request = buildRequest(endpoint,
                systemPrompt = prompt(
                    style = style,
                    punctuation = punctuation,
                    lexicon = compactLexicon(lexicon),
                    optimizeExpression = optimizeExpression,
                    forceStructure = optimizeExpression && (structureHint || needsStructuredExpression(text))
                ),
                userText = text,
                temperature = 0.15,
                stream = false
            )
            Result.success(client.newCall(request).awaitBody { response ->
                check(response.isSuccessful) { MimoApiConfig.responseError(response, "文本模型请求失败") }
                extractResponseContent(JSONObject(response.body?.string().orEmpty()), endpoint).ifBlank { text }
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    override fun polishStream(
        text: String,
        style: WritingStyle,
        punctuation: PunctuationPreference,
        lexicon: List<LexiconTerm>,
        optimizeExpression: Boolean,
        structureHint: Boolean
    ): Flow<String> {
        if (style == WritingStyle.RAW) return flowOf(text)
        val endpoint = endpointProvider()
        if (!endpoint.isComplete()) {
            return callbackFlow { close(IllegalStateException("请先配置文本模型接口")) }
        }
        val request = buildRequest(endpoint,
            systemPrompt = prompt(
                style = style,
                punctuation = punctuation,
                lexicon = compactLexicon(lexicon),
                optimizeExpression = optimizeExpression,
                forceStructure = optimizeExpression && (structureHint || needsStructuredExpression(text))
            ),
            userText = text,
            temperature = 0.15,
            stream = true
        )
        return callbackFlow {
            val call = client.newCall(request)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    close(error)
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use { streamedResponse ->
                        if (!streamedResponse.isSuccessful) {
                            close(IOException(MimoApiConfig.responseError(streamedResponse, "文本模型请求失败")))
                            return
                        }
                        val source = streamedResponse.body?.source()
                        if (source == null) {
                            close(IOException("文本模型未返回润色内容"))
                            return
                        }
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val payload = line.removePrefix("data:").trim()
                            if (payload == "[DONE]") {
                                close()
                                return
                            }
                            val delta = extractStreamDelta(JSONObject(payload), endpoint)
                            if (delta.isNotEmpty()) trySend(delta)
                        }
                        close()
                    }
                }
            })
            awaitClose { call.cancel() }
        }
    }

    override suspend fun answer(question: String): Result<String> = try {
        val answer = StringBuilder()
        answerStream(question).collect { answer.append(it) }
        Result.success(answer.toString().trim().ifBlank { "暂时无法回答这个问题。" })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }

    override fun answerStream(question: String): Flow<String> {
        val endpoint = endpointProvider()
        if (!endpoint.isComplete()) {
            return callbackFlow { close(IllegalStateException("请先配置文本模型接口")) }
        }
        val request = buildRequest(endpoint,
            systemPrompt = answerPrompt(),
            userText = question,
            temperature = 0.35,
            stream = true
        )
        return callbackFlow {
            val call = client.newCall(request)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    close(error)
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use { streamedResponse ->
                        if (!streamedResponse.isSuccessful) {
                            close(IOException(MimoApiConfig.responseError(streamedResponse, "Text model request failed")))
                            return
                        }
                        val source = streamedResponse.body?.source()
                        if (source == null) {
                            close(IOException("Text model returned an empty response"))
                            return
                        }
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val payload = line.removePrefix("data:").trim()
                            if (payload == "[DONE]") {
                                close()
                                return
                            }
                            val delta = extractStreamDelta(JSONObject(payload), endpoint)
                            if (delta.isNotEmpty()) trySend(delta)
                        }
                        close()
                    }
                }
            })
            awaitClose { call.cancel() }
        }
    }

    override suspend fun classifyWorkflow(command: String): Result<WorkflowTaskType> {
        val endpoint = endpointProvider()
        if (!endpoint.isComplete()) {
            return Result.failure(IllegalStateException("请先配置文本模型接口"))
        }
        return try {
            val request = buildRequest(
                endpoint = endpoint,
                systemPrompt = """
                    你是输入法工作流路由器。根据用户命令只输出一个大写标签：
                    ANSWER=普通提问或解释；
                    REWRITE=要求修改、删除、补充、重写当前文本；
                    POLISH=要求提升专业度、正式度、简洁度、语气或表达质量。
                    不要输出解释、标点或其他文字。
                """.trimIndent(),
                userText = command,
                temperature = 0.0,
                stream = false
            )
            val response = client.newCall(request).awaitBody { httpResponse ->
                check(httpResponse.isSuccessful) { MimoApiConfig.responseError(httpResponse, "工作流识别失败") }
                extractResponseContent(JSONObject(httpResponse.body?.string().orEmpty()), endpoint)
            }
            WorkflowTaskType.fromModel(response)
                ?.let(Result.Companion::success)
                ?: Result.failure(IllegalStateException("无法识别该命令"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    override suspend fun editWorkflowText(
        instruction: String,
        text: String,
        style: WritingStyle,
        punctuation: PunctuationPreference,
        lexicon: List<LexiconTerm>,
        optimizeExpression: Boolean
    ): Result<String> {
        if (text.isBlank()) return Result.failure(IllegalArgumentException("当前没有可处理的文本"))
        val endpoint = endpointProvider()
        if (!endpoint.isComplete()) {
            return Result.failure(IllegalStateException("请先配置文本模型接口"))
        }
        return try {
            val request = buildRequest(
                endpoint = endpoint,
                systemPrompt = workflowEditPrompt(style, punctuation, compactLexicon(lexicon), optimizeExpression),
                userText = "指令：$instruction\n\n待处理文本：\n$text",
                temperature = 0.1,
                stream = false
            )
            Result.success(client.newCall(request).awaitBody { response ->
                check(response.isSuccessful) { MimoApiConfig.responseError(response, "文本工作流失败") }
                extractResponseContent(JSONObject(response.body?.string().orEmpty()), endpoint).trim()
            }.takeIf { it.isNotBlank() } ?: return Result.failure(IllegalStateException("模型未返回修改后的文本")))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    override suspend fun translateToAmericanEnglish(text: String): Result<String> {
        val endpoint = endpointProvider()
        if (!endpoint.isComplete()) return Result.failure(IllegalStateException("请先配置文本模型接口"))
        return try {
            val request = buildRequest(
                endpoint = endpoint,
                systemPrompt = "将用户文本翻译为自然、准确的美式英语。保留原意、数字、专有名词和段落；只输出译文，不要解释或添加引号。",
                userText = text,
                temperature = 0.1,
                stream = false
            )
            Result.success(client.newCall(request).awaitBody { response ->
                check(response.isSuccessful) { MimoApiConfig.responseError(response, "文本模型翻译失败") }
                extractResponseContent(JSONObject(response.body?.string().orEmpty()), endpoint).ifBlank { text }
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun buildRequest(
        endpoint: ModelEndpointConfig,
        systemPrompt: String,
        userText: String,
        temperature: Double,
        stream: Boolean
    ): Request {
        val selectedModel = MimoApiConfig.normalizedTextModel(endpoint.model)
        val (resolvedEndpoint, payload) = when (endpoint.provider.textProtocol) {
            TextProviderProtocol.OPENAI_CHAT -> {
                val messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userText))
                MimoApiConfig.chatCompletionsEndpoint(endpoint.url) to JSONObject()
                    .put("model", selectedModel)
                    .put("temperature", temperature)
                    .put("messages", messages)
                    .apply { if (stream) put("stream", true) }
            }
            TextProviderProtocol.ANTHROPIC_MESSAGES -> {
                MimoApiConfig.anthropicMessagesEndpoint(endpoint.url) to JSONObject()
                    .put("model", selectedModel)
                    .put("max_tokens", 1024)
                    .put("temperature", temperature)
                    .put("system", systemPrompt)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userText)))
                    .apply { if (stream) put("stream", true) }
            }
            TextProviderProtocol.GEMINI_GENERATE_CONTENT -> {
                MimoApiConfig.geminiGenerateContentEndpoint(endpoint.url, selectedModel, stream) to JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                    .put("contents", JSONArray().put(JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", userText)))))
                    .put("generationConfig", JSONObject().put("temperature", temperature))
            }
        }
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        return MimoApiConfig.applyAuthorization(
            Request.Builder().url(resolvedEndpoint).post(body), endpoint.provider, endpoint.apiKey
        ).build()
    }

    suspend fun testConnection(): Result<Unit> {
        val endpoint = endpointProvider()
        if (!endpoint.isComplete()) return Result.failure(IllegalStateException("请完整填写文本模型接口"))
        return try {
            val request = buildRequest(
                endpoint = endpoint,
                systemPrompt = "只回复 OK。",
                userText = "ping",
                temperature = 0.0,
                stream = false
            )
            client.newCall(request).awaitBody { response ->
                check(response.isSuccessful) { MimoApiConfig.responseError(response, "文本模型连接失败") }
                Unit
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun compactLexicon(lexicon: List<LexiconTerm>): List<LexiconTerm> {
        if (lexicon.size <= MAX_LEXICON_TERMS) return lexicon
        return lexicon
            .distinctBy { it.term }
            .sortedWith(
                compareByDescending<LexiconTerm> { it.hint.isNotBlank() }
                    .thenByDescending { it.term.length }
                    .thenBy { it.term }
            )
            .take(MAX_LEXICON_TERMS)
    }

    private fun compactFastLexicon(lexicon: List<LexiconTerm>): List<LexiconTerm> =
        lexicon
            .distinctBy { it.term }
            .sortedWith(
                compareByDescending<LexiconTerm> { it.hint.isNotBlank() }
                    .thenByDescending { it.term.length }
                    .thenBy { it.term }
            )
            .take(MAX_FAST_LEXICON_TERMS)

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

    private fun extractResponseContent(response: JSONObject, endpoint: ModelEndpointConfig): String = when (endpoint.provider.textProtocol) {
        TextProviderProtocol.OPENAI_CHAT -> extractMessageContent(
            response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: JSONObject()
        )
        TextProviderProtocol.ANTHROPIC_MESSAGES -> extractMessageContent(response)
        TextProviderProtocol.GEMINI_GENERATE_CONTENT -> {
            val parts = response.optJSONArray("candidates")
                ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            buildString {
                repeat(parts.length()) { index -> parts.optJSONObject(index)?.optText("text")?.let(::append) }
            }
        }
    }

    private fun extractDeltaContent(payload: JSONObject): String {
        val delta = payload.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("delta")
            ?: return ""
        val direct = delta.optText("content")
        if (direct.isNotBlank()) return direct
        val array = delta.optJSONArray("content") ?: return ""
        return buildString {
            repeat(array.length()) { index ->
                val item = array.optJSONObject(index) ?: return@repeat
                val text = item.optText("text").ifBlank { item.optText("content") }
                if (text.isNotBlank()) append(text)
            }
        }
    }

    private fun extractStreamDelta(payload: JSONObject, endpoint: ModelEndpointConfig): String = when (endpoint.provider.textProtocol) {
        TextProviderProtocol.OPENAI_CHAT -> extractDeltaContent(payload)
        TextProviderProtocol.ANTHROPIC_MESSAGES -> {
            if (payload.optString("type") == "content_block_delta") {
                payload.optJSONObject("delta")?.optText("text").orEmpty()
            } else {
                ""
            }
        }
        TextProviderProtocol.GEMINI_GENERATE_CONTENT -> {
            val parts = payload.optJSONArray("candidates")
                ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            buildString {
                repeat(parts.length()) { index -> parts.optJSONObject(index)?.optText("text")?.let(::append) }
            }
        }
    }

    private fun JSONObject.optText(key: String): String {
        val value = opt(key)
        return when (value) {
            null, JSONObject.NULL -> ""
            is String -> value.takeUnless { it.equals("null", ignoreCase = true) }?.trim().orEmpty()
            else -> value.toString().takeUnless { it.equals("null", ignoreCase = true) }?.trim().orEmpty()
        }
    }

    private fun answerPrompt(): String = """
        你是维刻输入法里的问答助手。
        直接用中文简要回答用户问题，先给结论，再补充必要说明。
        不要复述问题，不要自我介绍，不要编造事实；不确定时直接说明不确定。
        除非用户明确要求，否则控制在三段以内，优先使用短句或简短列表。
    """.trimIndent()

    private fun workflowEditPrompt(
        style: WritingStyle,
        punctuation: PunctuationPreference,
        lexicon: List<LexiconTerm>,
        optimizeExpression: Boolean
    ): String {
        val styleRule = when (style) {
            WritingStyle.OFFICE -> "默认使用正式、清晰的办公表达。"
            WritingStyle.CHAT -> "默认使用自然、简洁的聊天表达。"
            WritingStyle.NOTE -> "默认使用结构清晰的笔记表达。"
            WritingStyle.RAW -> "除用户明确要求外，保持原有表达。"
        }
        val punctuationRule = when (punctuation) {
            PunctuationPreference.SMART -> "使用自然、完整的标点。"
            PunctuationPreference.SPACES -> "将标点替换为空格，并合并多余空格。"
            PunctuationPreference.NO_END -> "保留句内标点，但不添加末尾标点。"
        }
        val lexiconRule = lexicon.joinToString("、") { term ->
            if (term.hint.isBlank()) term.term else "${term.term}（${term.hint}）"
        }.ifBlank { "无" }
        val structureRule = if (optimizeExpression) {
            "若原文已有清单、步骤或分点结构，保留并用真实换行整理；不要凭空增加事实。"
        } else {
            "不要主动改变原文结构，除非用户指令明确要求。"
        }
        return """
            你是维刻输入法的文本编辑智能体。
            只处理用户提供的“待处理文本”，严格执行“指令”。
            $styleRule
            $punctuationRule
            $structureRule
            保留人名、数字、日期、链接、代码、品牌名和专业术语；不得编造事实或添加解释。
            专业词：$lexiconRule。
            只输出完整的修改后文本，不要标题、说明、引号、Markdown 代码块或前后缀。
        """.trimIndent()
    }

    private fun fastPrompt(
        style: WritingStyle,
        punctuation: PunctuationPreference,
        lexicon: List<LexiconTerm>
    ): String {
        val styleRule = when (style) {
            WritingStyle.OFFICE -> "偏正式。"
            WritingStyle.CHAT -> "自然口语。"
            WritingStyle.NOTE -> "简洁笔记。"
            WritingStyle.RAW -> "尽量原样。"
        }
        val punctuationRule = when (punctuation) {
            PunctuationPreference.SMART -> "补标点。"
            PunctuationPreference.SPACES -> "标点改空格。"
            PunctuationPreference.NO_END -> "末尾不要标点。"
        }
        val lexiconRule = lexicon.joinToString("；") { term ->
            if (term.hint.isBlank()) term.term else "${term.term}（${term.hint}）"
        }
        return buildString {
            append("润色语音转写，$styleRule $punctuationRule ")
            append("去掉嗯啊、明显重复和口误；保留数字、日期、英文、专名；不增事实；只输出正文。")
            if (lexiconRule.isNotBlank()) append(" 术语：").append(lexiconRule).append("。")
        }
    }

    private fun prompt(
        style: WritingStyle,
        punctuation: PunctuationPreference,
        lexicon: List<LexiconTerm>,
        optimizeExpression: Boolean,
        forceStructure: Boolean
    ): String {
        val styleRule = when (style) {
            WritingStyle.OFFICE -> "改写为正式、清晰的办公表达。"
            WritingStyle.CHAT -> "改写为自然、口语化但简洁的聊天表达。"
            WritingStyle.NOTE -> "整理为简洁笔记；内容并列时优先分点或分段。"
            WritingStyle.RAW -> "尽量保持原样。"
        }
        val punctuationRule = when (punctuation) {
            PunctuationPreference.SMART -> "按语义补全并优化标点。"
            PunctuationPreference.SPACES -> "将输出里的标点替换为空格，并合并多余空格。"
            PunctuationPreference.NO_END -> "保留句内标点，但删除最终文本末尾的标点。"
        }
        val structureRule = if (forceStructure) {
            """
            这段口述已被判定为结构化内容。必须真正换行输出，不能只补标点或空格后仍保留为一段连续文字。
            结构判断：只有 1 件事用完整段落；有 2 件及以上可区分事项时必须编号；3 件以上且存在不同主题时先按主题分组。
            同一类待办、步骤或清单保持扁平列表，使用“1.内容”“2.内容”“3.内容”；不同主题才使用一级主题和缩进的“(a)内容”“(b)内容”。
            将“一是、二是、三是”“第一、第二、第三”“首先、其次、最后”“包括、分别是、原因有、分为”等标记和中文停顿拆成独立条目。
            有总述时先保留总述并以冒号结束，再换行列出条目；括号中的补充说明写为对应条目下的“备注：”。
            不得凭空增加事项、主题、事实或结论；不得把用户的问题改成答案。
            示例：
            输入：今天要做三件事，第一睡觉，第二刷牙，第三洗澡
            输出：
            今天要做三件事：
            1.睡觉
            2.刷牙
            3.洗澡
            """.trimIndent()
        } else if (optimizeExpression) {
            """
            当口述包含分类、分点、步骤、待办、提纲、框架、并列事项或“包括/原因有/分为”等结构信号时，必须整理成真正换行的编号列表。
            两个及以上事项使用 1.、2.、3.；同类事项保持扁平，不要为了复杂而强行分组；不同主题才增加主题层和 (a)、(b) 子项。
            对含有“一是/二是/三是、第一/第二/第三、首先/其次/最后、分别是”等标记的内容，不能只补标点后保留为连续段落。
            保留全部原意，不补充新事实。括号中的补充说明优先整理成对应条目的“备注：”。
            对观点对立类作文框架，按开头段、中间段、结尾段分组，再拆成可读子项。
            """.trimIndent()
        } else {
            "不要主动把普通句子改造成复杂提纲；仅在原文已经明显分点时再整理结构。"
        }
        val lexiconRule = lexicon.joinToString("；") { term ->
            if (term.hint.isBlank()) term.term else "${term.term}（${term.hint}）"
        }.ifBlank { "无" }
        return """
            你是中文语音转写编辑。
            $styleRule
            $punctuationRule
            $structureRule
            删除无意义语气词、明显重复和自我纠正，但必须保留人名、数字、日期、英文、代码、品牌名和专业术语。
            不得补充事实，不得改变核心含义。
            只输出整理后的正文，不要标题、解释或引号。
            专业词库：$lexiconRule。
        """.trimIndent()
    }

    private fun needsStructuredExpression(text: String): Boolean {
        if (StructuredExpressionFormatter.needsStructure(text)) return true
        val normalized = text.replace(Regex("\\s+"), "")
        val cues = listOf(
            "一是", "二是", "三是", "第一", "第二", "第三",
            "首先", "其次", "最后", "分别是", "分为", "几件事",
            "待办", "步骤", "提纲", "框架", "开头段", "中间段", "结尾段",
            "正方观点", "反方观点"
        )
        return cues.count { normalized.contains(it) } >= 2 ||
            cues.any { normalized.contains(it) } &&
            (normalized.contains("和") || normalized.contains("、") || normalized.contains("还有"))
    }
}
