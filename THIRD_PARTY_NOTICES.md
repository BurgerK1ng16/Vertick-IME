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
- Dictionary source revisions and the prebuilt package contract are recorded in
  `dictionaries/SOURCE_LOCK.md`. The built-in package contains only
  `8105 + base + others`; optional packages are independently downloaded.

## RIME-LMDG / Wanxiang grammar data

- Upstream: https://github.com/amzxyz/RIME-LMDG
- License: CC-BY-4.0. Optional grammar packages must retain attribution and the
  exact upstream release digest in their manifest. They are not bundled by default.
- Used for: the optional `wanxiang-lts-zh-hans` long-sentence language model.

Sogou hotword data is never redistributed by this project. The local dictionary
importer accepts files selected by the user and stores them only in app-private
storage.

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
