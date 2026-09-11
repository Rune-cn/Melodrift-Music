package app.melodrift.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.melodrift.music.data.SettingsData

/**
 * Melodrift Music 主题。
 *
 * 固定品牌配色（[melodriftColorScheme]），不随系统动态取色，
 * 保证浅色背景恒为 #F0F1F3 的品牌一致性；
 * [SettingsData.darkMode] 三态控制：跟随系统 / 浅色 / 深色。
 */
@Composable
fun MelodriftMusicTheme(
    settings: SettingsData = SettingsData(),
    content: @Composable () -> Unit
) {
    // 三态：强制深色 / 强制浅色 / 跟随系统（isSystemInDarkTheme 随系统配置实时变化）
    val darkTheme = when (settings.darkMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = melodriftColorScheme(dark = darkTheme),
        content = content
    )
}