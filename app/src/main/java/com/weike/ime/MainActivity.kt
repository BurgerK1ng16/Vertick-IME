package com.weike.ime

import android.Manifest
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
import android.text.InputType
import android.view.Gravity
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import com.weike.ime.ime.RimePinyinDecoder
import com.weike.ime.ime.WeikeInputMethodService
import com.weike.ime.network.MimoTextPolisher
import com.weike.ime.network.MimoApiConfig
import com.weike.ime.speech.MimoAsrClient
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private enum class Page { HOME, HISTORY, DICTIONARY, ACCOUNT, SETTINGS, CLOUD, ABOUT }

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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(229, 226, 225))
                cornerRadius = 24 * density
            }
            selector.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 20 * density
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
            val inset = (3 * density).toInt()
            val cell = width / options.size
            selector.layoutParams = FrameLayout.LayoutParams(cell - inset * 2, height - inset * 2).apply {
                leftMargin = inset
                topMargin = inset
            }
            val targetX = (selected * cell).toFloat()
            if (animated) selector.animate().translationX(targetX).setDuration(180).start()
            else selector.translationX = targetX
            labels.forEachIndexed { index, label ->
                label.setTextColor(if (index == selected) Color.rgb(28, 27, 27) else Color.rgb(92, 95, 96))
                label.typeface = if (index == selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
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
            paint.color = Color.rgb(229, 226, 225)
            canvas.drawRoundRect(track, trackHeight / 2f, trackHeight / 2f, paint)

            if (value > 0f) {
                val fill = RectF(track.left, track.top, track.left + track.width() * value, track.bottom)
                paint.color = Color.rgb(37, 99, 235)
                canvas.drawRoundRect(fill, trackHeight / 2f, trackHeight / 2f, paint)
            }

            val thumbX = track.left + track.width() * value
            paint.setShadowLayer(2 * density, 0f, density, 0x33000000)
            setLayerType(LAYER_TYPE_SOFTWARE, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(thumbX, track.centerY(), 13 * density, paint)
            paint.clearShadowLayer()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.color = Color.rgb(207, 204, 203)
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

    private lateinit var container: AppContainer
    private lateinit var pageHost: FrameLayout
    private lateinit var bottomNavigation: LinearLayout
    private var page = Page.HOME

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
    private var microphoneStatus: TextView? = null
    private var latestStats = UsageStats()
    private var latestHistory: List<InputHistory> = emptyList()
    private var latestLexicon: List<LexiconTerm> = emptyList()
    private var latestTypingDictionary: List<TypingDictionaryEntry> = emptyList()
    private var latestOverrides: Map<String, WritingStyle> = emptyMap()
    private var latestKeyboardModes: List<KeyboardModePreference> = emptyList()
    private var dictionaryTab = 0
    private var dragHighlight: View? = null
    private var dragSource: View? = null
    private val pagePrimary = Color.rgb(37, 99, 235)
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
        showPage(Page.HOME)
    }

    override fun onBackPressed() {
        if (page == Page.SETTINGS || page == Page.CLOUD || page == Page.ABOUT) showPage(Page.ACCOUNT) else super.onBackPressed()
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
        }
        addView(bottomNavigation, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)))
    }

    private fun showPage(next: Page) {
        page = next
        pageHost.removeAllViews()
        pageHost.addView(
            when (next) {
                Page.HOME -> buildHome()
                Page.HISTORY -> buildHistory()
                Page.DICTIONARY -> buildDictionary()
                Page.ACCOUNT -> buildAccount()
                Page.SETTINGS -> buildSettings()
                Page.CLOUD -> buildCloudConfiguration()
                Page.ABOUT -> buildAbout()
            }
        )
        bottomNavigation.visibility = if (next == Page.SETTINGS || next == Page.CLOUD || next == Page.ABOUT) View.GONE else View.VISIBLE
        settingsBackCallback.isEnabled = next == Page.SETTINGS || next == Page.CLOUD || next == Page.ABOUT
        rebuildNavigation()
        updatePermissionStatus()
    }

    private fun rebuildNavigation() {
        bottomNavigation.removeAllViews()
        listOf(
            Triple(Page.HOME, "首页", R.drawable.ic_lucide_house),
            Triple(Page.HISTORY, "历史记录", R.drawable.ic_lucide_history),
            Triple(Page.DICTIONARY, "词典", R.drawable.ic_lucide_book_open),
            Triple(Page.ACCOUNT, "账户", R.drawable.ic_lucide_user_round)
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
        addView(brandTitle("账户"))
        addView(card().apply {
            addView(actionRow("设置", "外观、键盘、标点、触感与按键音") { showPage(Page.SETTINGS) })
            addDivider(this)
            addView(actionRow("权限授权", "麦克风权限与系统输入法") {
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            })
            addDivider(this)
            addView(actionRow("关于我们", "") { showPage(Page.ABOUT) })
        })
        microphoneStatus = subtitle("")
        addView(microphoneStatus)
        addView(section("应用文风"))
        addView(subtitle("按应用包名覆盖默认润色风格，例如 com.tencent.mm"))
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
                setOnCheckedChangeListener { _, checked -> lifecycleScope.launch { container.settings.saveExpressionOptimization(checked) } }
            })
        })
        addView(section("中文离线输入"))
        addView(card().apply {
            addView(actionRow("Rime-Ice 完整词典", RimePinyinDecoder.DICTIONARY_VERSION) {})
            addDivider(this)
            addView(actionRow("清除候选学习数据", "保留专业词与打字词典") {
                RimePinyinDecoder.requestClearLearnedData(this@MainActivity)
                sendBroadcast(Intent(WeikeInputMethodService.ACTION_CLEAR_RIME_LEARNING).setPackage(packageName))
            })
        })
        addView(section("云端连接"))
        addView(card().apply {
            addView(actionRow("语音与文本接口配置", "") { showPage(Page.CLOUD) })
        })
    }

    private fun buildCloudConfiguration(): View = screen {
        addView(subpageHeader("语音与文本接口配置") { showPage(Page.ACCOUNT) })
        addView(section("ASR 接口"))
        addView(endpointConfigurationCard(
            load = { container.settings.cloudApiSettings.first().asr },
            save = { config -> container.settings.saveAsrApi(config) },
            test = { config -> MimoAsrClient(endpointProvider = { config }).testConnection() }
        ))
        addView(section("文本模型"))
        addView(endpointConfigurationCard(
            load = { container.settings.cloudApiSettings.first().text },
            save = { config -> container.settings.saveTextApi(config) },
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

    private fun endpointConfigurationCard(
        load: suspend () -> ModelEndpointConfig,
        save: suspend (ModelEndpointConfig) -> Unit,
        test: suspend (ModelEndpointConfig) -> Result<Unit>
    ) = card().apply {
        val url = field("接口地址")
        val apiKey = field("接口密钥").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val model = field("模型")
        fun currentConfig() = ModelEndpointConfig(
            url = url.text.toString(),
            apiKey = apiKey.text.toString(),
            model = model.text.toString()
        )
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
                save(config)
                Toast.makeText(this@MainActivity, "已保存", Toast.LENGTH_SHORT).show()
            }
        }
        addView(url)
        addView(apiKey)
        addView(model)
        addView(LinearLayout(this@MainActivity).apply {
            addView(testButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
            addView(saveButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        })
        lifecycleScope.launch {
            load().also { config ->
                url.setText(config.url)
                apiKey.setText(config.apiKey)
                model.setText(config.model)
            }
        }
    }

    private fun cloudConfigValidationError(config: ModelEndpointConfig): String? = runCatching {
        require(config.isComplete()) { "请完整填写接口信息" }
        require(config.apiKey.length <= 512 && config.apiKey.none(Char::isWhitespace)) { "接口密钥格式无效" }
        require(config.model.length <= 128 && config.model.none(Char::isWhitespace)) { "模型名称格式无效" }
        MimoApiConfig.chatCompletionsEndpoint(config.url)
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
                val toggle = Switch(this@MainActivity).apply { isChecked = preference in enabled }
                toggle.setOnCheckedChangeListener { _, checked ->
                    val next = order.toMutableList()
                    if (checked && preference !in next) next += preference
                    if (!checked) next.remove(preference)
                    if (next.isEmpty()) {
                        toggle.isChecked = true
                        Toast.makeText(this@MainActivity, "至少保留一个键盘模式", Toast.LENGTH_SHORT).show()
                    } else lifecycleScope.launch { container.settings.saveKeyboardModes(next) }
                }
                row.addView(toggle)
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
