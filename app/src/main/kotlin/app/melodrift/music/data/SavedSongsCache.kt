package app.melodrift.music.data

import app.melodrift.music.net.NcmApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 「已收藏歌曲」服务器数据缓存：所有自建歌单（含「我喜欢的音乐」之外的歌单）
 * 里出现的歌曲 id 并集。用于爱心点亮判断：歌曲在任何自建歌单中即视为已收藏。
 *
 * 数据来自服务端（逐个歌单详情），TTL 5 分钟；收藏/新建/删除歌单后调用 [invalidate]。
 */
object SavedSongsCache {

    @Volatile
    private var savedIds: Set<Long>? = null
    @Volatile
    private var at: Long = 0L

    private const val TTL = 5 * 60_000L

    val fresh: Boolean
        get() = savedIds != null && System.currentTimeMillis() - at < TTL

    /** 当前缓存的已收藏 id 集合（可能为 null = 尚未加载） */
    fun current(): Set<Long> = savedIds ?: emptySet()

    /** 歌曲是否已收藏（任一自建歌单） */
    fun isSaved(songId: Long): Boolean = savedIds?.contains(songId) == true

    fun invalidate() {
        savedIds = null
        at = 0L
    }

    /**
     * 从服务端拉取所有自建歌单的歌曲 id 并集（并行请求歌单详情）。
     * 返回新的集合；失败时保留旧缓存并返回当前集合。
     */
    suspend fun refresh(): Set<Long> {
        val ids = try {
            coroutineScope {
                val acc = NcmApi.account()
                val arr = NcmApi.userPlaylists(acc.userId, 100, 0)
                val own = (0 until arr.length())
                    .mapNotNull { arr.optJSONObject(it) }
                    .filter { !it.optBoolean("subscribed", false) }
                    .map { it.optLong("id", 0L) }
                    .filter { it > 0 }
                own.map { pid ->
                    async {
                        try {
                            NcmApi.playlistDetail(pid).songs.map { it.id }.toSet()
                        } catch (_: Exception) {
                            emptySet()
                        }
                    }
                }.awaitAll().flatten().toSet()
            }
        } catch (_: Exception) {
            savedIds ?: emptySet()
        }
        savedIds = ids
        at = System.currentTimeMillis()
        return ids
    }
}