package app.melodrift.music.net

import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 网易云音乐 web 版 API 客户端（weapi 加密）。
 *
 * 全部走 POST /weapi/{path}，body = params + encSecKey。
 * [cookie] 由设置页注入（含 MUSIC_U / __csrf）。
 */
object NcmApi {

    private const val HOST = "https://music.163.com"

    /** 浏览器 UA（API 请求与播放 CDN 下载共用，网易 CDN 拒绝非浏览器 UA） */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 由 Settings 注入；空值 → 匿名请求（部分接口仍可用） */
    @Volatile
    var cookie: String = ""

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 暴露给 ExoPlayer 的 OkHttp 数据源复用 */
    fun httpClient(): OkHttpClient = client

    /**
     * 从 cookie 提取 `__csrf`。
     *
     * **必须取最后一个**：粘贴进来的 cookie 常含两份 `__csrf`（旧会话残留 + 当前值），
     * 服务端只认最后那份；取第一个会让所有写操作返回 `403 illegal request!`
     * （删除歌单失败的真实原因即在此）。
     */
    fun csrfToken(): String {
        var token = ""
        for (part in cookie.split(';')) {
            val kv = part.trim()
            if (kv.startsWith("__csrf=")) token = kv.removePrefix("__csrf=")
        }
        return token.trim()
    }

    private fun headers(writeCookie: Boolean = false): Headers = Headers.Builder()
        .add("User-Agent", USER_AGENT)
        .add("Referer", "https://music.163.com/")
        .add("Origin", "https://music.163.com")
        .add("Content-Type", "application/x-www-form-urlencoded")
        .apply {
            if (cookie.isNotBlank()) {
                // 部分写接口（建歌单等）校验客户端标识：官方 web 端场景 cookie 需带 os=pc & appver
                add(
                    "Cookie",
                    if (writeCookie) "$cookie; os=pc; appver=2.9.7" else cookie
                )
            }
        }
        .build()

    /** weapi POST，返回响应文本；非 2xx 抛 IOException */
    fun weapiPost(path: String, data: Map<String, Any>, writeCookie: Boolean = false): String {
        val enc = NcmCrypto.weapiEncrypt(data)
        val form = FormBody.Builder()
            .add("params", enc.getValue("params"))
            .add("encSecKey", enc.getValue("encSecKey"))
            .build()
        val request = Request.Builder()
            .url(HOST + "/weapi" + path.removePrefix("/api"))
            .post(form)
            .headers(headers(writeCookie))
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $text")
            return text
        }
    }

    private fun checkCode(j: JSONObject, where: String) {
        val code = j.optInt("code", -1)
        if (code != 200) {
            throw IOException("$where: code=$code ${j.optString("message")} ${j.optString("msg")}".trim())
        }
    }

    // ───────────────────────── 账号 ─────────────────────────

    data class AccountInfo(
        val userId: Long,
        val nickname: String,
        val avatarUrl: String?
    )

    /** 当前登录账户 */
    fun account(): AccountInfo {
        val j = JSONObject(weapiPost("/api/w/nuser/account/get", mapOf("csrf_token" to csrfToken())))
        checkCode(j, "account")
        val profile = j.optJSONObject("profile")
            ?: throw IOException("未登录：cookie 中缺少有效的 MUSIC_U")
        return AccountInfo(
            userId = profile.optLong("userId", 0L),
            nickname = Json.strOrNull(profile, "nickname") ?: "陌生人",
            avatarUrl = Json.strOrNull(profile, "avatarUrl")
        )
    }

    // ───────────────────────── 推荐 ─────────────────────────

    /** 每日推荐（v3：dailySongs 结构） */
    fun dailySongs(): List<Song> {
        val j = JSONObject(weapiPost("/api/v3/discovery/recommend/songs", mapOf("csrf_token" to csrfToken())))
        checkCode(j, "daily recommend")
        val arr = j.optJSONArray("dailySongs")
            ?: j.optJSONObject("data")?.optJSONArray("dailySongs")
            ?: throw IOException("每日推荐为空（可能未登录）")
        return parseSongs(arr)
    }

    /** 个性化推荐歌单 */
    fun personalizedPlaylists(limit: Int = 8): List<Playlist> {
        val j = JSONObject(weapiPost("/api/personalized/playlist", mapOf("limit" to limit, "offset" to 0)))
        checkCode(j, "personalized")
        val result = j.optJSONObject("result")
        val arr: JSONArray? = when {
            result != null -> result.optJSONArray("playlists")
            else -> j.optJSONArray("result")
        }
        return parsePlaylists(arr ?: JSONArray())
    }

    // ───────────────────────── 排行榜 ─────────────────────────

    data class Toplist(val id: Long, val name: String, val coverUrl: String?, val updateFreq: String)

    /** 排行榜总览（63 个榜单） */
    fun toplists(): List<Toplist> {
        val j = JSONObject(weapiPost("/api/toplist", mapOf("csrf_token" to csrfToken())))
        checkCode(j, "toplist")
        val arr = j.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    Toplist(
                        id = o.optLong("id", 0L),
                        name = Json.strOrNull(o, "name") ?: "",
                        coverUrl = Json.strOrNull(o, "coverImgUrl")
                            ?: Json.strOrNull(o, "coverImageUrl"),
                        updateFreq = Json.strOrNull(o, "updateFrequency") ?: ""
                    )
                )
            }
        }
    }

    // ───────────────────────── 歌单 / 歌曲 ─────────────────────────

    /** 歌单详情（v6：tracks 直接返回，n 大拉全量） */
    fun playlistDetail(id: Long): PlaylistDetail {
        val j = JSONObject(
            weapiPost(
                "/api/v6/playlist/detail",
                mapOf("id" to id, "n" to 100000, "s" to 8)
            )
        )
        checkCode(j, "playlist detail")
        val p = j.optJSONObject("playlist") ?: throw IOException("歌单不存在")
        // 服务端对部分请求只回 trackIds 不回 tracks（大歌单/登录态等场景，表现为
        // "第一次进有歌、重进就没歌"）：tracks 为空时用 trackIds 走批量详情兜底
        val tracks = p.optJSONArray("tracks") ?: JSONArray()
        var songs = parseSongs(tracks)
        if (songs.isEmpty()) {
            val idsArr = p.optJSONArray("trackIds") ?: JSONArray()
            val ids = buildList(idsArr.length()) {
                for (i in 0 until idsArr.length()) add(idsArr.optJSONObject(i)?.optLong("id") ?: 0L)
            }.filter { it > 0 }
            if (ids.isNotEmpty()) {
                val fetched = try {
                    songsDetail(ids)
                } catch (_: Exception) {
                    emptyList()
                }
                // 按歌单原始顺序排回
                val byId = fetched.associateBy { it.id }
                songs = ids.mapNotNull { byId[it] }
            }
        }
        return PlaylistDetail(
            id = p.optLong("id", id),
            name = Json.strOrNull(p, "name") ?: "未知歌单",
            coverUrl = Json.strOrNull(p, "coverImgUrl"),
            creatorName = p.optJSONObject("creator")?.let { Json.strOrNull(it, "nickname") } ?: "",
            playCount = p.optLong("playCount", 0L),
            trackCount = p.optInt("trackCount", songs.size),
            description = Json.strOrNull(p, "description") ?: "",
            songs = songs,
            canPlay = songs.isNotEmpty(),
            subscribed = p.optBoolean("subscribed", false)
        )
    }

    /** 歌单加/删歌曲（收藏到歌单）。trackIds 为歌曲 id 列表，op=add/del */
    fun manipulateTracks(playlistId: Long, trackIds: List<Long>, op: String): Boolean {
        if (trackIds.isEmpty()) return false
        val idsArr = JSONArray().apply { trackIds.forEach { put(it) } }
        val j = JSONObject(
            weapiPost(
                "/api/playlist/manipulate/tracks",
                mapOf(
                    "pid" to playlistId,
                    "trackIds" to idsArr.toString(),
                    "op" to op,
                    "csrf_token" to csrfToken()
                )
            )
        )
        checkCode(j, "manipulateTracks")
        return j.optInt("code") == 200
    }

    /** 新建歌单；返回新歌单 id，失败抛异常 */
    fun createPlaylist(name: String): Long {
        // 对齐官方 web 端 / NeteaseCloudMusicApi 的成功姿势：
        // - 只传 name + privacy（'10' 为默认公开档），不再多传 type
        // - cookie 需带 os=pc & appver=2.9.7（部分账号缺了会被拒）
        val j = JSONObject(
            weapiPost(
                "/api/playlist/create",
                mapOf(
                    "name" to name,
                    "privacy" to "10",
                    "csrf_token" to csrfToken()
                ),
                writeCookie = true
            )
        )
        checkCode(j, "playlist create")
        // 兼容两种响应形态：{playlist:{id,...}}（web 端）/ {code,id}
        val id = j.optJSONObject("playlist")?.optLong("id", 0L) ?: j.optLong("id", 0L)
        if (id <= 0) throw IOException("playlist create: empty id")
        return id
    }

    /** 删除歌单（仅自己创建的歌单）；失败抛异常。
     *  注意：参数是 pid 而非 id（传 id 会返回 400 请求参数错误）。 */
    fun deletePlaylist(playlistId: Long): Boolean {
        val j = JSONObject(
            weapiPost(
                "/api/playlist/delete",
                mapOf("pid" to playlistId, "csrf_token" to csrfToken())
            )
        )
        checkCode(j, "playlist delete")
        return j.optInt("code") == 200
    }

    /** 批量歌曲详情 */
    fun songsDetail(ids: List<Long>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val c = JSONArray().apply { ids.forEach { put(JSONObject().put("id", it)) } }.toString()
        val j = JSONObject(weapiPost("/api/v3/song/detail", mapOf("c" to c)))
        checkCode(j, "song detail")
        return parseSongs(j.optJSONArray("songs") ?: JSONArray())
    }

    /** 播放地址；level 可选 standard/exhigh/lossless/hires；拿不到返回 null */
    fun songUrl(id: Long, level: String = "standard"): String? {
        val j = JSONObject(
            weapiPost(
                "/api/song/enhance/player/url/v1",
                mapOf("ids" to "[$id]", "level" to level, "encodeType" to "aac")
            )
        )
        checkCode(j, "song url")
        val data = j.optJSONArray("data") ?: return null
        if (data.length() == 0) return null
        return Json.strOrNull(data.getJSONObject(0), "url")
    }

    /**
     * 播放/下载地址（带智能降级）：目标音质拿不到时按档位链向下找，
     * 保证至少能拿到标准音质。
     */
    fun songUrlSmart(id: Long, requested: String): String? {
        val chain = buildList {
            add(requested)
            if (requested != "exhigh") add("exhigh")
            if (requested != "standard") add("standard")
        }
        for (level in chain) {
            val url = try {
                songUrl(id, level)
            } catch (_: Exception) {
                null
            }
            if (!url.isNullOrBlank()) return url
        }
        return null
    }

    /** 批量获取播放地址（一次请求多个 id），返回 id→url 映射；拿不到的 id 不在结果里 */
    fun songUrls(ids: List<Long>, level: String = "standard"): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val idsStr = ids.joinToString(",", "[", "]")
        val j = JSONObject(
            weapiPost(
                "/api/song/enhance/player/url/v1",
                mapOf("ids" to idsStr, "level" to level, "encodeType" to "aac")
            )
        )
        checkCode(j, "song urls")
        val data = j.optJSONArray("data") ?: return emptyMap()
        return buildMap {
            for (i in 0 until data.length()) {
                val o = data.optJSONObject(i) ?: continue
                val id = o.optLong("id", 0L)
                val url = Json.strOrNull(o, "url")
                if (id > 0 && !url.isNullOrBlank()) put(id, url)
            }
        }
    }

    /**
     * 歌词：原文 + 译文**已解析并一对一配对**，UI 可直接渲染（见 [Lyrics]）。
     * 解析与配对都在这一次调用里（IO 线程）完成，不把两个原始列表丢给 UI 去拼。
     */
    fun lyric(id: Long): List<LyricRow> {
        val j = JSONObject(
            weapiPost("/api/song/lyric", mapOf("id" to id, "lv" to -1, "kv" to -1, "tv" to -1))
        )
        checkCode(j, "lyric")
        return Lyrics.build(
            lrcText = j.optJSONObject("lrc")?.optString("lyric", ""),
            translatedText = j.optJSONObject("tlyric")?.optString("lyric", "")
        )
    }

    // ───────────────────────── 搜索 ─────────────────────────

    /** 综合搜索；type: 1歌曲 100歌手 1000歌单 */
    fun search(keyword: String, type: Int = 1, limit: Int = 50): JSONObject {
        val j = JSONObject(
            weapiPost(
                "/api/cloudsearch/get/web",
                mapOf("s" to keyword, "type" to type, "limit" to limit, "offset" to 0, "total" to true)
            )
        )
        checkCode(j, "search")
        return j.optJSONObject("result") ?: JSONObject()
    }

    fun searchSongs(keyword: String): List<Song> {
        val result = search(keyword, 1)
        return parseSongs(result.optJSONArray("songs") ?: JSONArray())
    }

    fun searchPlaylists(keyword: String): List<Playlist> {
        val result = search(keyword, 1000)
        return parsePlaylists(result.optJSONArray("playlists") ?: JSONArray())
    }

    data class SearchArtist(val id: Long, val name: String, val picUrl: String?)

    fun searchArtists(keyword: String): List<SearchArtist> {
        val result = search(keyword, 100)
        val arr = result.optJSONArray("artists") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    SearchArtist(
                        id = o.optLong("id", 0L),
                        name = Json.strOrNull(o, "name") ?: "",
                        picUrl = Json.strOrNull(o, "picUrl")
                    )
                )
            }
        }
    }

    /** 歌手热门歌曲 */
    fun artistTopSongs(artistId: Long): List<Song> {
        val j = JSONObject(weapiPost("/api/artist/top/song", mapOf("id" to artistId)))
        checkCode(j, "artist top songs")
        return parseSongs(j.optJSONArray("songs") ?: JSONArray())
    }

    // ───────────────────────── 收藏 / 歌单操作 ─────────────────────────

    /** 批量查询歌曲是否已喜欢（红心真实状态），返回已喜欢的 id 集合 */
    fun likeCheck(trackIds: List<Long>): Set<Long> {
        if (trackIds.isEmpty()) return emptySet()
        val idsArr = JSONArray().apply { trackIds.forEach { put(it) } }
        val j = JSONObject(
            weapiPost("/api/song/like/check", mapOf("trackIds" to idsArr))
        )
        checkCode(j, "like check")
        val arr = j.optJSONArray("ids") ?: return emptySet()
        return buildSet {
            for (i in 0 until arr.length()) add(arr.optLong(i, 0L))
        }
    }

    @Volatile
    private var likedPidCache: Long = -1L

    /** 「我喜欢的音乐」歌单 id（specialType=5），进程内缓存 */
    private fun likedPlaylistId(): Long {
        if (likedPidCache > 0) return likedPidCache
        val arr = userPlaylists(account().userId, 100, 0)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("id", 0L)
            if (o.optInt("specialType", 0) == 5 ||
                o.optString("name", "").contains("喜欢") ||
                o.optString("name", "").contains("Liked")
            ) {
                if (id > 0) likedPidCache = id
                return id
            }
        }
        return -1L
    }

    /**
     * 喜欢/取消喜欢歌曲（红心）。
     * 实测 /api/resource/like|unlike 对歌曲是空操作（code 200 但无效果），
     * 正确做法：把歌曲增删到「我喜欢的音乐」歌单（manipulate/tracks）。
     */
    fun likeSong(songId: Long, like: Boolean): Boolean {
        val pid = likedPlaylistId()
        if (pid <= 0) return false
        return manipulateTracks(pid, listOf(songId), if (like) "add" else "del")
    }

    /** 收藏/取消收藏歌单（必须带 csrf_token，否则 403 illegal request） */
    fun subscribePlaylist(playlistId: Long, subscribe: Boolean): Boolean {
        val path = if (subscribe) "/api/playlist/subscribe" else "/api/playlist/unsubscribe"
        val j = JSONObject(
            weapiPost(
                path,
                mapOf("id" to playlistId, "csrf_token" to csrfToken())
            )
        )
        checkCode(j, if (subscribe) "subscribe" else "unsubscribe")
        return j.optInt("code") == 200
    }

    /** 用户歌单（创建 + 收藏），返回 Playlist 列表，Playlist 无 subscribed 标记故返回原始 JSON 数组便于判断 */
    fun userPlaylists(uid: Long, limit: Int = 100, offset: Int = 0): JSONArray {
        val j = JSONObject(weapiPost("/api/user/playlist", mapOf("uid" to uid, "limit" to limit, "offset" to offset)))
        checkCode(j, "user playlists")
        return j.optJSONArray("playlist") ?: JSONArray()
    }

    /** 解析用户歌单（带 subscribed 标记） */
    fun parseUserPlaylists(arr: JSONArray, subscribedOnly: Boolean = false): List<Playlist> {
        val out = mutableListOf<Playlist>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val isSub = o.optBoolean("subscribed", false)
            if (subscribedOnly && !isSub) continue
            if (o.optLong("id", 0L) == 0L) continue
            out.add(Json.parsePlaylist(o))
        }
        return out
    }

    // ───────────────────────── 解析辅助 ─────────────────────────

    private fun parseSongs(arr: JSONArray): List<Song> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("id", 0L) == 0L) continue
            add(Json.parseSong(o))
        }
    }

    private fun parsePlaylists(arr: JSONArray): List<Playlist> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("id", 0L) == 0L) continue
            add(Json.parsePlaylist(o))
        }
    }
}