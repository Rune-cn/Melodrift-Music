package app.melodrift.music.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 播放队列快照（持久化）。
 *
 * 目的：重新打开 App 后，底部迷你条仍显示上次退出前播放的歌曲（**不自动播放**）。
 * 只存歌曲 id 列表 + 当前下标 + 进度；恢复时经 `NcmApi.songsDetail` 拉回元数据。
 *
 * 写入时机：切歌、队列增删/排序、播放中每 5 秒（见 PlayerController.persistQueue）。
 */
object QueueStore {

    /** 队列快照：ids 顺序即播放顺序 */
    data class Snapshot(val ids: List<Long>, val index: Int, val positionMs: Long)

    private const val KEY = "queue_snapshot"

    private fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun save(context: Context, ids: List<Long>, index: Int, positionMs: Long) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        val json = JSONObject()
            .put("ids", arr)
            .put("index", index)
            .put("pos", positionMs)
        sp(context).edit().putString(KEY, json.toString()).apply()
    }

    fun load(context: Context): Snapshot? {
        val raw = sp(context).getString(KEY, null) ?: return null
        return try {
            val j = JSONObject(raw)
            val arr = j.optJSONArray("ids") ?: return null
            val ids = buildList(arr.length()) {
                for (i in 0 until arr.length()) add(arr.optLong(i))
            }
            if (ids.isEmpty()) return null
            Snapshot(ids, j.optInt("index"), j.optLong("pos"))
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        sp(context).edit().remove(KEY).apply()
    }
}
