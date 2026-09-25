package app.melodrift.music.ui

import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.melodrift.music.R
import app.melodrift.music.net.Comment
import app.melodrift.music.net.CommentSort
import app.melodrift.music.net.NcmApi
import app.melodrift.music.net.Song
import app.melodrift.music.net.ioNet
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/*
 * 歌曲评论面板：播放页三点菜单最上方「评论」进入。
 *
 * 用 ModalBottomSheet 而不是压进返回栈 —— 下滑即回，播放页的歌词位置 / 进度 / 队列都不被打断。
 * 能力：三种排序切换（最热 / 最新 / 推荐，各自独立缓存与翻页）、点赞（乐观更新）、
 * 按条展开追评（不一次全开）、长按复制（全文复制或进入可选文本）、发评论与追评。
 *
 * 布局尺寸一律取 res/values/dimens.xml，不在此写死 dp。
 */

private const val PAGE = 20
private const val FLOOR_PAGE = 10
/** 追评首屏预览条数：不按按钮也能先看到几条热门追评 */
private const val FLOOR_PREVIEW = 3

/** 一条评论的追评区状态机（收起/展开/无追评/加载中） */
private enum class FloorState { IDLE, LOADING, HAS_REPLIES, NO_REPLIES, COLLAPSED }

/** 排序：只有 最热 / 最新 两个 tab（「推荐」不做） */
private val SORT_ORDER = listOf(CommentSort.HOT, CommentSort.LATEST)

@Composable
private fun sortLabel(sort: CommentSort): String = stringResource(
    when (sort) {
        CommentSort.HOT -> R.string.comments_sort_hot
        CommentSort.LATEST -> R.string.comments_sort_latest
        CommentSort.RECOMMEND -> R.string.comments_sort_hot   // 不应出现；兜底
    }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentSheet(
    song: Song,
    onDismiss: () -> Unit,
    onOpenUser: (Long, String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        CommentsContent(song = song, onClose = onDismiss, onOpenUser = onOpenUser)
    }
}

/** 单个排序的列表状态（切 tab 不丢已加载的页） */
private class TabState {
    var total by mutableStateOf(0)
    var items by mutableStateOf<List<Comment>>(emptyList())
    var loading by mutableStateOf(true)
    var loadingMore by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var noMore by mutableStateOf(false)
    var loaded by mutableStateOf(false)

    /** 追评：父评论 id → 已拉到的列表 / 加载态 / 是否还有更多 / 下一页游标 */
    val floors = mutableStateMapOf<Long, MutableList<Comment>>()
    val floorTotal = mutableStateMapOf<Long, Int>()
    val floorState = mutableStateMapOf<Long, FloorState>()
    val floorHasMore = mutableStateMapOf<Long, Boolean>()
    val floorCursor = mutableStateMapOf<Long, Long>()

    fun replaceWhere(matchId: Long, next: (Comment) -> Comment) {
        items = items.map { if (it.id == matchId) next(it) else it }
        floors.values.forEach { list ->
            for (i in list.indices) if (list[i].id == matchId) list[i] = next(list[i])
        }
    }
}

@Composable
private fun CommentsContent(song: Song, onClose: () -> Unit, onOpenUser: (Long, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tabs = remember { SORT_ORDER.associateWith { TabState() } }
    var sort by remember { mutableStateOf(CommentSort.HOT) }
    val tab = tabs.getValue(sort)
    val listState = rememberLazyListState()
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var copyTarget by remember { mutableStateOf<Comment?>(null) }
    var input by remember { mutableStateOf("") }
    var replyTarget by remember { mutableStateOf<Comment?>(null) }
    var sending by mutableStateOf(false)
    val loggedIn = NcmApi.cookie.isNotBlank()

    suspend fun loadFirst(t: TabState) {
        t.loading = true
        t.failed = false
        t.noMore = false
        val page = ioNet {
            NcmApi.comments(song.id, offset = 0, limit = PAGE, sort = sort)
        }
        if (page == null) t.failed = true else {
            t.total = page.total
            t.items = page.comments
            t.noMore = !page.hasMore || page.comments.isEmpty()
        }
        t.loading = false
        t.loaded = true
    }

    // 首次进入 + 切换排序时按需加载
    LaunchedEffect(sort, song.id) {
        if (!tab.loaded) loadFirst(tab)
    }

    suspend fun loadMore() {
        if (tab.loadingMore || tab.noMore || tab.loading || tab.failed) return
        tab.loadingMore = true
        val page = ioNet {
            NcmApi.comments(
                song.id, offset = tab.items.size, limit = PAGE, sort = sort
            )
        }
        if (page == null) {
            tab.noMore = true
        } else {
            val seen = tab.items.mapTo(HashSet()) { it.id }
            tab.items = tab.items + page.comments.filter { seen.add(it.id) }
            tab.noMore = !page.hasMore || page.comments.isEmpty()
        }
        tab.loadingMore = false
    }

    suspend fun toggleLike(target: Comment) {
        val wantLike = !target.liked
        val delta = if (wantLike) 1 else -1
        // 乐观更新：先改本地（含追评列表里的同一条），请求失败再回滚
        tab.replaceWhere(target.id) {
            it.copy(liked = wantLike, likedCount = (it.likedCount + delta).coerceAtLeast(0))
        }
        val ok = ioNet { NcmApi.commentLike(song.id, target.id, wantLike) } ?: false
        if (!ok) {
            tab.replaceWhere(target.id) {
                it.copy(liked = !wantLike, likedCount = (it.likedCount - delta).coerceAtLeast(0))
            }
            Toast.makeText(ctx, R.string.comments_auth_failed, Toast.LENGTH_SHORT).show()
        }
    }

    suspend fun loadFloors(parent: Comment, preview: Boolean = false) {
        if (tab.floorState[parent.id] == FloorState.LOADING) return
        tab.floorState[parent.id] = FloorState.LOADING
        val first = tab.floors[parent.id].isNullOrEmpty()
        val cursor = if (first) -1L else tab.floorCursor[parent.id] ?: -1L
        val limit = if (preview) FLOOR_PREVIEW else FLOOR_PAGE
        val page = ioNet {
            NcmApi.commentFloors(song.id, parent.id, time = cursor, limit = limit)
        }
        if (page != null) {
            val list = tab.floors.getOrPut(parent.id) { mutableListOf() }
            val seen = list.mapTo(HashSet()) { it.id }
            list.addAll(page.comments.filter { seen.add(it.id) })
            // 注意：楼中楼第二页起 totalCount 会返回 0（实测），只在 >0 时更新，否则把真实总数覆盖成 0
            if (page.total > 0) tab.floorTotal[parent.id] = page.total
            tab.floorHasMore[parent.id] = page.hasMore && page.comments.isNotEmpty()
            tab.floorCursor[parent.id] = page.nextTime
            val hasAny = (tab.floorTotal[parent.id] ?: 0) > 0 || list.isNotEmpty()
            tab.floorState[parent.id] =
                if (hasAny) FloorState.HAS_REPLIES else FloorState.NO_REPLIES
        } else if (preview) {
            // 预览失败：回 IDLE —— 列表滚走再滚回来会自动重试，也保留「追评」按钮兜底
            tab.floorState[parent.id] = FloorState.IDLE
        } else {
            // 展开失败：保留已有内容，允许再点「展开追评」
            tab.floorState[parent.id] = FloorState.HAS_REPLIES
        }
    }

    suspend fun submit() {
        val text = input.trim()
        if (text.isEmpty() || sending) return
        sending = true
        val target = replyTarget
        val ok = ioNet {
            if (target == null) NcmApi.addComment(song.id, text)
            else NcmApi.replyComment(song.id, target.id, text)
        } ?: false
        sending = false
        if (ok) {
            input = ""
            replyTarget = null
            Toast.makeText(ctx, R.string.comments_sent, Toast.LENGTH_SHORT).show()
            tabs.values.forEach { it.loaded = false }   // 服务端数据已变，切回哪个 tab 都重拉
            loadFirst(tab)
        } else {
            Toast.makeText(ctx, R.string.comments_send_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // 触底自动翻页
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.visibleItemsInfo.isNotEmpty() &&
                info.visibleItemsInfo.last().index >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, tab.items.size) { if (nearEnd) loadMore() }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 顶栏：标题 + 关闭（不显示歌名，面板本身就在该歌曲上） ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = dimensionResource(R.dimen.page_padding),
                    end = dimensionResource(R.dimen.icon_button_touch_min),
                    top = 4.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.comments),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (tab.loaded && !tab.loading && !tab.failed && tab.total > 0) {
                    Text(
                        stringResource(R.string.comments_count, formatCount(tab.total.toLong())),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, stringResource(R.string.close))
            }
        }

        // ── 排序切换 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dimensionResource(R.dimen.page_padding))
                .padding(top = dimensionResource(R.dimen.space_s)),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                dimensionResource(R.dimen.space_s)
            )
        ) {
            SORT_ORDER.forEach { option ->
                FilterChip(
                    selected = option == sort,
                    onClick = {
                        if (option != sort) {
                            sort = option
                            expandedId = null
                        }
                    },
                    label = { Text(sortLabel(option)) }
                )
            }
        }

        // ── 列表 ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when {
                tab.loading -> CenteredBox { CircularProgressIndicator() }

                tab.failed -> CenteredBox {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.comments_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { scope.launch { loadFirst(tab) } }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }

                tab.items.isEmpty() -> CenteredBox {
                    Text(
                        stringResource(R.string.comments_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = dimensionResource(R.dimen.page_padding),
                        end = dimensionResource(R.dimen.page_padding),
                        top = dimensionResource(R.dimen.space_s),
                        bottom = dimensionResource(R.dimen.list_bottom_padding)
                    )
                ) {
                    items(tab.items, key = { "${sort}_${it.id}" }) { c ->
                        CommentBlock(
                            comment = c,
                            expandedContent = expandedId == c.id,
                            onToggleContent = {
                                expandedId = if (expandedId == c.id) null else c.id
                            },
                            onCopy = { copyTarget = c },
                            floors = tab.floors[c.id],
                            floorTotal = tab.floorTotal[c.id],
                            floorState = tab.floorState[c.id] ?: FloorState.IDLE,
                            floorHasMore = tab.floorHasMore[c.id] == true,
                            onExpandFloors = { scope.launch { loadFloors(c) } },
                            onCollapseFloors = {
                                tab.floors.remove(c.id)
                                // 收起 = 暂时不想看：置 COLLAPSED，滚走再回来不自动重弹，
                                // 但保留「追评」按钮可以手动再展开
                                tab.floorState[c.id] = FloorState.COLLAPSED
                            },
                            onLoadPreview = {
                                if (tab.floors[c.id].isNullOrEmpty() &&
                                    tab.floorState[c.id] != FloorState.COLLAPSED
                                ) {
                                    scope.launch { loadFloors(c, preview = true) }
                                }
                            },
                            onForceLoad = {
                                if (tab.floors[c.id].isNullOrEmpty()) {
                                    tab.floorState[c.id] = FloorState.IDLE
                                    scope.launch { loadFloors(c, preview = true) }
                                }
                            },
                            onLikeComment = { target -> scope.launch { toggleLike(target) } },
                            onReply = { target -> replyTarget = target },
                            onOpenUser = onOpenUser
                        )
                    }
                    item(key = "${sort}_footer") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = dimensionResource(R.dimen.space_l)),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                tab.loadingMore -> CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp), strokeWidth = 2.dp
                                )

                                tab.noMore -> Text(
                                    stringResource(R.string.comments_no_more),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                else -> Text(
                                    stringResource(R.string.comments_load_more),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clickable { scope.launch { loadMore() } }
                                        .padding(dimensionResource(R.dimen.space_s))
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 输入区（未登录不显示） ──
        if (loggedIn) {
            Surface(tonalElevation = 2.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = dimensionResource(R.dimen.page_padding),
                            vertical = dimensionResource(R.dimen.space_s)
                        )
                ) {
                    replyTarget?.let { target ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(
                                    R.string.comments_reply_to,
                                    target.nickname.ifBlank {
                                        stringResource(R.string.comments_anonymous)
                                    }
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { replyTarget = null }) {
                                Text(stringResource(R.string.comments_cancel_reply))
                            }
                        }
                        Spacer(Modifier.height(dimensionResource(R.dimen.space_xs)))
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text(stringResource(R.string.comments_input_hint)) },
                            minLines = 1,
                            maxLines = 4,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(max = dimensionResource(R.dimen.composer_max_height))
                        )
                        Spacer(Modifier.width(dimensionResource(R.dimen.space_s)))
                        IconButton(
                            onClick = { scope.launch { submit() } },
                            enabled = !sending && input.isNotBlank()
                        ) {
                            if (sending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp), strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    stringResource(R.string.comments_send),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    copyTarget?.let { c ->
        CommentCopyDialog(
            comment = c,
            onDismiss = { copyTarget = null }
        )
    }
}

/** 长按评论 → 复制全文 / 选择复制（后者用 SelectionContainer 长按选词） */
@Composable
private fun CommentCopyDialog(comment: Comment, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val name = comment.nickname.ifBlank { stringResource(R.string.comments_anonymous) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.comments_copy_title)) },
        text = {
            Column {
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(dimensionResource(R.dimen.space_s)))
                // 选择复制：允许长按拖动选段
                SelectionContainer {
                    Text(
                        comment.content,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = dimensionResource(R.dimen.dialog_list_max_height))
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(comment.content))
                    Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            ) { Text(stringResource(R.string.comments_copy_all)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}

/** 一条评论 + 它的追评区（默认折叠，点开才请求） */
@Composable
private fun CommentBlock(
    comment: Comment,
    expandedContent: Boolean,
    onToggleContent: () -> Unit,
    onCopy: () -> Unit,
    floors: List<Comment>?,
    floorTotal: Int?,
    floorState: FloorState,
    floorHasMore: Boolean,
    onExpandFloors: () -> Unit,
    onCollapseFloors: () -> Unit,
    onLoadPreview: () -> Unit,
    onForceLoad: () -> Unit,
    onLikeComment: (Comment) -> Unit,
    onReply: (Comment) -> Unit,
    onOpenUser: (Long, String) -> Unit
) {
    // 只有从未加载过（IDLE）才自动拉首屏预览；收起/无追评/加载中都交给状态机
    LaunchedEffect(comment.id) {
        if (floorState == FloorState.IDLE) onLoadPreview()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = dimensionResource(R.dimen.space_m))
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .clip(CircleShape)
                    .clickable { onOpenUser(comment.userId, comment.nickname) }
            ) {
                Avatar(comment.avatarUrl, Modifier.size(dimensionResource(R.dimen.comment_avatar)))
            }
            Spacer(Modifier.width(dimensionResource(R.dimen.space_m)))
            Column(Modifier.weight(1f)) {
                Text(
                    comment.nickname.ifBlank { stringResource(R.string.comments_anonymous) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onOpenUser(comment.userId, comment.nickname) }
                )
                Spacer(Modifier.height(dimensionResource(R.dimen.space_xs)))
                EmojiText(
                    comment.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expandedContent) Int.MAX_VALUE else 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        // 单击展开/收起，长按出复制菜单
                        .combinedClickable(onClick = onToggleContent, onLongClick = onCopy)
                )
                // 长评论：超过阈值默认截断 5 行，并给一个醒目的展开入口
                val longText = comment.content.length > 60
                if (longText) {
                    Text(
                        stringResource(
                            if (expandedContent) R.string.comments_collapse_hint
                            else R.string.comments_expand_hint
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onToggleContent)
                            .padding(top = dimensionResource(R.dimen.space_xs))
                    )
                }
                val meta = commentMeta(comment)
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(dimensionResource(R.dimen.space_xs)))
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }

            }
            Spacer(Modifier.width(dimensionResource(R.dimen.space_s)))
            LikeColumn(
                liked = comment.liked,
                likedCount = comment.likedCount,
                onLike = { onLikeComment(comment) },
                onReply = { onReply(comment) }
            )
        }

        // 追评区状态机：
        //  HAS_REPLIES → 预览框（共N条 + 列表 + 展开/收起）
        //  NO_REPLIES  → 查过确实没有追评，什么都不显示
        //  COLLAPSED   → 已收起：只留「追评」按钮可再展开
        //  IDLE/LOADING→ 未加载/加载中：LOADING 显示轻量提示；IDLE 显示「追评」按钮兜底
        when {
            !floors.isNullOrEmpty() && floorState != FloorState.COLLAPSED -> {
                Surface(
                    shape = RoundedCornerShape(dimensionResource(R.dimen.card_radius)),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = dimensionResource(R.dimen.comment_avatar) +
                                dimensionResource(R.dimen.space_m),
                            top = dimensionResource(R.dimen.space_s)
                        )
                ) {
                    Column(modifier = Modifier.padding(dimensionResource(R.dimen.space_s))) {
                        val total = floorTotal
                        if (total != null && total > 0) {
                            Text(
                                stringResource(R.string.comments_floor_count, total),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        floors.forEach { f ->
                            FloorRow(
                                comment = f,
                                onLike = { onLikeComment(f) },
                                onReply = { onReply(f) },
                                onOpenUser = onOpenUser
                            )
                        }
                        if (floorState == FloorState.LOADING) {
                            Text(
                                stringResource(R.string.comments_floor_loading),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = dimensionResource(R.dimen.space_s))
                            )
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = dimensionResource(R.dimen.space_s)),
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                                    dimensionResource(R.dimen.space_l)
                                )
                            ) {
                                if (floorHasMore) {
                                    Text(
                                        stringResource(R.string.comments_floor_more),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable(onClick = onExpandFloors)
                                    )
                                }
                                Text(
                                    stringResource(R.string.comments_floor_hide),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.clickable(onClick = onCollapseFloors)
                                )
                            }
                        }
                    }
                }
            }

            floorState == FloorState.NO_REPLIES -> Unit   // 确认无追评，不显示

            floorState == FloorState.LOADING -> Text(
                stringResource(R.string.comments_floor_loading),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = dimensionResource(R.dimen.comment_avatar) +
                        dimensionResource(R.dimen.space_m),
                    top = dimensionResource(R.dimen.space_s)
                )
            )

            else -> Text(
                stringResource(
                    if (floorState == FloorState.COLLAPSED) R.string.comments_floor_more
                    else R.string.comments_floor
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onForceLoad)
                    .padding(
                        start = dimensionResource(R.dimen.comment_avatar) +
                            dimensionResource(R.dimen.space_m),
                        top = dimensionResource(R.dimen.space_s)
                    )
            )
        }
    }
}

@Composable
private fun LikeColumn(
    liked: Boolean,
    likedCount: Int,
    onLike: () -> Unit,
    onReply: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(dimensionResource(R.dimen.comment_action_width))
    ) {
        IconButton(onClick = onLike) {
            Icon(
                imageVector = if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = stringResource(R.string.comments_like),
                tint = if (liked) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(dimensionResource(R.dimen.comment_like_icon))
            )
        }
        Text(
            formatCount(likedCount.toLong()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.comments_reply),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable(onClick = onReply)
                .padding(vertical = dimensionResource(R.dimen.space_xs))
        )
    }
}

/** 追评行：比主评论小一档，本身不再展开 */
@Composable
private fun FloorRow(
    comment: Comment,
    onLike: () -> Unit,
    onReply: () -> Unit,
    onOpenUser: (Long, String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = dimensionResource(R.dimen.space_s)),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .clip(CircleShape)
                .clickable { onOpenUser(comment.userId, comment.nickname) }
        ) {
            Avatar(comment.avatarUrl, Modifier.size(dimensionResource(R.dimen.comment_floor_avatar)))
        }
        Spacer(Modifier.width(dimensionResource(R.dimen.space_s)))
        Column(Modifier.weight(1f)) {
            Text(
                comment.nickname.ifBlank { stringResource(R.string.comments_anonymous) },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onOpenUser(comment.userId, comment.nickname) }
            )
            EmojiText(
                comment.content,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp)
            )
            val meta = commentMeta(comment)
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
        IconButton(onClick = onLike) {
            Icon(
                imageVector = if (comment.liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = stringResource(R.string.comments_like),
                tint = if (comment.liked) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(dimensionResource(R.dimen.comment_like_icon))
            )
        }
        TextButton(
            onClick = onReply,
            modifier = Modifier.width(40.dp)
        ) {
            Text(
                stringResource(R.string.comments_reply),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(dimensionResource(R.dimen.comment_placeholder_height)),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** 「时间 · IP」，IP 为空只显示时间 */
@Composable
private fun commentMeta(comment: Comment): String {
    val time = relativeCommentTime(comment.timeMs)
    return when {
        time.isBlank() -> comment.ipLocation
        comment.ipLocation.isBlank() -> time
        else -> "$time · ${comment.ipLocation}"
    }
}

@Composable
private fun relativeCommentTime(timeMs: Long): String {
    if (timeMs <= 0L) return ""
    val delta = System.currentTimeMillis() - timeMs
    return when {
        delta < 60_000L -> stringResource(R.string.comments_time_just_now)
        delta < 3_600_000L ->
            stringResource(R.string.comments_time_minutes, (delta / 60_000L).toInt())

        delta < 86_400_000L ->
            stringResource(R.string.comments_time_hours, (delta / 3_600_000L).toInt())

        delta < 7 * 86_400_000L ->
            stringResource(R.string.comments_time_days, (delta / 86_400_000L).toInt())

        else -> remember(timeMs) {
            DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(timeMs))
        }
    }
}
