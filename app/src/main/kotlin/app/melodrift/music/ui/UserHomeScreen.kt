package app.melodrift.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.melodrift.music.R
import app.melodrift.music.net.NcmApi
import app.melodrift.music.net.NcmApi.RecordEntry
import app.melodrift.music.net.NcmApi.UserProfile
import app.melodrift.music.net.Playlist
import app.melodrift.music.net.Song
import app.melodrift.music.net.ioNet

/*
 * 用户主页：从评论点头像 / 首页右上角头像进入。
 *
 * 顶部 = 用户信息（头像/昵称/签名/等级 + 关注/粉丝/听歌），
 * 下方两栏切换：歌单（创建 + 收藏）、听歌排行（最近一周 / 所有时间）。
 * 数据一次拉齐（userDetail + userPlaylists + playRecord），整页异步加载。
 */

private data class UserData(
    val profile: UserProfile,
    val playlists: List<Playlist>,
    val week: List<RecordEntry>,
    val all: List<RecordEntry>
)

@Composable
fun UserHomeScreen(
    uid: Long,
    onBack: () -> Unit,
    onOpenPlaylist: (Long, String) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onOpenUserList: (Boolean) -> Unit
) {
    var tab by remember(uid) { mutableIntStateOf(0) }   // 0 歌单 1 听歌排行

    Column(Modifier.fillMaxSize()) {
        // 顶栏：返回 + 标题（加载到昵称后用昵称）
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
                stringResource(R.string.user_home),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        Box(Modifier.weight(1f)) {
            AsyncContent(
                load = { loadUserData(uid) },
                retryKey = uid
            ) { data ->
                UserBody(
                    data = data,
                    tab = tab,
                    onTabChange = { tab = it },
                    onOpenPlaylist = onOpenPlaylist,
                    onPlaySongs = onPlaySongs,
                    onOpenUserList = onOpenUserList
                )
            }
        }
    }
}

/** 一次性拉齐用户主页数据（profile 失败视为用户不存在/未登录，抛错进 Error 分支） */
private suspend fun loadUserData(uid: Long): UserData {
    val profile = ioNet { NcmApi.userDetail(uid) }
        ?: throw java.io.IOException("load user failed")
    val playlists = ioNet {
        NcmApi.parseUserPlaylists(NcmApi.userPlaylists(uid, 100, 0))
    }.orEmpty()
    val week = ioNet { NcmApi.playRecord(uid, 1) }.orEmpty()
    val all = ioNet { NcmApi.playRecord(uid, 0) }.orEmpty()
    return UserData(profile, playlists, week, all)
}

@Composable
private fun UserBody(
    data: UserData,
    tab: Int,
    onTabChange: (Int) -> Unit,
    onOpenPlaylist: (Long, String) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onOpenUserList: (Boolean) -> Unit
) {
    val profile = data.profile
    LazyColumn(
        contentPadding = PaddingValues(
            start = dimensionResource(R.dimen.page_padding),
            end = dimensionResource(R.dimen.page_padding),
            top = dimensionResource(R.dimen.space_s),
            bottom = dimensionResource(R.dimen.list_bottom_padding)
        )
    ) {
        // ── 头部：头像 + 昵称 + 等级/VIP + 签名 ──
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(
                    url = profile.avatarUrl?.let { addParam(it, "120y120") },
                    modifier = Modifier.size(dimensionResource(R.dimen.user_avatar))
                )
                Spacer(Modifier.width(dimensionResource(R.dimen.space_m)))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            profile.nickname,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (profile.vipType > 0) {
                            Text(
                                stringResource(R.string.vip_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = dimensionResource(R.dimen.space_s))
                            )
                        }
                    }
                    if (profile.level > 0) {
                        Text(
                            stringResource(R.string.user_level_badge, profile.level),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (profile.signature.isNotBlank()) {
                        Text(
                            profile.signature,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.height(dimensionResource(R.dimen.space_m)))
        }

        // ── 统计：关注 / 粉丝 / 听歌（关注、粉丝可点进列表） ──
        item {
            Row(Modifier.fillMaxWidth()) {
                StatCell(
                    stringResource(R.string.user_follows), profile.follows.toLong(),
                    Modifier.weight(1f), onClick = { onOpenUserList(false) }
                )
                StatCell(
                    stringResource(R.string.user_fans), profile.followeds.toLong(),
                    Modifier.weight(1f), onClick = { onOpenUserList(true) }
                )
                StatCell(stringResource(R.string.user_songs), profile.listenSongs.toLong(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(dimensionResource(R.dimen.space_m)))
        }

        // ── 切换：歌单 / 听歌排行 ──
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s))
            ) {
                FilterChip(
                    selected = tab == 0,
                    onClick = { onTabChange(0) },
                    label = {
                        Text(stringResource(R.string.user_tab_playlists, data.playlists.size))
                    }
                )
                FilterChip(
                    selected = tab == 1,
                    onClick = { onTabChange(1) },
                    label = { Text(stringResource(R.string.user_tab_record, data.all.size)) }
                )
            }
            Spacer(Modifier.height(dimensionResource(R.dimen.space_s)))
        }

        when (tab) {
            0 -> {
                if (data.playlists.isEmpty()) {
                    item { EmptyHint(stringResource(R.string.user_no_playlists)) }
                } else {
                    itemsIndexed(data.playlists, key = { _, pl -> pl.id }) { _, pl ->
                        PlaylistRow(pl) { onOpenPlaylist(pl.id, pl.name) }
                    }
                }
            }

            else -> item {
                RecordScopeToggle(data, onPlaySongs)
            }
        }
    }
}

/** 听歌排行子切换（周 / 总）+ 列表，本地状态，故抽成独立 Composable */
@Composable
private fun RecordScopeToggle(
    data: UserData,
    onPlaySongs: (List<Song>, Int) -> Unit
) {
    var week by remember(data.profile.userId) { mutableStateOf(true) }
    val records = if (week) data.week else data.all
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s))
        ) {
FilterChip(
                    selected = week,
                    onClick = { week = true },
                    label = { Text(stringResource(R.string.user_record_week)) }
                )
                FilterChip(
                    selected = !week,
                    onClick = { week = false },
                    label = { Text(stringResource(R.string.user_record_all)) }
                )
        }
        Spacer(Modifier.height(dimensionResource(R.dimen.space_s)))
        if (records.isEmpty()) {
            EmptyHint(stringResource(R.string.user_no_record))
        } else {
            val songs = records.map { it.song }
            records.forEachIndexed { i, e ->
                RecordRow(rank = i + 1, entry = e) { onPlaySongs(songs, i) }
            }
        }
    }
}

@Composable
private fun StatCell(
    label: String,
    value: Long,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Column(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            formatCount(value),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PlaylistRow(pl: Playlist, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = dimensionResource(R.dimen.space_s))
    ) {
        CoverImage(
            url = pl.coverUrl?.let { addParam(it, "100y100") },
            modifier = Modifier.size(dimensionResource(R.dimen.user_cover)),
            cornerRadius = 8
        )
        Spacer(Modifier.width(dimensionResource(R.dimen.space_m)))
        Column(Modifier.weight(1f)) {
            Text(
                pl.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(R.string.playlist_track_count, pl.trackCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RecordRow(rank: Int, entry: RecordEntry, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = dimensionResource(R.dimen.space_s))
    ) {
        Text(
            rank.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (rank <= 3) FontWeight.Bold else FontWeight.Normal,
            color = if (rank <= 3) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(dimensionResource(R.dimen.user_rank_width))
        )
        CoverImage(
            url = entry.song.coverUrl?.let { addParam(it, "100y100") },
            modifier = Modifier.size(dimensionResource(R.dimen.cover_mini)),
            cornerRadius = 8
        )
        Spacer(Modifier.width(dimensionResource(R.dimen.space_m)))
        Column(Modifier.weight(1f)) {
            Text(
                entry.song.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                entry.song.artistNames,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            stringResource(R.string.user_plays_count, formatCount(entry.playCount.toLong())),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = dimensionResource(R.dimen.space_l)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}