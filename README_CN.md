# 维刻（Vertick）输入法

[English](README.md) | [下载正式版](https://github.com/BurgerK1ng16/Vertick-IME/releases)

维刻输入法是面向 Android 15+ 的开源中文输入法，提供离线拼音、英文候选、语音听写、文本润色和问答模式。项目采用 [GPL-3.0-or-later](LICENSE) 发布。
⚠️项目目前处于快速迭代模式，请注意检查更新
<p align="center">
  <img src="https://edgeoneimg.cdn.sn/i/6a574692127f4_1784104594.webp" alt="暗色模式下的听写和润色" width="49%" />
  <img src="https://edgeoneimg.cdn.sn/i/6a5746921c074_1784104594.webp" alt="亮色模式下的听写和润色" width="49%" />
</p>

## 功能与隐私

- Rime-Ice 驱动的离线拼音候选和本机学习。
- MiMo ASR 语音识别；文本模型使用 OpenAI Chat Completions 兼容协议。
- 云端接口由用户在应用内配置。密钥使用 Android Keystore 保护，绝不写入源码、构建参数或日志。
- 听写历史默认关闭。剪贴板历史默认关闭；开启后最多保留 20 条、24 小时内的非敏感文本。

完整数据边界见 [PRIVACY.md](PRIVACY.md)，安全报告方式见 [SECURITY.md](SECURITY.md)。

## 如何使用

1. 在 [Releases](https://github.com/BurgerK1ng16/Vertick-IME/releases) 下载适合设备的安装包。
2. 安装后，在应用的“快速开始”中授予所需权限并启用输入法。
3. 在“账户 - 云端连接”中添加你的 ASR 和文本模型服务。

目前推荐使用 `MiMo-V2.5-ASR` 与 `DeepSeek-V4-Flash`。维刻基于这组服务进行了重点验证；如需支持其他服务商，请在 [Issues](https://github.com/BurgerK1ng16/Vertick-IME/issues) 中提出。

## 功能特性

- **听写**：调用 ASR 模型直接输出文本，支持流式模型。
- **润色**：ASR 非流式转写后，经文本模型和特定提示词整理输出。

![听写和润色](https://edgeoneimg.cdn.sn/i/6a5747055be0d_1784104709.webp "听写和润色")

- **智能问答**：ASR 非流式转写后，交由文本模型生成简洁回答。

![问答](https://edgeoneimg.cdn.sn/i/6a57470877da2_1784104712.webp "问答")

- **中/英文手打键盘**：离线中文拼音候选和英文候选。

![中英文手打键盘](https://edgeoneimg.cdn.sn/i/6a5747013037f_1784104705.webp "中英文手打键盘")

- **剪贴板**：监测并快速粘贴复制内容，默认关闭，支持删除。

![剪贴板](https://edgeoneimg.cdn.sn/i/6a574700c7f02_1784104704.webp "剪贴板")

## 为什么做这个项目

去年第一次遇到 Typeless 时，它的便利性和准确性让我大吃一惊，但订阅价格对我来说偏高。于是我使用 Codex 从零做出了维刻。外观上它尽可能接近 Typeless，并根据自己的操作习惯增加和优化了一些功能：部分机型拥有更细腻的触感反馈，中文手打键盘接入主流开源大词库，让中文输入不再难受。

## 为什么开源

最接近的替代品大多是订阅制 SaaS：按月付费、无法自带模型、音频上传到厂商，词典和习惯也存放在厂商账户中。

Vertick 追求同样的终端体验，但坚持：

- **完全开源，本地优先**：代码就在本仓库；你的数据优先留在自己的设备上。
- **自带云端凭据**：支持用户自行配置 ASR 与兼容 Chat Completions 的文本模型，不绑定任何厂商。
- **为 AI 提示词而调优**：结构化模式会把零散口语重塑为带有上下文、约束和诉求的提示词，可直接粘贴进 ChatGPT、Claude 或 Cursor。
- **不会替你作答**：润色只清理和组织你的文字。若你说“这个应用还需要哪些功能？”，它会返回干净的问句，不会自行列出功能清单。

## 对比

| 工具 | 形态 | 与 Vertick 的不同之处 |
| --- | --- | --- |
| Typeless | 闭源 macOS / Windows / iOS，订阅制 | 开源；可配置模型；数据与词典留在本机 |
| Wispr Flow | 闭源 macOS / Windows，订阅制 | 开源；可配置 ASR 与文本模型；文本处理规则透明 |

## 功能路线图

| 功能 / 优化 | 说明 | 进度 |
| --- | --- | --- |
| 听写 / 润色 | 语音转录与文本润色 | 已完成 |
| 听写优化 | 适配更多 ASR 服务商模型 | 正在进行 |
| 标点习惯 | 智能标点、空格代替标点等 | 已完成 |
| 应用文风 | 不同应用输出不同润色结果 | 正在进行 |
| 结构化表达 | 整理特定类型的口述文本 | 正在进行 |
| 润色速度改善 | 精简提示词与优化流式输出 | 正在进行 |
| 用户词典优化 | 用户词条优先候选 | 待规划 |
| 文字键盘 | 26 键中英输入 | 已完成 |
| 中文词典优化 | 词典、分词和候选优化 | 正在进行 |
| 键盘音效优化 | 输入体验优化 | 正在进行 |
| 剪贴板 | 快速复制、粘贴与删除 | 已完成 |
| 历史保留策略 | 支持设置历史删除时间 | 待规划 |
| 智能问答模式 | 在输入法内完成 AI 问答 | 已完成 |
| 问答 Markdown 渲染 | 更易读的长回答显示 | 正在进行 |
| 翻译功能 | 多语言翻译输出 | 正在进行 |
| 输入法后台 / 管理台 | 快捷编辑与管理 | 已完成 |
| 管理页 UI 优化 | 改善可用性与视觉设计 | 待规划 |
| 触感反馈优化 | 适配更多机型的触觉体验 | 待规划 |

## 构建

需要 Android SDK、NDK 26.1、JDK 17 和 Gradle 8.7。

```powershell
$env:ANDROID_HOME='C:\Users\you\AppData\Local\Android\Sdk'
gradle :app:assembleDebug --no-daemon
adb install -r '.\app\build\outputs\apk\debug\app-debug.apk'
```

首次使用时，在“账户 - 云端连接 - 语音与文本接口配置”填写服务地址、密钥和模型：

- 文本接口接受 OpenAI Chat Completions 兼容端点。
- ASR 当前仅保证 MiMo 多模态聊天格式。
- 地址可填写主机、`/v1` 或完整 `/v1/chat/completions`，应用会自动规范化。

## 公开发布

不要提交 `local.properties`、签名文件、APK、真机截图、原始日志或任何真实密钥。发布前必须轮换开发密钥。

Rime/librime 使发行版受 GPLv3 约束。对应的 Trime/librime 源码、构建脚本与版本锁定文件已在 `third_party/librime` 提供；详情见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 贡献

提交前运行：

```powershell
gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

仅在准备公开 Release 时运行：

```powershell
gradle verifyOpenSourceRelease --no-daemon
```

### 自动发布签名 APK

推送 `v*` 标签会触发 GitHub Actions 构建并创建 GitHub Release。仓库 Settings → Secrets and variables → Actions 中必须配置：`ANDROID_KEYSTORE_BASE64`、`ANDROID_STORE_PASSWORD`、`ANDROID_KEY_ALIAS` 和 `ANDROID_KEY_PASSWORD`。

本机可运行 `scripts/prepare-github-signing-secrets.ps1` 将 JKS 转为可粘贴的 Base64 内容。签名证书与口令不得提交到 Git。

请勿在 Issue、PR 或截图中粘贴听写内容、剪贴板内容、密钥或个人词典内容。

## 帮助与支持

如果你对这个项目也有兴趣，欢迎随时加入我们。

- QQ：1929782060
- QQ 群：1905897002
- 邮箱：zejjj06@gamil.com
