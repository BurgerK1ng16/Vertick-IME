# Vertick IME

[简体中文](README.md) | [Download releases](https://github.com/BurgerK1ng16/Vertick-IME/releases)

Vertick IME is an open-source Chinese input method for Android 15 and later. It provides offline Pinyin input, English suggestions, voice dictation, AI text polishing, and a Q&A mode. It is released under [GPL-3.0-or-later](LICENSE).

<p align="center">
  <img src="https://edgeoneimg.cdn.sn/i/6a574692127f4_1784104594.webp" alt="Dictation and polishing in dark mode" width="49%" />
  <img src="https://edgeoneimg.cdn.sn/i/6a5746921c074_1784104594.webp" alt="Dictation and polishing in light mode" width="49%" />
</p>

## Features and Privacy

- Offline Pinyin candidates and on-device learning powered by Rime-Ice.
- MiMo ASR for speech recognition; text models use the OpenAI Chat Completions-compatible protocol.
- Cloud endpoints are configured inside the app. Secrets are protected with Android Keystore and are never written to source code, build arguments, or logs.
- Dictation history is disabled by default. Clipboard history is also disabled by default; when enabled, it stores at most 20 non-sensitive items for up to 24 hours.

See [PRIVACY.md](PRIVACY.md) for data boundaries and [SECURITY.md](SECURITY.md) for security reporting.

## Getting Started

1. Download the APK for your device from [Releases](https://github.com/BurgerK1ng16/Vertick-IME/releases).
2. Install it, grant the required permissions in Quick Start, and enable Vertick as an input method.
3. Configure your ASR and text-model services in **Account - Cloud Connection**.

`MiMo-V2.5-ASR` and `DeepSeek-V4-Flash` are currently recommended and receive the most testing. Please open an [issue](https://github.com/BurgerK1ng16/Vertick-IME/issues) for other provider support.

## Highlights

- **Dictation**: sends speech to an ASR model and inserts text directly; streaming ASR is supported.
- **Polish**: transcribes audio with ASR, then organizes the text with a text model and focused prompts.

![Dictation and polish](https://edgeoneimg.cdn.sn/i/6a5747055be0d_1784104709.webp "Dictation and polish")

- **Smart Q&A**: transcribes your spoken question with ASR, then generates a concise answer with the text model.

![Q&A](https://edgeoneimg.cdn.sn/i/6a57470877da2_1784104712.webp "Q&A")

- **Chinese and English keyboards**: offline Pinyin and English candidates.

![Chinese and English keyboards](https://edgeoneimg.cdn.sn/i/6a5747013037f_1784104705.webp "Chinese and English keyboards")

- **Clipboard**: monitors copied text and lets you paste it quickly. It is off by default and supports item deletion.

![Clipboard](https://edgeoneimg.cdn.sn/i/6a574700c7f02_1784104704.webp "Clipboard")

## Why This Project Exists

Typeless impressed me with its convenience and accuracy, but its subscription was more than I wanted to pay. I used Codex to build Vertick from scratch as a personal alternative. Its interface aims for a similarly minimal experience while adding a few workflow improvements: more refined haptics on selected devices and a large open-source dictionary for more comfortable Chinese typing.

## Why Open Source

The closest alternatives tend to be subscription SaaS products: monthly fees, no bring-your-own model, audio sent to a vendor, and dictionaries and preferences stored in a vendor account.

Vertick aims for a comparable endpoint experience while remaining:

- **Fully open source and local-first**: the code is in this repository and your data stays on your own device whenever possible.
- **Bring-your-own cloud credentials**: configure your own ASR and Chat Completions-compatible text services without vendor lock-in.
- **Optimized for AI prompts**: structured mode turns fragmented speech into prompts with context, constraints, and intent, ready to paste into ChatGPT, Claude, or Cursor.
- **Not an unsolicited answer engine**: polishing cleans and organizes your wording. If you say, “What features should this app have?”, it returns a clean question instead of inventing a feature list.

## Comparison

| Tool | Form factor | How Vertick differs |
| --- | --- | --- |
| Typeless | Closed-source macOS / Windows / iOS subscription app | Open source; configurable models; local data and dictionary |
| Wispr Flow | Closed-source macOS / Windows subscription app | Open source; configurable ASR and text models; transparent text processing |

## Roadmap

| Feature / improvement | Status |
| --- | --- |
| Dictation and polishing | Complete |
| More ASR provider support | In progress |
| Punctuation preferences | Complete |
| Per-app writing style | In progress |
| Structured expression | In progress |
| Faster polishing and streaming | In progress |
| User dictionary ranking | Planned |
| 26-key Chinese and English keyboard | Complete |
| Chinese dictionary and candidate improvements | In progress |
| Keyboard sound improvements | In progress |
| Clipboard with quick paste and deletion | Complete |
| Configurable history retention | Planned |
| Smart Q&A mode | Complete |
| Markdown Q&A rendering | In progress |
| Translation | In progress |
| Input method dashboard | Complete |
| Dashboard UI improvements | Planned |
| More device haptic tuning | Planned |

## Build

Android SDK, NDK 26.1, JDK 17, and Gradle 8.7 are required.

```powershell
$env:ANDROID_HOME='C:\Users\you\AppData\Local\Android\Sdk'
gradle :app:assembleDebug --no-daemon
adb install -r '.\app\build\outputs\apk\debug\app-debug.apk'
```

On first use, configure the endpoint, key, and model under **Account - Cloud Connection - Voice and Text API Configuration**:

- Text services must provide an OpenAI Chat Completions-compatible endpoint.
- ASR is currently guaranteed only for the MiMo multimodal chat request format.
- You may provide a host URL, `/v1`, or the full `/v1/chat/completions` URL. Vertick normalizes the endpoint automatically.

## Public Releases

Do not commit `local.properties`, signing files, APKs, device screenshots, raw logs, or real credentials. Rotate development keys before publishing.

Rime/librime makes the distributed application subject to GPLv3. Corresponding Trime/librime source, build scripts, and version lock files are available in `third_party/librime`; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for details.

## Contributing

Run before submitting changes:

```powershell
gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

Run the following only when preparing a public release:

```powershell
gradle verifyOpenSourceRelease --no-daemon
```

### Signed APK Releases

Pushing a `v*` tag triggers GitHub Actions to build a signed APK and create a GitHub Release. Configure these repository secrets under **Settings - Secrets and variables - Actions**: `ANDROID_KEYSTORE_BASE64`, `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`.

Use `scripts/prepare-github-signing-secrets.ps1` locally to convert a JKS file to pasteable Base64 content. Never commit the signing certificate or its passwords.

Do not include dictation content, clipboard content, keys, or personal-dictionary entries in issues, pull requests, or screenshots.

## Support

Interested in the project? You are welcome to join the community.

- QQ: 1929782060
- QQ group: 1905897002
- Email: zejjj06@gamil.com
