package com.example.jiangling.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/*
 * ========================== 主题（配色）怎么改 ==========================
 *
 * Material3 的组件（Button、Card、Text…）默认会从 MaterialTheme 里取颜色。
 * 例如 MaterialTheme.colorScheme.primary 是主色，
 * colorScheme.onSurfaceVariant 是“次要文字色”，colorScheme.surface 是表面色。
 *
 * 想换掉整个 App 的风格：
 * 1) 最简单：把下面 JiangLingTheme 的 dynamicColor 改成 false，
 *    然后修改 Color.kt 里的 Purple40（浅色主色）/ Purple80（深色主色）；
 * 2) 更彻底：在 lightColorScheme/darkColorScheme 里补上 background、surface、
 *    onPrimary、outline 等颜色（注释里有示例）；
 * 3) 只想改某一处：在对应 Composable 里直接写 color = Color(0xFFxxxxxx)，
 *    比如首页摄像头占位、日志等级色块就是这样做的。
 * =======================================================================
 */

/** 深色模式配色：只覆盖了主色/次色/第三色，其余由 Material3 自动推导。 */
private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

/** 浅色模式配色：数值来自 Color.kt，改那里即可。 */
private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* 其他可覆盖的颜色（取消注释即可生效）：
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

/**
 * App 主题入口。所有页面外层都套了一层 JiangLingTheme。
 *
 * @param darkTheme 是否使用深色配色，默认跟随系统设置。
 * @param dynamicColor Android 12+ 的“动态取色”：从壁纸生成配色。
 *        默认 true，所以 Android 12 及以上手机不会显示 Color.kt 里的颜色；
 *        想固定成自己的品牌色，把这个默认值改成 false。
 * @param content 被主题包裹的界面内容（插槽写法，和其他 Composable 一样）。
 */
@Composable
fun JiangLingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Android 12（API 31，代号 S）及以上且开启动态取色
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            // LocalContext 只能在 @Composable 里调用
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // MaterialTheme 会把 colorScheme、typography、shapes 放进 CompositionLocal，
    // 子级 Composable 通过 MaterialTheme.colorScheme / typography 读取。
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
