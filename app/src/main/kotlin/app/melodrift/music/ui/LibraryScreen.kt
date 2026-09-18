package app.melodrift.music.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.melodrift.music.R
import app.melodrift.music.net.Json
import app.melodrift.music.data.SavedSongsCache
import app.melodrift.music.net.NcmApi
import app.melodrift.music.net.Playlist
import app.melodrift.music.net.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 收藏页数据：一次 userPlaylists 调用解析出全部三个分区 */
internal data class LibraryData(
    val liked: Playlist?,                       // null = 未找到「我喜欢的音乐」
    val subscribed: List<Playlist>,
    val created: List<Playlist>
)

/** 收藏页内存缓存：60 秒内不重拉网络，避免频繁刷新 */
internal object LibraryCache {
    var cookie: String = ""
    var account: NcmApi.AccountInfo? = null
    var data: LibraryData? = null
    var at: Long = 0L
    val fresh: Boolean
        get() = data != null && System.currentTimeMillis() - at < 60_000

    /** 强制下次进入时重新拉取（歌单增删后调用） */
    fun invalidate() {
        at = 0L
    }
}

/**
 * 收藏页：喜欢的歌曲（我喜欢的音乐）+ 收藏的歌单 + 创建的歌单。
 * 数据有内存缓存：进入页面秒开旧数据，过期后静默更新；下拉手动刷新；支持新建歌单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    cookie: String,
    onOpenPlaylist: (Long, String) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit
) {
    var account by remember { mutableStateOf(LibraryCache.account) }
    var data by remember { mutableStateOf<LibraryData?>(LibraryCache.data) }
    var refreshing by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    suspend fun fetchAccount(): NcmApi.AccountInfo? = try {
        withContext(Dispatchers.IO) { NcmApi.account() }
    } catch (_: Exception) {
        null
    }

    suspend fun fetchAll(uid: Long): LibraryData {
        val arr = withContext(Dispatchers.IO) { NcmApi.userPlaylists(uid, 100, 0) }
        val all = NcmApi.parseUserPlaylists(arr, subscribedOnly = false)
        var liked: Playlist? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optInt("specialType", 0) == 5 ||
                o.optString("name", "").contains("喜欢") ||
                o.optString("name", "").contains("Liked")
            ) {
                liked = Json.parsePlaylist(o)
                break
            }
        }
        return LibraryData(liked, all.filter { it.subscribed }, all.filter { !it.subscribed })
    }

    suspend fun refresh(force: Boolean = false) {
        val cache = LibraryCache
        if (!force && cache.data != null && cache.fresh && cache.cookie == cookie) {
            account = cache.account
            data = cache.data
            return
        }
        val acc = fetchAccount()
        account = acc
        if (acc != null) {
            val d = try { fetchAll(acc.userId) } catch (_: Exception) { null }
            if (d != null) {
                data = d
                cache.cookie = cookie
                cache.account = acc
                cache.data = d
                cache.at = System.currentTimeMillis()
            }
        }
    }

    LaunchedEffect(cookie) {
        if (cookie.isBlank()) {
            account = null
            data = null
        } else {
            refresh(force = false)
        }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                refresh(force = true)
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = dimensionResource(R.dimen.page_padding))
    ) {
        item {
            Text(
                stringResource(R.string.nav_library),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
            )
        }

        val acc = account
        val libData = data
        when {
            acc == null -> {
                item {
                    Text(
                        stringResource(R.string.not_logged_in),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
                return@LazyColumn
            }
            libData == null -> {
                item { LoadingBox(stringResource(R.string.loading)) }
                return@LazyColumn
            }
        }

        // 我喜欢的音乐
        item { SectionTitle(stringResource(R.string.library_liked_songs)) }
        item {
            if (libData.liked != null) {
                LikedMusicCard(libData.liked) { onOpenPlaylist(libData.liked.id, libData.liked.name) }
            } else {
                Text(
                    stringResource(R.string.library_no_liked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = dimensionResource(R.dimen.space_s))
                )
            }
        }

        // 收藏的歌单
        item { SectionTitle(stringResource(R.string.library_subscribed)) }
        item {
            if (libData.subscribed.isEmpty()) {
                Text(
                    stringResource(R.string.library_no_subscribed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = dimensionResource(R.dimen.space_s))
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(libData.subscribed, key = { it.id }) { pl ->
                        PlaylistCardX(pl) { onOpenPlaylist(pl.id, pl.name) }
                    }
                }
            }
        }

        // 我创建的歌单（标题行带「新建歌单」按钮）
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle(stringResource(R.string.library_created), modifier = Modifier.weight(1f))
                IconButton(onClick = { showCreateDialog = true }) {
                    Icon(
                        Icons.Filled.AddCircleOutline,
                        contentDescription = stringResource(R.string.create_playlist),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        item {
            if (libData.created.isEmpty()) {
                Text(
                    stringResource(R.string.library_no_created),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = dimensionResource(R.dimen.space_s))
                )
            } else {
                Column {
                    libData.created.forEach { pl ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenPlaylist(pl.id, pl.name) }
                                .padding(vertical = dimensionResource(R.dimen.space_s)),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CoverImage(
                                url = pl.coverUrl?.let { addParam(it, "100y100") },
                                modifier = Modifier
                                    .width(52.dp)
                                    .aspectRatio(1f),
                                cornerRadius = 8
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    pl.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    stringResource(R.string.track_count, pl.trackCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(dimensionResource(R.dimen.space_l))) }
    }
    }

    // 新建歌单对话框
    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        var creating by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!creating) showCreateDialog = false },
            title = { Text(stringResource(R.string.create_playlist)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.create_playlist_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(30) },
                        placeholder = { Text(stringResource(R.string.create_playlist_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && !creating,
                    onClick = {
                        creating = true
                        scope.launch {
                            var failReason: String? = null
                            val ok = try {
                                withContext(Dispatchers.IO) {
                                    NcmApi.createPlaylist(name.trim()) > 0
                                }
                            } catch (e: Exception) {
                                // 把服务端给的真实原因带出来（如：需绑定手机号 / 数量上限 / 操作过快）
                                failReason = e.message
                                false
                            }
                            creating = false
                            showCreateDialog = false
                            Toast.makeText(
                                ctx,
                                when {
                                    ok -> ctx.getString(R.string.create_ok)
                                    !failReason.isNullOrBlank() -> failReason
                                    else -> ctx.getString(R.string.create_failed)
                                },
                                Toast.LENGTH_LONG
                            ).show()
                            if (ok) {
                                SavedSongsCache.invalidate()
                                refreshing = true
                                refresh(force = true)
                                refreshing = false
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.create))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !creating,
                    onClick = { showCreateDialog = false }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun LikedMusicCard(pl: Playlist, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = dimensionResource(R.dimen.space_s)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(
            url = pl.coverUrl?.let { addParam(it, "120y120") },
            modifier = Modifier
                .width(64.dp)
                .aspectRatio(1f),
            cornerRadius = 10
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                pl.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(R.string.track_count, pl.trackCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            stringResource(R.string.play_all),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun PlaylistCardX(pl: Playlist, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(132.dp)
            .clickable(onClick = onClick)
    ) {
        Box {
            CoverImage(
                url = pl.coverUrl?.let { addParam(it, "240y240") },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            )
            if (pl.playCount > 0) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Headphones,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        formatCount(pl.playCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }
        Text(
            pl.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}