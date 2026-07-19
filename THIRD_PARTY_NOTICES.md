# Third-party notices

## Trime JNI and librime

- Upstream: https://github.com/osfans/trime and https://github.com/rime/librime
- License: Trime is GPL-3.0-or-later; librime is BSD-3-Clause.
- Used for: Trime's Android JNI bridge, offline Pinyin decoding and user dictionary learning.
- Corresponding source, build scripts, and the version lock are provided in
  `third_party/librime`. `app/src/main/jniLibs/arm64-v8a/librime_jni.so` is
  verified against the official Trime v3.3.11 arm64 release in `SOURCE_LOCK.md`.

## Rime-Ice

- Upstream: https://github.com/iDvel/rime-ice
- License: GPL-3.0-or-later
- Used for: Chinese dictionaries and schema data under `app/src/main/assets/rime`.

## rime-essay-simp

- Upstream: https://github.com/rime/rime-essay-simp
- Revision: `c3de118026871c566e1f6097a068cdf0f3e53c6f`
- License: LGPL-3.0-or-later; the source lock and license are in
  `third_party/rime-essay-simp`.
- Used for: build-time high-frequency Simplified Chinese phrase weights in the
  generated offline Pinyin index. The source data is not retained on device.

## cppjieba and jieba dictionaries

- Upstream: https://github.com/yanyiwu/cppjieba and https://github.com/fxsjy/jieba
- License: MIT
- Used for: native Chinese segmentation and bundled dictionary data.
- The bundled jieba license is at `app/src/main/assets/jieba/LICENSE.txt`.

## Lucide

- Upstream: https://lucide.dev/
- License: ISC
- Used for: keyboard and settings icons.

## Android and Kotlin dependencies

AndroidX, Room, DataStore, OkHttp, Kotlin coroutines, and Kotlin serialization
are distributed under their respective upstream licenses. A release SBOM must
be attached to every published APK.
