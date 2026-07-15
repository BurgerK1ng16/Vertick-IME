# librime Android source lock

This directory contains the complete corresponding source for the
`librime_jni.so` binary shipped by Vertick IME. The binary was extracted from
the official Trime `v3.3.11` arm64 release and is not an unverified third-party
artifact.

## Locked upstream revisions

| Component | Repository | Revision |
| --- | --- | --- |
| Trime JNI integration | https://github.com/osfans/trime | `e4e67cdb9ebb1c59edbeed508bdbd572a3021beb` (`v3.3.11`) |
| librime | https://github.com/rime/librime | `33e78140250125871856cdc5b42ddc6a5fcd3cd4` |
| OpenCC | https://github.com/BYVoid/OpenCC | `907bfcbbd3aae86ff04bc8eaca67c8af03108ddf` |
| snappy | https://github.com/google/snappy | `32ded457c0b1fe78ceb8397632c416568d6714a0` |
| librime-lua | https://github.com/hchunhui/librime-lua | `68f9c364a2d25a04c7d4794981d7c796b05ab627` |
| librime-lua dependencies | https://github.com/hchunhui/librime-lua | `9c53b362229766a97b83683b9541c46679118a90` |
| librime-octagram | https://github.com/lotem/librime-octagram | `bd12863f45fbbd5c7db06d5ec8be8987b10253bf` |
| librime-predict | https://github.com/rime/librime-predict | `67c2881914a0242fc14ec7d6877782771b75800c` |
| glog | https://github.com/google/glog | `7b134a5c82c0c0b5698bb6bf7a835b230c5638e4` |
| googletest | https://github.com/google/googletest | `f8d7d77c06936315286eb55f8de22cd23c188571` |
| leveldb | https://github.com/google/leveldb | `99b3c03b3284f5886f9ef9a4ef703d57373e61be` |
| marisa-trie | https://github.com/s-yata/marisa-trie | `3e87d53b78e15f2f43783d5e376561a8c9722051` |
| librime OpenCC | https://github.com/BYVoid/OpenCC | `556ed22496d650bd0b13b6c163be9814637970ae` |
| yaml-cpp | https://github.com/jbeder/yaml-cpp | `2f86d13775d119edbb69af52e5f566fd65c6953b` |

The source is materialized under `trime/` in the original Trime layout. No
local source patches are applied.

Boost `1.89.0` is fetched by the locked upstream CMake script from the official
Boost release. Its expected SHA-256 is
`67acec02d0d118b5de9eb441f5fb707b3a1cdd884be00ca24b9a73c995511f74`.

## Distributed binary

- File: `app/src/main/jniLibs/arm64-v8a/librime_jni.so`
- SHA-256: `B09B6C4CA880F8949FC9D496A5AAABACE0106D174921BABCE34F95C4DF6474EA`
- Origin: official Trime `v3.3.11` arm64 APK
- Official APK SHA-256:
  `59BAE89D78186CB894AC8D072EED01044CB55322C3ED180E96423D860DE53BB1`

## Source archive checksums

The following official codeload archives were verified before materializing
`trime/`:

| Archive | SHA-256 |
| --- | --- |
| `trime-e4e67cdb9ebb1c59edbeed508bdbd572a3021beb.zip` | `28C9285FC15370A5FB2C502878E017BA2CD7B3AE1D665F66A5294E53A6571978` |
| `librime-33e78140250125871856cdc5b42ddc6a5fcd3cd4.zip` | `921010C5398817AADF9CC9181911A706C946BBD3AFD2F73B7CFC2811BDB422B1` |
| `opencc-907bfcbbd3aae86ff04bc8eaca67c8af03108ddf.zip` | `9726EC029085791E84FE1AE6EC2FA5A84185466C0FE7A73EB9FAA5571303459E` |
| `snappy-32ded457c0b1fe78ceb8397632c416568d6714a0.zip` | `8F11AD6DC2F5A437E8FB65C14180E8CF426400DD2D30D140CE0BF64DFB1B9600` |
| `librime-lua-68f9c364a2d25a04c7d4794981d7c796b05ab627.zip` | `4EB093E85C3CFF1AAE0DE121935D46F220E2548260A5622ADF1CF90F0AA788DD` |
| `librime-lua-deps-9c53b362229766a97b83683b9541c46679118a90.zip` | `5B2A3ED76BD269B1F22F799DCB87D0BFCC80EA3BFC09E6CC3CB8DB7017035CAC` |
| `librime-octagram-bd12863f45fbbd5c7db06d5ec8be8987b10253bf.zip` | `57FB40BC4A010E005721038BCE589AC2E9953CFB986AABAF25E44BEF9937DECE` |
| `librime-predict-67c2881914a0242fc14ec7d6877782771b75800c.zip` | `189DF91AF3333C6300C6D03455E23228C8CA73884EDBE7CBE337BB84BD0781C8` |

## Rebuild

Use Android NDK `26.1.10909125`, CMake `3.22+`, Ninja, and Android API `34`,
then run:

```powershell
./scripts/build-rime-jni.ps1
```

The script emits `librime_jni.so` for `arm64-v8a`. A locally rebuilt binary can
differ at the byte level because compiler and linker versions affect output;
the source revisions and build inputs above are the authoritative lock.

## License

Trime is GPL-3.0-or-later and librime is BSD-3-Clause. See their respective
`LICENSE` files inside `trime/`, and see `THIRD_PARTY_NOTICES.md` at the
repository root.
