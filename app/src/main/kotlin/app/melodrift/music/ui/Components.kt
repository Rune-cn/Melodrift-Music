package app.melodrift.music.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.melodrift.music.R
import app.melodrift.music.data.SavedPlaylists
import app.melodrift.music.data.SavedSongsCache
import app.melodrift.music.net.Json
import app.melodrift.music.net.NcmApi
import app.melodrift.music.net.Playlist
import coil.compose.AsyncImage
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 音质档位显示名（资源驱动，随语言切换） */
@Composable
fun qualityLabel(level: String): String = when (level) {
    "standard" -> stringResource(R.string.quality_standard)
    "exhigh" -> stringResource(R.string.quality_exhigh)
    "lossless" -> stringResource(R.string.quality_lossless)
    "hires" -> stringResource(R.string.quality_hires)
    else -> level
}

/** 协议全文弹窗（用户协议 / 隐私政策 / 免责声明），文本可滚动 */
@Composable
fun LegalContentDialog(kind: String, onDismiss: () -> Unit) {
    val (title, text) = when (kind) {
        "terms" -> stringResource(R.string.terms_title) to stringResource(R.string.terms_text)
        "privacy" -> stringResource(R.string.privacy_title) to stringResource(R.string.privacy_text)
        else -> stringResource(R.string.disclaimer_title) to stringResource(R.string.disclaimer_text)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm))
            }
        }
    )
}

/** 歌单条目（收藏弹窗用） */
/** 歌单条目（收藏弹窗用） */
private data class PlaylistEntry(
    val id: Long,
    val name: String,
    val cover: String?,
    val isLikedList: Boolean,  // 是否「我喜欢的音乐」
    val saved: Boolean,        // 当前勾选状态
    val original: Boolean      // 加载时的真实收藏状态（用于对比）
)

/**
 * 「收藏到指定歌单」弹窗（多选批量）：
 * 列出自己创建的歌单（含「我喜欢的音乐」），勾选 = 收藏，取消勾选 = 移出，
 * 点「保存」统一生效；操作后回调 [onChanged]（供外部刷新爱心状态）。
 */
@Composable
fun AddToPlaylistDialog(
    songId: Long,
    onChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember(songId) { mutableStateOf<List<PlaylistEntry>?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(songId) {
        try {
            val acc = withContext(Dispatchers.IO) { NcmApi.account() }
            val arr = withContext(Dispatchers.IO) { NcmApi.userPlaylists(acc.userId, 100, 0) }
            // 自己创建的歌单（subscribed=false），含「我喜欢的音乐」
            val own = mutableListOf<org.json.JSONObject>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!o.optBoolean("subscribed", false)) own.add(o)
            }
            val likedPid = own.firstOrNull {
                it.optInt("specialType", 0) == 5 || it.optString("name", "").contains("喜欢") ||
                    it.optString("name", "").contains("Liked")
            }?.optLong("id", 0L)

            // 以服务器为准：并行查询每个歌单详情，判断歌曲是否真实在其中。
            // 这样即使在官方 App / 网页收藏过的歌单也会正确勾选。
            val contains = withContext(Dispatchers.IO) {
                coroutineScope {
                    own.map { o ->
                        val pid = o.optLong("id", 0L)
                        async {
                            try {
                                val d = NcmApi.playlistDetail(pid)
                                d.songs.any { it.id == songId }
                            } catch (_: Exception) {
                                false
                            }
                        }
                    }.awaitAll()
                }
            }
            // 本地记录作为补充（本应用刚收藏过、服务器未刷新时兜底）
            val local = SavedPlaylists.playlistsFor(ctx, songId)
            entries = own.mapIndexed { i, o ->
                val id = o.optLong("id", 0L)
                val isLiked = id == likedPid
                val real = contains.getOrElse(i) { false } || id in local
                PlaylistEntry(
                    id = id,
                    name = o.optString("name", ""),
                    cover = Json.strOrNull(o, "coverImgUrl") ?: Json.strOrNull(o, "picUrl"),
                    isLikedList = isLiked,
                    saved = real,
                    original = real
                )
            }
        } catch (_: Exception) {
            entries = emptyList()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.add_to_playlist)) },
        text = {
            val list = entries
            when {
                list == null -> Box(
                    Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
                list.isEmpty() -> Text(
                    stringResource(R.string.no_playlists),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> Column {
                    Text(
                        stringResource(R.string.playlist_picker_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    // 快捷操作：全选 / 清空（清空后保存 = 取消全部收藏）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            enabled = !busy,
                            onClick = { entries = list.map { it.copy(saved = true) } }
                        ) { Text(stringResource(R.string.select_all)) }
                        TextButton(
                            enabled = !busy,
                            onClick = { entries = list.map { it.copy(saved = false) } }
                        ) { Text(stringResource(R.string.clear_all)) }
                    }
                    LazyColumn(Modifier.height(280.dp)) {
                        items(list, key = { it.id }) { e ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !busy) {
                                        entries = list.map {
                                            if (it.id == e.id) it.copy(saved = !it.saved) else it
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CoverImage(
                                    url = e.cover?.let { addParam(it, "100y100") },
                                    modifier = Modifier.size(44.dp),
                                    cornerRadius = 8
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        e.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        if (e.isLikedList) stringResource(R.string.liked_playlist_hint)
                                        else stringResource(R.string.my_playlist_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Checkbox(
                                    checked = e.saved,
                                    onCheckedChange = {
                                        entries = list.map {
                                            if (it.id == e.id) it.copy(saved = !it.saved) else it
                                        }
                                    }
                                )
                            }
                        }
                    }
                    // 选中计数
                    val selectedCount = list.count { it.saved }
                    Text(
                        stringResource(R.string.playlists_selected, selectedCount, list.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (entries?.any { it.saved != it.original } == true),
                onClick = {
                    val list = entries ?: return@TextButton
                    busy = true
                    scope.launch {
                        // 计算需要变更的歌单（勾选 vs 原始）
                        val toAdd = list.filter { !it.original && it.saved }
                        val toRemove = list.filter { it.original && !it.saved }
                        var okCount = 0
                        val total = toAdd.size + toRemove.size
                        // 逐项执行
                        val ops = buildList {
                            toAdd.forEach { add(Pair(it, true)) }
                            toRemove.forEach { add(Pair(it, false)) }
                        }
                        for ((e, target) in ops) {
                            val ok = try {
                                withContext(Dispatchers.IO) {
                                    NcmApi.manipulateTracks(
                                        e.id, listOf(songId), if (target) "add" else "del"
                                    )
                                }
                            } catch (_: Exception) {
                                false
                            }
                            if (ok) {
                                okCount++
                                if (!e.isLikedList) {
                                    if (target) SavedPlaylists.add(ctx, songId, e.id)
                                    else SavedPlaylists.remove(ctx, songId, e.id)
                                }
                            }
                        }
                        busy = false
                        if (total == 0) {
                            onDismiss()
                        } else if (okCount > 0) {
                            // 服务器数据已变化：失效已收藏缓存
                            SavedSongsCache.invalidate()
                            onDismiss()
                            android.widget.Toast.makeText(
                                ctx,
                                ctx.getString(R.string.playlists_saved, okCount),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            onChanged()
                        } else {
                            android.widget.Toast.makeText(
                                ctx, R.string.op_failed, android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

// ═══════════════════════════════════════════════════════════
// 异步加载状态
// ═══════════════════════════════════════════════════════════

sealed class LoadState<out T> {
    data object Loading : LoadState<Nothing>()
    data class Success<T>(val data: T) : LoadState<T>()
    data class Error(val message: String) : LoadState<Nothing>()
}

/**
 * 全局异步内容内存缓存：按缓存键保存最近一次成功结果。
 * 页面切换/重复进入时直接秒开旧数据，避免频繁请求（TTL 内不重新拉）。
 */
private object AsyncCache {
    private data class Entry(val value: Any, val at: Long)
    private val map = java.util.concurrent.ConcurrentHashMap<Any, Entry>()

    /** 默认 TTL：5 分钟 */
    const val TTL_MS = 5 * 60_000L

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: Any): T? {
        val e = map[key] ?: return null
        if (System.currentTimeMillis() - e.at > TTL_MS) {
            map.remove(key)
            return null
        }
        return e.value as T
    }

    fun <T> put(key: Any, value: T) {
        map[key] = Entry(value as Any, System.currentTimeMillis())
    }

    /** 清除指定键（数据可能过时的场景：新建/删除歌单、cookie 变更等） */
    fun invalidate(key: Any) {
        map.remove(key)
    }
}

/** 清除某键的异步内容缓存（数据变更场景用） */
fun invalidateAsyncCache(key: Any) {
    AsyncCache.invalidate(key)
}

/**
 * 通用异步内容容器：
 * 传入加载协程 [load]，自动管理 Loading / Error(带重试) / Success 三态。
 *
 * 传了 [cacheKey] 时启用内存缓存（默认 5 分钟 TTL）：重复进入秒开旧数据，
 * 不重新发请求；改变 [cacheKey]（如下拉刷新）会强制重新加载。
 */
@Composable
fun <T> AsyncContent(
    load: suspend () -> T,
    retryKey: Any? = null,
    cacheKey: Any? = null,
    loadingMessage: String = "Loading…",
    onRender: @Composable (T) -> Unit
) {
    var state by androidx.compose.runtime.remember(retryKey) {
        androidx.compose.runtime.mutableStateOf<LoadState<T>>(LoadState.Loading)
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val effectiveKey = cacheKey ?: retryKey

    androidx.compose.runtime.LaunchedEffect(retryKey) {
        // 命中缓存且未过期：秒开，不发请求
        if (effectiveKey != null) {
            val cached = AsyncCache.get<T>(effectiveKey)
            if (cached != null) {
                state = LoadState.Success(cached)
                return@LaunchedEffect
            }
        }
        state = LoadState.Loading
        state = try {
            val data = withContext(Dispatchers.IO) { load() }
            if (effectiveKey != null) AsyncCache.put(effectiveKey, data)
            LoadState.Success(data)
        } catch (e: Exception) {
            LoadState.Error(e.message ?: "error")
        }
    }

    AnimatedContent(
        targetState = state,
        transitionSpec = {
            (fadeIn(tween(220)) togetherWith fadeOut(tween(120)))
        },
        label = "async"
    ) { s ->
        when (s) {
            is LoadState.Loading -> LoadingBox(loadingMessage)
            is LoadState.Error -> ErrorBox(s.message) {
                scope.launch {
                    state = LoadState.Loading
                    state = try {
                        val data = withContext(Dispatchers.IO) { load() }
                        if (effectiveKey != null) AsyncCache.put(effectiveKey, data)
                        LoadState.Success(data)
                    } catch (e: Exception) {
                        LoadState.Error(e.message ?: "error")
                    }
                }
            }
            is LoadState.Success -> onRender(s.data)
        }
    }
}

@Composable
fun LoadingBox(message: String = "Loading…") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(Modifier.height(12.dp))
            IconButton(onClick = onRetry) {
                Icon(Icons.Filled.Refresh, contentDescription = "Retry")
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 封面图（Coil + 兜底音符图标）
// ═══════════════════════════════════════════════════════════

@Composable
fun CoverImage(
    url: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Int = 8
) {
    val shape = RoundedCornerShape(cornerRadius.dp)
    if (url.isNullOrBlank()) {
        Box(
            modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
        }
    } else {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape)
        )
    }
}

// ═══════════════════════════════════════════════════════════
// 通用小节标题
// ═══════════════════════════════════════════════════════════

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(vertical = 8.dp)
    )
}

// ═══════════════════════════════════════════════════════════
// 格式工具
// ═══════════════════════════════════════════════════════════

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format(Locale.US, "%d:%02d", m, s)
}

fun formatCount(n: Long): String = when {
    n >= 100_000_000 -> String.format(Locale.US, "%.1f亿", n / 100_000_000.0)
    n >= 10_000 -> String.format(Locale.US, "%.1f万", n / 10_000.0)
    else -> n.toString()
}

/** 圆形头像 */
@Composable
fun Avatar(url: String?, modifier: Modifier = Modifier.size(40.dp)) {
    if (url.isNullOrBlank()) {
        Box(
            modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    } else {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(CircleShape)
        )
    }
}

/**
 * Android 9 及以下写公共「下载」目录需要 `WRITE_EXTERNAL_STORAGE`
 * （API 29+ 走 MediaStore，无需该权限，直接放行）。
 *
 * 返回一个包装函数：已授权 / 无需授权时立即执行；否则先弹系统授权框，
 * 用户同意后自动补跑原动作，拒绝则提示一次原因（无需重新点下载）。
 */
@Composable
fun rememberStoragePermissionGuard(): (action: () -> Unit) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pending
        pending = null
        if (granted) action?.invoke()
        else Toast.makeText(ctx, R.string.download_need_permission, Toast.LENGTH_SHORT).show()
    }
    return { action ->
        val noPermissionNeeded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val granted = noPermissionNeeded || ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pending = action
            launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}


/** 项目仓库地址（关于页 GitHub 入口） */
const val GITHUB_URL = "https://github.com/Rune-cn/Melodrift-Music"

/** 用系统浏览器打开外链；没有可用浏览器时提示而不是抛异常崩掉 */
fun openExternalUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, R.string.no_browser, Toast.LENGTH_SHORT).show()
    }
}
