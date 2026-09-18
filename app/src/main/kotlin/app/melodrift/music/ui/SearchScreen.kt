package app.melodrift.music.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.melodrift.music.R
import app.melodrift.music.data.SearchHistory
import app.melodrift.music.net.NcmApi
import app.melodrift.music.net.Playlist
import app.melodrift.music.net.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SearchScreen(
    cookie: String,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onOpenPlaylist: (Long, String) -> Unit
) {
    var keyword by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    var playlists by remember { mutableStateOf<List<Playlist>?>(null) }
    var artists by remember { mutableStateOf<List<NcmApi.SearchArtist>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 搜索历史（空关键词时展示）
    var history by remember { mutableStateOf<List<String>>(emptyList()) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    fun refreshHistory() {
        history = SearchHistory.load(ctx)
    }

    // 进入页面时加载持久化的搜索历史（否则重进页面历史不显示）
    LaunchedEffect(cookie) {
        refreshHistory()
    }

    fun doSearch(kw: String) {
        if (kw.isBlank()) return
        keyword = kw
        searching = true
        error = null
        // 记录搜索历史
        SearchHistory.add(ctx, kw)
        refreshHistory()
        scope.launch {
            try {
                val (s, p, a) = withContext(Dispatchers.IO) {
                    Triple(
                        NcmApi.searchSongs(kw),
                        NcmApi.searchPlaylists(kw),
                        NcmApi.searchArtists(kw)
                    )
                }
                songs = s; playlists = p; artists = a
            } catch (e: Exception) {
                error = e.message ?: "error"
            }
            searching = false
        }
    }

    fun playArtist(artistId: Long) {
        scope.launch {
            try {
                val tops = withContext(Dispatchers.IO) { NcmApi.artistTopSongs(artistId) }
                if (tops.isNotEmpty()) onPlaySongs(tops, 0)
            } catch (_: Exception) {
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = dimensionResource(R.dimen.page_padding))) {
        Spacer(Modifier.height(dimensionResource(R.dimen.space_m)))
        OutlinedTextField(
            value = keyword,
            onValueChange = {
                keyword = it
                if (it.isBlank()) refreshHistory()
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { doSearch(keyword.trim()) })
        )

        if (keyword.isBlank()) {
            // 空关键词：展示搜索历史（有则显示，可点击回填/长按删除/一键清空）
            if (history.isEmpty()) {
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.search_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.weight(1f))
            } else {
                SearchHistoryList(
                    history = history,
                    onPick = { doSearch(it) },
                    onDelete = { kw ->
                        SearchHistory.remove(ctx, kw)
                        refreshHistory()
                    },
                    onClear = {
                        SearchHistory.clear(ctx)
                        refreshHistory()
                    }
                )
            }
            return@Column
        }

        // 结果 Tab
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            listOf(
                stringResource(R.string.search_songs),
                stringResource(R.string.search_playlists),
                stringResource(R.string.search_artists)
            ).forEachIndexed { i, label ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(label) }
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        // 结果区：硬切改为淡入过渡（Tab 切换 / 加载 / 空态都走同一动画）
        AnimatedContent(
            targetState = Triple(searching, error, tab),
            transitionSpec = {
                (fadeIn(tween(220)) + androidx.compose.animation.scaleIn(
                    initialScale = 0.98f, animationSpec = tween(220)
                )) togetherWith
                    (fadeOut(tween(140)) + androidx.compose.animation.scaleOut(
                        targetScale = 0.99f, animationSpec = tween(140)
                    ))
            },
            label = "searchResults"
        ) { (searchingState, errorState, tabState) ->
            when {
                searchingState -> LoadingBox()
                errorState != null -> ErrorBox(errorState) { doSearch(keyword.trim()) }
                tabState == 0 -> SongResults(songs ?: emptyList(), keyword, onPlaySongs)
                tabState == 1 -> PlaylistResults(playlists ?: emptyList(), keyword, onOpenPlaylist)
                else -> ArtistResults(artists ?: emptyList(), keyword, ::playArtist)
            }
        }
    }
}

/** 搜索历史列表：点击回填并搜索，长按删除单条，右上角一键清空 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchHistoryList(
    history: List<String>,
    onPick: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.search_history),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClear) {
                Text(
                    stringResource(R.string.search_history_clear),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column {
            history.forEach { kw ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onPick(kw) },
                            onLongClick = { onDelete(kw) }
                        )
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        kw,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
@Composable
private fun SongResults(
    songs: List<Song>,
    keyword: String,
    onPlaySongs: (List<Song>, Int) -> Unit
) {
    if (songs.isEmpty()) {
        EmptyHint(stringResource(R.string.no_result, keyword))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(songs, key = { it.id }) { song ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlaySongs(songs, songs.indexOf(song)) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CoverImage(
                    url = song.coverUrl?.let { addParam(it, "100y100") },
                    modifier = Modifier.size(44.dp),
                    cornerRadius = 6
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            song.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (song.isVip) {
                            Spacer(Modifier.width(4.dp))
                            VipBadge()
                        }
                    }
                    Text(
                        "${song.artistNames} · ${song.album?.name ?: ""}",
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
            }
        }
    }
}

@Composable
private fun PlaylistResults(
    playlists: List<Playlist>,
    keyword: String,
    onOpenPlaylist: (Long, String) -> Unit
) {
    if (playlists.isEmpty()) {
        EmptyHint(stringResource(R.string.no_result, keyword))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(playlists, key = { it.id }) { pl ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenPlaylist(pl.id, pl.name) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CoverImage(
                    url = pl.coverUrl?.let { addParam(it, "100y100") },
                    modifier = Modifier.size(44.dp),
                    cornerRadius = 6
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(pl.name, style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${pl.creatorName} · ${stringResource(R.string.track_count, pl.trackCount)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistResults(
    artists: List<NcmApi.SearchArtist>,
    keyword: String,
    onPlayArtist: (Long) -> Unit
) {
    if (artists.isEmpty()) {
        EmptyHint(stringResource(R.string.no_result, keyword))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(artists, key = { it.id }) { ar ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlayArtist(ar.id) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(url = ar.picUrl, modifier = Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ar.name, style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.search_artists),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(Icons.Filled.MusicNote, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp)
    )
}