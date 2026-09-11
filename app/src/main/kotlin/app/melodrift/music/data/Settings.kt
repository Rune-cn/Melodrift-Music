package app.melodrift.music.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用设置数据模型。
 *
 * 增删字段后，[SettingsRepository] 自动处理读写，
 * 无需手动序列化。
 */
data class SettingsData(
    /** 深色模式："system" 跟随系统 / "light" 浅色 / "dark" 深色 */
    val darkMode: String = "system",
    /** 界面语言："system" / "zh" / "en" */
    val language: String = "system",
    /** 网易云音乐网页版 Cookie（登录凭证，含 MUSIC_U） */
    val cookie: String = "",
    /** 默认播放音质：standard / exhigh / lossless / hires */
    val defaultQuality: String = "standard",
    /** 淡入淡出（切歌交叉过渡）开关 */
    val crossfadeEnabled: Boolean = false,
    /** 淡入淡出时长（秒，1~8）：新歌渐强、旧歌减弱共用 */
    val crossfadeSeconds: Int = 4,
    /** 允许与其他应用同时播放（不抢占音频焦点） */
    val allowMixedAudio: Boolean = false,
    /** 恢复播放位置：再次播放同一首歌时从上次位置继续 */
    val resumePlaybackPosition: Boolean = false,
    /** 播放器底部迷你条样式：square / rounded */
    val miniBarStyle: String = "rounded",
    /** 播放页进度条样式：standard / wave */
    val progressStyle: String = "standard",
    /** 歌词页上下边缘渐变遮罩 */
    val lyricsFade: Boolean = false,
    /** 播放页显示系统状态栏：开=显示，关=进入播放页隐藏（离开自动恢复） */
    val playerStatusBar: Boolean = true
)

/**
 * 设置持久化存储。
 *
 * 基于 SharedPreferences，以 [SettingsData] 字段名
 * 为 key，持久化到 SP。每次修改自动保存。
 */
class SettingsRepository(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 读取全部设置 */
    fun load(): SettingsData = SettingsData(
        darkMode = readDarkMode(sp),
        language = sp.getString("language", "system") ?: "system",
        cookie = sp.getString("cookie", "") ?: "",
        defaultQuality = sp.getString("defaultQuality", "standard") ?: "standard",
        crossfadeEnabled = sp.getBoolean("crossfadeEnabled", false),
        crossfadeSeconds = sp.getInt("crossfadeSeconds", 4),
        allowMixedAudio = sp.getBoolean("allowMixedAudio", false),
        resumePlaybackPosition = sp.getBoolean("resumePlaybackPosition", false),
        miniBarStyle = sp.getString("miniBarStyle", "rounded") ?: "rounded",
        progressStyle = sp.getString("progressStyle", "standard") ?: "standard",
        lyricsFade = sp.getBoolean("lyricsFade", false),
        playerStatusBar = sp.getBoolean("playerStatusBar", true)
    )

    /** 修改单项并立即保存 */
    fun save(block: SettingsData.() -> SettingsData) {
        val current = load()
        val updated = current.block()
        sp.edit()
            .putString("darkMode", updated.darkMode)
            .putString("language", updated.language)
            .putString("cookie", updated.cookie)
            .putString("defaultQuality", updated.defaultQuality)
            .putBoolean("crossfadeEnabled", updated.crossfadeEnabled)
            .putInt("crossfadeSeconds", updated.crossfadeSeconds)
            .putBoolean("allowMixedAudio", updated.allowMixedAudio)
            .putBoolean("resumePlaybackPosition", updated.resumePlaybackPosition)
            .putString("miniBarStyle", updated.miniBarStyle)
            .putString("progressStyle", updated.progressStyle)
            .putBoolean("lyricsFade", updated.lyricsFade)
            .putBoolean("playerStatusBar", updated.playerStatusBar)
            .apply()
    }
}

/**
 * 读取深色模式（"system" / "light" / "dark"）。
 *
 * 兼容旧版本：早期 `darkMode` 键存的是 Boolean（false=浅色 / true=深色），
 * 直接 getString 会抛 ClassCastException，因此先看实际存储类型：
 * Boolean 按原语义迁移、String 直接采用、从未写过则默认跟随系统。
 * 保存时统一写回 String，键名不变。
 */
private fun readDarkMode(sp: SharedPreferences): String =
    when (val stored = sp.all["darkMode"]) {
        is String -> stored
        is Boolean -> if (stored) "dark" else "light"
        else -> "system"
    }
