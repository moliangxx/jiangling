# 江灵（JingLing）

ESP32-S3 小车：Android 控制端 + 主板固件 + 副板 + 硬件/协议文档，单仓库管理。

| 目录 | 内容 |
|---|---|
| `index/` | 唯一文档区：通信协议、硬件配置与接线、项目策略、图纸 |
| `JiangLing_app/` | Android 控制端（Kotlin + Compose） |
| `JiangLing_ctrl/` | 主板固件（ESP32-S3 / PlatformIO） |
| `JiangLing_vedio/` | 副板（占位） |

## 从这里开始

- 文档导航：`index/README.md`
- 协议正文唯一来源：`index/江灵通信协议/`（总览 / 命令 / 回复 / 数据 / 日志 / 语音 / 操控），App 与固件均不保留副本。
- 固件：用 PlatformIO 打开 `JiangLing_ctrl/`，配置见 `JiangLing_ctrl/platformio.ini`。
- App：用 Android Studio 打开 `JiangLing_app/`。

## 远程仓库

- Gitee：https://gitee.com/moliangxx/jiangling
- GitHub：https://github.com/moliangxx/jiangling
