# Melodrift Music · 技术规范

> 面向二次开发与维护的规范。遵循 Google Android + Material Design 3 + androidx.media3 标准。
> 功能概览与构建见 [`README.md`](README.md)。

## 1. 项目速览

| 项 | 值 |
|------|------|
| 命名空间 / applicationId | `app.melodrift.music` |
| 版本 | 1.0.5（versionCode 6） |
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
net ─────────────── weapi 加密请求 / 模型解析 / 歌词解析配对(Lyrics) / 下载
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
- **`csrfToken()` 必须取 cookie 里最后一个 `__csrf`**：用户粘贴的 cookie 常同时带着旧会话
  和新会话两份 `__csrf`，服务端只认最后那份；取第一份会让写操作统一返回
  `403 illegal request!`（v1.0.2 及之前"删除歌单失败"的根因）。
- **`playlistDetail` 必须保留 trackIds 兜底**：`/api/v6/playlist/detail` 对部分请求只回
  `trackIds` 不回 `tracks`（大歌单/登录态等场景），`tracks` 为空时走
  `songsDetail(trackIds)` 批量详情并按原序排回 —— 删掉它会出现"第一次进歌单有歌、
  重进就没歌"的回归。
- `Models.kt`：轻量 data class + `Json` 工具（org.json，不引入序列化库）。
  **所有进 UI 的模型都标 `@Immutable`** —— 让 Compose 认作稳定参数，避免整列重组。
- `Lyrics.kt`：**歌词解析与原文↔译文配对全部收口在这里**，`NcmApi.lyric()` 在 IO 线程一次
  产出 `List<LyricRow>`（`timeMs / text / translation`），UI 只渲染。三条硬规则：
  1. **禁止**"最后一条 `timeMs <= t` 的译文"这种匹配 —— 网易云常只翻副歌
     （玫瑰少年 72 行原文 / 5 行译文），那样最后一条译文会一路漏到歌尾，后半段全是同一句。
  2. 配对**一对一**：先按时间戳精确配对（绝大多数歌译文与原文时间戳完全相同），剩余按时间差
     升序贪心，容差 `TOLERANCE_MS = 700`（实测偏差最大 580ms；722ms 的"制作人↔下一句"必须落在外面）。
  3. **制作信息行**（`作词 / 作曲 / 编曲 / 制作人 / OP / SP / Lyrics / Written by…`，见 `CREDIT_KEYS`）
     两侧都不参与配对，否则开头那几行会把真正歌词的译文抢走（Yesterday Once More 实测）。
  解析侧还做：一行多时间戳展开、无时间戳/空文本行丢弃、`(time,text)` 去重、稳定升序。
- `Downloader`：按音质取地址 → 写系统「下载」目录（MediaStore，低版本回落 legacy 外部存储）；
  失败抛带原因的 `IOException`；**必须在 IO 线程调用**。
- `comments()`（歌曲评论，线程 id `R_SO_4_<songId>`）：**分页只能靠 `offset`**。
  实测 `pageNo` 被服务端忽略（第 1/2/3 页返回完全相同），`hasMore` 也不可信
  （16 万条评论的歌仍返回 `false`）→ 一律按「本页不足一页 = 到底」判断；
  热评 `hotComments` 只在 `offset=0` 时下发。游标翻页偶有重复条目，追加前按 `commentId` 去重。
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
- **异步响应过期判定（v1.0.3 修）**：`playIndex` 里歌词与播放地址两个 `scope.launch`
  必须同时校验 `currentIndex == index && current?.id == songId`。只判 index 会漏掉
  "队列被整体替换 / 拖拽排序 `moveInQueue` / 删除条目 `removeFromQueue` 后同一 index
  指向另一首歌"的情况，过期歌词响应被放行 → 连点切歌后"放的是这首、歌词是上首"；
  只判 id 又挡不住队列里同一首歌出现两次时连点两个位置。切歌瞬间还要
  `lyrics = emptyList()`，否则新歌加载期间显示的是上一首歌词。
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

- **歌词只有单一状态 `PlayerController.lyrics: List<LyricRow>`**（原文 + 已配对译文），
  配对在 net 层 IO 线程完成（见 §3 `Lyrics.kt`）；UI 侧不再有 `translatedLyrics`、
  也不再有 `remember` 缓存的对齐函数。
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
  上拉手势三条红线（v1.0.4 修过"有时拉不进播放页"）：
  ① `pointerInput` 必须排在 `clickable` **之前**（链上靠前的先拿事件，clickable 在前会把小幅拖拽当点击吃掉）；
  ② 位移累加**手势自身的增量**（`dragAccum`），禁止每帧 `launch { snapTo(offsetY.value + dy) }` ——
     读到的是上一帧旧值且并发 launch 乱序，松手时判定用的位置会落后手指；
  ③ 判定 = 位移 ≥ 80dp **或** 末次事件速度 ≥ 900dp/s 向上，缺一才回弹。
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
- 主页 ⇄ 子页面：`AnimatedContent` 用**带方向的推进/回退** —— 进入子页面 `slideInHorizontally(280ms, FastOutSlowIn) { it/3 } + fadeIn(200)`、
  主页同时 `slideOutHorizontally(240ms, LinearOutSlowIn) { -it/5 } + fadeOut(200)`，返回时方向相反。
  （旧版只有 `scaleIn(0.94)/scaleOut(0.97)` 缩放，两级页面之间没有方向信息，看不出"进了子页"。）
- **顶栏固定在滚动区之外**：主页与子页面都是 `Column { 顶栏 Row; Column(verticalScroll) { 内容 } }`，
  设置项变多往下滚时返回按钮不会跟着滚走；**设置主页也有返回按钮**（它是压栈进来的页面）。
- **分组不用多张卡，用卡内分隔线**：卡片保持每区一张，逻辑组之间插 `HorizontalDivider`
  （`thickness = 1dp`、两侧缩进 `row_padding_h`、色 `outlineVariant`）—— 即"与卡片等宽、上下细"的分割线。
- **Cookie 弹窗保持朴素**（输入 + 保存）；连接测试在账户页是一行，结果只有 `test_ok`（绿）/ `test_failed`（红）。
- **`rememberLauncherForActivityResult`（导出导入、Android 9 下载授权）必须能拿到
  `LocalActivityResultRegistryOwner`**：`MainActivity` 因语言切换把 `LocalContext` 换成了
  `createConfigurationContext` 的产物（不是 Activity），已在 `CompositionLocalProvider` 里
  `LocalActivityResultRegistryOwner provides this` —— 缺它抛 `No ActivityResultRegistryOwner was provided`，
  即「设置 → 数据」闪退的根因。
- **所有单选弹窗共用 `Components.SelectionDialog`**（标题 + 可滚动选项行 + 当前项主色 + 右侧对勾 + 取消），
  列表最高 48% 屏高，档位多的（音质 / 淡入淡出时长 / 下载音质）不会顶出屏幕。
  新增值型设置项**不要再手写** `AlertDialog + RadioRow`（那两个私有组件已删）。
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

### 5.5 评论面板（`ui/CommentSheet.kt`）

- 入口是播放页三点菜单的**第一项**；用 `ModalBottomSheet`（`skipPartiallyExpanded = true`）
  而**不压进返回栈** —— 下滑即回，播放页的歌词位置 / 进度 / 队列状态都不被打断。
- **两个排序 tab：最热 / 最新**（`NcmApi.comments(sort=…)`）—— 最热=`/api/v1/resource/hotcomments/R_SO_4_x`
  （顶层 `hotComments`，`hasMore` 可信）、最新=`/api/v1/resource/comments/R_SO_4_x`（顶层 `comments`，`more` 可信）；
  旧的 `/api/comment/resource/comments/get` 仅作参考实现保留。每个排序一个 `TabState`
  独立缓存与翻页，切走再切回不重复请求。
- 长评论默认 5 行截断，点击展开（单条互斥，`expandedId`）；**长按弹复制菜单**：
  复制全文（直接进剪贴板）或进入 `SelectionContainer` 的可选文本对话框拖选复制。
- **追评预览机制**：最热/最新两个 v1 接口的评论**不带 `replyCount`**（实测字段缺失），
  无法预知有没有追评，所以每条评论在组合时**自动拉一屏预览**（`commentFloors`，limit=3，失败静默）。
  `total == 0`（真的没有追评）→ **整个追评区不显示**；`total > 0` → 显示圆角预览框
  （「共 N 条追评」+ 前 3 条热门追评），底部按钮按 `hasMore` 切换：
  「展开追评」继续用 `time` 游标翻页 / 「收起追评」移除本地数据不发请求。
  **不要一次性把所有追评铺开**。
- 点赞是实心红心切换 + 数字增减，乐观更新失败回滚。
- 底部输入区只在**已填 Cookie** 时出现；点某条评论的「回复」进入追评模式（顶部显示
  「回复 @某人」+ 取消），发送走 `addComment` / `replyComment`，成功后重新拉首页。
- 昵称缺失（匿名评论）回落 `comments_anonymous`；时间用相对格式，7 天以上转本地日期。

### 5.6 布局令牌（`res/values/dimens.xml`）

- **结构性尺寸一律不写在代码里**：间距栅格 `space_xs/s/m/l/xl/xxl`（4 的倍数）、
  `page_padding`、`card_gap`、`card_radius`、`row_padding_h/v`、`list_bottom_padding`、
  各控件尺寸（`comment_avatar`、`about_logo`、`composer_max_height`、`dialog_list_max_height`…）
  全部取自 `R.dimen.*`（`dimensionResource(...)`）。
- 目的：① 不把布局常量烧进 dex，改一处全局生效，也能靠 `values-sw600dp/`、`values-land/`
  分屏适配而不动代码；② 这张表就是 Flutter 移植时的 Design Token 清单，直接抄成
  dart 常量 / `ThemeExtension`，不用去 Compose 里考古散落的 `12.dp`。
- 例外：动画数值走 `R.integer`（时长/位移比例，见 §9 待办）、纯装饰的一次性尺寸可保留字面量，
  但新增设置页/面板类结构时禁止再写 `dp` 常量。

### 5.7 资源与文案

- 全部文案进 `values/strings.xml`（en）+ `values-zh-rCN/strings.xml`（zh），**key 与中英一一对应**
  （当前各 242 条，可用 `diff <(grep -o 'name="…"' values/strings.xml|sort) <(… values-zh-rCN/…|sort)` 校验）；
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
| `PlayHistoryStore` | 听歌历史 id 列表 | 最新在前、去重、上限 50；内存里的 `playHistory` 与它同上限（v1.0.2 前内存截 20，导致"当次只显示 20、重启变 50"） |
| `PlaybackPositions` | 每首歌的播放位置 | 断点续播 |
| `SavedPlaylists` | songId → 收藏到的歌单集合 | 本地补全"收藏到任意歌单即点亮红心" |
| `SavedSongsCache` | 自建歌单歌曲 id 并集 | 服务端数据，TTL 5 分钟，写操作后 `invalidate()` |
| `SearchHistory` | 搜索关键词 | 去重、上限 20 |
| 设置备份 | `SettingsData.toBackupJson()` / `mergeBackup()` | 设置 → 数据 导出/导入 JSON；**刻意不含 Cookie**，导入时保留当前 cookie、缺字段沿用现值，`app` 标记不符直接判为非法文件 |
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

## 9. Flutter 移植准备（分层与可平移清单）

现在的分层就是按"UI 可整体替换"设计的，迁移时只重写 `ui/`，其余照搬：

| 层 | 现状 | Flutter 侧 |
|---|---|---|
| `net/NcmCrypto.kt` | weapi（双层 AES-128-CBC + RSA `encSecKey`）/ eapi 已实现 | `pointycastle` 或 `cryptography` 逐行平移，参数表在文件头注释里 |
| `net/NcmApi.kt` | 全部接口 + 请求姿势坑（csrf 取末个、`os=pc/appver`、评论 `offset` 分页…） | 直接当"接口规格说明书"用，本文件 §3 的坑一条都别丢 |
| `net/Models.kt` | 纯 data class + `org.json` 解析，不碰 Android API | 换成 `fromJson` / freezed 即可，字段表不变 |
| `net/Lyrics.kt` | LRC 解析 + 译文一对一配对，纯算法 | 直接重写单测平移 |
| `data/*` | SharedPreferences，key 即字段名 | 换 `shared_preferences`，注意 §6 的类型迁移规则 |
| `player/PlayerController.kt` | ExoPlayer + 单例状态 + 进度回路不变量 | 换 `just_audio`/`audio_service`，§4.2 的不变量必须逐条对照 |
| `ui/*` + `res/values/dimens.xml` | Compose | 全部重写；`dimens.xml` 当 Design Token 表抄成 dart 常量 |

移植前必须先补齐的三件事（避免 UI 逻辑继续往代码里漏）：

1. 动画常量（时长 / 位移比例 / 缓动）还没资源化的，收进 `res/values/integers.xml` + 一份映射表；
2. `NcmApi` 里剩余的中文异常文案资源化（§8.7），否则 Flutter 侧要重新扒一遍错误码；
3. `CommentSheet` / `AddToPlaylistDialog` 这类"面板内自带分页与乐观更新"的状态机，
   抽出成不依赖 Compose 的 plain class（现在已有 `CommentFeed` 雏形），迁移时直接换成 ChangeNotifier。
