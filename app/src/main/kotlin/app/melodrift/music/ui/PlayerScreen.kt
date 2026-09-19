package app.melodrift.music.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.sin

import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.delay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Context
import app.melodrift.music.R
import app.melodrift.music.data.SavedSongsCache
import app.melodrift.music.net.Downloader
import app.melodrift.music.net.AlbumDetail
import app.melodrift.music.net.LyricRow
import app.melodrift.music.net.NcmApi
import app.melodrift.music.net.Song
import app.melodrift.music.player.LoopMode
import app.melodrift.music.player.PlayerController
import app.melodrift.music.player.QUALITY_LEVELS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val song = PlayerController.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 顶部返回（向下的箭头 ↓：下拉收起播放页）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.back))
            }
        }

        if (song == null) {
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.no_tracks),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.weight(1f))
            return@Column
        }

        // 封面(左) / 歌词(右) 左右滑动
        val pagerState = rememberPagerState { 2 }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            if (page == 0) CoverPage(song) else LyricsPage()
        }

        // 底部控制区：进度 + 控制键（所有页共用）
        PlayerControls()
    }
}

// ═══ 封面页（左）═══

@Composable
private fun CoverPage(song: Song) {
    var saved by remember(song.id) { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }
    var showDownload by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // 爱心状态：在「我喜欢的音乐」（likeCheck）或任一自建歌单（服务器已收藏集合）
    fun refreshSaved() {
        scope.launch {
            val liked = try {
                withContext(Dispatchers.IO) { song.id in NcmApi.likeCheck(listOf(song.id)) }
            } catch (_: Exception) {
                false
            }
            val savedIds = try {
                if (!SavedSongsCache.fresh) {
                    withContext(Dispatchers.IO) { SavedSongsCache.refresh() }
                } else {
                    SavedSongsCache.current()
                }
            } catch (_: Exception) {
                emptySet()
            }
            saved = liked || song.id in savedIds
        }
    }
    LaunchedEffect(song.id) { refreshSaved() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .aspectRatio(1f)
        ) {
            CoverImage(
                url = song.coverUrl?.let { addParam(it, "500y500") },
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 20
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 长按歌名复制
                    Text(
                        song.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    clipboard.setText(AnnotatedString(song.name))
                                    Toast.makeText(
                                        ctx, R.string.song_name_copied, Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                    )
                    if (song.isVip) {
                        Spacer(Modifier.width(6.dp))
                        VipBadge()
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    song.artistNames,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 喜欢（唱进我喜欢的音乐），点赞弹跳动画
            // 收藏（爱心）：点亮 = 已收藏到「我喜欢的音乐」或任意自建歌单；点击弹出歌单选择
            IconButton(onClick = { showAddToPlaylist = true }) {
                Icon(
                    if (saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = stringResource(R.string.add_to_playlist),
                    tint = if (saved) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 三点扩展菜单
            Box {
                IconButton(onClick = { showMore = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        stringResource(R.string.more_actions),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Chat,
                                contentDescription = null,
                                modifier = Modifier.size(dimensionResource(R.dimen.menu_icon))
                            )
                        },
                        text = { Text(stringResource(R.string.comments)) },
                        onClick = { showMore = false; showComments = true }
                    )
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Download,
                                contentDescription = null,
                                modifier = Modifier.size(dimensionResource(R.dimen.menu_icon))
                            )
                        },
                        text = { Text(stringResource(R.string.download)) },
                        onClick = { showMore = false; showDownload = true }
                    )
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = null,
                                modifier = Modifier.size(dimensionResource(R.dimen.menu_icon))
                            )
                        },
                        text = { Text(stringResource(R.string.detail)) },
                        onClick = { showMore = false; showDetail = true }
                    )
                }
            }
        }
    }

    // 歌曲信息对话框（封面 + 官方接口补全的完整信息）
    if (showDetail) {
        var albumInfo by remember(song.id) { mutableStateOf<AlbumDetail?>(null) }
        var credits by remember(song.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
        val detailScope = rememberCoroutineScope()
        LaunchedEffect(song.id) {
            detailScope.launch {
                val info = withContext(Dispatchers.IO) {
                    NcmApi.albumDetail(song.album?.id ?: 0L)
                }
                if (info != null) albumInfo = info
                // 作词/作曲/编曲：官方歌词接口头部就是制作信息行，顺路解析
                val rows = withContext(Dispatchers.IO) {
                    try { NcmApi.lyric(song.id) } catch (_: Exception) { emptyList() }
                }
                credits = extractCredits(rows)
            }
        }
        AlertDialog(
            onDismissRequest = { showDetail = false },
            title = { Text(stringResource(R.string.detail)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CoverImage(
                        url = song.coverUrl?.let { addParam(it, "500y500") },
                        modifier = Modifier.size(96.dp),
                        cornerRadius = 12
                    )
                    Spacer(Modifier.height(dimensionResource(R.dimen.space_m)))
                    Text(
                        song.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (song.isVip) {
                        Spacer(Modifier.height(4.dp))
                        VipBadge()
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        song.artistNames,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(dimensionResource(R.dimen.space_m)))
                    HorizontalDivider()
                    Spacer(Modifier.height(dimensionResource(R.dimen.space_s)))
                    DetailRow(stringResource(R.string.detail_album), song.album?.name ?: "-")
                    DetailRow(stringResource(R.string.detail_duration), formatDuration(song.durationMs))
                    DetailRow(
                        stringResource(R.string.detail_quality),
                        qualityLabel(PlayerController.qualityLevel)
                    )
                    DetailRow(
                        stringResource(R.string.detail_status),
                        if (song.isVip) stringResource(R.string.detail_vip)
                        else stringResource(R.string.detail_free)
                    )
                    if (albumInfo != null) {
                        val a = albumInfo ?: return@Column
                        if (a.publishTimeMs > 0) {
                            DetailRow(
                                stringResource(R.string.detail_publish_time),
                                SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(a.publishTimeMs))
                            )
                        }
                        if (a.company.isNotBlank()) {
                            DetailRow(stringResource(R.string.detail_company), a.company)
                        }
                    }
                    listOf("作词" to R.string.detail_lyricist, "作曲" to R.string.detail_composer, "编曲" to R.string.detail_arranger)
                        .forEach { (key, labelRes) ->
                            val v = credits[key]
                            if (!v.isNullOrBlank()) {
                                DetailRow(stringResource(labelRes), v)
                            }
                        }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetail = false }) { Text(stringResource(R.string.close)) }
            }
        )
    }

    // 下载音质选择对话框
    if (showAddToPlaylist) {
        AddToPlaylistDialog(
            songId = song.id,
            onChanged = { refreshSaved() },
            onDismiss = { showAddToPlaylist = false }
        )
    }

    if (showDownload) {
        // 下载授权守卫必须在**对话框之外**的组合里创建（AlertDialog 是独立窗口组合，
        // 拿不到 LocalActivityResultRegistryOwner）；Android 9 及以下会先弹授权、同意后自动续跑
        val withStorage = rememberStoragePermissionGuard()
        DownloadQualityDialog(
            song = song,
            onDismiss = { showDownload = false },
            onDownload = { level -> withStorage { downloadSingleSong(ctx, song, level) } }
        )
    }

    if (showComments) {
        CommentSheet(song = song, onDismiss = { showComments = false })
    }
}

/**
 * 从歌词（官方接口返回，头部就是制作信息行）里提取 作词/作曲/编曲。
 * 找不到的键不出现；歌词页里这些行不参与译文配对（见 net/Lyrics.kt），但详情里展示出来。
 */
private fun extractCredits(rows: List<LyricRow>): Map<String, String> {
    val keys = listOf("作词", "作曲", "编曲")
    val out = LinkedHashMap<String, String>()
    for (r in rows) {
        val t = r.text.trim()
        for (k in keys) {
            if (t.startsWith(k) && !out.containsKey(k)) {
                val v = t.removePrefix(k)
                    .trim().removePrefix(":").trim().removePrefix("：").trim()
                if (v.isNotEmpty()) out[k] = v
            }
        }
        if (out.size == keys.size) break
    }
    return out
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label + "：",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 下载音质选择对话框：档位单选（复用统一选择弹窗），选完按该音质下载 */
@Composable
internal fun DownloadQualityDialog(
    song: Song,
    onDismiss: () -> Unit,
    /** 提供时走自定义下载逻辑（如歌单下载全部），否则下载单曲 [song] */
    onDownload: ((String) -> Unit)? = null
) {
    val ctx = LocalContext.current
    val current = PlayerController.qualityLevel
    SelectionDialog(
        title = stringResource(R.string.download),
        options = QUALITY_LEVELS.map {
            SelectionOption(
                key = it,
                label = qualityLabel(it),
                subtitle = if (it == current) stringResource(R.string.quality_current) else null
            )
        },
        selectedKey = current,
        onSelected = { level ->
            if (onDownload != null) {
                onDownload(level)
            } else {
                downloadSingleSong(ctx, song, level)
            }
            onDismiss()
        },
        onDismiss = onDismiss
    )
}


/**
 * 单曲下载（下载音质对话框确认后调用）。
 *
 * 用 [Downloader.taskScope] 而不是对话框的 rememberCoroutineScope：
 * onDismiss 会移除对话框并取消其 scope，下载其实在 IO 上跑完了，
 * 但 CancellationException 会被当成失败 —— 出现"提示下载失败，文件却已保存"的假失败。
 */
internal fun downloadSingleSong(ctx: Context, song: Song, level: String) {
    Downloader.taskScope.launch {
        Toast.makeText(ctx, R.string.downloading, Toast.LENGTH_SHORT).show()
        var okPath: String? = null
        var failMsg: String? = null
        try {
            okPath = withContext(Dispatchers.IO) { Downloader.downloadSong(ctx, song, level) }
        } catch (e: Exception) {
            failMsg = e.message
        }
        if (okPath != null) {
            Toast.makeText(ctx, R.string.downloaded, Toast.LENGTH_SHORT).show()
        } else {
            val msg = when {
                failMsg == "no_url" -> ctx.getString(R.string.download_no_url)
                failMsg != null && failMsg.startsWith("http:") -> ctx.getString(R.string.download_http)
                failMsg != null -> ctx.getString(R.string.download_failed_reason, failMsg)
                else -> ctx.getString(R.string.download_failed)
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun VipBadge() {
    Text(
        "VIP",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(
                color = Color(0xFFD4AF37),
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

// ═══ 歌词页（右）═══

@Composable
private fun LyricsPage() {
    // 歌词行由 net/Lyrics.kt 在 IO 线程解析 + 配对完毕，UI 只负责渲染
    val rows = PlayerController.lyrics
    val listState = rememberLazyListState()

    // 当前歌词行：用 derivedStateOf 派生 —— 只有"行号真的变了"才让本页重组，
    // 不再跟着 positionMs 每 500ms 重算一次（原来是全表线性扫 + withIndex 分配）。
    // 歌词按时间升序，故改二分查找。
    val currentIndex by remember(rows) {
        derivedStateOf {
            val position = PlayerController.positionMs
            var lo = 0
            var hi = rows.lastIndex
            var idx = 0
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (rows[mid].timeMs <= position) {
                    idx = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            idx
        }
    }

    LaunchedEffect(currentIndex) {
        if (rows.isNotEmpty()) {
            listState.animateScrollToItem((currentIndex - 3).coerceAtLeast(0))
        }
    }

    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.no_lyrics),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    // 「歌词上下渐变」：不再用背景色遮罩盖住上下边缘 —— 遮罩是死的，
    // 会把"正在播放行"和它的邻行一起罩灰（歌曲开头/结尾当前行贴边时尤其难受）。
    // 改为每行按"到视口上下边缘的距离"计算透明度，写在 graphicsLayer 里
    // （draw 阶段求值：滚动时只重绘、不重组）。当前播放行豁免，永远全亮。
    // 渐隐距离 120dp；当前行 ±1 邻行全亮；**其余行透明度下限 25%** ——
    // 任何歌词行都不会完全消失（上一版远端行 alpha 会到 0，用户看到"歌词消失"）。
    val fadePx = with(LocalDensity.current) { 120.dp.toPx() }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(rows) { index, row ->
            val isCurrent = index == currentIndex
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        if (PlayerController.lyricsFade && !isCurrent) {
                            val info = listState.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.index == index } ?: return@graphicsLayer
                            val vp = listState.layoutInfo.viewportSize.height
                            val d = minOf(
                                info.offset.toFloat(),
                                (vp - info.offset - info.size).toFloat()
                            ).coerceIn(0f, fadePx)
                            // 当前行 ±1 全亮；其余行最低保持 25% 可见
                            alpha = when {
                                kotlin.math.abs(index - currentIndex) <= 1 -> 1f
                                else -> 0.25f + 0.75f * (d / fadePx)
                            }
                        }
                    }
                    .padding(vertical = 6.dp)
            ) {
                Text(
                    row.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    },
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
                // 译文行：字号小一档、透明度更低；当前行也用主色但略淡，保持主次
                val transText = row.translation
                if (transText != null) {
                    Text(
                        transText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f)
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
        item { Spacer(Modifier.height(dimensionResource(R.dimen.space_xl))) }
    }
}

// ═══ 底部控制区 ═══

@Composable
private fun PlayerControls() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    ) {
        ProgressRow()
        ControlButtonsRow()
        ExtraToolsRow()
        ErrorRow()
    }
}

/** 标准进度条（细版）：4dp 圆头轨道 + 小圆点 thumb。
 * 触摸热区 48dp（轨道视觉仍居中，四周留白好命中）；
 * 点击跳转 / 拖动跟手（tap 与 drag 手势分离，互不干扰）；
 * 拖动时 thumb 放大并在上方显示实时时间气泡。 */
@Composable
private fun StandardTrackProgress(
    progress: Float,
    dragTimeText: String = "",
    onDraggingChange: (Boolean) -> Unit = {},
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    var dragging by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .height(48.dp)
            .pointerInput(Unit) {
                detectTapGestures { onSeek(it.x / size.width.coerceAtLeast(1).toFloat()) }
            }
            // 独立 pointerInput：拖动不与被 tap 检测抢占（修复"拉不动"）
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        onDraggingChange(true)
                    },
                    onDragEnd = {
                        dragging = false
                        onDraggingChange(false)
                    },
                    onDragCancel = {
                        dragging = false
                        onDraggingChange(false)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        onSeek(change.position.x / size.width.coerceAtLeast(1).toFloat())
                    }
                )
            }
    ) {
        // 视觉轨道：保持原 24dp 高外观，垂直居中（拖动放大 thumb 不影响布局）
        Canvas(
            Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .height(24.dp)
        ) {
            val stroke = 4.dp.toPx()
            val midY = size.height / 2f
            // 轨道（浅色）
            drawLine(
                primary.copy(alpha = 0.2f),
                Offset(0f, midY),
                Offset(size.width, midY),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            // 进度（主色）
            val endX = (progress * size.width).coerceIn(0f, size.width)
            drawLine(
                primary,
                Offset(0f, midY),
                Offset(endX, midY),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            // 圆点 thumb：拖动时放大
            drawCircle(primary, (if (dragging) 9.dp else 6.dp).toPx(), Offset(endX, midY))
        }

        // 拖动时间气泡：贴轨道上方、跟随 thumb 水平位置
        if (dragging) {
            val bubbleW = 56.dp
            val bubbleH = 22.dp
            val bubbleX = (maxWidth * progress - bubbleW / 2)
                .coerceIn(0.dp, (maxWidth - bubbleW).coerceAtLeast(0.dp))
            Box(
                modifier = Modifier
                    .offset(x = bubbleX, y = 0.dp)
                    .size(bubbleW, bubbleH)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.inverseSurface),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    dragTimeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface
                )
            }
        }
    }
}

/** 波浪进度条：整条波浪（已播段主色/未播段浅色）。
 * 播放中：小幅度圆润波浪流动；停止：完全直线。与标准进度条同粗、无圆点端点。
 * 触摸热区 48dp；点击/拖动跳转（手势分离）；拖动时时间气泡 + thumb 放大。 */
@Composable
private fun WaveTrackProgress(
    progress: Float,
    dragTimeText: String = "",
    onDraggingChange: (Boolean) -> Unit = {},
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    playing: Boolean = true
) {
    // 相位（横向流动）动画
    val infiniteTransition = rememberInfiniteTransition(label = "WaveFlow")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Phase"
    )
    // 振幅微呼吸（播放中才起伏）
    val ampK by infiniteTransition.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Amp"
    )
    val primary = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    // 停止 → 振幅平滑归零（波浪 400ms 过渡成直线，不突变）；播放中 → 小波浪
    val ampSmooth by animateFloatAsState(
        targetValue = if (playing) ampK else 0f,
        animationSpec = tween(400),
        label = "AmpTrans"
    )
    val phaseVal = if (playing) phase else 0f
    var dragging by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .height(48.dp)
            .pointerInput(Unit) {
                detectTapGestures { onSeek(it.x / size.width.coerceAtLeast(1).toFloat()) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        onDraggingChange(true)
                    },
                    onDragEnd = {
                        dragging = false
                        onDraggingChange(false)
                    },
                    onDragCancel = {
                        dragging = false
                        onDraggingChange(false)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        onSeek(change.position.x / size.width.coerceAtLeast(1).toFloat())
                    }
                )
            }
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .height(24.dp)
        ) {
            // 与标准进度条同粗（4dp），圆头描边（波浪圆润无棱角）
            val stroke = 4.dp.toPx()
            val midY = size.height / 2f
            val amp = size.height * ampSmooth
            val omega = (2f * Math.PI.toFloat()) / 48.dp.toPx() // 波长约 48dp
            val endX = (progress * size.width).coerceIn(0f, size.width)

            fun waveY(x: Float): Float = midY + amp * kotlin.math.sin(x * omega + phaseVal)

            // 未播段（endX → width）：浅色
            val tail = Path().apply {
                moveTo(endX, waveY(endX))
                var x = endX
                while (x <= size.width) {
                    lineTo(x, waveY(x))
                    x += 1f
                }
            }
            drawPath(
                tail, trackColor,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke, cap = StrokeCap.Round
                )
            )

            // 已播段（0 → endX）：主色
            val head = Path().apply {
                moveTo(0f, waveY(0f))
                var x = 0f
                while (x <= endX) {
                    lineTo(x, waveY(x))
                    x += 1f
                }
            }
            drawPath(
                head, primary,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke, cap = StrokeCap.Round
                )
            )
            // 进度前端圆点（拖动时放大）
            drawCircle(
                primary,
                radius = (if (dragging) 9.dp else 6.dp).toPx(),
                center = Offset(endX, waveY(endX))
            )
        }

        // 拖动时间气泡（同标准版）
        if (dragging) {
            val bubbleW = 56.dp
            val bubbleH = 22.dp
            val bubbleX = (maxWidth * progress - bubbleW / 2)
                .coerceIn(0.dp, (maxWidth - bubbleW).coerceAtLeast(0.dp))
            Box(
                modifier = Modifier
                    .offset(x = bubbleX, y = 0.dp)
                    .size(bubbleW, bubbleH)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.inverseSurface),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    dragTimeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface
                )
            }
        }
    }
}

@Composable
private fun ProgressRow() {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val duration = PlayerController.durationMs
    val position = PlayerController.positionMs
    val sliderValue = if (dragging) dragValue else if (duration > 0) (position.toFloat() / duration) else 0f
    val dragTimeText = formatDuration((dragValue * duration).toLong())

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            formatDuration(if (dragging) (dragValue * duration).toLong() else position),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (PlayerController.progressStyle == "wave") {
            // 波浪进度条：浅色平直轨道 + 已播放段波浪线横向推进（可拖动）
            WaveTrackProgress(
                progress = sliderValue,
                dragTimeText = dragTimeText,
                onDraggingChange = { dragging = it },
                onSeek = { v ->
                    dragValue = v
                    PlayerController.seekTo((v * duration).toLong())
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                playing = PlayerController.isPlaying
            )
        } else {
            // 标准进度条（细版）：4dp 圆头轨道 + 小圆点，可点击/拖动
            StandardTrackProgress(
                progress = sliderValue,
                dragTimeText = dragTimeText,
                onDraggingChange = { dragging = it },
                onSeek = { v ->
                    dragValue = v
                    PlayerController.seekTo((v * duration).toLong())
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
        }
        Text(
            formatDuration(duration),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ControlButtonsRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 上一首
        IconButton(
            onClick = { PlayerController.prev() },
            enabled = PlayerController.currentIndex > 0
        ) {
            Icon(Icons.Filled.SkipPrevious, stringResource(R.string.prev), modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.width(8.dp))
        // 后退 15s
        IconButton(onClick = { PlayerController.seekBack() }) {
            Icon(Icons.Filled.FastRewind, stringResource(R.string.back30), modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.width(8.dp))
        // 播放/暂停
        IconButton(
            onClick = { PlayerController.toggle() },
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            if (PlayerController.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 3.dp
                )
            } else {
                Icon(
                    if (PlayerController.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (PlayerController.isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        // 快进 15s
        IconButton(onClick = { PlayerController.seekForward() }) {
            Icon(Icons.Filled.FastForward, stringResource(R.string.fwd30), modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.width(8.dp))
        // 下一首
        IconButton(
            onClick = { PlayerController.next() },
            enabled = PlayerController.currentIndex < PlayerController.queue.lastIndex
        ) {
            Icon(Icons.Filled.SkipNext, stringResource(R.string.next), modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun ExtraToolsRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 音质
        QualityButton()
        // 倍速
        SpeedButton()
        // 循环模式
        LoopButton()
        // 定时关闭
        SleepTimerButton()
    }
}

@Composable
private fun QualityButton() {
    var open by remember { mutableStateOf(false) }
    val label = qualityLabel(PlayerController.qualityLevel)

    Box {
        TextButton(onClick = { open = true }) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            QUALITY_LEVELS.forEach { level ->
                DropdownMenuItem(
                    text = { Text(qualityLabel(level)) },
                    onClick = {
                        PlayerController.setQuality(level)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SpeedButton() {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Filled.Speed, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                if (PlayerController.playbackSpeed == 1f) stringResource(R.string.speed_1x)
                else stringResource(
                    R.string.speed_format,
                    "%.2f".format(PlayerController.playbackSpeed).removeSuffix("0")
                ),
                style = MaterialTheme.typography.labelLarge
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { s ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.speed_format, "$s")) },
                    onClick = {
                        PlayerController.setSpeed(s)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
private fun LoopButton() {
    val mode = PlayerController.loopMode
    val icon = when (mode) {
        LoopMode.LIST -> Icons.Filled.Repeat
        LoopMode.ONE -> Icons.Filled.RepeatOne
        LoopMode.SHUFFLE -> Icons.Filled.Shuffle
    }
    val desc = when (mode) {
        LoopMode.LIST -> stringResource(R.string.loop_list)
        LoopMode.ONE -> stringResource(R.string.loop_one)
        LoopMode.SHUFFLE -> stringResource(R.string.loop_shuffle)
    }
    TextButton(onClick = { PlayerController.nextLoopMode() }) {
        Icon(icon, desc, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SleepTimerButton() {
    var open by remember { mutableStateOf(false) }
    val remain = PlayerController.sleepRemainMs
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Filled.Timer, null, modifier = Modifier.size(18.dp))
            if (remain > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.timer_remain_minutes, remain / 60000),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.timer_off)) },
                onClick = { PlayerController.cancelSleepTimer(); open = false }
            )
            listOf(10L, 20L, 30L, 60L, 90L).forEach { min ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.timer_minutes, min)) },
                    onClick = {
                        PlayerController.setSleepTimer(min * 60_000)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ErrorRow() {
    val err = PlayerController.errorText
    if (err != null) {
        Text(
            if (err == "no_url") stringResource(R.string.song_unavailable)
            else stringResource(R.string.play_failed, err),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton(onClick = { PlayerController.retry() }) {
                Text(stringResource(R.string.retry))
            }
        }
        Spacer(Modifier.height(dimensionResource(R.dimen.space_s)))
    } else {
        Spacer(Modifier.height(dimensionResource(R.dimen.space_m)))
    }
}
