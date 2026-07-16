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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.settingsDataStore by preferencesDataStore("weike_settings")

class AppSettingsRepository(private val context: Context) {
    private val overridesKey = stringPreferencesKey("style_overrides")
    private val punctuationKey = stringPreferencesKey("punctuation_preference")
    private val hapticStrengthKey = intPreferencesKey("haptic_strength")
    private val expressionOptimizationKey = booleanPreferencesKey("expression_optimization")
    private val keyboardThemeKey = stringPreferencesKey("keyboard_theme")
    private val keyboardSoundVolumeKey = floatPreferencesKey("keyboard_sound_volume")
    private val historyRetentionKey = stringPreferencesKey("history_retention")
    private val keyboardModesKey = stringPreferencesKey("keyboard_modes")
    private val chineseKeyboardLayoutKey = stringPreferencesKey("chinese_keyboard_layout")
    // Legacy plaintext keys are read once, migrated into SecureSecretStore, then deleted.
    private val asrUrlKey = stringPreferencesKey("asr_api_url")
    private val asrApiKeyKey = stringPreferencesKey("asr_api_key")
    private val asrModelKey = stringPreferencesKey("asr_api_model")
    private val textUrlKey = stringPreferencesKey("text_api_url")
    private val textApiKeyKey = stringPreferencesKey("text_api_key")
    private val textModelKey = stringPreferencesKey("text_api_model")
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
        context.settingsDataStore.edit { prefs -> prefs[hapticStrengthKey] = strength.storedValue }
    }

    suspend fun expressionOptimizationEnabled(): Boolean = expressionOptimization.first()

    suspend fun saveExpressionOptimization(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[expressionOptimizationKey] = enabled }
    }

    suspend fun keyboardTheme(): KeyboardTheme = keyboardTheme.first()

    suspend fun saveKeyboardTheme(theme: KeyboardTheme) {
        context.settingsDataStore.edit { prefs -> prefs[keyboardThemeKey] = theme.name }
    }

    suspend fun keyboardSoundVolume(): Float = keyboardSoundVolume.first()

    suspend fun saveKeyboardSoundVolume(volume: Float) {
        context.settingsDataStore.edit { prefs -> prefs[keyboardSoundVolumeKey] = volume.coerceIn(0f, 1f) }
    }

    suspend fun historyRetention(): HistoryRetention = historyRetention.first()

    suspend fun saveHistoryRetention(retention: HistoryRetention) {
        context.settingsDataStore.edit { prefs -> prefs[historyRetentionKey] = retention.name }
    }

    suspend fun keyboardModes(): List<KeyboardModePreference> = keyboardModes.first()

    suspend fun saveKeyboardModes(modes: List<KeyboardModePreference>) {
        val normalized = modes.distinct().ifEmpty { DEFAULT_KEYBOARD_MODES }
        context.settingsDataStore.edit { prefs ->
            prefs[keyboardModesKey] = normalized.joinToString(",") { it.name }
        }
    }

    suspend fun chineseKeyboardLayout(): ChineseKeyboardLayout = chineseKeyboardLayout.first()

    suspend fun saveChineseKeyboardLayout(layout: ChineseKeyboardLayout) {
        context.settingsDataStore.edit { prefs -> prefs[chineseKeyboardLayoutKey] = layout.name }
    }

    suspend fun clipboardHistoryEnabled(): Boolean = clipboardHistoryEnabled.first()

    suspend fun saveClipboardHistoryEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[clipboardHistoryEnabledKey] = enabled }
    }

    suspend fun saveAsrApi(config: ModelEndpointConfig) {
        secrets.write(SECURE_ASR_KEY, config.apiKey.trim())
        context.settingsDataStore.edit { prefs ->
            prefs[asrUrlKey] = config.url.trim()
            prefs[asrModelKey] = config.model.trim()
            prefs.remove(asrApiKeyKey)
        }
    }

    suspend fun saveTextApi(config: ModelEndpointConfig) {
        secrets.write(SECURE_TEXT_KEY, config.apiKey.trim())
        context.settingsDataStore.edit { prefs ->
            prefs[textUrlKey] = config.url.trim()
            prefs[textModelKey] = config.model.trim()
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

    companion object {
        private const val SECURE_ASR_KEY = "asr_api_key"
        private const val SECURE_TEXT_KEY = "text_api_key"
        const val DEFAULT_KEYBOARD_SOUND_VOLUME = .45f
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
