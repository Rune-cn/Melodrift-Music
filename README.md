# Melodrift Music

网易云音乐第三方 Android 客户端。**Jetpack Compose + Material 3**，登录走网页版 Cookie，
接口为网易云 web 端（weapi 加密），播放基于 **androidx.media3 ExoPlayer**。

- 包名 `app.melodrift.music` · 当前版本 **1.0.2**（versionCode 3）
- `minSdk 26` / `targetSdk 36`（Android 16）/ `compileSdk 37`，仅打包 **arm64-v8a**
- 界面语言：中 / 英（应用内即时切换，不重建 Activity）

> 技术细节与规范见 [`SPEC.md`](SPEC.md)；设计任务书与播放器定案见 [`docs/`](docs/)。

## 功能

**首页**
每日推荐 · 推荐歌单 · 排行榜（飙升/新歌/原创/热歌）· 播放历史（本地持久化，点击续播）

**收藏**
我喜欢的音乐 · 收藏的歌单 · 创建的歌单（支持新建 / 删除 / 批量管理曲目）

**搜索**
歌曲 / 歌单 / 歌手三类结果；搜索历史（去重、最多 20 条、长按删除、一键清空）

**播放页**
封面页与歌词页左右滑动；旋转唱片；双语歌词滚动 + 边缘渐变；进度条支持标准 / 波浪两种样式；
迷你条上拉跟手预览、过阈值进入播放页。工具行含**音质**、**倍速**、**循环模式**（列表 / 单曲 / 随机）、
**定时关闭**；支持下载当前歌曲（按所选音质存入系统「下载」目录）。

**后台与控制**
前台服务（`mediaPlayback`）+ 通知栏 MediaStyle 控制 + `MediaSessionCompat`（耳机线控 / 系统媒体控制）。

**设置（四个板块）**

| 板块 | 项 |
|---|---|
| 账户 | 网易云 Cookie（含连接测试） |
| 播放 | 默认音质、淡入淡出（开关 + 时长）、允许与其他应用同时播放、恢复播放位置、**播放页显示状态栏** |
| 外观 | **深色模式（跟随系统 / 浅色 / 深色）**、语言、迷你条样式、进度条样式、歌词上下渐变 |
| 关于 | 版本、用户协议 / 隐私政策 / 免责声明、崩溃日志查看 |

## 构建

```bash
cd /workspace/melodrift-music
ANDROID_HOME=/opt/android-sdk /opt/gradle-9.6.0/bin/gradle assembleRelease --no-daemon
```

产物：`app/build/outputs/apk/release/app-release.apk`（R8 混淆 + 资源收缩 + 签名，约 2.2 MB）。

> ⚠️ AGP 9.4 起**必须使用 Gradle ≥ 9.6.0**，旧版 Gradle 会直接报版本不满足。

依赖版本一览在 `SPEC.md` 的「项目速览」。源码包内**不含密钥库**：缺密钥时 `assembleRelease`
仍会成功但产出**未签名** APK（`build.gradle.kts` 里做了存在性判断）。

## 签名（如何恢复）

源码分发时密钥库与密码会被移除，重新构建签名版需要放回：

1. 密钥库置于工程根目录：`melodrift.keystore`，别名 `melodrift`，证书 `CN=Melodrift Music`
   （RSA 3072，有效期 30 年）
2. `gradle.properties` 补回两行：

```properties
KEYSTORE_PASS=<密钥库密码>
KEY_PASS=<密钥密码>
```

3. 重新执行构建命令即可自动签名（v2 scheme）。

生成新密钥库的参考命令：

```bash
keytool -genkeypair -v -keystore melodrift.keystore -alias melodrift \
  -keyalg RSA -keysize 3072 -validity 10950 \
  -storepass <密码> -keypass <密码> \
  -dname "CN=Melodrift Music, OU=App, O=Melodrift, L=Internet, ST=Internet, C=CN"
```

> 换签名密钥后**无法覆盖安装**旧版，需先卸载（本地设置与缓存会丢）。

## 目录结构

```
melodrift-music/
├── README.md / SPEC.md                 # 本说明 / 技术规范
├── docs/                               # 设计任务书 + 播放器技术定案
├── settings.gradle.kts                 # 阿里云镜像仓库 + flatDir(libs)
├── build.gradle.kts                    # AGP / Kotlin compose 插件版本
├── gradle.properties                   # JVM 参数 / aapt2 覆盖 / 签名密码
└── app/
    ├── build.gradle.kts                # 编译配置、依赖、签名
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml         # 权限 + 前台服务 + Activity
        ├── kotlin/app/melodrift/music/
        │   ├── net/                    # NcmApi(接口) NcmCrypto(加密) Models(模型) Downloader(下载)
        │   ├── player/                 # PlayerController(全局播放器) MusicPlaybackService(前台服务/通知)
        │   ├── data/                   # Settings + 历史/进度/收藏/搜索历史等本地存储
        │   ├── ui/                     # Compose 界面（导航、各页面、主题）
        │   └── util/CrashLog.kt        # 未捕获异常落盘，供「关于」查看
        └── res/                        # 图标、通知图标、中英文案、主题、网络安全配置
```

## 使用前提

1. 设置 → 账户 → 粘贴网易云网页版登录后的 **Cookie**（须含 `MUSIC_U`）
2. 未登录时推荐 / 收藏 / 红心等账户相关接口不可用，但榜单与搜索仍可用
3. VIP 歌曲按服务端返回的音质降级播放（`songUrlSmart`）

## 已知限制

- 仅 arm64-v8a（不含 x86 / armeabi-v7a，模拟器需 arm64 镜像）
- 逐字歌词（KRC）未实现，当前为普通 YRC/文本歌词
- 媒体会话仍基于 `androidx.media` 的 `MediaSessionCompat`（该库 1.8.0 起整体标记废弃），
  迁移到 `androidx.media3.session` 是待办项，详见 `SPEC.md`「技术债」
