package com.example.jiangling.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * 字体排版表。
 *
 * MaterialTheme.typography 里的每个名字对应一种文字样式，常用：
 *   displayLarge / headlineLarge      —— 超大标题
 *   titleLarge / titleMedium          —— 页面标题、卡片标题（本项目大量使用）
 *   bodyLarge / bodyMedium / bodySmall —— 正文（大 / 中 / 小）
 *   labelLarge / labelSmall           —— 按钮、标签等小文字
 *
 * 每个 TextStyle 可以改：
 *   - fontSize：字号，单位 sp（会跟随系统字体大小）；
 *   - fontWeight：字重，如 Normal / Medium / Bold；
 *   - fontFamily：字体，默认 FontFamily.Default（系统字体）；
 *   - lineHeight：行高；letterSpacing：字间距。
 *
 * 下面只覆盖了 bodyLarge，其余沿用 Material3 默认值。
 * 想整体换字体（比如思源黑体），把 fontFamily 全部替换成自定义 FontFamily 即可。
 */
val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
    /* 其他可覆盖的样式示例（取消注释即可生效）：
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
    */
)
