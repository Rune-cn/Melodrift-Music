package app.melodrift.music.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.melodrift.music.R
import app.melodrift.music.data.SavedPlaylists
import app.melodrift.music.data.SavedSongsCache
import app.melodrift.music.net.Downloader
import app.melodrift.music.net.NcmApi
import app.melodrift.music.net.PlaylistDetail
import app.melodrift.music.net.Song
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通用歌曲列表页：
 * - [playlistId] 非空 → 从歌单详情加载（头部信息随列表一起滚动）
 * - [preloadedSongs] 非空 → 直接用已加载歌曲（每日推荐等场景）
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SongListScreen(
    title: String,
    playlistId: Long?,
    preloadedSongs: List<Song>?,
    onBack: () -> Unit,
    onDeleted: () -> Unit = {},
    onPlaySongs: (List<Song>, Int) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (playlistId != null && preloadedSongs == null) {
                                AsyncContent(
                    load = { NcmApi.playlistDetail(playlistId) },
                    retryKey = "pl-$playlistId"
                ) { detail: PlaylistDetail ->
                    PlaylistBody(detail, onPlaySongs)
                }
            } else {
                val songs = preloadedSongs ?: emptyList()
                if (songs.isEmpty()) {
                    CenterText(stringResource(R.string.no_tracks))
                } else {
                    SongsBody(songs, onPlaySongs)
                }
            }
        }
    }
}

@Composable
private fun PlaylistBody(
    detail: PlaylistDetail,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onDeleted: () -> Unit = {}
) {
    // 顶部下拉刷新在页内重取详情：liveDetail 影子顶替参数名，函数内其余引用零改动
    var liveDetail by remember(detail.id) { mutableStateOf(detail) }
    val detail = liveDetail
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // 用歌单详情的真实收藏状态；可取消收藏
    var subscribed by remember(detail.id) { mutableStateOf(detail.subscribed) }
    var subBusy by remember { mutableStateOf(false) }
    // 当前登录用户昵称（用于判断是否自己创建的歌单，决定是否显示删除）
    var myName by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var downloadBusy by remember { mutableStateOf(false) }
    // 下载授权守卫在组合里创建（Android 9 及以下先授权后自动续跑）；传入对话框的 lambda 里不能再 remember
    val withStorage = rememberStoragePermissionGuard()

    LaunchedEffect(detail.id) {
        myName = try {
            withContext(Dispatchers.IO) { NcmApi.account().nickname }
        } catch (_: Exception) {
            null
        }
    }

    // 红心真实状态：进入页面时批量查询（我喜欢的音乐 likeCheck + 服务器已收藏集合）
    var likedIds by remember(detail.id) { mutableStateOf<Set<Long>?>(null) }
    var savedIds by remember(detail.id) { mutableStateOf<Set<Long>?>(null) }
    var pickerSong by remember { mutableStateOf<Song?>(null) }
    suspend fun fetchLiked() {
        likedIds = try {
            withContext(Dispatchers.IO) { NcmApi.likeCheck(detail.songs.map { it.id }) }
        } catch (_: Exception) {
            null
        }
    }
    suspend fun fetchSavedSongs() {
        if (!SavedSongsCache.fresh) {
            savedIds = withContext(Dispatchers.IO) { SavedSongsCache.refresh() }
        } else {
            savedIds = SavedSongsCache.current()
        }
    }
    LaunchedEffect(detail.id) { fetchLiked(); fetchSavedSongs() }
    // 是否"已收藏"：在「我喜欢的音乐」或任一自建歌单（服务器为准）
    fun isSaved(song: Song): Boolean =
        likedIds?.contains(song.id) == true || savedIds?.contains(song.id) == true

    // 下载歌单全部歌曲：先批量取 URL（1 次请求），再串行下载；失败单首重试一次
    fun downloadAll(level: String) {
        if (detail.songs.isEmpty()) return
        downloadBusy = true
        val progressTpl = ctx.getString(R.string.download_all_progress)
        val doneTpl = ctx.getString(R.string.download_all_done)
        // 下载任务与页面生命周期解耦（退出歌单页不再中断批量下载），见 [Downloader.taskScope]
        Downloader.taskScope.launch {
            var ok = 0
            var fail = 0
            val songs = detail.songs
            val total = songs.size
            // 批量获取播放地址（避免逐首请求触发限流）
            val urls = try {
                withContext(Dispatchers.IO) { NcmApi.songUrls(songs.map { it.id }, level) }
            } catch (_: Exception) {
                emptyMap()
            }
            songs.forEachIndexed { i, song ->
                Toast.makeText(
                    ctx,
                    String.format(progressTpl, i + 1, total),
                    Toast.LENGTH_SHORT
                ).show()
                try {
                    var url = urls[song.id]
                    if (url == null) {
                        url = withContext(Dispatchers.IO) { NcmApi.songUrlSmart(song.id, level) }
                    }
                    if (url == null) {
                        fail++
                    } else {
                        // 单首下载失败重试一次（网络抖动常见）
                        var okSong = false
                        repeat(2) {
                            if (!okSong) {
                                try {
                                    withContext(Dispatchers.IO) {
                                        Downloader.downloadSongUrl(ctx, song, url, level)
                                    }
                                    okSong = true
                                } catch (_: Exception) {
                                    // 稍等再试
                                    kotlinx.coroutines.delay(300)
                                }
                            }
                        }
                        if (okSong) ok++ else fail++
                    }
                } catch (_: Exception) {
                    fail++
                }
            }
            downloadBusy = false
            Toast.makeText(
                ctx,
                String.format(doneTpl, ok, fail),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // 列表 key 必须在 Composable 上下文里 remember（LazyListScope 内不能调 remember）
    val detailKeys = remember(detail.songs) { songKeys(detail.songs) }

    var refreshing by remember(detail.id) { mutableStateOf(false) }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                try {
                    liveDetail = withContext(Dispatchers.IO) { NcmApi.playlistDetail(detail.id) }
                    fetchLiked()
                    fetchSavedSongs()
                } catch (_: Exception) {
                }
                refreshing = false
            }
        }
    ) {
        LazyColumn(Modifier.fillMaxSize()) {            // 头部：封面 + 信息（随列表滚动，简介完整展示自动换行）
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(dimensionResource(R.dimen.page_padding)),
                    verticalAlignment = Alignment.Top
                ) {
                    CoverImage(
                        url = detail.coverUrl?.let { addParam(it, "200y200") },
                        modifier = Modifier.size(110.dp),
                        cornerRadius = 12
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            detail.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${detail.creatorName} · ${stringResource(R.string.track_count, detail.trackCount)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (detail.description.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            // 简介完整展示，长文本自动换行，不固定高度；长按可选中文字复制
                            SelectionContainer {
                                Text(
                                    detail.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            // 操作行：播放全部 + 下载全部 + 收藏/删除
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { if (detail.songs.isNotEmpty()) onPlaySongs(detail.songs, 0) }
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.play_all),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // 下载全部
                    TextButton(
                        enabled = detail.songs.isNotEmpty() && !downloadBusy,
                        onClick = { showDownloadDialog = true }
                    ) {
                        if (downloadBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(4.dp))
                        } else {
                            Icon(
                                Icons.Filled.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            stringResource(R.string.download_all),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    // 自己创建的歌单：可删除
                    if (myName != null && detail.creatorName == myName) {
                        TextButton(
                            enabled = !deleting,
                            onClick = { showDeleteDialog = true }
                        ) {
                            if (deleting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.delete_playlist),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    } else {
                        TextButton(
                            enabled = detail.id != 0L && !subBusy,
                            onClick = {
                                val target = !subscribed
                                subBusy = true
                                scope.launch {
                                    val ok = try {
                                        withContext(Dispatchers.IO) {
                                            NcmApi.subscribePlaylist(detail.id, target)
                                        }
                                    } catch (_: Exception) {
                                        false
                                    }
                                    subBusy = false
                                    if (ok) {
                                        subscribed = target
                                        Toast.makeText(
                                            ctx,
                                            if (target) R.string.subscribed else R.string.unsubscribed,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Toast.makeText(ctx, R.string.op_failed, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        ) {
                            if (subBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(4.dp))
                            } else {
                                Icon(
                                    if (subscribed) Icons.Filled.CheckCircle else Icons.Filled.AddCircleOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                if (subscribed) stringResource(R.string.unsubscribe)
                                else stringResource(R.string.subscribe),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
            if (detail.songs.isEmpty()) {
                item { CenterText(stringResource(R.string.no_tracks)) }
            } else {
                itemsIndexed(detail.songs, key = { i, _ -> detailKeys[i] }) { _, song ->
                    SongRow(
                        song = song,
                        list = detail.songs,
                        onPlaySongs = onPlaySongs,
                        liked = isSaved(song),
                        onAddToPlaylist = { pickerSong = song }
                    )
                }
            }
            item { Spacer(Modifier.height(dimensionResource(R.dimen.space_l))) }
        }

        val picker = pickerSong
        if (picker != null) {
            AddToPlaylistDialog(
                songId = picker.id,
                onChanged = { scope.launch { fetchLiked() } },
                onDismiss = { pickerSong = null }
            )
        }

        // 下载全部：音质选择
        if (showDownloadDialog) {
            val first = detail.songs.firstOrNull()
            if (first != null) {
                DownloadQualityDialog(
                    song = first,
                    onDismiss = { showDownloadDialog = false },
                    onDownload = { level ->
                        showDownloadDialog = false
                        withStorage { downloadAll(level) }
                    }
                )
            }
        }

        // 删除歌单确认
        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { if (!deleting) showDeleteDialog = false },
                title = { Text(stringResource(R.string.delete_playlist)) },
                text = { Text(stringResource(R.string.delete_playlist_confirm)) },
                confirmButton = {
                    TextButton(
                        enabled = !deleting,
                        onClick = {
                            deleting = true
                            scope.launch {
                                val ok = try {
                                    withContext(Dispatchers.IO) {
                                        NcmApi.deletePlaylist(detail.id)
                                    }
                                } catch (_: Exception) {
                                    false
                                }
                                deleting = false
                                showDeleteDialog = false
                                Toast.makeText(
                                    ctx,
                                    if (ok) R.string.delete_ok else R.string.delete_failed,
                                    Toast.LENGTH_SHORT
                                ).show()
                                if (ok) {
                                    // 让歌单详情与收藏页缓存立即失效
                                    invalidateAsyncCache("pl-${detail.id}")
                                    LibraryCache.invalidate()
                                    SavedSongsCache.invalidate()
                                    onDeleted()
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.delete_playlist))
                    }
                },
                dismissButton = {
                    TextButton(enabled = !deleting, onClick = { showDeleteDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun SongsBody(
    songs: List<Song>,
    onPlaySongs: (List<Song>, Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // 红心真实状态（每日推荐等预加载歌单场景）
    var likedIds by remember(songs) { mutableStateOf<Set<Long>?>(null) }
    var savedIds by remember(songs) { mutableStateOf<Set<Long>?>(null) }
    var pickerSong by remember { mutableStateOf<Song?>(null) }
    suspend fun fetchLiked() {
        likedIds = try {
            withContext(Dispatchers.IO) { NcmApi.likeCheck(songs.map { it.id }) }
        } catch (_: Exception) {
            null
        }
    }
    suspend fun fetchSavedSongs() {
        if (!SavedSongsCache.fresh) {
            savedIds = withContext(Dispatchers.IO) { SavedSongsCache.refresh() }
        } else {
            savedIds = SavedSongsCache.current()
        }
    }
    LaunchedEffect(songs) { fetchLiked(); fetchSavedSongs() }
    fun isSaved(song: Song): Boolean =
        likedIds?.contains(song.id) == true || savedIds?.contains(song.id) == true

    val listKeys = remember(songs) { songKeys(songs) }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(songs, key = { i, _ -> listKeys[i] }) { _, song ->
            SongRow(
                song = song,
                list = songs,
                onPlaySongs = onPlaySongs,
                liked = isSaved(song),
                onAddToPlaylist = { pickerSong = song }
            )
        }
        item { Spacer(Modifier.height(dimensionResource(R.dimen.space_l))) }
    }

    val picker = pickerSong
    if (picker != null) {
        AddToPlaylistDialog(
            songId = picker.id,
            onChanged = { scope.launch { fetchLiked() } },
            onDismiss = { pickerSong = null }
        )
    }
}

/** 歌曲行：序号 + 名称(换行) + 歌手 + 时长 + 收藏（爱心点亮 = 已收藏到任意歌单，点击弹歌单选择） */
@Composable
private fun SongRow(
    song: Song,
    list: List<Song>,
    onPlaySongs: (List<Song>, Int) -> Unit,
    liked: Boolean,
    onAddToPlaylist: () -> Unit
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlaySongs(list, list.indexOf(song)) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            (list.indexOf(song) + 1).toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp)
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 长按歌名复制
                Text(
                    song.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2, // 长歌名自动换行
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .combinedClickable(
                            onClick = { onPlaySongs(list, list.indexOf(song)) },
                            onLongClick = {
                                clipboard.setText(AnnotatedString(song.name))
                                Toast.makeText(
                                    ctx, R.string.song_name_copied, Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                )
                if (song.isVip) {
                    Spacer(Modifier.width(4.dp))
                    VipBadge()
                }
            }
            Text(
                song.artistNames,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            formatDuration(song.durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        // 收藏：爱心点亮 = 已收藏到「我喜欢的音乐」或任意自建歌单；点击弹出歌单选择
        IconButton(onClick = onAddToPlaylist) {
            Icon(
                if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = stringResource(R.string.add_to_playlist),
                tint = if (liked) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun CenterText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}