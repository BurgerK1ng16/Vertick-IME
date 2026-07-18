# Changelog

## 1.3.0 - 2026-07-18

### English

- Fixed unsolicited landscape keyboard opening during rotation, notification-shade changes, and IME view recreation. The floating keyboard now opens only from a fresh editor show request.
- Refined the landscape header with a compact mode selector, an aligned `chevron-down` hide control, centered nine-key letters, and no numeric labels in landscape nine-key mode.
- Added a shared portrait/landscape Hide-key setting, stronger nine-key T9 recovery, and candidate fallback protection.
- Added seven candidate-text size levels, English sentence-initial auto-capitalization, and optional double-space period entry.
- Replaced the keyboard key-click audio.

### 简体中文

- 修复横屏旋转、下拉状态栏和输入法视图重建时自动调出键盘的问题；悬浮键盘仅在新的文本输入请求时显示。
- 优化横屏顶部模式切换与 `chevron-down` 收起键布局；九宫格横屏隐藏数字编码并居中显示字母。
- 新增横竖屏共用的收起键开关，强化九宫格 T9 恢复与候选兜底。
- 新增候选词七档大小、英文句首自动大写和双击空格输入句号设置。
- 替换按键音效。

## 1.2.9 - 2026-07-17

### English

- Rebuilt the landscape keyboard as an independent top-level floating window. It no longer reserves IME bottom insets or moves the focused editor upward.
- The landscape surface now uses a fixed `1.7:1` width-to-height ratio, remains within 60% of the display height, and renders at 80% opacity.
- The keyboard can be dragged freely across the landscape screen from the logo area. Dragging is disabled while candidates are visible so the first candidates remain directly tappable.
- Reduced all landscape letter-key labels to 80% of their portrait size for the compact floating layout.
- Fixed reopening the floating keyboard after it is dismissed: input-view and show requests are now scheduled from the service main loop, independent of a detached keyboard view.

### 简体中文

- 横屏键盘改为独立的顶层悬浮窗口，不再占用系统输入法底部空间，也不会顶起当前文本框。
- 横屏悬浮键盘固定为 `1.7:1` 宽高比，高度不超过屏幕的 60%，整体透明度为 80%。
- 可从 Logo 区域在横屏内任意拖动键盘；候选栏显示时自动关闭拖动热区，前几个候选词可以正常点选。
- 横屏文字键盘的字母字号缩小至竖屏的 80%，适配紧凑悬浮布局。
- 修复关闭横屏键盘后再次点击文本框无法调起的问题：输入视图和显示请求统一由输入法服务主线程重新创建悬浮窗口。

## 1.2.4 - 2026-07-17

### English

- Fixed IME state loss during landscape rotation. Recreating the input view now preserves the active keyboard surface and restores unfinished Pinyin or English composition instead of resetting the keyboard.
- Fixed the last composing letter being accidentally committed during rotation. This also preserves the internal nine-key code so candidates remain correct after restoring the view.
- Voice recording is safely cancelled during a rotation; no stale recording or network result can be inserted into the newly recreated input connection.
- Added a lightweight mode restore for Xiaomi system cases that recreate the IME service itself during rotation. It stores only the last keyboard mode, never typed text, audio, candidates, or cloud credentials.

### 简体中文

- 修复横屏切换时输入法状态丢失：输入视图重建后会保留当前键盘界面，并恢复未上屏的拼音或英文组合态，不再回到初始状态。
- 修复旋转过程中最后一个组合字母意外上屏；九宫格会保留原始数字编码，恢复后候选词保持正确。
- 横竖屏切换会安全停止录音，不会把失效录音或旧网络结果写入新输入框。
- 针对小米系统旋转时可能重建输入法服务的情况，新增轻量模式恢复。仅保存上次键盘模式，绝不保存输入文本、录音、候选词或云端凭据。

## 1.2.3 - 2026-07-16

### English

- The complete Rime-Ice Pinyin table is now precompiled and bundled with the APK. New installations unpack and load it directly instead of compiling 1.8 million dictionary entries on the phone.
- First-run typing is available immediately after the bundled table is copied; dictionary preparation no longer blocks the keyboard or consumes substantial memory.
- Fixed the Rime readiness check so it validates the loaded prebuilt schema once without repeatedly reopening or rebuilding the dictionary.
- Added a candidate safety fallback: an incompatible prebuilt table cannot leave the candidate strip empty; typing remains usable while the dictionary is recovered.

### 简体中文

- 完整 Rime-Ice 拼音词典表现已预编译并随 APK 提供。新安装仅解压、加载词典，不再在手机上编译约 188 万词条。
- 首次进入即可使用手打键盘；词典准备不再阻塞输入或占用大量内存。
- 修复 Rime 就绪检测：仅验证一次已加载的预编译方案，不再反复重载或重建词典。
- 增加候选保护：预编译表异常时不会留下空的“正在匹配中”候选栏，手打仍可继续使用。

## 1.2.2 - 2026-07-16

### English

- Hardened Rime-Ice deployment: the health check now allows a realistic first-build window and automatically removes an incomplete compiled table before rebuilding once.
- Polishing and translation long-press UI no longer overlaps the listening capsule. Dragging into translation now swaps the visual emphasis between the polish area and translation capsule; drag to the top-right close control to cancel recording.
- Long-press Delete now accelerates smoothly with one matching haptic pulse per deleted character.
- Direct dictation no longer inserts streaming ASR deltas. Text is cleaned locally, including punctuation preferences, before it is committed once.
- Reordered the nine-key keypad with the separator key first, and moved sidebar punctuation 3dp right for optical centering.

### 简体中文

- 强化 Rime-Ice 词典部署：首次完整编译获得更合理的健康检查时长；失败时会自动清理不完整编译表并重建一次。
- 长按润色/翻译界面不再与拾音胶囊重叠；拖入翻译区时润色区与翻译胶囊深浅互换，拖到右上角关闭按钮可直接取消录音。
- 所有删除键长按连续删除时会平滑加速，并保持每删除一个字符对应一次触感反馈。
- 听写不再流式直写；识别完成后先执行本地清洗和标点习惯处理，再一次性提交文本。
- 九宫格将分隔符键调整到首位并重排数字，左侧符号整体右移 3dp 以获得更好的视觉居中。

## 1.2.1 - 2026-07-16

### English

- First-run Pinyin is now usable immediately while the complete Rime-Ice dictionary is verified in the background. Typing, deletion, custom-word matching, and explicit raw-Pinyin commit remain available instead of blocking on “Preparing dictionary”.
- Redesigned the nine-key Chinese layout with a vertically scrollable punctuation sidebar, three-column keypad, dedicated delete key, two-cell newline area, full-width spacebar, and the existing `123` symbol page.
- Added Settings controls to add, edit, delete, and drag-sort nine-key sidebar punctuation. The default set is `， 。 ？ ！ … ： 、 ～`.
- Fixed MiMo ASR JSON `null` values and trailing `null` artifacts appearing in dictation output.

### 简体中文

- 首次使用时，完整 Rime-Ice 词典仍在后台校验和编译，但拼音键盘已可立即输入、删除、命中自定义词并提交原始拼音，不再被“词典准备中”阻塞。
- 重做九宫格中文布局：左侧可上下滑动的符号栏、三列九键、独立删除键、两格高换行区、三格空格键和原有 `123` 数字符号页。
- 设置页新增九宫格侧边符号管理，可添加、编辑、删除和拖动排序；默认符号为 `， 。 ？ ！ … ： 、 ～`。
- 修复 MiMo ASR 返回 JSON `null` 或末尾 `null` 被写入听写结果的问题。

## 1.2.0 - 2026-07-16

### English

- Redesigned the long-press polish gesture with a rising lower semicircle, a floating irregular translation capsule, and smooth selection feedback.
- Long press the voice button to begin a polish recording. Release in the lower area to polish as before, or slide upward to **English (US)** and release to translate after ASR.
- Added a dedicated American English translation prompt to the configured text model. The original ASR text is preserved if translation fails or times out.

### 简体中文

- 重做长按润色手势界面：下方半圆遮罩平滑升起，并显示包裹遮罩的异形翻译胶囊。
- 长按录音键开始润色录音，在下方区域松开保持润色；向上滑入“英语（美国）”区域后松开，则 ASR 识别后调用文本模型翻译。
- 文本模型新增美式英语翻译提示词；翻译失败或超时时自动保留原始 ASR 转写。

## 0.1.3 - 2026-07-16

### English

- English keyboard symbols now use English punctuation instead of Chinese punctuation.
- Added independent multi-touch key handling, allowing simultaneous two-hand typing without dropping a key.
- Added OpenAI-compatible SSE streaming for direct ASR dictation. Text is inserted as ASR deltas arrive; providers without streaming support automatically fall back to the standard request.
- Added a Chinese primary-keyboard setting: full 26-key Pinyin or offline Rime T9 nine-key Pinyin.

### 简体中文

- 英文键盘的符号页改为英文标点，不再统一显示中文符号。
- 增加独立多点触控按键处理，双手同时输入时不会丢失按键。
- 听写支持 OpenAI 兼容 SSE 流式 ASR，识别增量到达时立即写入；服务端不支持流式时自动回退普通请求。
- 设置页新增“中文主键盘”，可选 26 键全键盘拼音或离线 Rime 九宫格拼音。

## 0.1.2 - 2026-07-16

### English

- Fixed the final uncommitted Pinyin initial being confirmed when composition is cleared. Deleting an unfinished composition now removes every letter without requiring a second delete.
- Fixed keyboard touch handling: sliding away from a pressed key cancels it instead of inserting the key under the finger when released.
- Removed Send, Delete, and `@` controls from Smart Q&A mode.
- The Smart Q&A processing capsule now displays `Vertick Knows` (维刻知道) with the same progress animation used by dictation polishing.
- Custom-term updates now stage their Rime data in the background for the next deployment and never restart the active decoder, eliminating the stall when returning to the keyboard after editing the dictionary.

### 简体中文

- 修复拼音组合态清空时末位字母被确认上屏的问题；未上屏拼音可以一次删完，不再需要多按一次删除。
- 修复按键点选逻辑：按住按键后滑出原按键会取消输入，松开时不会误输入滑动位置的字母。
- 智能问答模式移除发送、删除和 `@` 按钮。
- 智能问答处理胶囊改为显示“维刻知道”，并沿用听写润色的进度动画。
- 词典更新只在后台写入下次 Rime 部署所需数据，不再重启正在使用的解码器，修复新增词典后切回键盘卡顿。

## 0.1.1 - 2026-07-16

### English

- Fixed IME state loss after landscape rotation by handling configuration changes in the input-method service and preserving the active keyboard mode.
- Added a compact landscape layout for the keyboard and voice panel.
- Pre-warms and keeps the offline Rime engine alive while the IME service is running, avoiding repeated dictionary preparation when returning to text input.
- Prevented unfinished Pinyin letters from being committed while typing or deleting. Deleting `nihao` down to `n` no longer inserts an extra `n`.
- Pressing Send with a Pinyin composition now commits the literal composition first, for example `dfsh`; press Send again to submit it to the target app.
- Added immediate local matching for custom terms, including full Pinyin and initial abbreviations such as `weike` and `wk` for `维刻`.
- Deferred Rime custom-dictionary rebuilds so adding professional terms does not interrupt active typing.

### 简体中文

- 修复横屏切换后输入法重置或不可用：输入法服务自行处理配置变化，并保留当前键盘模式。
- 增加横屏下更紧凑的键盘与语音面板布局。
- 输入法服务启动后预热并保持 Rime 引擎，避免每次进入手打键盘重复出现“词典准备中”。
- 修复拼音输入、退格时未完成字母意外上屏；`nihao` 删除到 `n` 不会再额外上屏一个 `n`。
- 拼音组合态按发送会先提交原始组合，例如 `dfsh`；再次按发送才向目标应用提交。
- 自定义词支持完整拼音和首字母缩写即时匹配，例如“维刻”可由 `weike` 或 `wk` 命中。
- 专业词与打字词典的 Rime 重编译改为延后执行，新增词条不会中断当前输入。
