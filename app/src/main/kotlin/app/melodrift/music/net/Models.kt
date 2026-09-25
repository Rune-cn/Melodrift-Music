package app.melodrift.music.net

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/** 轻量数据模型（org.json 解析，避免额外序列化依赖） */

@Immutable
data class Artist(val id: Long, val name: String)

@Immutable
data class Album(val id: Long, val name: String, val picUrl: String?)

@Immutable
data class Song(
    val id: Long,
    val name: String,
    val artists: List<Artist>,
    val album: Album?,
    val durationMs: Long = 0,
    val picUrl: String?,
    /** 版权/付费标记：0 免费，1 VIP/会员歌曲，8 可试听 */
    val fee: Int = 0
) {
    val artistNames: String
        get() = artists.joinToString(" / ") { it.name }

    /** 需要会员才能播放的歌曲（fee=1） */
    val isVip: Boolean
        get() = fee == 1

    /** 封面兜底：专辑图或方形缩放 */
    val coverUrl: String?
        get() = picUrl ?: album?.picUrl
}

@Immutable
data class Playlist(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val playCount: Long = 0,
    val trackCount: Int = 0,
    val creatorName: String = "",
    val subscribed: Boolean = false
)

/** 专辑详情（官方 `/api/v1/album/{id}`）：v3 歌曲详情里的 album 不含发行时间/公司，需另查 */
@Immutable
data class AlbumDetail(
    val id: Long,
    val name: String,
    val company: String,
    val publishTimeMs: Long,
    val picUrl: String?,
    val description: String = ""
)

/**
 * 歌曲评论（网易云 `/api/comment/resource/comments/get`）。
 *
 * 字段全部来自服务端 JSON，解析收口在 [Json.parseComments]；这一层不碰 Android /
 * Compose API，换 UI 框架（Flutter 移植）时可照字段表直接平移。
 */
@Immutable
data class Comment(
    val id: Long,
    val threadId: String,
    val userId: Long,
    val nickname: String,
    val avatarUrl: String?,
    val content: String,
    val timeMs: Long,
    val likedCount: Int,
    val liked: Boolean,
    val ipLocation: String,
    /** 追评（楼中楼）条数：>0 才显示「查看 N 条追评」 */
    val replyCount: Int = 0,
    /** 本人发布 */
    val owner: Boolean = false
)

/** 评论排序：推荐（服务端算法）/ 最热（按点赞）/ 最新（按时间） */
enum class CommentSort { RECOMMEND, HOT, LATEST }

/** 一页评论（三个排序各走各的接口，见 NcmApi.comments） */
@Immutable
data class CommentPage(
    val total: Int,
    val comments: List<Comment>,
    val hasMore: Boolean
)

/** 一条评论的追评分页；`nextTime` 是下一页游标（服务端 data.time） */
@Immutable
data class FloorPage(
    val total: Int,
    val comments: List<Comment>,
    val hasMore: Boolean,
    val nextTime: Long
)

/** 歌单详情（含歌曲列表） */
@Immutable
data class PlaylistDetail(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val creatorName: String,
    val playCount: Long,
    val trackCount: Int,
    val description: String,
    val songs: List<Song>,
    val canPlay: Boolean,
    val subscribed: Boolean = false
)

object Json {

    /** org.json 的 optString 遇 JSON null 会返回 "null" 字符串，这里统一归一为 null/空串 */
    fun strOrNull(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        val s = o.optString(key).trim()
        return s.ifBlank { null }
    }

    fun parseArtist(o: JSONObject) = Artist(
        id = o.optLong("id", 0L),
        name = strOrNull(o, "name") ?: "未知"
    )

    fun parseAlbum(o: JSONObject?) = o?.let {
        Album(
            id = it.optLong("id", 0L),
            name = strOrNull(it, "name") ?: "",
            picUrl = strOrNull(it, "picUrl")
        )
    }

    fun parseSong(o: JSONObject): Song {
        val ar = o.optJSONArray("ar") ?: o.optJSONArray("artists")
        val artists = mutableListOf<Artist>()
        if (ar != null) {
            for (i in 0 until ar.length()) {
                artists.add(parseArtist(ar.getJSONObject(i)))
            }
        }
        val al = o.optJSONObject("al") ?: o.optJSONObject("album")
        val album = parseAlbum(al)
        // 版权/付费：优先 privilege.fee（权威），回退 song.fee
        val fee = o.optJSONObject("privilege")?.optInt("fee", o.optInt("fee", 0))
            ?: o.optInt("fee", 0)
        return Song(
            id = o.optLong("id", 0L),
            name = strOrNull(o, "name") ?: "未知歌曲",
            artists = artists,
            album = album,
            durationMs = o.optLong("dt", o.optLong("duration", 0L)),
            picUrl = strOrNull(o, "picUrl"),
            fee = fee
        )
    }

    /**
     * 解析评论数组（评论列表 / 热评 / 追评共用同一套字段）。
     * content 缺失或为空的占位条目直接丢掉。
     */
    fun parseComments(arr: JSONArray?): List<Comment> {
        if (arr == null) return emptyList()
        val out = ArrayList<Comment>(arr.length())
        for (k in 0 until arr.length()) {
            val o = arr.optJSONObject(k) ?: continue
            val content = o.optString("content", "").trim()
            if (content.isEmpty()) continue
            val u = o.optJSONObject("user")
            out.add(
                Comment(
                    id = o.optLong("commentId", 0L),
                    threadId = o.optString("threadId", ""),
                    userId = u?.optLong("userId", 0L) ?: 0L,
                    nickname = u?.optString("nickname", "").orEmpty().trim(),
                    avatarUrl = u?.let { strOrNull(it, "avatarUrl") },
                    content = content,
                    timeMs = o.optLong("time", 0L),
                    likedCount = o.optInt("likedCount", 0),
                    liked = o.optBoolean("liked", false),
                    ipLocation = o.optJSONObject("ipLocation")?.optString("location", "")
                        .orEmpty().trim(),
                    replyCount = o.optInt("replyCount", 0),
                    owner = o.optBoolean("owner", false)
                )
            )
        }
        return out
    }

    fun parsePlaylist(o: JSONObject): Playlist {
        // 不同接口封面字段不同：歌单详情 coverImgUrl / 个性化推荐 picUrl
        val cover = strOrNull(o, "coverImgUrl")
            ?: strOrNull(o, "picUrl")
            ?: o.optJSONObject("creator")?.let { strOrNull(it, "avatarUrl") }
        return Playlist(
            id = o.optLong("id", 0L),
            name = strOrNull(o, "name") ?: "未知歌单",
            coverUrl = cover,
            playCount = o.optLong("playCount", 0L),
            trackCount = o.optInt("trackCount", 0),
            creatorName = o.optJSONObject("creator")?.let { strOrNull(it, "nickname") } ?: "",
            subscribed = o.optBoolean("subscribed", false)
        )
    }
}
