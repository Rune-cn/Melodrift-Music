package app.melodrift.music.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.melodrift.music.data.PlaybackPositions
import app.melodrift.music.data.PlayHistoryStore
import app.melodrift.music.data.QueueStore
import app.melodrift.music.net.LyricRow
import app.melodrift.music.net.NcmApi
import app.melodrift.music.net.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** 循环模式 */
enum class LoopMode { LIST, ONE, SHUFFLE }

/** 音质档位 */
val QUALITY_LEVELS = listOf("standard", "exhigh", "lossless", "hires")

/**
 * 全局播放器（单例，ExoPlayer 实现）。
 *
 * 队列 = [queue]，当前歌曲 = [current]。所有状态均为 Compose State，
 * UI 直接观察；播放器回调统一在主线程更新。
 *
 * 必须先在 Activity 中调用 [init] 注入 Context 并创建播放器。
 */
object PlayerController {

    /** 队列与当前序号：Compose State，增删/移动后「播放列表」弹窗、迷你条等立即刷新 */
    var queue by mutableStateOf<List<Song>>(emptyList())
        private set
    var currentIndex by mutableIntStateOf(-1)
        private set

    var isPlaying by mutableStateOf(false)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var errorText by mutableStateOf<String?>(null)
        private set

    /** 歌词行（原文 + 已配对译文），由 NcmApi.lyric 在 IO 线程解析/配对好；切歌瞬间清空 */
    var lyrics by mutableStateOf<List<LyricRow>>(emptyList())
        private set

    /** 播放历史（最近播放，最新在前，去重，上限 50 条，与 PlayHistoryStore 一致） */
    var playHistory by mutableStateOf<List<Song>>(emptyList())
        private set

    // ── 播放偏好 ──
    var qualityLevel by mutableStateOf("standard")
        private set
    var playbackSpeed by mutableFloatStateOf(1f)
        private set
    var loopMode by mutableStateOf(LoopMode.LIST)
        private set
    /** 定时关闭剩余毫秒；0 = 未开启 */
    var sleepRemainMs by mutableLongStateOf(0L)
        private set
    /** 定时关闭总时长毫秒；0 = 未开启 */
    var sleepTotalMs by mutableLongStateOf(0L)
        private set

    // ── 播放行为设置（由设置页驱动） ──
    /** 淡入淡出时长毫秒（新歌渐强、旧歌减弱共用）；0 = 关闭 */
    var crossfadeMs by mutableLongStateOf(0L)
        private set
    /** 允许与其他应用同时播放（不抢占音频焦点） */
    var allowMixedAudio by mutableStateOf(false)
        private set
    /** 恢复播放位置（再次播放同一首歌时从上次位置继续） */
    var resumePlayback by mutableStateOf(false)
        private set

    // ── 外观（由设置页驱动） ──
    /** 播放页进度条样式：standard / wave */
    var progressStyle by mutableStateOf("standard")
    /** 歌词页上下边缘渐变遮罩 */
    var lyricsFade by mutableStateOf(false)

    val current: Song?
        get() = queue.getOrNull(currentIndex)

    private var exo: ExoPlayer? = null
    /**
     * 播放器当前实际在放的歌曲 id。
     *
     * 与 [current]（队列指针指向的歌）配合使用：playIndex 在异步拉取播放地址期间
     * 就更新了 currentIndex，但 exo 还在放上一首 —— 这段窗口期内两者不一致。
     * 所有"读 positionMs / 写断点"的路径都必须先确认 `current?.id == exoSongId`，
     * 否则会把上一首的进度记到新歌名下（表现为新歌起播时"跳过一段"）。
     */
    private var exoSongId: Long? = null
    private var initialized = false
    private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sleepJob: Job? = null
    private var fadeJob: Job? = null
    // 媒体会话（通知栏标准媒体控制）
    private var mediaSession: MediaSessionCompat? = null
    private var coverBitmapCache: Bitmap? = null
    private var coverUrlCache: String? = null

    /**
     * 进度轮询：只在真正播放时自续期，暂停 / 无歌 / 播放器为空即停，
     * 不再从 App 启动起常驻 500ms 唤醒主线程。
     *
     * 注意：这里**不再**调 updateSessionState()。媒体会话与通知栏的进度由
     * MediaSession 依据 (position, playbackSpeed, updateTime) 自行插值，
     * 高频推送纯属浪费；播放态变化与 seek 处各自单独刷新即可。
     */
    private val progress = object : Runnable {
        private var tick = 0
        override fun run() {
            val p = exo
            val song = current
            // 切歌过渡期（currentIndex 已指向新歌、播放器还在放上一首）：
            // 停止轮询。否则旧歌进度会写进 positionMs，甚至落盘到新歌名下，
            // 导致新歌起播时从旧歌的位置"跳过一段"。
            if (p == null || song == null || song.id != exoSongId || !p.isPlaying) {
                tickerRunning = false
                return
            }
            positionMs = p.currentPosition.coerceAtLeast(0L)
            if (p.duration > 0) durationMs = p.duration
            // 恢复播放位置：播放中每 5 秒落盘一次（500ms tick × 10）
            tick++
            if (resumePlayback && tick % 10 == 0) {
                val ctx = appContext
                if (ctx != null) {
                    PlaybackPositions.save(ctx, song.id, positionMs)
                }
            }
            main.postDelayed(this, 500)
        }
    }

    /** 轮询是否已在跑（幂等保护，避免重复 post 叠加成多条时间线） */
    private var tickerRunning = false

    /** 启动进度轮询：真正开始播放时调用（幂等） */
    private fun startTicker() {
        if (tickerRunning || !initialized) return
        tickerRunning = true
        main.post(progress)
    }

    /** 必须在 Activity.onCreate 调用（applicationContext 即可） */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        PlaybackNotifications.ensureChannel(context.applicationContext)
        exo = buildPlayer(handleFocus = true)
        // 标准媒体会话：通知栏按钮/系统媒体中心共用一套控制
        mediaSession = MediaSessionCompat(context.applicationContext, "MelodriftPlayer").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = toggle()
                override fun onPause() = toggle()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = prev()
                override fun onSeekTo(pos: Long) = seekTo(pos)
                override fun onStop() = stop()
            })
            isActive = true
        }
        // 恢复持久化的听歌历史（异步拉取歌曲信息）
        scope.launch {
            val ctx = appContext ?: return@launch
            val ids = PlayHistoryStore.loadIds(ctx)
            if (ids.isEmpty()) return@launch
            val songs = withContext(Dispatchers.IO) {
                try {
                    val list = NcmApi.songsDetail(ids)
                    val byId = list.associateBy { it.id }
                    ids.mapNotNull { byId[it] }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            if (songs.isNotEmpty()) playHistory = songs
        }

        // 恢复上次退出时的队列与当前歌（仅展示，不自动播放）：
        // 迷你条仍显示上次的歌曲；点播放会从断点位置继续
        scope.launch {
            val ctx = appContext ?: return@launch
            val st = QueueStore.load(ctx) ?: return@launch
            val songs = withContext(Dispatchers.IO) {
                try {
                    val list = NcmApi.songsDetail(st.ids)
                    val byId = list.associateBy { it.id }
                    st.ids.mapNotNull { byId[it] }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            // 用户抢先点了歌（队列已建立）则不覆盖
            if (songs.isEmpty() || queue.isNotEmpty()) return@launch
            queue = songs
            currentIndex = st.index.coerceIn(songs.indices)
            val song = current
            durationMs = song?.durationMs ?: 0L
            positionMs = if (song != null && st.positionMs in 1 until (song.durationMs - 1_000)) {
                st.positionMs
            } else {
                0L
            }
            // 进度写入断点记录：恢复态点「播放」时经 playIndex(restorePosition=true) 原位续播
            val pos = positionMs
            if (song != null && pos > 5_000) {
                PlaybackPositions.save(ctx, song.id, pos)
            }
        }
    }

    /** 通知栏 MediaStyle 所需的会话 Token */
    fun mediaSessionToken(): MediaSessionCompat.Token? = mediaSession?.sessionToken

    /** 最近一次加载的封面（通知用） */
    fun coverBitmap(): Bitmap? = coverBitmapCache

    /** 媒体会话支持的操作集合：常量，避免每次刷新 PlaybackState 重新拼一遍位掩码 */
    private val sessionActions =
        PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_STOP

    /**
     * 更新媒体会话的播放状态（进度/播放态），通知进度条随之滚动。
     *
     * 只在"状态真的变了"时调用：播放态切换、seek、变速、通知刷新。
     * 进度推进交给 MediaSession 依据 playbackSpeed 自行插值，无需定时调用。
     */
    private fun updateSessionState() {
        val session = mediaSession ?: return
        if (currentIndex < 0) return
        val state = when {
            isLoading -> PlaybackStateCompat.STATE_BUFFERING
            isPlaying -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val speed = if (playbackSpeed > 0f) playbackSpeed else 1f
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(sessionActions)
                .setState(state, positionMs, speed)
                .build()
        )
    }

    /** 更新媒体会话元数据（切歌时调用；封面异步加载） */
    private fun updateSessionMetadata() {
        val session = mediaSession ?: return
        val song = current ?: return
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.name)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artistNames)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album?.name ?: "")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.durationMs)
        val cached = if (coverUrlCache == song.coverUrl) coverBitmapCache else null
        if (cached != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cached)
            session.setMetadata(builder.build())
            return
        }
        session.setMetadata(builder.build())
        val cover = song.coverUrl
        if (cover.isNullOrBlank()) return
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { PlaybackNotifications.loadBitmapSync(cover) }
            if (bmp != null && current?.id == song.id) {
                coverBitmapCache = bmp
                coverUrlCache = cover
                updateSessionMetadata()
            }
        }
    }

    private fun buildPlayer(handleFocus: Boolean): ExoPlayer {
        val dsFactory = OkHttpDataSource.Factory(NcmApi.httpClient())
            // 网易 CDN 校验 UA：非浏览器 UA 返回 403；并统一带 Referer 保险
            .setDefaultRequestProperties(
                mapOf(
                    "User-Agent" to NcmApi.USER_AGENT,
                    "Referer" to "https://music.163.com/"
                )
            )
        return ExoPlayer.Builder(appContext!!)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                handleFocus
            )
            .build()
            .also {
                it.addListener(playerListener)
                it.setPlaybackSpeed(playbackSpeed)
            }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            if (playing) {
                errorText = null
                startTicker()
            }
            updateNotification()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    isLoading = false
                    val p = exo
                    if (p != null && p.duration > 0) durationMs = p.duration
                    p?.setPlaybackSpeed(playbackSpeed)
                    // 缓冲完成即可能已在播放（自动播放 / seek 后恢复）：确保轮询在跑
                    if (p?.isPlaying == true) startTicker()
                }
                Player.STATE_BUFFERING -> isLoading = true
                Player.STATE_ENDED -> onCompleted()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            errorText = "player_error:${error.errorCodeName ?: error.errorCode}"
            isLoading = false
            updateNotification()
        }
    }

    /** 用一组歌曲作为播放队列并立刻播放 [index] */
    fun playQueue(songs: List<Song>, index: Int = 0) {
        if (songs.isEmpty()) return
        queue = songs
        playIndex(index)
    }

    /** 播放队列中 [index]（自动加载地址 + 歌词） */
    /** 播放队列中 [index]（自动加载地址 + 歌词）。
     *  [restorePosition] = true 时若该歌保存过中断位置则续播（断点播放，仅主动点歌时用）；
     *  手动切歌 / 自动切歌 / 重试传 false，永远从头播。 */
    fun playIndex(index: Int, restorePosition: Boolean = true) {
        if (index !in queue.indices) return
        // 保存上一首的播放位置（恢复播放位置开启时）
        if (resumePlayback) {
            val ctx = appContext
            val oldSong = queue.getOrNull(currentIndex)
            // oldSong.id == exoSongId：播放器确实还在放 oldSong。
            // 快速连点（A→B 未起播又点 C）时 positionMs 可能属于更早的歌，禁止落盘
            if (ctx != null && oldSong != null && currentIndex != index &&
                oldSong.id == exoSongId
            ) {
                PlaybackPositions.save(ctx, oldSong.id, positionMs)
            }
        }
        currentIndex = index
        val song = queue[index]
        // 两个异步任务的过期判定用「index 未变 且 该 index 上的歌仍是发起请求的那首」：
        // 只看 index 会漏掉队列被整体替换 / 拖拽排序（moveInQueue）/ 删除条目
        // （removeFromQueue）后同一 index 指向另一首歌的情况 —— 过期响应被放行，
        // 表现为"放的是这首、歌词是上首"；再加上 id 校验才能同时挡住
        // 队列里同一首歌出现两次时快速连点两个位置（只该保留最后一次）。
        val songId = song.id
        errorText = null
        isLoading = true
        durationMs = 0L
        positionMs = 0L
        // 切歌立即清空歌词：否则新歌加载期间显示的是上一首的歌词
        // （连点时即使最终会被正确覆盖，中间那段也是"歌词对不上"）
        lyrics = emptyList()
        // 队列快照落盘：重开 App 后迷你条恢复上次歌曲
        persistQueue()

        // 歌词（IO）
        scope.launch {
            val r = try {
                withContext(Dispatchers.IO) { NcmApi.lyric(songId) }
            } catch (_: Exception) {
                null
            }
            if (currentIndex != index || current?.id != songId) return@launch
            lyrics = r ?: emptyList()
        }

        // 播放地址（IO）→ 主线程播放
        scope.launch {
            val url = try {
                withContext(Dispatchers.IO) { NcmApi.songUrlSmart(songId, qualityLevel) }
            } catch (_: Exception) {
                null
            }
            if (currentIndex != index || current?.id != songId) return@launch
            isLoading = false
            if (url.isNullOrBlank()) {
                errorText = "no_url"
                updateNotification()
                return@launch
            }
            fadeOutIfNeeded()
            // 淡出作用在旧歌上，等它完成、即将切换的这一刻才对齐：
            // 此前轮询/落盘看到的都是"不匹配"（旧歌在放、current 已是新歌），会自动停手
            exoSongId = song.id
            playUrl(url)
            MusicPlaybackService.start(appContext ?: return@launch)
            recordHistory(song)
            updateSessionMetadata()
            // 恢复播放位置（仅主动点歌）：开头/结尾附近不恢复（>5s 且 < 结尾前 10s，歌曲 ≥30s）
            if (restorePosition && resumePlayback && song.durationMs >= 30_000) {
                val saved = PlaybackPositions.load(appContext ?: return@launch, song.id)
                if (saved > 5_000 && saved < song.durationMs - 10_000) {
                    exo?.seekTo(saved)
                    positionMs = saved
                }
            }
        }
    }

    /** 记录播放历史（去重，最新在前，上限与 [PlayHistoryStore.MAX] 一致 = 50） */
    private fun recordHistory(song: Song) {
        val list = playHistory.toMutableList()
        list.removeAll { it.id == song.id }
        list.add(0, song)
        // 与持久化上限保持一致：原来这里截到 20，而 PlayHistoryStore 存 50，
        // 导致"当次会话只显示 20 条、重启后变 50 条"的不一致
        if (list.size > 50) list.subList(50, list.size).clear()
        playHistory = list
        val ctx = appContext
        if (ctx != null) PlayHistoryStore.addId(ctx, song.id)
    }

    private fun playUrl(url: String) {
        val p = exo ?: return
        p.stop()
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.play()
        exo?.setPlaybackSpeed(playbackSpeed)
        fadeIn()
        // 显式拉起轮询（淡出等待 + 切歌窗口期已把它停掉；onIsPlayingChanged 也会拉，双保险）
        startTicker()
    }

    // ── 淡入淡出：旧歌减弱、新歌渐强（音量包络模拟交叉过渡，共用 crossfadeMs） ──

    /** 切歌前把当前音量降到 0（仅播放中且开启时）；淡出约占全程 45% */
    private suspend fun fadeOutIfNeeded() {
        val p = exo ?: return
        if (crossfadeMs <= 0 || !p.isPlaying) return
        val ms = (crossfadeMs * 0.45f).toLong().coerceAtLeast(120L)
        val steps = 12
        val stepDelay = (ms / steps).coerceAtLeast(8L)
        for (i in steps downTo 1) {
            p.volume = i / steps.toFloat()
            delay(stepDelay)
        }
        p.volume = 0f
    }

    /** 新歌从 0 渐强到 1（用满整个 crossfadeMs） */
    private fun fadeIn() {
        val p = exo ?: return
        if (crossfadeMs <= 0) {
            p.volume = 1f
            return
        }
        fadeJob?.cancel()
        val steps = 20
        val stepDelay = (crossfadeMs / steps).toLong().coerceAtLeast(16L)
        fadeJob = scope.launch {
            p.volume = 0f
            for (i in 1..steps) {
                p.volume = i / steps.toFloat()
                delay(stepDelay)
            }
            p.volume = 1f
        }
    }

    private fun onCompleted() {
        // 播放器实际在放的歌 ≠ 队列当前歌 = 正在切歌的过渡期（旧歌播完但用户已选新歌）：
        // 忽略本次 ENDED —— 否则会误清新歌的断点、还会再切一次歌
        if (current?.id != exoSongId) return
        // 自然播完：清除该曲的保存位置（下次从头播）
        if (resumePlayback) {
            val ctx = appContext
            val done = queue.getOrNull(currentIndex)
            if (ctx != null && done != null) {
                PlaybackPositions.clear(ctx, done.id)
            }
        }
        when (loopMode) {
            LoopMode.ONE -> {
                // 单曲循环：重新播放当前（从头）
                if (currentIndex in queue.indices) playIndex(currentIndex, restorePosition = false)
            }
            LoopMode.SHUFFLE -> {
                if (queue.size > 1) {
                    var next = currentIndex
                    while (next == currentIndex) next = Random.nextInt(queue.size)
                    playIndex(next, restorePosition = false)
                } else if (currentIndex in queue.indices) {
                    playIndex(currentIndex, restorePosition = false)
                }
            }
            LoopMode.LIST -> {
                if (currentIndex < queue.lastIndex) playIndex(currentIndex + 1, restorePosition = false)
                else if (queue.isNotEmpty()) playIndex(0, restorePosition = false) // 列表循环
                else {
                    isPlaying = false
                    exo?.seekTo(0)
                }
            }
        }
    }

    fun toggle() {
        // 恢复态（重开 App：队列在、播放器还没装内容）或切歌过渡期：
        // 点「播放」= 加载当前歌并按断点续播，而不是空操作
        if (current?.id != exoSongId) {
            val idx = currentIndex
            if (idx in queue.indices) playIndex(idx, restorePosition = true)
            return
        }
        val p = exo ?: return
        if (p.isPlaying) {
            p.pause()
            // 暂停即落盘：断点播放关键时机（否则暂停后退出会丢最后几分钟位置）
            savePlaybackPosition()
        } else {
            p.play()
        }
        updateNotification()
    }

    /** 持久化队列快照：重开 App 后迷你条恢复上次歌曲（含当前进度，见 [QueueStore]） */
    private fun persistQueue() {
        val ctx = appContext ?: return
        if (queue.isEmpty()) {
            QueueStore.clear(ctx)
        } else {
            QueueStore.save(ctx, queue.map { it.id }, currentIndex, positionMs)
        }
    }

    /** 保存当前歌曲播放位置（仅开启恢复播放位置且位置合理时） */
    private fun savePlaybackPosition() {
        if (!resumePlayback) return
        val ctx = appContext
        val song = current
        // 切歌过渡期（播放器还在放上一首）：positionMs 不属于 current，禁止落盘
        // —— 否则旧歌进度会写进新歌名下，新歌下次起播"跳过一段"
        if (ctx != null && song != null && song.id == exoSongId && positionMs > 5_000) {
            PlaybackPositions.save(ctx, song.id, positionMs)
        }
    }

    fun seekTo(ms: Long) {
        exo?.seekTo(ms.coerceAtLeast(0L))
        positionMs = ms
        // 轮询不再高频推会话状态，seek 后必须手动同步一次（否则通知栏进度不回跳）
        updateSessionState()
    }

    /** 快进 [seconds] 秒（默认 30） */
    fun seekForward(seconds: Long = 30) {
        val p = exo ?: return
        p.seekTo((p.currentPosition + seconds * 1000).coerceAtMost(p.duration.coerceAtLeast(0)))
        updatePosition()
    }

    /** 后退 [seconds] 秒（默认 30） */
    fun seekBack(seconds: Long = 30) {
        val p = exo ?: return
        p.seekTo((p.currentPosition - seconds * 1000).coerceAtLeast(0))
        updatePosition()
    }

    private fun updatePosition() {
        positionMs = exo?.currentPosition?.coerceAtLeast(0L) ?: 0L
        updateSessionState()
    }

    fun next() {
        when (loopMode) {
            LoopMode.SHUFFLE -> {
                if (queue.size > 1) {
                    var next = currentIndex
                    while (next == currentIndex) next = Random.nextInt(queue.size)
                    playIndex(next, restorePosition = false)
                } else if (currentIndex in queue.indices) playIndex(currentIndex, restorePosition = false)
            }
            else -> {
                if (currentIndex < queue.lastIndex) playIndex(currentIndex + 1, restorePosition = false)
                else if (queue.isNotEmpty()) playIndex(0, restorePosition = false)
            }
        }
    }

    fun prev() {
        if (currentIndex > 0) playIndex(currentIndex - 1, restorePosition = false)
        else if (queue.isNotEmpty()) {
            exo?.seekTo(0)
            updatePosition()
        }
    }

    /** 重试当前歌曲（重新拉取播放地址，从头播） */
    fun retry() {
        if (currentIndex in queue.indices) playIndex(currentIndex, restorePosition = false)
    }

    // ── 队列管理 ──

    /** 删除队列中某首歌（含正在播放的） */
    fun removeFromQueue(index: Int) {
        if (index !in queue.indices) return
        val list = queue.toMutableList()
        list.removeAt(index)
        queue = list
        persistQueue()
        when {
            list.isEmpty() -> {
                currentIndex = -1
                stop()
            }
            index < currentIndex -> currentIndex--
            index == currentIndex -> {
                // 正在播放的被删：播下一首（同位置，从头）
                val next = if (currentIndex >= list.size) list.lastIndex else currentIndex
                playIndex(next, restorePosition = false)
            }
        }
    }

    /** 队列内移动：把 [from] 移到 [to] */
    fun moveInQueue(from: Int, to: Int) {
        if (from !in queue.indices || to !in queue.indices || from == to) return
        val list = queue.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        queue = list
        // 修正 currentIndex
        currentIndex = when {
            from == currentIndex -> to
            from < currentIndex && to >= currentIndex -> currentIndex - 1
            from > currentIndex && to <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
        persistQueue()
    }

    /** 队列中直接播放某首 */
    fun playIndexInQueue(index: Int) {
        if (index in queue.indices) playIndex(index)
    }

    // ── 偏好设置 ──

    /** 应用播放相关设置（由设置页/启动时调用） */
    fun applyPlaybackSettings(
        quality: String,
        crossfadeEnabled: Boolean,
        crossfadeSeconds: Int,
        mixedAudio: Boolean,
        resumePlaybackPosition: Boolean = false
    ) {
        if (quality in QUALITY_LEVELS) {
            qualityLevel = quality
        }
        setCrossfade(crossfadeEnabled, crossfadeSeconds)
        applyMixedAudio(mixedAudio)
        resumePlayback = resumePlaybackPosition
    }

    /** 淡入淡出开关与时长（秒，新歌渐强与旧歌减弱共用） */
    fun setCrossfade(enabled: Boolean, seconds: Int) {
        crossfadeMs = if (enabled) {
            (seconds * 1000L).coerceIn(1000L, 8000L)
        } else {
            0L
        }
        if (crossfadeMs <= 0) exo?.volume = 1f
    }

    /** 允许与其他应用同时播放：重建播放器，切换是否抢占音频焦点 */
    fun applyMixedAudio(allow: Boolean) {
        if (allowMixedAudio == allow) return
        allowMixedAudio = allow
        val ctx = appContext ?: return
        if (!initialized) return
        val old = exo
        val keepMs = old?.currentPosition ?: positionMs
        exo = buildPlayer(handleFocus = !allow)
        old?.release()
        // 播放中则用新播放器续播当前歌曲（保留当前进度）
        if (currentIndex in queue.indices && (isPlaying || isLoading)) {
            playIndex(currentIndex, restorePosition = false)
            scope.launch {
                delay(300)
                if (currentIndex in queue.indices) {
                    exo?.seekTo(keepMs)
                    positionMs = keepMs
                }
            }
        }
    }

    fun setQuality(level: String) {
        if (level == qualityLevel) return
        qualityLevel = level
        // 重播当前歌曲以应用新音质（保留当前进度）
        if (currentIndex in queue.indices) {
            val keepMs = exo?.currentPosition ?: positionMs
            playIndex(currentIndex, restorePosition = false)
            scope.launch {
                delay(300)
                if (currentIndex in queue.indices) {
                    exo?.seekTo(keepMs)
                    positionMs = keepMs
                }
            }
        }
    }

    fun setSpeed(speed: Float) {
        playbackSpeed = speed
        exo?.setPlaybackSpeed(speed)
        // 变速后刷新会话状态：系统按新速率插值进度，通知栏才不会越走越偏
        updateSessionState()
    }

    fun setLoopModeValue(mode: LoopMode) {
        loopMode = mode
    }

    fun nextLoopMode(): LoopMode {
        val next = when (loopMode) {
            LoopMode.LIST -> LoopMode.ONE
            LoopMode.ONE -> LoopMode.SHUFFLE
            LoopMode.SHUFFLE -> LoopMode.LIST
        }
        loopMode = next
        return next
    }

    /** 设置定时关闭（毫秒）；0 取消 */
    fun setSleepTimer(ms: Long) {
        sleepJob?.cancel()
        sleepJob = null
        if (ms <= 0) {
            sleepRemainMs = 0
            sleepTotalMs = 0
            return
        }
        sleepTotalMs = ms
        sleepRemainMs = ms
        sleepJob = scope.launch {
            while (sleepRemainMs > 0) {
                delay(1000)
                sleepRemainMs = (sleepRemainMs - 1000).coerceAtLeast(0)
            }
            // 到点：暂停播放
            if (sleepRemainMs == 0L && sleepTotalMs > 0) {
                exo?.pause()
                isPlaying = false
                updateNotification()
                sleepTotalMs = 0
            }
        }
    }

    fun cancelSleepTimer() = setSleepTimer(0)

    /** 停止（保留队列） */
    fun stop() {
        // 保存当前播放位置（断点播放）
        savePlaybackPosition()
        exo?.stop()
        isPlaying = false
        isLoading = false
        sleepJob?.cancel()
        sleepRemainMs = 0
        sleepTotalMs = 0
        MusicPlaybackService.stop(appContext ?: return)
    }

    private fun updateNotification() {
        val ctx = appContext ?: return
        val song = current
        if (song == null) {
            MusicPlaybackService.stop(ctx)
            return
        }
        if (isPlaying || isLoading) {
            MusicPlaybackService.start(ctx)
        }
        updateSessionState()
        PlaybackNotifications.notify(ctx)
    }
}