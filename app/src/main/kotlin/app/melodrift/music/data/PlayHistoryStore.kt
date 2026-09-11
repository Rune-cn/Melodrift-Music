package app.melodrift.music.data

import android.content.Context
import org.json.JSONArray

/**
 * 听歌历史持久化：歌曲 id 列表（最新在前，去重，最多 50 条）。
 * 重启后从本地恢复，首页「播放历史」区可继续展示。
 */
object PlayHistoryStore {
    private const val PREFS = "settings"
    private const val KEY = "play_history"
    private const val MAX = 50

    fun loadIds(context: Context): List<Long> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length())
                .map { arr.optLong(it, 0L) }
                .filter { it > 0 }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addId(context: Context, songId: Long) {
        if (songId <= 0) return
        val cur = loadIds(context).toMutableList()
        cur.remove(songId)
        cur.add(0, songId)
        if (cur.size > MAX) cur.subList(MAX, cur.size).clear()
        save(context, cur)
    }

    private fun save(context: Context, ids: List<Long>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }
}