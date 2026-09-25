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

    // ───────────────────────── 专辑 ─────────────────────────

    /**
     * 专辑详情（官方接口 `/api/v1/album/{id}`）：补全**发行时间**与**发行公司**。
     * v3 歌曲详情里的 album 只有 id/name/封面，这两个字段是 null（实测）。
     * 失败返回 null（详情弹窗里静默处理，不影响主流程）。
     */
    fun albumDetail(albumId: Long): AlbumDetail? {
        if (albumId <= 0) return null
        return try {
            val j = JSONObject(weapiPost("/api/v1/album/$albumId", mapOf()))
            checkCode(j, "album detail")
            val a = j.optJSONObject("album") ?: return null
            AlbumDetail(
                id = a.optLong("id", albumId),
                name = Json.strOrNull(a, "name") ?: "",
                company = Json.strOrNull(a, "company") ?: "",
                publishTimeMs = a.optLong("publishTime", 0L),
                picUrl = Json.strOrNull(a, "picUrl"),
                description = Json.strOrNull(a, "description").orEmpty()
            )
        } catch (_: Exception) {
            null
        }
    }

    // ───────────────────────── 评论 ─────────────────────────

    /** 歌曲评论的会话 id */
    private fun songThread(songId: Long) = "R_SO_4_$songId"

    /**
     * 评论分页。**三个排序是三个不同接口**（实测结论）：
     *
     * - 推荐 `/api/comment/resource/comments/get` -> `data.comments`，翻页靠 `offset`；
     *   该接口的 `pageNo` / `orderType` 都被服务端忽略（实测 1/2/3 返回完全相同），
     *   `hasMore` 也不可信（16 万评论的歌返回 false）-> 只能按「本页满页」判断还有下一页。
     * - 最热 `/api/v1/resource/hotcomments/R_SO_4_x` -> 顶层 `hotComments`，`offset` 翻页，`hasMore` 可信。
     * - 最新 `/api/v1/resource/comments/R_SO_4_x` -> 顶层 `comments`，`offset` 翻页，`more` 可信。
     */
    fun comments(
        songId: Long,
        offset: Int = 0,
        limit: Int = 20,
        sort: CommentSort = CommentSort.RECOMMEND
    ): CommentPage {
        val thread = songThread(songId)
        return when (sort) {
            CommentSort.RECOMMEND -> {
                val j = JSONObject(
                    weapiPost(
                        "/api/comment/resource/comments/get",
                        mapOf(
                            "rid" to thread,
                            "threadId" to thread,
                            "pageSize" to limit,
                            "cursor" to -1,
                            "offset" to offset,
                            "orderType" to 1
                        )
                    )
                )
                checkCode(j, "comments")
                val d = j.optJSONObject("data") ?: return CommentPage(0, emptyList(), false)
                val list = Json.parseComments(d.optJSONArray("comments"))
                CommentPage(total = d.optInt("totalCount", 0), comments = list, hasMore = list.size >= limit)
            }

            CommentSort.HOT -> {
                val j = JSONObject(
                    weapiPost(
                        "/api/v1/resource/hotcomments/" + thread,
                        mapOf("rid" to songId, "limit" to limit, "offset" to offset, "beforeTime" to 0)
                    )
                )
                checkCode(j, "hotComments")
                val list = Json.parseComments(j.optJSONArray("hotComments"))
                CommentPage(
                    total = j.optInt("total", 0),
                    comments = list,
                    hasMore = j.optBoolean("hasMore", list.size >= limit)
                )
            }

            CommentSort.LATEST -> {
                val j = JSONObject(
                    weapiPost(
                        "/api/v1/resource/comments/" + thread,
                        mapOf("rid" to songId, "limit" to limit, "offset" to offset, "beforeTime" to 0)
                    )
                )
                checkCode(j, "latestComments")
                val list = Json.parseComments(j.optJSONArray("comments"))
                CommentPage(
                    total = j.optInt("total", 0),
                    comments = list,
                    hasMore = j.optBoolean("more", list.size >= limit)
                )
            }
        }
    }

    /**
     * 一条评论的追评（楼中楼）。翻页用返回的 [FloorPage.nextTime] 作 `time` 游标，
     * 首次传 -1。
     */
    fun commentFloors(
        songId: Long,
        parentCommentId: Long,
        time: Long = -1L,
        limit: Int = 10
    ): FloorPage {
        val j = JSONObject(
            weapiPost(
                "/api/resource/comment/floor/get",
                mapOf(
                    "parentCommentId" to parentCommentId,
                    "threadId" to songThread(songId),
                    "time" to time,
                    "limit" to limit
                )
            )
        )
        checkCode(j, "commentFloors")
        val d = j.optJSONObject("data") ?: return FloorPage(0, emptyList(), false, -1L)
        return FloorPage(
            total = d.optInt("totalCount", 0),
            comments = Json.parseComments(d.optJSONArray("comments")),
            hasMore = d.optBoolean("hasMore", false),
            nextTime = d.optLong("time", -1L)
        )
    }

    /** 点赞 / 取消点赞评论 */
    fun commentLike(songId: Long, commentId: Long, like: Boolean): Boolean {
        val j = JSONObject(
            weapiPost(
                "/api/v1/comment/" + if (like) "like" else "unlike",
                mapOf("threadId" to songThread(songId), "commentId" to commentId)
            )
        )
        return j.optInt("code", -1) == 200
    }

    /**
     * 发表评论 / 追评（回复某条评论）。
     *
     * 实测**空 `checkToken` 也能成功**（网易易盾只对高风险账号弹窗），
     * 所以这里不接风控 SDK；未登录会被服务端拒（返回非 200）。
     */
    fun addComment(songId: Long, content: String): Boolean {
        val text = content.trim()
        if (text.isEmpty()) return false
        val j = JSONObject(
            weapiPost(
                "/api/resource/comments/add",
                mapOf(
                    "threadId" to songThread(songId),
                    "content" to text,
                    "checkToken" to "",
                    "code" to "0"
                )
            )
        )
        return j.optInt("code", -1) == 200
    }

    /** 追评：回复某条评论 */
    fun replyComment(songId: Long, commentId: Long, content: String): Boolean {
        val text = content.trim()
        if (text.isEmpty()) return false
        val j = JSONObject(
            weapiPost(
                "/api/resource/comments/reply",
                mapOf(
                    "threadId" to songThread(songId),
                    "commentId" to commentId,
                    "content" to text,
                    "checkToken" to "",
                    "code" to "0"
                )
            )
        )
        return j.optInt("code", -1) == 200
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

    // ───────────────────────── 用户主页 ─────────────────────────

    /** 用户主页信息（个人主页顶部：关注/粉丝/听歌数/等级） */
    data class UserProfile(
        val userId: Long,
        val nickname: String,
        val avatarUrl: String?,
        val signature: String,
        val follows: Int,
        val followeds: Int,
        val playlistCount: Int,
        val eventCount: Int,
        val level: Int,
        val listenSongs: Int,
        val vipType: Int,
        val allSubscribedCount: Int
    )

    /** 用户主页信息 */
    fun userDetail(uid: Long): UserProfile {
        val j = JSONObject(weapiPost("/api/v1/user/detail/$uid", mapOf("uid" to uid)))
        checkCode(j, "user detail")
        val p = j.optJSONObject("profile") ?: throw IOException("用户不存在")
        return UserProfile(
            userId = p.optLong("userId", uid),
            nickname = Json.strOrNull(p, "nickname") ?: "",
            avatarUrl = Json.strOrNull(p, "avatarUrl"),
            signature = Json.strOrNull(p, "signature").orEmpty(),
            follows = p.optInt("follows", 0),
            followeds = p.optInt("followeds", 0),
            playlistCount = p.optInt("playlistCount", 0),
            eventCount = p.optInt("eventCount", 0),
            level = j.optInt("level", 0),
            listenSongs = j.optInt("listenSongs", 0),
            vipType = p.optInt("vipType", 0),
            allSubscribedCount = p.optInt("allSubscribedCount", 0)
        )
    }

    /** 关注/粉丝列表里的一个用户 */
    data class FollowUser(
        val userId: Long,
        val nickname: String,
        val avatarUrl: String?,
        val signature: String,
        val follows: Int,
        val followeds: Int
    )

    /** 关注列表 */
    fun followedUsers(uid: Long, limit: Int = 100, offset: Int = 0): List<FollowUser> {
        val j = JSONObject(
            weapiPost(
                "/api/user/getfollows/$uid",
                mapOf("userId" to uid, "limit" to limit, "offset" to offset)
            )
        )
        checkCode(j, "user follows")
        return parseFollowUsers(j.optJSONArray("follow"))
    }

    /** 粉丝列表 */
    fun followerUsers(uid: Long, limit: Int = 100, offset: Int = 0): List<FollowUser> {
        val j = JSONObject(
            weapiPost(
                "/api/user/getfolloweds",
                mapOf("userId" to uid, "limit" to limit, "offset" to offset)
            )
        )
        checkCode(j, "user followers")
        return parseFollowUsers(j.optJSONArray("followeds"))
    }

    private fun parseFollowUsers(arr: JSONArray?): List<FollowUser> = buildList {
        if (arr == null) return@buildList
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("userId", 0L) == 0L) continue
            add(
                FollowUser(
                    userId = o.optLong("userId", 0L),
                    nickname = Json.strOrNull(o, "nickname") ?: "",
                    avatarUrl = Json.strOrNull(o, "avatarUrl"),
                    signature = Json.strOrNull(o, "signature").orEmpty(),
                    follows = o.optInt("follows", 0),
                    followeds = o.optInt("followeds", 0)
                )
            )
        }
    }

    // ───────────────────────── 用户动态 ─────────────────────────

    /** 用户动态里的一条（分享歌曲/歌单/文本/图片等），只取能展示与可操作的最小集 */
    data class EventItem(
        val id: Long,
        val timeMs: Long,
        val text: String,
        val song: Song?,
        val playlistId: Long,
        val playlistName: String?,
        val hasImages: Boolean
    )

    data class EventPage(
        val items: List<EventItem>,
        val nextTime: Long,
        val hasMore: Boolean
    )

    /**
     * 某用户的动态（GET /api/event/get/{userId}，翻页游标 [EventPage.nextTime]，
     * 首页传 -1）。返回结构顶层 `events`，每条 json 为字符串。
     */
    fun userEvents(uid: Long, lasttime: Long = -1L, limit: Int = 20): EventPage {
        val j = httpGetJson(
            "/api/event/get/$uid?userId=$uid&lasttime=$lasttime&limit=$limit&getcounts=true"
        )
        checkCode(j, "user events")
        val arr = j.optJSONArray("events") ?: return EventPage(emptyList(), -1L, false)
        val items = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optLong("eventId", 0L)
                if (id == 0L) continue
                val jsonStr = o.optString("json")
                val jo = if (jsonStr.isNotEmpty()) {
                    try {
                        JSONObject(jsonStr)
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
                val text = jo?.optString("msg").orEmpty()
                    .ifBlank { jo?.optString("shareContent").orEmpty() }
                    .trim()
                val song = jo?.optJSONObject("song")?.let { so ->
                    try {
                        Json.parseSong(so)
                    } catch (_: Exception) {
                        null
                    }
                }
                val playlist = jo?.optJSONObject("playlist")
                add(
                    EventItem(
                        id = id,
                        timeMs = o.optLong("showTime", 0L),
                        text = text,
                        song = song?.takeIf { it.id != 0L },
                        playlistId = playlist?.optLong("id", 0L) ?: 0L,
                        playlistName = playlist?.let { Json.strOrNull(it, "name") },
                        hasImages = (jo?.optJSONArray("pics")?.length() ?: 0) > 0
                    )
                )
            }
        }
        val next = j.optLong("lasttime", items.lastOrNull()?.timeMs ?: -1L)
        return EventPage(items = items, nextTime = next, hasMore = j.optBoolean("more", false))
    }

    /** 普通 HTTP GET（非 weapi 加密）拿 JSON，用于动态等纯 GET 接口 */
    private fun httpGetJson(path: String): JSONObject {
        val request = Request.Builder()
            .url(HOST + path)
            .headers(headers())
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $text")
            return JSONObject(if (text.isEmpty()) "{}" else text)
        }
    }

    /** 听歌排行里的一首：歌曲 + 播放次数 */
    data class RecordEntry(val song: Song, val playCount: Int)

    /** 听歌排行：type 0=所有时间 1=最近一周（响应顶层 allData/weekData） */
    fun playRecord(uid: Long, type: Int = 0): List<RecordEntry> {
        val j = JSONObject(
            weapiPost(
                "/api/v1/play/record",
                mapOf("uid" to uid, "type" to type, "limit" to 100, "offset" to 0, "total" to true)
            )
        )
        checkCode(j, "play record")
        val arr = if (type == 1) j.optJSONArray("weekData") else j.optJSONArray("allData")
            ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val songObj = o.optJSONObject("song") ?: continue
                val song = Json.parseSong(songObj)
                if (song.id == 0L) continue
                add(RecordEntry(song, o.optInt("playCount", 0)))
            }
        }
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