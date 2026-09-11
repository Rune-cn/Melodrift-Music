package app.melodrift.music.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地"已收藏歌单"记录：songId → 收藏到的歌单 id 集合。
 *
 * 用途：红心状态 = 歌曲是否已在「我喜欢的音乐」（服务器 likeCheck）
 * 或 是否已通过本应用收藏到其他歌单（本地记录）。
 * 这样收藏到任意歌单后爱心都会点亮，且卸载前始终一致。
 */
object SavedPlaylists {
    private const val PREFS = "settings"
    private const val KEY = "saved_playlists"

    private fun loadMap(context: Context): JSONObject {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "{}") ?: "{}"
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun saveMap(context: Context, map: JSONObject) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, map.toString())
            .apply()
    }

    /** 某首歌已收藏到的歌单 id 集合 */
    fun playlistsFor(context: Context, songId: Long): Set<Long> {
        val map = loadMap(context)
        val arr = map.optJSONArray(songId.toString()) ?: return emptySet()
        return buildSet {
            for (i in 0 until arr.length()) add(arr.optLong(i, 0L))
        }.filter { it > 0 }.toSet()
    }

    /** 是否已收藏到任意歌单（本地记录） */
    fun isSaved(context: Context, songId: Long): Boolean = playlistsFor(context, songId).isNotEmpty()

    /** 记录：songId 收藏到了 pid 歌单 */
    fun add(context: Context, songId: Long, playlistId: Long) {
        if (playlistId <= 0) return
        val map = loadMap(context)
        val arr = map.optJSONArray(songId.toString()) ?: JSONArray()
        val set = buildSet {
            for (i in 0 until arr.length()) add(arr.optLong(i, 0L))
        }.toMutableSet()
        set.add(playlistId)
        val out = JSONArray()
        set.filter { it > 0 }.forEach { out.put(it) }
        map.put(songId.toString(), out)
        saveMap(context, map)
    }

    /** 记录：songId 从 pid 歌单取消收藏 */
    fun remove(context: Context, songId: Long, playlistId: Long) {
        val map = loadMap(context)
        val arr = map.optJSONArray(songId.toString()) ?: return
        val set = buildSet {
            for (i in 0 until arr.length()) add(arr.optLong(i, 0L))
        }.toMutableSet()
        set.remove(playlistId)
        if (set.isEmpty()) {
            map.remove(songId.toString())
        } else {
            val out = JSONArray()
            set.filter { it > 0 }.forEach { out.put(it) }
            map.put(songId.toString(), out)
        }
        saveMap(context, map)
    }
}