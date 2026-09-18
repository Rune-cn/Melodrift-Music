package app.melodrift.music.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.Image
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
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
import app.melodrift.music.data.mergeBackup
import app.melodrift.music.data.toBackupJson
import app.melodrift.music.net.NcmApi
import app.melodrift.music.player.QUALITY_LEVELS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 设置板块（点击卡片进入的子页面） */
private enum class SettingsSection(val titleRes: Int) {
    ACCOUNT(R.string.account),
    PLAYBACK(R.string.settings_playback),
    APPEARANCE(R.string.appearance),
    DATA(R.string.settings_data),
    ABOUT(R.string.about)
}

/**
 * 设置页：主页只展示几张板块卡片（简洁明了），
 * 点击卡片进入对应子页面；子页面内返回箭头 / 系统返回键回到主页。
 */
@Composable
fun SettingsScreen(
    settings: SettingsData,
    onChange: (SettingsData) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    var section by remember { mutableStateOf<SettingsSection?>(null) }
    // 子页面时：系统返回键先回到设置主页，而不是退出设置
    BackHandler(enabled = section != null) { section = null }

    // 主页 ⇄ 子页面：带方向的推进 / 回退过渡。
    // 原来只有缩放（0.94→1 / 1→0.97），两级页面之间没有方向信息，看不出"进了子页"；
    // 改成子页面从右侧推进、主页同时向左退场，返回时反向，位移只有屏宽 1/5 ~ 1/3，
    // 不会像整页平移那样喧宾夺主。
    AnimatedContent(
        targetState = section,
        transitionSpec = {
            val enteringSub = targetState != null
            val enter = slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) {
                if (enteringSub) it / 3 else -it / 5
            } + fadeIn(tween(200))
            val exit = slideOutHorizontally(tween(240, easing = LinearOutSlowInEasing)) {
                if (enteringSub) -it / 5 else it / 3
            } + fadeOut(tween(200))
            enter togetherWith exit
        },
        label = "settings"
    ) { current ->
        if (current == null) {
            SettingsMainPage(
                settings = settings,
                onChange = onChange,
                onOpen = { section = it },
                onBack = onBack
            )
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
    onOpen: (SettingsSection) -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏固定在滚动区之外：设置项变多往下滚时，返回按钮不会跟着滚走
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
            Text(
                stringResource(R.string.nav_settings),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = dimensionResource(R.dimen.page_padding))
        ) {
        // 设置板块卡片（无分隔线，行距更开阔）
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(dimensionResource(R.dimen.card_radius)),
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
                title = stringResource(R.string.settings_data),
                onClick = { onOpen(SettingsSection.DATA) }
            )
            SectionEntryRow(
                title = stringResource(R.string.about),
                showDivider = false,
                onClick = { onOpen(SettingsSection.ABOUT) }
            )
        }
        Spacer(Modifier.height(dimensionResource(R.dimen.space_xl)))
        }
    }
}

/** 设置主页入口行：标题 + 右箭头（无副标题） */
@Composable
private fun SectionEntryRow(
    title: String,
    trailing: @Composable () -> Unit = {},
    showDivider: Boolean = true,
    onClick: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
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
        // 主页卡内行间分隔线（最后一行 showDivider=false）
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
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

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部栏固定在滚动区之外（与设置主页一致）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = dimensionResource(R.dimen.page_padding))
        ) {
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
                    AccountTestRow(cookie = settings.cookie)
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
                        showDivider = false,
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
                        showDivider = false,
                        trailing = {
                            Switch(
                                checked = settings.lyricsFade,
                                onCheckedChange = { onChange(settings.copy(lyricsFade = it)) }
                            )
                        }
                    )
                }
            }
            SettingsSection.DATA -> {
                var pendingImport by remember { mutableStateOf<String?>(null) }
                var importFailed by remember { mutableStateOf(false) }
                val exportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/json")
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    val ok = try {
                        ctx.contentResolver.openOutputStream(uri)?.use {
                            it.write(settings.toBackupJson().toByteArray())
                        } != null
                    } catch (_: Exception) {
                        false
                    }
                    Toast.makeText(
                        ctx,
                        if (ok) R.string.export_ok else R.string.export_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    val text = try {
                        ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    } catch (_: Exception) {
                        null
                    }
                    if (text == null || settings.mergeBackup(text) == null) {
                        importFailed = true
                    } else {
                        pendingImport = text
                    }
                }
                SettingsCard {
                    SettingsRow(
                        title = stringResource(R.string.export_settings),
                        onClick = {
                            exportLauncher.launch(
                                // 注意：模式串里不能有裸字母（melodrift 里的 'e' 会被当成格式字符），
                                // 字面量必须加单引号；文件名前缀放到外面拼
                                "melodrift-settings-" +
                                    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
                                    ".json"
                            )
                        }
                    )
                    SettingsRow(
                        title = stringResource(R.string.import_settings),
                        showDivider = false,
                        onClick = {
                            importFailed = false
                            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        }
                    )
                }
                Spacer(Modifier.height(dimensionResource(R.dimen.card_gap)))
                Text(
                    stringResource(R.string.data_backup_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.space_s))
                )
                if (importFailed) {
                    Spacer(Modifier.height(dimensionResource(R.dimen.space_s)))
                    Text(
                        stringResource(R.string.import_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.space_s))
                    )
                }
                pendingImport?.let { json ->
                    AlertDialog(
                        onDismissRequest = { pendingImport = null },
                        title = { Text(stringResource(R.string.import_settings)) },
                        text = { Text(stringResource(R.string.import_confirm)) },
                        confirmButton = {
                            TextButton(onClick = {
                                val merged = settings.mergeBackup(json)
                                if (merged != null) {
                                    onChange(merged)
                                    Toast.makeText(
                                        ctx, R.string.import_ok, Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    importFailed = true
                                }
                                pendingImport = null
                            }) { Text(stringResource(R.string.confirm)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingImport = null }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    )
                }
            }
            SettingsSection.ABOUT -> {
                var legalKind by remember { mutableStateOf<String?>(null) }
                var crashLogText by remember { mutableStateOf<String?>(null) }
                // 应用图标：居中置顶，在所有设置项之上（自带浅灰底，故裁成圆角方形即可）
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp, bottom = 24.dp)
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_app_logo),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier
                            .size(dimensionResource(R.dimen.about_logo))
                            .clip(RoundedCornerShape(dimensionResource(R.dimen.about_logo_radius)))
                    )
                    Spacer(Modifier.height(dimensionResource(R.dimen.space_m)))
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
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
                        title = stringResource(R.string.github_repo),
                        trailing = {
                            // 用自有矢量（material-icons-extended 里没有 GitHub 品牌图标）
                            Icon(
                                painter = painterResource(R.drawable.ic_github),
                                contentDescription = stringResource(R.string.github_repo),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        onClick = { openExternalUrl(ctx, GITHUB_URL) }
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
                        showDivider = false,
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
        Spacer(Modifier.height(dimensionResource(R.dimen.space_xl)))
    }
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

@Composable
private fun MiniBarStyleDialog(current: String, onSelected: (String) -> Unit, onDismiss: () -> Unit) {
    SelectionDialog(
        title = stringResource(R.string.minibar_style),
        options = listOf(
            SelectionOption("rounded", stringResource(R.string.minibar_rounded)),
            SelectionOption("square", stringResource(R.string.minibar_square))
        ),
        selectedKey = current,
        onSelected = onSelected,
        onDismiss = onDismiss
    )
}

@Composable
private fun ProgressStyleDialog(current: String, onSelected: (String) -> Unit, onDismiss: () -> Unit) {
    SelectionDialog(
        title = stringResource(R.string.progress_style),
        options = listOf(
            SelectionOption("standard", stringResource(R.string.progress_standard)),
            SelectionOption("wave", stringResource(R.string.progress_wave))
        ),
        selectedKey = current,
        onSelected = onSelected,
        onDismiss = onDismiss
    )
}

/** Cookie 弹窗：输入 → 测试连接 → 保存 */
/**
 * Cookie 输入弹窗（保持朴素：输入 + 保存）。
 * 连接测试不在这里做，已移到「设置 → 账户」的一行，只给成功 / 失败两种结果。
 */
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
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.cookie_placeholder)) },
                minLines = 4,
                maxLines = 8,
                shape = RoundedCornerShape(dimensionResource(R.dimen.space_m))
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** 账户页的「测试连接」行：只给成功 / 失败两种结果（不显示昵称与错误详情） */
@Composable
private fun AccountTestRow(cookie: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var ok by remember { mutableStateOf<Boolean?>(null) }

    SettingsRow(
        title = stringResource(R.string.test_connection),
        showDivider = false,
        trailing = {
            when {
                testing -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                ok != null -> Text(
                    stringResource(if (ok == true) R.string.test_ok else R.string.test_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ok == true) colorResource(R.color.success_green)
                    else MaterialTheme.colorScheme.error
                )
            }
        },
        onClick = {
            if (cookie.isBlank()) {
                Toast.makeText(ctx, R.string.cookie_empty, Toast.LENGTH_SHORT).show()
            } else if (!testing) {
                testing = true
                ok = null
                scope.launch {
                    val r = try {
                        withContext(Dispatchers.IO) { NcmApi.account(); true }
                    } catch (_: Exception) {
                        false
                    }
                    ok = r
                    testing = false
                }
            }
        }
    )
}

// ─── 通用设置组件 ─────────────────────────────


@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(dimensionResource(R.dimen.card_radius)),
        colors = CardDefaults.cardColors(
            // 中性灰卡（surfaceContainerHighest 已显式定义，避免 M3 紫灰）+ 1dp 阴影：
            // 与参考项目一致的"浮卡"观感，卡内 1dp 分隔线（outlineVariant）可见
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsRow(
    title: String,
    enabled: Boolean = true,
    showDivider: Boolean = true,
    trailing: @Composable () -> Unit = {},
    onClick: () -> Unit = {}
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(
                    horizontal = dimensionResource(R.dimen.row_padding_h),
                    vertical = dimensionResource(R.dimen.row_padding_v)
                ),
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
        // 行间分隔线：与卡片等宽（两侧缩进行内边距），每组最后一行 showDivider=false
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.row_padding_h)),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
private fun languageLabel(lang: String): String = when (lang) {
    "zh" -> stringResource(R.string.language_zh)
    "en" -> stringResource(R.string.language_en)
    else -> stringResource(R.string.language_system)
}

@Composable
private fun QualityDialog(
    current: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    SelectionDialog(
        title = stringResource(R.string.default_quality),
        options = QUALITY_LEVELS.map { SelectionOption(it, qualityLabel(it)) },
        selectedKey = current,
        onSelected = onSelected,
        onDismiss = onDismiss
    )
}

@Composable
private fun CrossfadeDialog(
    current: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    SelectionDialog(
        title = stringResource(R.string.crossfade_duration),
        options = (1..8).map {
            SelectionOption(it.toString(), stringResource(R.string.seconds_format, it))
        },
        selectedKey = current.toString(),
        onSelected = { onSelected(it.toInt()) },
        onDismiss = onDismiss
    )
}

@Composable
private fun LanguageDialog(
    current: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    SelectionDialog(
        title = stringResource(R.string.language),
        options = listOf(
            SelectionOption("system", stringResource(R.string.language_system)),
            SelectionOption("zh", "简体中文"),
            SelectionOption("en", "English")
        ),
        selectedKey = current,
        onSelected = onSelected,
        onDismiss = onDismiss
    )
}

/** 深色模式当前值的显示文案 */
@Composable
private fun darkModeLabel(mode: String): String = when (mode) {
    "dark" -> stringResource(R.string.dark_mode_dark)
    "light" -> stringResource(R.string.dark_mode_light)
    else -> stringResource(R.string.dark_mode_system)
}

@Composable
private fun DarkModeDialog(
    current: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    SelectionDialog(
        title = stringResource(R.string.dark_mode),
        options = listOf(
            SelectionOption("system", stringResource(R.string.dark_mode_system)),
            SelectionOption("light", stringResource(R.string.dark_mode_light)),
            SelectionOption("dark", stringResource(R.string.dark_mode_dark))
        ),
        selectedKey = current,
        onSelected = onSelected,
        onDismiss = onDismiss
    )
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
