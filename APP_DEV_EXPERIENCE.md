# Android 普通应用开发实战经验（Compose + Material 3）

> ⚠️ **本文是通用 Android/Compose 模板（「Example」模块）的经验沉淀，不是本项目规范。**
> 本项目（Melodrift Music）的工程规范以 [`SPEC.md`](SPEC.md) 为准；两者结论冲突时以 `SPEC.md` 为准，
> 冲突处已在对应小节标注「本项目实际做法」。
> 文中提到的 `LSP_MODULE_EXPERIENCE.md` 属于另一个 LSPosed 模块工程，**不在本仓库内**。

---

## 1. 工程与构建

### 1.1 环境与命令行构建

```bash
# 构建（工程目录内；Android Studio 之外的命令行方式）
ANDROID_HOME=/opt/android-sdk /opt/gradle-9.6.0/bin/gradle assembleRelease --no-daemon

# 验证签名
/opt/android-sdk/build-tools/36.0.0/apksigner verify app/build/outputs/apk/release/app-release.apk
```

- AGP 9.4.0 / Kotlin 2.4.10（compose 插件同版本）/ Compose BOM 2026.08.00
- **AGP 9.4 起必须 Gradle ≥ 9.6.0**，旧版 Gradle 直接报版本不满足（9.3.x 已不可用）
- `settings.gradle.kts`：阿里云镜像优先（google/central/gradle-plugin）+ `flatDir { dirs("libs") }` 支持本地 aar

### 1.2 aapt2 版本冲突（必踩）

```
android.aapt2FromMavenOverride=/opt/android-sdk/build-tools/34.0.0/aapt2
```

AGP 自带 aapt2 与 SDK 不匹配时会报资源编译错误；用 `build-tools` 里本机 aapt2 覆盖是**必要修复，别删**。

### 1.3 资源混淆（AGP 默认开启）

- shrunk+optimize 后 APK 里 `res/` 文件名会被改短（如 `res/BW.xml`、`res/Qr.xml`）
- **排查资源别用 `unzip -l | grep 名字`**，要用资源表：

```bash
/opt/android-sdk/build-tools/34.0.0/aapt2 dump resources app-release.apk
# 例：resource 0x7f080000 mipmap/ic_launcher；0x7f020004 color/ic_launcher_background #ff595d72
```

### 1.4 Release 构建

- `isMinifyEnabled + isShrinkResources` 建议都开
- 签名密码放 `gradle.properties`（不硬编码在 kts 里）；**密钥库不要进版本库**

---

## 2. Compose 主题与配色

### 2.1 动态取色 + 低版本回退

```kotlin
val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
} else {
    baselineColorScheme(darkTheme)   // Google Material 3 官方 baseline
}
```

> 📌 **本项目不采用动态取色**：Melodrift Music 固定品牌配色（浅色背景恒 `#F0F1F3`），
> 见 `SPEC.md` §5.3。以下结论适用于"要做动态取色"的模板工程。

- Android 12+ 走 Material You；**低版本不要自己推 HSL 配色**——直接抄官方 baseline 色板
  （`baselineColorScheme`：light primary `#6750A4` / dark primary `#D0BCFF`，design token 全套手抄进 `ColorSchemes.kt`，稳定且观感统一）

### 2.2 卡片对比度

- `surfaceContainerLow` 与背景（baseline 浅色 `#FEF7FF` vs `#F7F2FA`）几乎同色
- **改成 `surfaceContainerHighest`** 一眼可见，且语义色跟随动态取色/baseline 自动适配；比加描边干净

### 2.3 颜色动画坑

- 个别 Compose BOM 下 `animateColorAsState` 无法解析 → **自建 `animateColorWithLerp`**：

```kotlin
@Composable
private fun animateColorWithLerp(target: Color, spec: FiniteAnimationSpec<Float> = tween(300), label: String = "color"): State<Color> {
    val prev = remember { mutableStateOf(target) }
    val progress by animateFloatAsState(1f, animationSpec = spec, label = label)
    LaunchedEffect(target) { prev.value = target }
    return remember(target, progress) { derivedStateOf { lerp(prev.value, target, progress) } }
}
```

### 2.4 深色模式

- 手动开关控制（`settings.darkMode`），不跟随系统——用户可控性更好

> 📌 **本项目实际是三态**：`darkMode: String ∈ system / light / dark`，默认跟随系统（`SPEC.md` §5.3）。
> 从旧的 Boolean 迁移时要用 `sp.all[key]` 判实际类型，否则 `getString` 抛 `ClassCastException`。

---

## 3. 语言切换（本项目踩坑最多，直接给结论）

**目标**：运行中切换 中文 / English / 跟随系统，不重建 Activity、状态栏不动。

### 3.1 ❌ 方案一：`resources.updateConfiguration()`（废弃）

```kotlin
resources.updateConfiguration(config, resources.displayMetrics)  // 状态栏直接变白
```

直接替换 Activity 的 Resources 对象，破坏 Window/系统 UI 的资源配置引用 → 状态栏异常。**禁用**。

### 3.2 ❌ 方案二：切换后 `recreate()`（废弃）

```kotlin
LaunchedEffect(settings.language) { if (settings.language != "system") recreate() }
```

`LaunchedEffect` 每次重建后的首次组合都会执行 → `recreate()` 无限触发 = **Activity 死循环重建**（表现为"一直动/点不了/反复闪白"）。即使加"相对初始值"判断避免死循环，仍有一闪而过的退出重建。

### 3.3 ✅ 方案三：`CompositionLocalProvider(LocalContext)` 不重建（正解）

```kotlin
// ① 首次启动：attachBaseContext 挂目标语言（只换 locale，不动原 Resources）
override fun attachBaseContext(newBase: Context) {
    super.attachBaseContext(applyLocaleContext(newBase, readLanguage(newBase)))
}
private fun applyLocaleContext(base: Context, lang: String): Context {
    if (lang == "system") return base
    val locale = if (lang == "zh") Locale.forLanguageTag("zh-CN") else Locale.ENGLISH  // Locale("zh","CN") 已 deprecated
    val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
    return base.createConfigurationContext(config)
}

// ② 运行时切换：为整棵 UI 树提供带目标 locale 的 Context → Compose 即时重组
val localizedContext = remember(settings.language) {
    applyLocaleContext(this@MainActivity, settings.language)
}
CompositionLocalProvider(LocalContext provides localizedContext) {
    ExampleTheme(settings = settings) { /* 全部界面 */ }
}
```

- `stringResource` / `LocalConfiguration` 自动从新 Context 取资源 → 全 UI 换语言
- **无 recreate、无退出动画、无白屏，状态栏从始至终不动**
- 切换语言保存到 SharedPreferences，`LaunchedEffect(settings)` 兜底持久化

---

## 4. 编译坑速查表

| 坑 | 现象 | 对策 |
|----|------|------|
| `stringResource` 用在非 @Composable lambda（`clickable { }` 内） | 编译错 "Composable invocations can only happen…" | 先在 composable 内取变量再用 |
| `@Composable` 注解悬空在 `enum class` 前 | "This annotation is not applicable to target 'enum class'" | 注解必须紧贴函数 |
| 删 strings 没删代码引用 | Unresolved reference 'R.string.xxx' | 删除资源时同步 grep 代码 |
| `SettingsData` 加/删字段 | 读写不齐导致默认值丢失 | 同步改 `load()` + `save()` 三处 |
| 本地 aar 路径写死 | 换机/换目录编译失败 | `flatDir + files("libs/xxx.aar")` 相对工程目录 |

---

## 5. 图标

- minSdk ≥ 26 可直接用 adaptive icon（`mipmap-anydpi-v26/`），无需 legacy PNG
- 结构：`colors.xml` 背景色 + `drawable/ic_launcher_foreground.xml`（vector 前景）+ 自适应容器（含 `<monochrome>` 支持主题图标）
- **Manifest `<application>` 记得设 `android:icon` / `android:roundIcon`**（不设 = 系统默认图标）

---

## 6. 验证命令汇总

```bash
# 签名
apksigner verify app-release.apk
# Manifest 组件/属性（icon、alias、meta-data）
aapt2 dump xmltree --file AndroidManifest.xml app-release.apk
# 资源表（混淆后查资源映射）
aapt2 dump resources app-release.apk
# dex 硬编码中文检查
# ❌ 别用 `strings | grep -P '[\x{4e00}-\x{9fff}]'`：strings 只输出可打印 ASCII，
#    非 ASCII 序列被它吃掉，结果永远 0 命中（假阴性，给的是错误安全感）。
# ✅ 直接对二进制按 UTF-8 匹配：
unzip -p app-release.apk classes.dex | grep -cP '\p{Han}'
# 注意：命中数包含依赖库自带的中文，不等于"本应用 UI 有硬编码文案"；
#      本仓库实测 107 行命中，来源是 net/ 层异常文案与 亿/万 单位（见 SPEC.md §5.5 已知例外）。
```