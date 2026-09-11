package app.melodrift.music.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Melodrift Music 品牌配色。
 *
 * 浅色背景固定为用户指定的 #F0F1F3，主色取主题音符的深灰蓝 #4A4F55，
 * 全色板围绕这套中性蓝灰体系（不随系统动态取色，保证品牌一致性）。
 */
fun melodriftColorScheme(dark: Boolean): ColorScheme =
    if (dark) MelodriftDark else MelodriftLight

/** Melodrift · Light（背景 #F0F1F3，主色 #4A4F55） */
private val MelodriftLight = lightColorScheme(
    primary = Color(0xFF4A4F55),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9DDE2),
    onPrimaryContainer = Color(0xFF22272C),
    secondary = Color(0xFF656A71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF1F3F6),
    onSecondaryContainer = Color(0xFF292E33),
    tertiary = Color(0xFF5A6572),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDE9F7),
    onTertiaryContainer = Color(0xFF222C36),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF0F1F3),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF0F1F3),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE1E6),
    onSurfaceVariant = Color(0xFF42474D),
    outline = Color(0xFF6F747B),
    outlineVariant = Color(0xFFC0C4CB),
    inverseSurface = Color(0xFF31343A),
    inverseOnSurface = Color(0xFFF0F1F3),
    inversePrimary = Color(0xFFC6CBD2),
    surfaceTint = Color(0xFF4A4F55)
)

/** Melodrift · Dark（中性暗色背景 #121316） */
private val MelodriftDark = darkColorScheme(
    primary = Color(0xFFC5CAD2),
    onPrimary = Color(0xFF2C3036),
    primaryContainer = Color(0xFF3F444B),
    onPrimaryContainer = Color(0xFFE3E7EC),
    secondary = Color(0xFFC3C8CF),
    onSecondary = Color(0xFF2D3136),
    secondaryContainer = Color(0xFF44494F),
    onSecondaryContainer = Color(0xFFE4E8ED),
    tertiary = Color(0xFFC3CCD8),
    onTertiary = Color(0xFF2C343D),
    tertiaryContainer = Color(0xFF424B55),
    onTertiaryContainer = Color(0xFFE1EAF6),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF121316),
    onBackground = Color(0xFFE2E4E7),
    surface = Color(0xFF121316),
    onSurface = Color(0xFFE2E4E7),
    surfaceVariant = Color(0xFF40444A),
    onSurfaceVariant = Color(0xFFC2C6CC),
    outline = Color(0xFF8B9097),
    outlineVariant = Color(0xFF40444A),
    inverseSurface = Color(0xFFE2E4E7),
    inverseOnSurface = Color(0xFF2E3136),
    inversePrimary = Color(0xFF4A4F55),
    surfaceTint = Color(0xFFC5CAD2)
)