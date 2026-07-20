# Offline Dictionary Source Lock

The default bundle is derived from Rime-Ice data included in this repository.

| Component | Locked revision / version | License |
| --- | --- | --- |
| Rime-Ice dictionaries | workspace snapshot `5ea4a4d378a19bdd0d462f87f3d8970e4563dc3c` | GPL-3.0-or-later |
| `8105.dict.yaml` | `2026-07-01` | GPL-3.0-or-later |
| `base.dict.yaml` | `2026-07-03` | GPL-3.0-or-later |
| `others.dict.yaml` | `2026-03-08` | GPL-3.0-or-later |
| librime | `33e78140250125871856cdc5b42ddc6a5fcd3cd4` | BSD-3-Clause |
| librime-octagram | `bd12863f45fbbd5c7db06d5ec8be8987b10253bf` | BSD-3-Clause |
| Wanxiang `wanxiang-lts-zh-hans` | release digest recorded in the grammar package manifest | CC-BY-4.0 |

`rime-ice-base` includes `8105 + base + others`. `ext + tencent`, English,
Emoji, and Wanxiang grammar are separate complete prebuilt bundles. No Sogou
data is included in source control, APK assets, downloads, or release assets.

## Packaging contract

Each release bundle contains exactly these runtime files:

```
default.yaml
weike_pinyin.schema.yaml
weike_t9.schema.yaml
build/weike_pinyin.table.bin
build/weike_pinyin.prism.bin
build/weike_pinyin.reverse.bin
build/default.yaml
build/weike_pinyin.schema.yaml
build/weike_t9.schema.yaml
```

The application verifies the signed manifest, package SHA-256, package size,
safe archive paths, and this required file set before activating a package.
