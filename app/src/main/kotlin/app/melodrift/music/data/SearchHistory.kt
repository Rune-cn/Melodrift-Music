package app.melodrift.music.data

import android.content.Context
import org.json.JSONArray

/**
 * 搜索历史（SharedPreferences 存储）。
 * 最新在前，自动去重，最多保留 20 条。
 */
object SearchHistory {
    private const val PREFS = "settings"
    private const val KEY = "search_history"
    private const val MAX = 20

    fun load(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length())
                .map { arr.optString(it, "") }
                .filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, keyword: String) {
        val kw = keyword.trim()
        if (kw.isBlank()) return
        val cur = load(context).toMutableList()
        cur.remove(kw)
        cur.add(0, kw)
        if (cur.size > MAX) cur.subList(MAX, cur.size).clear()
        save(context, cur)
    }

    fun remove(context: Context, keyword: String) {
        save(context, load(context).filter { it != keyword })
    }

    fun clear(context: Context) {
        save(context, emptyList())
    }

    private fun save(context: Context, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }
}