package com.example.jiangling.ui

import androidx.compose.ui.graphics.Color

/*
 * 颜色写法：Color(0xAARRGGBB)
 *   AA = 透明度（00 全透明 ~ FF 不透明），RR = 红，GG = 绿，BB = 蓝。
 * 例如 Color(0xFF2E7D32) 是纯不透明的深绿色。
 *
 * 名称里的 80/40 是历史命名：80 系列用于深色模式，40 系列用于浅色模式。
 * 想改 App 主色调，优先改 Purple40（浅色）与 Purple80（深色）。
 * 注意：如果 Theme.kt 里 dynamicColor = true，
 * Android 12+ 手机会用壁纸动态取色，看不到这里的颜色。
 */

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625B71)
val Pink40 = Color(0xFF7D5260)
