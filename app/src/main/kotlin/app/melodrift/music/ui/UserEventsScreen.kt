package app.melodrift.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.melodrift.music.R
import app.melodrift.music.net.NcmApi
import app.melodrift.music.net.NcmApi.EventItem
import app.melodrift.music.net.Song
import app.melodrift.music.net.ioNet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * 用户动态：从个人主页「动态」数进入。
 * 分页拉取（lasttime 游标），行里可播放分享的歌曲 / 打开分享的歌单。
 */

@Composable
fun UserEventsScreen(
    uid: Long,
    onBack: () -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onOpenPlaylist: (Long, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var items by remember(uid) { mutableStateOf<List<EventItem>>(emptyList()) }
    var nextTime by remember(uid) { mutableLongStateOf(-1L) }
    var hasMore by remember(uid) { mutableStateOf(true) }
    var loading by remember(uid) { mutableStateOf(true) }
    var loadingMore by remember(uid) { mutableStateOf(false) }
    var failed by remember(uid) { mutableStateOf(false) }
    var errorMsg by remember(uid) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    suspend fun loadFirst() {
        loading = true
        failed = false
        errorMsg = null
        // 直接捕获异常信息：区分「风控/需登录（code 301）」与「真的没动态」，方便反馈与调试
        val page = try {
            withContext(Dispatchers.IO) { NcmApi.userEvents(uid, -1L, 20) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMsg = e.message ?: "error"
            null
        }
        if (page == null) {
            failed = true
        } else {
            items = page.items
            nextTime = page.nextTime
            hasMore = page.hasMore
        }
        loading = false
    }

    suspend fun loadMore() {
        if (loadingMore || !hasMore || loading || failed) return
        loadingMore = true
        val page = ioNet { NcmApi.userEvents(uid, nextTime, 20) }
        if (page == null) {
            hasMore = false
        } else {
            val seen = items.mapTo(HashSet()) { it.id }
            items = items + page.items.filter { seen.add(it.id) }
            nextTime = page.nextTime
            hasMore = page.hasMore && page.items.isNotEmpty()
        }
        loadingMore = false
    }

    androidx.compose.runtime.LaunchedEffect(uid) { loadFirst() }

    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.visibleItemsInfo.isNotEmpty() &&
                info.visibleItemsInfo.last().index >= info.totalItemsCount - 4
        }
    }
    androidx.compose.runtime.LaunchedEffect(nearEnd, items.size) { if (nearEnd) loadMore() }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = dimensionResource(R.dimen.page_padding),
                    end = dimensionResource(R.dimen.icon_button_touch_min),
                    top = 4.dp
                )
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close))
            }
            Text(
                stringResource(R.string.user_events),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        Box(Modifier.weight(1f)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            errorMsg?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.load_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.page_padding))
                        )
                        androidx.compose.material3.TextButton(
                            onClick = { scope.launch { loadFirst() } }
                        ) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }

                items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.user_events_private),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.page_padding))
                    )
                }

                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = dimensionResource(R.dimen.page_padding),
                        end = dimensionResource(R.dimen.page_padding),
                        top = dimensionResource(R.dimen.space_s),
                        bottom = dimensionResource(R.dimen.list_bottom_padding)
                    )
                ) {
                    items(items, key = { it.id }) { e ->
                        UserEventRow(
                            event = e,
                            onPlay = e.song?.let { song ->
                                { onPlaySongs(listOf(song), 0) }
                            },
                            onOpenPlaylist = e.playlistName?.let { name ->
                                { onOpenPlaylist(e.playlistId, name) }
                            }
                        )
                    }
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = dimensionResource(R.dimen.space_l)),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                loadingMore -> CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp), strokeWidth = 2.dp
                                )

                                !hasMore -> Text(
                                    stringResource(R.string.user_events_no_more),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 一条动态：类型标签 + 文案，附可操作的 歌曲/歌单 */
@Composable
internal fun UserEventRow(
    event: EventItem,
    onPlay: (() -> Unit)?,
    onOpenPlaylist: (() -> Unit)?
) {
    Column(Modifier.fillMaxWidth().padding(vertical = dimensionResource(R.dimen.space_m))) {
        Row(verticalAlignment = Alignment.Top) {
            if (event.song != null) {
                CoverImage(
                    url = event.song.coverUrl?.let { addParam(it, "100y100") },
                    modifier = Modifier.size(dimensionResource(R.dimen.cover_mini)),
                    cornerRadius = 8
                )
            } else {
                Box(
                    Modifier
                        .size(dimensionResource(R.dimen.cover_mini))
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp)
                        )
                )
            }
            Spacer(Modifier.width(dimensionResource(R.dimen.space_m)))
            Column(Modifier.weight(1f)) {
                Text(
                    eventLabel(event),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                androidx.compose.material3.Text(
                    event.text.ifBlank { " " },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                if (event.hasImages) {
                    Text(
                        stringResource(R.string.event_has_images),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (onPlay != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onPlay)
                            .padding(horizontal = dimensionResource(R.dimen.space_s), vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(dimensionResource(R.dimen.space_xs)))
                        Text(
                            event.song!!.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (onOpenPlaylist != null) {
                    Text(
                        event.playlistName ?: "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable(onClick = onOpenPlaylist)
                            .padding(top = 4.dp)
                    )
                }
                Text(
                    eventTime(event.timeMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/** 根据内容推导类型标签（不依赖 event type 数字） */
@Composable
internal fun eventLabel(event: EventItem): String = when {
    event.song != null -> stringResource(R.string.event_share_song)
    event.playlistName != null -> stringResource(R.string.event_share_playlist)
    event.hasImages -> stringResource(R.string.event_share_photo)
    else -> stringResource(R.string.user_events)
}

internal fun eventTime(timeMs: Long): String {
    if (timeMs <= 0) return ""
    val delta = System.currentTimeMillis() - timeMs
    return when {
        delta < 60_000L -> "刚刚"
        delta < 3_600_000L -> "${delta / 60_000L}分钟前"
        delta < 86_400_000L -> "${delta / 3_600_000L}小时前"
        delta < 7 * 86_400_000L -> "${delta / 86_400_000L}天前"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date(timeMs))
    }
}