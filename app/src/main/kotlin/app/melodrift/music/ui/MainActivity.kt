package app.melodrift.music.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.melodrift.music.R
import app.melodrift.music.data.SettingsData
import app.melodrift.music.util.CrashLog
import app.melodrift.music.data.SettingsRepository
import app.melodrift.music.net.NcmApi
import app.melodrift.music.net.Song
import app.melodrift.music.player.PlayerController
import app.melodrift.music.ui.theme.MelodriftMusicTheme
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var systemBase: Context? = null

    override fun attachBaseContext(newBase: Context) {
        systemBase = newBase
        super.attachBaseContext(applyLocaleContext(newBase, readLanguage(newBase)))
    }

    private fun readLanguage(base: Context): String =
        base.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("language", "system") ?: "system"

    private fun applyLocaleContext(base: Context, lang: String): Context {
        if (lang == "system") return systemBase ?: base
        val locale = if (lang == "zh") Locale.forLanguageTag("zh-CN") else Locale.ENGLISH
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        enableEdgeToEdge()
        PlayerController.init(applicationContext)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
            )
        }

        setContent {
            val repo = remember { SettingsRepository(this@MainActivity) }
            val initial = remember { repo.load() }
            var settings by remember { mutableStateOf(initial) }

            LaunchedEffect(settings) {
                repo.save { settings }
            }

            LaunchedEffect(settings.cookie) {
                NcmApi.cookie = settings.cookie
            }
            // 播放相关设置（默认音质 / 淡入淡出 / 允许混音 / 恢复播放位置）实时生效
            LaunchedEffect(
                settings.defaultQuality,
                settings.crossfadeEnabled,
                settings.crossfadeSeconds,
                settings.allowMixedAudio,
                settings.resumePlaybackPosition
            ) {
                PlayerController.applyPlaybackSettings(
                    quality = settings.defaultQuality,
                    crossfadeEnabled = settings.crossfadeEnabled,
                    crossfadeSeconds = settings.crossfadeSeconds,
                    mixedAudio = settings.allowMixedAudio,
                    resumePlaybackPosition = settings.resumePlaybackPosition
                )
            }
            // 外观设置：进度条样式 / 歌词渐变 / 迷你条样式
            LaunchedEffect(settings.progressStyle, settings.lyricsFade) {
                PlayerController.progressStyle = settings.progressStyle
                PlayerController.lyricsFade = settings.lyricsFade
            }

            // 首次启动：弹出 用户协议 / 隐私政策 / 免责声明
            var showLegalWelcome by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                val sp = this@MainActivity.getSharedPreferences("settings", Context.MODE_PRIVATE)
                if (!sp.getBoolean("legal_agreed", false)) {
                    showLegalWelcome = true
                }
            }
            if (showLegalWelcome) {
                LegalWelcomeDialog(
                    onAgree = {
                        this@MainActivity.getSharedPreferences("settings", Context.MODE_PRIVATE)
                            .edit().putBoolean("legal_agreed", true).apply()
                        showLegalWelcome = false
                    }
                )
            }

            val localizedContext = remember(settings.language) {
                applyLocaleContext(systemBase ?: this@MainActivity, settings.language)
            }
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                // localizedContext 是 createConfigurationContext 的产物，不是 Activity；
                // rememberLauncherForActivityResult 只认 LocalActivityResultRegistryOwner，
                // 不显式提供就抛 "No ActivityResultRegistryOwner was provided"
                // —— 这就是点进「设置 → 数据」闪退的原因（Android 9 下载授权弹窗同理）。
                LocalActivityResultRegistryOwner provides this@MainActivity
            ) {
                MelodriftMusicTheme(settings = settings) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MelodriftApp(
                            settings = settings,
                            onSettingsChange = { settings = it }
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// 导航（返回栈）
// ═══════════════════════════════════════════════════════════════════

private sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Library : Screen
    data object Settings : Screen
    data class Playlist(val id: Long, val name: String) : Screen
    data class SongList(val title: String, val songs: List<Song>) : Screen
    data object Player : Screen
}

private data class TabItem(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

@Composable
private fun MelodriftApp(
    settings: SettingsData,
    onSettingsChange: (SettingsData) -> Unit
) {
    // 返回栈：back 回到上一步，push 去重避免重复页面
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    val current = stack.last()

    val push: (Screen) -> Unit = { s -> if (stack.last() != s) stack = stack + s }
    val pop: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }

    // 播放页拉起偏移量（0=全屏显示，screenHeightPx=完全沉底）：
    // 点击迷你条 / 上拉手势 / 播放页下滑收起，三处共用同一个值，动画连贯
    val playerOffsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val screenHeightPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    // 上拉预览状态：true = 播放页覆盖层以预览形式跟手升起（不进页面栈）；
    // 松手过阈值 → push 正式进入；不足 → 回弹隐藏，始终停留在当前页面
    var previewingPlayer by remember { mutableStateOf(false) }

    /** 关闭播放页覆盖层：预览态 → 滑出屏幕后取消预览（不进栈不出栈）；正式态 → pop */
    fun closePlayerOverlay() {
        if (previewingPlayer) {
            scope.launch {
                playerOffsetY.animateTo(screenHeightPx, tween(160))
                previewingPlayer = false
                playerOffsetY.snapTo(0f)
            }
        } else if (current is Screen.Player) {
            pop()
        }
    }

    // 系统返回键：预览中 → 滑出取消预览；否则正常回退
    BackHandler(enabled = stack.size > 1 || previewingPlayer) {
        if (previewingPlayer) {
            closePlayerOverlay()
        } else {
            pop()
        }
    }

    // 播放页状态栏：开关关闭 = 进入播放页隐藏状态栏，离开播放页 / 回到前台自动恢复
    val decorView = LocalView.current
    val playerBarHidden = !settings.playerStatusBar && current is Screen.Player
    LaunchedEffect(playerBarHidden) {
        applyStatusBar(decorView, playerBarHidden)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, playerBarHidden) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) applyStatusBar(decorView, playerBarHidden)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val tabs = remember {
        listOf(
            TabItem("home", Icons.Filled.Home, Icons.Outlined.Home),
            TabItem("library", Icons.Filled.MusicNote, Icons.Outlined.MusicNote)
        )
    }

    val showTabs = current is Screen.Home || current is Screen.Library

    /** 直接显示播放页（点歌/按钮等非迷你条路径）：复位偏移，防止上次拖动残留 */
    fun showPlayer() {
        if (current is Screen.Player) return
        previewingPlayer = false
        scope.launch { playerOffsetY.snapTo(0f) }
        push(Screen.Player)
    }

    /** 上拉预览过阈值后确认进入：页面已停在顶部，直接 push（不再沉底重升） */
    fun commitPlayerPreview() {
        if (current is Screen.Player) return
        previewingPlayer = false
        push(Screen.Player)
    }

    val onPlaySongs: (List<Song>, Int) -> Unit = { songs, index ->
        PlayerController.playQueue(songs, index)
        showPlayer()
    }

    /** 从迷你条进入播放页：先沉底再升起（点击迷你条路径） */
    fun openPlayerFromBar() {
        if (current is Screen.Player) return
        previewingPlayer = false
        scope.launch {
            // 先沉底，再组合播放页（组合时 offsetY 已是底部，升起动画完整可见）
            playerOffsetY.snapTo(screenHeightPx)
            push(Screen.Player)
            playerOffsetY.animateTo(0f, tween(300))
        }
    }

    Scaffold(
        bottomBar = {
            Column {
                // 迷你播放条：所有非播放页常驻（无歌也显示占位）；播放页打开时快速落下。
                // 点击进入播放页；上拉 → 预览式升起（过阈值才真正进入，不足回弹）
                AnimatedVisibility(
                    visible = current !is Screen.Player,
                    enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
                    exit = slideOutVertically(tween(90)) { it } + fadeOut(tween(90))
                ) {
                    MiniPlayerBar(
                        onOpenPlayer = { openPlayerFromBar() },
                        onCommitPreview = { commitPlayerPreview() },
                        style = settings.miniBarStyle,
                        playerOffsetY = playerOffsetY,
                        scope = scope,
                        screenHeightPx = screenHeightPx,
                        active = current !is Screen.Player,
                        onPreviewingChange = { previewingPlayer = it }
                    )
                }
                // 底部导航（仅根页面；播放页打开时快速滑出）
                AnimatedVisibility(
                    visible = showTabs,
                    enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
                    exit = slideOutVertically(tween(90)) { it } + fadeOut(tween(90))
                ) {
                    NavigationBar {
                        tabs.forEachIndexed { index, item ->
                            val screens = listOf(Screen.Home, Screen.Library)
                            val screenOf = screens[index]
                            val selected = current == screenOf
                            val label = when (screenOf) {
                                is Screen.Home -> stringResource(R.string.nav_home)
                                else -> stringResource(R.string.nav_library)
                            }
                            NavigationBarItem(
                                selected = selected,
                                onClick = { if (current != screenOf) stack = listOf(screenOf) },
                                icon = {
                                    Icon(
                                        if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            // 播放页打开时，底层渲染「进入播放页之前的页面」，
            // 播放页作为覆盖层叠在上面 → 下滑即可预览/露出主页面（抽屉效果）
            val baseScreen = if (current is Screen.Player) {
                stack.getOrNull(stack.size - 2) ?: Screen.Home
            } else {
                current
            }
            // 内容层（页面主体）
            Box(
                Modifier.fillMaxSize()
            ) {
            // 纯交叉淡化过渡：无位移无缩放，最自然不"别扭"
            Crossfade(
                targetState = baseScreen,
                animationSpec = tween(180),
                label = "screen"
            ) { screen ->
                when (screen) {
                    is Screen.Home -> HomeScreen(
                        cookie = settings.cookie,
                        onOpenPlaylist = { id, name -> push(Screen.Playlist(id, name)) },
                        onOpenSongList = { title, songs -> push(Screen.SongList(title, songs)) },
                        onPlaySongs = onPlaySongs,
                        onOpenPlayer = { showPlayer() },
                        onOpenSearch = { push(Screen.Search) },
                        onOpenSettingsPage = { push(Screen.Settings) }
                    )
                    is Screen.Search -> SearchScreen(
                        cookie = settings.cookie,
                        onPlaySongs = onPlaySongs,
                        onOpenPlaylist = { id, name -> push(Screen.Playlist(id, name)) }
                    )
                    is Screen.Settings -> SettingsScreen(
                        settings = settings,
                        onChange = onSettingsChange,
                        onBack = { pop() }
                    )
                    is Screen.Library -> LibraryScreen(
                        cookie = settings.cookie,
                        onOpenPlaylist = { id, name -> push(Screen.Playlist(id, name)) },
                        onPlaySongs = onPlaySongs
                    )
                    is Screen.Playlist -> SongListScreen(
                        title = screen.name,
                        playlistId = screen.id,
                        preloadedSongs = null,
                        onBack = pop,
                        onDeleted = pop,
                        onPlaySongs = onPlaySongs
                    )
                    is Screen.SongList -> SongListScreen(
                        title = screen.title,
                        playlistId = null,
                        preloadedSongs = screen.songs,
                        onBack = pop,
                        onPlaySongs = onPlaySongs
                    )
                    is Screen.Player -> PlayerScreen(onBack = pop)
                }
            }
            }
            // 播放页覆盖层：位置由共享 playerOffsetY 驱动。
            // 显示条件 = 已进入播放页（current is Screen.Player）或上拉预览中（previewingPlayer）。
            // 预览时仅跟手显示、不进页面栈；AnimatedVisibility 只做透明过渡，位移交给 offsetY。
            AnimatedVisibility(
                visible = current is Screen.Player || previewingPlayer,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(120))
            ) {
                PlayerOverlay(
                    onBack = { closePlayerOverlay() },
                    playerOffsetY = playerOffsetY,
                    scope = scope,
                    screenHeightPx = screenHeightPx
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// 迷你播放条
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun MiniPlayerBar(
    onOpenPlayer: () -> Unit,
    onCommitPreview: () -> Unit = {},
    style: String = "rounded",
    playerOffsetY: Animatable<Float, AnimationVector1D>,
    scope: CoroutineScope,
    screenHeightPx: Float,
    active: Boolean = true,
    onPreviewingChange: (Boolean) -> Unit = {}
) {
    val song = PlayerController.current
    // 是否正在上拉（预览播放页，未正式进入）
    var draggingUp by remember { mutableStateOf(false) }
    // 样式：方形 / 圆角
    val shape = if (style == "square") RoundedCornerShape(0.dp) else RoundedCornerShape(18.dp)
    // 圆角悬浮、方形贴底
    val pad = if (style == "square") 0.dp else 6.dp

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = shape,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (style == "square") 0.dp else 10.dp, vertical = pad)
            // iOS 式上拉：播放页只做"预览式"跟手升起——不进页面栈；
            // 松手过阈值（或快速上扫）才真正进入，不足则回弹并停留在当前页面。
            //
            // 三处修复"有时候上拉进不去"：
            // ① pointerInput 必须排在 clickable **之前**：修饰符链里靠前的先拿到事件，
            //    原来 clickable 在前，小幅拖拽会被它当成点击吃掉；
            // ② 位移改为累加**手势自身的增量**（dragAccum），不再读 playerOffsetY.value
            //    —— 原来每个事件都 scope.launch { snapTo(value + dy) }，读到的常是上一帧的旧值、
            //    并发 launch 还会乱序，松手时 offsetY 可能远落后于手指，于是判"没拉够"而回弹；
            // ③ 阈值从"屏高 20%"降到 80dp，并加**速度判定**：快速轻扫即使位移不够也放行。
            .pointerInput(active, song?.id) {
                if (!active || song == null) return@pointerInput
                val dragRequiredPx = 80.dp.toPx()
                val flingUpPxPerSec = (-900).dp.toPx()
                var dragAccum = 0f
                // 本版本 detectVerticalDragGestures 的 onDragEnd 不给速度，
                // 所以自己按最后两次事件估算瞬时速度（向上为负 px/s）
                var lastEventNanos = 0L
                var upSpeedPxPerSec = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        draggingUp = true
                        dragAccum = 0f
                        upSpeedPxPerSec = 0f
                        lastEventNanos = System.nanoTime()
                        // 播放页覆盖层先沉底，跟随手指升起（仅预览，不 push）
                        scope.launch { playerOffsetY.snapTo(screenHeightPx) }
                        onPreviewingChange(true)
                    },
                    onVerticalDrag = { change, dragAmount ->
                        if (!draggingUp) return@detectVerticalDragGestures
                        change.consume()
                        dragAccum += dragAmount
                        val now = System.nanoTime()
                        val dt = (now - lastEventNanos) / 1_000_000_000f
                        if (dt > 0.0001f) {
                            upSpeedPxPerSec = dragAmount / dt
                            lastEventNanos = now
                        }
                        val target = (screenHeightPx - dragAccum).coerceIn(0f, screenHeightPx)
                        scope.launch { playerOffsetY.snapTo(target) }
                    },
                    onDragEnd = {
                        if (draggingUp) {
                            draggingUp = false
                            val enoughDistance = dragAccum >= dragRequiredPx
                            val flingUp = upSpeedPxPerSec < flingUpPxPerSec
                            if (enoughDistance || flingUp) {
                                // 到位：立即确认进入（push 同步生效 → 迷你条/底栏马上开始落下），
                                // 播放页归位动画与 push 并行，不阻塞
                                onPreviewingChange(false)
                                onCommitPreview()
                                scope.launch {
                                    playerOffsetY.animateTo(
                                        0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                            } else {
                                // 距离与速度都不够 → 回弹复位，停留在当前页面
                                scope.launch {
                                    playerOffsetY.animateTo(screenHeightPx, tween(160))
                                    onPreviewingChange(false)
                                }
                            }
                        }
                    },
                    onDragCancel = {
                        if (draggingUp) {
                            draggingUp = false
                            dragAccum = 0f
                            upSpeedPxPerSec = 0f
                            scope.launch {
                                playerOffsetY.animateTo(screenHeightPx, tween(160))
                                onPreviewingChange(false)
                            }
                        }
                    }
                )
            }
            // 点击打开播放页：放在 pointerInput **之后**，让上拉手势先拿到事件
            .clickable(enabled = active && song != null, onClick = onOpenPlayer)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (song == null) {
                // 无歌状态：迷你条常驻占位（点击/上拉无效）
                Box(
                    modifier = Modifier.size(46.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.mini_bar_idle_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        stringResource(R.string.mini_bar_idle_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(12.dp))
            } else {
                SpinningCover()
                Spacer(Modifier.width(12.dp))
                // 切歌：歌名/歌手 交叉淡化过渡（weight 在 AnimatedContent 上，撑满剩余宽度，
                // 把播放/暂停按钮推到最右边）
                AnimatedContent(
                    targetState = song.id,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        (fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 8 }) togetherWith
                            (fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 8 })
                    },
                    label = "miniSong"
                ) { _ ->
                    Column {
                        Text(
                            song.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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
                // 播放/暂停：状态切换交叉淡化
                AnimatedContent(
                    targetState = PlayerController.isLoading to PlayerController.isPlaying,
                    transitionSpec = {
                        (fadeIn(tween(160)) + scaleIn(initialScale = 0.6f, animationSpec = tween(160))) togetherWith
                            (fadeOut(tween(120)) + scaleOut(targetScale = 0.7f, animationSpec = tween(120)))
                    },
                    label = "miniBtn"
                ) { (loading, playing) ->
                    IconButton(onClick = { PlayerController.toggle() }) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (playing) stringResource(R.string.pause)
                                else stringResource(R.string.play)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// 播放队列弹窗
// ═══════════════════════════════════════════════════════════════════

/**
 * 首次启动的协议弹窗：用户协议 / 隐私政策 / 免责声明 三个条目，
 * 点击条目查看全文，同意后继续使用。样式跟随主题（深浅色均正常显示）。
 */
@Composable
private fun LegalWelcomeDialog(onAgree: () -> Unit) {
    var current by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = {},
        title = {
            Column {
                Text(
                    stringResource(R.string.legal_welcome_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.legal_welcome_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column {
                LegalEntry(stringResource(R.string.terms_title)) { current = "terms" }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                LegalEntry(stringResource(R.string.privacy_title)) { current = "privacy" }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                LegalEntry(stringResource(R.string.disclaimer_title)) { current = "disclaimer" }
            }
        },
        confirmButton = {
            Button(
                onClick = onAgree,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.agree_and_continue))
            }
        }
    )

    // 二级弹窗：协议全文
    val kind = current
    if (kind != null) {
        LegalContentDialog(kind = kind, onDismiss = { current = null })
    }
}

@Composable
private fun LegalEntry(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 播放队列弹窗：长按拖拽排序、点击跳播、右侧删除 */
/**
 * 播放页覆盖层：全屏叠在底层页面之上。
 * 位置由共享 [playerOffsetY] 驱动（0=全屏；screenHeightPx=沉底）；
 * 进入：点击/上拉迷你条把 offsetY 从底部升到 0（见 [openPlayerFromBar] / MiniPlayerBar 手势）。
 * 退出：下滑手势跟手平移（露出底层主页面的预览），松手：
 *  - 位移超过阈值 → 滑出屏幕后 [onBack]
 *  - 否则回弹
 */
@Composable
private fun PlayerOverlay(
    onBack: () -> Unit,
    playerOffsetY: Animatable<Float, AnimationVector1D>,
    scope: CoroutineScope,
    screenHeightPx: Float
) {
    val density = LocalDensity.current
    // 下滑关闭阈值：跟手拉过 170dp 即关闭（与迷你条上拉 20% 屏高对称，偏灵敏）
    val thresholdPx = with(density) { 170.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 让覆盖层响应下滑手势，其余触摸交给内部 PlayerScreen
            .pointerInput(Unit) {
                var dragging = false
                detectVerticalDragGestures(
                    onDragStart = { dragging = true },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        if (dragging && dragAmount > 0) {
                            scope.launch {
                                playerOffsetY.snapTo(
                                    (playerOffsetY.value + dragAmount).coerceAtLeast(0f)
                                )
                            }
                        }
                    },
                    onDragEnd = {
                        dragging = false
                        scope.launch {
                            if (playerOffsetY.value > thresholdPx) {
                                // 先跟手滑出屏幕，再关闭（视觉上完整滑走）
                                playerOffsetY.animateTo(
                                    targetValue = screenHeightPx,
                                    animationSpec = tween(160)
                                )
                                onBack()
                            } else {
                                playerOffsetY.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                            }
                        }
                    },
                    onDragCancel = {
                        dragging = false
                        scope.launch {
                            playerOffsetY.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        }
                    }
                )
            }
            .graphicsLayer {
                translationY = playerOffsetY.value
            }
    ) {
        PlayerScreen(onBack = onBack)
    }
}

@Composable
private fun QueueDialog(onDismiss: () -> Unit) {
    val queue = PlayerController.queue
    // 拖拽状态
    var dragIndex by remember { mutableStateOf(-1) }
    var dragDelta by remember { mutableStateOf(0f) }
    var rowHeightPx by remember { mutableStateOf(0f) }

    AlertDialog(
        onDismissRequest = { if (dragIndex < 0) onDismiss() },
        title = { Text(stringResource(R.string.queue)) },
        text = {
            if (queue.isEmpty()) {
                Text(stringResource(R.string.queue_empty))
            } else {
                Column {
                    Text(
                        stringResource(R.string.queue_drag_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    val queueKeys = remember(queue) { songKeys(queue) }
                    LazyColumn(Modifier.height(360.dp)) {
                        itemsIndexed(queue, key = { i, _ -> queueKeys[i] }) { index, s ->
                            val isDragging = index == dragIndex
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .graphicsLayer {
                                        if (isDragging) {
                                            translationY = dragDelta
                                            scaleX = 1.03f
                                            scaleY = 1.03f
                                            shadowElevation = 8f
                                        }
                                    }
                                    // 长按拖动排序：跟手悬浮，松手按位移落位
                                    .pointerInput(s.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                dragIndex = index
                                                dragDelta = 0f
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragDelta += amount.y
                                            },
                                            onDragEnd = {
                                                if (rowHeightPx > 0f) {
                                                    val target = (dragIndex +
                                                        (dragDelta / rowHeightPx).roundToInt())
                                                        .coerceIn(0, PlayerController.queue.lastIndex)
                                                    if (target != dragIndex) {
                                                        PlayerController.moveInQueue(dragIndex, target)
                                                    }
                                                }
                                                dragIndex = -1
                                                dragDelta = 0f
                                            },
                                            onDragCancel = { dragIndex = -1; dragDelta = 0f }
                                        )
                                    }
                                    .onSizeChanged {
                                        if (index == 0 && rowHeightPx == 0f) {
                                            rowHeightPx = it.height.toFloat()
                                        }
                                    }
                            ) {
                                // 拖拽把手
                                Icon(
                                    Icons.Filled.Menu,
                                    contentDescription = stringResource(R.string.queue_drag_hint),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clickable { PlayerController.playIndexInQueue(index) }
                                ) {
                                    Text(
                                        s.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (index == PlayerController.currentIndex) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        s.artistNames,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                // 删除
                                IconButton(onClick = { PlayerController.removeFromQueue(index) }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        stringResource(R.string.queue_remove),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}

/** 圆形封面：播放时旋转 + 外层圆形进度环 */
@Composable
private fun SpinningCover() {
    val song = PlayerController.current ?: return
    val isPlaying = PlayerController.isPlaying
    val duration = PlayerController.durationMs
    val position = PlayerController.positionMs
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    val infinite = rememberInfiniteTransition(label = "spin")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "rotation"
    )

    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
        // 圆形进度环
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(46.dp),
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
        // 圆形封面（播放时旋转）
        CoverImage(
            url = song.coverUrl?.let { addParam(it, "100y100") },
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .graphicsLayer {
                    rotationZ = if (isPlaying) rotation else 0f
                }
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// 播放页状态栏显隐
// ═══════════════════════════════════════════════════════════════════

/**
 * 按开关切换系统状态栏：[hidden] 为 true 时隐藏（上滑可临时唤出），
 * 为 false 时恢复显示。经 [view] 反查所在 Activity 的 Window。
 */
private fun applyStatusBar(view: View, hidden: Boolean) {
    val window = (view.context as? Activity)?.window ?: return
    WindowCompat.getInsetsController(window, window.decorView).apply {
        if (hidden) {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        } else {
            show(WindowInsetsCompat.Type.statusBars())
        }
    }
}
