# 播放器技术决策与风险清单（已定案 v2）

> 用途：架构评审 / 技术排期 / 新会话 AI 直接照做。
> 状态：🟢=已定案可直接实现 ｜ 🟡=待验证/有保留 ｜ 🔴=需再讨论
> 来源：网易云第三方客户端（Kotlin + Compose + Media3）。两轮讨论（含工业级方案）后定稿。

---

## 1. 音频焦点：允许与其他应用同时播放 —— 🟢 已定案

**方案**：不做三态，做**「独立播放」开关**。
- 默认：请求标准焦点 `AUDIOFOCUS_GAIN`（最安全，符合规范，不被判恶意后台）
- 开启「允许与其他应用同时播放」：**完全不请求焦点**，AudioAttributes 用 `USAGE_MEDIA`，系统当纯音轨叠加
- **不用 MAY_DUCK**：国内 OEM（小米/华为）实现恶劣——有的当 GAIN 把别人停掉，有的把本 App 音量压到听不清

**⚠️ 待验证（对方 AI 的说法）**：
对方称"Android 14+ 不请求焦点会导致 mediaPlayback FGS 被拒，需要用短暂占位音频/空 Focus 监听欺骗系统"。
**我的判断**：此说法**存疑**——Media3 前台服务启动不依赖音频焦点，该"欺骗"手段可能引发反效果（被判异常）。**建议按标准方式实现**：FGS + MediaSessionService 正常启动，独立播放只是"不调 acquireAudioFocus"。真机验证后再决定是否需补充处理。

**风险提示**：不请求焦点时，本 App 感知不到他人抢焦点（无法做淡出）。对"同时播放"需求可接受。

---

## 2. 歌词：渲染方案 + 对齐修正/降级 —— 🟢 已定案

### 2A. 渲染方案（分级）
- **普通歌词（≤300 行）**：LazyColumn + `derivedStateOf`，够用、可维护
- **逐字高亮（Krc 卡拉OK）**：必须 Canvas，否则高频重组必卡
  - 用 `android.text.StaticLayout`（不手写测量）
  - 只遍历计算可见的 5~7 行，`canvas.nativeCanvas.save()` + `translate(0, currentY)` + `staticLayout.draw()`
  - 逐字高亮**不用拆 Text**：一行 = 一个 StaticLayout，给 Paint 设 `LinearGradient`
  - 渐变色标四点 `[已播色, 已播色, 未播色, 未播色]`，按字进度算分割点 X
  - 高频只改 Paint 的 Shader，**只走 Draw 阶段不触发布局重绘**，稳 120Hz
- **MVP 策略**：普通歌词 LazyColumn 起步；Krc 逐字高亮预留 Canvas 渲染接口，实现 Krc 时切换

### 2B. 对齐机制
网易云返回标准 LRC（`[mm:ss.xxx]` 毫秒时间戳），行级对齐 = 播放器 `currentPosition` 查表定位（毫秒级准确）。多数歌无 klyric，逐字高亮"有则做、无则回退行级"，不当卖点。

### 2C. 偏移记忆（做）
Room 表 `LyricOffset(song_id PK, offset_ms)`。加载歌词先查表，有记录则累加 baseOffset 到整首 LRC 时间戳。**不做自动校准**（音频指纹 ROI 低，无标准时间轴可比对）。

### 2D. 中途漂移（平滑补正，非强制跳）
- 触发下一行时间戳但漂移未到 → 不用 scrollTo 硬切换
- 计算当前滚动位与目标位差值 → 250ms Spring/Tween 动画平滑"滑/淡"到正确位置
- 可选进阶：解析时对稀疏时间轴做动态插值平摊漂移（默认不做）

### 2E. 分场景降级（绝不让播放页"秃头"或满屏 [00:00.00]）
- **纯音乐**：居中弱化一行"纯音乐，请欣赏 / Let the music speak..."，关闭滚动与歌词控制
- **残缺时间戳**（>80% 行以 [00:00.00] 开头，或正则 `\[\d{2}:\d{2}\.\d{2,3}\]` 匹配失败）：降级**文本模式**——放弃时间轴驱动，整篇可自由上下滑动，高亮停在首行，顶部弱提示"当前歌词无时间戳，已开启文本模式"
- **大段间奏**（`Time(N+1)-Time(N) > 12s`，阈值可调常量）：自动插入虚拟行，时间戳 `Time(N)+2s`，内容 `•••`，高亮自动切到三个点暗示"间奏中"，防误以为卡死

---

## 3. 波浪进度条 —— 🟢 已定案

**方案**：
- **分段采样**：横向每 4~6dp 采一个点，`Path.quadraticTo`（贝塞尔）连接，点数降 80%
- **裁剪代替重绘**：画两个完整周期波浪 Path，进度用 `clipRect(0,0,progressX,height)` 裁剪（左=已播色，右=未播色），不重画 Path
- **Seek 表现**：手指拖动时**停止相位自增或阻尼降速**（绝不能保持原速荡漾，叠加触摸事件必卡）；松手后 200ms 弹性动画（Spring）恢复摆动

---

## 4. 动态取色 + 切歌过渡 —— 🟢 已定案

**方案**：
- **异步**：`withContext(Dispatchers.Default)`，绝不在主线程 `Palette.generate()`
- **缩图**：封面先 `createScaledBitmap` 压成 50x50 再取色
- **限色**：`Palette.from(mini).maximumColorCount(8).clearFilters()`
- **二级缓存**：全局 `LruCache<String, AlbumColors>(maxSize=20)`，key=songId，切回历史歌秒出
- **兜底色**：取色失败/进行中 → 用上一首歌颜色或标准深灰，不显示空白
- **渐变防闪**：`animateColorAsState(target, tween(400, LinearOutSlowInEasing))`，颜色跳变视觉上丝滑晕染

---

## 5. 旋转唱片（含"不旋转"设置）—— 🟢 已定案

**方案（相对角度叠加法）**：
- 唱片角度**不绑 currentPosition**（绑了会 seek 时螺旋桨暴转）
- 用无限动画控制器驱动（`rememberInfiniteTransition` 的 animateFloat 0→360）
- **Seek 时**：动画原地暂停（保持当前角度）
- **Seek 结束**：音乐在播 → 从暂停角度继续；倍速 → 每帧增量 `deltaAngle * rate`
- **暂停**：`animateTo(target, tween(200))` 平稳减速到 0
- **「不旋转」设置**：开关关闭 → 动画直接停住，显示静态封面（不透明度/阴影保持）
- 由于角度与进度解耦，seek 后天然平滑，无需回补

---

## 汇总状态

| # | 主题 | 状态 | 备注 |
|---|---|---|---|
| 1 | 音频焦点 | 🟢 | 独立播放开关；"FGS 被拒/欺骗"说法**待真机验证** |
| 2 | 歌词（渲染/对齐/降级） | 🟢 | 2A~2E 全套定案 |
| 3 | 波浪进度条 | 🟢 | 采样+裁剪+拖动阻尼 |
| 4 | 动态取色 | 🟢 | 异步+缩图+缓存+渐变 |
| 5 | 旋转唱片 | 🟢 | 解耦角度；不旋转=静态封面 |
| 6 | 登录态 | 🟢 | 仅填 Cookie（简化）；歌单 subscribed 区分归属 |
| 7 | 播放 URL 生命周期 | 🟢 | 403 静默重取 + 预取秒开 |
| 8 | 下载管理器 | 🟢 | 断点+并发+限速+重试 |
| 9 | VIP 过期降级 | 🟢 | 本地播+自动降音质 |
| 10 | 缓存管理 | 🟢 | 目录分离+LRU+损坏自愈 |
| 11 | 写操作限频 | 🟢 | Debounce/Throttle；⚠️ 阈值真机调 |
| 12 | 断网/网络切换 | 🟢 | 自动续播+队列挂起恢复 |
| 13 | eapi 设备头 | 🟢 | 随机持久化指纹（关键修正） |

**遗留待真机验证**：
- #1"不请求焦点是否影响 FGS 启动"
- #11 写操作风控阈值

---

## 6. 登录态 —— 🟢 已定案（简化：仅填 Cookie）
- **方案**：仅 Cookie 注入（MUSIC_U + __csrf），写入 DataStore/EncryptedSharedPreferences。不做二维码/密码登录，不做登录接口逆向。
- **校验**：冷启动 account 接口验证 cookie 有效性；失效则提示重新填写
- **歌单归属**：`user/playlist` 用 `subscribed` 字段区分创建(False)/收藏(True)，`creator.userId` 双保险；「喜欢的音乐」= 收藏的歌曲集合

## 7. 播放 URL 生命周期 —— 🟢 已定案
- URL 带 wsSecret/wsTime，有效期约 **20min~2h**（随音质/CDN 变化）
- **播放中失效静默重取**：监听 `onPlayerError`，捕获 `ERROR_CODE_IO_BAD_HTTP_STATUS`(403) → 协程取新 URL → `replaceMediaItem` + `prepare()` + `play()`，用户无感知（短暂缓冲）
- **两端预取**：当前曲 80% 或剩余<30s 时异步取下一首 URL；再用 `CacheWriter` 预拉前 512KB~1MB（音频头+首帧），实现切歌秒开

## 8. 大文件下载管理器 —— 🟢 已定案
- 架构：UI → DownloadRepository → Room DB(状态/进度) + OkHttp + Coroutine Worker
- **断点续传**：下载前 HEAD/GET 带 `Range: bytes=<current>-`，`RandomAccessFile` 追加写入（网易 CDN 支持）
- **并发/限速**：最大 2~3 个并发任务，单任务不分块并发（防风控）；限速用 OkHttp Interceptor + 协程 delay 令牌桶
- **失败重试**：指数退避 1s→2s→4s，单任务最多 3 次，超限转"失败"由用户手动重启
- **风险**：无损多线程拉取可能触发 CDN 封 IP 15 分钟

## 9. VIP 过期降级 —— 🟢 已定案
- **已下载本地无损**：明文 .flac 本地直接播，不依赖接口（账号未切换即可）
- **重新拉 URL**：过期后 lossless 可能报错或返回 standard / 试听片段（freeTrialInfo）
- **自动降音质**：解析响应，若请求音质未返回则取最高可用音质 URL，播放页弱提示"VIP已过期，已自动切换为标准音质"

## 10. 缓存管理 —— 🟢 已定案
- **目录分离**：Download（用户明确下载，长期保留，Room 索引）vs Cache（SimpleCache 临时分片）
- **上限/淘汰**：`LeastRecentlyUsedCacheEvictor(512MB)`，Media3 自动 LRU 淘汰，不写定时器
- **损坏自愈**：`new SimpleCache` 用 try-catch 包裹，捕获 CacheException → `cacheDir.deleteRecursively()` 清空重建（索引损坏用空间换稳定）

## 11. 写操作限频（防风控）—— 🟢 已定案
- **点赞/红心**：防抖 Debounce——UI 立即变，800ms 后才发最终状态
- **评论/收藏**：节流 Throttle First——点击立即置灰 + 5s 倒计时
- ⚠️ 具体阈值（评论<10s、点赞<1s 触发 405）为经验值，需真机调

## 12. 断网与网络切换 —— 🟢 已定案
- `ConnectivityManager.NetworkCallback` 监听网络变更
- WiFi→移动：若开启"非Wi-Fi提示"，`player.pause()` + Compose 弹窗询问，允许则恢复
- 彻底断网：进入"等待网络恢复"态；网络重连后自动 `prepare()` + `play()` 续播
- 下载队列：断网批量挂起 `PAUSED_WAITING_FOR_NETWORK`，恢复自动重启，无需手动

## 13. eapi 设备头（唯一标识）—— 🟢 已定案（关键修正）
- **禁止**：固定假 ID（所有账号同指纹 → 批量封号/400）
- **随机持久化指纹**：首启生成 UUID deviceId + 机型库（小米13/一加11/三星S23等）随机 brand/model + 算法模拟 mac/androidId → 存 DataStore/EncryptedSharedPreferences，**卸载前永久固定**
- **时间同步**：接口带时间戳/哈希时，用响应头 `Date` 动态修正服务器时间差（偏差>5min 接口失效）

## 14. eapi clientSign —— ✅ 已排雷（当前接口无需）
- **机制已破解**：格式 `MAC@@@hex(deviceId)@@@@@@sha256(前段 + secretKey)`，deviceId 为 8 段十六进制（如 `E823_8FA6_BF53_0001_001B_444A_46C6_3683`）
- **结论**：NeteaseCloudMusicApi 的 request.js 里 clientSign **被注释掉**（`// clientSign:`），即官方参考实现都不发它；我们实测的 weapi/eapi 全部接口（含评论/用户详情/歌单）**都不带 clientSign 即通**
- **secretKey 未知**：官方值 `sha256(base+secret)` 匹配不上空 secret，但**不影响使用**（因为接口不要求）
- **后续策略**：若某写操作接口意外返回 400，再逆向该接口的 secret；当前不用管
