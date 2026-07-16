# Changelog

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
