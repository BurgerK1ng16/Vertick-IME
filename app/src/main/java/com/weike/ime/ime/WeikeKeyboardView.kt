package com.weike.ime.ime

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.animation.ValueAnimator
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.OverScroller
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import com.weike.ime.R
import com.weike.ime.data.ClipboardEntry
import com.weike.ime.data.HapticStrength
import com.weike.ime.data.KeyboardTheme
import com.weike.ime.data.VoiceUiState
import com.weike.ime.data.WritingStyle

enum class KeyboardMode { VOICE, ASK, TEXT, ENGLISH, PINYIN, CLIPBOARD, SYMBOLS }

interface KeyboardActions {
    fun selectMode(mode: KeyboardMode)
    fun toggleVoice()
    fun startPolishedVoice()
    fun setLongPressTranslation(selected: Boolean)
    fun finishVoice()
    fun cancelVoice()
    fun dismissAnswer()
    fun pasteClipboard(entry: ClipboardEntry)
    fun deleteClipboard(entry: ClipboardEntry)
    fun undoLastInsert()
    fun typeEnglish(value: String)
    fun typeEnglishLetter(value: String)
    fun typePinyin(value: String)
    fun chooseCandidate(candidate: PinyinCandidate)
    fun chooseEnglishCandidate(value: String)
    fun commitEnglishComposition(addSpace: Boolean)
    fun backspace()
    fun enter()
    fun newline()
    fun insertAt()
    fun toggleSymbols()
}

/** Custom fixed-layout IME surface based on the supplied phone reference. */
class WeikeKeyboardView(context: Context, private val actions: KeyboardActions) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val targets = mutableListOf<TouchTarget>()
    private val darkLogo = BitmapFactory.decodeResource(resources, R.drawable.vertick_white)
    private val lightLogo = BitmapFactory.decodeResource(resources, R.drawable.vertick_black)
    private val keySound = KeyboardSoundPlayer(context)

    private var mode = KeyboardMode.VOICE
    private var availableModes: List<KeyboardMode> = listOf(KeyboardMode.VOICE, KeyboardMode.TEXT)
    private var voiceState: VoiceUiState = VoiceUiState.Idle
    private var pinyinBuffer = ""
    private var pinyinCandidates: List<PinyinCandidate> = emptyList()
    private var englishBuffer = ""
    private var englishCandidates: List<PinyinCandidate> = emptyList()
    private var clipboardEntries: List<ClipboardEntry> = emptyList()
    private var pinyinReady = true
    private var pinyinStatus = ""
    private var pinyinNineKey = false
    private var symbolsUseEnglish = false
    private var nineKeySymbols: List<String> = emptyList()
    private var nineKeySymbolScrollY = 0f
    private var nineKeySymbolMaxScroll = 0f
    private var nineKeySymbolViewport: RectF? = null
    private var nineKeySymbolDragging = false
    private var nineKeySymbolDragStart = 0f
    private var candidateScrollX = 0f
    private var candidateMaxScroll = 0f
    private var candidateDragging = false
    private var candidateDragStartScroll = 0f
    private var candidateStrip: RectF? = null
    private val candidateScroller = OverScroller(context)
    private var candidateVelocityTracker: VelocityTracker? = null
    private var answerScrollY = 0f
    private var answerMaxScroll = 0f
    private var answerDragging = false
    private var answerDragStartScroll = 0f
    private var answerViewport: RectF? = null
    private var clipboardScrollY = 0f
    private var clipboardMaxScroll = 0f
    private var clipboardDragStartScroll = 0f
    private var clipboardViewport: RectF? = null
    private var clipboardTracking = false
    private var clipboardTouchedEntry: ClipboardEntry? = null
    private var clipboardRevealId: Long? = null
    private var clipboardSwipeOffset = 0f
    private var clipboardInitialReveal = false
    private var clipboardRows: List<ClipboardRow> = emptyList()
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var uppercase = false
    private var sensitive = false
    private var hapticStrength = HapticStrength.MEDIUM
    private var keyboardSoundVolume = .45f
    private var keyboardTheme = KeyboardTheme.DARK
    private var meter = 0f
    private var voiceMorph = 0f
    private var modeIndicator = 0f
    private var pressedBox: RectF? = null
    private var activeTarget: TouchTarget? = null
    private var activeTargetCancelled = false
    private val secondaryTouches = mutableMapOf<Int, TouchTarget>()
    private val cancelledSecondaryTouches = mutableSetOf<Int>()
    private var longPressTriggered = false
    private var longPressTranslationSelected = false
    private var longPressVoiceBox: RectF? = null
    private var holdOverlayProgress = 0f
    private var repeatAction: (() -> Unit)? = null
    private val longPressRunnable = Runnable {
        val target = activeTarget ?: return@Runnable
        val action = target.longPressAction ?: return@Runnable
        if (pressedBox != target.box || candidateDragging || answerDragging || clipboardTracking) return@Runnable
        longPressTriggered = true
        longPressTranslationSelected = false
        longPressVoiceBox = target.box
        animateHoldOverlay(show = true)
        emitHaptic(HapticFeedbackConstants.GESTURE_START)
        action.invoke()
        invalidate()
    }
    private val repeatBackspace = object : Runnable {
        override fun run() {
            repeatAction?.invoke() ?: return
            postDelayed(this, 58L)
        }
    }
    private var processingStartedAt = 0L
    private var polishingStreamActive = false
    private var polishingStreamStartedAt = 0L
    private var processingTextAlpha = 1f
    private var leavingProcessing = false
    private var completionProgress = 1f
    private var shakeOffset = 0f
    private val transition = ValueAnimator().apply {
        duration = 200L
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { voiceMorph = it.animatedValue as Float; invalidate() }
    }
    private val modeTransition = ValueAnimator().apply {
        duration = 200L
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            modeIndicator = it.animatedValue as Float
            invalidate()
        }
    }
    private val processingFade = ValueAnimator().apply {
        duration = 150L
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { processingTextAlpha = it.animatedValue as Float; invalidate() }
    }

    private val logo: android.graphics.Bitmap get() = if (keyboardTheme == KeyboardTheme.DARK) darkLogo else lightLogo
    private val background: Int get() = if (keyboardTheme == KeyboardTheme.DARK) Color.rgb(46, 46, 47) else Color.rgb(247, 247, 249)
    private val key: Int get() = if (keyboardTheme == KeyboardTheme.DARK) Color.rgb(79, 79, 81) else Color.rgb(227, 227, 232)
    private val tabBackground: Int get() = if (keyboardTheme == KeyboardTheme.DARK) Color.rgb(30, 30, 31) else Color.rgb(235, 235, 239)
    private val white: Int get() = if (keyboardTheme == KeyboardTheme.DARK) Color.rgb(246, 245, 243) else Color.rgb(28, 28, 30)
    private val muted: Int get() = if (keyboardTheme == KeyboardTheme.DARK) Color.rgb(190, 190, 192) else Color.rgb(105, 105, 112)
    private val actionIcon: Int get() = if (keyboardTheme == KeyboardTheme.DARK) Color.BLACK else Color.WHITE
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    init {
        isClickable = true
        contentDescription = "Vertick keyboard"
    }

    fun update(
        mode: KeyboardMode = this.mode,
        voiceState: VoiceUiState = this.voiceState,
        style: WritingStyle = WritingStyle.CHAT,
        pinyinBuffer: String = this.pinyinBuffer,
        pinyinCandidates: List<PinyinCandidate> = this.pinyinCandidates,
        englishBuffer: String = this.englishBuffer,
        englishCandidates: List<PinyinCandidate> = this.englishCandidates,
        liveTranscript: String = "",
        sensitive: Boolean = this.sensitive,
        hapticStrength: HapticStrength = this.hapticStrength,
        pinyinReady: Boolean = this.pinyinReady,
        pinyinStatus: String = this.pinyinStatus,
        keyboardTheme: KeyboardTheme = this.keyboardTheme,
        keyboardSoundVolume: Float = this.keyboardSoundVolume,
        modeOptions: List<KeyboardMode> = this.availableModes,
        clipboardEntries: List<ClipboardEntry> = this.clipboardEntries,
        pinyinNineKey: Boolean = this.pinyinNineKey,
        symbolsUseEnglish: Boolean = this.symbolsUseEnglish,
        nineKeySymbols: List<String> = this.nineKeySymbols
    ) {
        val normalizedModes = modeOptions.distinct().filter { it != KeyboardMode.SYMBOLS }
            .ifEmpty { listOf(KeyboardMode.PINYIN) }
        // Symbols is a temporary sub-page of the text keyboard, not a top-right mode.
        // Keep it intact instead of falling back to the first configured mode (usually voice).
        val hasTextMode = KeyboardMode.TEXT in normalizedModes
        val requestedMode = when {
            mode == KeyboardMode.SYMBOLS -> KeyboardMode.SYMBOLS
            mode in listOf(KeyboardMode.PINYIN, KeyboardMode.ENGLISH) && hasTextMode -> mode
            mode in normalizedModes -> mode
            else -> normalizedModes.first()
        }
        val targetMode = if (sensitive && (requestedMode == KeyboardMode.VOICE || requestedMode == KeyboardMode.ASK)) {
            if (hasTextMode) KeyboardMode.ENGLISH else normalizedModes.first()
        } else requestedMode
        val modeChanged = this.mode != targetMode
        val optionsChanged = this.availableModes != normalizedModes
        val targetIndicator = topModeFor(targetMode, normalizedModes).let(normalizedModes::indexOf).coerceAtLeast(0).toFloat()
        if (modeChanged) requestLayout()
        val previousState = this.voiceState
        val oldActive = isActive(previousState)
        this.mode = targetMode
        this.availableModes = normalizedModes
        this.voiceState = voiceState
        if (this.pinyinBuffer != pinyinBuffer || this.englishBuffer != englishBuffer) {
            candidateScrollX = 0f
            candidateMaxScroll = 0f
        }
        val previousPreview = previousState as? VoiceUiState.Preview
        val nextPreview = voiceState as? VoiceUiState.Preview
        if ((previousPreview == null && nextPreview != null) ||
            (previousPreview != null && nextPreview != null && previousPreview.question != nextPreview.question)
        ) {
            answerScrollY = 0f
            answerMaxScroll = 0f
        }
        this.pinyinBuffer = pinyinBuffer
        this.pinyinCandidates = pinyinCandidates
        this.englishBuffer = englishBuffer
        this.englishCandidates = englishCandidates
        if (this.clipboardEntries != clipboardEntries) {
            clipboardScrollY = 0f
            clipboardRevealId = clipboardRevealId?.takeIf { id -> clipboardEntries.any { it.id == id } }
        }
        this.clipboardEntries = clipboardEntries
        this.pinyinReady = pinyinReady
        this.pinyinStatus = pinyinStatus
        this.pinyinNineKey = pinyinNineKey
        this.symbolsUseEnglish = symbolsUseEnglish
        this.nineKeySymbols = nineKeySymbols
        this.keyboardTheme = keyboardTheme
        this.sensitive = sensitive
        this.hapticStrength = hapticStrength
        this.keyboardSoundVolume = keyboardSoundVolume
        if (voiceState == VoiceUiState.Processing && previousState != VoiceUiState.Processing) {
            processingStartedAt = System.currentTimeMillis()
            polishingStreamActive = false
            polishingStreamStartedAt = processingStartedAt
            processingTextAlpha = 1f
            leavingProcessing = false
        } else if (previousState == VoiceUiState.Processing && voiceState != VoiceUiState.Processing) {
            leavingProcessing = true
            completionProgress = 0f
            processingFade.cancel()
            processingFade.setFloatValues(1f, 0f)
            processingFade.start()
            postDelayed({
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 200L
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener {
                        completionProgress = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }, 150L)
            postDelayed({ leavingProcessing = false; completionProgress = 1f; invalidate() }, 350L)
        }
        val newActive = isActive(voiceState)
        if (oldActive != newActive) {
            transition.cancel()
            transition.setFloatValues(voiceMorph, if (newActive) 1f else 0f)
            transition.start()
        } else invalidate()
        if (modeChanged || optionsChanged) {
            emitHaptic(HapticFeedbackConstants.CONTEXT_CLICK)
            modeTransition.cancel()
            modeTransition.setFloatValues(modeIndicator, targetIndicator)
            modeTransition.start()
        }
        if (previousState != voiceState) {
            val feedback = when {
                voiceState == VoiceUiState.Listening -> HapticFeedbackConstants.GESTURE_START
                voiceState == VoiceUiState.Processing -> HapticFeedbackConstants.GESTURE_END
                previousState !is VoiceUiState.Preview && voiceState is VoiceUiState.Preview -> HapticFeedbackConstants.CONFIRM
                voiceState is VoiceUiState.Error -> HapticFeedbackConstants.REJECT
                voiceState == VoiceUiState.Idle && previousState == VoiceUiState.Processing -> HapticFeedbackConstants.CONFIRM
                else -> null
            }
            feedback?.let(::emitHaptic)
        }
    }

    fun setAudioLevel(level: Float) {
        val visualLevel = (kotlin.math.ln(1.0 + level.coerceIn(0f, 1f) * 120.0) / kotlin.math.ln(121.0)).toFloat()
        meter = meter * .45f + visualLevel * .55f
        postInvalidateOnAnimation()
    }

    fun setPolishingStreamActive(active: Boolean) {
        polishingStreamActive = active
        if (active) polishingStreamStartedAt = System.currentTimeMillis()
        postInvalidateOnAnimation()
    }

    fun notePolishingDelta() {
        postInvalidateOnAnimation()
    }

    fun playTimeout() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val phase = it.animatedValue as Float
                shakeOffset = kotlin.math.sin(phase * Math.PI * 6.0).toFloat() * dp(7) * (1f - phase)
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = if (isLandscape()) dp(238).toInt() else dp(330).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        // Keep the IME surface square at the bottom while exposing rounded top corners.
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        paint.style = Paint.Style.FILL
        paint.color = background
        val corner = dp(24)
        val surface = Path().apply {
            moveTo(0f, corner)
            quadTo(0f, 0f, corner, 0f)
            lineTo(width - corner, 0f)
            quadTo(width.toFloat(), 0f, width.toFloat(), corner)
            lineTo(width.toFloat(), height.toFloat())
            lineTo(0f, height.toFloat())
            close()
        }
        canvas.drawPath(surface, paint)
        targets.clear()
        candidateStrip = null
        answerViewport = null
        clipboardViewport = null
        nineKeySymbolViewport = null
        clipboardRows = emptyList()
        if (hasComposition()) {
            val pinyin = mode == KeyboardMode.PINYIN
            val candidateItems = if (pinyin) {
                pinyinCandidates
            } else {
                englishCandidates
            }
            candidates(canvas, candidateItems, pinyin)
        } else {
            drawHeader(canvas)
            if (mode == KeyboardMode.PINYIN && !pinyinReady) {
                label(canvas, pinyinStatus.ifBlank { "词典准备中" }, width / 2f, dp(59), 12f, muted, Paint.Align.CENTER, true)
            }
        }
        when (mode) {
            KeyboardMode.VOICE, KeyboardMode.ASK -> drawVoice(canvas)
            KeyboardMode.CLIPBOARD -> drawClipboard(canvas)
            KeyboardMode.SYMBOLS -> drawSymbols(canvas)
            KeyboardMode.TEXT -> drawKeyboard(canvas, true)
            else -> drawKeyboard(canvas, mode == KeyboardMode.PINYIN)
        }
    }

    private fun drawHeader(canvas: Canvas) {
        val showingAnswer = mode == KeyboardMode.ASK && voiceState is VoiceUiState.Preview
        if (showingAnswer) {
            val back = RectF(dp(8), dp(10), dp(46), dp(48))
            lucide(canvas, R.drawable.ic_lucide_chevron_left, back.centerX(), back.centerY(), dp(24), white)
            target(back, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) { actions.dismissAnswer() }
        } else {
            val logoHeight = dp(26)
            val logoWidth = logoHeight * logo.width / logo.height
            val logoTop = dp(15)
            canvas.drawBitmap(logo, null, RectF(dp(13), logoTop, dp(13) + logoWidth, logoTop + logoHeight), paint)
        }

        if (voiceState == VoiceUiState.Listening) {
            val close = RectF(width - dp(46), dp(10), width - dp(8), dp(48))
            rounded(canvas, close, dp(19), tabBackground)
            lucide(canvas, R.drawable.ic_lucide_x, close.centerX(), close.centerY(), dp(17), muted)
            target(close, hapticFeedback = HapticFeedbackConstants.GESTURE_END) { actions.cancelVoice() }
            return
        }

        val tabWidth = dp(52) * availableModes.size
        val tabs = RectF(width - dp(8) - tabWidth, dp(10), width - dp(8), dp(48))
        rounded(canvas, tabs, dp(20), tabBackground)
        val cell = tabs.width() / availableModes.size
        val selected = availableModes.indexOf(topModeFor(mode, availableModes))
        if (selected >= 0) {
            val sliderLeft = tabs.left + cell * modeIndicator + dp(3)
            rounded(canvas, RectF(sliderLeft, tabs.top + dp(3), sliderLeft + cell - dp(6), tabs.bottom - dp(3)), dp(17), key)
        }
        availableModes.forEachIndexed { index, option ->
            val centerX = tabs.left + cell * (index + .5f)
            val selectedColor = if (index == selected) white else muted
            when (option) {
                KeyboardMode.VOICE -> lucide(canvas, R.drawable.ic_lucide_audio_lines, centerX, tabs.centerY(), dp(20), selectedColor)
                KeyboardMode.ASK -> lucide(canvas, R.drawable.ic_lucide_message_circle_more, centerX, tabs.centerY(), dp(19), selectedColor)
                KeyboardMode.TEXT -> label(
                    canvas,
                    if (mode == KeyboardMode.ENGLISH) "EN" else "\u62fc",
                    centerX,
                    tabs.centerY() + dp(6),
                    if (mode == KeyboardMode.ENGLISH) 15f else 16f,
                    selectedColor,
                    Paint.Align.CENTER,
                    mode != KeyboardMode.ENGLISH
                )
                KeyboardMode.PINYIN -> label(canvas, "\u62fc", centerX, tabs.centerY() + dp(6), 16f, selectedColor, Paint.Align.CENTER, true)
                KeyboardMode.ENGLISH -> label(canvas, "EN", centerX, tabs.centerY() + dp(6), 15f, selectedColor, Paint.Align.CENTER)
                KeyboardMode.CLIPBOARD -> lucide(canvas, R.drawable.ic_lucide_clipboard, centerX, tabs.centerY(), dp(19), selectedColor)
                KeyboardMode.SYMBOLS -> Unit
            }
            target(
                RectF(tabs.left + cell * index, tabs.top, tabs.left + cell * (index + 1), tabs.bottom),
                !sensitive || (option != KeyboardMode.VOICE && option != KeyboardMode.ASK),
                hapticFeedback = null
            ) { actions.selectMode(option) }
        }
    }

    private fun drawClipboard(canvas: Canvas) {
        val left = dp(10)
        val top = dp(65)
        val bottom = height.toFloat() - dp(16)
        val viewport = RectF(left, top, width - dp(10), bottom)
        clipboardViewport = RectF(0f, top - dp(6), width.toFloat(), bottom + dp(6))
        if (clipboardEntries.isEmpty()) {
            label(canvas, "剪贴板暂无内容", width / 2f, top + dp(34), 14f, muted, Paint.Align.CENTER)
            return
        }
        val rowHeight = dp(54)
        val gap = dp(8)
        val contentHeight = clipboardEntries.size * (rowHeight + gap) - gap
        clipboardMaxScroll = (contentHeight - viewport.height()).coerceAtLeast(0f)
        clipboardScrollY = clipboardScrollY.coerceIn(0f, clipboardMaxScroll)
        val deleteWidth = dp(68)
        val rows = ArrayList<ClipboardRow>(clipboardEntries.size)
        canvas.save()
        canvas.clipRect(viewport)
        var y = viewport.top - clipboardScrollY
        clipboardEntries.forEach { entry ->
            val base = RectF(viewport.left, y, viewport.right, y + rowHeight)
            rows += ClipboardRow(entry, base)
            if (base.bottom >= viewport.top - rowHeight && base.top <= viewport.bottom + rowHeight) {
                val revealed = clipboardRevealId == entry.id
                val swiping = clipboardTracking && clipboardTouchedEntry?.id == entry.id && clipboardSwipeOffset > 0f
                if (revealed || swiping) {
                    rounded(canvas, RectF(base.right - deleteWidth, base.top, base.right, base.bottom), dp(9), Color.rgb(207, 64, 69))
                    label(canvas, "删除", base.right - deleteWidth / 2, base.centerY() + dp(5), 14f, Color.WHITE, Paint.Align.CENTER, true)
                }
                val offset = when {
                    clipboardTracking && clipboardTouchedEntry?.id == entry.id -> -clipboardSwipeOffset
                    revealed -> -deleteWidth
                    else -> 0f
                }
                val contentBox = RectF(base).apply { offset(offset, 0f) }
                rounded(canvas, contentBox, dp(9), key)
                val content = entry.content.replace(Regex("\\s+"), " ").trim()
                leftClippedLabel(canvas, content, contentBox, 16f, white)
            }
            y += rowHeight + gap
        }
        canvas.restore()
        clipboardRows = rows
    }

    private fun drawVoice(canvas: Canvas) {
        val state = voiceState
        val answer = (state as? VoiceUiState.Preview)?.takeIf { mode == KeyboardMode.ASK }
        if (answer != null) {
            drawAnswer(canvas, answer.question, answer.text, answer.streaming)
            return
        }
        val cx = width / 2f
        val cy = if (isLandscape()) height * .48f else dp(174)
        val morph = voiceMorph
        val sizeScale = if (isLandscape()) .78f else 1f
        val buttonWidth = dp((188f * sizeScale).toInt()) - dp((56f * sizeScale).toInt()) * morph
        val buttonHeight = dp((64f * sizeScale).toInt()) + dp((68f * sizeScale).toInt()) * morph
        val buttonX = cx + shakeOffset
        val button = RectF(buttonX - buttonWidth / 2, cy - buttonHeight / 2, buttonX + buttonWidth / 2, cy + buttonHeight / 2)
        val listening = state == VoiceUiState.Listening
        val processing = state == VoiceUiState.Processing
        if (processing) {
            rounded(canvas, button, buttonHeight / 2, Color.rgb(120, 120, 123))
            val elapsed = (System.currentTimeMillis() - processingStartedAt).coerceAtLeast(0L)
            streamProgress(canvas, button)
            val pulse = .8f + .2f * ((kotlin.math.sin((elapsed % 1500L) / 1500f * Math.PI * 2.0) + 1.0) / 2.0).toFloat()
            label(
                canvas,
                if (mode == KeyboardMode.ASK) "\u7ef4\u523b\u77e5\u9053" else "\u6da6\u8272\u4e2d",
                buttonX,
                cy + dp(6),
                16f,
                Color.rgb(58, 58, 60),
                Paint.Align.CENTER,
                true,
                pulse
            )
            postInvalidateOnAnimation()
        } else {
            val fill = if (leavingProcessing) blend(Color.rgb(120, 120, 123), white, completionProgress) else white
            rounded(canvas, button, buttonHeight / 2, fill)
            val micAlpha = if (leavingProcessing) completionProgress else 1f - morph
            if (micAlpha > .01f) lucide(canvas, R.drawable.ic_lucide_mic, buttonX, cy, dp(30), actionIcon, micAlpha)
            if (listening && morph > .01f) {
                voiceBars(canvas, buttonX, cy, morph)
            }
            if (leavingProcessing) {
                label(
                    canvas,
                    if (mode == KeyboardMode.ASK) "\u7ef4\u523b\u77e5\u9053" else "\u6da6\u8272\u4e2d",
                    buttonX,
                    cy + dp(6),
                    16f,
                    Color.rgb(58, 58, 60),
                    Paint.Align.CENTER,
                    true,
                    processingTextAlpha
                )
            }
        }
        val prompt = when {
            listening -> "松开以完成"
            processing -> ""
            state is VoiceUiState.Error -> state.message
            else -> "按下开始说话"
        }
        val resolvedPrompt = if (mode == KeyboardMode.ASK && !listening && !processing && state !is VoiceUiState.Error) {
            "\u6309\u4e0b\u5f00\u59cb\u63d0\u95ee"
        } else prompt
        if (resolvedPrompt.isNotEmpty()) label(canvas, resolvedPrompt, buttonX, cy - buttonHeight / 2 - dp(20), 14f, muted, Paint.Align.CENTER, true)
        if (!processing && !leavingProcessing) {
            if (mode == KeyboardMode.VOICE) {
                target(
                    button,
                    hapticFeedback = null,
                    longPressAction = { actions.startPolishedVoice() },
                    releaseAction = { actions.finishVoice() }
                ) { actions.toggleVoice() }
            } else {
                target(button, hapticFeedback = null) { actions.toggleVoice() }
            }
        }
        if (!listening && mode != KeyboardMode.ASK) {
            val bottom = height.toFloat() - if (isLandscape()) dp(14) else dp(32)
            val newline = RectF(cx - dp(60), bottom - dp(48), cx + dp(60), bottom)
            rounded(canvas, newline, dp(24), key)
            label(canvas, "\u53d1\u9001", newline.centerX(), newline.centerY() + dp(5), 14f, muted, Paint.Align.CENTER)
            target(newline, longPressAction = { actions.newline() }) { actions.enter() }
            val sideX = width - dp(43)
            val atY = bottom - dp(24)
            val deleteY = atY - dp(55)
            circle(canvas, sideX, deleteY, dp(24), key)
            lucide(canvas, R.drawable.ic_lucide_delete, sideX, deleteY, dp(22), muted)
            target(RectF(sideX - dp(25), deleteY - dp(25), sideX + dp(25), deleteY + dp(25)), repeat = true) { actions.backspace() }
            circle(canvas, sideX, atY, dp(24), key)
            lucide(canvas, R.drawable.ic_lucide_at_sign, sideX, atY, dp(21), muted)
            target(RectF(sideX - dp(25), atY - dp(25), sideX + dp(25), atY + dp(25))) { actions.insertAt() }
        }
        if (longPressTriggered && mode == KeyboardMode.VOICE) drawLongPressTranslationOverlay(canvas)
    }

    private fun drawLongPressTranslationOverlay(canvas: Canvas) {
        if (holdOverlayProgress <= .01f) return
        val progress = holdOverlayProgress
        val centerX = width / 2f
        val maskTop = height - height * .52f * progress
        val mask = RectF(-dp(32), maskTop, width + dp(32), height + dp(32))
        rounded(canvas, mask, width.toFloat() / 2f, blend(key, Color.rgb(190, 190, 193), progress))
        label(canvas, "松开以${if (longPressTranslationSelected) "翻译" else "润色"}", centerX, maskTop + dp(48), 19f, actionIcon, Paint.Align.CENTER, true)

        val capsuleWidth = width * .66f
        val capsuleHeight = dp(68)
        val capsuleBottom = maskTop - dp(18)
        val capsule = RectF(centerX - capsuleWidth / 2f, capsuleBottom - capsuleHeight, centerX + capsuleWidth / 2f, capsuleBottom)
        rounded(canvas, capsule, capsuleHeight / 2f, blend(key, Color.rgb(226, 226, 228), progress))
        label(canvas, "英语（美国）", centerX, capsule.centerY() + dp(7), 22f, actionIcon, Paint.Align.CENTER, true)
        label(canvas, "向上滑动以翻译", centerX, capsule.top - dp(18), 14f, muted, Paint.Align.CENTER, true)
        postInvalidateOnAnimation()
    }

    private fun animateHoldOverlay(show: Boolean) {
        ValueAnimator.ofFloat(holdOverlayProgress, if (show) 1f else 0f).apply {
            duration = if (show) 220L else 160L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { holdOverlayProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun drawAnswer(canvas: Canvas, question: String, answer: String, streaming: Boolean) {
        val left = dp(20)
        val right = width - dp(20)
        val viewportTop = dp(76)
        val viewportBottom = height - dp(18)
        val viewport = RectF(left, viewportTop, right, viewportBottom)
        answerViewport = RectF(0f, viewportTop - dp(6), width.toFloat(), viewportBottom + dp(6))

        val topPadding = dp(20)
        val lines = buildAnswerLines(question.trim(), answer.trim(), streaming, viewport.width())
        val contentHeight = (topPadding + lines.sumOf { it.height.toDouble() }.toFloat()).coerceAtLeast(viewport.height())
        answerMaxScroll = (contentHeight - viewport.height()).coerceAtLeast(0f)
        answerScrollY = answerScrollY.coerceIn(0f, answerMaxScroll)

        canvas.save()
        canvas.clipRect(viewport)
        var y = viewport.top + topPadding - answerScrollY
        lines.forEach { line ->
            if (y + line.height >= viewport.top - dp(24) && y <= viewport.bottom + dp(24)) {
                label(canvas, line.text, left, y, line.sizeSp, line.color, Paint.Align.LEFT, line.bold, line.alpha)
            }
            y += line.height
        }
        canvas.restore()
    }

    private fun buildAnswerLines(question: String, answer: String, streaming: Boolean, maxWidth: Float): List<AnswerLine> {
        val lines = mutableListOf<AnswerLine>()
        lines += AnswerLine("问题", 14f, muted, true, 1f, dp(28))
        lines += wrapAnswerText(if (question.isBlank()) "未识别到问题" else question, 18f, white, true, maxWidth, dp(28))
        lines += AnswerLine("", 18f, white, false, 1f, dp(18))
        lines += AnswerLine("回答", 14f, muted, true, 1f, dp(28))
        val answerBody = answer.ifBlank { if (streaming) "" else "暂时没有回答内容" }
        if (answerBody.isNotBlank()) lines += wrapAnswerText(answerBody, 18f, white, false, maxWidth, dp(28))
        return lines
    }

    private fun wrapAnswerText(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean,
        maxWidth: Float,
        lineHeight: Float
    ): List<AnswerLine> {
        val result = mutableListOf<AnswerLine>()
        paint.textSize = dp(sizeSp.toInt())
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        text.split('\n').forEachIndexed { paragraphIndex, paragraph ->
            if (paragraph.isEmpty()) {
                result += AnswerLine("", sizeSp, color, bold, 1f, lineHeight)
            } else {
                var remaining = paragraph
                while (remaining.isNotEmpty()) {
                    val count = paint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
                    result += AnswerLine(remaining.substring(0, count), sizeSp, color, bold, 1f, lineHeight)
                    remaining = remaining.substring(count)
                }
            }
            if (paragraphIndex != text.split('\n').lastIndex) {
                // Paragraph spacing is represented by an empty line entry.
            }
        }
        return result
    }

    private fun drawKeyboard(canvas: Canvas, pinyin: Boolean) {
        if (pinyin && pinyinNineKey) {
            drawNineKeyKeyboard(canvas)
            return
        }
        val top = dp(62)
        val gap = dp(8)
        val keyHeight = (height - top - dp(9) - dp(32) - gap * 3) / 4f
        val edge = dp(5)
        val letterWidth = (width - edge * 2 - dp(7) * 9) / 10f
        letters(canvas, "qwertyuiop", top, keyHeight, pinyin, edge, letterWidth)
        val secondWidth = letterWidth * 9 + dp(7) * 8
        letters(canvas, "asdfghjkl", top + keyHeight + gap, keyHeight, pinyin, (width - secondWidth) / 2f, letterWidth)
        thirdRow(canvas, top + (keyHeight + gap) * 2, keyHeight, pinyin, letterWidth)
        bottomRow(canvas, top + (keyHeight + gap) * 3, keyHeight, pinyin, letterWidth)
    }

    private fun drawNineKeyKeyboard(canvas: Canvas) {
        val top = dp(62)
        val gap = dp(7)
        val edge = dp(5)
        val keyHeight = (height - top - dp(9) - dp(32) - gap * 3) / 4f
        val sidebarWidth = (width * .17f).coerceAtLeast(dp(54))
        val sidebar = RectF(edge, top, edge + sidebarWidth, top + keyHeight * 3 + gap * 2)
        val mainLeft = sidebar.right + gap
        val actionWidth = (width * .17f).coerceAtLeast(dp(54))
        val mainRight = width - edge - actionWidth - gap
        val keyWidth = (mainRight - mainLeft - gap * 2) / 3f
        val rows = listOf(
            listOf("ABC" to "2", "DEF" to "3", "GHI" to "4"),
            listOf("JKL" to "5", "MNO" to "6", "PQRS" to "7"),
            listOf("TUV" to "8", "WXYZ" to "9", "'" to "'")
        )
        rows.forEachIndexed { rowIndex, row ->
            val y = top + (keyHeight + gap) * rowIndex
            row.forEachIndexed { column, (letters, code) ->
                val x = mainLeft + column * (keyWidth + gap)
                val box = RectF(x, y, x + keyWidth, y + keyHeight)
                key(canvas, box)
                if (letters == "'") {
                    label(canvas, letters, box.centerX(), box.centerY() + dp(7), 25f, white, Paint.Align.CENTER, true)
                } else {
                    label(canvas, letters, box.centerX(), box.centerY() - dp(3), 17f, white, Paint.Align.CENTER, true)
                    label(canvas, code, box.centerX(), box.centerY() + dp(17), 12f, muted, Paint.Align.CENTER)
                }
                target(box, keySound = true) { actions.typePinyin(code) }
            }
        }
        drawNineKeySymbolSidebar(canvas, sidebar)
        val actionLeft = mainRight + gap
        val delete = RectF(actionLeft, top, width - edge, top + keyHeight)
        key(canvas, delete)
        lucide(canvas, R.drawable.ic_lucide_delete, delete.centerX(), delete.centerY(), dp(23), white)
        target(delete, repeat = true, keySound = true) { actions.backspace() }
        val newline = RectF(actionLeft, top + keyHeight + gap, width - edge, top + keyHeight * 3 + gap * 2)
        key(canvas, newline)
        centeredLabel(canvas, "换行", newline, 16f, white, Paint.Align.CENTER)
        target(newline, keySound = true) { actions.newline() }
        val y = top + (keyHeight + gap) * 3
        val symbols = RectF(edge, y, edge + sidebarWidth, y + keyHeight)
        val enter = RectF(actionLeft, y, width - edge, y + keyHeight)
        val space = RectF(mainLeft, y, mainRight, y + keyHeight)
        key(canvas, symbols)
        key(canvas, space)
        rounded(canvas, enter, dp(10), white)
        centeredLabel(canvas, "123", symbols, 16f, white, Paint.Align.CENTER)
        label(canvas, "拼", space.right - dp(11), space.centerY() + dp(10), 14f, muted, Paint.Align.RIGHT, true)
        lucide(canvas, R.drawable.ic_lucide_move_right, enter.centerX(), enter.centerY(), dp(21), actionIcon)
        target(symbols, hapticFeedback = null) { actions.toggleSymbols() }
        target(space, keySound = true) {
            if (pinyinCandidates.isNotEmpty()) actions.chooseCandidate(pinyinCandidates.first())
        }
        target(enter, keySound = true, longPressAction = { actions.newline() }) { actions.enter() }
    }

    private fun drawNineKeySymbolSidebar(canvas: Canvas, viewport: RectF) {
        nineKeySymbolViewport = RectF(viewport)
        val itemHeight = dp(48)
        val contentHeight = (nineKeySymbols.size * itemHeight).coerceAtLeast(viewport.height())
        nineKeySymbolMaxScroll = (contentHeight - viewport.height()).coerceAtLeast(0f)
        nineKeySymbolScrollY = nineKeySymbolScrollY.coerceIn(0f, nineKeySymbolMaxScroll)
        canvas.save()
        canvas.clipRect(viewport)
        nineKeySymbols.forEachIndexed { index, symbol ->
            val y = viewport.top + index * itemHeight - nineKeySymbolScrollY
            val box = RectF(viewport.left, y, viewport.right, y + itemHeight - dp(1))
            key(canvas, box)
            fittedLabel(canvas, symbol, box, 21f, white, false)
            val targetBox = RectF(box).apply { intersect(viewport) }
            target(targetBox, keySound = true) { actions.typeEnglish(symbol) }
        }
        canvas.restore()
    }

    private fun candidates(
        canvas: Canvas,
        candidateItems: List<PinyinCandidate>,
        pinyin: Boolean
    ) {
        val y = dp(11)
        val strip = RectF(dp(5), y, width - dp(5), y + dp(37))
        candidateStrip = RectF(0f, y - dp(4), width.toFloat(), y + dp(42))
        paint.textSize = dp(18)
        paint.typeface = Typeface.DEFAULT_BOLD
        if (candidateItems.isEmpty()) {
            label(canvas, if (pinyin) "正在匹配…" else "正在推荐…", strip.left + dp(8), strip.centerY() + dp(7), 14f, muted, Paint.Align.LEFT)
            return
        }
        val widths = candidateItems.map { maxOf(dp(32), paint.measureText(it.text) + dp(18)) }
        val contentWidth = widths.sum() + dp(7) * (widths.size - 1).coerceAtLeast(0)
        candidateMaxScroll = (contentWidth - strip.width()).coerceAtLeast(0f)
        candidateScrollX = candidateScrollX.coerceIn(0f, candidateMaxScroll)
        canvas.save()
        canvas.clipRect(strip)
        var cursor = strip.left - candidateScrollX
        candidateItems.forEachIndexed { index, candidate ->
            val box = RectF(cursor, y, cursor + widths[index], y + dp(37))
            cursor += widths[index] + dp(7)
            if (box.right < strip.left || box.left > strip.right) return@forEachIndexed
            if (index == 0) rounded(canvas, box, dp(7), key)
            label(canvas, candidate.text, box.centerX(), box.centerY() + dp(7), 18f, white, Paint.Align.CENTER, true)
            val visibleBox = RectF(box).apply { intersect(strip) }
            target(visibleBox, keySound = true) { if (pinyin) actions.chooseCandidate(candidate) else actions.chooseEnglishCandidate(candidate.text) }
        }
        canvas.restore()
    }

    private fun letters(canvas: Canvas, chars: String, y: Float, h: Float, pinyin: Boolean, inset: Float, keyWidth: Float) {
        val gap = dp(7)
        chars.forEachIndexed { index, character ->
            val x = inset + index * (keyWidth + gap)
            val box = RectF(x, y, x + keyWidth, y + h)
            key(canvas, box)
            val output = if (pinyin || uppercase) character.uppercaseChar().toString() else character.toString()
            label(canvas, output, box.centerX(), box.centerY() + dp(10), if (pinyin) 23f else 25f, white, Paint.Align.CENTER)
            target(box, keySound = true) { if (pinyin) actions.typePinyin(character.toString()) else actions.typeEnglishLetter(output) }
        }
    }

    private fun thirdRow(canvas: Canvas, y: Float, h: Float, pinyin: Boolean, keyWidth: Float) {
        val gap = dp(7)
        val side = dp(43)
        val delete = dp(43)
        val chars = "zxcvbnm"
        val rowWidth = side + delete + keyWidth * chars.length + gap * (chars.length + 1)
        val edge = (width - rowWidth) / 2f
        val shift = RectF(edge, y, edge + side, y + h)
        key(canvas, shift)
        val shiftIcon = if (!pinyin && uppercase) R.drawable.ic_lucide_arrow_big_up_dash else R.drawable.ic_lucide_arrow_big_up
        lucide(canvas, shiftIcon, shift.centerX(), shift.centerY(), dp(20), white)
        target(shift, keySound = true) { if (pinyin) actions.typePinyin("'") else { uppercase = !uppercase; invalidate() } }
        chars.forEachIndexed { index, character ->
            val x = edge + side + gap + index * (keyWidth + gap)
            val box = RectF(x, y, x + keyWidth, y + h)
            key(canvas, box)
            val output = if (pinyin || uppercase) character.uppercaseChar().toString() else character.toString()
            label(canvas, output, box.centerX(), box.centerY() + dp(10), if (pinyin) 23f else 25f, white, Paint.Align.CENTER)
            target(box, keySound = true) { if (pinyin) actions.typePinyin(character.toString()) else actions.typeEnglishLetter(output) }
        }
        val back = RectF(edge + rowWidth - delete, y, edge + rowWidth, y + h)
        key(canvas, back)
        lucide(canvas, R.drawable.ic_lucide_delete, back.centerX(), back.centerY(), dp(23), white)
        target(back, repeat = true, keySound = true) { actions.backspace() }
    }

    private fun bottomRow(canvas: Canvas, y: Float, h: Float, pinyin: Boolean, keyWidth: Float) {
        val gap = dp(7)
        val edge = dp(5)
        val controlWidth = keyWidth * 2 + gap
        val number = RectF(edge, y, edge + controlWidth, y + h)
        val enter = RectF(width - edge - controlWidth, y, width - edge, y + h)
        val space = RectF(number.right + gap, y, enter.left - gap, y + h)
        key(canvas, number)
        key(canvas, space)
        rounded(canvas, enter, dp(10), white)
        centeredLabel(canvas, "123", number, 19f, white, Paint.Align.CENTER)
        label(canvas, if (pinyin) "\u62fc" else "EN", space.right - dp(11), space.centerY() + dp(10), 14f, muted, Paint.Align.RIGHT, true)
        lucide(canvas, R.drawable.ic_lucide_move_right, enter.centerX(), enter.centerY(), dp(21), actionIcon)
        target(number, hapticFeedback = null) { actions.toggleSymbols() }
        target(space, keySound = true) {
            if (pinyin && pinyinCandidates.isNotEmpty()) actions.chooseCandidate(pinyinCandidates.first())
            else if (!pinyin && englishBuffer.isNotBlank()) actions.commitEnglishComposition(true)
            else actions.typeEnglish(" ")
        }
        target(enter, keySound = true, longPressAction = { actions.newline() }) { actions.enter() }
    }

    private fun drawSymbols(canvas: Canvas) {
        val top = dp(62)
        val gap = dp(8)
        val keyHeight = (height - top - dp(9) - dp(32) - gap * 3) / 4f
        val edge = dp(5)
        val symbolWidth = (width - edge * 2 - dp(7) * 9) / 10f
        val chineseSymbols = !symbolsUseEnglish
        symbolsRow(canvas, "1234567890", top, keyHeight, symbolWidth, chineseSymbols)
        symbolsRow(canvas, "-/:;()$&@", top + keyHeight + gap, keyHeight, symbolWidth, chineseSymbols)
        symbolsRow(canvas, ".,?!'\"[]", top + (keyHeight + gap) * 2, keyHeight, symbolWidth, chineseSymbols)
        val y = top + (keyHeight + gap) * 3
        val controlWidth = symbolWidth * 2 + dp(7)
        val number = RectF(edge, y, edge + controlWidth, y + keyHeight)
        val enter = RectF(width - edge - controlWidth, y, width - edge, y + keyHeight)
        val deleteWidth = dp(43)
        val delete = RectF(enter.left - dp(7) - deleteWidth, y, enter.left - dp(7), y + keyHeight)
        val space = RectF(number.right + dp(7), y, delete.left - dp(7), y + keyHeight)
        key(canvas, number)
        key(canvas, space)
        key(canvas, delete)
        rounded(canvas, enter, dp(10), white)
        centeredLabel(canvas, "ABC", number, 19f, white, Paint.Align.CENTER)
        lucide(canvas, R.drawable.ic_lucide_delete, delete.centerX(), delete.centerY(), dp(23), white)
        lucide(canvas, R.drawable.ic_lucide_move_right, enter.centerX(), enter.centerY(), dp(21), actionIcon)
        target(number, hapticFeedback = null) { actions.toggleSymbols() }
        target(space, keySound = true) { actions.typeEnglish(" ") }
        target(delete, repeat = true, keySound = true) { actions.backspace() }
        target(enter, keySound = true, longPressAction = { actions.newline() }) { actions.enter() }
    }

    private fun symbolsRow(
        canvas: Canvas,
        symbols: String,
        y: Float,
        h: Float,
        keyWidth: Float,
        chineseSymbols: Boolean
    ) {
        val displaySymbols = if (!chineseSymbols) symbols else when {
            symbols.startsWith("-") -> "，。？！：；（）“”"
            symbols.startsWith(".") -> "、】【《》“”‘’、"
            else -> symbols
        }
        val gap = dp(7)
        val rowWidth = keyWidth * displaySymbols.length + gap * (displaySymbols.length - 1)
        val edge = (width - rowWidth) / 2f
        displaySymbols.forEachIndexed { index, character ->
            val x = edge + index * (keyWidth + gap)
            val box = RectF(x, y, x + keyWidth, y + h)
            key(canvas, box)
            centeredLabel(canvas, character.toString(), box, 23f, white, Paint.Align.CENTER)
            target(box, keySound = true) { actions.typeEnglish(character.toString()) }
        }
    }

    private fun key(canvas: Canvas, box: RectF) = rounded(canvas, box, dp(9), key)

    private fun fittedLabel(
        canvas: Canvas,
        value: String,
        box: RectF,
        size: Float,
        color: Int,
        bold: Boolean
    ) {
        var renderedSize = size
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        while (renderedSize > 11f) {
            paint.textSize = dp(renderedSize.toInt())
            if (paint.measureText(value) <= box.width() - dp(8)) break
            renderedSize -= 1f
        }
        canvas.save()
        canvas.clipRect(box)
        label(canvas, value, box.centerX(), box.centerY() + dp(renderedSize.toInt()) * .35f, renderedSize, color, Paint.Align.CENTER, bold)
        canvas.restore()
    }

    private fun uniformCandidateLabel(canvas: Canvas, value: String, box: RectF, size: Float, color: Int) {
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = dp(size.toInt())
        val maxWidth = box.width() - dp(10)
        var rendered = value
        while (rendered.length > 1 && paint.measureText("$rendered...") > maxWidth) rendered = rendered.dropLast(1)
        if (rendered != value) rendered += "..."
        canvas.save()
        canvas.clipRect(box)
        label(canvas, rendered, box.centerX(), box.centerY() + dp(size.toInt()) * .35f, size, color, Paint.Align.CENTER, true)
        canvas.restore()
    }

    private fun leftClippedLabel(canvas: Canvas, value: String, box: RectF, size: Float, color: Int) {
        paint.typeface = Typeface.DEFAULT
        paint.textSize = dp(size.toInt())
        val maxWidth = box.width() - dp(24)
        var rendered = value
        while (rendered.length > 1 && paint.measureText("$rendered...") > maxWidth) rendered = rendered.dropLast(1)
        if (rendered != value) rendered += "..."
        canvas.save()
        canvas.clipRect(box)
        label(canvas, rendered, box.left + dp(12), box.centerY() + dp(size.toInt()) * .35f, size, color, Paint.Align.LEFT)
        canvas.restore()
    }

    private fun progress(canvas: Canvas, box: RectF, fraction: Float) {
        val clip = Path().apply { addRoundRect(box, box.height() / 2, box.height() / 2, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        paint.style = Paint.Style.FILL
        paint.color = white
        canvas.drawRect(box.left, box.top, box.left + box.width() * fraction, box.bottom, paint)
        canvas.restore()
    }

    private fun streamProgress(canvas: Canvas, box: RectF) {
        val elapsed = (System.currentTimeMillis() - polishingStreamStartedAt).coerceAtLeast(0L)
        progress(canvas, box, (elapsed % 1_500L) / 1_500f)
        postInvalidateOnAnimation()
    }

    private fun blend(from: Int, to: Int, fraction: Float): Int {
        val t = fraction.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt()
        )
    }

    private fun lucide(
        canvas: Canvas,
        @DrawableRes icon: Int,
        centerX: Float,
        centerY: Float,
        size: Float,
        tint: Int,
        alpha: Float = 1f
    ) {
        val drawable = AppCompatResources.getDrawable(context, icon)?.mutate() ?: return
        drawable.setTint(tint)
        drawable.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        val half = size / 2f
        drawable.setBounds((centerX - half).toInt(), (centerY - half).toInt(), (centerX + half).toInt(), (centerY + half).toInt())
        drawable.draw(canvas)
    }

    private fun rounded(canvas: Canvas, box: RectF, radius: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        if (isPressed(box)) {
            canvas.save()
            canvas.scale(.95f, .95f, box.centerX(), box.centerY())
            canvas.drawRoundRect(box, radius, radius, paint)
            canvas.restore()
        } else canvas.drawRoundRect(box, radius, radius, paint)
    }

    private fun circle(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawCircle(x, y, radius, paint)
    }

    private fun label(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        align: Paint.Align,
        bold: Boolean = false,
        alpha: Float = 1f
    ) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        paint.textSize = dp(size.toInt())
        paint.textAlign = align
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        canvas.drawText(value, x, y, paint)
        paint.alpha = 255
    }

    private fun centeredLabel(canvas: Canvas, value: String, box: RectF, size: Float, color: Int, align: Paint.Align) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = dp(size.toInt())
        paint.textAlign = align
        paint.typeface = Typeface.DEFAULT
        val baseline = box.centerY() - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(value, box.centerX(), baseline, paint)
    }

    private fun wave(canvas: Canvas, x: Float, y: Float, color: Int, size: Float) {
        paint.color = color
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = maxOf(dp(4), size / 10f)
        floatArrayOf(.42f, .72f, 1f, .72f, .42f).forEachIndexed { index, scale ->
            val px = x + (index - 2) * size / 4.2f
            val half = size * scale / 2f
            canvas.drawLine(px, y - half, px, y + half, paint)
        }
    }

    private fun microphone(canvas: Canvas, x: Float, y: Float, color: Int, size: Float, alpha: Float = 1f) {
        paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        rounded(canvas, RectF(x - size * .18f, y - size * .42f, x + size * .18f, y + size * .13f), size * .2f, color)
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = size * .12f
        canvas.drawArc(RectF(x - size * .34f, y - size * .15f, x + size * .34f, y + size * .40f), 0f, 180f, false, paint)
        canvas.drawLine(x, y + size * .40f, x, y + size * .58f, paint)
        canvas.drawLine(x - size * .20f, y + size * .58f, x + size * .20f, y + size * .58f, paint)
        paint.style = Paint.Style.FILL
        paint.alpha = 255
    }

    private fun loadingDots(canvas: Canvas, x: Float, y: Float, alpha: Float) {
        val step = (System.currentTimeMillis() % 800L).toFloat() / 800f
        repeat(6) { index ->
            val phase = (step + index / 6f) % 1f
            val dotAlpha = (.3f + .7f * kotlin.math.abs(phase * 2f - 1f)) * alpha
            paint.alpha = (dotAlpha * 255).toInt()
            circle(canvas, x + (index - 2.5f) * dp(12), y, dp(3), actionIcon)
        }
        paint.alpha = 255
        postInvalidateOnAnimation()
    }

    private fun voiceBars(canvas: Canvas, x: Float, y: Float, alpha: Float) {
        val factors = floatArrayOf(.48f, .72f, 1f, .86f, .64f, .42f)
        paint.color = actionIcon
        paint.alpha = (alpha * 255).toInt()
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = dp(4)
        factors.forEachIndexed { index, factor ->
            val motion = .72f + .28f * kotlin.math.sin(System.currentTimeMillis() / 68.0 + index * 1.1).toFloat()
            val height = dp(6) + dp(36) * (meter.coerceAtMost(1f) * factor * motion)
            val px = x + (index - 2.5f) * dp(8)
            canvas.drawLine(px, y - height / 2, px, y + height / 2, paint)
        }
        paint.alpha = 255
        postInvalidateOnAnimation()
    }

    private fun backArrow(canvas: Canvas, x: Float, y: Float, color: Int, size: Float) {
        paint.color = color
        paint.strokeWidth = dp(2)
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x + size, y, x - size, y, paint)
        canvas.drawLine(x - size, y, x - size * .35f, y - size * .55f, paint)
        canvas.drawLine(x - size, y, x - size * .35f, y + size * .55f, paint)
    }

    private fun pencil(canvas: Canvas, x: Float, y: Float, color: Int, size: Float) {
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2)
        canvas.save()
        canvas.rotate(-40f, x, y)
        canvas.drawRect(x - size * .18f, y - size, x + size * .18f, y + size * .7f, paint)
        canvas.drawLine(x - size * .18f, y - size, x, y - size * 1.35f, paint)
        canvas.drawLine(x + size * .18f, y - size, x, y - size * 1.35f, paint)
        canvas.restore()
        paint.style = Paint.Style.FILL
    }

    private fun closeGlyph(canvas: Canvas, x: Float, y: Float, color: Int, size: Float) {
        paint.color = color
        paint.strokeWidth = dp(2)
        canvas.drawLine(x - size, y - size, x + size, y + size, paint)
        canvas.drawLine(x + size, y - size, x - size, y + size, paint)
    }

    private fun backspace(canvas: Canvas, x: Float, y: Float, color: Int, size: Float) {
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = dp(2)
        val shape = Path().apply {
            moveTo(x - size, y)
            lineTo(x - size * .4f, y - size * .6f)
            lineTo(x + size, y - size * .6f)
            lineTo(x + size, y + size * .6f)
            lineTo(x - size * .4f, y + size * .6f)
            close()
        }
        canvas.drawPath(shape, paint)
        canvas.drawLine(x, y - size * .27f, x + size * .45f, y + size * .27f, paint)
        canvas.drawLine(x + size * .45f, y - size * .27f, x, y + size * .27f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun shiftGlyph(canvas: Canvas, x: Float, y: Float, color: Int, size: Float) {
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = dp(2)
        val shape = Path().apply {
            moveTo(x, y - size)
            lineTo(x - size, y)
            lineTo(x - size * .45f, y)
            lineTo(x - size * .45f, y + size)
            lineTo(x + size * .45f, y + size)
            lineTo(x + size * .45f, y)
            lineTo(x + size, y)
            close()
        }
        canvas.drawPath(shape, paint)
        paint.style = Paint.Style.FILL
    }

    private fun enterGlyph(canvas: Canvas, x: Float, y: Float, color: Int, size: Float) {
        paint.color = color
        paint.strokeWidth = dp(2)
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x + size * .45f, y - size * .55f, x + size * .45f, y, paint)
        canvas.drawLine(x + size * .45f, y, x - size * .55f, y, paint)
        canvas.drawLine(x - size * .55f, y, x - size * .18f, y - size * .35f, paint)
        canvas.drawLine(x - size * .55f, y, x - size * .18f, y + size * .35f, paint)
    }

    private fun target(
        box: RectF,
        enabled: Boolean = true,
        repeat: Boolean = false,
        hold: Boolean = false,
        keySound: Boolean = false,
        hapticFeedback: Int? = HapticFeedbackConstants.KEYBOARD_TAP,
        longPressAction: (() -> Unit)? = null,
        releaseAction: (() -> Unit)? = null,
        action: () -> Unit
    ) {
        targets += TouchTarget(box, enabled, repeat, hold, keySound, hapticFeedback, longPressAction, releaseAction, action)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                clipboardTracking = mode == KeyboardMode.CLIPBOARD && clipboardViewport?.contains(event.x, event.y) == true
                if (clipboardTracking) {
                    clipboardDragStartScroll = clipboardScrollY
                    clipboardTouchedEntry = clipboardRows.lastOrNull { it.box.contains(event.x, event.y) }?.entry
                    clipboardInitialReveal = clipboardTouchedEntry?.id == clipboardRevealId
                    clipboardSwipeOffset = if (clipboardInitialReveal) dp(68) else 0f
                    pressedBox = null
                    activeTarget = null
                    invalidate()
                    return true
                }
                candidateDragging = candidateStrip?.contains(event.x, event.y) == true
                if (candidateDragging) {
                    candidateScroller.forceFinished(true)
                    candidateVelocityTracker?.recycle()
                    candidateVelocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                }
                nineKeySymbolDragging = !candidateDragging && nineKeySymbolViewport?.contains(event.x, event.y) == true
                if (nineKeySymbolDragging) {
                    nineKeySymbolDragStart = nineKeySymbolScrollY
                }
                answerDragging = !candidateDragging && answerViewport?.contains(event.x, event.y) == true
                candidateDragStartScroll = candidateScrollX
                answerDragStartScroll = answerScrollY
                val hit = targets.lastOrNull { it.enabled && it.box.contains(event.x, event.y) }
                activeTarget = hit
                activeTargetCancelled = false
                longPressTriggered = false
                pressedBox = hit?.box
                if (hit?.repeat == true) {
                    repeatAction = hit.action
                    postDelayed(repeatBackspace, 360L)
                }
                if (hit?.longPressAction != null) postDelayed(longPressRunnable, 420L)
                if (hit?.hold == true) hit.action()
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                val hit = targets.lastOrNull { it.enabled && it.box.contains(event.getX(pointerIndex), event.getY(pointerIndex)) }
                if (hit != null) secondaryTouches[pointerId] = hit
                cancelledSecondaryTouches.remove(pointerId)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                secondaryTouches.toMap().forEach { (pointerId, target) ->
                    val pointerIndex = event.findPointerIndex(pointerId)
                    if (pointerIndex < 0 || !target.box.contains(event.getX(pointerIndex), event.getY(pointerIndex))) {
                        cancelledSecondaryTouches += pointerId
                    }
                }
                if (clipboardTracking) {
                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY
                    if (kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                        clipboardScrollY = (clipboardDragStartScroll - dy).coerceIn(0f, clipboardMaxScroll)
                        clipboardSwipeOffset = 0f
                    } else {
                        clipboardSwipeOffset = if (clipboardInitialReveal) {
                            (dp(68) - dx).coerceIn(0f, dp(68))
                        } else {
                            (-dx).coerceIn(0f, dp(68))
                        }
                    }
                    invalidate()
                } else if (nineKeySymbolDragging) {
                    nineKeySymbolScrollY = (nineKeySymbolDragStart - (event.y - touchDownY))
                        .coerceIn(0f, nineKeySymbolMaxScroll)
                    pressedBox = null
                    invalidate()
                } else if (candidateDragging) {
                    candidateVelocityTracker?.addMovement(event)
                    candidateScrollX = (candidateDragStartScroll - (event.x - touchDownX)).coerceIn(0f, candidateMaxScroll)
                    pressedBox = null
                    invalidate()
                } else if (answerDragging) {
                    answerScrollY = (answerDragStartScroll - (event.y - touchDownY)).coerceIn(0f, answerMaxScroll)
                    pressedBox = null
                    invalidate()
                } else if (longPressTriggered && longPressVoiceBox != null) {
                    val selected = event.y <= touchDownY - dp(86)
                    if (selected != longPressTranslationSelected) {
                        longPressTranslationSelected = selected
                        actions.setLongPressTranslation(selected)
                        emitHaptic(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                    pressedBox = activeTarget?.box
                    invalidate()
                } else {
                    val active = activeTarget
                    if (active != null && !active.box.contains(event.x, event.y)) {
                        activeTargetCancelled = true
                        pressedBox = null
                        removeCallbacks(repeatBackspace)
                        removeCallbacks(longPressRunnable)
                        repeatAction = null
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                val target = secondaryTouches.remove(pointerId)
                val cancelled = cancelledSecondaryTouches.remove(pointerId)
                if (target != null && !cancelled && target.box.contains(event.getX(pointerIndex), event.getY(pointerIndex))) {
                    target.hapticFeedback?.let(::emitHaptic)
                    if (target.keySound) keySound.play(keyboardSoundVolume)
                    target.action.invoke()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(repeatBackspace)
                removeCallbacks(longPressRunnable)
                repeatAction = null
                if (clipboardTracking) {
                    val entry = clipboardTouchedEntry
                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY
                    val revealAmount = clipboardSwipeOffset
                    clipboardTracking = false
                    clipboardTouchedEntry = null
                    clipboardSwipeOffset = 0f
                    clipboardInitialReveal = false
                    when {
                        entry == null -> Unit
                        kotlin.math.abs(dy) > kotlin.math.abs(dx) -> Unit
                        kotlin.math.abs(dx) >= dp(8) -> {
                            clipboardRevealId = if (revealAmount >= dp(34)) entry.id else null
                        }
                        clipboardRevealId == entry.id && event.x >= width - dp(78) -> {
                            clipboardRevealId = null
                            actions.deleteClipboard(entry)
                        }
                        kotlin.math.abs(dx) < dp(8) -> {
                            clipboardRevealId = null
                            emitHaptic(HapticFeedbackConstants.KEYBOARD_TAP)
                            actions.pasteClipboard(entry)
                        }
                    }
                    invalidate()
                    performClick()
                    return true
                }
                val wasNineKeySymbolDragging = nineKeySymbolDragging && kotlin.math.abs(event.y - touchDownY) > dp(4)
                val wasDragging = candidateDragging && kotlin.math.abs(event.x - touchDownX) > dp(4)
                val wasAnswerDragging = answerDragging && kotlin.math.abs(event.y - touchDownY) > dp(4)
                nineKeySymbolDragging = false
                candidateDragging = false
                answerDragging = false
                if (wasNineKeySymbolDragging || wasDragging || wasAnswerDragging) {
                    candidateVelocityTracker?.addMovement(event)
                    candidateVelocityTracker?.computeCurrentVelocity(1_000)
                    val velocity = (-(candidateVelocityTracker?.xVelocity ?: 0f)).toInt()
                    candidateVelocityTracker?.recycle()
                    candidateVelocityTracker = null
                    if (wasDragging && velocity != 0) {
                        candidateScroller.fling(
                            candidateScrollX.toInt(), 0, velocity, 0,
                            0, candidateMaxScroll.toInt(), 0, 0
                        )
                        postInvalidateOnAnimation()
                    }
                    pressedBox = null
                    performClick()
                    return true
                }
                candidateVelocityTracker?.recycle()
                candidateVelocityTracker = null
                val active = activeTarget
                val hit = targets.lastOrNull { it.enabled && it.box.contains(event.x, event.y) }
                val releasedOnOriginalTarget = !activeTargetCancelled && sameBox(hit?.box, active?.box)
                postDelayed({ pressedBox = null; invalidate() }, 100L)
                if (longPressTriggered) {
                    active?.releaseAction?.invoke()
                } else if (releasedOnOriginalTarget) {
                    hit?.hapticFeedback?.let(::emitHaptic)
                    if (hit?.keySound == true) keySound.play(keyboardSoundVolume)
                    hit?.action?.invoke()
                }
                activeTarget = null
                activeTargetCancelled = false
                longPressVoiceBox = null
                animateHoldOverlay(show = false)
                secondaryTouches.clear()
                cancelledSecondaryTouches.clear()
                longPressTriggered = false
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(repeatBackspace)
                removeCallbacks(longPressRunnable)
                repeatAction = null
                activeTarget?.takeIf { longPressTriggered }?.releaseAction?.invoke()
                activeTarget = null
                activeTargetCancelled = false
                longPressVoiceBox = null
                animateHoldOverlay(show = false)
                secondaryTouches.clear()
                cancelledSecondaryTouches.clear()
                longPressTriggered = false
                candidateDragging = false
                candidateVelocityTracker?.recycle()
                candidateVelocityTracker = null
                answerDragging = false
                nineKeySymbolDragging = false
                clipboardTracking = false
                clipboardTouchedEntry = null
                clipboardSwipeOffset = 0f
                clipboardInitialReveal = false
                pressedBox = null
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }

    fun releaseResources() {
        keySound.release()
    }

    override fun computeScroll() {
        if (candidateScroller.computeScrollOffset()) {
            candidateScrollX = candidateScroller.currX.toFloat().coerceIn(0f, candidateMaxScroll)
            postInvalidateOnAnimation()
        }
    }

    /** Xiaomi routes Android's vibrator service through its system haptic engine. */
    private fun emitHaptic(type: Int) {
        if (hapticStrength == HapticStrength.OFF) return
        if (hapticStrength == HapticStrength.SYSTEM) {
            val enabled = runCatching {
                Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
            }.getOrDefault(true)
            if (enabled) performHapticFeedback(type)
            return
        }
        val baseDuration = when (type) {
            HapticFeedbackConstants.KEYBOARD_TAP -> 5L
            HapticFeedbackConstants.CONTEXT_CLICK -> 8L
            HapticFeedbackConstants.GESTURE_START -> 14L
            HapticFeedbackConstants.GESTURE_END -> 11L
            HapticFeedbackConstants.CONFIRM -> 18L
            HapticFeedbackConstants.REJECT -> 26L
            else -> 8L
        }
        val duration = baseDuration + when (hapticStrength) {
            HapticStrength.WEAK -> 0L
            HapticStrength.MEDIUM -> 3L
            HapticStrength.FAIRLY_STRONG -> 7L
            HapticStrength.STRONG -> 11L
            else -> 0L
        }
        val deviceVibrator = vibrator
        if (deviceVibrator?.hasVibrator() == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (hapticStrength == HapticStrength.STRONG && type != HapticFeedbackConstants.KEYBOARD_TAP) {
                    VibrationEffect.createWaveform(
                        longArrayOf(0L, duration, 16L, 5L),
                        intArrayOf(0, hapticStrength.amplitude, 0, 150),
                        -1
                    )
                } else VibrationEffect.createOneShot(duration, hapticStrength.amplitude)
                deviceVibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                deviceVibrator.vibrate(duration)
            }
        } else {
            performHapticFeedback(type)
        }
    }

    private fun dp(value: Int): Float = value * resources.displayMetrics.density
    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private fun hasComposition(): Boolean =
        (mode == KeyboardMode.PINYIN && pinyinBuffer.isNotBlank()) ||
            (mode == KeyboardMode.ENGLISH && englishBuffer.isNotBlank())
    private fun topModeFor(mode: KeyboardMode, options: List<KeyboardMode>): KeyboardMode =
        if (mode in listOf(KeyboardMode.PINYIN, KeyboardMode.ENGLISH) && KeyboardMode.TEXT in options) KeyboardMode.TEXT else mode
    private fun isActive(state: VoiceUiState) = state == VoiceUiState.Listening
    private fun isPressed(box: RectF): Boolean = pressedBox?.let { pressed ->
        kotlin.math.abs(pressed.left - box.left) < 1f && kotlin.math.abs(pressed.top - box.top) < 1f &&
            kotlin.math.abs(pressed.right - box.right) < 1f && kotlin.math.abs(pressed.bottom - box.bottom) < 1f
    } == true
    private fun sameBox(first: RectF?, second: RectF?): Boolean = first != null && second != null &&
        kotlin.math.abs(first.left - second.left) < 1f && kotlin.math.abs(first.top - second.top) < 1f &&
        kotlin.math.abs(first.right - second.right) < 1f && kotlin.math.abs(first.bottom - second.bottom) < 1f
    private data class TouchTarget(
        val box: RectF,
        val enabled: Boolean,
        val repeat: Boolean,
        val hold: Boolean,
        val keySound: Boolean,
        val hapticFeedback: Int?,
        val longPressAction: (() -> Unit)?,
        val releaseAction: (() -> Unit)?,
        val action: () -> Unit
    )

    private data class AnswerLine(
        val text: String,
        val sizeSp: Float,
        val color: Int,
        val bold: Boolean,
        val alpha: Float,
        val height: Float
    )

    private data class ClipboardRow(val entry: ClipboardEntry, val box: RectF)

}
