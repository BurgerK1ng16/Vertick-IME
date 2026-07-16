# Changelog

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
- Added immediate local matching for custom terms, including full Pinyin and initial abbreviations such as `zhangenjie` and `zej` for `张恩捷`.
- Deferred Rime custom-dictionary rebuilds so adding professional terms does not interrupt active typing.

### 简体中文

- 修复横屏切换后输入法重置或不可用：输入法服务自行处理配置变化，并保留当前键盘模式。
- 增加横屏下更紧凑的键盘与语音面板布局。
- 输入法服务启动后预热并保持 Rime 引擎，避免每次进入手打键盘重复出现“词典准备中”。
- 修复拼音输入、退格时未完成字母意外上屏；`nihao` 删除到 `n` 不会再额外上屏一个 `n`。
- 拼音组合态按发送会先提交原始组合，例如 `dfsh`；再次按发送才向目标应用提交。
- 自定义词支持完整拼音和首字母缩写即时匹配，例如“张恩捷”可由 `zhangenjie` 或 `zej` 命中。
- 专业词与打字词典的 Rime 重编译改为延后执行，新增词条不会中断当前输入。
