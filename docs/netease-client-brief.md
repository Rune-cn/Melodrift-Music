# 网易云音乐第三方客户端 · 新会话任务书

> 给新建会话 AI 的开场说明。已有逆向成果全部就绪，无需重新逆向。
> 技术栈已定：**Kotlin + Jetpack Compose（Android）**，外观**动态取色**，设置**分组分类**，
> 支持**中英双语**，不做检查更新。

## 0. 已有资产（直接复用，别重造轮子）

| 资产 | 位置 | 说明 |
|---|---|---|
| **API 客户端库** | `/workspace/netease/ncm.py` | `NCM` 类，60+ 方法，weapi+eapi 全封装 |
| 歌词模块 | `/workspace/netease/lyric.py` | LRC 解析 + 滚动定位（`line_at`/`visible_window`） |
| Cookie 配置 | `/workspace/netease/ncm_cookie.py` | 填入用户 cookie（`MUSIC_U`）即可用 |
| 接口文档 | `/workspace/netease/API_REFERENCE.md` | 130+ 接口：路径/参数/验证状态 |
| 逆向报告 | `/workspace/netease/README.md` | 加密机制/会员判断/实测结论 |
| 易盾方案 | `/workspace/netease/dun/` | 发评论 checkToken（实测空 token 可通，被风控才用 WebView） |

**NCM 类能力清单**（已实测可用）：
账户/会员判断、歌曲详情/歌词/播放地址/下载、歌单 CRUD/收藏/曲风歌单、搜索/建议、
评论读/发/回/删/赞、用户主页/关注/粉丝、艺人信息、排行榜、每日推荐、个性化推荐、
风格(曲风)推荐、eapi 用户详情、NOS 上传 token。

## 1. 技术栈（已定，勿改）

- **Kotlin + Jetpack Compose**（Android 原生，单端）
- 播放：ExoPlayer / Media3（支持后台、倍速、AudioFocus）
- 网络：逆向成果是 Python，需**用 Kotlin 重写 weapi/eapi 加密**（算法见 API_REFERENCE.md 与 ncm.py，两套已文档化）
  - weapi：双层 AES-CBC + RSA 小端打包（用 Java 内置 Crypto / BouncyCastle）
  - eapi：AES-ECB + MD5 签名 + 设备头（Java Crypto 即可）
- 协程 + Retrofit（或不引 Retrofit，直接 OkHttp + kotlinx.serialization）
- 图片：Coil（配合动态取色）

> ⚠️ 明确：`ncm.py` 是 Python 参考实现，Kotlin 端要照算法重写，但**所有接口路径/参数/响应结构直接照 API_REFERENCE.md 抄**，无需重新逆向。

## 2. 外观约束（重点：动态取色 + 高度自定义）

### 2.1 主题：动态取色（不要死板的网易黑 #191414）
- **主色从当前歌曲封面动态提取**：加载封面 Bitmap → 取主色/次色（如 AndroidX Palette 或自研平均色算法）→ 生成 Material3 colorScheme
- 过渡：切歌时颜色平滑渐变（AnimatedContent / animateColorAsState，300-400ms）
- 深色基底 + 封面色点缀；用户可在设置里选「跟随封面 / 固定色 / 跟随系统」
- 播放页、歌词页、进度条、迷你条、通知栏配色都跟随该主题

### 2.2 播放界面（最核心，务必精细）
布局分区（Compose 纵向）：
```
┌─────────────────────────────────────────────┐
│  顶栏：返回 / 歌曲名·歌手（垂直堆叠）/ 喜欢 / 更多(···)
├─────────────────────────────────────────────┤
│  中部（唱片区）：
│    - 封面形状可配：圆形 / 方圆（方形抹掉一点角，圆角很小）两种
│    - 旋转动画（速度=播放倍速同步，暂停则停）
│    - 封面圆角/是否旋转/显示歌词时是否隐藏 → 设置可配
│    - 手势：垂直拖拽唱片缩放、点封面切换 封面⇄歌词
├─────────────────────────────────────────────┤
│  歌词区（歌词视图）：
│    - 当前行居中 + 高亮（字号/颜色/是否加粗 → 设置可配）
│    - 上下预滚行数/淡出 → 设置可配
│    - 滚动用 line_at(currentTime) 驱动
├─────────────────────────────────────────────┤
│  进度条（高度自定义）：
│    - 样式可配：直线 / 波浪
│    - 拖拽 seek / 显示已播-总时长
│  控制排：上一首 / 播放暂停(大圆钮) / 下一首 / 倍速 / 循环模式
│  底部：音量 / 播放列表 / 定时关闭 / 音质 / 允许与其他应用同时播放
└─────────────────────────────────────────────┘
```

播放器底部 Bar（迷你播放条）：
- 形状可配：**直角方条** / **圆角悬浮胶囊** 两种
- 显示内容：封面 / 歌名·歌手 / 播放暂停 / 喜欢
- 是否显示 → 设置可配（见 3.2 ①）
- 播放页是否显示系统状态栏 → 设置可配（见 3.2 ①）

### 2.3 高度自定义清单（设置驱动，UI 参数化）
- **封面**：形状（圆形/方圆）、旋转开关、圆角、缩放比例、模糊背景开关
- **歌词**：字号（S/M/L）、当前行颜色、普通行颜色、是否加粗当前行、滚动速度、逐字高亮开关（有 klyric 时）
- **进度条**：样式（直线/波浪）、颜色跟随主题、拖动反馈（震动开关）
- **播放器底部Bar**：形状（直角方条/圆角悬浮）、是否显示
- **状态栏**：播放页是否显示系统状态栏
- 所有自定义项落到 settings（见第3节），UI 只读配置值

### 2.4 页面清单（MVP）
1. 每日推荐（默认+曲风切换 Tab）
2. 歌单广场（分类/精品/榜单入口）
3. 排行榜（toplist 63 榜）
4. 搜索（综合/歌曲/歌单/用户 Tab）
5. 我的（歌单/收藏/最近播放/喜欢的音乐）
   - 歌单区分**创建**与**收藏**两组展示（`subscribed` 字段：False=创建、True=收藏；`creator.userId` 双保险）
   - 「喜欢的音乐」歌单 = 收藏的歌曲集合，置顶展示
6. 播放页（见 2.2）
7. 用户主页（资料/歌单/关注/粉丝）

## 3. 设置中心（设置很多，必须分组分类）

### 3.1 分类展示原则
- 设置页 = 分组列表（Grouped List），每组标题 + 若干项
- 每项：图标 + 名称 + 当前值 + 点击进子页/弹选择
- 大分类 6 组，子项别超 8 个/组（超过拆子页）
- 全部项支持中英双语（字符串资源）

### 3.2 六大设置分组
```
① 外观与主题
   - 主题色：跟随封面 / 固定色 / 跟随系统
   - 深色模式：开关
   - 语言：中文 / English（全局生效，重启即时切换）
   - 唱片形状：圆形 / 方圆
   - 封面旋转：开关
   - 歌词字号：小/中/大
   - 进度条样式：直线 / 波浪
   - 播放器底部Bar：显示/隐藏 + 形状（直角方条/圆角悬浮）
   - 播放页显示状态栏：开关

② 播放设置
   - 默认音质：标准/高/无损/Hi-Res
   - 允许与其他应用同时播放：开关（关键，见下方音频焦点）
   - 倍速记忆：记住上次倍速
   - 淡入淡出：开关
   - 音量记忆：记住上次音量
   - 边下边听：开关

③ 歌词显示
   - 歌词偏移：±2s 微调（步进 0.5s，应对时间戳偏移的歌；默认0）
   - 显示翻译歌词：开关（有 tlyric 时）
   - 歌词字号（可并入①也可独立）
   - 当前行颜色 / 普通行颜色
   - 当前行加粗：开关
   - 滚动速度：慢/中/快
   - 逐字高亮：开关（有 klyric 时才生效，多数歌无则自动回退行级）
   - 锁屏显示歌词：开关（移动端）

④ 通知与后台
   - 后台播放：开关（关键！前台服务 MediaSession）
   - 锁屏控制：开关
   - 通知显示：歌名/歌手/无
   - 耳机线控：开关

⑤ 下载与存储
   - 下载目录：路径选择
   - 下载音质：标准/高/无损
   - 仅 Wi-Fi 下载：开关
   - 缓存上限：256M/512M/1G/不限制
   - 清理缓存：显示占用 + 一键清

⑥ 账户与隐私
   - 当前账号：头像/昵称/会员（黑胶VIP等）
   - 退出登录
   - 隐私：显示真实 IP？/ 允许被搜到？
   - 清除搜索历史
```

### 3.3 关键：允许与其他应用同时播放（音频焦点）
- 实现：Media3/ExoPlayer 播放器不请求独占 AudioFocus，或请求时带
  `AudioFocusRequest.Builder(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)` 且**不真正夺焦**
- 开启时：本 App 播放不打断其他 App（音乐/视频/播客可混播）
- 关闭时：默认行为（请求独占焦点，冲突时暂停）
- 该开关要实时生效（切换时重建/更新播放器的焦点策略）

### 3.4 设置项数据结构（模块化）
```kotlin
// settings 统一 key-value（DataStore Preferences）
data class SettingItem(
    val key: String,
    val title: Int,        // 字符串资源 id（中英由资源切换）
    val type: SettingType, // TOGGLE / SELECT / SLIDER / PATH / INFO / BUTTON / SUBPAGE
    val value: Any? = null,
    val options: List<Pair<Int, Any>> = emptyList(), // (标签资源id, 值)
    val onAction: (() -> Unit)? = null
)
data class SettingGroup(val title: Int, val items: List<SettingItem>)
```
- 渲染层只认 `SettingGroup[]`：加设置 = 加一条数据，不改 UI 代码
- 语言切换：全局 Strings 资源 + 观察 DataStore，UI 自动刷新

## 4. 模块化架构（便于维护）

```
app/src/main/java/<pkg>/
├── api/              # 网络层：Kotlin 重写的 weapi/eapi 加密 + NCM 客户端 + 各资源 API
│   ├── crypto/       # WeapiCrypto.kt / EapiCrypto.kt（照 ncm.py 算法翻译）
│   ├── NcmClient.kt  # 单例，注入 cookie，封装 _req/eapi
│   └── SongApi.kt / PlaylistApi.kt / SearchApi.kt / CommentApi.kt / UserApi.kt ...
├── data/             # 数据模型（照 API_REFERENCE 响应字段）+ DataStore settings
│   └── SettingsRepo.kt   # key-value 读写，暴露 Flow
├── player/           # 播放核心（UI 无关）
│   ├── PlayerCore.kt     # Media3 封装 play/pause/seek/rate/focus 策略
│   └── AudioFocusManager.kt
├── ui/               # Compose UI
│   ├── theme/        # 动态取色 colorScheme（封面→Palette→ColorScheme）
│   ├── components/
│   │   ├── player/   # MiniBar / FullPlayer（读 PlayerCore 状态）
│   │   ├── lyric/    # LyricView(lyricText, currentTime) 纯展示
│   │   ├── settings/ # SettingsScreen(SettingGroup[]) 纯渲染
│   │   └── common/   # 封面/列表项/加载/空态
│   └── screens/      # 推荐/广场/排行/搜索/我的/播放/用户主页
└── util/             # 时长格式化/单位/取色工具
```

### 关键解耦约定
1. **PlayerCore 与 UI 解耦**：迷你条/全屏/通知/耳机都操控同一播放状态机（StateFlow）
2. **Lyric 与 Player 解耦**：LyricView 只收 `(lyricText, currentTime, 样式配置)`，不关心播放来源
3. **Settings 渲染与业务解耦**：渲染器读 `SettingGroup[]`，新增设置=加数据
4. **api 层薄**：只做请求映射 + 加密，业务逻辑在 data/ui 层
5. **Cookie 独立**：NcmClient 单例注入，别散落

## 5. 给新会话 AI 的第一句话（可直接粘贴）

```
你在做一个网易云音乐第三方 Android 客户端（Kotlin + Jetpack Compose）。
逆向成果已就绪，不要重新逆向：
- 接口文档 /workspace/netease/API_REFERENCE.md（130+接口：路径/参数/响应/验证状态）
- 加密参考 /workspace/netease/ncm.py（weapi/eapi 两套算法，用 Kotlin 照译）
- 歌词模块参考 /workspace/netease/lyric.py
- Cookie 填 /workspace/netease/ncm_cookie.py（App 内做登录也行）
任务按 /workspace/netease-client-brief.md 执行：
1) 先搭项目骨架（第4节模块化结构）+ Kotlin 重写 weapi/eapi
2) 实现播放界面（第2.2节，外观重点，动态取色 + 高度自定义）
3) 实现设置中心（第3节六组分类，含“允许与其他应用同时播放”）
4) 支持中英文（Strings 资源），不做检查更新
5) 调接口前先看 API_REFERENCE.md；写操作用文档已列方法
```

## 6. 迭代顺序建议
1. 项目骨架 + Kotlin 重写 weapi/eapi（先跑通 account()）
2. 动态取色主题 + 播放核心 + 全屏播放页（外观重点）
3. 搜索 + 歌曲页 + 歌词滚动（高度自定义参数化）
4. 每日推荐 + 歌单广场 + 排行榜
5. 设置中心（六组分类 + 中英文 + 音频焦点开关）
6. 评论/用户主页
7. 易盾降级（被风控才做）

## 7. 关键技术决策（已定案，见 player-tech-decisions.md）

以下技术点已定稿，直接照做，不再讨论：

0. **歌词对齐机制**（实测+定案）：网易云返回标准 LRC（`[mm:ss.xxx]` 毫秒时间戳），行级对齐 = 播放器 `currentPosition` 查表定位（毫秒级准确）。多数歌无 klyric，逐字高亮不能当卖点，有则高亮、无则回退行级。
   - **偏移记忆**：Room 表 `LyricOffset(song_id, offset_ms)`，加载先查表累加；不做自动校准
   - **中途漂移**：250ms Spring 动画平滑补正到目标行，不用 scrollTo 硬跳
   - **分场景降级**：纯音乐→居中弱文案；残缺时间戳(>80%全0)→文本模式可滑动+弱提示；间奏间隔>12s→自动插 `•••` 虚拟行防"以为卡死"
   - 必须提供**歌词偏移设置（±2s）**应对部分歌时间戳整体偏移

1. **音频焦点**：做「独立播放」开关，默认请求标准焦点；开启「允许与其他应用同时播放」= 完全不请求焦点 + `USAGE_MEDIA`。不用 MAY_DUCK。
2. **歌词滚动**：普通歌词（≤300 行）LazyColumn + derivedStateOf；**逐字高亮（Krc）用 Canvas + StaticLayout + LinearGradient Shader**（一行一个 StaticLayout，改 Paint Shader 只走 Draw 不重排，稳 120Hz）。MVP 先 LazyColumn，Krc 时切 Canvas。
3. **波浪进度条**：分段采样（4~6dp/点）+ quadraticTo 贝塞尔连接；进度用 clipRect 裁剪两周期 Path；**拖动时停止/阻尼相位动画，松手 200ms Spring 恢复**。
4. **动态取色**：异步 + 封面缩 50x50 + maximumColorCount(8)；`LruCache<songId, colors>(20)` 二级缓存；取色失败用兜底色；`animateColorAsState(400ms LinearOutSlowIn)` 防闪烁。
5. **旋转唱片**：角度**不绑播放进度**（独立无限动画），seek 时暂停、播时续转、倍速乘增量、暂停 200ms 平滑停；「不旋转」开关=静态封面。
6. **登录态**：**仅填 Cookie 注入**（MUSIC_U + __csrf 写入 DataStore/EncryptedSharedPreferences）。不做二维码/密码登录。冷启动校验 cookie 有效性（account 接口），失效则提示重新填。不做登录接口逆向。
7. **播放 URL**：有效期约 20min~2h；403 时 `onPlayerError` 静默重取 + replaceMediaItem；当前曲 80%/剩余<30s 预取下一首 + CacheWriter 预拉 1MB 秒开。
8. **下载**：断点续传（Range + RandomAccessFile 追加）+ 2~3 并发 + 指数退避重试 3 次；无损多线程拉取有 CDN 封 IP 风险。
9. **VIP 降级**：已下载明文本地可播；重取 URL 自动降音质 + 弱提示"已切换标准音质"。
10. **缓存**：Download/Cache 目录分离；SimpleCache LRU 512MB；**损坏自愈 deleteRecursively 重建**。
11. **写操作限频**：点赞 Debounce 800ms；评论 Throttle 置灰 5s（阈值真机调）。
12. **断网**：NetworkCallback 监听；移动网络弹窗询问；断网自动续播；下载挂起/恢复。
13. **eapi 设备头**：**随机持久化指纹**（UUID+机型库+DataStore 持久化），禁固定假 ID（批量风控）；用响应头 Date 修正时间偏移。
14. **eapi clientSign**：已排雷——格式 `MAC@@@hex(deviceId)@@@@@@sha256(前段+secret)`；参考实现里被注释、实测接口全部无需；遇 400 再逆向。

**真机验证项**：
- 音频焦点方案中"不请求焦点是否影响 Android 14+ FGS 启动"（先用标准 Media3 方式，遇问题再补）
- `login/refresh` 与二维码接口（qr/key、qr/check）的真实路径与响应
- 写操作风控阈值（评论<10s、点赞<1s）

## 8. 待定项（开发时按此优先级）
- Krc 逐字歌词的 Canvas 渲染（实现 Krc 时才做）
- 音频焦点 FGS 真机验证

## 9. 验收清单
- [ ] 播放页：旋转唱片 + 歌词滚动 + 进度 seek + 倍速 + 定时关闭 + 循环模式
- [ ] 主题随封面动态取色，切歌平滑过渡
- [ ] 封面（圆形/方圆）/ 歌词 / 进度条（直线/波浪）/ 底部Bar（直角/圆角悬浮）四类高度自定义（设置驱动，UI 参数化）
- [ ] 播放页显示/隐藏系统状态栏 开关生效
- [ ] 设置中心六组分类；语言中英切换即时生效
- [ ] 「允许与其他应用同时播放」开关生效（开=不打断他App）
- [ ] 后台播放 + 通知控制 + 耳机线控
- [ ] 模块化：player/lyric/settings 三处可独立替换
- [ ] 断网/切歌/下一首不崩溃
