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
    val model: String = ""
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
