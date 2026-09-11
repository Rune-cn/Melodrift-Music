package app.melodrift.music.net

import androidx.compose.runtime.Immutable
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
