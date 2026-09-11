# Melodrift Music · 技术规范

> 面向二次开发与维护的规范。遵循 Google Android + Material Design 3 + androidx.media3 标准。
> 功能概览与构建见 [`README.md`](README.md)。

## 1. 项目速览

| 项 | 值 |
|------|------|
| 命名空间 / applicationId | `app.melodrift.music` |
| 版本 | 1.0.2（versionCode 3） |
| compileSdk / targetSdk / minSdk | 37 / **36（Android 16）** / 26 |
| ABI | 仅 `arm64-v8a` |
| 语言资源 | 仅 `zh-rCN` + `en`（`resourceConfigurations`）。注意英文是**默认 `values/`**，
  `aapt2 dump badging` 显示为 `--_--` 而非 `en`，`"en"` 一项实际不参与筛选（保留以防将来出现带前缀的 en 资源） |
| AGP / Gradle | **9.4.0 / ≥ 9.6.0**（AGP 9.4 硬性要求，旧 Gradle 直接报错） |
| Kotlin | 2.4.10（compose 插件同版本，Java 17） |
| Compose BOM | 2026.08.00 |
| 播放 | media3 1.11.0（exoplayer + datasource-okhttp）、androidx.media 1.8.0 |
| 网络 / 图片 | okhttp 4.12.0、coil-compose 2.6.0、coroutines 1.11.0 |
| AndroidX | activity-compose 1.13.0、lifecycle-runtime-compose 2.11.0、core-ktx 1.19.0 |
| 产物 | `app-release.apk`（R8 + shrinkResources + v2 签名，约 2.2 MB） |

### 构建约束（踩过的坑）

- **`android.aapt2FromMavenOverride`**：AGP 自带 aapt2 与本机 SDK 不匹配会失败，
  指向 `build-tools/34.0.0/aapt2` 是必要修复，**不能删**。
- **okhttp 锁 4.12.0**：`media3-datasource-okhttp:1.11.0` 的编译依赖就是 okhttp 4.12.0，
  升 okhttp 5.x 有二进制兼容风险，**不要顺手升**。
- **`resourceConfigurations` 在 AGP 9.4 报 deprecation**：提示改用 `androidResources.localeFilters`，
  但后者走 `GenerateLocaleConfigTask`（per-app language 配置），**与"链接期剥离资源"语义不等价**，
  贸然替换有静默丢中文资源的风险，故保留现状。
- 仓库优先阿里云镜像 + `FAIL_ON_PROJECT_REPOS` 统一；`flatDir { dirs("libs") }` 备用。

## 2. 分层架构

```
ui ──────────────┐  Compose 界面，只读状态 + 调 PlayerController / NcmApi
 ├── PlayerController（object 单例，Compose State 暴露）
 ├── NcmApi（object 单例，阻塞式，必须在 IO 线程调用）
 └── data/*（SharedPreferences 存储，同步读写）
player ──────────── ExoPlayer 封装 + 前台服务 + 通知 + MediaSession
net ─────────────── weapi 加密请求 / 模型解析 / 下载
util ────────────── CrashLog
```

- **无 ViewModel / DI 框架**：状态集中在 `PlayerController` 单例与 `SettingsRepository`，
  页面直接观察。新增全局状态优先放进 `PlayerController`，不要在各 Screen 里造平行状态。
- **网络调用一律 `withContext(Dispatchers.IO)`**：`NcmApi` 全部是阻塞函数，
  UI 侧禁止在主线程直接调用（新代码请沿用 `AsyncContent` / `LaunchedEffect + Dispatchers.IO`）。

## 3. 网络层规范（`net/`）

- `NcmCrypto`：weapi（双层 AES-128-CBC + RSA `encSecKey`）与 eapi（AES-128-ECB）两套算法集中于此，
  改加密只动这个文件。**当前业务只用 weapi**；`eapiEncrypt()` 已实现但无调用点（预留给官方下载接口，
  尚未接入），别误以为 eapi 链路已经跑通。
- `NcmApi`：单例 `OkHttpClient`；`cookie` 由设置页注入（含 `MUSIC_U` / `__csrf`）；
  写操作（红心 / 收藏 / 建删歌单）返回 `Boolean`/`Long`，读操作返回 `Models.kt` 里的数据类。
- **`playlistDetail` 必须保留 trackIds 兜底**：`/api/v6/playlist/detail` 对部分请求只回
  `trackIds` 不回 `tracks`（大歌单/登录态等场景），`tracks` 为空时走
  `songsDetail(trackIds)` 批量详情并按原序排回 —— 删掉它会出现"第一次进歌单有歌、
  重进就没歌"的回归。
- `Models.kt`：轻量 data class + `Json` 工具（org.json，不引入序列化库）。
  **所有进 UI 的模型都标 `@Immutable`** —— 让 Compose 认作稳定参数，避免整列重组。
- `Downloader`：按音质取地址 → 写系统「下载」目录（MediaStore，低版本回落 legacy 外部存储）；
  失败抛带原因的 `IOException`；**必须在 IO 线程调用**。
  - **只有一种下载方案**：weapi `/api/song/enhance/player/url/v1` 取播放地址 → CDN 直下，
    音质拿不到时 `songUrlSmart` 智能降级。官方客户端下载接口（eapi
    `/api/song/enhance/download/url/v1`）**未实现**，也没有「下载方式」设置项，别照着旧文档加开关。
  - **落盘位置**：`Download/MelodriftMusic`（API 29+ 走 MediaStore；API 26–28 走 legacy 公共目录，
    需要 `WRITE_EXTERNAL_STORAGE`，Manifest 已限 `maxSdkVersion="28"`，由 `DownloadQualityDialog`
    里的 `rememberStoragePermissionGuard()` 请求，授权后自动续跑下载；未授权或被拒绝时回落应用专属外部目录）。
  - **批量下载整个歌单**：`songUrls()` 一次批量取址（避免逐首请求被限流）+ 串行下载 +
    单首失败延时 300ms 重试一次；入口在歌单详情页菜单，与单曲共用同一个音质选择对话框。
  - **下载必须跑在 `Downloader.taskScope`**（独立 SupervisorJob），不得用页面/对话框的
    `rememberCoroutineScope` —— 对话框 dismiss、页面退出都会取消那个 scope，
    下载实际完成却因 CancellationException 被当失败（"提示失败但文件已下载"就是这个坑）。

## 4. 播放器规范（`player/`）

### 4.1 状态与线程

- `PlayerController` 是 `object` 单例，状态全部为 Compose State（`mutableStateOf` /
  `mutableLongStateOf` 等），回调统一在主线程更新。
- `LoopMode { LIST, ONE, SHUFFLE }`；音质档位 `QUALITY_LEVELS = standard / exhigh / lossless / hires`。
- 生命周期：`MusicPlaybackService`（`foregroundServiceType=mediaPlayback`）持有前台通知，
  按钮经 `PendingIntent.getService` 回调服务再转发给 `PlayerController`。

### 4.2 进度回路（性能关键，改动前必读）

- 进度轮询 `progress` Runnable **只在真正播放时自续期**：暂停 / 无歌 / 播放器为空即停，
  由 `startTicker()`（幂等）在 `onIsPlayingChanged` 与 `STATE_READY` 拉起。
  **禁止**改回"从 App 启动起常驻 500ms"。
- 轮询内**不得**调用 `updateSessionState()`：媒体会话按 `(position, playbackSpeed, updateTime)`
  自行插值，每 500ms 推一次是纯浪费的跨进程调用。刷新时机固定为
  **播放态变化、`seekTo`、`updatePosition`、`setSpeed`、`updateNotification`**。
- `PlaybackStateCompat` 的 actions 位掩码提为常量 `sessionActions`，不要每次重建。
- 断点续播：播放中每 5 秒落盘一次位置，暂停时立即落盘（`savePlaybackPosition`）。
- **切歌过渡期不变量（`exoSongId`，破坏它会复现"新歌跳过一段"bug）**：
  `playIndex` 先更新 `currentIndex`、异步拉到播放地址后才真正 `playUrl` 切换播放器，
  期间 `current`（新歌）与 `exo` 在放的（旧歌）**不一致**。所有"读 `positionMs` /
  写断点"的路径必须先确认 `current?.id == exoSongId`：
  - 轮询 `progress`：不匹配就停（窗口期内不得刷新 `positionMs` / `durationMs`）
  - 周期落盘 / `savePlaybackPosition()`（toggle 暂停路径）：不匹配禁止写 `PlaybackPositions`
  - `playIndex` 开头"保存上一首"：要求 `oldSong.id == exoSongId`（快速连点时 `positionMs` 可能属于更早的歌）
  - `onCompleted()`：不匹配直接 return（窗口期内旧歌自然播完 ≠ 当前歌播完，
    否则会误清新歌断点并重复切歌）
  `exoSongId` 在 `playUrl` 前一刻设为 `song.id`（淡出作用在旧歌上，淡完才对齐），
  `playUrl` 末尾 `startTicker()` 重新拉起轮询。
- **恢复态**：`init()` 从 `QueueStore` 拉回上次队列后 `exoSongId` 仍为 null，
  `toggle()` 检测到 `current?.id != exoSongId` 会走 `playIndex(restorePosition=true)`
  —— 这让"重开 App 后点播放"等于从断点继续，而不是空操作。改动 toggle 前先理解这条。

### 4.3 重组范围

- 高频状态（`positionMs`）只喂进度条；派生值一律用 `derivedStateOf`。
  歌词当前行 = `derivedStateOf` + **二分查找**（歌词按时间升序），
  确保"只有行号变化才重组"，不得退回"每 tick 全表线性扫"。
- 动画用 `graphicsLayer`（旋转唱片等），避免触发重排。

## 5. UI 规范（`ui/`）

### 5.1 导航

- 自实现返回栈：`private sealed interface Screen`（Home / Search / Library / Settings /
  Playlist / SongList / Player）+ `var stack: List<Screen>`；`push` 只挡**栈顶**重复
  （`if (stack.last() != s)`，不是全栈去重），`pop` 出栈。
- 底栏两个 tab（首页 / 收藏）；播放页是**覆盖层**而非普通页面：
  迷你条上拉 → 预览态跟手升起，过阈值才 `push`；下滑 → 跟手收起，过阈值才关闭。
- 系统返回键：预览中先取消预览，其次出栈；**设置子页面优先退回设置主页**
  （`BackHandler(enabled = section != null)`，子页面 handler 注册晚于 Activity 级因而优先响应）。

### 5.2 设置页（两级结构）

- `SettingsSection` 枚举携带 `titleRes`；新增板块 = 加枚举项 + `when` 分支，**不要**在主页平铺设置项。
- **主页**：`headlineLarge` 标题 + 一张 `SettingsCard`，内含若干 `SectionEntryRow`
  （标题 + `KeyboardArrowRight`，无副标题、无分隔线）。
- **子页面** `SettingsSectionPage`：返回 `IconButton`(`ArrowBack`) + `headlineSmall/SemiBold`
  板块名 + `when(section)` 渲染分组卡片。
- **`SettingsRow`**：只有 `title` + `trailing`（+ `onClick` / `enabled`）；
  **不带副标题、不带 `HorizontalDivider`**，行内边距水平 20dp / 垂直 16dp，靠留白分隔。
- 值型项右侧用当前值文本，点击开 `AlertDialog` 单选（`RadioRow`）；开关型直接 `Switch`。
- 主页 ⇄ 子页面：`AnimatedContent`，`scaleIn(0.94f, 240ms)+fadeIn(240)` ↔ `scaleOut(0.97f, 160ms)+fadeOut(160)`。
- 长列表一律 `verticalScroll(rememberScrollState())`。

### 5.3 深色模式（三态）

- `SettingsData.darkMode: String` ∈ `system / light / dark`，默认 `system`。
- 主题侧：`"dark"→true`、`"light"→false`、其余 `isSystemInDarkTheme()`。
- **配色固定品牌色**（`melodriftColorScheme`），**不做动态取色**，浅色背景恒为 `#F0F1F3`。

### 5.4 列表

- 所有 `LazyColumn` / `LazyRow` 的 `items` **必须带 `key`**。
- 歌曲列表**不得**直接用 `song.id` 当 key —— 队列与歌单可能出现同一首歌两次，
  LazyList 撞 key 会抛 `IllegalArgumentException` 崩在滚动时；统一走 `ui/ListKeys.kt` 的
  `songKeys()`（同 id 第 n 次出现 → `"id#n"`，绑定条目而非位置，拖拽排序不受影响）。
- 唯一实体（歌单 / 榜单 / 歌手 / 搜索结果）可直接 `key = { it.id }`。
- 歌词行 `itemsIndexed(lines)` 允许无 key（`LyricLine` 无稳定 id，用 index 等于没加）。

### 5.5 资源与文案

- 全部文案进 `values/strings.xml`（en）+ `values-zh-rCN/strings.xml`（zh），**key 与中英一一对应**
  （当前各 192 条，可用 `diff <(grep -o 'name="…"' values/strings.xml|sort) <(… values-zh-rCN/…|sort)` 校验）；
  ui 层一律 `stringResource()`，不得新增硬编码文案。
- **已知例外（待办，见 §8）**：`net/` 层异常文案与兜底名仍是中文字面量
  （`"未登录：cookie 中缺少有效的 MUSIC_U"`、`"每日推荐为空（可能未登录）"`、`"歌单不存在"`、
  `"未知歌曲/未知歌单/陌生人"`），会经 `e.message` 进 `ErrorBox`/Toast；
  `Components.formatCount()` 的 `"%.1f亿"/"%.1f万"` 在英文界面也显示中文单位。
- 语言切换：`attachBaseContext` 挂载 locale + `CompositionLocalProvider(LocalContext provides ...)`
  即时生效，**不 recreate Activity**；locale 构造用 `Locale.forLanguageTag("zh-CN")`
  （`Locale("zh","CN")` 已 deprecated）。
- 图标一律带 `contentDescription`（装饰性传 `null`）。

## 6. 本地存储（`data/`）

| 存储 | 内容 | 说明 |
|---|---|---|
| `SettingsRepository` | 全部设置项 | SP `settings`，字段名即 key，`save { copy() }` 全量写回 |
| `QueueStore` | 播放队列快照（ids+index+进度） | 重开 App 后迷你条恢复上次歌曲，**不自动播放**；点播放按断点续播 |
| `PlayHistoryStore` | 听歌历史 id 列表 | 最新在前、去重、上限 50 |
| `PlaybackPositions` | 每首歌的播放位置 | 断点续播 |
| `SavedPlaylists` | songId → 收藏到的歌单集合 | 本地补全"收藏到任意歌单即点亮红心" |
| `SavedSongsCache` | 自建歌单歌曲 id 并集 | 服务端数据，TTL 5 分钟，写操作后 `invalidate()` |
| `SearchHistory` | 搜索关键词 | 去重、上限 20 |
| `CrashLog` | 未捕获异常堆栈 | 私有目录 `crash.log`，「关于 → 崩溃日志」查看 |

**改设置项类型的迁移规则**：SharedPreferences 换类型后旧值会让 `getString` 抛
`ClassCastException`。统一用 `sp.all[key]` 判实际类型再转换
（见 `Settings.kt` 的 `readDarkMode`：旧 Boolean `true→dark` / `false→light`，无记录→`system`）。

## 7. 发布检查清单

- [ ] `versionCode` / `versionName` 已递增（改签名或大版本必须递增）
- [ ] release 构建开启 minify + shrinkResources，`assembleRelease` 通过且**无新增警告**
- [ ] `apksigner verify --print-certs` 证书为 `CN=Melodrift Music`
- [ ] `aapt2 dump badging` 确认 `targetSdkVersion:36`、`minSdkVersion:26`
- [ ] `aapt2 dump resources` 确认 `zh-rCN` 配置仍在、其他语言已剥离
- [ ] 三态深色模式（含跟随系统随设备切换）、中英切换、首次启动协议弹窗各验一遍
- [ ] 播放页：状态栏开关、进度 seek、倍速、定时、循环、音质切换、下载
- [ ] 双语歌词：有译文的歌显示原文 + 译文两行且随当前行高亮；无译文歌只剩原文；
      「歌词上下渐变」开关对整块（含译文）生效
- [ ] Android 9 及以下：首次下载弹存储权限 → 同意后自动续跑下载、文件出现在 `Download/MelodriftMusic`；
      拒绝时给出一次明确提示而不是静默失败
- [ ] 后台播放 + 通知控制 + 耳机线控；断网 / 切歌 / 播放完毕不崩溃
- [ ] 大歌单滚动无掉帧、队列拖拽排序后条目状态不错位
- [ ] 源码分发：移除 `*.keystore`、`local.properties`、`build/`、`.gradle/`、`.kotlin/`，
      `gradle.properties` 删掉 `KEYSTORE_PASS` / `KEY_PASS` 两行

## 8. 技术债（按优先级）

1. **`MediaSessionCompat` → `androidx.media3.session.MediaSession`**
   `androidx.media` 1.8.0 已把 `MediaSessionCompat` / `NotificationCompat` /
   `PlaybackStateCompat` / `MediaMetadataCompat` 全类标记废弃（构建时 20+ 条警告）。
   迁移后可整个删掉 `androidx.media` 依赖。需真机回归通知栏、线控、后台拉起。
2. **Baseline Profile**：AGP 9 原生支持 `androidx.baselineprofile`，对 Compose 首帧 / 滚动
   收益最大，但需真机跑 macrobenchmark 生成。
3. **`material-icons-extended`** 全量图标库 → 换 core + 按需矢量，降编译期与 dex 体积。
4. **okhttp 5.x / Coil 3.x**：分别受 media3 依赖与包名迁移阻塞，需单独排期。
5. **KRC 逐字歌词** Canvas 渲染（尚未实现）。
6. 零散清理：`TabRow` → `PrimaryTabRow`；`LocalClipboardManager` → `LocalClipboard`（API 变 suspend）。
7. **`net/` 层文案资源化**：异常原因与兜底名改 key（或错误码 + ui 层映射），
   `formatCount()` 的 `亿/万` 改按当前 locale 走 `CompactNumberFormatter` 或本地化模板。
