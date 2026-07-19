package com.weike.ime

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.PopupWindow
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.weike.ime.data.AppContainer
import com.weike.ime.data.ChineseKeyboardLayout
import com.weike.ime.data.CloudProvider
import com.weike.ime.data.HapticStrength
import com.weike.ime.data.HistoryRetention
import com.weike.ime.data.InputHistory
import com.weike.ime.data.InputHistoryType
import com.weike.ime.data.KeyboardModePreference
import com.weike.ime.data.KeyboardTheme
import com.weike.ime.data.LexiconTerm
import com.weike.ime.data.ModelEndpointConfig
import com.weike.ime.data.PunctuationPreference
import com.weike.ime.data.TypingDictionaryEntry
import com.weike.ime.data.UsageStats
import com.weike.ime.data.WritingStyle
import com.weike.ime.ime.LocalPinyinDecoder
import com.weike.ime.ime.WeikeInputMethodService
import com.weike.ime.network.MimoTextPolisher
import com.weike.ime.network.MimoApiConfig
import com.weike.ime.network.ModelCatalog
import com.weike.ime.speech.MimoAsrClient
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private enum class Page {
        HOME, HISTORY, DICTIONARY, ACCOUNT, SETTINGS, CLOUD, ABOUT,
        LAYOUT, KEY_EFFECTS, AUXILIARY_INPUT, TOOLBAR, KEYBOARD_MANAGEMENT,
        CLIPBOARD_SETTINGS, LOCAL_DATA, OPTIMIZE_INPUT, PERMISSION_MANAGEMENT, TEST
    }

    private class SegmentedControl(
        context: android.content.Context,
        private val options: List<String>,
        initial: Int,
        private val onSelected: (Int) -> Unit
    ) : FrameLayout(context) {
        private val density = resources.displayMetrics.density
        private val selector = View(context)
        private val labels = mutableListOf<TextView>()
        private var selected = initial.coerceIn(0, options.lastIndex)

        init {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (46 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.weike_key))
                cornerRadius = 15 * density
            }
            selector.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(18, 95, 142))
                cornerRadius = 12 * density
            }
            addView(selector)
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            options.forEachIndexed { index, label ->
                row.addView(TextView(context).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 13f
                    setOnClickListener { setSelected(index, true) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                labels += row.getChildAt(index) as TextView
            }
            addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            post { render(false) }
        }

        fun setSelected(index: Int, notify: Boolean = false) {
            val next = index.coerceIn(0, options.lastIndex)
            if (next == selected && !notify) return
            selected = next
            render(true)
            if (notify) onSelected(next)
        }

        private fun render(animated: Boolean) {
            if (width == 0 || options.isEmpty()) return
            val inset = (4 * density).toInt()
            val cell = width / options.size
            selector.layoutParams = FrameLayout.LayoutParams(cell - inset * 2, height - inset * 2).apply {
                leftMargin = inset
                topMargin = inset
            }
            val targetX = (selected * cell).toFloat()
            if (animated) selector.animate().translationX(targetX).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            else selector.translationX = targetX
            labels.forEachIndexed { index, label ->
                label.setTextColor(if (index == selected) Color.WHITE else ContextCompat.getColor(context, R.color.weike_muted))
                label.typeface = if (index == selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
    }

    private class CloudProviderDropdown(
        context: android.content.Context,
        initial: CloudProvider,
        private val onSelected: (CloudProvider) -> Unit
    ) : LinearLayout(context) {
        private val choices = CloudProvider.entries.toList()
        private var selected = initial
        private val icon = ImageView(context)
        private val label = TextView(context)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 14), 0, dp(context, 12), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.weike_key))
                cornerRadius = dp(context, 14).toFloat()
            }
            icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            addView(icon, LayoutParams(dp(context, 26), dp(context, 26)))
            label.textSize = 16f
            label.gravity = Gravity.CENTER_VERTICAL
            label.setPadding(dp(context, 10), 0, dp(context, 6), 0)
            addView(label, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_lucide_chevron_down)
                setColorFilter(ContextCompat.getColor(context, R.color.weike_muted))
                contentDescription = "展开模型厂商"
            }, LayoutParams(dp(context, 22), dp(context, 22)))
            isClickable = true
            setOnClickListener { showMenu() }
            render()
        }

        fun setSelected(value: CloudProvider, notify: Boolean = false) {
            if (selected == value && !notify) return
            selected = value
            render()
            if (notify) onSelected(value)
        }

        private fun render() {
            icon.setImageResource(if (selected == CloudProvider.CUSTOM) R.drawable.provider_custom_logo else R.drawable.ic_xiaomi_mimo)
            icon.clearColorFilter()
            label.text = selected.displayName
            label.setTextColor(ContextCompat.getColor(context, R.color.weike_text))
            label.typeface = Typeface.DEFAULT_BOLD
        }

        private fun showMenu() {
            val content = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.weike_panel))
                    cornerRadius = dp(context, 16).toFloat()
                }
            }
            val search = EditText(context).apply {
                hint = "搜索模型厂商"
                setSingleLine(true)
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.weike_text))
                setHintTextColor(ContextCompat.getColor(context, R.color.weike_muted))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.weike_key))
                    cornerRadius = dp(context, 11).toFloat()
                }
                setPadding(dp(context, 12), 0, dp(context, 12), 0)
            }
            val rows = LinearLayout(context).apply { orientation = VERTICAL }
            content.addView(search, LayoutParams(LayoutParams.MATCH_PARENT, dp(context, 46)))
            content.addView(rows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(context, 8) })
            val popup = PopupWindow(content, width.coerceAtLeast(dp(context, 260)), LayoutParams.WRAP_CONTENT, true).apply {
                isOutsideTouchable = true
                elevation = dp(context, 10).toFloat()
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            }
            fun renderRows(query: String) {
                rows.removeAllViews()
                choices.filter { it.displayName.contains(query.trim(), ignoreCase = true) }.forEachIndexed { index, provider ->
                    rows.addView(providerRow(provider, popup), LayoutParams(LayoutParams.MATCH_PARENT, dp(context, 54)).apply {
                        if (index > 0) topMargin = dp(context, 5)
                    })
                }
                if (rows.childCount == 0) {
                    rows.addView(TextView(context).apply {
                        text = "没有匹配的模型厂商"
                        gravity = Gravity.CENTER
                        textSize = 14f
                        setTextColor(ContextCompat.getColor(context, R.color.weike_muted))
                    }, LayoutParams(LayoutParams.MATCH_PARENT, dp(context, 54)))
                }
            }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = renderRows(value?.toString().orEmpty())
                override fun afterTextChanged(value: Editable?) = Unit
            })
            renderRows("")
            popup.showAsDropDown(this, 0, dp(context, 7))
        }

        private fun providerRow(provider: CloudProvider, popup: PopupWindow) = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), 0, dp(context, 12), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (provider == selected) Color.rgb(226, 239, 247) else ContextCompat.getColor(context, R.color.weike_key))
                cornerRadius = dp(context, 11).toFloat()
            }
            addView(ImageView(context).apply {
                setImageResource(if (provider == CloudProvider.CUSTOM) R.drawable.provider_custom_logo else R.drawable.ic_xiaomi_mimo)
                clearColorFilter()
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LayoutParams(dp(context, 25), dp(context, 25)))
            addView(TextView(context).apply {
                text = provider.displayName
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(ContextCompat.getColor(context, R.color.weike_text))
                setPadding(dp(context, 11), 0, 0, 0)
            }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            setOnClickListener {
                setSelected(provider, true)
                popup.dismiss()
            }
        }

        private fun dp(context: android.content.Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()
    }

    /** A compact slider drawn with the same rounded track language as segmented controls. */
    private class VolumeSlider(
        context: android.content.Context,
        initialValue: Float,
        private val onValueChanged: (Float, Boolean) -> Unit
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var value = initialValue.coerceIn(0f, 1f)
        private val track = RectF()

        init {
            contentDescription = "按键音量"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (48 * density).toInt()
            )
            isClickable = true
        }

        fun setValue(nextValue: Float) {
            value = nextValue.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val side = 2 * density
            val trackHeight = 18 * density
            val top = (height - trackHeight) / 2f
            track.set(side, top, width - side, top + trackHeight)
            paint.color = ContextCompat.getColor(context, R.color.weike_key)
            canvas.drawRoundRect(track, trackHeight / 2f, trackHeight / 2f, paint)

            if (value > 0f) {
                val fill = RectF(track.left, track.top, track.left + track.width() * value, track.bottom)
                paint.color = Color.rgb(18, 95, 142)
                canvas.drawRoundRect(fill, trackHeight / 2f, trackHeight / 2f, paint)
            }

            val thumbX = track.left + track.width() * value
            paint.setShadowLayer(2 * density, 0f, density, 0x33000000)
            setLayerType(LAYER_TYPE_SOFTWARE, paint)
            paint.color = ContextCompat.getColor(context, R.color.weike_panel)
            canvas.drawCircle(thumbX, track.centerY(), 13 * density, paint)
            paint.clearShadowLayer()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.color = ContextCompat.getColor(context, R.color.weike_muted)
            canvas.drawCircle(thumbX, track.centerY(), 13 * density, paint)
            paint.style = Paint.Style.FILL
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    val side = 2 * density
                    value = ((event.x - side) / (width - side * 2)).coerceIn(0f, 1f)
                    invalidate()
                    onValueChanged(value, event.actionMasked == MotionEvent.ACTION_UP)
                    if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private class BlueToggle(
        context: android.content.Context,
        initial: Boolean,
        private val onChanged: (Boolean) -> Unit
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var checked = initial
        private var thumbProgress = if (initial) 1f else 0f

        init {
            layoutParams = LinearLayout.LayoutParams((52 * density).toInt(), (32 * density).toInt())
            isClickable = true
            contentDescription = "开关"
        }

        fun setChecked(next: Boolean, notify: Boolean = false) {
            if (checked == next) return
            checked = next
            ValueAnimator.ofFloat(thumbProgress, if (next) 1f else 0f).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    thumbProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
            if (notify) onChanged(next)
        }

        override fun onDraw(canvas: Canvas) {
            val track = RectF(0f, 0f, width.toFloat(), height.toFloat())
            paint.color = if (checked) Color.rgb(18, 95, 142) else ContextCompat.getColor(context, R.color.weike_key)
            canvas.drawRoundRect(track, height / 2f, height / 2f, paint)
            paint.color = ContextCompat.getColor(context, R.color.weike_panel)
            val radius = height / 2f - 4.5f * density
            val centerX = height / 2f + (width - height) * thumbProgress
            canvas.drawCircle(centerX, height / 2f, radius, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                setChecked(!checked, notify = true)
                performClick()
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    /** Live miniature of the keyboard used by display-related settings. */
    private class KeyboardSettingsPreview(context: android.content.Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var theme = KeyboardTheme.DARK
        private var candidateLevel = 0

        init {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (142 * density).toInt())
        }

        fun setTheme(value: KeyboardTheme) {
            theme = value
            invalidate()
        }

        fun setCandidateLevel(value: Int) {
            candidateLevel = value.coerceIn(-3, 3)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            when (theme) {
                KeyboardTheme.DARK -> drawKeyboard(canvas, true)
                KeyboardTheme.LIGHT -> drawKeyboard(canvas, false)
                KeyboardTheme.SYSTEM -> {
                    canvas.save()
                    canvas.clipRect(0f, 0f, width / 2f, height.toFloat())
                    drawKeyboard(canvas, false)
                    canvas.restore()
                    canvas.save()
                    canvas.clipRect(width / 2f, 0f, width.toFloat(), height.toFloat())
                    drawKeyboard(canvas, true)
                    canvas.restore()
                    paint.color = Color.argb(35, 255, 255, 255)
                    canvas.drawRect(width / 2f - density / 2f, 0f, width / 2f + density / 2f, height.toFloat(), paint)
                }
            }
        }

        private fun drawKeyboard(canvas: Canvas, dark: Boolean) {
            val surface = if (dark) Color.rgb(38, 39, 42) else Color.rgb(242, 244, 247)
            val key = if (dark) Color.rgb(76, 78, 83) else Color.WHITE
            val text = if (dark) Color.rgb(247, 248, 249) else Color.rgb(28, 31, 35)
            val muted = if (dark) Color.rgb(181, 184, 190) else Color.rgb(100, 106, 114)
            val margin = 10 * density
            val candidateHeight = 42 * density
            paint.color = surface
            canvas.drawRoundRect(rect, 20 * density, 20 * density, paint)

            paint.color = if (dark) Color.rgb(52, 54, 58) else Color.rgb(231, 235, 240)
            canvas.drawRoundRect(RectF(margin, margin, width - margin, margin + candidateHeight), 12 * density, 12 * density, paint)
            paint.textAlign = Paint.Align.LEFT
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = (11 + candidateLevel * 1.15f) * density
            paint.color = text
            canvas.drawText("今天", margin + 12 * density, margin + candidateHeight * .63f, paint)
            paint.color = muted
            canvas.drawText("候选词", width * .39f, margin + candidateHeight * .63f, paint)
            canvas.drawText("输入法", width * .69f, margin + candidateHeight * .63f, paint)

            val rows = intArrayOf(8, 7, 5)
            val keyTop = margin + candidateHeight + 9 * density
            val availableHeight = height - keyTop - margin
            rows.forEachIndexed { row, count ->
                val gap = 5 * density
                val keyHeight = (availableHeight - gap * (rows.size - 1)) / rows.size
                val rowWidth = width - margin * 2 - gap * (count - 1)
                val keyWidth = rowWidth / count
                val top = keyTop + row * (keyHeight + gap)
                repeat(count) { index ->
                    val left = margin + index * (keyWidth + gap)
                    paint.color = key
                    canvas.drawRoundRect(RectF(left, top, left + keyWidth, top + keyHeight), 7 * density, 7 * density, paint)
                    if (row < 2) {
                        paint.textAlign = Paint.Align.CENTER
                        paint.typeface = Typeface.DEFAULT
                        paint.textSize = 8 * density
                        paint.color = text
                        canvas.drawText(('A'.code + (row * 8 + index) % 26).toChar().toString(), left + keyWidth / 2f, top + keyHeight * .64f, paint)
                    }
                }
            }
        }
    }

    private lateinit var container: AppContainer
    private lateinit var pageHost: FrameLayout
    private lateinit var bottomNavigation: LinearLayout
    private var page = Page.ACCOUNT
    private val pageScrollPositions = mutableMapOf<Page, Int>()

    private var homeStats: TextView? = null
    private var statMinutes: TextView? = null
    private var statWords: TextView? = null
    private var statSaved: TextView? = null
    private var statSpeed: TextView? = null
    private var historyList: LinearLayout? = null
    private var lexiconList: LinearLayout? = null
    private var typingDictionaryList: LinearLayout? = null
    private var overridesList: LinearLayout? = null
    private var keyboardModesList: LinearLayout? = null
    private var nineKeySymbolsList: LinearLayout? = null
    private var microphoneStatus: TextView? = null
    private var latestStats = UsageStats()
    private var latestHistory: List<InputHistory> = emptyList()
    private var latestLexicon: List<LexiconTerm> = emptyList()
    private var latestTypingDictionary: List<TypingDictionaryEntry> = emptyList()
    private var latestOverrides: Map<String, WritingStyle> = emptyMap()
    private var latestKeyboardModes: List<KeyboardModePreference> = emptyList()
    private var latestNineKeySymbols: List<String> = emptyList()
    private var dictionaryTab = 0
    private var dragHighlight: View? = null
    private var dragSource: View? = null
    private val pagePrimary = Color.rgb(0, 55, 85)
    private val managementLogoBlue = Color.rgb(0, 55, 85)
    private val settingsBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showPage(Page.ACCOUNT)
        }
    }

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updatePermissionStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container = AppContainer(this)
        setContentView(buildShell())
        onBackPressedDispatcher.addCallback(this, settingsBackCallback)
        observeData()
        lifecycleScope.launch { applyKeyboardTheme(container.settings.keyboardTheme()) }
        showPage(Page.ACCOUNT)
    }

    override fun onBackPressed() {
        if (page != Page.ACCOUNT) showPage(Page.ACCOUNT) else super.onBackPressed()
    }

    private fun buildShell(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(getColor(R.color.weike_background))
        pageHost = FrameLayout(this@MainActivity)
        addView(pageHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        bottomNavigation = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(12))
            setBackgroundColor(getColor(R.color.weike_panel))
            visibility = View.GONE
        }
    }

    private fun showPage(next: Page) {
        while (pageHost.childCount > 1) pageHost.removeViewAt(0)
        val previous = pageHost.getChildAt(0)
        val previousPage = page
        (previous as? ScrollView)?.let { pageScrollPositions[previousPage] = it.scrollY }
        page = next
        val nextView = when (next) {
                Page.HOME -> buildHome()
                Page.HISTORY -> buildHistory()
                Page.DICTIONARY -> buildDictionary()
                Page.ACCOUNT -> buildAccount()
                Page.SETTINGS -> buildSettings()
                Page.CLOUD -> buildCloudConfiguration()
                Page.ABOUT -> buildAbout()
                Page.LAYOUT -> buildLayoutDisplay()
                Page.KEY_EFFECTS -> buildKeyEffects()
                Page.AUXILIARY_INPUT -> buildAuxiliaryInput()
                Page.TOOLBAR -> buildToolbarSettings()
                Page.KEYBOARD_MANAGEMENT -> buildKeyboardManagement()
                Page.CLIPBOARD_SETTINGS -> buildClipboardSettings()
                Page.LOCAL_DATA -> buildLocalData()
                Page.OPTIMIZE_INPUT -> buildOptimizeInput()
                Page.PERMISSION_MANAGEMENT -> buildPermissionManagement()
                Page.TEST -> buildTestPage()
            }
        if (previous == null) {
            pageHost.addView(nextView)
        } else {
            previous.animate().cancel()
            nextView.alpha = 0f
            nextView.translationX = dp(20).toFloat()
            pageHost.addView(nextView)
            nextView.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(220L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            previous.animate()
                .alpha(0f)
                .translationX(-dp(10).toFloat())
                .setDuration(160L)
                .withEndAction {
                    if (previous.parent === pageHost) pageHost.removeView(previous)
                }
                .start()
        }
        (nextView as? ScrollView)?.let { scrollView ->
            scrollView.post { scrollView.scrollTo(0, pageScrollPositions[next] ?: 0) }
        }
        bottomNavigation.visibility = View.GONE
        settingsBackCallback.isEnabled = next != Page.ACCOUNT
        updatePermissionStatus()
    }

    private fun rebuildNavigation() {
        bottomNavigation.removeAllViews()
        listOf(
            Triple(Page.HOME, "首页", R.drawable.ic_lucide_house),
            Triple(Page.HISTORY, "历史记录", R.drawable.ic_lucide_history),
            Triple(Page.DICTIONARY, "词典", R.drawable.ic_lucide_book_open),
            Triple(Page.ACCOUNT, "管理", R.drawable.ic_lucide_settings_2)
        ).forEach { (target, label, icon) ->
            val selected = page == target
            val tint = if (selected) pagePrimary else getColor(R.color.weike_muted)
            bottomNavigation.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(icon)
                    setColorFilter(tint)
                }, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(TextView(this@MainActivity).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 13f
                    typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(tint)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24)))
                setOnClickListener { showPage(target) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun buildHome(): View = screen {
        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_lucide_mic)
                setColorFilter(pagePrimary)
            }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(8) })
            addView(brandTitle("维刻"))
        })
        addView(subtitle("输入法与语音工作台"))
        addView(section("听写统计"))
        addView(card().apply {
            addView(statsRow(
                statCell("总听写时间", R.drawable.ic_lucide_clock_3) { statMinutes = it },
                statCell("听写字数", R.drawable.ic_lucide_mic) { statWords = it }
            ))
            addView(statsRow(
                statCell("节省的时间", R.drawable.ic_lucide_hourglass) { statSaved = it },
                statCell("平均听写速度", R.drawable.ic_lucide_zap) { statSpeed = it }
            ))
        })
        renderStats(latestStats)
        addView(section("快速开始"))
        addView(card().apply {
            addView(actionRow("启用维刻输入法", "打开系统输入法设置") {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            })
            addDivider(this)
            addView(actionRow("切换输入法", "选择维刻输入法") {
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            })
            addDivider(this)
            addView(actionRow("录音权限", "允许语音听写与润色") {
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            })
            addDivider(this)
            addView(actionRow("横屏悬浮窗", "横屏时保持键盘可用") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
            })
        })
        addView(section("测试输入"))
        addView(EditText(this@MainActivity).apply {
            hint = "在这里试试语音、拼音或英文输入"
            setTextColor(getColor(R.color.weike_text))
            setHintTextColor(getColor(R.color.weike_muted))
            textSize = 17f
            minLines = 4
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = roundedBackground(getColor(R.color.weike_panel), 24)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(150), top = 2)
        })
    }

    private fun buildHistory(): View = screen {
        addView(brandTitle("历史记录"))
        addView(card().apply {
            val options = HistoryRetention.entries.toList()
            val labels = listOf("不保留", "24小时", "1周", "1月", "永久")
            val control = SegmentedControl(this@MainActivity, labels, 0) { position ->
                lifecycleScope.launch {
                    val retention = options[position]
                    container.settings.saveHistoryRetention(retention)
                    if (retention == HistoryRetention.NEVER) container.inputHistory.deleteAll()
                }
            }
            addView(TextView(this@MainActivity).apply { text = "保留历史"; textSize = 18f; setTextColor(getColor(R.color.weike_text)) })
            addView(subtitle("内容仅存储在这台设备"))
            addView(control)
            addDivider(this)
            addView(actionRow("删除全部历史记录", "此操作不会清除统计数据", destructive = true) {
                lifecycleScope.launch { container.inputHistory.deleteAll() }
            })
            lifecycleScope.launch { control.setSelected(options.indexOf(container.settings.historyRetention()).coerceAtLeast(0)) }
        })
        addView(section("最近记录"))
        historyList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(historyList)
        renderHistory(latestHistory)
    }

    private fun buildDictionary(): View = screen {
        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(brandTitle("词典"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_lucide_plus)
                setColorFilter(Color.WHITE)
                background = circleBackground(getColor(R.color.weike_text))
                setPadding(dp(13), dp(13), dp(13), dp(13))
                setOnClickListener { showAddDictionaryDialog() }
            }, LinearLayout.LayoutParams(dp(52), dp(52)))
        })
        addView(
            SegmentedControl(this@MainActivity, listOf("所有", "专业词", "打字词"), dictionaryTab) { index ->
                dictionaryTab = index
                showPage(Page.DICTIONARY)
            },
            margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), top = 16)
        )
        if (dictionaryTab != 2) {
            addView(section("专业词库"))
            lexiconList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(lexiconList)
            renderLexicon(latestLexicon)
        }
        if (dictionaryTab != 1) {
            addView(section("打字词典"))
            typingDictionaryList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(typingDictionaryList)
            renderTypingDictionary(latestTypingDictionary)
        }
    }

    private fun buildAccount(): View = screen {
        addView(managementBrandHeader())
        addView(section("听写统计"))
        addView(card().apply {
            addView(statsRow(
                statCell("总听写时间", R.drawable.ic_lucide_clock_3) { statMinutes = it },
                statCell("听写字数", R.drawable.ic_lucide_mic) { statWords = it }
            ))
            addView(statsRow(
                statCell("节省的时间", R.drawable.ic_lucide_hourglass) { statSaved = it },
                statCell("平均听写速度", R.drawable.ic_lucide_zap) { statSpeed = it }
            ))
        })
        renderStats(latestStats)

        addView(section("设置"))
        addView(managementTileRow(
            managementTile("布局和显示", "外观与候选词大小", R.drawable.ic_lucide_layout_dashboard) { showPage(Page.LAYOUT) },
            managementTile("按键效果", "声音与触感强度", R.drawable.ic_lucide_zap) { showPage(Page.KEY_EFFECTS) }
        ))
        addView(managementTileRow(
            managementTile("辅助输入", "标点与英文输入", R.drawable.ic_lucide_sliders_horizontal) { showPage(Page.AUXILIARY_INPUT) },
            managementTile("定制工具栏", "模式胶囊与收起键", R.drawable.ic_lucide_settings_2) { showPage(Page.TOOLBAR) }
        ), margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(156), top = 12))
        addView(managementTileRow(
            managementTile("键盘管理", "中文主键盘与九宫格", R.drawable.ic_lucide_keyboard) { showPage(Page.KEYBOARD_MANAGEMENT) },
            managementTile("剪贴板", "本机剪贴板历史", R.drawable.ic_lucide_clipboard) { showPage(Page.CLIPBOARD_SETTINGS) }
        ), margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(156), top = 12))
        addView(managementTileRow(
            managementTile("优化输入", "词典、文风与表达优化", R.drawable.ic_lucide_book_open) { showPage(Page.OPTIMIZE_INPUT) },
            managementTile("语音与文本", "接口连接与模型配置", R.drawable.ic_lucide_cloud) { showPage(Page.CLOUD) }
        ), margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(156), top = 12))

        addView(section("更多"))
        addView(managementTileRow(
            managementTile("本机数据", "历史记录与本机学习", R.drawable.ic_lucide_history) { showPage(Page.LOCAL_DATA) },
            managementTile("权限管理", "输入法、录音与悬浮窗", R.drawable.ic_lucide_settings_2) { showPage(Page.PERMISSION_MANAGEMENT) }
        ))
        addView(managementTileRow(
            managementTile("关于", "开源协议与捐助", R.drawable.ic_lucide_circle_help) { showPage(Page.ABOUT) },
            managementTile("测试", "验证输入法与当前配置", R.drawable.ic_lucide_keyboard) { showPage(Page.TEST) }
        ), margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(156), top = 12))
    }

    private fun buildLayoutDisplay(): View = screen {
        addView(subpageHeader("布局和显示") { showPage(Page.ACCOUNT) })
        val themePreview = KeyboardSettingsPreview(this@MainActivity)
        addView(illustratedOptionCard("外观", themePreview) {
            val themes = KeyboardTheme.entries.toList()
            val themeControl = SegmentedControl(this@MainActivity, themes.map { it.displayName }, 0) { index ->
                lifecycleScope.launch {
                    val theme = themes[index]
                    themePreview.setTheme(theme)
                    container.settings.saveKeyboardTheme(theme)
                    applyKeyboardTheme(theme)
                }
            }
            addView(themeControl)
            lifecycleScope.launch {
                val theme = container.settings.keyboardTheme()
                themePreview.setTheme(theme)
                themeControl.setSelected(themes.indexOf(theme).coerceAtLeast(0))
            }
        })
        val candidatePreview = KeyboardSettingsPreview(this@MainActivity)
        addView(illustratedOptionCard("候选词大小", candidatePreview) {
            val levels = (-3..3).toList()
            val sizeControl = SegmentedControl(this@MainActivity, listOf("12", "14", "16", "默认", "20", "22", "24"), 0) { index ->
                candidatePreview.setCandidateLevel(levels[index])
                lifecycleScope.launch { container.settings.saveCandidateTextSizeLevel(levels[index]) }
            }
            addView(sizeControl)
            lifecycleScope.launch {
                val level = container.settings.candidateTextSizeLevel()
                candidatePreview.setCandidateLevel(level)
                sizeControl.setSelected(level + 3)
            }
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
    }

    private fun buildTestPage(): View = screen {
        addView(subpageHeader("测试") { showPage(Page.ACCOUNT) })
        addView(operationGuide("在文本框内验证当前输入法、候选词和听写结果", "当前保存的外观、字号、按键效果会直接应用"))
        addView(section("测试输入"))
        val input = EditText(this@MainActivity).apply {
            hint = "在这里试试拼音、英文、语音和润色"
            setTextColor(getColor(R.color.weike_text))
            setHintTextColor(getColor(R.color.weike_muted))
            textSize = 17f
            gravity = Gravity.TOP or Gravity.START
            minLines = 7
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = roundedBackground(getColor(R.color.weike_key), 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        addView(card().apply {
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)))
            addView(primaryButton("清空文本") { input.text.clear() }, margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(46), top = 12))
        })
    }

    private fun buildKeyEffects(): View = screen {
        addView(subpageHeader("按键效果") { showPage(Page.ACCOUNT) })
        addView(illustratedOptionCard("触感强度", operationGuide("选择适合自己的触感强度", "每次按键会按当前强度反馈")) {
            val haptics = HapticStrength.entries.toList()
            val hapticControl = SegmentedControl(this@MainActivity, listOf("无", "系统", "弱", "适中", "较强", "强"), 0) { index ->
                lifecycleScope.launch { container.settings.saveHapticStrength(haptics[index]) }
            }
            addView(hapticControl)
            lifecycleScope.launch { hapticControl.setSelected(haptics.indexOf(container.settings.hapticStrength()).coerceAtLeast(0)) }
        })
        addView(illustratedOptionCard("按键音量", operationGuide("拖动滑块调整按键音量", "拖到最左侧即可静音")) {
            val slider = VolumeSlider(this@MainActivity, 0.7f) { value, completed ->
                if (completed) lifecycleScope.launch { container.settings.saveKeyboardSoundVolume(value) }
            }
            addView(slider)
            lifecycleScope.launch { slider.setValue(container.settings.keyboardSoundVolume()) }
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
    }

    private fun buildAuxiliaryInput(): View = screen {
        addView(subpageHeader("辅助输入") { showPage(Page.ACCOUNT) })
        addView(illustratedOptionCard("标点习惯", operationGuide("选择听写与润色结果的标点规则", "设置会在下一次输出时生效")) {
            val punctuation = PunctuationPreference.entries.toList()
            val punctuationControl = SegmentedControl(this@MainActivity, punctuation.map { it.displayName }, 0) { index ->
                lifecycleScope.launch {
                    val preference = punctuation[index]
                    container.settings.savePunctuationPreference(preference)
                }
            }
            addView(punctuationControl)
            lifecycleScope.launch {
                val preference = container.settings.punctuationPreference()
                punctuationControl.setSelected(punctuation.indexOf(preference).coerceAtLeast(0))
            }
        })
        val autoCapitalize = BlueToggle(this@MainActivity, false) { checked ->
            lifecycleScope.launch { container.settings.saveEnglishAutoCapitalize(checked) }
        }
        addView(illustratedOptionCard("英文首字母自动大写", operationGuide("开启后，英文句首会自动使用大写字母"), autoCapitalize) {
            lifecycleScope.launch { autoCapitalize.setChecked(container.settings.englishAutoCapitalize()) }
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
        val doubleSpace = BlueToggle(this@MainActivity, false) { checked ->
            lifecycleScope.launch { container.settings.saveDoubleSpacePeriod(checked) }
        }
        addView(illustratedOptionCard("双击空格输入句号", operationGuide("连续双击文字键盘的空格键", "会输入一个句号并保留正常空格逻辑"), doubleSpace) {
            lifecycleScope.launch { doubleSpace.setChecked(container.settings.doubleSpacePeriod()) }
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
    }

    private fun buildToolbarSettings(): View = screen {
        addView(subpageHeader("定制工具栏") { showPage(Page.ACCOUNT) })
        addView(illustratedOptionCard("键盘模式", operationGuide("轻触开关显示或隐藏模式", "长按右侧拖动柄可调整模式顺序")) {
            keyboardModesList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(keyboardModesList)
        })
        renderKeyboardModeControls(latestKeyboardModes)
        val closeButton = BlueToggle(this@MainActivity, false) { checked ->
            lifecycleScope.launch { container.settings.saveKeyboardCloseButtonEnabled(checked) }
        }
        addView(illustratedOptionCard("显示收起键", operationGuide("开启后，键盘右上角会显示收起按钮"), closeButton) {
            lifecycleScope.launch { closeButton.setChecked(container.settings.keyboardCloseButtonEnabled()) }
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
    }

    private fun buildKeyboardManagement(): View = screen {
        addView(subpageHeader("键盘管理") { showPage(Page.ACCOUNT) })
        addView(illustratedOptionCard("中文主键盘", operationGuide("选择 26 键全键盘或九宫格拼音", "切换后在下一次打开键盘时应用")) {
            val layouts = ChineseKeyboardLayout.entries.toList()
            val layoutControl = SegmentedControl(this@MainActivity, layouts.map { it.displayName }, 0) { index ->
                lifecycleScope.launch {
                    val layout = layouts[index]
                    container.settings.saveChineseKeyboardLayout(layout)
                }
            }
            addView(layoutControl)
            lifecycleScope.launch {
                val layout = container.settings.chineseKeyboardLayout()
                layoutControl.setSelected(layouts.indexOf(layout).coerceAtLeast(0))
            }
        })
        addView(illustratedOptionCard("九宫格符号", operationGuide("点击符号可编辑", "长按右侧拖动柄可调整侧边栏顺序")) {
            nineKeySymbolsList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(nineKeySymbolsList)
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
        renderNineKeySymbolControls(latestNineKeySymbols)
    }

    private fun buildClipboardSettings(): View = screen {
        addView(subpageHeader("剪贴板") { showPage(Page.ACCOUNT) })
        val clipboard = BlueToggle(this@MainActivity, false) { checked ->
            lifecycleScope.launch { container.settings.saveClipboardHistoryEnabled(checked) }
        }
        addView(illustratedOptionCard(
            "剪贴板历史",
            operationGuide("复制的内容会显示在这里", "点击内容即可粘贴", "向左滑动可删除"),
            clipboard
        ) {
            lifecycleScope.launch { clipboard.setChecked(container.settings.clipboardHistoryEnabled()) }
        })
    }

    private fun buildLocalData(): View = screen {
        addView(subpageHeader("本机数据") { showPage(Page.ACCOUNT) })
        addView(illustratedOptionCard("历史记录", operationGuide("选择自动保留时长", "选择“从不”会立即清除已有历史")) {
            val options = HistoryRetention.entries.toList()
            val labels = listOf("从不", "24小时", "1周", "1个月", "永久")
            val control = SegmentedControl(this@MainActivity, labels, 0) { position ->
                lifecycleScope.launch {
                    val retention = options[position]
                    container.settings.saveHistoryRetention(retention)
                    if (retention == HistoryRetention.NEVER) container.inputHistory.deleteAll()
                }
            }
            addView(control)
            addDivider(this)
            addView(actionRow("删除全部历史记录", "", destructive = true) {
                lifecycleScope.launch { container.inputHistory.deleteAll() }
            })
            lifecycleScope.launch { control.setSelected(options.indexOf(container.settings.historyRetention()).coerceAtLeast(0)) }
        })
        addView(section("最近记录"))
        historyList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(historyList)
        renderHistory(latestHistory)
        addView(illustratedOptionCard("中文离线输入", operationGuide("候选与学习数据只保留在设备本地", "清除学习数据不会删除专业词和打字词典")) {
            addView(actionRow("离线拼音词典", LocalPinyinDecoder.DICTIONARY_VERSION) {})
            addDivider(this)
            addView(actionRow("清除候选学习数据", "") {
                sendBroadcast(Intent(WeikeInputMethodService.ACTION_CLEAR_RIME_LEARNING).setPackage(packageName))
            })
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
    }

    private fun buildOptimizeInput(): View = screen {
        addView(subpageHeader("优化输入") { showPage(Page.ACCOUNT) })
        addView(primaryButton("添加词条") { showAddDictionaryDialog() })
        addView(section("专业词库"))
        lexiconList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(lexiconList)
        renderLexicon(latestLexicon)
        addView(section("打字词典"))
        typingDictionaryList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(typingDictionaryList)
        renderTypingDictionary(latestTypingDictionary)
        addView(section("应用文风"))
        val packageInput = field("应用包名")
        val styles = WritingStyle.entries.toList()
        var selectedStyle = 0
        val styleControl = SegmentedControl(this@MainActivity, styles.map { it.displayName }, selectedStyle) { selectedStyle = it }
        addView(illustratedOptionCard("应用文风", operationGuide("输入应用包名后选择文风", "保存后，该应用的润色会使用指定文风")) {
            addView(packageInput)
            addView(styleControl)
            addView(primaryButton("保存应用文风") {
                lifecycleScope.launch {
                    container.settings.saveOverride(packageInput.text.toString(), styles[selectedStyle])
                    packageInput.text.clear()
                }
            })
        })
        overridesList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(overridesList)
        renderOverrides(latestOverrides)
        val optimizeExpression = BlueToggle(this@MainActivity, false) { checked ->
            lifecycleScope.launch { container.settings.saveExpressionOptimization(checked) }
        }
        addView(illustratedOptionCard("优化表达", operationGuide("开启后，分类和分点表达会自动整理", "仅影响润色，不改动手动输入内容"), optimizeExpression) {
            lifecycleScope.launch { optimizeExpression.setChecked(container.settings.expressionOptimizationEnabled()) }
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
    }

    private fun buildPermissionManagement(): View = screen {
        addView(subpageHeader("权限管理") { showPage(Page.ACCOUNT) })
        addView(illustratedOptionCard("输入法与系统权限", operationGuide("依次完成输入法、录音和悬浮窗授权", "系统页面授权后返回此处即可继续使用")) {
            addView(actionRow("启用维刻输入法", "") {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            })
            addDivider(this)
            addView(actionRow("切换输入法", "") {
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            })
            addDivider(this)
            addView(actionRow("录音权限", "") {
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            })
            addDivider(this)
            addView(actionRow("横屏悬浮窗", "") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
            })
        })
        microphoneStatus = TextView(this@MainActivity).apply {
            textSize = 15f
            layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 14)
        }
        addView(microphoneStatus)
    }

    private fun buildCloudConfiguration(): View = screen {
        addView(subpageHeader("语音与文本") { showPage(Page.ACCOUNT) })
        addView(section("ASR 接口"))
        addView(providerConfigurationCard(
            isAsr = true,
            load = { container.settings.cloudApiSettings.first().asr },
            loadProvider = { container.settings.asrProvider() },
            save = { config, provider -> container.settings.saveAsrApi(config, provider) },
            test = { config -> MimoAsrClient(endpointProvider = { config }).testConnection() }
        ))
        addView(section("文本模型"))
        addView(providerConfigurationCard(
            isAsr = false,
            load = { container.settings.cloudApiSettings.first().text },
            loadProvider = { container.settings.textProvider() },
            save = { config, provider -> container.settings.saveTextApi(config, provider) },
            test = { config -> MimoTextPolisher(endpointProvider = { config }).testConnection() }
        ))
    }

    private fun buildAbout(): View = screen {
        addView(subpageHeader("关于我们") { showPage(Page.ACCOUNT) })
        addView(card().apply {
            addView(TextView(this@MainActivity).apply {
                text = "维刻输入法"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.weike_text))
            })
            addView(TextView(this@MainActivity).apply {
                text = "开源的语音与离线输入法"
                textSize = 16f
                setTextColor(getColor(R.color.weike_muted))
                layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 6)
            })
        })
        addView(section("开源协议"))
        addView(card().apply {
            addView(actionRow("GNU GPL v3.0 或更高版本", "") { showLicenseDialog() })
            addDivider(this)
            addView(actionRow("第三方开源声明", "") { showThirdPartyNoticeDialog() })
        })
        addView(section("捐助我"))
        addView(card().apply {
            addView(TextView(this@MainActivity).apply {
                text = "如果你觉得软件有帮到你，欢迎在此捐助。捐助将用于词典维护、兼容测试和持续开发。"
                textSize = 17f
                setLineSpacing(dp(4).toFloat(), 1f)
                setTextColor(getColor(R.color.weike_text))
            })
            addView(ImageView(this@MainActivity).apply {
                contentDescription = "支付宝捐助二维码"
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(
                    assets.open("images/alipay-donation.jpg").use(BitmapFactory::decodeStream)
                )
                layoutParams = margins(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    top = 16
                )
            })
        })
    }

    private fun showLicenseDialog() = showLongTextDialog("GNU GPL v3.0", "licenses/GPL-3.0.txt")

    private fun showThirdPartyNoticeDialog() = showLongTextDialog("第三方开源声明", "licenses/THIRD_PARTY_NOTICES.txt")

    private fun showLongTextDialog(title: String, asset: String) {
        val content = runCatching {
            assets.open(asset).bufferedReader().use { it.readText() }
        }.getOrDefault("协议文件不可用")
        val body = ScrollView(this).apply {
            addView(TextView(this@MainActivity).apply {
                text = content
                textSize = 13f
                setTextColor(getColor(R.color.weike_text))
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(dp(24), dp(12), dp(24), dp(12))
            })
        }
        AlertDialog.Builder(this).setTitle(title).setView(body).setPositiveButton("关闭", null).show()
    }

    private fun providerConfigurationCard(
        isAsr: Boolean,
        load: suspend () -> ModelEndpointConfig,
        loadProvider: suspend () -> CloudProvider,
        save: suspend (ModelEndpointConfig, CloudProvider) -> Unit,
        test: suspend (ModelEndpointConfig) -> Result<Unit>
    ) = card().apply {
        val url = field("接口地址")
        val apiKey = field("接口密钥").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val model = field("模型名")
        var selectedProvider = CloudProvider.CUSTOM
        var previousCustomUrl = ""
        val officialEndpoint = TextView(this@MainActivity).apply {
            textSize = 13f
            setTextColor(getColor(R.color.weike_muted))
            setPadding(dp(2), 0, dp(2), dp(8))
            visibility = View.GONE
        }

        fun currentConfig() = ModelEndpointConfig(
            url = url.text.toString(),
            apiKey = apiKey.text.toString(),
            model = model.text.toString()
        )

        fun applyProvider(provider: CloudProvider, replaceWithPreset: Boolean) {
            selectedProvider = provider
            val preset = cloudProviderPreset(provider, isAsr)
            val builtIn = provider != CloudProvider.CUSTOM
            url.visibility = if (builtIn) View.GONE else View.VISIBLE
            officialEndpoint.visibility = if (builtIn) View.VISIBLE else View.GONE
            if (builtIn) {
                previousCustomUrl = url.text.toString().takeIf { it.isNotBlank() } ?: previousCustomUrl
                officialEndpoint.text = "官方接口：${preset.url}"
                if (replaceWithPreset) {
                    url.setText(preset.url)
                    model.setText(preset.model)
                }
            } else if (replaceWithPreset && url.text.isBlank()) {
                url.setText(previousCustomUrl)
            }
        }

        val picker = CloudProviderDropdown(this@MainActivity, selectedProvider) { provider ->
            applyProvider(provider, replaceWithPreset = true)
        }

        lateinit var readModelsButton: Button
        readModelsButton = formActionButton("读取模型", false) {
            val config = currentConfig()
            val validationError = cloudEndpointKeyValidationError(config)
            if (validationError != null) {
                Toast.makeText(this@MainActivity, validationError, Toast.LENGTH_SHORT).show()
                return@formActionButton
            }
            readModelsButton.isEnabled = false
            readModelsButton.text = "读取中"
            lifecycleScope.launch {
                val result = ModelCatalog().list(config)
                readModelsButton.isEnabled = true
                readModelsButton.text = "读取模型"
                result.onSuccess { models ->
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("选择模型")
                        .setItems(models.toTypedArray()) { _, index -> model.setText(models[index]) }
                        .show()
                }.onFailure { error ->
                    Toast.makeText(this@MainActivity, error.message ?: "读取模型失败", Toast.LENGTH_LONG).show()
                }
            }
        }
        lateinit var testButton: Button
        testButton = formActionButton("测试连接", false) {
            val config = currentConfig()
            val validationError = cloudConfigValidationError(config)
            if (validationError != null) {
                Toast.makeText(this@MainActivity, validationError, Toast.LENGTH_SHORT).show()
                return@formActionButton
            }
            testButton.isEnabled = false
            testButton.text = "测试中"
            lifecycleScope.launch {
                val result = test(config)
                testButton.isEnabled = true
                testButton.text = "测试连接"
                Toast.makeText(
                    this@MainActivity,
                    if (result.isSuccess) "连接成功" else result.exceptionOrNull()?.message ?: "连接失败",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        val saveButton = formActionButton("保存", true) {
            val config = currentConfig()
            val validationError = cloudConfigValidationError(config)
            if (validationError != null) {
                Toast.makeText(this@MainActivity, validationError, Toast.LENGTH_SHORT).show()
                return@formActionButton
            }
            lifecycleScope.launch {
                save(config, selectedProvider)
                Toast.makeText(this@MainActivity, "已保存", Toast.LENGTH_SHORT).show()
            }
        }
        addView(picker, margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), bottom = 12))
        addView(officialEndpoint)
        addView(url)
        addView(apiKey)
        addView(model)
        addView(readModelsButton, margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), bottom = 8))
        addView(LinearLayout(this@MainActivity).apply {
            addView(testButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
            addView(saveButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        })
        lifecycleScope.launch {
            val provider = loadProvider()
            load().also { config ->
                val resolvedProvider = inferCloudProvider(provider, config.url)
                picker.setSelected(resolvedProvider)
                applyProvider(resolvedProvider, replaceWithPreset = false)
                url.setText(config.url)
                apiKey.setText(config.apiKey)
                model.setText(config.model)
            }
        }
    }

    private fun cloudProviderPreset(provider: CloudProvider, isAsr: Boolean): ModelEndpointConfig = when (provider) {
        CloudProvider.XIAOMI_MIMO -> ModelEndpointConfig(
            url = "https://api.xiaomimimo.com/v1",
            model = if (isAsr) "MiMo-V2.5-ASR" else "MiMo-V2.5"
        )
        CloudProvider.XIAOMI_MIMO_PLAN -> ModelEndpointConfig(
            url = "https://token-plan-cn.xiaomimimo.com/v1",
            model = if (isAsr) "MiMo-V2.5-ASR" else "MiMo-V2.5"
        )
        CloudProvider.CUSTOM -> ModelEndpointConfig()
    }

    private fun inferCloudProvider(saved: CloudProvider, url: String): CloudProvider = when {
        saved != CloudProvider.CUSTOM -> saved
        url.contains("token-plan-cn.xiaomimimo.com", ignoreCase = true) -> CloudProvider.XIAOMI_MIMO_PLAN
        url.contains("api.xiaomimimo.com", ignoreCase = true) -> CloudProvider.XIAOMI_MIMO
        else -> CloudProvider.CUSTOM
    }

    private fun cloudConfigValidationError(config: ModelEndpointConfig): String? = runCatching {
        require(config.isComplete()) { "请完整填写接口信息" }
        require(config.apiKey.length <= 512 && config.apiKey.none(Char::isWhitespace)) { "接口密钥格式无效" }
        require(config.model.length <= 128 && config.model.none(Char::isWhitespace)) { "模型名称格式无效" }
        MimoApiConfig.chatCompletionsEndpoint(config.url)
    }.exceptionOrNull()?.message

    private fun cloudEndpointKeyValidationError(config: ModelEndpointConfig): String? = runCatching {
        require(config.url.isNotBlank() && config.apiKey.isNotBlank()) { "请先填写接口地址和接口密钥" }
        require(config.apiKey.length <= 512 && config.apiKey.none(Char::isWhitespace)) { "接口密钥格式无效" }
        MimoApiConfig.modelsEndpoint(config.url)
    }.exceptionOrNull()?.message

    private fun buildSettings(): View = screen {
        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_lucide_chevron_left)
                setColorFilter(getColor(R.color.weike_text))
                setPadding(dp(14), dp(14), dp(14), dp(14))
                contentDescription = "返回"
                setOnClickListener { showPage(Page.ACCOUNT) }
            }, LinearLayout.LayoutParams(dp(52), dp(64)))
            addView(TextView(this@MainActivity).apply {
                text = "设置"
                textSize = 28f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.weike_text))
            }, LinearLayout.LayoutParams(0, dp(64), 1f))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(52), dp(64)))
        })
        addView(section("键盘"))
        addView(card().apply {
            val themes = KeyboardTheme.entries.toList()
            val themeControl = SegmentedControl(this@MainActivity, themes.map { it.displayName }, 0) { index ->
                lifecycleScope.launch {
                    val theme = themes[index]
                    container.settings.saveKeyboardTheme(theme)
                    applyKeyboardTheme(theme)
                }
            }
            addView(TextView(this@MainActivity).apply { text = "外观"; textSize = 18f; setTextColor(getColor(R.color.weike_text)) })
            addView(subtitle("暗黑、亮色或跟随系统"))
            addView(themeControl)
            lifecycleScope.launch { themeControl.setSelected(themes.indexOf(container.settings.keyboardTheme()).coerceAtLeast(0)) }
            addDivider(this)
            val punctuation = PunctuationPreference.entries.toList()
            val punctuationControl = SegmentedControl(this@MainActivity, punctuation.map { it.displayName }, 0) { index ->
                lifecycleScope.launch { container.settings.savePunctuationPreference(punctuation[index]) }
            }
            addView(TextView(this@MainActivity).apply { text = "标点习惯"; textSize = 18f; setTextColor(getColor(R.color.weike_text)); layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 14) })
            addView(subtitle("跟随听写与润色"))
            addView(punctuationControl)
            lifecycleScope.launch { punctuationControl.setSelected(punctuation.indexOf(container.settings.punctuationPreference()).coerceAtLeast(0)) }
            addDivider(this)
            val haptics = HapticStrength.entries.toList()
            val hapticControl = SegmentedControl(this@MainActivity, listOf("无", "系统", "弱", "中", "较强", "强"), 0) { index ->
                lifecycleScope.launch { container.settings.saveHapticStrength(haptics[index]) }
            }
            addView(TextView(this@MainActivity).apply { text = "触感反馈"; textSize = 18f; setTextColor(getColor(R.color.weike_text)); layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 14) })
            addView(subtitle("小米系统触感引擎"))
            addView(hapticControl)
            lifecycleScope.launch { hapticControl.setSelected(haptics.indexOf(container.settings.hapticStrength()).coerceAtLeast(0)) }
            addDivider(this)
            val closeButtonControl = SegmentedControl(this@MainActivity, listOf("关闭", "显示"), 0) { index ->
                lifecycleScope.launch { container.settings.saveKeyboardCloseButtonEnabled(index == 1) }
            }
            addView(TextView(this@MainActivity).apply {
                text = "收起键"
                textSize = 18f
                setTextColor(getColor(R.color.weike_text))
                layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 14)
            })
            addView(closeButtonControl)
            lifecycleScope.launch {
                closeButtonControl.setSelected(if (container.settings.keyboardCloseButtonEnabled()) 1 else 0)
            }
            addDivider(this)
            val candidateSizeLevels = (-3..3).toList()
            val candidateSizeControl = SegmentedControl(
                this@MainActivity,
                listOf("12", "14", "16", "默认", "20", "22", "24"),
                0
            ) { index ->
                lifecycleScope.launch { container.settings.saveCandidateTextSizeLevel(candidateSizeLevels[index]) }
            }
            addView(TextView(this@MainActivity).apply {
                text = "候选词大小"
                textSize = 18f
                setTextColor(getColor(R.color.weike_text))
                layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 14)
            })
            addView(candidateSizeControl)
            lifecycleScope.launch {
                candidateSizeControl.setSelected(container.settings.candidateTextSizeLevel() + 3)
            }
        })
        addView(section("按键音效"))
        addView(card().apply {
            addView(TextView(this@MainActivity).apply { text = "按键音量"; textSize = 18f; setTextColor(getColor(R.color.weike_text)) })
            val slider = VolumeSlider(this@MainActivity, 0.7f) { value, completed ->
                if (completed) lifecycleScope.launch { container.settings.saveKeyboardSoundVolume(value) }
            }
            addView(slider)
            lifecycleScope.launch { slider.setValue(container.settings.keyboardSoundVolume()) }
        })
        addView(section("键盘模式"))
        keyboardModesList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(keyboardModesList)
        renderKeyboardModeControls(latestKeyboardModes)
        addView(card().apply {
            val layouts = ChineseKeyboardLayout.entries.toList()
            val layoutControl = SegmentedControl(this@MainActivity, layouts.map { it.displayName }, 0) { index ->
                lifecycleScope.launch { container.settings.saveChineseKeyboardLayout(layouts[index]) }
            }
            addView(TextView(this@MainActivity).apply {
                text = "中文主键盘"
                textSize = 18f
                setTextColor(getColor(R.color.weike_text))
            })
            addView(layoutControl)
            lifecycleScope.launch {
                layoutControl.setSelected(layouts.indexOf(container.settings.chineseKeyboardLayout()).coerceAtLeast(0))
            }
        })
        addView(section("英文键盘"))
        addView(card().apply {
            val autoCapitalizeControl = SegmentedControl(this@MainActivity, listOf("关闭", "开启"), 0) { index ->
                lifecycleScope.launch { container.settings.saveEnglishAutoCapitalize(index == 1) }
            }
            addView(TextView(this@MainActivity).apply {
                text = "首字母自动大写"
                textSize = 18f
                setTextColor(getColor(R.color.weike_text))
            })
            addView(autoCapitalizeControl)
            lifecycleScope.launch {
                autoCapitalizeControl.setSelected(if (container.settings.englishAutoCapitalize()) 1 else 0)
            }
            addDivider(this)
            val doubleSpaceControl = SegmentedControl(this@MainActivity, listOf("关闭", "开启"), 0) { index ->
                lifecycleScope.launch { container.settings.saveDoubleSpacePeriod(index == 1) }
            }
            addView(TextView(this@MainActivity).apply {
                text = "双击空格输入句号"
                textSize = 18f
                setTextColor(getColor(R.color.weike_text))
                layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 14)
            })
            addView(doubleSpaceControl)
            lifecycleScope.launch {
                doubleSpaceControl.setSelected(if (container.settings.doubleSpacePeriod()) 1 else 0)
            }
        })
        addView(section("九宫格符号"))
        nineKeySymbolsList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(nineKeySymbolsList)
        renderNineKeySymbolControls(latestNineKeySymbols)
        addView(section("剪贴板"))
        addView(card().apply {
            val clipboardControl = SegmentedControl(this@MainActivity, listOf("关闭", "开启"), 0) { index ->
                lifecycleScope.launch { container.settings.saveClipboardHistoryEnabled(index == 1) }
            }
            addView(TextView(this@MainActivity).apply {
                text = "剪贴板历史"
                textSize = 18f
                setTextColor(getColor(R.color.weike_text))
            })
            addView(clipboardControl)
            lifecycleScope.launch {
                clipboardControl.setSelected(if (container.settings.clipboardHistoryEnabled()) 1 else 0)
            }
        })
        addView(section("应用文风"))
        val packageInput = field("应用包名")
        val styles = WritingStyle.entries.toList()
        var selectedStyle = 0
        val styleControl = SegmentedControl(this@MainActivity, styles.map { it.displayName }, selectedStyle) { selectedStyle = it }
        addView(card().apply {
            addView(packageInput)
            addView(styleControl)
            addView(primaryButton("保存应用文风") {
                lifecycleScope.launch {
                    container.settings.saveOverride(packageInput.text.toString(), styles[selectedStyle])
                    packageInput.text.clear()
                }
            })
        })
        overridesList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(overridesList)
        renderOverrides(latestOverrides)
        addView(section("优化表达"))
        addView(card().apply {
            addView(Switch(this@MainActivity).apply {
                text = "启用结构化表达优化"
                textSize = 17f
                setTextColor(getColor(R.color.weike_text))
                lifecycleScope.launch { isChecked = container.settings.expressionOptimizationEnabled() }
                setOnCheckedChangeListener { _, checked ->
                    lifecycleScope.launch { container.settings.saveExpressionOptimization(checked) }
                }
            })
        })
        addView(section("中文离线输入"))
        addView(card().apply {
            addView(actionRow("离线拼音词典", LocalPinyinDecoder.DICTIONARY_VERSION) {})
            addDivider(this)
            addView(actionRow("清除候选学习数据", "保留专业词与打字词典") {
                sendBroadcast(Intent(WeikeInputMethodService.ACTION_CLEAR_RIME_LEARNING).setPackage(packageName))
            })
        })
        addView(section("云端连接"))
        addView(card().apply {
            addView(actionRow("语音与文本接口配置", "") { showPage(Page.CLOUD) })
        })
    }

    private fun subpageHeader(title: String, back: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_lucide_chevron_left)
            setColorFilter(getColor(R.color.weike_text))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            contentDescription = "返回"
            setOnClickListener { back() }
        }, LinearLayout.LayoutParams(dp(52), dp(64)))
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.weike_text))
        }, LinearLayout.LayoutParams(0, dp(64), 1f))
        addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(52), dp(64)))
    }

    private fun dictionaryEditor(
        wordHint: String,
        pronunciationHint: String,
        action: String,
        save: suspend (String, String) -> Unit
    ) = card().apply {
        val word = field(wordHint)
        val hint = field(pronunciationHint)
        addView(word)
        addView(hint)
        addView(primaryButton(action) {
            val value = word.text.toString().trim()
            if (value.isNotBlank()) lifecycleScope.launch {
                save(value, hint.text.toString().trim())
                word.text.clear()
                hint.text.clear()
            }
        })
    }

    private fun showAddDictionaryDialog() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val type = settingsSpinner(listOf("专业词库", "打字词典"))
        val word = field("单词或词语")
        val hint = field("拼音或备注（可选）")
        body.addView(type)
        body.addView(word)
        body.addView(hint)
        AlertDialog.Builder(this)
            .setTitle("添加词条")
            .setView(body)
            .setNegativeButton("取消", null)
            .setPositiveButton("添加") { _, _ ->
                val value = word.text.toString().trim()
                if (value.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    if (type.selectedItemPosition == 0) container.lexicon.upsert(LexiconTerm(value, hint.text.toString().trim()))
                    else container.typingDictionary.upsert(TypingDictionaryEntry(value, hint.text.toString().trim()))
                }
            }
            .show()
    }

    private fun observeData() {
        lifecycleScope.launch {
            container.usageStats.observe().collectLatest {
                latestStats = it ?: UsageStats()
                renderStats(latestStats)
            }
        }
        lifecycleScope.launch {
            container.inputHistory.observeRecent().collectLatest { entries ->
                latestHistory = entries
                renderHistory(entries)
            }
        }
        lifecycleScope.launch {
            container.lexicon.observeAll().collectLatest { entries ->
                latestLexicon = entries
                renderLexicon(entries)
            }
        }
        lifecycleScope.launch {
            container.typingDictionary.observeAll().collectLatest { entries ->
                latestTypingDictionary = entries
                renderTypingDictionary(entries)
            }
        }
        lifecycleScope.launch {
            container.settings.overrides.collectLatest { overrides ->
                latestOverrides = overrides
                renderOverrides(overrides)
            }
        }
        lifecycleScope.launch {
            container.settings.keyboardModes.collectLatest {
                latestKeyboardModes = it
                renderKeyboardModeControls(it)
            }
        }
        lifecycleScope.launch {
            container.settings.nineKeySymbols.collectLatest {
                latestNineKeySymbols = it
                renderNineKeySymbolControls(it)
            }
        }
    }

    private fun renderStats(stats: UsageStats) {
        val minutes = stats.dictationDurationMs / 60_000.0
        val saved = (stats.dictationUnits / 30.0).roundToInt()
        val speed = if (minutes > 0.01) (stats.dictationUnits / minutes).roundToInt() else 0
        statMinutes?.text = "${String.format(java.util.Locale.CHINA, "%.1f", minutes)} min"
        statWords?.text = "${stats.dictationUnits} 字"
        statSaved?.text = "$saved min"
        statSpeed?.text = "$speed 字/分钟"
    }

    private fun renderHistory(entries: List<InputHistory>) {
        val host = historyList ?: return
        host.removeAllViews()
        if (entries.isEmpty()) host.addView(subtitle("暂无保留的历史记录"))
        entries.forEach { entry ->
            val type = runCatching { InputHistoryType.valueOf(entry.type) }.getOrDefault(InputHistoryType.DICTATION)
            val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(entry.createdAt))
            val text = if (type == InputHistoryType.QUESTION && entry.question.isNotBlank()) {
                "${type.displayName} · $time\n问：${entry.question}\n答：${entry.content}"
            } else "${type.displayName} · $time\n${entry.content}"
            host.addView(historyCard(text))
        }
    }

    private fun renderLexicon(entries: List<LexiconTerm>) {
        val host = lexiconList ?: return
        host.removeAllViews()
        entries.forEach { entry -> host.addView(itemCard("${entry.term}${if (entry.hint.isBlank()) "" else " · ${entry.hint}"}") {
            lifecycleScope.launch { container.lexicon.delete(entry.term) }
        }) }
    }

    private fun renderTypingDictionary(entries: List<TypingDictionaryEntry>) {
        val host = typingDictionaryList ?: return
        host.removeAllViews()
        entries.forEach { entry -> host.addView(itemCard("${entry.term}${if (entry.hint.isBlank()) "" else " · ${entry.hint}"}") {
            lifecycleScope.launch { container.typingDictionary.delete(entry.term) }
        }) }
    }

    private fun renderOverrides(overrides: Map<String, WritingStyle>) {
        val host = overridesList ?: return
        host.removeAllViews()
        overrides.entries.sortedBy { it.key }.forEach { (name, style) -> host.addView(itemCard("$name · ${style.displayName}") {
            lifecycleScope.launch { container.settings.removeOverride(name) }
        }) }
    }

    private fun renderKeyboardModeControls(order: List<KeyboardModePreference>) {
        val host = keyboardModesList ?: return
        host.removeAllViews()
        host.setOnDragListener { _, event ->
            fun targetIndex(): Int {
                for (index in 0 until order.size) {
                    val child = host.getChildAt(index) ?: continue
                    if (event.y < child.top + child.height / 2f) return index
                }
                return order.size
            }
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                    val target = targetIndex().coerceIn(0, (order.size - 1).coerceAtLeast(0))
                    setDragHighlight(host.getChildAt(target))
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    setDragHighlight(null)
                    true
                }
                DragEvent.ACTION_DROP -> {
                    val moving = event.localState as? KeyboardModePreference ?: return@setOnDragListener false
                    val next = order.toMutableList()
                    val from = next.indexOf(moving)
                    if (from < 0) return@setOnDragListener false
                    next.removeAt(from)
                    next.add(targetIndex().coerceIn(0, next.size), moving)
                    setDragHighlight(null)
                    lifecycleScope.launch { container.settings.saveKeyboardModes(next) }
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    dragSource?.animate()?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.setDuration(140)?.start()
                    dragSource = null
                    setDragHighlight(null)
                    true
                }
                else -> true
            }
        }
        val enabled = order.toSet()
        (order + KeyboardModePreference.entries.filter { it !in enabled }).forEach { preference ->
            host.addView(card(top = 5).apply {
                val row = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(this@MainActivity).apply {
                    text = preference.displayName
                    textSize = 18f
                    setTextColor(getColor(R.color.weike_text))
                    gravity = Gravity.CENTER_VERTICAL
                }, LinearLayout.LayoutParams(0, dp(50), 1f))
                lateinit var toggle: BlueToggle
                toggle = BlueToggle(this@MainActivity, preference in enabled) { checked ->
                    val next = order.toMutableList()
                    if (checked && preference !in next) next += preference
                    if (!checked) next.remove(preference)
                    if (next.isEmpty()) {
                        toggle.setChecked(true)
                        Toast.makeText(this@MainActivity, "至少保留一个键盘模式", Toast.LENGTH_SHORT).show()
                    } else lifecycleScope.launch { container.settings.saveKeyboardModes(next) }
                }
                row.addView(toggle, LinearLayout.LayoutParams(dp(54), dp(34)))
                if (preference in enabled) {
                    row.addView(ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_lucide_grip_vertical)
                        setColorFilter(getColor(R.color.weike_muted))
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                        setOnLongClickListener {
                            dragSource = this
                            animate().alpha(0.55f).scaleX(0.92f).scaleY(0.92f).setDuration(120).start()
                            startDragAndDrop(
                                ClipData.newPlainText("keyboard_mode", preference.name),
                                View.DragShadowBuilder(this),
                                preference,
                                0
                            )
                            true
                        }
                    }, LinearLayout.LayoutParams(dp(44), dp(50)))
                }
                addView(row)
            })
        }
    }

    private fun renderNineKeySymbolControls(symbols: List<String>) {
        val host = nineKeySymbolsList ?: return
        host.removeAllViews()
        host.setOnDragListener { _, event ->
            fun targetIndex(): Int {
                for (index in symbols.indices) {
                    val child = host.getChildAt(index) ?: continue
                    if (event.y < child.top + child.height / 2f) return index
                }
                return symbols.size
            }
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                    setDragHighlight(host.getChildAt(targetIndex().coerceIn(0, (symbols.size - 1).coerceAtLeast(0))))
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> { setDragHighlight(null); true }
                DragEvent.ACTION_DROP -> {
                    val moving = event.localState as? String ?: return@setOnDragListener false
                    val next = symbols.toMutableList()
                    val from = next.indexOf(moving)
                    if (from < 0) return@setOnDragListener false
                    next.removeAt(from)
                    next.add(targetIndex().coerceIn(0, next.size), moving)
                    setDragHighlight(null)
                    lifecycleScope.launch { container.settings.saveNineKeySymbols(next) }
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    dragSource?.animate()?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.setDuration(140)?.start()
                    dragSource = null
                    setDragHighlight(null)
                    true
                }
                else -> true
            }
        }
        symbols.forEach { symbol ->
            host.addView(card(top = 5).apply {
                val row = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(this@MainActivity).apply {
                    text = symbol
                    textSize = 22f
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(getColor(R.color.weike_text))
                    setOnClickListener { showNineKeySymbolEditor(symbol) }
                }, LinearLayout.LayoutParams(0, dp(50), 1f))
                row.addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_lucide_grip_vertical)
                    setColorFilter(getColor(R.color.weike_muted))
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    setOnLongClickListener {
                        dragSource = this
                        animate().alpha(0.55f).scaleX(0.92f).scaleY(0.92f).setDuration(120).start()
                        startDragAndDrop(ClipData.newPlainText("nine_key_symbol", symbol), View.DragShadowBuilder(this), symbol, 0)
                        true
                    }
                }, LinearLayout.LayoutParams(dp(44), dp(50)))
                addView(row)
            })
        }
        host.addView(primaryButton("添加符号") { showNineKeySymbolEditor() })
    }

    private fun showNineKeySymbolEditor(existing: String? = null) {
        val input = field("输入一个符号").apply { setText(existing.orEmpty()); setSelection(text.length) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加九宫格符号" else "编辑九宫格符号")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) lifecycleScope.launch {
                    val next = latestNineKeySymbols.toMutableList()
                    if (existing != null) next[next.indexOf(existing)] = value else next += value
                    container.settings.saveNineKeySymbols(next)
                }
            }
        if (existing != null) dialog.setNeutralButton("删除") { _, _ ->
            lifecycleScope.launch {
                    container.settings.saveNineKeySymbols(latestNineKeySymbols.filterNot { it == existing })
            }
        }
        dialog.show()
    }

    private fun setDragHighlight(target: View?) {
        if (dragHighlight === target) return
        dragHighlight?.animate()?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.translationY(0f)?.setDuration(120)?.start()
        dragHighlight = target
        target?.animate()?.alpha(0.82f)?.scaleX(1.015f)?.scaleY(1.015f)?.translationY(dp(2).toFloat())?.setDuration(120)?.start()
    }

    private fun updatePermissionStatus() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphoneStatus?.text = if (granted) "麦克风权限：已授权" else "麦克风权限：未授权"
        microphoneStatus?.setTextColor(getColor(if (granted) R.color.weike_accent else R.color.weike_muted))
    }

    private fun applyKeyboardTheme(theme: KeyboardTheme) {
        val mode = when (theme) {
            KeyboardTheme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            KeyboardTheme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            KeyboardTheme.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun managementBrandHeader() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.app_icon)
            background = roundedBackground(Color.TRANSPARENT, 16)
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { topMargin = dp(10) })
        addView(TextView(this@MainActivity).apply {
            text = "维刻输入法"
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.weike_text))
            layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 10)
        })
        addView(TextView(this@MainActivity).apply {
            text = "本地优先，语音与离线输入"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.weike_muted))
            layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 3)
        })
    }

    private fun managementPreview() = FrameLayout(this).apply {
        background = roundedBackground(Color.rgb(232, 248, 244), 24)
        setPadding(dp(18), dp(18), dp(18), dp(16))
        addView(TextView(this@MainActivity).apply {
            text = "维刻输入体验"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.weike_text))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(TextView(this@MainActivity).apply {
            text = "语音、拼音与英文键盘"
            textSize = 14f
            setTextColor(getColor(R.color.weike_muted))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(28)
        })
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_lucide_audio_lines)
            setColorFilter(pagePrimary)
            background = circleBackground(Color.WHITE)
            setPadding(dp(11), dp(11), dp(11), dp(11))
        }, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.END))
        addView(keyboardPreviewPlaceholder(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(98), Gravity.BOTTOM))
    }

    private fun keyboardPreviewPlaceholder() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = roundedBackground(Color.argb(92, 255, 255, 255), 16)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        listOf(10, 9, 7).forEachIndexed { row, count ->
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER
                repeat(count) {
                    addView(View(this@MainActivity).apply {
                        background = roundedBackground(if (row == 2 && it == 0) Color.rgb(205, 230, 222) else Color.WHITE, 5)
                    }, LinearLayout.LayoutParams(0, dp(15), 1f).apply {
                        if (it < count - 1) marginEnd = dp(4)
                    })
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(19)))
        }
    }

    private fun managementTileRow(left: View, right: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, LinearLayout.LayoutParams(0, dp(156), 1f).apply { marginEnd = dp(12) })
        addView(right, LinearLayout.LayoutParams(0, dp(156), 1f))
    }

    private fun managementSingleTile(tile: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(tile, LinearLayout.LayoutParams(0, dp(156), 1f).apply { marginEnd = dp(12) })
        addView(View(this@MainActivity), LinearLayout.LayoutParams(0, dp(156), 1f))
    }

    private fun managementTile(title: String, detail: String, icon: Int, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBackground(getColor(R.color.weike_panel), 18)
        setPadding(dp(15), dp(14), dp(15), dp(13))
        val header = FrameLayout(this@MainActivity)
        header.addView(ImageView(this@MainActivity).apply {
            setImageResource(icon)
            setColorFilter(Color.argb(204, 0, 55, 85))
            background = roundedBackground(Color.argb(51, 0, 55, 85), 9)
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.START or Gravity.TOP))
        header.addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_lucide_chevron_right)
            setColorFilter(getColor(R.color.weike_muted))
        }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.END or Gravity.CENTER_VERTICAL))
        addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.weike_text))
            layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 10)
        })
        addView(TextView(this@MainActivity).apply {
            text = detail
            textSize = 13f
            maxLines = 2
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(getColor(R.color.weike_muted))
            layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 3)
        })
        setOnClickListener { action() }
    }

    private fun screen(children: LinearLayout.() -> Unit): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(getColor(R.color.weike_background))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(38), dp(20), dp(28))
            children()
        })
    }

    private fun brandTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 32f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(getColor(R.color.weike_text))
    }

    private fun section(value: String) = TextView(this).apply {
        text = value
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(getColor(R.color.weike_text))
        layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 28, bottom = 10)
    }

    private fun subtitle(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(getColor(R.color.weike_muted))
        setLineSpacing(dp(3).toFloat(), 1f)
        layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 8)
        visibility = View.GONE
    }

    private fun card(top: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBackground(getColor(R.color.weike_panel), 24)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = top)
    }

    private fun illustratedOptionCard(
        title: String,
        guide: View? = null,
        trailing: View? = null,
        content: LinearLayout.() -> Unit = {}
    ) = card().apply {
        guide?.let {
            val guideHeight = it.layoutParams?.height?.takeIf { height -> height > 0 }
                ?: ViewGroup.LayoutParams.WRAP_CONTENT
            addView(it, margins(ViewGroup.LayoutParams.MATCH_PARENT, guideHeight, bottom = 12))
        }
        val titleRow = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.weike_text))
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        trailing?.let { titleRow.addView(it, LinearLayout.LayoutParams(dp(54), dp(34))) }
        addView(titleRow)
        content()
    }

    private fun operationGuide(vararg steps: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(2), 0, dp(2))
        addView(View(this@MainActivity).apply {
            background = roundedBackground(Color.rgb(18, 95, 142), 2)
        }, LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(10) })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            steps.filter { it.isNotBlank() }.forEachIndexed { index, value ->
                addView(TextView(this@MainActivity).apply {
                    text = value
                    textSize = if (index == 0) 14f else 13f
                    typeface = if (index == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(if (index == 0) getColor(R.color.weike_text) else getColor(R.color.weike_muted))
                    setLineSpacing(dp(2).toFloat(), 1f)
                }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, bottom = if (index == steps.lastIndex) 0 else 4))
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun actionRow(title: String, detail: String, destructive: Boolean = false, action: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 18f
                setTextColor(getColor(if (destructive) android.R.color.holo_red_dark else R.color.weike_text))
            })
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_lucide_chevron_right)
            setColorFilter(getColor(R.color.weike_muted))
            setPadding(dp(7), dp(12), dp(7), dp(12))
            contentDescription = "进入"
        }, LinearLayout.LayoutParams(dp(34), dp(48)))
        setOnClickListener { action() }
    }

    private fun settingRow(title: String, detail: String, control: View) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply { text = title; textSize = 18f; setTextColor(getColor(R.color.weike_text)) })
            addView(TextView(this@MainActivity).apply { text = detail; textSize = 13f; setTextColor(getColor(R.color.weike_muted)) })
        }, LinearLayout.LayoutParams(0, dp(62), 1f))
        addView(control, LinearLayout.LayoutParams(dp(150), dp(54)))
    }

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint
        setSingleLine(true)
        textSize = 16f
        setTextColor(getColor(R.color.weike_text))
        setHintTextColor(getColor(R.color.weike_muted))
        background = roundedBackground(getColor(R.color.weike_key), 12)
        setPadding(dp(12), 0, dp(12), 0)
        layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), bottom = 8)
    }

    private fun settingsSpinner(options: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
        setPadding(0, 0, 0, 0)
    }

    private fun historyCard(value: String) = TextView(this).apply {
        text = value
        textSize = 17f
        setLineSpacing(dp(4).toFloat(), 1f)
        setTextColor(getColor(R.color.weike_text))
        background = roundedBackground(getColor(R.color.weike_panel), 22)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 8)
    }

    private fun statsRow(left: View, right: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, LinearLayout.LayoutParams(0, dp(108), 1f))
        addView(right, LinearLayout.LayoutParams(0, dp(108), 1f))
    }

    private fun statCell(label: String, icon: Int, bind: (TextView) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(this@MainActivity).apply {
            setImageResource(icon)
            setColorFilter(getColor(R.color.weike_muted))
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        val value = TextView(this@MainActivity).apply {
            text = "0"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.weike_text))
        }
        bind(value)
        addView(value, margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 8))
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 14f
            setTextColor(getColor(R.color.weike_muted))
            layoutParams = margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 5)
        })
    }

    private fun itemCard(value: String, remove: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = roundedBackground(getColor(R.color.weike_panel), 18)
        setPadding(dp(16), dp(7), dp(8), dp(7))
        addView(TextView(this@MainActivity).apply {
            text = value
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(getColor(R.color.weike_text))
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        addView(smallButton("删除", remove))
        layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 7)
    }

    private fun smallButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(getColor(R.color.weike_text))
        background = roundedBackground(getColor(R.color.weike_key), 12)
        minWidth = 0
        minimumWidth = 0
        setOnClickListener { action() }
    }

    private fun primaryButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        background = roundedBackground(pagePrimary, 24)
        layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), top = 4)
        setOnClickListener { action() }
    }

    private fun formActionButton(label: String, filled: Boolean, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(if (filled) Color.WHITE else pagePrimary)
        background = roundedBackground(if (filled) pagePrimary else getColor(R.color.weike_key), 18)
        minWidth = 0
        minimumWidth = 0
        setOnClickListener { action() }
    }

    private fun addDivider(parent: LinearLayout) {
        parent.addView(View(this).apply { setBackgroundColor(getColor(R.color.weike_key)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
    }

    private fun roundedBackground(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun circleBackground(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(color)
    }

    private fun margins(width: Int, height: Int, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(width, height).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun simpleSelection(onSelected: (Int) -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = onSelected(position)
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
