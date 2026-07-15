
# 维刻（Vertick）输入法

维刻输入法是面向 Android 15+ 的开源中文输入法，提供离线拼音、英文候选、语音听写、文本润色和问答模式。项目采用 GPL-3.0-or-later 发布。

![暗色模式下的听写/润色](https://edgeoneimg.cdn.sn/i/6a574692127f4_1784104594.webp "暗色模式下的听写/润色")

![亮色模式下的听写/润色](https://edgeoneimg.cdn.sn/i/6a5746921c074_1784104594.webp "亮色模式下的听写/润色")

## 功能与隐私

- Rime-Ice 驱动的离线拼音候选和本机学习。
- MiMo ASR 语音识别；文本模型使用 OpenAI Chat Completions 兼容协议。
- 云端接口由用户在应用内配置。密钥使用 Android Keystore 保护，绝不写入源码、构建参数或日志。
- 听写历史默认关闭。剪贴板历史默认关闭；开启后最多保留 20 条、24 小时内的非敏感文本。

完整数据边界见 [PRIVACY.md](PRIVACY.md)，安全报告方式见 [SECURITY.md](SECURITY.md)。
## 构建

需要 Android SDK、NDK 26.1、JDK 17 和 Gradle 8.7。

```powershell
$env:ANDROID_HOME='C:\Users\you\AppData\Local\Android\Sdk'
gradle :app:assembleDebug --no-daemon
adb install -r '.\app\build\outputs\apk\debug\app-debug.apk'
```

首次使用时，在“账户 → 云端连接 → 语音与文本接口配置”填写服务地址、密钥和模型：

- 文本接口接受 OpenAI Chat Completions 兼容端点。
- ASR 当前仅保证 MiMo 多模态聊天格式。
- 地址可填写主机、`/v1` 或完整 `/v1/chat/completions`，应用会自动规范化。

## 如何使用
1. 打开[列表](https://github.com/BurgerK1ng16/Vertick-IME/releases "列表")找到适合你设备的安装包下载
1. 安装并在软件：快速开始处授予权限
1. 在账户-云端连接中添加你的ARK服务商和文本模型服务商
（目前我们更推荐你使用mimo-v2.5-asr与deepseek-v4-flash，因为我们基于此模型搭建，如需适配其他服务商，请在[issues](https://github.com/BurgerK1ng16/Vertick-IME/issues "issues")中提起）

## 为什么要做这样的项目

去年，我第一次遇到了Typeless，它的便利性和准确性让我大吃一惊，但是对于我来说它的价格有些贵了。于是我使用Codex手搓了一份出来，在外观上，它几乎与Typeless无异，可能会更好些，但是为了操作习惯，我增添和优化了几个小功能：在部分机型上我们做了优化，触感会更细腻。在中文手打键盘上，我使用了主流的的开源的大词库，使得输入中文不再难受。

## 功能特性

- **听写**：调用ASR模型输出文本（支持流式模型）
- **润色**：由ASR模型输出文本（非流式）经过文本模型（特定提示词）润色后输出
  
![听写和润色](https://edgeoneimg.cdn.sn/i/6a5747055be0d_1784104709.webp "听写和润色")

- **智能问答**：由ASR模型输出文本（非流式）经过文本模型（特定提示词）后输出
  
![问答](https://edgeoneimg.cdn.sn/i/6a57470877da2_1784104712.webp "问答")

- **中/英文 手打键盘**：使用开源
  
![中英文手写键盘](https://edgeoneimg.cdn.sn/i/6a5747013037f_1784104705.webp "中英文手写键盘")

- **剪贴板**：监测复制内容（默认关闭）
  
![剪贴板](https://edgeoneimg.cdn.sn/i/6a574700c7f02_1784104704.webp "剪贴板")

## 为什么要开源

最接近的替代品都是订阅制 SaaS:按月付费、无法自带模型、音频上传到厂商、你的词典和习惯都存在它们的账户里。

Vertick追求同样的终端体验,但:
完全开源、本地优先。 代码就在本仓库,你的所有数据都留在自己的机器上。
自带云端凭据。 Volcengine 流式 ASR + Ark / DeepSeek 兼容的 chat completions,不绑定任何厂商。
为 AI 提示词而调优。 结构化模式会把零散的口语重塑为带有上下文、约束与诉求的提示词,可直接粘贴进 ChatGPT、Claude 或 Cursor。
它不会替你作答。 模型只负责清理你的文字。如果你说“这个应用还需要哪些功能?”,它会原样返回为一个干净的问句——而不会给你列一份功能清单。那种事,去问 AI 本体。

##对比
|工具| 形态 |与Vertick的不同之处|
| ------------ | ------------ | ------------ |
| Typeless  |闭源 macOS / Windows / iOS,订阅制|开源;显式的 AI 提示词模式;自带 ASR + LLM;数据与词典留在本机|
| Wispr Flow|闭源 macOS / Windows,订阅制|开源;自带 ASR + LLM;文本处理规则透明|

## 功能路线图

| 功能/优化    | 解释                                       | 进度     |
| ------------ | ------------------------------------------ | -------- |
| ✅听写/润色功能 | 转录语音 | 已完成 |
| -听写功能优化 | 单独适配各种ASR厂商模型 | 正在进行 |
| -标点习惯 | 支持智能标点、空格代替标点等习惯调节 | 已完成 |
| -应用文风 | 不同应用输出不同的润色结果 | 正在进行 |
| -结构化表达 | 使特定的句子输出更具结构化 | 正在进行 |
| -润色速度改善 | 现有的润色速度因提示词的问题无法较快的输出 | 正在进行 |
| -用户词典优化 | 支持用户词典内容优先输出 | 待规划 |
| ✅文字键盘 | 支持26键的中英输入 | 已完成 |
| -中文词典的优化 | 现有的中文词典依赖开源进行 分词的优化等 | 正在进行 |
| -键盘音效的优化 | 体验优化 | 正在进行 |
| -用户词典优化 | 支持用户词典内容优先输出 | 待规划 |
| ✅剪贴板功能 | 快速复制与粘贴文本（支持删除） | 已完成 |
| -支持删除历史时间 | 尊重隐私安全 | 待规划 |
| ✅智能问答模式 | 在输入法内完成Ai问答 | 已完成 |
| -智能问答模式 | 支持Markdown格式渲染 | 正在进行 |
| ❌翻译功能 | 功能添加 | 正在进行 |
| -翻译功能优化 | 支持多语言翻译输出 | 正在进行 |
| ✅输入法后台/管理台 | 支持快捷编辑 | 已完成 |
| -后台/管理页UI优化 | 现有的UI不通人性/太丑了 | 待规划 |
| -触感反馈优化 | 适配多机型的触觉反馈体验 | 待规划 |

## 公开发布

不要提交 `local.properties`、签名文件、APK、真机截图、原始日志或任何真实密钥。发布前必须轮换开发密钥。

Rime/librime 使发行版受 GPLv3 约束。对应的 Trime/librime 源码、构建脚本与版本锁定文件已在
`third_party/librime` 提供；详情见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 贡献

提交前运行：

```powershell
gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

仅在准备公开 Release 时运行 `gradle verifyOpenSourceRelease --no-daemon`。

### 自动发布签名 APK

推送 `v*` 标签会触发 GitHub Actions 构建并创建 GitHub Release。仓库 Settings →
Secrets and variables → Actions 中必须配置：`ANDROID_KEYSTORE_BASE64`、
`ANDROID_STORE_PASSWORD`、`ANDROID_KEY_ALIAS` 和 `ANDROID_KEY_PASSWORD`。

### 帮助与支持

- 如果你对这个项目也有兴趣，欢迎随时加入我们。
QQ：1929782060
QQ群组：1905897002
邮箱：zejjj06@gamil.com

