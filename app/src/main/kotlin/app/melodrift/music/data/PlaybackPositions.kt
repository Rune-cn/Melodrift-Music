package app.melodrift.music.data

import android.content.Context

/**
 * 每首歌的播放位置记录（SharedPreferences）。
 * 供「恢复专辑歌曲播放位置」使用：再次播放同一首歌时从上次位置继续。
 */
object PlaybackPositions {
    private const val PREFS = "settings"
    private const val KEY = "playback_positions"

    /** 读取某首歌的保存位置（毫秒）；无记录返回 0 */
    fun load(context: Context, songId: Long): Long {
        if (songId <= 0) return 0L
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "{}") ?: "{}"
        return try {
            org.json.JSONObject(raw).optLong(songId.toString(), 0L)
        } catch (_: Exception) {
            0L
        }
    }

    /** 保存某首歌的位置（毫秒） */
    fun save(context: Context, songId: Long, ms: Long) {
        if (songId <= 0) return
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, "{}") ?: "{}"
        val obj = try {
            org.json.JSONObject(raw)
        } catch (_: Exception) {
            org.json.JSONObject()
        }
        obj.put(songId.toString(), ms.coerceAtLeast(0L))
        sp.edit().putString(KEY, obj.toString()).apply()
    }

    /** 清除某首歌的位置（播完或取消时） */
    fun clear(context: Context, songId: Long) {
        if (songId <= 0) return
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, "{}") ?: "{}"
        val obj = try {
            org.json.JSONObject(raw)
        } catch (_: Exception) {
            org.json.JSONObject()
        }
        if (obj.has(songId.toString())) {
            obj.remove(songId.toString())
            sp.edit().putString(KEY, obj.toString()).apply()
        }
    }
}