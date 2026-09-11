package app.melodrift.music.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.melodrift.music.BuildConfig
import app.melodrift.music.R
import app.melodrift.music.util.CrashLog
import app.melodrift.music.data.SettingsData
import app.melodrift.music.net.NcmApi
import app.melodrift.music.player.QUALITY_LEVELS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置板块（点击卡片进入的子页面） */
private enum class SettingsSection(val titleRes: Int) {
    ACCOUNT(R.string.account),
    PLAYBACK(R.string.settings_playback),
    APPEARANCE(R.string.appearance),
    ABOUT(R.string.about)
}

/**
 * 设置页：主页只展示几张板块卡片（简洁明了），
 * 点击卡片进入对应子页面；子页面内返回箭头 / 系统返回键回到主页。
 */
@Composable
fun SettingsScreen(
    settings: SettingsData,
    onChange: (SettingsData) -> Unit
) {
    val ctx = LocalContext.current
    var section by remember { mutableStateOf<SettingsSection?>(null) }
    // 子页面时：系统返回键先回到设置主页，而不是退出设置
    BackHandler(enabled = section != null) { section = null }

    // 主页 ⇄ 子页面：缩放 + 淡入淡出过渡
    AnimatedContent(
        targetState = section,
        transitionSpec = {
            (scaleIn(initialScale = 0.94f, animationSpec = tween(240)) + fadeIn(tween(240))) togetherWith
                (scaleOut(targetScale = 0.97f, animationSpec = tween(160)) + fadeOut(tween(160)))
        },
        label = "settings"
    ) { current ->
        if (current == null) {
            SettingsMainPage(settings = settings, onChange = onChange, onOpen = { section = it })
        } else {
            SettingsSectionPage(
                section = current,
                settings = settings,
                onChange = onChange,
                onBack = { section = null }
            )
        }
    }
}

// ═══════════════════ 主页：板块卡片列表 ═══════════════════

@Composable
private fun SettingsMainPage(
    settings: SettingsData,
    onChange: (SettingsData) -> Unit,
    onOpen: (SettingsSection) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            stringResource(R.string.nav_settings),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        // 设置板块卡片（无分隔线，行距更开阔）
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        ) {
            SectionEntryRow(
                title = stringResource(R.string.account),
                trailing = {
                    Text(
                        if (settings.cookie.isBlank()) {
                            stringResource(R.string.cookie_unset)
                        } else {
                            stringResource(R.string.cookie_set)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                onClick = { onOpen(SettingsSection.ACCOUNT) }
            )
            SectionEntryRow(
                title = stringResource(R.string.settings_playback),
                onClick = { onOpen(SettingsSection.PLAYBACK) }
            )
            SectionEntryRow(
                title = stringResource(R.string.appearance),
                onClick = { onOpen(SettingsSection.APPEARANCE) }
            )
            SectionEntryRow(
                title = stringResource(R.string.about),
                onClick = { onOpen(SettingsSection.ABOUT) }
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 设置主页入口行：标题 + 右箭头（无副标题） */
@Composable
private fun SectionEntryRow(
    title: String,
    trailing: @Composable () -> Unit = {},
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        trailing()
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ═══════════════════ 子页面 ═══════════════════

@Composable
private fun SettingsSectionPage(
    section: SettingsSection,
    settings: SettingsData,
    onChange: (SettingsData) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    var showCookieDialog by remember { mutableStateOf(false) }
    var showDarkModeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showCrossfadeDialog by remember { mutableStateOf(false) }
    var showMiniBarDialog by remember { mutableStateOf(false) }
    var showProgressDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 子页面顶部栏：返回 + 标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
            Text(
                stringResource(section.titleRes),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
        }

        when (section) {
            SettingsSection.ACCOUNT -> {
                SettingsCard {
                    SettingsRow(
                        title = stringResource(R.string.cookie),
                        trailing = {
                            Text(
                                if (settings.cookie.isBlank()) {
                                    stringResource(R.string.cookie_unset)
                                } else {
                                    stringResource(R.string.cookie_set)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = { showCookieDialog = true }
                    )
                }
            }
            SettingsSection.PLAYBACK -> {
                SettingsCard {
                    SettingsRow(
                        title = stringResource(R.string.default_quality),
                        trailing = {
                            Text(
                                qualityLabel(settings.defaultQuality),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = { showQualityDialog = true }
                    )
                    SettingsRow(
                        title = stringResource(R.string.crossfade),
                        trailing = {
                            Switch(
                                checked = settings.crossfadeEnabled,
                                onCheckedChange = {
                                    onChange(settings.copy(crossfadeEnabled = it))
                                }
                            )
                        }
                    )
                    if (settings.crossfadeEnabled) {
                        SettingsRow(
                            title = stringResource(R.string.crossfade_duration),
                            trailing = {
                                Text(
                                    stringResource(
                                        R.string.seconds_format, settings.crossfadeSeconds
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = { showCrossfadeDialog = true }
                        )
                    }
                    SettingsRow(
                        title = stringResource(R.string.allow_mixed_audio),
                        trailing = {
                            Switch(
                                checked = settings.allowMixedAudio,
                                onCheckedChange = { onChange(settings.copy(allowMixedAudio = it)) }
                            )
                        }
                    )
                    SettingsRow(
                        title = stringResource(R.string.resume_playback),
                        trailing = {
                            Switch(
                                checked = settings.resumePlaybackPosition,
                                onCheckedChange = {
                                    onChange(settings.copy(resumePlaybackPosition = it))
                                }
                            )
                        }
                    )
                    SettingsRow(
                        title = stringResource(R.string.player_status_bar),
                        trailing = {
                            Switch(
                                checked = settings.playerStatusBar,
                                onCheckedChange = {
                                    onChange(settings.copy(playerStatusBar = it))
                                }
                            )
                        }
                    )
                }
            }
            SettingsSection.APPEARANCE -> {
                SettingsCard {
                    SettingsRow(
                        title = stringResource(R.string.dark_mode),
                        trailing = {
                            Text(
                                darkModeLabel(settings.darkMode),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = { showDarkModeDialog = true }
                    )
                    SettingsRow(
                        title = stringResource(R.string.language),
                        trailing = {
                            Text(
                                languageLabel(settings.language),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = { showLanguageDialog = true }
                    )
                    SettingsRow(
                        title = stringResource(R.string.minibar_style),
                        trailing = {
                            Text(
                                stringResource(
                                    if (settings.miniBarStyle == "square") R.string.minibar_square
                                    else R.string.minibar_rounded
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = { showMiniBarDialog = true }
                    )
                    SettingsRow(
                        title = stringResource(R.string.progress_style),
                        trailing = {
                            Text(
                                stringResource(
                                    if (settings.progressStyle == "wave") R.string.progress_wave
                                    else R.string.progress_standard
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = { showProgressDialog = true }
                    )
                    SettingsRow(
                        title = stringResource(R.string.lyrics_fade),
                        trailing = {
                            Switch(
                                checked = settings.lyricsFade,
                                onCheckedChange = { onChange(settings.copy(lyricsFade = it)) }
                            )
                        }
                    )
                }
            }
            SettingsSection.ABOUT -> {
                var legalKind by remember { mutableStateOf<String?>(null) }
                var crashLogText by remember { mutableStateOf<String?>(null) }
                SettingsCard {
                    SettingsRow(
                        title = stringResource(R.string.app_name),
                        trailing = {
                            Text(
                                stringResource(
                                    R.string.version_code, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                    SettingsRow(
                        title = stringResource(R.string.terms_title),
                        onClick = { legalKind = "terms" }
                    )
                    SettingsRow(
                        title = stringResource(R.string.privacy_title),
                        onClick = { legalKind = "privacy" }
                    )
                    SettingsRow(
                        title = stringResource(R.string.disclaimer_title),
                        onClick = { legalKind = "disclaimer" }
                    )
                    SettingsRow(
                        title = stringResource(R.string.crash_log),
                        onClick = {
                            crashLogText = CrashLog.read(ctx)
                            if (crashLogText == null) {
                                Toast.makeText(
                                    ctx, R.string.no_crash_log, Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }
                legalKind?.let { kind ->
                    LegalContentDialog(kind = kind, onDismiss = { legalKind = null })
                }
                crashLogText?.let { text ->
                    CrashLogDialog(text = text, onDismiss = { crashLogText = null })
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showCookieDialog) {
        CookieDialog(
            current = settings.cookie,
            onSave = { cookie ->
                onChange(settings.copy(cookie = cookie))
                showCookieDialog = false
            },
            onDismiss = { showCookieDialog = false }
        )
    }
    if (showDarkModeDialog) {
        DarkModeDialog(
            current = settings.darkMode,
            onSelected = { mode ->
                onChange(settings.copy(darkMode = mode))
                showDarkModeDialog = false
            },
            onDismiss = { showDarkModeDialog = false }
        )
    }
    if (showLanguageDialog) {
        LanguageDialog(
            current = settings.language,
            onSelected = { lang ->
                onChange(settings.copy(language = lang))
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }
    if (showQualityDialog) {
        QualityDialog(
            current = settings.defaultQuality,
            onSelected = { q ->
                onChange(settings.copy(defaultQuality = q))
                showQualityDialog = false
            },
            onDismiss = { showQualityDialog = false }
        )
    }
    if (showCrossfadeDialog) {
        CrossfadeDialog(
            current = settings.crossfadeSeconds,
            onSelected = { sec ->
                onChange(settings.copy(crossfadeSeconds = sec))
                showCrossfadeDialog = false
            },
            onDismiss = { showCrossfadeDialog = false }
        )
    }
    if (showMiniBarDialog) {
        MiniBarStyleDialog(
            current = settings.miniBarStyle,
            onSelected = { s ->
                onChange(settings.copy(miniBarStyle = s))
                showMiniBarDialog = false
            },
            onDismiss = { showMiniBarDialog = false }
        )
    }
    if (showProgressDialog) {
        ProgressStyleDialog(
            current = settings.progressStyle,
            onSelected = { s ->
                onChange(settings.copy(progressStyle = s))
                showProgressDialog = false
            },
            onDismiss = { showProgressDialog = false }
        )
    }
}

/** 播放器底部迷你条样式弹窗 */
@Composable
private fun MiniBarStyleDialog(current: String, onSelected: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.minibar_style)) },
        text = {
            Column {
                RadioRow(stringResource(R.string.minibar_rounded), current != "square") { onSelected("rounded") }
                RadioRow(stringResource(R.string.minibar_square), current == "square") { onSelected("square") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** 进度条样式弹窗 */
@Composable
private fun ProgressStyleDialog(current: String, onSelected: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.progress_style)) },
        text = {
            Column {
                RadioRow(stringResource(R.string.progress_standard), current != "wave") { onSelected("standard") }
                RadioRow(stringResource(R.string.progress_wave), current == "wave") { onSelected("wave") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** Cookie 弹窗：输入 → 测试连接 → 保存 */
@Composable
private fun CookieDialog(
    current: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cookie)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.cookie_placeholder)) },
                    minLines = 4,
                    maxLines = 8,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(8.dp))
                TestConnectionRow(cookie = text)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/** Cookie 测试连接行：成功后显示账号昵称 */
@Composable
private fun TestConnectionRow(cookie: String) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val emptyMsg = stringResource(R.string.cookie_empty)
    val okTemplate = stringResource(R.string.cookie_ok)
    val errTemplate = stringResource(R.string.cookie_error)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = {
                if (cookie.isBlank()) {
                    Toast.makeText(ctx, emptyMsg, Toast.LENGTH_SHORT).show()
                    return@Button
                }
                testing = true
                result = null
                scope.launch {
                    NcmApi.cookie = cookie.trim()
                    val r = try {
                        val acc = withContext(Dispatchers.IO) { NcmApi.account() }
                        Pair(true, String.format(okTemplate, acc.nickname))
                    } catch (e: Exception) {
                        Pair(false, String.format(errTemplate, e.message ?: ""))
                    }
                    testing = false
                    result = r
                }
            },
            enabled = !testing
        ) {
            if (testing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.cookie_testing))
            } else {
                Text(stringResource(R.string.cookie_test))
            }
        }
        Spacer(Modifier.width(12.dp))
        val r = result
        if (r != null) {
            Icon(
                if (r.first) Icons.Filled.Check else Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = if (r.first) colorResource(R.color.success_green)
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.width(20.dp).height(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                r.second,
                style = MaterialTheme.typography.bodySmall,
                color = if (r.first) colorResource(R.color.success_green)
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ─── 通用设置组件 ─────────────────────────────

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsRow(
    title: String,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit = {},
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) Color.Unspecified
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun languageLabel(lang: String): String = when (lang) {
    "zh" -> stringResource(R.string.language_zh)
    "en" -> stringResource(R.string.language_en)
    else -> stringResource(R.string.language_system)
}

/** 单选行（供 Quality / Crossfade / Language 复用） */
@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun QualityDialog(
    current: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.default_quality)) },
        text = {
            Column {
                QUALITY_LEVELS.forEach { level ->
                    RadioRow(
                        label = qualityLabel(level),
                        selected = current == level,
                        onSelect = { onSelected(level) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/** 淡入淡出时长弹窗：单一时长，新歌渐强与旧歌减弱共用（1~8 秒） */
@Composable
private fun CrossfadeDialog(
    current: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crossfade_duration)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                (1..8).forEach { sec ->
                    RadioRow(
                        label = stringResource(R.string.seconds_format, sec),
                        selected = current == sec,
                        onSelect = { onSelected(sec) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun LanguageDialog(
    current: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language)) },
        text = {
            Column {
                LanguageRow(
                    value = "system",
                    label = stringResource(R.string.language_system),
                    selected = current,
                    onSelect = { onSelected("system") }
                )
                LanguageRow(
                    value = "zh",
                    label = stringResource(R.string.language_zh),
                    selected = current,
                    onSelect = { onSelected("zh") }
                )
                LanguageRow(
                    value = "en",
                    label = stringResource(R.string.language_en),
                    selected = current,
                    onSelect = { onSelected("en") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/** 深色模式当前值的显示文案 */
@Composable
private fun darkModeLabel(mode: String): String = when (mode) {
    "dark" -> stringResource(R.string.dark_mode_dark)
    "light" -> stringResource(R.string.dark_mode_light)
    else -> stringResource(R.string.dark_mode_system)
}

/** 深色模式弹窗：跟随系统 / 浅色 / 深色 */
@Composable
private fun DarkModeDialog(
    current: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dark_mode)) },
        text = {
            Column {
                RadioRow(
                    label = stringResource(R.string.dark_mode_system),
                    selected = current == "system",
                    onSelect = { onSelected("system") }
                )
                RadioRow(
                    label = stringResource(R.string.dark_mode_light),
                    selected = current == "light",
                    onSelect = { onSelected("light") }
                )
                RadioRow(
                    label = stringResource(R.string.dark_mode_dark),
                    selected = current == "dark",
                    onSelect = { onSelected("dark") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun LanguageRow(value: String, label: String, selected: String, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected == value, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** 崩溃日志查看弹窗：显示堆栈 + 一键复制 */
@Composable
private fun CrashLogDialog(text: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crash_log)) },
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 16,
                overflow = TextOverflow.Ellipsis
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        dismissButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(text))
                Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.copy)) }
        }
    )
}
