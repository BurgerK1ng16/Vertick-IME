package com.weike.ime.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.settingsDataStore by preferencesDataStore("weike_settings")

class AppSettingsRepository(private val context: Context) {
    private val startupPreferences = context.applicationContext.getSharedPreferences(
        KEYBOARD_STARTUP_PREFERENCES,
        Context.MODE_PRIVATE
    )
    private val overridesKey = stringPreferencesKey("style_overrides")
    private val punctuationKey = stringPreferencesKey("punctuation_preference")
    private val hapticStrengthKey = intPreferencesKey("haptic_strength")
    private val expressionOptimizationKey = booleanPreferencesKey("expression_optimization")
    private val keyboardThemeKey = stringPreferencesKey("keyboard_theme")
    private val keyboardSoundVolumeKey = floatPreferencesKey("keyboard_sound_volume")
    private val keyboardCloseButtonKey = booleanPreferencesKey("keyboard_close_button")
    private val candidateTextSizeLevelKey = intPreferencesKey("candidate_text_size_level")
    private val englishAutoCapitalizeKey = booleanPreferencesKey("english_auto_capitalize")
    private val doubleSpacePeriodKey = booleanPreferencesKey("double_space_period")
    private val historyRetentionKey = stringPreferencesKey("history_retention")
    private val keyboardModesKey = stringPreferencesKey("keyboard_modes")
    private val chineseKeyboardLayoutKey = stringPreferencesKey("chinese_keyboard_layout")
    private val nineKeySymbolsKey = stringPreferencesKey("nine_key_symbols")
    // Legacy plaintext keys are read once, migrated into SecureSecretStore, then deleted.
    private val asrUrlKey = stringPreferencesKey("asr_api_url")
    private val asrApiKeyKey = stringPreferencesKey("asr_api_key")
    private val asrModelKey = stringPreferencesKey("asr_api_model")
    private val textUrlKey = stringPreferencesKey("text_api_url")
    private val textApiKeyKey = stringPreferencesKey("text_api_key")
    private val textModelKey = stringPreferencesKey("text_api_model")
    private val asrProviderKey = stringPreferencesKey("asr_provider")
    private val textProviderKey = stringPreferencesKey("text_provider")
    private val clipboardHistoryEnabledKey = booleanPreferencesKey("clipboard_history_enabled")
    private val secrets = SecureSecretStore(context)
    private val secretMigrationMutex = Mutex()
    private var secretsMigrated = false

    val overrides = context.settingsDataStore.data.map { prefs -> decode(prefs[overridesKey].orEmpty()) }
    val punctuation = context.settingsDataStore.data.map { prefs ->
        runCatching { PunctuationPreference.valueOf(prefs[punctuationKey].orEmpty()) }.getOrDefault(PunctuationPreference.SMART)
    }
    val hapticStrength = context.settingsDataStore.data.map { prefs ->
        HapticStrength.entries.firstOrNull { it.storedValue == prefs[hapticStrengthKey] } ?: HapticStrength.MEDIUM
    }
    val expressionOptimization = context.settingsDataStore.data.map { prefs ->
        prefs[expressionOptimizationKey] ?: false
    }
    val keyboardTheme = context.settingsDataStore.data.map { prefs ->
        runCatching { KeyboardTheme.valueOf(prefs[keyboardThemeKey].orEmpty()) }.getOrDefault(KeyboardTheme.DARK)
    }
    val keyboardSoundVolume = context.settingsDataStore.data.map { prefs ->
        (prefs[keyboardSoundVolumeKey] ?: DEFAULT_KEYBOARD_SOUND_VOLUME).coerceIn(0f, 1f)
    }
    val keyboardCloseButtonEnabled = context.settingsDataStore.data.map { prefs ->
        prefs[keyboardCloseButtonKey] ?: true
    }
    val candidateTextSizeLevel = context.settingsDataStore.data.map { prefs ->
        (prefs[candidateTextSizeLevelKey] ?: DEFAULT_CANDIDATE_TEXT_SIZE_LEVEL)
            .coerceIn(MIN_CANDIDATE_TEXT_SIZE_LEVEL, MAX_CANDIDATE_TEXT_SIZE_LEVEL)
    }
    val englishAutoCapitalize = context.settingsDataStore.data.map { prefs ->
        prefs[englishAutoCapitalizeKey] ?: true
    }
    val doubleSpacePeriod = context.settingsDataStore.data.map { prefs ->
        prefs[doubleSpacePeriodKey] ?: false
    }
    val historyRetention = context.settingsDataStore.data.map { prefs ->
        runCatching { HistoryRetention.valueOf(prefs[historyRetentionKey].orEmpty()) }
            .getOrDefault(HistoryRetention.NEVER)
    }
    val keyboardModes = context.settingsDataStore.data.map { prefs ->
        decodeKeyboardModes(prefs[keyboardModesKey].orEmpty())
    }
    val chineseKeyboardLayout = context.settingsDataStore.data.map { prefs ->
        runCatching { ChineseKeyboardLayout.valueOf(prefs[chineseKeyboardLayoutKey].orEmpty()) }
            .getOrDefault(ChineseKeyboardLayout.FULL)
    }
    val nineKeySymbols = context.settingsDataStore.data.map { prefs ->
        decodeNineKeySymbols(prefs[nineKeySymbolsKey].orEmpty())
    }
    val cloudApiSettings = flow {
        migrateCloudSecrets()
        emitAll(context.settingsDataStore.data.map { prefs ->
            CloudApiSettings(
                asr = ModelEndpointConfig(
                    prefs[asrUrlKey].orEmpty(),
                    secrets.read(SECURE_ASR_KEY).orEmpty(),
                    prefs[asrModelKey].orEmpty()
                ),
                text = ModelEndpointConfig(
                    prefs[textUrlKey].orEmpty(),
                    secrets.read(SECURE_TEXT_KEY).orEmpty(),
                    prefs[textModelKey].orEmpty()
                )
            )
        })
    }
    val clipboardHistoryEnabled = context.settingsDataStore.data.map { prefs ->
        prefs[clipboardHistoryEnabledKey] ?: false
    }

    suspend fun styleFor(packageName: String): WritingStyle {
        return overrides.first()[packageName] ?: defaultStyleFor(packageName)
    }

    suspend fun punctuationPreference(): PunctuationPreference = punctuation.first()

    suspend fun savePunctuationPreference(preference: PunctuationPreference) {
        context.settingsDataStore.edit { prefs -> prefs[punctuationKey] = preference.name }
    }

    suspend fun hapticStrength(): HapticStrength = hapticStrength.first()

    suspend fun saveHapticStrength(strength: HapticStrength) {
        startupPreferences.edit().putInt(STARTUP_HAPTIC, strength.storedValue).apply()
        context.settingsDataStore.edit { prefs -> prefs[hapticStrengthKey] = strength.storedValue }
    }

    suspend fun expressionOptimizationEnabled(): Boolean = expressionOptimization.first()

    suspend fun saveExpressionOptimization(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[expressionOptimizationKey] = enabled }
    }

    suspend fun keyboardTheme(): KeyboardTheme = keyboardTheme.first()

    suspend fun saveKeyboardTheme(theme: KeyboardTheme) {
        startupPreferences.edit().putString(STARTUP_THEME, theme.name).apply()
        context.settingsDataStore.edit { prefs -> prefs[keyboardThemeKey] = theme.name }
    }

    suspend fun keyboardSoundVolume(): Float = keyboardSoundVolume.first()

    suspend fun saveKeyboardSoundVolume(volume: Float) {
        val normalized = volume.coerceIn(0f, 1f)
        startupPreferences.edit().putFloat(STARTUP_SOUND_VOLUME, normalized).apply()
        context.settingsDataStore.edit { prefs -> prefs[keyboardSoundVolumeKey] = normalized }
    }

    suspend fun keyboardCloseButtonEnabled(): Boolean = keyboardCloseButtonEnabled.first()

    suspend fun saveKeyboardCloseButtonEnabled(enabled: Boolean) {
        startupPreferences.edit().putBoolean(STARTUP_CLOSE_BUTTON, enabled).apply()
        context.settingsDataStore.edit { prefs -> prefs[keyboardCloseButtonKey] = enabled }
    }

    suspend fun candidateTextSizeLevel(): Int = candidateTextSizeLevel.first()

    suspend fun saveCandidateTextSizeLevel(level: Int) {
        val normalized = level.coerceIn(MIN_CANDIDATE_TEXT_SIZE_LEVEL, MAX_CANDIDATE_TEXT_SIZE_LEVEL)
        startupPreferences.edit().putInt(STARTUP_CANDIDATE_TEXT_SIZE_LEVEL, normalized).apply()
        context.settingsDataStore.edit { prefs -> prefs[candidateTextSizeLevelKey] = normalized }
    }

    suspend fun englishAutoCapitalize(): Boolean = englishAutoCapitalize.first()

    suspend fun saveEnglishAutoCapitalize(enabled: Boolean) {
        startupPreferences.edit().putBoolean(STARTUP_ENGLISH_AUTO_CAPITALIZE, enabled).apply()
        context.settingsDataStore.edit { prefs -> prefs[englishAutoCapitalizeKey] = enabled }
    }

    suspend fun doubleSpacePeriod(): Boolean = doubleSpacePeriod.first()

    suspend fun saveDoubleSpacePeriod(enabled: Boolean) {
        startupPreferences.edit().putBoolean(STARTUP_DOUBLE_SPACE_PERIOD, enabled).apply()
        context.settingsDataStore.edit { prefs -> prefs[doubleSpacePeriodKey] = enabled }
    }

    suspend fun historyRetention(): HistoryRetention = historyRetention.first()

    suspend fun saveHistoryRetention(retention: HistoryRetention) {
        context.settingsDataStore.edit { prefs -> prefs[historyRetentionKey] = retention.name }
    }

    suspend fun keyboardModes(): List<KeyboardModePreference> = keyboardModes.first()

    suspend fun saveKeyboardModes(modes: List<KeyboardModePreference>) {
        val normalized = modes.distinct().ifEmpty { DEFAULT_KEYBOARD_MODES }
        startupPreferences.edit().putString(STARTUP_MODES, normalized.joinToString(",") { it.name }).apply()
        context.settingsDataStore.edit { prefs ->
            prefs[keyboardModesKey] = normalized.joinToString(",") { it.name }
        }
    }

    suspend fun chineseKeyboardLayout(): ChineseKeyboardLayout = chineseKeyboardLayout.first()

    suspend fun saveChineseKeyboardLayout(layout: ChineseKeyboardLayout) {
        startupPreferences.edit().putString(STARTUP_CHINESE_LAYOUT, layout.name).apply()
        context.settingsDataStore.edit { prefs -> prefs[chineseKeyboardLayoutKey] = layout.name }
    }

    /**
     * DataStore is asynchronous. An IME may be recreated during a display
     * rotation before its first collection arrives, so keep this non-sensitive
     * rendering snapshot in SharedPreferences for synchronous startup.
     */
    fun keyboardStartupState(): KeyboardStartupState {
        val theme = startupPreferences.getString(STARTUP_THEME, null)
            ?.let { runCatching { KeyboardTheme.valueOf(it) }.getOrNull() }
            ?: KeyboardTheme.DARK
        val layout = startupPreferences.getString(STARTUP_CHINESE_LAYOUT, null)
            ?.let { runCatching { ChineseKeyboardLayout.valueOf(it) }.getOrNull() }
            ?: ChineseKeyboardLayout.FULL
        val modes = decodeKeyboardModes(startupPreferences.getString(STARTUP_MODES, null).orEmpty())
        val haptic = HapticStrength.entries.firstOrNull {
            it.storedValue == startupPreferences.getInt(STARTUP_HAPTIC, HapticStrength.MEDIUM.storedValue)
        } ?: HapticStrength.MEDIUM
        val volume = startupPreferences.getFloat(STARTUP_SOUND_VOLUME, DEFAULT_KEYBOARD_SOUND_VOLUME)
            .coerceIn(0f, 1f)
        val closeButtonEnabled = startupPreferences.getBoolean(STARTUP_CLOSE_BUTTON, true)
        val candidateTextSizeLevel = startupPreferences.getInt(STARTUP_CANDIDATE_TEXT_SIZE_LEVEL, DEFAULT_CANDIDATE_TEXT_SIZE_LEVEL)
            .coerceIn(MIN_CANDIDATE_TEXT_SIZE_LEVEL, MAX_CANDIDATE_TEXT_SIZE_LEVEL)
        val englishAutoCapitalize = startupPreferences.getBoolean(STARTUP_ENGLISH_AUTO_CAPITALIZE, true)
        val doubleSpacePeriod = startupPreferences.getBoolean(STARTUP_DOUBLE_SPACE_PERIOD, false)
        return KeyboardStartupState(
            theme, layout, modes, haptic, volume, closeButtonEnabled, candidateTextSizeLevel,
            englishAutoCapitalize, doubleSpacePeriod, startupPreferences.contains(STARTUP_THEME)
        )
    }

    /** A one-time upgrade bridge for an IME recreated before DataStore emits. */
    fun keyboardStartupStateBlocking(): KeyboardStartupState = runBlocking {
        val prefs = context.settingsDataStore.data.first()
        val theme = runCatching { KeyboardTheme.valueOf(prefs[keyboardThemeKey].orEmpty()) }
            .getOrDefault(KeyboardTheme.DARK)
        val layout = runCatching { ChineseKeyboardLayout.valueOf(prefs[chineseKeyboardLayoutKey].orEmpty()) }
            .getOrDefault(ChineseKeyboardLayout.FULL)
        val modes = decodeKeyboardModes(prefs[keyboardModesKey].orEmpty())
        val haptic = HapticStrength.entries.firstOrNull { it.storedValue == prefs[hapticStrengthKey] }
            ?: HapticStrength.MEDIUM
        val volume = (prefs[keyboardSoundVolumeKey] ?: DEFAULT_KEYBOARD_SOUND_VOLUME).coerceIn(0f, 1f)
        val closeButtonEnabled = prefs[keyboardCloseButtonKey] ?: true
        val candidateTextSizeLevel = (prefs[candidateTextSizeLevelKey] ?: DEFAULT_CANDIDATE_TEXT_SIZE_LEVEL)
            .coerceIn(MIN_CANDIDATE_TEXT_SIZE_LEVEL, MAX_CANDIDATE_TEXT_SIZE_LEVEL)
        val englishAutoCapitalize = prefs[englishAutoCapitalizeKey] ?: true
        val doubleSpacePeriod = prefs[doubleSpacePeriodKey] ?: false
        cacheKeyboardStartupState(
            theme, layout, modes, haptic, volume, closeButtonEnabled, candidateTextSizeLevel,
            englishAutoCapitalize, doubleSpacePeriod
        )
        KeyboardStartupState(
            theme, layout, modes, haptic, volume, closeButtonEnabled, candidateTextSizeLevel,
            englishAutoCapitalize, doubleSpacePeriod, true
        )
    }

    fun cacheKeyboardStartupState(
        theme: KeyboardTheme? = null,
        layout: ChineseKeyboardLayout? = null,
        modes: List<KeyboardModePreference>? = null,
        haptic: HapticStrength? = null,
        soundVolume: Float? = null,
        closeButtonEnabled: Boolean? = null,
        candidateTextSizeLevel: Int? = null,
        englishAutoCapitalize: Boolean? = null,
        doubleSpacePeriod: Boolean? = null
    ) {
        startupPreferences.edit().apply {
            theme?.let { putString(STARTUP_THEME, it.name) }
            layout?.let { putString(STARTUP_CHINESE_LAYOUT, it.name) }
            modes?.let { putString(STARTUP_MODES, it.joinToString(",") { mode -> mode.name }) }
            haptic?.let { putInt(STARTUP_HAPTIC, it.storedValue) }
            soundVolume?.let { putFloat(STARTUP_SOUND_VOLUME, it.coerceIn(0f, 1f)) }
            closeButtonEnabled?.let { putBoolean(STARTUP_CLOSE_BUTTON, it) }
            candidateTextSizeLevel?.let { putInt(STARTUP_CANDIDATE_TEXT_SIZE_LEVEL, it.coerceIn(MIN_CANDIDATE_TEXT_SIZE_LEVEL, MAX_CANDIDATE_TEXT_SIZE_LEVEL)) }
            englishAutoCapitalize?.let { putBoolean(STARTUP_ENGLISH_AUTO_CAPITALIZE, it) }
            doubleSpacePeriod?.let { putBoolean(STARTUP_DOUBLE_SPACE_PERIOD, it) }
        }.apply()
    }

    suspend fun nineKeySymbols(): List<String> = nineKeySymbols.first()

    suspend fun saveNineKeySymbols(symbols: List<String>) {
        val normalized = symbols.map(String::trim).filter(String::isNotBlank).distinct().take(MAX_NINE_KEY_SYMBOLS)
            .ifEmpty { DEFAULT_NINE_KEY_SYMBOLS }
        context.settingsDataStore.edit { prefs -> prefs[nineKeySymbolsKey] = normalized.joinToString("\n") }
    }

    suspend fun clipboardHistoryEnabled(): Boolean = clipboardHistoryEnabled.first()

    suspend fun saveClipboardHistoryEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[clipboardHistoryEnabledKey] = enabled }
    }

    suspend fun saveAsrApi(config: ModelEndpointConfig) {
        saveAsrApi(config, CloudProvider.CUSTOM)
    }

    suspend fun asrProvider(): CloudProvider = context.settingsDataStore.data.first().let { prefs ->
        runCatching { CloudProvider.valueOf(prefs[asrProviderKey].orEmpty()) }.getOrDefault(CloudProvider.CUSTOM)
    }

    suspend fun saveAsrApi(config: ModelEndpointConfig, provider: CloudProvider) {
        secrets.write(SECURE_ASR_KEY, config.apiKey.trim())
        context.settingsDataStore.edit { prefs ->
            prefs[asrUrlKey] = config.url.trim()
            prefs[asrModelKey] = config.model.trim()
            prefs[asrProviderKey] = provider.name
            prefs.remove(asrApiKeyKey)
        }
    }

    suspend fun saveTextApi(config: ModelEndpointConfig) {
        saveTextApi(config, CloudProvider.CUSTOM)
    }

    suspend fun textProvider(): CloudProvider = context.settingsDataStore.data.first().let { prefs ->
        runCatching { CloudProvider.valueOf(prefs[textProviderKey].orEmpty()) }.getOrDefault(CloudProvider.CUSTOM)
    }

    suspend fun saveTextApi(config: ModelEndpointConfig, provider: CloudProvider) {
        secrets.write(SECURE_TEXT_KEY, config.apiKey.trim())
        context.settingsDataStore.edit { prefs ->
            prefs[textUrlKey] = config.url.trim()
            prefs[textModelKey] = config.model.trim()
            prefs[textProviderKey] = provider.name
            prefs.remove(textApiKeyKey)
        }
    }

    private suspend fun migrateCloudSecrets() {
        secretMigrationMutex.withLock {
            if (secretsMigrated) return
            val prefs = context.settingsDataStore.data.first()
            val legacyAsr = prefs[asrApiKeyKey].orEmpty()
            val legacyText = prefs[textApiKeyKey].orEmpty()
            if (secrets.read(SECURE_ASR_KEY).isNullOrBlank() && legacyAsr.isNotBlank()) secrets.write(SECURE_ASR_KEY, legacyAsr)
            if (secrets.read(SECURE_TEXT_KEY).isNullOrBlank() && legacyText.isNotBlank()) secrets.write(SECURE_TEXT_KEY, legacyText)
            if (legacyAsr.isNotBlank() || legacyText.isNotBlank()) {
                context.settingsDataStore.edit { updated ->
                    updated.remove(asrApiKeyKey)
                    updated.remove(textApiKeyKey)
                }
            }
            secretsMigrated = true
        }
    }

    suspend fun saveOverride(packageName: String, style: WritingStyle) {
        val cleaned = packageName.trim()
        if (cleaned.isEmpty()) return
        context.settingsDataStore.edit { prefs ->
            val current = decode(prefs[overridesKey].orEmpty()).toMutableMap()
            current[cleaned] = style
            prefs[overridesKey] = encode(current)
        }
    }

    suspend fun removeOverride(packageName: String) {
        context.settingsDataStore.edit { prefs ->
            val current = decode(prefs[overridesKey].orEmpty()).toMutableMap()
            current.remove(packageName)
            prefs[overridesKey] = encode(current)
        }
    }

    private fun defaultStyleFor(packageName: String): WritingStyle = when {
        packageName in CHAT_PACKAGES -> WritingStyle.CHAT
        packageName in OFFICE_PACKAGES -> WritingStyle.OFFICE
        packageName in NOTE_PACKAGES -> WritingStyle.NOTE
        else -> WritingStyle.CHAT
    }

    private fun decode(value: String): Map<String, WritingStyle> = value
        .split('\n')
        .mapNotNull { line ->
            val parts = line.split('|', limit = 2)
            val style = parts.getOrNull(1)?.let { runCatching { WritingStyle.valueOf(it) }.getOrNull() }
            if (parts.size == 2 && parts[0].isNotBlank() && style != null) parts[0] to style else null
        }
        .toMap()

    private fun encode(value: Map<String, WritingStyle>): String = value.entries.joinToString("\n") {
        "${it.key}|${it.value.name}"
    }

    private fun decodeKeyboardModes(value: String): List<KeyboardModePreference> {
        val parsed = value.split(',').mapNotNull { name ->
            when (name) {
                // Upgrade the old two-button text configuration without changing user order.
                "PINYIN", "ENGLISH", "TEXT" -> KeyboardModePreference.TEXT
                else -> runCatching { KeyboardModePreference.valueOf(name) }.getOrNull()
            }
        }.distinct()
        return parsed.ifEmpty { DEFAULT_KEYBOARD_MODES }
    }

    private fun decodeNineKeySymbols(value: String): List<String> = value.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .take(MAX_NINE_KEY_SYMBOLS)
        .toList()
        .ifEmpty { DEFAULT_NINE_KEY_SYMBOLS }

    companion object {
        private const val KEYBOARD_STARTUP_PREFERENCES = "keyboard_startup_state"
        private const val STARTUP_THEME = "theme"
        private const val STARTUP_CHINESE_LAYOUT = "chinese_layout"
        private const val STARTUP_MODES = "modes"
        private const val STARTUP_HAPTIC = "haptic"
        private const val STARTUP_SOUND_VOLUME = "sound_volume"
        private const val STARTUP_CLOSE_BUTTON = "close_button"
        private const val STARTUP_CANDIDATE_TEXT_SIZE_LEVEL = "candidate_text_size_level"
        private const val STARTUP_ENGLISH_AUTO_CAPITALIZE = "english_auto_capitalize"
        private const val STARTUP_DOUBLE_SPACE_PERIOD = "double_space_period"
        private const val SECURE_ASR_KEY = "asr_api_key"
        private const val SECURE_TEXT_KEY = "text_api_key"
        const val DEFAULT_KEYBOARD_SOUND_VOLUME = .45f
        const val MIN_CANDIDATE_TEXT_SIZE_LEVEL = -3
        const val DEFAULT_CANDIDATE_TEXT_SIZE_LEVEL = 0
        const val MAX_CANDIDATE_TEXT_SIZE_LEVEL = 3
        const val MAX_NINE_KEY_SYMBOLS = 16
        val DEFAULT_NINE_KEY_SYMBOLS = listOf("，", "。", "？", "！", "…", "：", "、", "～")
        val DEFAULT_KEYBOARD_MODES = listOf(
            KeyboardModePreference.VOICE,
            KeyboardModePreference.TEXT
        )
        private val CHAT_PACKAGES = setOf(
            "com.tencent.mm", "com.tencent.mobileqq", "com.alibaba.android.rimet",
            "com.ss.android.lark", "com.whatsapp", "org.telegram.messenger"
        )
        private val OFFICE_PACKAGES = setOf(
            "com.microsoft.office.outlook", "com.google.android.gm", "com.microsoft.office.word",
            "com.kingsoft", "com.tencent.wework", "com.alibaba.android.rimet", "com.ss.android.lark"
        )
        private val NOTE_PACKAGES = setOf(
            "com.miui.notes", "com.xiaomi.notes", "com.youdao.note", "com.evernote", "notion.id"
        )
    }
}

data class KeyboardStartupState(
    val theme: KeyboardTheme,
    val chineseLayout: ChineseKeyboardLayout,
    val modes: List<KeyboardModePreference>,
    val haptic: HapticStrength,
    val soundVolume: Float,
    val closeButtonEnabled: Boolean,
    val candidateTextSizeLevel: Int,
    val englishAutoCapitalize: Boolean,
    val doubleSpacePeriod: Boolean,
    val isSeeded: Boolean
)
