# 江灵（JiangLing）

ESP32-S3 小车：Android 控制端 + 主板固件 + 副板 + 硬件/协议文档，单仓库管理。

## 仓库结构

| 目录 | 内容 | 入口 |
|---|---|---|
| `index/` | 唯一文档区：通信协议、硬件配置与接线、项目策略、图纸 | `index/江灵通信协议/总览.md` |
| `JiangLing_app/` | Android 控制端（Kotlin + Compose） | `JiangLing_app/`（Android Studio 打开） |
| `JiangLing_ctrl/` | 主板固件（ESP32-S3 / PlatformIO） | `JiangLing_ctrl/platformio.ini` |
| `JiangLing_vedio/` | 副板（占位） | — |

## 文档导航（`index/`）

| 子目录 | 装什么 | 入口文件 |
|---|---|---|
| `江灵通信协议/` | 协议权威文档：帧格式、命令、回执、日志、数据、语音、操控 | `index/江灵通信协议/总览.md` |
| `硬件配置/` | 引脚、电源、外设、接线 | `index/硬件配置/01-硬件配置文档.md` |
| `项目策略/` | 「为什么这么定」：AI 与网络方案、通道选型对比、语音方案 | `index/项目策略/04-语音识别与AI对话方案.md` |
| `diagrams/` | 原理图与接线图资产（SVG / PNG），**保持原位，不要移动** | `index/diagrams/接线图.svg` |

## 新人阅读顺序

1. **先看硬件**：`index/硬件配置/01-硬件配置文档.md`（选型与规格）→ `index/硬件配置/05-接线文档.md`（元件 → GPIO 对照）。
2. **再看协议**：`index/江灵通信协议/总览.md`（拓扑、通道、帧格式）→ `命令.md` → `回复.md` → `数据.md` → `日志.md`；语音、操控单独成册。
3. **最后看策略**：`index/项目策略/` 下的方案说明，理解通道划分与取舍，再动手改代码。
4. **动手**：固件用 PlatformIO 打开 `JiangLing_ctrl/`；App 用 Android Studio 打开 `JiangLing_app/`。

## 维护约定

- 协议改动：只改 `index/江灵通信协议/` 下的七份分册（总览 / 命令 / 回复 / 数据 / 日志 / 语音 / 操控），这是唯一事实来源；不再生成单文件拼接版，App（`JiangLing_app/`）与固件（`JiangLing_ctrl/`）侧也不保留任何副本，需要时直接来这里查。
- 引脚改动：`index/硬件配置/05-接线文档.md`、`index/diagrams/接线图.svg`、`JiangLing_ctrl/src/base/m.h`、`JiangLing_ctrl/src/main/audio/AudioBus.h`、`JiangLing_ctrl/src/main/motion/Motor.cpp` 五处一起改；`index/硬件配置/01-硬件配置文档.md` 不放引脚表。
- 策略结论变更：改 `index/项目策略/`，不要把「为什么」写回协议分册。
- 通道职责、优先级、断链停车、持久化、双板分工的**现行口径**在 `index/江灵通信协议/总览.md` §2 / §3（协议上下文不可拆），`index/项目策略/` 只放选型与背景论证。
- SVG 图纸按功能分区维护（供电 / 电机 / 主控 / 显示 / 音频 / 下载日志），改引脚时同步改图与 `05-接线文档.md`；引用路径变了就改 Markdown 里的链接。

## 远程仓库

- Gitee：https://gitee.com/moliangxx/jiangling
- GitHub：https://github.com/moliangxx/jiangling
