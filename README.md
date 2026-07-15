# 维刻输入法

维刻输入法是面向 Android 15+ 的开源中文输入法，提供离线拼音、英文候选、语音听写、文本润色和问答模式。项目采用 GPL-3.0-or-later 发布。

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

本机可运行 `scripts/prepare-github-signing-secrets.ps1` 将 JKS 转为可粘贴的
Base64 内容。签名证书与口令不得提交到 Git。

请勿在 issue、PR 或截图中粘贴听写、剪贴板、密钥或个人词典内容。
