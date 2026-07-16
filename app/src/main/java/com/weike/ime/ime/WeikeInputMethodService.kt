package com.weike.ime.ime

import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.IntentFilter
import android.content.res.Configuration
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.weike.ime.data.AppContainer
import com.weike.ime.data.AppSettingsRepository
import com.weike.ime.data.ClipboardEntry
import com.weike.ime.data.CloudApiSettings
import com.weike.ime.data.HapticStrength
import com.weike.ime.data.HistoryRetention
import com.weike.ime.data.InputHistory
import com.weike.ime.data.InputHistoryType
import com.weike.ime.data.KeyboardTheme
import com.weike.ime.data.KeyboardModePreference
import com.weike.ime.data.PunctuationPreference
import com.weike.ime.data.TypingDictionaryEntry
import com.weike.ime.data.VoiceUiState
import com.weike.ime.data.WritingStyle
import com.weike.ime.network.MimoTextPolisher
import com.weike.ime.network.TextPolisher
import com.weike.ime.speech.AudioRecorder
import com.weike.ime.speech.BoundedPcmBuffer
import com.weike.ime.speech.MimoAsrClient
import com.weike.ime.text.JiebaSegmenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WeikeInputMethodService : InputMethodService(), KeyboardActions {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: AppContainer
    private lateinit var recorder: AudioRecorder
    private lateinit var keyboard: WeikeKeyboardView
    private lateinit var pinyinDecoder: RimePinyinDecoder
    private lateinit var jieba: JiebaSegmenter
    private lateinit var clipboardManager: ClipboardManager
    @Volatile private var cloudApiSettings = CloudApiSettings()
    private val polisher: TextPolisher = MimoTextPolisher(endpointProvider = { cloudApiSettings.text })
    private val asrClient = MimoAsrClient(endpointProvider = { cloudApiSettings.asr })

    private var mode = KeyboardMode.VOICE
    private var modeBeforeSymbols = KeyboardMode.PINYIN
    private var visibleKeyboardModes = listOf(KeyboardMode.VOICE, KeyboardMode.TEXT)
    private var voiceState: VoiceUiState = VoiceUiState.Idle
    private var style = WritingStyle.CHAT
    private var punctuation = PunctuationPreference.SMART
    private var hapticStrength = HapticStrength.MEDIUM
    private var keyboardSoundVolume = AppSettingsRepository.DEFAULT_KEYBOARD_SOUND_VOLUME
    private var keyboardTheme = KeyboardTheme.DARK
    private var keyboardThemePreference = KeyboardTheme.DARK
    private var expressionOptimization = false
    private var pinyinBuffer = ""
    private var pinyinCandidates: List<PinyinCandidate> = emptyList()
    private val pinyinMutex = Mutex()
    private val languageEngineMutex = Mutex()
    private val pinyinOperations = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var pinyinReady = false
    private var engineLoadJob: Job? = null
    private var engineIdleReleaseJob: Job? = null
    private var rimeTermStageJob: Job? = null
    private var pendingPinyinMutations = 0
    private var lastSyncedTerms: List<com.weike.ime.data.LexiconTerm> = emptyList()
    private var lastSyncedTypingDictionary: List<TypingDictionaryEntry> = emptyList()
    private var englishBuffer = ""
    private var englishCandidates: List<PinyinCandidate> = emptyList()
    private var clipboardEntries: List<ClipboardEntry> = emptyList()
    private var lastClipboardContent = ""
    private var clipboardHistoryEnabled = false
    private var sensitiveField = false
    private var rawTranscript = ""
    private var finishVoiceJob: Job? = null
    private var commitTextJob: Job? = null
    private var audioBuffer = BoundedPcmBuffer(MAX_PCM_BYTES)
    private var voiceSessionId = 0L
    private var activeVoiceMode: KeyboardMode? = null
    private var activeVoicePolish = false
    private var lastCommittedText: String? = null
    private var lastCommitConnection: InputConnection? = null
    private var lastPackageName: String? = null
    private var voiceStartedAtMs = 0L
    private var activeRecordingDurationMs = 0L
    @Volatile private var renderPending = false
    private val clearLearningReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            if (intent.action == ACTION_CLEAR_RIME_LEARNING) clearPinyinLearning()
        }
    }
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { capturePrimaryClipboard() }

    override fun onCreate() {
        super.onCreate()
        retainedKeyboardMode?.let { mode = it }
        container = AppContainer(this)
        recorder = AudioRecorder(this)
        pinyinDecoder = RimePinyinDecoder(this)
        jieba = JiebaSegmenter(this)
        clipboardManager = getSystemService(ClipboardManager::class.java)
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        capturePrimaryClipboard()
        registerReceiver(
            clearLearningReceiver,
            IntentFilter(ACTION_CLEAR_RIME_LEARNING),
            Context.RECEIVER_NOT_EXPORTED
        )
        serviceScope.launch {
            for (operation in pinyinOperations) pinyinMutex.withLock { operation() }
        }
        serviceScope.launch {
            container.settings.hapticStrength.collect { strength ->
                hapticStrength = strength
                render()
            }
        }
        serviceScope.launch {
            container.settings.keyboardSoundVolume.collect { volume ->
                keyboardSoundVolume = volume
                render()
            }
        }
        serviceScope.launch {
            container.settings.keyboardTheme.collect { theme ->
                keyboardThemePreference = theme
                keyboardTheme = resolveKeyboardTheme(theme)
                render()
            }
        }
        serviceScope.launch {
            container.settings.keyboardModes.collect { configured ->
                visibleKeyboardModes = configured.map(::toKeyboardMode).distinct().ifEmpty {
                    listOf(KeyboardMode.TEXT)
                }
                if (!isConfiguredMode(mode) && mode != KeyboardMode.SYMBOLS) {
                    if (voiceState != VoiceUiState.Idle || activeVoiceMode != null) cancelActiveVoiceSession()
                    mode = initialModeFor(visibleKeyboardModes.first())
                }
                render()
            }
        }
        serviceScope.launch {
            container.settings.cloudApiSettings.collect { settings ->
                cloudApiSettings = settings
            }
        }
        serviceScope.launch {
            container.settings.historyRetention.collect { retention ->
                pruneHistory(retention)
            }
        }
        serviceScope.launch {
            lastSyncedTerms = container.lexicon.all()
            lastSyncedTypingDictionary = container.typingDictionary.all()
            EnglishCandidateEngine.initialize(this@WeikeInputMethodService, container.englishLearning.all(), lastSyncedTypingDictionary)
            requestPinyinEngine()
            container.lexicon.observeAll().collect { terms ->
                if (terms == lastSyncedTerms) return@collect
                lastSyncedTerms = terms
                if (jieba.isReady) {
                    serviceScope.launch(Dispatchers.Default) {
                        languageEngineMutex.withLock { jieba.syncProfessionalTerms(terms) }
                    }
                }
                deferRimeTermSync()
            }
        }
        serviceScope.launch {
            container.typingDictionary.observeAll().collect { entries ->
                if (entries == lastSyncedTypingDictionary) return@collect
                lastSyncedTypingDictionary = entries
                EnglishCandidateEngine.syncTypingDictionary(entries)
                deferRimeTermSync()
            }
        }
        serviceScope.launch {
            container.clipboard.observeRecent().collect { entries ->
                clipboardEntries = entries
                render()
            }
        }
        serviceScope.launch {
            container.settings.clipboardHistoryEnabled.collect { enabled ->
                clipboardHistoryEnabled = enabled
                if (!enabled) {
                    lastClipboardContent = ""
                    container.clipboard.deleteAll()
                } else {
                    capturePrimaryClipboard()
                }
            }
        }
    }

    override fun onCreateInputView(): WeikeKeyboardView {
        keyboard = WeikeKeyboardView(this, this)
        render()
        return keyboard
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (voiceState != VoiceUiState.Idle || activeVoiceMode != null || rawTranscript.isNotBlank()) {
            cancelActiveVoiceSession()
        }
        currentInputConnection?.finishComposingText()
        lastPackageName = attribute?.packageName
        sensitiveField = isSensitive(attribute)
        pinyinBuffer = ""
        pinyinCandidates = emptyList()
        englishBuffer = ""
        englishCandidates = emptyList()
        enqueuePinyin { if (pinyinReady) pinyinDecoder.clear() }
        if (sensitiveField) mode = KeyboardMode.ENGLISH
        serviceScope.launch {
            style = if (sensitiveField) WritingStyle.RAW else container.settings.styleFor(attribute?.packageName.orEmpty())
            punctuation = container.settings.punctuationPreference()
            hapticStrength = container.settings.hapticStrength()
            keyboardSoundVolume = container.settings.keyboardSoundVolume()
            expressionOptimization = container.settings.expressionOptimizationEnabled()
            render()
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        Log.d(TAG, "onStartInputView; restarting=$restarting")
        super.onStartInputView(info, restarting)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Log.d(TAG, "onFinishInputView; finishingInput=$finishingInput")
        stopVoice(cancelled = true)
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        Log.d(TAG, "onWindowHidden")
        super.onWindowHidden()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (keyboardThemePreference == KeyboardTheme.SYSTEM) {
            keyboardTheme = resolveKeyboardTheme(keyboardThemePreference)
            render()
        }
        if (::keyboard.isInitialized) keyboard.requestLayout()
        if (mode == KeyboardMode.PINYIN) requestPinyinEngine()
        render()
    }

    override fun onDestroy() {
        stopVoice(cancelled = true)
        if (::keyboard.isInitialized) keyboard.releaseResources()
        engineLoadJob?.cancel()
        engineIdleReleaseJob?.cancel()
        rimeTermStageJob?.cancel()
        runBlocking(Dispatchers.Default) {
            languageEngineMutex.withLock {
                pinyinDecoder.shutdown()
                jieba.release()
            }
        }
        if (::clipboardManager.isInitialized) clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        unregisterReceiver(clearLearningReceiver)
        pinyinOperations.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun selectMode(mode: KeyboardMode) {
        if (mode == KeyboardMode.TEXT) {
            toggleTextKeyboard()
            return
        }
        if (sensitiveField && (mode == KeyboardMode.VOICE || mode == KeyboardMode.ASK)) return
        if (this.mode == mode) return
        if (this.mode != mode && (voiceState != VoiceUiState.Idle || activeVoiceMode != null || rawTranscript.isNotBlank())) {
            cancelActiveVoiceSession()
        }
        if (this.mode == KeyboardMode.PINYIN && pinyinBuffer.isNotBlank()) {
            enqueuePinyin {
                commitCurrentPinyin()
                applyMode(mode)
            }
        } else applyMode(mode)
    }

    private fun applyMode(nextMode: KeyboardMode) {
        val previous = mode
        mode = nextMode
        if (mode != KeyboardMode.SYMBOLS) retainedKeyboardMode = mode
        if (mode == KeyboardMode.PINYIN) requestPinyinEngine()
        if (previous == KeyboardMode.PINYIN && mode != KeyboardMode.PINYIN) scheduleLanguageEngineRelease()
        render()
    }

    private fun requestPinyinEngine() {
        engineIdleReleaseJob?.cancel()
        if (pinyinReady || engineLoadJob?.isActive == true) return
        engineLoadJob = serviceScope.launch {
            languageEngineMutex.withLock {
                if (pinyinReady) return@withLock
                pinyinReady = false
                render()
                ensureLatestLanguageData()
                ensureJiebaReadyLocked()
                pinyinReady = runCatching {
                    pinyinDecoder.startSession(lastSyncedTerms, lastSyncedTypingDictionary)
                }
                    .onFailure { Log.e(TAG, "Unable to prepare Rime", it) }
                    .getOrDefault(false)
                if (pinyinReady && RimePinyinDecoder.consumeClearRequest(this@WeikeInputMethodService)) {
                    pinyinReady = pinyinDecoder.clearUserLearning()
                }
            }
            render()
        }
    }

    private suspend fun ensureJiebaReady(): Boolean {
        engineIdleReleaseJob?.cancel()
        return languageEngineMutex.withLock {
            ensureLatestLanguageData()
            ensureJiebaReadyLocked()
        }
    }

    private suspend fun ensureJiebaReadyLocked(): Boolean {
        if (jieba.isReady) return true
        return jieba.initialize(lastSyncedTerms)
    }

    private suspend fun ensureLatestLanguageData() {
        if (lastSyncedTerms.isEmpty()) lastSyncedTerms = container.lexicon.all()
        if (lastSyncedTypingDictionary.isEmpty()) lastSyncedTypingDictionary = container.typingDictionary.all()
    }

    private fun scheduleLanguageEngineRelease() {
        // Keeping Rime warm prevents the loading state whenever the IME is reopened.
        // Resources are released only when the IME service is destroyed.
        engineIdleReleaseJob?.cancel()
    }

    private fun deferRimeTermSync() {
        // Immediate matching uses the in-memory overlay. Persist the term list for
        // the next Rime deployment without restarting the live decoder.
        rimeTermStageJob?.cancel()
        rimeTermStageJob = serviceScope.launch(Dispatchers.Default) {
            runCatching {
                pinyinDecoder.stageProfessionalTerms(lastSyncedTerms, lastSyncedTypingDictionary)
            }.onFailure { Log.e(TAG, "Unable to stage Rime terms", it) }
        }
    }

    private fun toggleTextKeyboard() {
        val next = if (mode == KeyboardMode.PINYIN) KeyboardMode.ENGLISH else KeyboardMode.PINYIN
        finishCompositionThen { applyMode(next) }
    }

    override fun toggleVoice() {
        Log.d(TAG, "toggleVoice; state=$voiceState")
        when (voiceState) {
            VoiceUiState.Idle, is VoiceUiState.Error, is VoiceUiState.Preview -> startVoice(polish = false)
            VoiceUiState.Listening -> stopVoice(cancelled = false)
            VoiceUiState.Processing -> Unit
        }
    }

    override fun startPolishedVoice() {
        if (mode != KeyboardMode.VOICE) return
        when (voiceState) {
            VoiceUiState.Idle, is VoiceUiState.Error, is VoiceUiState.Preview -> startVoice(polish = true)
            else -> Unit
        }
    }

    override fun finishVoice() {
        if (voiceState == VoiceUiState.Listening) stopVoice(cancelled = false)
    }

    override fun cancelVoice() = stopVoice(cancelled = true)

    override fun dismissAnswer() {
        if (mode == KeyboardMode.ASK && voiceState is VoiceUiState.Preview) {
            rawTranscript = ""
            voiceState = VoiceUiState.Idle
            render()
        }
    }

    override fun pasteClipboard(entry: ClipboardEntry) {
        finishCompositionThen { currentInputConnection?.commitText(entry.content, 1) }
    }

    override fun deleteClipboard(entry: ClipboardEntry) {
        if (lastClipboardContent == entry.content) lastClipboardContent = ""
        serviceScope.launch(Dispatchers.IO) { container.clipboard.delete(entry.id) }
    }

    private fun startVoice(polish: Boolean) {
        if (sensitiveField) {
            mode = KeyboardMode.ENGLISH
            render()
            return
        }
        if (!recorder.canRecord()) {
            voiceState = VoiceUiState.Error("未授予麦克风权限，请打开维刻输入法完成授权")
            render()
            return
        }
        finishVoiceJob?.cancel()
        finishVoiceJob = null
        val sessionMode = if (mode == KeyboardMode.ASK) KeyboardMode.ASK else KeyboardMode.VOICE
        voiceSessionId += 1
        val sessionId = voiceSessionId
        activeVoiceMode = sessionMode
        activeVoicePolish = polish && sessionMode == KeyboardMode.VOICE
        rawTranscript = ""
        audioBuffer = BoundedPcmBuffer(MAX_PCM_BYTES)
        voiceStartedAtMs = System.currentTimeMillis()
        activeRecordingDurationMs = 0L
        lastCommittedText = null
        commitTextJob?.cancel()
        voiceState = VoiceUiState.Listening
        recorder.start(serviceScope, { chunk, count -> audioBuffer.append(chunk, count) }, { level ->
            if (::keyboard.isInitialized) keyboard.post { keyboard.setAudioLevel(level) }
        }) { error ->
            serviceScope.launch {
                if (!isCurrentVoiceSession(sessionId, sessionMode)) return@launch
                recorder.stop()
                activeVoiceMode = null
                voiceState = VoiceUiState.Error(error)
                render()
            }
        }
        render()
    }

    private fun stopVoice(cancelled: Boolean) {
        val recordingDurationMs = if (voiceStartedAtMs == 0L) 0 else System.currentTimeMillis() - voiceStartedAtMs
        activeRecordingDurationMs = recordingDurationMs
        val sessionId = voiceSessionId
        val sessionMode = activeVoiceMode ?: if (mode == KeyboardMode.ASK) KeyboardMode.ASK else KeyboardMode.VOICE
        val askMode = sessionMode == KeyboardMode.ASK
        val polishMode = activeVoicePolish
        Log.d(TAG, "stopVoice; cancelled=$cancelled; durationMs=$recordingDurationMs; state=$voiceState; sessionMode=$sessionMode")
        finishVoiceJob?.cancel()
        finishVoiceJob = null
        recorder.stop()
        voiceStartedAtMs = 0L
        if (cancelled) {
            cancelActiveVoiceSession()
            render()
            return
        }
        val minimumDurationMs = if (polishMode) MINIMUM_POLISHED_RECORDING_MS else MINIMUM_RECORDING_MS
        if (recordingDurationMs < minimumDurationMs) {
            voiceSessionId += 1
            activeVoiceMode = null
            activeVoicePolish = false
            rawTranscript = ""
            audioBuffer.clear()
            voiceState = VoiceUiState.Error("录音时间过短，请说完一句后再点完成")
            render()
            return
        }
        voiceState = VoiceUiState.Processing
        render()
        finishVoiceJob = serviceScope.launch {
            ensureVoiceSession(sessionId, sessionMode)
            val capture = audioBuffer.snapshotAndClear()
            if (capture.truncated) {
                activeVoiceMode = null
                voiceState = VoiceUiState.Error("录音时间过长，请缩短到 ${AudioRecorder.MAX_RECORDING_SECONDS} 秒以内")
                render()
                return@launch
            }
            val pcm = capture.pcm
            if (pcm.isEmpty()) {
                if (!isCurrentVoiceSession(sessionId, sessionMode)) return@launch
                activeVoiceMode = null
                voiceState = VoiceUiState.Error("没有采集到语音，请重试")
                render()
                return@launch
            }
            val transcription = withTimeoutOrNull(ASR_TIMEOUT_MS) {
                ensureVoiceSession(sessionId, sessionMode)
                asrClient.transcribe(pcm)
            } ?: Result.failure(IllegalStateException("语音识别超时，请重试"))
            ensureVoiceSession(sessionId, sessionMode)
            val source = transcription.getOrElse { error ->
                activeVoiceMode = null
                voiceState = VoiceUiState.Error("MiMo 语音识别失败：${error.message ?: "网络不可用"}")
                render()
                return@launch
            }.trim()
            if (source.isBlank()) {
                activeVoiceMode = null
                voiceState = VoiceUiState.Error("没有识别到语音，请重试")
                render()
                return@launch
            }
            rawTranscript = source
            val activePunctuation = if (askMode) punctuation else container.settings.punctuationPreference()
            if (!askMode && !polishMode) {
                commitResult(
                    applyPunctuationPreference(source, activePunctuation),
                    sessionId,
                    sessionMode,
                    instant = true,
                    historyType = InputHistoryType.DICTATION,
                    recordingDurationMs = recordingDurationMs
                )
                return@launch
            }
            val allTerms = if (askMode) emptyList() else container.lexicon.all()
            if (!askMode) ensureJiebaReady()
            val segmentedTerms = if (askMode) emptySet() else jieba.segment(source, com.weike.ime.text.JiebaMode.SEARCH)
                .filter { it.professional }
                .map { it.text }
                .toSet()
            val terms = allTerms.sortedBy { if (it.term in segmentedTerms) 0 else 1 }
            val optimizeExpression = !askMode && container.settings.expressionOptimizationEnabled()
            val structureHint = optimizeExpression && jieba.hasStructuredExpression(source)
            val deepPolish = optimizeExpression && (structureHint || shouldUseDeepPolish(source))
            val processingTimeout = when {
                askMode -> ANSWER_PROCESSING_TIMEOUT_MS
                deepPolish -> DEEP_POLISHING_TIMEOUT_MS
                else -> FAST_POLISHING_TIMEOUT_MS
            }
            val result = withTimeoutOrNull(processingTimeout) {
                ensureVoiceSession(sessionId, sessionMode)
                // Both voice modes always send ASR text, never PCM audio, to the text model.
                if (askMode) return@withTimeoutOrNull answerQuestion(source, sessionId, sessionMode)
                if (!deepPolish) {
                    val fastText = polisher.polishFast(source, style, activePunctuation, terms).getOrElse { error ->
                        Log.w(TAG, "Fast polishing failed; inserting local fallback", error)
                        localPolishFallback(source)
                    }
                    return@withTimeoutOrNull VoiceProcessingResult.Success(
                        text = applyPunctuationPreference(fastText, activePunctuation),
                        instant = true
                    )
                }
                val polishedText = try {
                    val polished = StringBuilder()
                    var receivedPolishingDelta = false
                    polisher.polishStream(source, style, activePunctuation, terms, optimizeExpression, structureHint).collect { delta ->
                        ensureVoiceSession(sessionId, sessionMode)
                        polished.append(delta)
                        if (::keyboard.isInitialized) {
                            if (!receivedPolishingDelta) keyboard.setPolishingStreamActive(true)
                            keyboard.notePolishingDelta()
                        }
                        receivedPolishingDelta = true
                    }
                    polished.toString().trim().ifBlank { source }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.w(TAG, "Polishing stream failed; falling back to standard request", error)
                    if (::keyboard.isInitialized) keyboard.setPolishingStreamActive(false)
                    polisher.polish(source, style, activePunctuation, terms, optimizeExpression, structureHint).getOrElse { fallbackError ->
                        Log.w(TAG, "Standard polishing request failed; inserting raw transcription", fallbackError)
                        source
                    }
                }
                VoiceProcessingResult.Success(applyPunctuationPreference(polishedText, activePunctuation))
            }
            if (!isCurrentVoiceSession(sessionId, sessionMode)) return@launch
            when (result) {
                null -> {
                    if (sessionMode == KeyboardMode.VOICE && rawTranscript.isNotBlank()) {
                        commitResult(
                            applyPunctuationPreference(localPolishFallback(rawTranscript), activePunctuation),
                            sessionId,
                            sessionMode,
                            instant = true,
                            historyType = if (polishMode) InputHistoryType.POLISH else InputHistoryType.DICTATION,
                            recordingDurationMs = recordingDurationMs
                        )
                    } else {
                        handleVoiceTimeout(sessionId, sessionMode)
                    }
                }
                is VoiceProcessingResult.Error -> {
                    activeVoiceMode = null
                    voiceState = VoiceUiState.Error(result.message)
                    render()
                }
                is VoiceProcessingResult.Success -> if (sessionMode == KeyboardMode.VOICE) {
                    commitResult(
                        result.text,
                        sessionId,
                        sessionMode,
                        result.instant,
                        if (polishMode) InputHistoryType.POLISH else InputHistoryType.DICTATION,
                        recordingDurationMs
                    )
                }
                is VoiceProcessingResult.Answer -> if (sessionMode == KeyboardMode.ASK) showAnswer(result.question, result.text, sessionId, sessionMode)
            }
        }
    }

    private suspend fun answerQuestion(question: String, sessionId: Long, sessionMode: KeyboardMode): VoiceProcessingResult = try {
        val answer = StringBuilder()
        var receivedDelta = false
        polisher.answerStream(question).collect { delta ->
            ensureVoiceSession(sessionId, sessionMode)
            answer.append(delta)
            if (!receivedDelta) {
                voiceState = VoiceUiState.Preview(question = question, text = "", streaming = true)
                render()
            }
            voiceState = VoiceUiState.Preview(question = question, text = answer.toString(), streaming = true)
            if (::keyboard.isInitialized) {
                if (!receivedDelta) keyboard.setPolishingStreamActive(true)
                keyboard.notePolishingDelta()
            }
            receivedDelta = true
            render()
        }
        val text = answer.toString().trim()
        if (text.isBlank()) VoiceProcessingResult.Error("MiMo 未返回回答，请重试")
        else VoiceProcessingResult.Answer(question, text)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        ensureVoiceSession(sessionId, sessionMode)
        Log.w(TAG, "Question stream failed; retrying once", error)
        if (::keyboard.isInitialized) keyboard.setPolishingStreamActive(false)
        polisher.answer(question)
            .fold(
                onSuccess = { text -> VoiceProcessingResult.Answer(question, text) },
                onFailure = { fallbackError -> VoiceProcessingResult.Error("MiMo 问答失败：${fallbackError.message ?: "网络不可用"}") }
            )
    }

    private fun showAnswer(question: String, text: String, sessionId: Long, sessionMode: KeyboardMode) {
        if (!isCurrentVoiceSession(sessionId, sessionMode)) return
        rawTranscript = ""
        voiceState = VoiceUiState.Preview(question = question, text = text, streaming = false)
        persistHistory(InputHistoryType.QUESTION, text, question)
        render()
    }

    private fun handleVoiceTimeout(sessionId: Long, sessionMode: KeyboardMode) {
        if (!isCurrentVoiceSession(sessionId, sessionMode)) return
        activeVoiceMode = null
        rawTranscript = ""
        audioBuffer.clear()
        if (::keyboard.isInitialized) keyboard.playTimeout()
        toastFor("识别超时，请重试", 1_500L)
        voiceState = VoiceUiState.Idle
        render()
    }

    private fun commitResult(
        text: String,
        sessionId: Long,
        sessionMode: KeyboardMode,
        instant: Boolean = false,
        historyType: InputHistoryType? = null,
        recordingDurationMs: Long = activeRecordingDurationMs
    ) {
        if (!isCurrentVoiceSession(sessionId, sessionMode)) return
        val connection = currentInputConnection
        if (connection == null || sensitiveField) {
            activeVoiceMode = null
            voiceState = VoiceUiState.Error("当前输入框不可用，内容未插入")
            render()
            return
        }
        // Let the processing label disappear before the capsule restores and text begins to appear.
        voiceState = VoiceUiState.Idle
        render()
        commitTextJob?.cancel()
        commitTextJob = serviceScope.launch {
            delay(PROCESSING_LABEL_FADE_MS)
            if (!isCurrentVoiceSession(sessionId, sessionMode) || currentInputConnection !== connection || sensitiveField) return@launch
            if (instant) {
                connection.commitText(text, 1)
                lastCommittedText = text
                lastCommitConnection = connection
                historyType?.let { persistDictation(it, text, recordingDurationMs) }
                if (isCurrentVoiceSession(sessionId, sessionMode)) activeVoiceMode = null
                return@launch
            }
            text.forEach { character ->
                if (!isCurrentVoiceSession(sessionId, sessionMode)) return@launch
                connection.commitText(character.toString(), 1)
                delay(TYPEWRITER_CHARACTER_DELAY_MS)
            }
            lastCommittedText = text
            lastCommitConnection = connection
            historyType?.let { persistDictation(it, text, recordingDurationMs) }
            if (isCurrentVoiceSession(sessionId, sessionMode)) activeVoiceMode = null
        }
    }

    private fun cancelActiveVoiceSession() {
        finishVoiceJob?.cancel()
        finishVoiceJob = null
        voiceSessionId += 1
        activeVoiceMode = null
        activeVoicePolish = false
        recorder.stop()
        voiceStartedAtMs = 0L
        activeRecordingDurationMs = 0L
        rawTranscript = ""
        audioBuffer.clear()
        if (::keyboard.isInitialized) keyboard.setPolishingStreamActive(false)
        voiceState = VoiceUiState.Idle
    }

    private fun isCurrentVoiceSession(sessionId: Long, sessionMode: KeyboardMode): Boolean =
        voiceSessionId == sessionId && activeVoiceMode == sessionMode

    private fun ensureVoiceSession(sessionId: Long, sessionMode: KeyboardMode) {
        if (!isCurrentVoiceSession(sessionId, sessionMode)) throw CancellationException("Stale voice session")
    }

    private fun applyPunctuationPreference(text: String, preference: PunctuationPreference): String {
        val cleaned = cleanSymbols(text)
        return when (preference) {
            PunctuationPreference.SMART -> cleaned
            PunctuationPreference.SPACES -> cleaned
            .replace(Regex("[，。！？、；：,.!?;:…—()（）\\[\\]{}\\\"'“”]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            PunctuationPreference.NO_END -> cleaned
            .replace(Regex("[\\s，。！？、；：,.!?;:…—]+$"), "")
            .trimEnd()
        }
    }

    private fun cleanSymbols(text: String): String = text
        .replace(Regex("[\\u00A0\\t\\r\\n]+"), " ")
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\s+([，。！？、；：,.!?;:])"), "$1")
        .replace(Regex("([（(【\\[])[\\s]+"), "$1")
        .replace(Regex("[\\s]+([）)】\\]])"), "$1")
        .replace(Regex("([，。！？、；：,.!?;:]){2,}")) { match ->
            val value = match.value
            when {
                value.any { it == '？' || it == '?' } -> "？"
                value.any { it == '！' || it == '!' } -> "！"
                value.any { it == '。' || it == '.' } -> "。"
                value.any { it == '；' || it == ';' } -> "；"
                value.any { it == '：' || it == ':' } -> "："
                value.any { it == '、' } -> "、"
                else -> "，"
            }
        }
        .trim()

    private fun localPolishFallback(text: String): String = text
        .replace(Regex("[，,。.!！?？；;：:、]{2,}"), "。")
        .replace(Regex("\\s+"), " ")
        .replace(Regex("^(嗯+|啊+|呃+|额+|那个|就是)[，,。.!！?？；;：:\\s]*"), "")
        .replace(Regex("[\\s，,。.!！?？；;：:、]*(嗯+|啊+|呃+|额+)[\\s，,。.!！?？；;：:、]*"), "")
        .trim()

    private fun shouldUseDeepPolish(text: String): Boolean {
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

    /*
     * The service only ever keeps this small result in memory. It avoids a late HTTP
     * completion inserting content after the visual timeout has restored the keyboard.
     */
    private sealed interface VoiceProcessingResult {
        data class Success(val text: String, val instant: Boolean = false) : VoiceProcessingResult
        data class Answer(val question: String, val text: String) : VoiceProcessingResult
        data class Error(val message: String) : VoiceProcessingResult
    }

    override fun undoLastInsert() {
        val text = lastCommittedText ?: return
        if (lastCommitConnection !== currentInputConnection) {
            toast("输入焦点已变更，无法撤销")
            return
        }
        currentInputConnection?.deleteSurroundingText(text.length, 0)
        lastCommittedText = null
        voiceState = VoiceUiState.Idle
        render()
    }

    override fun typeEnglish(value: String) {
        if (mode == KeyboardMode.SYMBOLS && modeBeforeSymbols == KeyboardMode.PINYIN && pinyinBuffer.isNotBlank()) {
            enqueuePinyin {
                commitCurrentPinyin()
                currentInputConnection?.commitText(value, 1)
            }
        } else if (mode == KeyboardMode.SYMBOLS && englishBuffer.isNotBlank()) {
            commitEnglishComposition(false)
            currentInputConnection?.commitText(value, 1)
        } else currentInputConnection?.commitText(value, 1)
    }

    override fun typeEnglishLetter(value: String) {
        englishBuffer += value
        englishCandidates = EnglishCandidateEngine.candidates(englishBuffer)
        updateComposingText(englishBuffer)
        render()
    }

    override fun typePinyin(value: String) {
        if (!pinyinReady) {
            requestPinyinEngine()
            return
        }
        engineIdleReleaseJob?.cancel()
        pendingPinyinMutations += 1
        enqueuePinyin {
            try {
                // Rime can emit a raw unfinished initial. Only explicit candidate
                // selection is allowed to reach the focused editor.
                applyPinyinState(pinyinDecoder.input(value.lowercase()), allowCommit = false)
            } finally {
                pendingPinyinMutations -= 1
            }
        }
    }

    override fun chooseCandidate(candidate: PinyinCandidate) {
        if (!pinyinReady) return
        enqueuePinyin {
            if (candidate.directCommit) commitDirectPinyin(candidate.text)
            else if (candidate.index >= 0) applyPinyinState(pinyinDecoder.selectCandidate(candidate.index))
        }
    }

    override fun chooseEnglishCandidate(value: String) {
        currentInputConnection?.commitText(value, 1)
        recordEnglishSelection(value)
        englishBuffer = ""
        englishCandidates = emptyList()
        updateComposingText(englishBuffer)
        render()
    }

    override fun commitEnglishComposition(addSpace: Boolean) {
        val value = englishCandidates.firstOrNull()?.text ?: englishBuffer
        if (value.isNotBlank()) {
            currentInputConnection?.commitText(value, 1)
            recordEnglishSelection(value)
        }
        if (addSpace) currentInputConnection?.commitText(" ", 1)
        englishBuffer = ""
        englishCandidates = emptyList()
        updateComposingText(englishBuffer)
        render()
    }

    override fun backspace() {
        if (mode == KeyboardMode.PINYIN && (pinyinBuffer.isNotEmpty() || pendingPinyinMutations > 0)) {
            pendingPinyinMutations += 1
            enqueuePinyin {
                try {
                    // Never apply Rime's automatic commit while editing composition.
                    applyPinyinState(pinyinDecoder.backspace(), allowCommit = false)
                } finally {
                    pendingPinyinMutations -= 1
                }
            }
        } else if (mode == KeyboardMode.ENGLISH && englishBuffer.isNotEmpty()) {
            englishBuffer = englishBuffer.dropLast(1)
            englishCandidates = EnglishCandidateEngine.candidates(englishBuffer)
            updateComposingText(englishBuffer)
            render()
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    override fun enter() {
        if (mode == KeyboardMode.PINYIN && (pinyinBuffer.isNotBlank() || pendingPinyinMutations > 0)) {
            enqueuePinyin { commitRawPinyin() }
        } else {
            finishCompositionThen { currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_SEND) }
        }
    }

    override fun newline() {
        finishCompositionThen { currentInputConnection?.commitText("\n", 1) }
    }

    private fun finishCompositionThen(action: () -> Unit) {
        if (mode == KeyboardMode.PINYIN && pinyinBuffer.isNotEmpty()) {
            enqueuePinyin {
                commitCurrentPinyin()
                action()
            }
        } else if (mode == KeyboardMode.ENGLISH && englishBuffer.isNotEmpty()) {
            commitEnglishComposition(false)
            action()
        } else action()
    }

    override fun insertAt() = typeEnglish("@")

    override fun toggleSymbols() {
        val toggle = {
            mode = if (mode == KeyboardMode.SYMBOLS) modeBeforeSymbols else {
                modeBeforeSymbols = if (mode == KeyboardMode.PINYIN) KeyboardMode.PINYIN else KeyboardMode.ENGLISH
                KeyboardMode.SYMBOLS
            }
            render()
        }
        if (mode == KeyboardMode.PINYIN && pinyinBuffer.isNotBlank()) enqueuePinyin {
            commitCurrentPinyin()
            toggle()
        } else toggle()
    }

    private fun render() {
        if (!::keyboard.isInitialized) return
        if (renderPending) return
        renderPending = true
        keyboard.post {
            renderPending = false
            keyboard.update(
                mode, voiceState, style, pinyinBuffer, pinyinCandidates,
                englishBuffer, englishCandidates, rawTranscript, sensitiveField, hapticStrength,
                pinyinReady, pinyinDecoder.statusText, keyboardTheme, keyboardSoundVolume, visibleKeyboardModes,
                clipboardEntries
            )
        }
    }

    private fun toKeyboardMode(preference: KeyboardModePreference): KeyboardMode = when (preference) {
        KeyboardModePreference.VOICE -> KeyboardMode.VOICE
        KeyboardModePreference.TEXT -> KeyboardMode.TEXT
        KeyboardModePreference.ASK -> KeyboardMode.ASK
        KeyboardModePreference.CLIPBOARD -> KeyboardMode.CLIPBOARD
    }

    private fun isConfiguredMode(candidate: KeyboardMode): Boolean =
        candidate in visibleKeyboardModes ||
            (candidate in listOf(KeyboardMode.PINYIN, KeyboardMode.ENGLISH) && KeyboardMode.TEXT in visibleKeyboardModes)

    private fun initialModeFor(configured: KeyboardMode): KeyboardMode =
        if (configured == KeyboardMode.TEXT) KeyboardMode.PINYIN else configured

    private fun capturePrimaryClipboard() {
        if (!clipboardHistoryEnabled) return
        val content = runCatching {
            clipboardManager.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
                ?.trim()
                .orEmpty()
        }.getOrDefault("")
        if (content.isBlank() || content == lastClipboardContent || isSensitiveClipboard(content)) return
        lastClipboardContent = content
        serviceScope.launch(Dispatchers.IO) {
            container.clipboard.record(content)
        }
    }

    private fun isSensitiveClipboard(content: String): Boolean {
        if (content.length > MAX_CLIPBOARD_CONTENT_LENGTH) return true
        val normalized = content.trim()
        if (normalized.matches(Regex("^\\d{6,8}$"))) return true
        return SENSITIVE_CLIPBOARD_MARKERS.containsMatchIn(normalized)
    }

    private fun refreshPinyinCandidates() {
        // Switching into 拼 must never clear a composition created by the prior key event.
        render()
    }

    private fun applyPinyinState(state: PinyinSessionState, allowCommit: Boolean = true) {
        if (allowCommit) state.committedText?.let { currentInputConnection?.commitText(it, 1) }
        pinyinBuffer = state.preedit
        pinyinCandidates = mergeCustomPinyinCandidates(state.preedit, state.candidates)
        updateComposingText(pinyinBuffer)
        render()
    }

    private suspend fun commitCurrentPinyin() {
        val candidate = pinyinCandidates.firstOrNull() ?: return
        if (candidate.directCommit) commitDirectPinyin(candidate.text)
        else if (candidate.index >= 0) applyPinyinState(pinyinDecoder.selectCandidate(candidate.index))
    }

    private suspend fun commitRawPinyin() {
        val raw = pinyinDecoder.currentState().preedit.ifBlank { pinyinBuffer }
        pinyinDecoder.clear()
        pinyinBuffer = ""
        pinyinCandidates = emptyList()
        updateComposingText("")
        if (raw.isNotBlank()) currentInputConnection?.commitText(raw, 1)
        render()
    }

    private suspend fun commitDirectPinyin(text: String) {
        pinyinDecoder.clear()
        pinyinBuffer = ""
        pinyinCandidates = emptyList()
        updateComposingText("")
        currentInputConnection?.commitText(text, 1)
        render()
    }

    private fun mergeCustomPinyinCandidates(
        preedit: String,
        nativeCandidates: List<PinyinCandidate>
    ): List<PinyinCandidate> {
        val query = preedit.lowercase().replace("'", "").trim()
        if (query.isBlank()) return nativeCandidates
        val custom = (lastSyncedTypingDictionary.map { it.term to it.hint } + lastSyncedTerms.map { it.term to it.hint })
            .asSequence()
            .mapNotNull { (term, hint) ->
                val codeParts = pinyinDecoder.pinyinCodeForTerm(term, hint)
                    .split(' ')
                    .filter(String::isNotBlank)
                if (codeParts.isEmpty()) return@mapNotNull null
                val fullCode = codeParts.joinToString("")
                val initials = codeParts.joinToString("") { it.take(1) }
                val fullMatch = query == fullCode
                val initialsMatch = query.length >= 3 && initials.startsWith(query)
                if (fullMatch || initialsMatch) PinyinCandidate(
                    text = term,
                    score = Double.MAX_VALUE,
                    index = -1,
                    directCommit = true
                ) else null
            }
            .distinctBy { it.text }
            .toList()
        if (custom.isEmpty()) return nativeCandidates
        return custom + nativeCandidates.filterNot { native -> custom.any { it.text == native.text } }
    }

    private fun enqueuePinyin(block: suspend () -> Unit) {
        pinyinOperations.trySend(block)
    }

    private fun resolveKeyboardTheme(preference: KeyboardTheme): KeyboardTheme = when (preference) {
        KeyboardTheme.SYSTEM -> {
            val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (night == Configuration.UI_MODE_NIGHT_YES) KeyboardTheme.DARK else KeyboardTheme.LIGHT
        }
        else -> preference
    }

    private fun recordEnglishSelection(value: String) {
        if (value.isBlank()) return
        val entry = EnglishCandidateEngine.selected(value)
        serviceScope.launch { container.englishLearning.upsert(entry) }
    }

    private fun persistDictation(type: InputHistoryType, text: String, durationMs: Long) {
        if (text.isBlank()) return
        serviceScope.launch(Dispatchers.IO) {
            container.usageStats.record(durationMs, countInputUnits(text).toLong())
            persistHistory(type, text)
        }
    }

    private fun persistHistory(type: InputHistoryType, content: String, question: String = "") {
        if (content.isBlank()) return
        serviceScope.launch(Dispatchers.IO) {
            val retention = container.settings.historyRetention()
            if (retention == HistoryRetention.NEVER) return@launch
            pruneHistory(retention)
            container.inputHistory.insert(
                InputHistory(type = type.name, content = content.trim(), question = question.trim())
            )
        }
    }

    private suspend fun pruneHistory(retention: HistoryRetention) {
        when (val duration = retention.durationMs) {
            0L -> container.inputHistory.deleteAll()
            null -> Unit
            else -> container.inputHistory.deleteBefore(System.currentTimeMillis() - duration)
        }
    }

    /** Chinese characters count individually; a contiguous English word or number counts once. */
    private fun countInputUnits(text: String): Int = INPUT_UNIT_PATTERN.findAll(text).count()

    /** Keeps the raw composing code in the focused editor, not in the candidate strip. */
    private fun updateComposingText(value: String) {
        val connection = currentInputConnection ?: return
        if (value.isBlank()) {
            // finishComposingText confirms the final raw character in many editors.
            // Replace it first so the last deleted Pinyin initial cannot leak through.
            connection.setComposingText("", 1)
            connection.finishComposingText()
        } else connection.setComposingText(value, 1)
    }

    private fun clearPinyinLearning() {
        RimePinyinDecoder.consumeClearRequest(this)
        pinyinReady = false
        render()
        serviceScope.launch {
            pinyinReady = runCatching { pinyinMutex.withLock { pinyinDecoder.clearUserLearning() } }
                .onFailure { Log.e(TAG, "Unable to clear Rime learning data", it) }
                .getOrDefault(false)
            render()
            toast(if (pinyinReady) "已清除本机候选学习数据" else "清除学习数据失败")
        }
    }

    private fun isSensitive(info: EditorInfo?): Boolean {
        val inputType = info?.inputType ?: return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val passwordVariation = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        return passwordVariation || (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun toastFor(message: String, durationMs: Long) {
        val toast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
        toast.show()
        Handler(Looper.getMainLooper()).postDelayed({ toast.cancel() }, durationMs)
    }

    companion object {
        private const val TAG = "WeikeIme"
        @Volatile private var retainedKeyboardMode: KeyboardMode? = null
        private const val MINIMUM_RECORDING_MS = 700L
        private const val MINIMUM_POLISHED_RECORDING_MS = 350L
        private const val ASR_TIMEOUT_MS = 8_000L
        private const val FAST_POLISHING_TIMEOUT_MS = 3_500L
        private const val DEEP_POLISHING_TIMEOUT_MS = 8_000L
        private const val ANSWER_PROCESSING_TIMEOUT_MS = 18_000L
        private const val PROCESSING_LABEL_FADE_MS = 150L
        private const val TYPEWRITER_CHARACTER_DELAY_MS = 16L
        private const val LANGUAGE_ENGINE_IDLE_TIMEOUT_MS = 5L * 60L * 1000L
        private const val DEFERRED_TERM_SYNC_DELAY_MS = 1_000L
        private const val MAX_PCM_BYTES = AudioRecorder.MAX_RECORDING_SECONDS * AudioRecorder.SAMPLE_RATE * 2
        private val INPUT_UNIT_PATTERN = Regex("[\\u4E00-\\u9FFF]|[A-Za-z]+(?:['-][A-Za-z]+)*|\\d+(?:[.,]\\d+)?")
        private const val MAX_CLIPBOARD_CONTENT_LENGTH = 4_096
        private val SENSITIVE_CLIPBOARD_MARKERS = Regex(
            "(?i)(password|passwd|passcode|验证码|动态码|verification\\s*code|one[- ]?time\\s*password|otp)"
        )
        const val ACTION_CLEAR_RIME_LEARNING = "com.weike.ime.action.CLEAR_RIME_LEARNING"
    }
}
