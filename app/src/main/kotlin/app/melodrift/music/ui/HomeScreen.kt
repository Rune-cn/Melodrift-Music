package app.melodrift.music.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import app.melodrift.music.R
import app.melodrift.music.net.NcmApi
import app.melodrift.music.net.NcmApi.Toplist
import app.melodrift.music.net.Playlist
import app.melodrift.music.net.Song
import app.melodrift.music.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    cookie: String,
    onOpenPlaylist: (Long, String) -> Unit,
    onOpenSongList: (String, List<Song>) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettingsPage: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf<NcmApi.AccountInfo?>(null) }
    var dailyLoading by remember { mutableStateOf(false) }
    val dailyTitle = stringResource(R.string.daily_recommend)

    // 账号信息（cookie 变化时刷新；60s 内复用缓存，避免频繁请求）
    LaunchedEffect(cookie) {
        account = null
        if (cookie.isBlank()) {
            HomeAccountCache.clear()
            return@LaunchedEffect
        }
        if (HomeAccountCache.fresh) {
            account = HomeAccountCache.value
            return@LaunchedEffect
        }
        account = try {
            withContext(Dispatchers.IO) { NcmApi.account() }.also {
                HomeAccountCache.put(it)
            }
        } catch (_: Exception) {
            null // 未登录/失败 → 显示游客文案
        }
    }

    val openDaily: () -> Unit = {
        if (!dailyLoading) {
            dailyLoading = true
            scope.launch {
                val songs = try {
                    withContext(Dispatchers.IO) { NcmApi.dailySongs() }
                } catch (_: Exception) {
                    emptyList()
                }
                dailyLoading = false
                if (songs.isNotEmpty()) {
                    // 未登录或昵称缺失时不要拼出"· 每日推荐"这种带头分隔符的标题
                    val nick = account?.nickname?.trim().orEmpty()
                    onOpenSongList(if (nick.isEmpty()) dailyTitle else "$nick · $dailyTitle", songs)
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = dimensionResource(R.dimen.page_padding))
    ) {
        // 问候
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (account != null) {
                            stringResource(R.string.hello_user, account!!.nickname)
                        } else {
                            stringResource(R.string.hello_guest)
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (cookie.isBlank()) {
                        Text(
                            stringResource(R.string.not_logged_in),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 搜索（放大镜）入口
                IconButton(onClick = onOpenSearch) {
                    Icon(
                        Icons.Filled.Search,
                        stringResource(R.string.nav_search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 设置入口
                IconButton(onClick = onOpenSettingsPage) {
                    Icon(
                        Icons.Filled.Settings,
                        stringResource(R.string.nav_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 头像（最右）
                Avatar(url = account?.avatarUrl, modifier = Modifier.size(44.dp))
            }
        }

        // 每日推荐卡片
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = dimensionResource(R.dimen.space_s))
                    .clickable(onClick = openDaily),
                shape = RoundedCornerShape(dimensionResource(R.dimen.card_radius)),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.DateRange,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            stringResource(R.string.daily_recommend),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(R.string.daily_recommend_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        // 推荐歌单
        item { SectionTitle(stringResource(R.string.recommended_playlists)) }
        item {
            AsyncContent(
                load = { NcmApi.personalizedPlaylists(8) },
                retryKey = "personalized"
            ) { playlists ->
                if (playlists.isEmpty()) {
                    Text(
                        stringResource(R.string.not_logged_in),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(playlists, key = { it.id }) { pl ->
                            PlaylistCard(pl) { onOpenPlaylist(pl.id, pl.name) }
                        }
                    }
                }
            }
        }

        // 排行榜
        item { SectionTitle(stringResource(R.string.toplists)) }
        item {
            AsyncContent(
                load = { NcmApi.toplists() },
                retryKey = "toplist"
            ) { lists ->
                val picks = pickHotToplists(lists)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(picks, key = { it.id }) { t ->
                        ToplistCard(t) { onOpenPlaylist(t.id, t.name) }
                    }
                }
            }
        }

        // 播放历史（最近播放，点击续播）
        val history = PlayerController.playHistory
        if (history.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.play_history)) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(history, key = { it.id }) { s ->
                        HistorySongCard(s) { onPlaySongs(history, history.indexOf(s)) }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(dimensionResource(R.dimen.space_m))) }
    }
}

/** 播放历史卡片：封面 + 歌名 + 歌手 */
@Composable
private fun HistorySongCard(song: Song, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable(onClick = onClick)
    ) {
        Box {
            CoverImage(
                url = song.coverUrl?.let { addParam(it, "240y240") },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            )
            // 播放角标
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Text(
            song.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            song.artistNames,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 挑出最热榜单（飙升/新歌/原创/热歌），缺失时回退到列表前几个 */
private fun pickHotToplists(lists: List<Toplist>): List<Toplist> {
    val want = mapOf(
        19723756L to "飙升榜",
        3779629L to "新歌榜",
        2884035L to "原创榜",
        3778678L to "热歌榜"
    )
    val picked = mutableListOf<Toplist>()
    for ((id, _) in want) {
        lists.firstOrNull { it.id == id }?.let { picked.add(it) }
    }
    if (picked.size < 4) {
        for (t in lists) {
            if (picked.size >= 4) break
            if (picked.none { it.id == t.id }) picked.add(t)
        }
    }
    return picked
}

@Composable
private fun PlaylistCard(pl: Playlist, onClick: () -> Unit) {
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
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Headphones,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        formatCount(pl.playCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = androidx.compose.ui.graphics.Color.White
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

@Composable
private fun ToplistCard(t: Toplist, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(132.dp)
            .clickable(onClick = onClick)
    ) {
        CoverImage(
            url = t.coverUrl?.let { addParam(it, "240y240") },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        )
        Text(
            t.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/** 网易图片 URL 缩放参数 */
fun addParam(url: String, param: String): String =
    if (url.contains("?")) "$url&param=$param" else "$url?param=$param"

/** 首页账号信息缓存（60s）：切换 Tab / 返回首页时不重复请求 */
private object HomeAccountCache {
    var value: NcmApi.AccountInfo? = null
    var at: Long = 0L
    const val TTL = 60_000L
    val fresh: Boolean
        get() = value != null && System.currentTimeMillis() - at < TTL
    fun put(v: NcmApi.AccountInfo) {
        value = v
        at = System.currentTimeMillis()
    }
    fun clear() {
        value = null
        at = 0L
    }
}