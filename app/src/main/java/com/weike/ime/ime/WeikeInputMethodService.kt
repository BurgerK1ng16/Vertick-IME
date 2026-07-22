package com.weike.ime.ime

import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.icu.text.BreakIterator
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import com.weike.ime.R
import com.weike.ime.data.AppContainer
import com.weike.ime.data.AppSettingsRepository
import com.weike.ime.data.ChineseKeyboardLayout
import com.weike.ime.data.ClipboardEntry
import com.weike.ime.data.CloudApiSettings
import com.weike.ime.data.DictionaryPackManager
import com.weike.ime.data.HapticStrength
import com.weike.ime.data.HistoryRetention
import com.weike.ime.data.InputHistory
import com.weike.ime.data.InputHistoryType
import com.weike.ime.data.KeyboardTheme
import com.weike.ime.data.KeyboardModePreference
import com.weike.ime.data.KeyboardStartupMode
import com.weike.ime.data.KeyboardLogoConfig
import com.weike.ime.data.PunctuationPreference
import com.weike.ime.data.PinyinLearning
import com.weike.ime.data.PredictionLearning
import com.weike.ime.data.TypingDictionaryEntry
import com.weike.ime.data.VoiceUiState
import com.weike.ime.data.WritingStyle
import com.weike.ime.network.MimoTextPolisher
import com.weike.ime.network.TextPolisher
import com.weike.ime.speech.AudioRecorder
import com.weike.ime.speech.BoundedPcmBuffer
import com.weike.ime.speech.RoutedAsrClient
import com.weike.ime.text.JiebaSegmenter
import com.weike.ime.text.StructuredExpressionFormatter
import com.weike.ime.workflow.WorkflowIntent
import com.weike.ime.workflow.WorkflowReplaceTarget
import com.weike.ime.workflow.WorkflowRouter
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

class WeikeInputMethodService : InputMethodService(), KeyboardActions {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: AppContainer
    private lateinit var recorder: AudioRecorder
    private lateinit var keyboard: WeikeKeyboardView
    private var inputViewHost: FrameLayout? = null
    private var overlayHost: FrameLayout? = null
    private var overlayWindowManager: WindowManager? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var overlayContext: Context? = null
    private var overlayPanel: FloatingKeyboardPanel? = null
    private lateinit var pinyinDecoder: PinyinDecoder
    private lateinit var jieba: JiebaSegmenter
    private lateinit var predictionEngine: PredictionEngine
    private lateinit var keyboardSound: KeyboardSoundPlayer
    private lateinit var clipboardManager: ClipboardManager
    @Volatile private var cloudApiSettings = CloudApiSettings()
    private val polisher: TextPolisher = MimoTextPolisher(endpointProvider = { cloudApiSettings.text })
    private val asrClient = RoutedAsrClient(endpointProvider = { cloudApiSettings.asr })

    private var mode = KeyboardMode.VOICE
    private var modeBeforeSymbols = KeyboardMode.PINYIN
    private var visibleKeyboardModes = listOf(KeyboardMode.VOICE, KeyboardMode.TEXT)
    private var voiceState: VoiceUiState = VoiceUiState.Idle
    private var style = WritingStyle.CHAT
    private var punctuation = PunctuationPreference.SMART
    private var hapticStrength = HapticStrength.MEDIUM
    private var keyboardSoundVolume = AppSettingsRepository.DEFAULT_KEYBOARD_SOUND_VOLUME
    private var keyboardCloseButtonEnabled = true
    private var quickImeSwitcherEnabled = false
    private var keyboardStartupMode = KeyboardStartupMode.LAST_USED
    private var keyboardLogo = KeyboardLogoConfig()
    private var candidateTextSizeLevel = AppSettingsRepository.DEFAULT_CANDIDATE_TEXT_SIZE_LEVEL
    private var englishAutoCapitalize = true
    private var doubleSpacePeriod = false
    private var keyboardTheme = KeyboardTheme.DARK
    private var keyboardThemePreference = KeyboardTheme.DARK
    private var expressionOptimization = false
    private var chineseKeyboardLayout = ChineseKeyboardLayout.FULL
    private var nineKeySymbols = AppSettingsRepository.DEFAULT_NINE_KEY_SYMBOLS
    private var pinyinBuffer = ""
    // Unlike pinyinBuffer this keeps the actual decoder code. They differ for T9:
    // the editor displays "ni" while the decoder must retain "64".
    private var pinyinRawBuffer = ""
    private var pinyinCandidates: List<PinyinCandidate> = emptyList()
    private var predictionCandidates: List<PredictionCandidate> = emptyList()
    private var predictionEnabled = true
    private var predictionLearningEnabled = true
    private var predictionRequestId = 0L
    private var predictionContextTokens: List<String> = emptyList()
    private val pinyinMutex = Mutex()
    private val languageEngineMutex = Mutex()
    private val pinyinOperations = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val predictionOperations = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var pinyinReady = false
    private var engineLoadJob: Job? = null
    private var engineIdleReleaseJob: Job? = null
    private var rimeTermStageJob: Job? = null
    private var pendingPinyinMutations = 0
    private val pendingPinyinLetters = StringBuilder()
    private var pinyinBatchScheduled = false
    private var lastSyncedTerms: List<com.weike.ime.data.LexiconTerm> = emptyList()
    private var lastSyncedTypingDictionary: List<TypingDictionaryEntry> = emptyList()
    private var lastPinyinLearning: List<PinyinLearning> = emptyList()
    private var englishBuffer = ""
    private var englishCandidates: List<PinyinCandidate> = emptyList()
    private var lastTextSpaceAtMs = 0L
    private var lastTextSpaceConnection: InputConnection? = null
    private var clipboardEntries: List<ClipboardEntry> = emptyList()
    private var lastClipboardContent = ""
    private var clipboardHistoryEnabled = false
    private var recentClipboardPasteEnabled = true
    private var recentClipboardContent = ""
    private var recentClipboardCapturedAtMs = 0L
    private var recentClipboardVisibleUntilMs = 0L
    private var inputViewActive = false
    private var keyboardHeightLevel = 0
    private var keyboardBottomOffsetLevel = 0
    private var punctuationShortcuts = false
    private var cursorSliderEnabled = true
    private var sensitiveField = false
    private var rawTranscript = ""
    private var finishVoiceJob: Job? = null
    private var commitTextJob: Job? = null
    private var audioBuffer = BoundedPcmBuffer(MAX_PCM_BYTES)
    private var voiceSessionId = 0L
    private var activeVoiceMode: KeyboardMode? = null
    private var activeVoicePolish = false
    private var activeVoiceTranslation = false
    private var lastCommittedText: String? = null
    private var lastCommitConnection: InputConnection? = null
    private var lastPackageName: String? = null
    private var voiceStartedAtMs = 0L
    private var activeRecordingDurationMs = 0L
    private var editorSessionId = 0L
    private var inputViewRecovery: InputViewRecovery? = null
    private var configurationChangeUntilMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var renderPending = false
    private val renderFrameCallback = Choreographer.FrameCallback {
        renderPending = false
        if (::keyboard.isInitialized) {
            keyboard.update(
                mode, voiceState, style, pinyinBuffer, pinyinCandidates,
                englishBuffer, englishCandidates, predictionCandidates, rawTranscript, sensitiveField, hapticStrength,
                pinyinReady, pinyinDecoder.statusText, keyboardTheme, keyboardSoundVolume, visibleKeyboardModes,
                clipboardEntries, chineseKeyboardLayout == ChineseKeyboardLayout.NINE_KEY,
                modeBeforeSymbols == KeyboardMode.ENGLISH, nineKeySymbols, keyboardCloseButtonEnabled,
                quickImeSwitcherEnabled, candidateTextSizeLevel, englishAutoCapitalize, shouldAutoCapitalizeEnglish(), keyboardLogo,
                currentRecentClipboard(), keyboardHeightLevel, keyboardBottomOffsetLevel, punctuationShortcuts,
                cursorSliderEnabled
            )
        }
    }
    private val clearLearningReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            when (intent.action) {
                ACTION_CLEAR_RIME_LEARNING -> clearPinyinLearning()
                ACTION_CLEAR_PREDICTION_LEARNING -> clearPredictionLearning()
                ACTION_RELOAD_RIME_BUNDLE -> reloadPinyinBundle()
            }
        }
    }
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { capturePrimaryClipboard() }

    override fun onCreate() {
        super.onCreate()
        (retainedKeyboardMode ?: loadRetainedKeyboardMode())?.let { mode = it }
        container = AppContainer(this)
        var startup = container.settings.keyboardStartupState()
        // A cache is populated after the first settings observation. When an app
        // upgrades from an older version, synchronously seed it once so a service
        // recreated by rotation does not flash the default dark palette.
        if (!startup.isSeeded) startup = container.settings.keyboardStartupStateBlocking()
        keyboardThemePreference = startup.theme
        keyboardTheme = resolveKeyboardTheme(startup.theme)
        chineseKeyboardLayout = startup.chineseLayout
        visibleKeyboardModes = startup.modes.map(::toKeyboardMode).distinct()
        hapticStrength = startup.haptic
        keyboardSoundVolume = startup.soundVolume
        keyboardCloseButtonEnabled = startup.closeButtonEnabled
        candidateTextSizeLevel = startup.candidateTextSizeLevel
        englishAutoCapitalize = startup.englishAutoCapitalize
        doubleSpacePeriod = startup.doubleSpacePeriod
        keyboardHeightLevel = startup.keyboardHeightLevel
        keyboardBottomOffsetLevel = startup.keyboardBottomOffsetLevel
        if (!isConfiguredMode(mode) && mode != KeyboardMode.SYMBOLS) {
            mode = initialModeFor(visibleKeyboardModes.firstOrNull() ?: KeyboardMode.TEXT)
        }
        recorder = AudioRecorder(this)
        keyboardSound = KeyboardSoundPlayer(this)
        pinyinDecoder = RimePinyinDecoder(this)
        jieba = JiebaSegmenter(this)
        predictionEngine = PredictionEngine(this, jieba)
        clipboardManager = getSystemService(ClipboardManager::class.java)
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        capturePrimaryClipboard()
        registerReceiver(
            clearLearningReceiver,
            IntentFilter().apply {
                addAction(ACTION_CLEAR_RIME_LEARNING)
                addAction(ACTION_CLEAR_PREDICTION_LEARNING)
                addAction(ACTION_RELOAD_RIME_BUNDLE)
            },
            Context.RECEIVER_NOT_EXPORTED
        )
        serviceScope.launch {
            for (operation in pinyinOperations) {
                try {
                    pinyinMutex.withLock { operation() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    // A malformed dictionary record or a one-off InputConnection
                    // failure must never kill the serialized key-event consumer.
                    // Once that coroutine exits, every following nine-key press is
                    // accepted by the view but remains forever queued.
                    Log.e(TAG, "Pinyin operation failed; resetting composition", error)
                    pendingPinyinMutations = 0
                    runCatching {
                        pinyinMutex.withLock {
                            applyPinyinState(pinyinDecoder.clear(), allowCommit = false)
                        }
                    }.onFailure { resetError ->
                        Log.e(TAG, "Unable to recover Pinyin operation queue", resetError)
                    }
                }
            }
        }
        serviceScope.launch {
            for (operation in predictionOperations) {
                try {
                    operation()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.w(TAG, "Prediction operation failed", error)
                }
            }
        }
        serviceScope.launch {
            container.settings.hapticStrength.collect { strength ->
                hapticStrength = strength
                container.settings.cacheKeyboardStartupState(haptic = strength)
                render()
            }
        }
        serviceScope.launch {
            container.settings.keyboardSoundVolume.collect { volume ->
                keyboardSoundVolume = volume
                container.settings.cacheKeyboardStartupState(soundVolume = volume)
                render()
            }
        }
        serviceScope.launch {
            container.settings.keyboardCloseButtonEnabled.collect { enabled ->
                keyboardCloseButtonEnabled = enabled
                container.settings.cacheKeyboardStartupState(closeButtonEnabled = enabled)
                render()
            }
        }
        serviceScope.launch {
            container.settings.candidateTextSizeLevel.collect { level ->
                candidateTextSizeLevel = level
                container.settings.cacheKeyboardStartupState(candidateTextSizeLevel = level)
                render()
            }
        }
        serviceScope.launch {
            container.settings.englishAutoCapitalize.collect { enabled ->
                englishAutoCapitalize = enabled
                container.settings.cacheKeyboardStartupState(englishAutoCapitalize = enabled)
                render()
            }
        }
        serviceScope.launch {
            container.settings.doubleSpacePeriod.collect { enabled ->
                doubleSpacePeriod = enabled
                container.settings.cacheKeyboardStartupState(doubleSpacePeriod = enabled)
            }
        }
        serviceScope.launch {
            container.settings.keyboardHeightLevel.collect { level ->
                keyboardHeightLevel = level
                container.settings.cacheKeyboardStartupState(keyboardHeightLevel = level)
                if (::keyboard.isInitialized) keyboard.requestLayout()
                render()
            }
        }
        serviceScope.launch {
            container.settings.keyboardBottomOffsetLevel.collect { level ->
                keyboardBottomOffsetLevel = level
                container.settings.cacheKeyboardStartupState(keyboardBottomOffsetLevel = level)
                if (::keyboard.isInitialized) keyboard.requestLayout()
                render()
            }
        }
        serviceScope.launch {
            container.settings.punctuationShortcuts.collect { enabled ->
                punctuationShortcuts = enabled
                render()
            }
        }
        serviceScope.launch {
            container.settings.cursorSliderEnabled.collect { enabled ->
                cursorSliderEnabled = enabled
                if (::keyboard.isInitialized) keyboard.requestLayout()
                render()
            }
        }
        serviceScope.launch {
            container.settings.predictionEnabled.collect { enabled ->
                predictionEnabled = enabled
                if (!enabled) clearPredictions()
            }
        }
        serviceScope.launch {
            container.settings.predictionLearningEnabled.collect { enabled ->
                predictionLearningEnabled = enabled
            }
        }
        serviceScope.launch {
            container.settings.keyboardTheme.collect { theme ->
                keyboardThemePreference = theme
                keyboardTheme = resolveKeyboardTheme(theme)
                container.settings.cacheKeyboardStartupState(theme = theme)
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
                container.settings.cacheKeyboardStartupState(modes = configured)
                render()
            }
        }
        serviceScope.launch {
            container.settings.keyboardStartupMode.collect { startupMode ->
                keyboardStartupMode = startupMode
            }
        }
        serviceScope.launch {
            container.settings.keyboardLogo.collect { logo ->
                keyboardLogo = logo
                render()
            }
        }
        serviceScope.launch {
            container.settings.chineseKeyboardLayout.collect { layout ->
                chineseKeyboardLayout = layout
                container.settings.cacheKeyboardStartupState(layout = layout)
                enqueuePinyin {
                    pinyinDecoder.selectChineseKeyboardLayout(layout)
                    applyPinyinState(pinyinDecoder.clear(), allowCommit = false)
                }
                render()
            }
        }
        serviceScope.launch {
            container.settings.nineKeySymbols.collect { symbols ->
                nineKeySymbols = symbols
                render()
            }
        }
        serviceScope.launch {
            container.settings.quickImeSwitcherEnabled.collect { enabled ->
                quickImeSwitcherEnabled = enabled
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
            lastPinyinLearning = container.pinyinLearning.all()
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
        serviceScope.launch {
            container.settings.recentClipboardPasteEnabled.collect { enabled ->
                recentClipboardPasteEnabled = enabled
                if (!enabled) clearRecentClipboard()
                else capturePrimaryClipboard()
                render()
            }
        }
    }

    override fun onCreateInputView(): FrameLayout {
        Log.d(TAG, "onCreateInputView; mode=$mode; pinyin=${pinyinRawBuffer.length}; english=${englishBuffer.length}")
        val host = FrameLayout(this)
        inputViewHost = host
        ensureKeyboard()
        if (!isLandscapeOverlayActive()) attachKeyboardTo(host)
        render()
        return host
    }

    private fun ensureKeyboard() {
        if (!::keyboard.isInitialized) keyboard = WeikeKeyboardView(this, this, keyboardSound)
    }

    private fun attachKeyboardTo(host: ViewGroup) {
        (keyboard.parent as? ViewGroup)?.removeView(keyboard)
        if (keyboard.parent == null) {
            host.addView(
                keyboard,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    private fun isLandscapeOverlayActive(): Boolean =
        overlayHost != null && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun updateLandscapeOverlay() {
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            dismissLandscapeOverlay()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("请先在维刻的快速开始中授权悬浮窗权限")
            return
        }
        if (overlayHost != null) return
        ensureKeyboard()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        // Landscape floating keyboard: width : height = 1.7 : 1. Its height
        // is capped at 60% of the available screen, then width is derived from
        // that cap so the requested ratio never changes.
        val maxPanelHeight = minOf(dp(330), (screenHeight * .60f).toInt())
        val panelHeight = minOf(maxPanelHeight, ((screenWidth - dp(32)) / 1.7f).toInt())
        val panelWidth = (panelHeight * 1.7f).toInt()
        val windowContext = createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val panel = FloatingKeyboardPanel(
            context = windowContext,
            canDrag = { pinyinBuffer.isBlank() && englishBuffer.isBlank() },
            showCloseButton = keyboardCloseButtonEnabled,
            onClose = ::closeLandscapeKeyboard,
            onMove = ::moveLandscapeKeyboard
        )
        panel.alpha = .80f
        attachKeyboardTo(panel.keyboardHost)
        val params = WindowManager.LayoutParams(
            panelWidth,
            panelHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = (screenWidth - panelWidth) / 2
            y = (screenHeight - panelHeight) / 2
            title = "Vertick Landscape Keyboard"
        }
        runCatching {
            val manager = windowContext.getSystemService(WindowManager::class.java)
            manager.addView(panel, params)
            overlayHost = panel
            overlayPanel = panel
            overlayWindowManager = manager
            overlayLayoutParams = params
            overlayContext = windowContext
            Log.d(TAG, "Landscape overlay attached")
            // Keep only the overlay at the top level. The standard IME window
            // would otherwise reserve bottom insets and push the editor up.
            mainHandler.post { requestHideSelf(0) }
        }.onFailure { error ->
            Log.e(TAG, "Unable to attach landscape overlay", error)
            inputViewHost?.let(::attachKeyboardTo)
        }
    }

    private fun scheduleLandscapeOverlay(delayMs: Long = 0L) {
        // Do not post this work to `keyboard`: after a floating keyboard is
        // closed that view has no attached window, and View.post() may never
        // run. The service main looper remains valid across both IME windows.
        mainHandler.removeCallbacks(landscapeOverlayRunnable)
        mainHandler.postDelayed(landscapeOverlayRunnable, delayMs)
    }

    private val landscapeOverlayRunnable = Runnable { updateLandscapeOverlay() }

    private fun dismissLandscapeOverlay() {
        val host = overlayHost ?: return
        runCatching { overlayWindowManager?.removeViewImmediate(host) }
            .onFailure { Log.w(TAG, "Unable to remove landscape overlay", it) }
        overlayHost = null
        overlayPanel = null
        overlayWindowManager = null
        overlayLayoutParams = null
        overlayContext = null
    }

    private fun moveLandscapeKeyboard(x: Int, y: Int) {
        val host = overlayHost ?: return
        val params = overlayLayoutParams ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        params.x = x.coerceIn(0, (screenWidth - params.width).coerceAtLeast(0))
        params.y = y.coerceIn(0, (screenHeight - params.height).coerceAtLeast(0))
        runCatching { overlayWindowManager?.updateViewLayout(host, params) }
            .onFailure { Log.w(TAG, "Unable to move landscape overlay", it) }
    }

    private fun closeLandscapeKeyboard() {
        dismissLandscapeOverlay()
        // Reset the framework's IME session as well. The next focused text
        // field produces onShowInputRequested(), where we reattach the overlay.
        requestHideSelf(0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()

    private inner class FloatingKeyboardPanel(
        context: Context,
        private val canDrag: () -> Boolean,
        private val showCloseButton: Boolean,
        private val onClose: () -> Unit,
        private val onMove: (Int, Int) -> Unit
    ) : FrameLayout(context) {
        val keyboardHost = FrameLayout(context)
        private var dragStartX = 0f
        private var dragStartY = 0f
        private var panelStartX = 0f
        private var panelStartY = 0f
        private var dragging = false

        init {
            setWillNotDraw(false)
            clipToOutline = false
            addView(keyboardHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            if (showCloseButton) {
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_lucide_chevron_down)
                    // Match the unselected icons in the keyboard's mode capsule.
                    setColorFilter(
                        if (keyboardTheme == KeyboardTheme.DARK) {
                            android.graphics.Color.rgb(190, 190, 192)
                        } else {
                            android.graphics.Color.rgb(105, 105, 112)
                        }
                    )
                    setPadding(dp(9), dp(9), dp(9), dp(9))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(19).toFloat()
                        setColor(
                            if (keyboardTheme == KeyboardTheme.DARK) {
                                android.graphics.Color.rgb(44, 44, 46)
                            } else {
                                android.graphics.Color.rgb(235, 235, 239)
                            }
                        )
                    }
                    contentDescription = "Hide floating keyboard"
                    setOnClickListener { onClose() }
                }, LayoutParams(dp(38), dp(38), android.view.Gravity.TOP or android.view.Gravity.END).apply {
                    topMargin = dp(10)
                    marginEnd = dp(7)
                })
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // Candidate rows replace the brand bar. Never intercept their first
            // entries as a drag gesture.
            val dragHandle = canDrag() && event.x <= dp(156) && event.y <= dp(58)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = dragHandle
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    panelStartX = overlayLayoutParams?.x?.toFloat() ?: 0f
                    panelStartY = overlayLayoutParams?.y?.toFloat() ?: 0f
                    return dragging
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        onMove(
                            (panelStartX + event.rawX - dragStartX).toInt(),
                            (panelStartY + event.rawY - dragStartY).toInt()
                        )
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    return true
                }
            }
            return true
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val dragHandle = canDrag() && event.x <= dp(156) && event.y <= dp(58)
                    dragHandle
                }
                else -> dragging
            }
        }
    }

    /**
     * The framework normally enters its legacy extract-editor UI in landscape.
     * Vertick only owns a custom input view, not an extract view; allowing that
     * transition replaces the keyboard with the framework's initial state and
     * loses the active editor on HyperOS. Keep the regular IME surface in both
     * orientations without changing its visual layout.
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val isPlaceholderConnection = (attribute?.fieldId ?: 0) < 0
        val recovery = inputViewRecovery?.takeIf {
            !isPlaceholderConnection &&
                it.matches(attribute, isSensitive(attribute)) &&
                SystemClock.elapsedRealtime() <= it.expiresAtMs
        }
        val configurationRestart = recovery != null ||
            (restarting && SystemClock.elapsedRealtime() <= configurationChangeUntilMs)
        Log.d(
            TAG,
            "onStartInput; restarting=$restarting; package=${attribute?.packageName}; field=${attribute?.fieldId}; " +
                "recovery=${recovery != null}; mode=$mode"
        )
        inputViewRecovery = null
        if (voiceState != VoiceUiState.Idle || activeVoiceMode != null || rawTranscript.isNotBlank()) {
            cancelActiveVoiceSession()
        }
        lastPackageName = attribute?.packageName
        sensitiveField = isSensitive(attribute)
        if (!configurationRestart) {
            editorSessionId += 1
            currentInputConnection?.finishComposingText()
            clearTextCompositions()
            clearPredictions(clearContext = true)
            if (sensitiveField) mode = KeyboardMode.ENGLISH
            else applyConfiguredStartupMode()
        } else if (recovery != null) {
            restoreInputViewRecovery(recovery)
            Log.d(TAG, "Restored IME session after configuration change; mode=${recovery.mode}")
        } else {
            // The client connection was recreated but Android did not send a
            // finish callback. Preserve the existing decoder state rather than
            // treating rotation as a new editor session.
            Log.d(TAG, "Preserved IME session for configuration restart")
        }
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
        Log.d(TAG, "onStartInputView; restarting=$restarting; mode=$mode; pinyin=${pinyinRawBuffer.length}; english=${englishBuffer.length}")
        super.onStartInputView(info, restarting)
        inputViewActive = true
        presentRecentClipboardForInputView()
        // Some Xiaomi builds recreate only the input view. Reassert the composing
        // value against the newly supplied InputConnection in that case.
        if (pinyinBuffer.isNotBlank()) updateComposingText(pinyinBuffer)
        else if (englishBuffer.isNotBlank()) updateComposingText(englishBuffer)
        if (::keyboard.isInitialized) keyboard.requestLayout()
        render()
    }

    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        val accepted = super.onShowInputRequested(flags, configChange)
        if (!configChange && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // Only a fresh request from the focused editor may open the floating
            // keyboard. Rotation, shade expansion and window recreation also
            // reach the IME lifecycle, but must never summon an overlay.
            ensureKeyboard()
            scheduleLandscapeOverlay(80L)
        }
        return accepted
    }

    override fun onWindowShown() {
        super.onWindowShown()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Log.d(TAG, "onFinishInputView; finishingInput=$finishingInput; mode=$mode; pinyin=${pinyinRawBuffer.length}; english=${englishBuffer.length}")
        // Xiaomi may report `finishingInput=true` while replacing the view during
        // rotation. Keep a short-lived, same-editor snapshot in either case; a
        // different field cannot restore it because InputViewRecovery.matches()
        // checks the package, input type, and field id.
        captureInputViewRecovery()
        inputViewActive = false
        stopVoice(cancelled = true)
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        // Some Xiaomi rotation paths finish the editor before they finish the
        // input view. Capture here too; the snapshot is accepted only by the
        // same package/input type/field during the five-second rotation window.
        captureInputViewRecovery()
        Log.d(TAG, "onFinishInput; mode=$mode; pinyin=${pinyinRawBuffer.length}")
        super.onFinishInput()
    }

    override fun onWindowHidden() {
        // HyperOS hides and recreates the regular IME window during landscape
        // rotation. That is precisely when the overlay takes over, so treating
        // this callback as an explicit user dismissal creates an endless
        // show/hide loop. Portrait uses the system IME normally and still
        // clears any stale fallback surface.
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        Log.d(TAG, "onWindowHidden; mode=$mode; landscape=$landscape; overlay=${overlayHost != null}")
        if (!landscape) dismissLandscapeOverlay()
        inputViewActive = false
        super.onWindowHidden()
    }

    override fun onUnbindInput() {
        // Back gestures and editor changes can unbind the IME without sending a
        // separate hide callback on HyperOS. The landscape surface must never
        // outlive that input session.
        dismissLandscapeOverlay()
        super.onUnbindInput()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        captureInputViewRecovery()
        configurationChangeUntilMs = SystemClock.elapsedRealtime() + INPUT_VIEW_RECOVERY_WINDOW_MS
        Log.d(TAG, "onConfigurationChanged; orientation=${newConfig.orientation}; mode=$mode; pinyin=${pinyinRawBuffer.length}")
        super.onConfigurationChanged(newConfig)
        if (keyboardThemePreference == KeyboardTheme.SYSTEM) {
            keyboardTheme = resolveKeyboardTheme(keyboardThemePreference)
            render()
        }
        if (::keyboard.isInitialized) keyboard.requestLayout()
        // The mapped dictionary is already alive. Reopening it on every rotation
        // races with composition restore and previously cleared the nine-key code.
        // A cold service still initializes through onCreate().
        if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            // The overlay is only a landscape fallback. Never leave it attached
            // above the app after the user returns to portrait.
            dismissLandscapeOverlay()
        } else {
            // A delayed task posted before rotation would otherwise create an
            // overlay even though the user has not focused an editor in the
            // new orientation.
            mainHandler.removeCallbacks(landscapeOverlayRunnable)
        }
        render()
    }

    override fun onDestroy() {
        stopVoice(cancelled = true)
        mainHandler.removeCallbacks(landscapeOverlayRunnable)
        dismissLandscapeOverlay()
        if (::keyboardSound.isInitialized) keyboardSound.release()
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
        predictionOperations.close()
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
        flushPendingPinyinBatch()
        if (this.mode == KeyboardMode.PINYIN && (pinyinRawBuffer.isNotBlank() || pendingPinyinMutations > 0)) {
            enqueuePinyin {
                commitCurrentPinyin()
                applyMode(mode)
            }
        } else applyMode(mode)
    }

    override fun closeKeyboard() {
        if (voiceState != VoiceUiState.Idle || activeVoiceMode != null || rawTranscript.isNotBlank()) {
            cancelActiveVoiceSession()
        }
        if (isLandscapeOverlayActive()) dismissLandscapeOverlay()
        requestHideSelf(0)
    }

    private fun applyMode(nextMode: KeyboardMode) {
        val previous = mode
        mode = nextMode
        if (previous != nextMode) clearPredictions()
        rememberKeyboardMode(mode)
        if (mode == KeyboardMode.PINYIN) requestPinyinEngine()
        if (previous == KeyboardMode.PINYIN && mode != KeyboardMode.PINYIN) scheduleLanguageEngineRelease()
        render()
    }

    private fun requestPinyinEngine() {
        engineIdleReleaseJob?.cancel()
        if (engineLoadJob?.isActive == true) return
        pinyinReady = pinyinDecoder.isReady
        engineLoadJob = serviceScope.launch(Dispatchers.Default) {
            runCatching {
                ensureLatestLanguageData()
                pinyinReady = pinyinDecoder.startSession(lastSyncedTerms, lastSyncedTypingDictionary, lastPinyinLearning)
                pinyinDecoder.selectChineseKeyboardLayout(chineseKeyboardLayout)
                ensureJiebaReady()
            }.onFailure {
                pinyinReady = false
                Log.e(TAG, "Unable to start librime Pinyin", it)
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
        if (lastPinyinLearning.isEmpty()) lastPinyinLearning = container.pinyinLearning.all()
    }

    private fun scheduleLanguageEngineRelease() {
        // Keeping Rime warm prevents the loading state whenever the IME is reopened.
        // Resources are released only when the IME service is destroyed.
        engineIdleReleaseJob?.cancel()
    }

    private fun deferRimeTermSync() {
        // Updating a user dictionary only updates an in-memory overlay. It must not
        // restart or compile an engine while the user is typing.
        rimeTermStageJob?.cancel()
        rimeTermStageJob = serviceScope.launch(Dispatchers.Default) {
            runCatching {
                pinyinDecoder.syncProfessionalTerms(lastSyncedTerms, lastSyncedTypingDictionary)
            }.onFailure { Log.e(TAG, "Unable to update local Pinyin terms", it) }
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

    override fun setLongPressTranslation(selected: Boolean) {
        if (voiceState == VoiceUiState.Listening && activeVoiceMode == KeyboardMode.VOICE && activeVoicePolish) {
            activeVoiceTranslation = selected
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

    override fun insertAnswer() {
        val answer = (voiceState as? VoiceUiState.Preview)?.takeIf {
            mode == KeyboardMode.ASK && !it.streaming && it.text.isNotBlank()
        } ?: return
        if (sensitiveField) {
            toast("敏感输入框不能插入问答内容")
            return
        }
        finishCompositionThen {
            val connection = currentInputConnection ?: return@finishCompositionThen
            connection.commitText(answer.text, 1)
            lastCommittedText = answer.text
            lastCommitConnection = connection
            rawTranscript = ""
            voiceState = VoiceUiState.Idle
            render()
        }
    }

    override fun pasteClipboard(entry: ClipboardEntry) {
        finishCompositionThen { currentInputConnection?.commitText(entry.content, 1) }
    }

    override fun pasteRecentClipboard() {
        val content = recentClipboardContent.takeIf {
            it.isNotBlank() && SystemClock.elapsedRealtime() - recentClipboardCapturedAtMs <= RECENT_CLIPBOARD_WINDOW_MS
        } ?: return
        finishCompositionThen { currentInputConnection?.commitText(content, 1) }
        clearRecentClipboard()
        render()
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
        activeVoiceTranslation = false
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
        val translationMode = activeVoiceTranslation
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
        val minimumDurationMs = if (polishMode || translationMode) MINIMUM_POLISHED_RECORDING_MS else MINIMUM_RECORDING_MS
        if (recordingDurationMs < minimumDurationMs) {
            voiceSessionId += 1
            activeVoiceMode = null
            activeVoicePolish = false
            activeVoiceTranslation = false
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
            val activePunctuation = if (askMode) punctuation else container.settings.punctuationPreference()
            val transcription = withTimeoutOrNull(ASR_TIMEOUT_MS) {
                ensureVoiceSession(sessionId, sessionMode)
                asrClient.transcribe(pcm)
            } ?: Result.failure(IllegalStateException("语音识别超时，请重试"))
            ensureVoiceSession(sessionId, sessionMode)
            val source = transcription.getOrElse { error ->
                activeVoiceMode = null
                voiceState = VoiceUiState.Error("语音识别失败：${error.message ?: "网络不可用"}")
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
            if (translationMode) {
                val translated = withTimeoutOrNull(TRANSLATION_TIMEOUT_MS) {
                    ensureVoiceSession(sessionId, sessionMode)
                    polisher.translateToAmericanEnglish(source)
                }?.getOrElse { error ->
                    Log.w(TAG, "Translation failed; inserting transcription", error)
                    source
                } ?: source
                commitResult(
                    translated,
                    sessionId,
                    sessionMode,
                    instant = true,
                    historyType = InputHistoryType.TRANSLATION,
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
            val explicitStructureHint = StructuredExpressionFormatter.needsStructure(source)
            val structureHint = !askMode && (
                explicitStructureHint || (optimizeExpression && jieba.hasStructuredExpression(source))
            )
            val deepPolish = !askMode && (explicitStructureHint || (optimizeExpression && shouldUseDeepPolish(source)))
            val processingTimeout = when {
                askMode -> ANSWER_PROCESSING_TIMEOUT_MS
                deepPolish -> DEEP_POLISHING_TIMEOUT_MS
                else -> FAST_POLISHING_TIMEOUT_MS
            }
            val result = withTimeoutOrNull(processingTimeout) {
                ensureVoiceSession(sessionId, sessionMode)
                // Both voice modes always send ASR text, never PCM audio, to the text model.
                if (askMode) return@withTimeoutOrNull processAskWorkflow(source, sessionId, sessionMode)
                if (!deepPolish) {
                    val fastText = polisher.polishFast(source, style, activePunctuation, terms).getOrElse { error ->
                        Log.w(TAG, "Fast polishing failed; inserting local fallback", error)
                        localPolishFallback(source)
                    }
                    val structuredFastText = StructuredExpressionFormatter.enforce(source, fastText)
                    return@withTimeoutOrNull VoiceProcessingResult.Success(
                        text = applyPunctuationPreference(structuredFastText, activePunctuation),
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
                val structuredText = StructuredExpressionFormatter.enforce(source, polishedText)
                VoiceProcessingResult.Success(applyPunctuationPreference(structuredText, activePunctuation))
            }
            if (!isCurrentVoiceSession(sessionId, sessionMode)) return@launch
            when (result) {
                null -> {
                    if (sessionMode == KeyboardMode.VOICE && rawTranscript.isNotBlank()) {
                        commitResult(
                            applyPunctuationPreference(
                                StructuredExpressionFormatter.enforce(rawTranscript, localPolishFallback(rawTranscript)),
                                activePunctuation
                            ),
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
                is VoiceProcessingResult.WorkflowApplied -> if (sessionMode == KeyboardMode.ASK) {
                    activeVoiceMode = null
                    rawTranscript = ""
                    voiceState = VoiceUiState.Idle
                    toastFor(result.message, 1_800L)
                    render()
                }
            }
        }
    }

    private suspend fun processAskWorkflow(
        command: String,
        sessionId: Long,
        sessionMode: KeyboardMode
    ): VoiceProcessingResult {
        val localIntent = WorkflowRouter.route(command)
        val intent = when (localIntent) {
            is WorkflowIntent.Ambiguous -> polisher.classifyWorkflow(localIntent.command)
                .fold(
                    onSuccess = { WorkflowRouter.fromClassification(localIntent.command, it) },
                    onFailure = { WorkflowIntent.Answer(localIntent.command) }
                )
            else -> localIntent
        }
        ensureVoiceSession(sessionId, sessionMode)
        return when (intent) {
            is WorkflowIntent.Answer -> answerQuestion(intent.question, sessionId, sessionMode)
            is WorkflowIntent.ExactReplace -> applyExactWorkflowReplace(intent, sessionId, sessionMode)
            is WorkflowIntent.TargetedReplace -> applyTargetedWorkflowReplace(intent, sessionId, sessionMode)
            is WorkflowIntent.Continue -> applyModelWorkflowEdit(intent.instruction, WorkflowEditOperation.CONTINUE, sessionId, sessionMode)
            is WorkflowIntent.Summarize -> applyModelWorkflowEdit(intent.instruction, WorkflowEditOperation.SUMMARIZE, sessionId, sessionMode)
            is WorkflowIntent.Expand -> applyModelWorkflowEdit(intent.instruction, WorkflowEditOperation.EXPAND, sessionId, sessionMode)
            is WorkflowIntent.Translate -> applyModelWorkflowEdit(intent.instruction, WorkflowEditOperation.TRANSLATE, sessionId, sessionMode)
            is WorkflowIntent.Proofread -> applyModelWorkflowEdit(intent.instruction, WorkflowEditOperation.PROOFREAD, sessionId, sessionMode)
            is WorkflowIntent.Format -> applyModelWorkflowEdit(intent.instruction, WorkflowEditOperation.FORMAT, sessionId, sessionMode)
            is WorkflowIntent.Extract -> applyModelWorkflowEdit(intent.instruction, WorkflowEditOperation.EXTRACT, sessionId, sessionMode)
            is WorkflowIntent.Reply -> applyModelWorkflowEdit(intent.instruction, WorkflowEditOperation.REPLY, sessionId, sessionMode)
            is WorkflowIntent.Rewrite -> applyModelWorkflowEdit(intent.instruction, WorkflowEditOperation.REWRITE, sessionId, sessionMode)
            is WorkflowIntent.Polish -> applyModelWorkflowEdit(intent.instruction, WorkflowEditOperation.POLISH, sessionId, sessionMode)
            is WorkflowIntent.Ambiguous -> VoiceProcessingResult.Error("无法识别该命令")
        }
    }

    private suspend fun applyExactWorkflowReplace(
        intent: WorkflowIntent.ExactReplace,
        sessionId: Long,
        sessionMode: KeyboardMode
    ): VoiceProcessingResult {
        if (sensitiveField) return VoiceProcessingResult.Error("敏感输入框不支持文本修改")
        val snapshot = captureEditorSnapshot() ?: return VoiceProcessingResult.Error("当前输入框没有可修改的文本")
        if (!snapshot.text.contains(intent.source)) {
            return VoiceProcessingResult.Error("没有找到“${intent.source}”，未修改内容")
        }
        ensureVoiceSession(sessionId, sessionMode)
        val revised = snapshot.text.replace(intent.source, intent.replacement)
        return if (replaceEditorSnapshot(snapshot, revised)) {
            VoiceProcessingResult.WorkflowApplied("已${snapshot.scope.label}修改文本")
        } else {
            VoiceProcessingResult.Error("文本已变化，请重新执行命令")
        }
    }

    private suspend fun applyTargetedWorkflowReplace(
        intent: WorkflowIntent.TargetedReplace,
        sessionId: Long,
        sessionMode: KeyboardMode
    ): VoiceProcessingResult {
        if (sensitiveField) return VoiceProcessingResult.Error("敏感输入框不支持文本修改")
        val snapshot = captureEditorSnapshot() ?: return VoiceProcessingResult.Error("当前输入框没有可修改的文本")
        val revised = when (intent.target) {
            WorkflowReplaceTarget.PARENTHESES_CONTENT -> replaceNearestDelimitedContent(
                snapshot.text,
                snapshot.beforeCursor.length,
                intent.replacement,
                listOf('（' to '）', '(' to ')')
            )
            WorkflowReplaceTarget.QUOTED_CONTENT -> replaceNearestDelimitedContent(
                snapshot.text,
                snapshot.beforeCursor.length,
                intent.replacement,
                listOf('“' to '”', '‘' to '’', '"' to '"', '\'' to '\'')
            )
            WorkflowReplaceTarget.SELECTED_TEXT -> {
                if (snapshot.selectedText.isEmpty()) null
                else snapshot.text.replaceRange(
                    snapshot.beforeCursor.length,
                    snapshot.beforeCursor.length + snapshot.selectedText.length,
                    intent.replacement
                )
            }
        }
        if (revised == null) {
            val targetLabel = when (intent.target) {
                WorkflowReplaceTarget.PARENTHESES_CONTENT -> "括号里的内容"
                WorkflowReplaceTarget.QUOTED_CONTENT -> "引号里的内容"
                WorkflowReplaceTarget.SELECTED_TEXT -> "选中的内容"
            }
            return VoiceProcessingResult.Error("没有找到$targetLabel，未修改内容")
        }
        ensureVoiceSession(sessionId, sessionMode)
        return if (replaceEditorSnapshot(snapshot, revised)) {
            VoiceProcessingResult.WorkflowApplied("已${snapshot.scope.label}替换内容")
        } else {
            VoiceProcessingResult.Error("文本已变化，请重新执行命令")
        }
    }

    /** Replaces one named range: prefer the range under the cursor, otherwise the nearest one. */
    private fun replaceNearestDelimitedContent(
        text: String,
        cursor: Int,
        replacement: String,
        delimiters: List<Pair<Char, Char>>
    ): String? {
        data class Range(val start: Int, val end: Int)
        val ranges = buildList {
            delimiters.forEach { (opening, closing) ->
                val stack = ArrayDeque<Int>()
                text.forEachIndexed { index, character ->
                    if (opening == closing && character == opening) {
                        if (stack.isEmpty()) stack.addLast(index)
                        else add(Range(stack.removeLast() + 1, index))
                    } else {
                        when (character) {
                            opening -> stack.addLast(index)
                            closing -> if (stack.isNotEmpty()) add(Range(stack.removeLast() + 1, index))
                        }
                    }
                }
            }
        }
        val selected = ranges.minWithOrNull(
            compareBy<Range> { range ->
                when {
                    cursor < range.start -> range.start - cursor
                    cursor > range.end -> cursor - range.end
                    else -> 0
                }
            }.thenBy { it.end - it.start }
        ) ?: return null
        return text.replaceRange(selected.start, selected.end, replacement)
    }

    private suspend fun applyModelWorkflowEdit(
        instruction: String,
        operation: WorkflowEditOperation,
        sessionId: Long,
        sessionMode: KeyboardMode
    ): VoiceProcessingResult {
        if (sensitiveField) return VoiceProcessingResult.Error("敏感输入框不支持文本修改")
        val snapshot = captureEditorSnapshot() ?: return VoiceProcessingResult.Error("当前输入框没有可修改的文本")
        val terms = container.lexicon.all()
        val revised = polisher.editWorkflowText(
            instruction = workflowInstruction(operation, instruction),
            text = snapshot.text,
            style = style,
            punctuation = punctuation,
            lexicon = terms,
            optimizeExpression = operation in setOf(WorkflowEditOperation.POLISH, WorkflowEditOperation.FORMAT) && expressionOptimization
        ).getOrElse { error ->
            return VoiceProcessingResult.Error("${operation.label}失败：${error.message ?: "网络不可用"}")
        }.trim()
        ensureVoiceSession(sessionId, sessionMode)
        if (revised.isBlank()) return VoiceProcessingResult.Error("模型未返回修改后的文本")
        if (revised == snapshot.text) return VoiceProcessingResult.WorkflowApplied("内容没有变化")
        return if (replaceEditorSnapshot(snapshot, revised)) {
            VoiceProcessingResult.WorkflowApplied("已${snapshot.scope.label}${operation.label}")
        } else {
            VoiceProcessingResult.Error("文本已变化，请重新执行命令")
        }
    }

    private fun workflowInstruction(operation: WorkflowEditOperation, command: String): String = when (operation) {
        WorkflowEditOperation.CONTINUE ->
            "在不改动、删减或重复原文的前提下，沿用原文的语言、语气和上下文自然续写；只新增结尾内容，并返回原文加新增续写后的完整文本。"
        WorkflowEditOperation.SUMMARIZE ->
            "根据用户命令总结原文，保留核心事实、结论、数字和限制；删除冗余，输出摘要本身。用户补充：$command"
        WorkflowEditOperation.EXPAND ->
            "根据用户命令扩写原文，补足必要衔接、说明或细节；不得编造事实、数据或经历。用户补充：$command"
        WorkflowEditOperation.TRANSLATE ->
            "将原文按用户指定的目标语言自然翻译；未指定时翻译为自然英语。保留段落、专有名词、数字、链接和代码，只输出译文。用户补充：$command"
        WorkflowEditOperation.PROOFREAD ->
            "只修正原文中的错别字、语病、明显标点和空格问题；不得改变原意、文风、结构或补充内容。用户补充：$command"
        WorkflowEditOperation.FORMAT ->
            "按用户命令整理原文格式，保留全部信息；需要列表、待办、标题或 Markdown 时使用真实换行，不得凭空增加内容。用户补充：$command"
        WorkflowEditOperation.EXTRACT ->
            "从原文中提取用户要求的信息；没有指定类型时提取关键要点。只输出提取结果，不得编造未出现的信息。用户补充：$command"
        WorkflowEditOperation.REPLY ->
            "将原文视为待回复的消息或上下文，按用户要求生成一条可直接发送的回复；只输出回复正文，不要复述原文、说明或引号。用户补充：$command"
        WorkflowEditOperation.REWRITE -> command
        WorkflowEditOperation.POLISH -> command
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
        activeVoiceTranslation = false
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
                .split('\n')
                .joinToString("\n") { line ->
                    line.replace(Regex("[，。！？、；：,.!?;:…—()（）\\[\\]{}\\\"'“”]+"), " ")
                        .replace(Regex("[ \\t]+"), " ")
                        .trim()
                }
                .trim()
            PunctuationPreference.NO_END -> cleaned
            .replace(Regex("[\\s，。！？、；：,.!?;:…—]+$"), "")
            .trimEnd()
        }
    }

    private fun cleanSymbols(text: String): String = text
        .replace("\r\n", "\n")
        .replace(Regex("[\\u00A0\\t\\r]+"), " ")
        .lineSequence()
        .joinToString("\n") { line ->
            line.replace(Regex(" {2,}"), " ")
                .replace(Regex(" +([，。！？、；：,.!?;:])"), "$1")
                .trim()
        }
        .replace(Regex("\\n{3,}"), "\n\n")
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

    private fun captureEditorSnapshot(): EditorTextSnapshot? {
        val connection = currentInputConnection ?: return null
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
        val fullText = extracted?.text?.toString()
        if (!fullText.isNullOrEmpty() && extracted.startOffset == 0 && extracted.partialStartOffset < 0) {
            val start = extracted.selectionStart.coerceIn(0, fullText.length)
            val end = extracted.selectionEnd.coerceIn(start, fullText.length)
            return EditorTextSnapshot(
                connection = connection,
                editorSessionId = editorSessionId,
                text = fullText,
                beforeCursor = fullText.substring(0, start),
                selectedText = fullText.substring(start, end),
                afterCursor = fullText.substring(end),
                scope = WorkflowTextScope.FULL,
                fullText = fullText,
                selectionStart = start,
                selectionEnd = end
            )
        }

        val selected = connection.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotEmpty()) {
            return EditorTextSnapshot(
                connection = connection,
                editorSessionId = editorSessionId,
                text = selected,
                beforeCursor = "",
                selectedText = selected,
                afterCursor = "",
                scope = WorkflowTextScope.SELECTION
            )
        }

        val before = connection.getTextBeforeCursor(MAX_WORKFLOW_CONTEXT_CHARS, 0)?.toString().orEmpty()
        val after = connection.getTextAfterCursor(MAX_WORKFLOW_CONTEXT_CHARS, 0)?.toString().orEmpty()
        val paragraphBefore = before.substringAfterLast('\n')
        val paragraphAfter = after.substringBefore('\n')
        val paragraph = paragraphBefore + paragraphAfter
        if (paragraph.isBlank()) return null
        return EditorTextSnapshot(
            connection = connection,
            editorSessionId = editorSessionId,
            text = paragraph,
            beforeCursor = paragraphBefore,
            selectedText = "",
            afterCursor = paragraphAfter,
            scope = WorkflowTextScope.PARAGRAPH
        )
    }

    /**
     * The editor may change while the model request is in flight. Validate both the
     * IME connection and the exact range around the cursor before a destructive edit.
     */
    private fun replaceEditorSnapshot(snapshot: EditorTextSnapshot, replacement: String): Boolean {
        if (snapshot.connection !== currentInputConnection || snapshot.editorSessionId != editorSessionId || sensitiveField) return false
        val connection = snapshot.connection
        if (snapshot.fullText != null) {
            val current = connection.getExtractedText(ExtractedTextRequest(), 0) ?: return false
            if (current.text?.toString() != snapshot.fullText ||
                current.selectionStart != snapshot.selectionStart || current.selectionEnd != snapshot.selectionEnd
            ) return false
        } else {
            val currentSelected = connection.getSelectedText(0)?.toString().orEmpty()
            val currentBefore = connection.getTextBeforeCursor(snapshot.beforeCursor.length, 0)?.toString().orEmpty()
            val currentAfter = connection.getTextAfterCursor(snapshot.afterCursor.length, 0)?.toString().orEmpty()
            if (currentSelected != snapshot.selectedText ||
                currentBefore != snapshot.beforeCursor || currentAfter != snapshot.afterCursor
            ) return false
        }
        val batchStarted = connection.beginBatchEdit()
        return try {
            // commitText first removes a live selection. The remaining deletion is
            // relative to the now-collapsed cursor, avoiding global setSelection.
            if (snapshot.selectedText.isNotEmpty()) connection.commitText("", 1)
            if (snapshot.beforeCursor.isNotEmpty() || snapshot.afterCursor.isNotEmpty()) {
                connection.deleteSurroundingText(snapshot.beforeCursor.length, snapshot.afterCursor.length)
            }
            connection.commitText(replacement, 1)
        } finally {
            if (batchStarted) connection.endBatchEdit()
        }
    }

    private enum class WorkflowTextScope(val label: String) {
        FULL("全文"),
        SELECTION("选中文本"),
        PARAGRAPH("当前段落")
    }

    private data class EditorTextSnapshot(
        val connection: InputConnection,
        val editorSessionId: Long,
        val text: String,
        val beforeCursor: String,
        val selectedText: String,
        val afterCursor: String,
        val scope: WorkflowTextScope,
        val fullText: String? = null,
        val selectionStart: Int = -1,
        val selectionEnd: Int = -1
    )

    private enum class WorkflowEditOperation(val label: String) {
        CONTINUE("续写"),
        SUMMARIZE("总结"),
        EXPAND("扩写"),
        TRANSLATE("翻译"),
        PROOFREAD("改错"),
        FORMAT("整理格式"),
        EXTRACT("提取"),
        REPLY("生成回复"),
        REWRITE("修改"),
        POLISH("润色")
    }

    /*
     * The service only ever keeps this small result in memory. It avoids a late HTTP
     * completion inserting content after the visual timeout has restored the keyboard.
     */
    private sealed interface VoiceProcessingResult {
        data class Success(val text: String, val instant: Boolean = false) : VoiceProcessingResult
        data class Answer(val question: String, val text: String) : VoiceProcessingResult
        data class WorkflowApplied(val message: String) : VoiceProcessingResult
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
        if (value != " " || mode == KeyboardMode.SYMBOLS) resetDoubleSpacePeriod()
        if (mode == KeyboardMode.SYMBOLS && modeBeforeSymbols == KeyboardMode.PINYIN && pinyinRawBuffer.isNotBlank()) {
            enqueuePinyin {
                commitCurrentPinyin()
                commitTextAndPredict(value)
            }
        } else if (mode == KeyboardMode.SYMBOLS && englishBuffer.isNotBlank()) {
            commitEnglishComposition(false)
            commitTextAndPredict(value)
        } else commitTextAndPredict(value)
    }

    override fun typeEnglishLetter(value: String) {
        resetDoubleSpacePeriod()
        clearPredictions()
        englishBuffer += value
        englishCandidates = EnglishCandidateEngine.candidates(englishBuffer)
        updateComposingText(englishBuffer)
        render()
    }

    override fun typePinyin(value: String) {
        engineIdleReleaseJob?.cancel()
        if (value.length != 1) return
        if (pendingPinyinLetters.isEmpty()) clearPredictions()
        pendingPinyinMutations += 1
        pendingPinyinLetters.append(value.lowercase())
        if (!pinyinBatchScheduled) {
            pinyinBatchScheduled = true
            Choreographer.getInstance().postFrameCallback { flushPendingPinyinBatch() }
        }
    }

    override fun chooseCandidate(candidate: PinyinCandidate) {
        flushPendingPinyinBatch()
        val targetConnection = currentInputConnection
        enqueuePinyin {
            if (candidate.directCommit) {
                commitDirectPinyin(candidate.text, targetConnection)
            } else {
                val current = pinyinDecoder.currentState()
                val verified = current.candidates.firstOrNull {
                    !it.directCommit && it.index == candidate.index && it.text == candidate.text
                } ?: current.candidates.firstOrNull { !it.directCommit && it.text == candidate.text }
                if (verified == null) {
                    applyPinyinState(current, allowCommit = false)
                    return@enqueuePinyin
                }
                val state = pinyinDecoder.selectCandidate(verified.index)
                // Selecting a segment of a long Rime composition does not commit
                // text yet. It only confirms a segment inside the live preedit.
                // The engine's commit is authoritative; using the tapped text as
                // a fallback here duplicates it when the final segment commits.
                commitNativePinyin(state, state.committedText, targetConnection)
            }
        }
    }

    override fun chooseEnglishCandidate(value: String) {
        resetDoubleSpacePeriod()
        commitTextAndPredict(value)
        recordEnglishSelection(value)
        englishBuffer = ""
        englishCandidates = emptyList()
        updateComposingText(englishBuffer)
        render()
    }

    override fun choosePrediction(candidate: PredictionCandidate) {
        resetDoubleSpacePeriod()
        commitTextAndPredict(candidate.text, candidate.appendSpace)
    }

    override fun commitEnglishComposition(addSpace: Boolean) {
        if (!addSpace) resetDoubleSpacePeriod()
        val value = englishCandidates.firstOrNull()?.text ?: englishBuffer
        if (value.isNotBlank()) {
            commitTextAndPredict(value)
            recordEnglishSelection(value)
        }
        if (addSpace) currentInputConnection?.commitText(" ", 1)
        englishBuffer = ""
        englishCandidates = emptyList()
        updateComposingText(englishBuffer)
        render()
    }

    override fun pressTextSpace(pinyin: Boolean) {
        if (pinyin && (pinyinRawBuffer.isNotBlank() || pendingPinyinMutations > 0)) {
            enqueueAfterPendingPinyin {
                commitCurrentPinyin()
            }
            resetDoubleSpacePeriod()
            return
        }
        if (!pinyin && englishBuffer.isNotBlank()) {
            commitEnglishComposition(true)
            markTextSpace()
            return
        }
        insertTextSpace(pinyin)
    }

    override fun backspace() {
        resetDoubleSpacePeriod()
        clearPredictions()
        flushPendingPinyinBatch()
        if (mode == KeyboardMode.PINYIN && (pinyinRawBuffer.isNotEmpty() || pendingPinyinMutations > 0)) {
            pendingPinyinMutations += 1
            enqueuePinyin {
                try {
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
            deletePreviousEditorText()
        }
    }

    override fun canBackspace(): Boolean {
        if (mode == KeyboardMode.PINYIN && (pinyinRawBuffer.isNotEmpty() || pendingPinyinMutations > 0)) return true
        if (mode == KeyboardMode.ENGLISH && englishBuffer.isNotEmpty()) return true
        val connection = currentInputConnection ?: return false
        return connection.getSelectedText(0)?.isNotEmpty() == true ||
            connection.getTextBeforeCursor(2, 0)?.isNotEmpty() == true
    }

    /** Deletes one user-visible character, never a single UTF-16 surrogate unit. */
    private fun deletePreviousEditorText(): Boolean {
        val connection = currentInputConnection ?: return false
        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            return connection.commitText("", 1) || sendDeleteKey(connection)
        }
        val preceding = connection.getTextBeforeCursor(MAX_DELETE_CONTEXT_CHARS, 0)?.toString().orEmpty()
        if (preceding.isEmpty()) return false
        val deleteLength = previousGraphemeLength(preceding)
        return connection.deleteSurroundingText(deleteLength, 0) || sendDeleteKey(connection)
    }

    private fun previousGraphemeLength(text: String): Int {
        val iterator = BreakIterator.getCharacterInstance(Locale.getDefault())
        iterator.setText(text)
        val start = iterator.preceding(text.length)
        return if (start == BreakIterator.DONE) {
            Character.charCount(text.codePointBefore(text.length))
        } else {
            (text.length - start).coerceAtLeast(1)
        }
    }

    private fun sendDeleteKey(connection: InputConnection): Boolean =
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)) &&
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))

    override fun moveCursorBy(delta: Int) {
        if (delta == 0) return
        finishCompositionThen {
            val connection = currentInputConnection ?: return@finishCompositionThen
            val keyCode = if (delta > 0) android.view.KeyEvent.KEYCODE_DPAD_RIGHT else android.view.KeyEvent.KEYCODE_DPAD_LEFT
            val requested = kotlin.math.abs(delta).coerceAtMost(12)
            val available = if (delta > 0) {
                connection.getTextAfterCursor(requested, 0)?.length ?: 0
            } else {
                connection.getTextBeforeCursor(requested, 0)?.length ?: 0
            }
            repeat(minOf(requested, available)) {
                connection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
                connection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
            }
        }
        clearPredictions()
    }

    override fun enter() {
        if (mode == KeyboardMode.PINYIN && (pinyinRawBuffer.isNotBlank() || pendingPinyinMutations > 0)) {
            enqueueAfterPendingPinyin { commitRawPinyin() }
        } else {
            finishCompositionThen { currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_SEND) }
        }
    }

    override fun newline() {
        finishCompositionThen { currentInputConnection?.commitText("\n", 1) }
    }

    private fun finishCompositionThen(action: () -> Unit) {
        if (mode == KeyboardMode.PINYIN && (pinyinRawBuffer.isNotEmpty() || pendingPinyinMutations > 0)) {
            enqueueAfterPendingPinyin {
                commitCurrentPinyin()
                action()
            }
        } else if (mode == KeyboardMode.ENGLISH && englishBuffer.isNotEmpty()) {
            commitEnglishComposition(false)
            action()
        } else action()
    }

    override fun insertAt() = typeEnglish("@")

    override fun switchInputMethod() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
    }

    override fun toggleSymbols() {
        val toggle = {
            clearPredictions()
            mode = if (mode == KeyboardMode.SYMBOLS) modeBeforeSymbols else {
                modeBeforeSymbols = if (mode == KeyboardMode.PINYIN) KeyboardMode.PINYIN else KeyboardMode.ENGLISH
                KeyboardMode.SYMBOLS
            }
            render()
        }
        if (mode == KeyboardMode.PINYIN && (pinyinRawBuffer.isNotBlank() || pendingPinyinMutations > 0)) {
            enqueueAfterPendingPinyin {
            commitCurrentPinyin()
            toggle()
            }
        } else toggle()
    }

    private fun render() {
        if (!::keyboard.isInitialized) return
        // Rime completion callbacks run on a dedicated worker thread. Choreographer
        // is thread-local and must only be obtained from the main looper.
        mainHandler.post {
            if (!::keyboard.isInitialized || renderPending) return@post
            renderPending = true
            Choreographer.getInstance().postFrameCallback(renderFrameCallback)
        }
    }

    private fun currentRecentClipboard(): String = recentClipboardContent.takeIf {
        recentClipboardPasteEnabled && !sensitiveField &&
            SystemClock.elapsedRealtime() - recentClipboardCapturedAtMs <= RECENT_CLIPBOARD_WINDOW_MS
            && SystemClock.elapsedRealtime() <= recentClipboardVisibleUntilMs
    }.orEmpty()

    private fun shouldAutoCapitalizeEnglish(): Boolean {
        if (!englishAutoCapitalize || mode != KeyboardMode.ENGLISH || englishBuffer.isNotBlank()) return false
        val preceding = currentInputConnection?.getTextBeforeCursor(32, 0)?.toString()?.trimEnd().orEmpty()
        return preceding.isBlank() || preceding.lastOrNull() in AUTO_CAPITALIZE_AFTER
    }

    private fun insertTextSpace(pinyin: Boolean) {
        val connection = currentInputConnection ?: return
        val now = SystemClock.elapsedRealtime()
        val shouldInsertPeriod = doubleSpacePeriod && connection === lastTextSpaceConnection &&
            now - lastTextSpaceAtMs <= DOUBLE_SPACE_PERIOD_WINDOW_MS
        if (shouldInsertPeriod) {
            connection.deleteSurroundingText(1, 0)
            connection.commitText(if (pinyin) "。" else ". ", 1)
            resetDoubleSpacePeriod()
        } else {
            connection.commitText(" ", 1)
            lastTextSpaceAtMs = now
            lastTextSpaceConnection = connection
        }
        render()
    }

    private fun markTextSpace() {
        lastTextSpaceAtMs = SystemClock.elapsedRealtime()
        lastTextSpaceConnection = currentInputConnection
    }

    private fun resetDoubleSpacePeriod() {
        lastTextSpaceAtMs = 0L
        lastTextSpaceConnection = null
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

    private fun applyConfiguredStartupMode() {
        val requested = when (keyboardStartupMode) {
            KeyboardStartupMode.LAST_USED -> retainedKeyboardMode ?: loadRetainedKeyboardMode()
            KeyboardStartupMode.VOICE -> KeyboardMode.VOICE
            KeyboardStartupMode.PINYIN -> KeyboardMode.PINYIN
            KeyboardStartupMode.ENGLISH -> KeyboardMode.ENGLISH
            KeyboardStartupMode.ASK -> KeyboardMode.ASK
            KeyboardStartupMode.CLIPBOARD -> KeyboardMode.CLIPBOARD
        }
        if (requested != null && isConfiguredMode(requested)) mode = requested
    }

    private fun capturePrimaryClipboard() {
        val content = runCatching {
            clipboardManager.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
                ?.trim()
                .orEmpty()
        }.getOrDefault("")
        if (content.isBlank() || isSensitiveClipboard(content)) return
        if (recentClipboardPasteEnabled) {
            recentClipboardContent = content
            recentClipboardCapturedAtMs = SystemClock.elapsedRealtime()
            if (inputViewActive) presentRecentClipboardForInputView()
            mainHandler.postDelayed({
                if (SystemClock.elapsedRealtime() - recentClipboardCapturedAtMs >= RECENT_CLIPBOARD_WINDOW_MS) {
                    clearRecentClipboard()
                    render()
                }
            }, RECENT_CLIPBOARD_WINDOW_MS)
            render()
        }
        if (clipboardHistoryEnabled && content != lastClipboardContent) {
            lastClipboardContent = content
            serviceScope.launch(Dispatchers.IO) {
                container.clipboard.record(content)
            }
        }
    }

    private fun clearRecentClipboard() {
        recentClipboardContent = ""
        recentClipboardCapturedAtMs = 0L
        recentClipboardVisibleUntilMs = 0L
    }

    private fun presentRecentClipboardForInputView() {
        if (!recentClipboardPasteEnabled || recentClipboardContent.isBlank() ||
            SystemClock.elapsedRealtime() - recentClipboardCapturedAtMs > RECENT_CLIPBOARD_WINDOW_MS) {
            clearRecentClipboard()
            return
        }
        val expiresAt = SystemClock.elapsedRealtime() + QUICK_PASTE_VISIBLE_WINDOW_MS
        recentClipboardVisibleUntilMs = expiresAt
        mainHandler.postDelayed({
            if (recentClipboardVisibleUntilMs == expiresAt && SystemClock.elapsedRealtime() >= expiresAt) {
                clearRecentClipboard()
                render()
            }
        }, QUICK_PASTE_VISIBLE_WINDOW_MS)
        render()
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
        if (allowCommit) state.committedText?.let(::commitTextAndPredict)
        // A live composition owns the candidate bar. Invalidate async
        // predictions before they can overwrite Rime's remaining candidates.
        if (state.rawComposition.isNotBlank()) clearPredictions()
        pinyinBuffer = displayPinyinComposition(state)
        pinyinRawBuffer = state.rawComposition
        pinyinCandidates = state.candidates
        updateComposingText(pinyinBuffer)
        render()
    }

    /**
     * Rime's T9 schema receives numeric key codes. Keep that exact code for
     * decoding and deletion, but never expose it as composing text to apps.
     */
    private fun displayPinyinComposition(state: PinyinSessionState): String {
        if (chineseKeyboardLayout != ChineseKeyboardLayout.NINE_KEY) return state.preedit
        // The T9 schema may retain separators in context.input while the user
        // enters successive syllables. Decode against its digit sequence rather
        // than requiring the raw context to contain digits only.
        val t9Code = state.rawComposition.filter(Char::isDigit)
        if (t9Code.isBlank()) return state.preedit
        // The T9 schema receives digits internally. Pick a candidate whose
        // reading maps back to those exact digits before exposing a composing
        // string. This prevents raw codes such as "64 426" from leaking into
        // the target editor while still showing the complete "nihao" reading.
        return state.candidates.asSequence()
            .map { candidate -> pinyinDecoder.pinyinForDisplay(candidate.text).replace(" ", "") }
            .firstOrNull { reading -> reading.isNotBlank() && t9CodeFor(reading) == t9Code }
            ?: state.preedit.takeUnless { it.any(Char::isDigit) }.orEmpty()
    }

    private fun t9CodeFor(pinyin: String): String = buildString(pinyin.length) {
        pinyin.lowercase().forEach { char ->
            append(
                when (char) {
                    in 'a'..'c' -> '2'
                    in 'd'..'f' -> '3'
                    in 'g'..'i' -> '4'
                    in 'j'..'l' -> '5'
                    in 'm'..'o' -> '6'
                    in 'p'..'s' -> '7'
                    in 't'..'v' -> '8'
                    in 'w'..'z' -> '9'
                    else -> return@forEach
                }
            )
        }
    }

    private suspend fun commitCurrentPinyin() {
        val current = pinyinDecoder.currentState()
        val candidate = current.candidates.firstOrNull() ?: return
        if (candidate.directCommit) commitDirectPinyin(candidate.text)
        else {
            val state = pinyinDecoder.selectCandidate(candidate.index)
            commitNativePinyin(state, state.committedText)
        }
    }

    private suspend fun commitRawPinyin() {
        val state = pinyinDecoder.currentState()
        val raw = state.rawComposition
        val committed = if (raw.all(Char::isDigit)) {
            state.candidates.firstOrNull()?.text.orEmpty()
        } else state.preedit.ifBlank { pinyinBuffer }
        pinyinDecoder.clear()
        pinyinBuffer = ""
        pinyinRawBuffer = ""
        pinyinCandidates = emptyList()
        updateComposingText("")
        if (committed.isNotBlank()) commitTextAndPredict(committed)
        render()
    }

    private suspend fun commitDirectPinyin(text: String, targetConnection: InputConnection? = currentInputConnection) {
        if (!sensitiveField && pinyinDecoder.learnCandidate(text)) {
            serviceScope.launch(Dispatchers.IO) {
                container.pinyinLearning.record(text)
                lastPinyinLearning = container.pinyinLearning.all()
            }
        }
        pinyinDecoder.clear()
        pinyinBuffer = ""
        pinyinRawBuffer = ""
        pinyinCandidates = emptyList()
        commitPinyinCandidateAndPredict(text, targetConnection)
        render()
    }

    private suspend fun commitNativePinyin(
        state: PinyinSessionState,
        text: String?,
        targetConnection: InputConnection? = currentInputConnection
    ) {
        if (text.isNullOrBlank()) {
            // Partial selection: Rime keeps the whole key sequence in
            // context.input, so rawComposition alone cannot tell us whether text
            // was committed. Preserve the decoder and render its actual preedit.
            applyPinyinState(state, allowCommit = false)
        } else {
            commitPinyinCandidate(text, targetConnection, schedulePrediction = true)
            pinyinDecoder.clear()
            pinyinBuffer = ""
            pinyinRawBuffer = ""
            pinyinCandidates = emptyList()
            render()
        }
    }

    /**
     * Candidate selection must not clear composing text before the selected text
     * reaches the editor. Several editors confirm that transient empty composing
     * state on their next Enter event, which caused a missing or duplicate pick.
     */
    private fun commitPinyinCandidate(
        text: String,
        targetConnection: InputConnection?,
        schedulePrediction: Boolean
    ) {
        if (text.isBlank()) return
        val connection = targetConnection ?: return
        if (connection !== currentInputConnection) return
        val batchStarted = connection.beginBatchEdit()
        try {
            if (connection.setComposingText(text, 1)) {
                connection.finishComposingText()
            } else {
                connection.commitText(text, 1)
            }
        } finally {
            if (batchStarted) connection.endBatchEdit()
        }
        if (schedulePrediction) schedulePredictions(text, connection)
    }

    private fun commitPinyinCandidateAndPredict(text: String, targetConnection: InputConnection?) =
        commitPinyinCandidate(text, targetConnection, schedulePrediction = true)

    private fun commitTextAndPredict(text: String, appendSpace: Boolean = false) {
        if (text.isBlank()) return
        val connection = currentInputConnection ?: return
        connection.commitText(text, 1)
        if (appendSpace) connection.commitText(" ", 1)
        schedulePredictions(text, connection)
    }

    private fun schedulePredictions(text: String, connection: InputConnection) {
        if (!predictionEnabled || sensitiveField) {
            clearPredictions()
            return
        }
        val requestId = ++predictionRequestId
        predictionCandidates = emptyList()
        render()
        predictionOperations.trySend {
            ensureJiebaReady()
            val appended = predictionEngine.tokenize(text)
            if (appended.isEmpty()) return@trySend
            val previous = predictionContextTokens
            val combined = (previous + appended).takeLast(PREDICTION_CONTEXT_LIMIT)
            if (predictionLearningEnabled) {
                val transitions = predictionEngine.transitionsForAppend(previous, appended)
                withContext(Dispatchers.IO) {
                    transitions.forEach { (context, target) -> container.predictionLearning.record(context, target) }
                }
            }
            predictionContextTokens = combined
            val contexts = predictionEngine.contextsFor(combined)
            val learned = if (predictionLearningEnabled && contexts.isNotEmpty()) {
                withContext(Dispatchers.IO) { container.predictionLearning.forContexts(contexts) }
            } else emptyList()
            val predictions = predictionEngine.suggestionsForTokens(
                combined,
                learned,
                DictionaryPackManager(this@WeikeInputMethodService).hasEnhancedDictionary()
            )
            if (requestId != predictionRequestId || connection !== currentInputConnection || sensitiveField ||
                pinyinRawBuffer.isNotBlank() || englishBuffer.isNotBlank()
            ) return@trySend
            predictionCandidates = predictions
            render()
        }
    }

    private fun clearPredictions(clearContext: Boolean = false) {
        predictionRequestId += 1
        predictionCandidates = emptyList()
        if (clearContext) predictionContextTokens = emptyList()
        render()
    }

    private fun enqueuePinyin(block: suspend () -> Unit) {
        pinyinOperations.trySend(block)
    }

    private fun flushPendingPinyinBatch() {
        if (!pinyinBatchScheduled && pendingPinyinLetters.isEmpty()) return
        pinyinBatchScheduled = false
        val batch = pendingPinyinLetters.toString()
        pendingPinyinLetters.clear()
        if (batch.isBlank()) return
        enqueuePinyin {
            try {
                applyPinyinState(pinyinDecoder.inputBatch(batch), allowCommit = false)
            } finally {
                pendingPinyinMutations = (pendingPinyinMutations - batch.length).coerceAtLeast(0)
            }
        }
    }

    private fun enqueueAfterPendingPinyin(action: suspend () -> Unit) {
        flushPendingPinyinBatch()
        enqueuePinyin(action)
    }

    private fun clearTextCompositions() {
        pinyinBuffer = ""
        pinyinRawBuffer = ""
        pinyinCandidates = emptyList()
        englishBuffer = ""
        englishCandidates = emptyList()
        clearPredictions(clearContext = true)
        enqueuePinyin { pinyinDecoder.clear() }
    }

    private fun captureInputViewRecovery() {
        if (sensitiveField) return
        val recovery = InputViewRecovery(
            packageName = lastPackageName.orEmpty(),
            inputType = currentInputEditorInfo?.inputType ?: 0,
            fieldId = currentInputEditorInfo?.fieldId ?: 0,
            mode = mode,
            modeBeforeSymbols = modeBeforeSymbols,
            pinyinCode = pinyinRawBuffer,
            englishCode = englishBuffer,
            expiresAtMs = SystemClock.elapsedRealtime() + INPUT_VIEW_RECOVERY_WINDOW_MS
        )
        inputViewRecovery = recovery
        Log.d(TAG, "Captured IME recovery; mode=${recovery.mode}; pinyin=${recovery.pinyinCode.length}; field=${recovery.fieldId}")
        serviceScope.launch {
            delay(INPUT_VIEW_RECOVERY_WINDOW_MS)
            if (inputViewRecovery === recovery) inputViewRecovery = null
        }
    }

    private fun restoreInputViewRecovery(recovery: InputViewRecovery) {
        Log.d(TAG, "Restoring IME recovery; mode=${recovery.mode}; pinyin=${recovery.pinyinCode.length}; field=${recovery.fieldId}")
        mode = recovery.mode
        modeBeforeSymbols = recovery.modeBeforeSymbols
        rememberKeyboardMode(mode)
        pinyinBuffer = ""
        pinyinRawBuffer = ""
        pinyinCandidates = emptyList()
        englishBuffer = recovery.englishCode
        englishCandidates = EnglishCandidateEngine.candidates(englishBuffer)
        if (recovery.pinyinCode.isNotBlank() && mode == KeyboardMode.PINYIN) {
            requestPinyinEngine()
            enqueuePinyin {
                applyPinyinState(pinyinDecoder.restoreComposition(recovery.pinyinCode), allowCommit = false)
            }
        } else {
            updateComposingText(englishBuffer)
            render()
        }
    }

    /**
     * Xiaomi can occasionally recreate just the IME service while rotating. Keep
     * the selected surface, but never text, audio, candidates, or cloud settings.
     */
    private fun rememberKeyboardMode(value: KeyboardMode) {
        val retained = if (value == KeyboardMode.SYMBOLS) modeBeforeSymbols else value
        retainedKeyboardMode = retained
        getSharedPreferences(IME_STATE_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(KEY_RETAINED_KEYBOARD_MODE, retained.name)
            .apply()
    }

    private fun loadRetainedKeyboardMode(): KeyboardMode? = runCatching {
        getSharedPreferences(IME_STATE_PREFERENCES, MODE_PRIVATE)
            .getString(KEY_RETAINED_KEYBOARD_MODE, null)
            ?.let(KeyboardMode::valueOf)
    }.getOrNull()

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
        serviceScope.launch {
            val cleared = runCatching { pinyinMutex.withLock { pinyinDecoder.clearUserLearning() } }
                .onFailure { Log.e(TAG, "Unable to clear local Pinyin learning data", it) }
                .getOrDefault(false)
            if (cleared) {
                lastPinyinLearning = emptyList()
                serviceScope.launch(Dispatchers.IO) { container.pinyinLearning.deleteAll() }
            }
            render()
            toast(if (cleared) "已清除本机候选学习数据" else "清除学习数据失败")
        }
    }

    private fun clearPredictionLearning() {
        serviceScope.launch(Dispatchers.IO) {
            container.predictionLearning.deleteAll()
        }
        clearPredictions(clearContext = true)
        toast("已清除本机联想学习数据")
    }

    private fun reloadPinyinBundle() {
        serviceScope.launch {
            engineLoadJob?.cancel()
            pinyinMutex.withLock {
                pinyinDecoder.shutdown()
                pinyinReady = false
                pinyinBuffer = ""
                pinyinRawBuffer = ""
                pinyinCandidates = emptyList()
                updateComposingText("")
            }
            requestPinyinEngine()
            render()
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
        private const val TRANSLATION_TIMEOUT_MS = 8_000L
        private const val DEEP_POLISHING_TIMEOUT_MS = 8_000L
        private const val ANSWER_PROCESSING_TIMEOUT_MS = 18_000L
        private const val MAX_WORKFLOW_CONTEXT_CHARS = 8_192
        private const val MAX_DELETE_CONTEXT_CHARS = 64
        private const val PROCESSING_LABEL_FADE_MS = 150L
        private const val TYPEWRITER_CHARACTER_DELAY_MS = 16L
        private const val LANGUAGE_ENGINE_IDLE_TIMEOUT_MS = 5L * 60L * 1000L
        private const val DEFERRED_TERM_SYNC_DELAY_MS = 1_000L
        private const val MAX_PCM_BYTES = AudioRecorder.MAX_RECORDING_SECONDS * AudioRecorder.SAMPLE_RATE * 2
        private val INPUT_UNIT_PATTERN = Regex("[\\u4E00-\\u9FFF]|[A-Za-z]+(?:['-][A-Za-z]+)*|\\d+(?:[.,]\\d+)?")
        private const val MAX_CLIPBOARD_CONTENT_LENGTH = 4_096
        private const val PREDICTION_CONTEXT_LIMIT = 16
        private const val INPUT_VIEW_RECOVERY_WINDOW_MS = 5_000L
        private const val DOUBLE_SPACE_PERIOD_WINDOW_MS = 350L
        private const val RECENT_CLIPBOARD_WINDOW_MS = 15_000L
        private const val QUICK_PASTE_VISIBLE_WINDOW_MS = 5_000L
        private val AUTO_CAPITALIZE_AFTER = setOf('.', '!', '?', '。', '！', '？', '\n')
        private const val LANDSCAPE_OVERLAY_HEIGHT_DP = 258
        private const val IME_STATE_PREFERENCES = "ime_view_state"
        private const val KEY_RETAINED_KEYBOARD_MODE = "retained_keyboard_mode"
        private val SENSITIVE_CLIPBOARD_MARKERS = Regex(
            "(?i)(password|passwd|passcode|验证码|动态码|verification\\s*code|one[- ]?time\\s*password|otp)"
        )
        const val ACTION_CLEAR_RIME_LEARNING = "com.weike.ime.action.CLEAR_RIME_LEARNING"
        const val ACTION_CLEAR_PREDICTION_LEARNING = "com.weike.ime.action.CLEAR_PREDICTION_LEARNING"
        const val ACTION_RELOAD_RIME_BUNDLE = "com.weike.ime.action.RELOAD_RIME_BUNDLE"
    }

    /** Kept only in memory and only long enough to bridge an IME view recreation. */
    private data class InputViewRecovery(
        val packageName: String,
        val inputType: Int,
        val fieldId: Int,
        val mode: KeyboardMode,
        val modeBeforeSymbols: KeyboardMode,
        val pinyinCode: String,
        val englishCode: String,
        val expiresAtMs: Long
    ) {
        fun matches(info: EditorInfo?, sensitive: Boolean): Boolean =
            !sensitive &&
                packageName == info?.packageName.orEmpty() &&
                // HyperOS creates a short-lived placeholder connection during
                // rotation (field -1 / input type 0), then immediately replaces
                // it with the actual editor (typically field 0). Treat unknown
                // values as wildcards, while still rejecting another package.
                (inputType == 0 || info?.inputType == 0 || inputType == info?.inputType) &&
                (fieldId <= 0 || (info?.fieldId ?: 0) <= 0 || fieldId == info?.fieldId)
    }
}
