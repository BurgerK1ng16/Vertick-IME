package com.weike.ime.data

enum class WritingStyle(val displayName: String) {
    OFFICE("办公正式"),
    CHAT("聊天自然"),
    NOTE("笔记简洁"),
    RAW("不润色")
}

enum class PunctuationPreference(val displayName: String) {
    SMART("智能标点"),
    SPACES("空格代替标点"),
    NO_END("句末不添加标点")
}

enum class HapticStrength(val displayName: String, val storedValue: Int, val amplitude: Int) {
    OFF("无", 0, 0),
    SYSTEM("跟随系统触感", 4, 0),
    WEAK("弱", 1, 55),
    MEDIUM("适中", 2, 125),
    FAIRLY_STRONG("较强", 5, 190),
    STRONG("强", 3, 255)
}

enum class KeyboardTheme(val displayName: String) {
    DARK("暗黑模式"),
    LIGHT("亮色模式"),
    SYSTEM("自适应系统")
}

/**
 * Text providers supported by the cloud configuration screen.
 *
 * The ASR picker intentionally exposes only MiMo and Custom: normal text-model
 * endpoints are not interchangeable with the MiMo audio chat protocol.
 */
enum class CloudProvider(
    val displayName: String,
    val iconResourceName: String? = null,
    val textProtocol: TextProviderProtocol = TextProviderProtocol.OPENAI_CHAT,
    val endpointPreset: String = "",
    val defaultTextModel: String = "",
    val endpointRequiresUserValue: Boolean = false
) {
    XIAOMI_MIMO("Xiaomi MiMo", null, TextProviderProtocol.OPENAI_CHAT, "https://api.xiaomimimo.com/v1", "MiMo-V2.5"),
    XIAOMI_MIMO_PLAN("Xiaomi MiMo Plan", null, TextProviderProtocol.OPENAI_CHAT, "https://token-plan-cn.xiaomimimo.com/v1", "MiMo-V2.5"),
    OPENAI("OpenAI", "provider_openai", TextProviderProtocol.OPENAI_CHAT, "https://api.openai.com/v1"),
    ZHIPU("\u667a\u8c31", "provider_zhipu", TextProviderProtocol.OPENAI_CHAT, "https://open.bigmodel.cn/api/paas/v4"),
    QWEN("\u5343\u95ee", "provider_qwen", TextProviderProtocol.OPENAI_CHAT, "https://dashscope.aliyuncs.com/compatible-mode/v1"),
    DOUBAO("\u8c46\u5305", "provider_doubao", TextProviderProtocol.OPENAI_CHAT, "https://ark.cn-beijing.volces.com/api/v3"),
    AIHUBMIX("\u63a8\u7406\u65f6\u4ee3", "provider_aihubmix", TextProviderProtocol.OPENAI_CHAT, "https://aihubmix.com/v1"),
    ALIBABA_CLOUD("\u963f\u91cc\u4e91", "provider_alibaba_cloud", TextProviderProtocol.OPENAI_CHAT, "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"),
    BAIDU_CLOUD("\u767e\u5ea6\u667a\u80fd\u4e91", "provider_baidu_cloud", TextProviderProtocol.OPENAI_CHAT, "https://qianfan.baidubce.com/v2"),
    BAILIAN("\u963f\u91cc\u4e91\u767e\u70bc", "provider_bailian", TextProviderProtocol.OPENAI_CHAT, "https://dashscope.aliyuncs.com/compatible-mode/v1"),
    CLOUDFLARE("Cloudflare", "provider_cloudflare", TextProviderProtocol.OPENAI_CHAT, "https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}/ai/v1", endpointRequiresUserValue = true),
    // CodeBuddy does not publish one stable public data-plane endpoint. Keep it
    // selectable, but require the endpoint supplied by the user's workspace.
    CODEBUDDY("CodeBuddy", "provider_codebuddy", TextProviderProtocol.OPENAI_CHAT, endpointRequiresUserValue = true),
    DEEPSEEK("DeepSeek", "provider_deepseek", TextProviderProtocol.OPENAI_CHAT, "https://api.deepseek.com/v1"),
    HUNYUAN("\u817e\u8baf\u6df7\u5143", "provider_hunyuan", TextProviderProtocol.OPENAI_CHAT, "https://api.hunyuan.cloud.tencent.com/v1"),
    SILICON_CLOUD("SiliconCloud", "provider_siliconcloud", TextProviderProtocol.OPENAI_CHAT, "https://api.siliconflow.cn/v1"),
    WENXIN("\u6587\u5fc3\u4e00\u8a00", "provider_wenxin", TextProviderProtocol.OPENAI_CHAT, "https://qianfan.baidubce.com/v2"),
    VOLCENGINE("\u706b\u5c71\u5f15\u64ce", "provider_volcengine", TextProviderProtocol.OPENAI_CHAT, "https://ark.cn-beijing.volces.com/api/v3"),
    CLAUDE("Claude", "provider_claude", TextProviderProtocol.ANTHROPIC_MESSAGES, "https://api.anthropic.com/v1"),
    ANTHROPIC("Anthropic", "provider_anthropic", TextProviderProtocol.ANTHROPIC_MESSAGES, "https://api.anthropic.com/v1"),
    GEMINI("Gemini", "provider_gemini", TextProviderProtocol.GEMINI_GENERATE_CONTENT, "https://generativelanguage.googleapis.com/v1beta"),
    OLLAMA("Ollama", "provider_ollama", TextProviderProtocol.OPENAI_CHAT, "https://your-ollama-host.example/v1", endpointRequiresUserValue = true),
    AZURE("Azure OpenAI", "provider_azure", TextProviderProtocol.OPENAI_CHAT, "https://{RESOURCE}.openai.azure.com/openai/v1", endpointRequiresUserValue = true),
    CUSTOM("\u81ea\u5b9a\u4e49\u6a21\u578b", null, TextProviderProtocol.OPENAI_CHAT)
}

enum class TextProviderProtocol { OPENAI_CHAT, ANTHROPIC_MESSAGES, GEMINI_GENERATE_CONTENT }

enum class ChineseKeyboardLayout(val displayName: String) {
    FULL("26键全键盘拼音"),
    NINE_KEY("九宫格拼音")
}

enum class HistoryRetention(val displayName: String, val durationMs: Long?) {
    NEVER("不保留", 0L),
    HOURS_24("24小时", 24L * 60L * 60L * 1000L),
    WEEK("1周", 7L * 24L * 60L * 60L * 1000L),
    MONTH("一个月", 30L * 24L * 60L * 60L * 1000L),
    FOREVER("永久保留", null)
}

/** The order in this list is also the order of the keyboard's top-right mode switcher. */
enum class KeyboardModePreference(val displayName: String) {
    VOICE("语音"),
    TEXT("中文/英文"),
    ASK("问答"),
    CLIPBOARD("剪贴板")
}

enum class KeyboardStartupMode(val displayName: String) {
    LAST_USED("默认（上次使用的模式）"),
    VOICE("听写"),
    PINYIN("拼音"),
    ENGLISH("英文"),
    ASK("问答"),
    CLIPBOARD("剪贴板")
}

enum class KeyboardLogoStyle(val displayName: String) {
    VERTICK("维刻默认"),
    OPENLESS("OpenLess"),
    CUSTOM("自定义图片")
}

data class KeyboardLogoConfig(
    val style: KeyboardLogoStyle = KeyboardLogoStyle.VERTICK,
    val darkPath: String = "",
    val lightPath: String = ""
)

enum class InputHistoryType(val displayName: String) {
    DICTATION("听写"),
    POLISH("润色"),
    TRANSLATION("翻译"),
    QUESTION("问答")
}

data class AppStyleOverride(val packageName: String, val style: WritingStyle)

/** A user-managed OpenAI-compatible endpoint. Keys stay in this device's private app data. */
data class ModelEndpointConfig(
    val url: String = "",
    val apiKey: String = "",
    val model: String = "",
    val provider: CloudProvider = CloudProvider.CUSTOM
) {
    fun isComplete(): Boolean = url.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

data class CloudApiSettings(
    val asr: ModelEndpointConfig = ModelEndpointConfig(),
    val text: ModelEndpointConfig = ModelEndpointConfig()
)

data class RecognitionResult(
    val text: String,
    val isFinal: Boolean = false,
    val error: String? = null
)

sealed interface VoiceUiState {
    data object Idle : VoiceUiState
    data object Listening : VoiceUiState
    data object Processing : VoiceUiState
    data class Preview(
        val question: String = "",
        val text: String,
        val isFallback: Boolean = false,
        val streaming: Boolean = false
    ) : VoiceUiState
    data class Error(val message: String) : VoiceUiState
}
