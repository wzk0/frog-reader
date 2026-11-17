# AGENTS.md — 可复用的工程特性与约定

这份文件写给「下一个 App」：本项目里值得照搬的东西（UI 体系、动画、数据持久化、CI、签名、发版、踩过的坑），
以及照搬时必须改的地方。`ARCHITECTURE.md` 讲本 App 的内部原理，`design-qa.md` 讲一次 UI 评审的过程，
这里只讲**可迁移的做法**。

- 本仓库：`github.com/wzk0/frog-reader`（上游 `KNITIPKA/frog-reader`，GPL-3.0-only）
- 包名 `github.wzk0.frogreader`，namespace `com.example.frogreader`（namespace 与包名解耦，改包名不用动 Kotlin 包路径）
- 纯离线阅读器 + 一个例外：字体预设下载（唯一的网络请求）

---

## 1. 工具链与版本（新项目照抄这一组能跑）

| 项 | 值 |
|---|---|
| AGP | 9.4.1（内置 Kotlin 编译，`kotlin-android` 插件不再需要） |
| Kotlin | 2.4.20（compiler plugins: compose、serialization） |
| Gradle | wrapper 9.7.0，`org.gradle.configuration-cache=true`、`parallel=true` |
| Compose BOM | 2026.09.00，Material3 **1.5.0-alpha29**（高于 BOM，为了 Expressive API） |
| SDK | compileSdk 37（用 `compileSdk { version = release(37) }` 新 DSL）、targetSdk 36、minSdk 26 |
| Java | 源/目标 11；构建用 JDK 17 或 21（CI 用 21） |
| 其它 | kotlinx.serialization、Coroutines、Coil 3（含 svg/gif）、navigation-compose、DataStore、Glance（桌面小组件）、WorkManager、CameraX、Brotli、jsoup、commonmark |

**依赖集中在 `gradle/libs.versions.toml`**，`app/build.gradle.kts` 只引 alias。升级 Material3 的 Expressive
API 时需要 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`，本项目一律在文件级或函数级显式标注。

---

## 2. 常用命令

```bash
./gradlew :app:compileDebugKotlin                 # 最快的语法检查
./gradlew :app:testDebugUnitTest                  # JVM 单测（本项目 1951 项，约 20 s）
./gradlew :app:testDebugUnitTest --tests "com.example.frogreader.ui.LibrarySortTest"
./gradlew :app:assembleDebug                      # 调试包：app/build/outputs/apk/debug/
./gradlew :app:lintDebug                          # 首次约 5 min，之后增量很快
. ./release-signing.env && ./gradlew :app:assembleRelease   # 签名 release（见 §8）
```

只有 debug 单测变体存在（`:app:testReleaseUnitTest` 不存在，release 开了 R8 优化），
所以 `./gradlew test` 与 `:app:testDebugUnitTest` 等价。

**16 GB 内存的机器上必须降堆**，否则 Gradle/Kotlin/R8 两个 4 GB daemon 会把系统内存吃光被 OOM kill
（本机 `gradle.properties` 是 `-Xmx4096m` + `-Xmx4096m`）：

```bash
./gradlew <tasks> -Dorg.gradle.jvmargs="-Xmx2560m" -Dkotlin.daemon.jvmargs="-Xmx1536m" --no-parallel
```

`assembleRelease`（R8）用 `-Xmx3072m`，并且先 `./gradlew --stop` 清掉旧 daemon。

---

## 3. UI 体系

### 3.1 主题
- `ui/theme/Theme.kt`：`AppTheme { WHITE, SEPIA, OLED }`，另有 `followSystemTheme` + `lightThemeDefault { LIGHT, BEIGE }`
  推导出「跟随系统」的浅色主题；Material You 取色只作用于 Light/Midnight，米色永远保持暖色调。
- `LocalFrogColors: FrogExtraColors`（`staticCompositionLocalOf`）补上 Material3 表达不了的「语义角色」：
  `headerTop/headerBottom`（渐变头部）、`glass`（头部上的搜索框/齿轮）、`chip`/`pill`/`pill60`（进度槽
  与封面角标）、`lift`（拖拽遮罩）、`nav`（底部胶囊）、`folder`、`completionFill/completionInk`（读完的金色）、
  `ink/ink2`（彩色头部上的主/次文字）、`profileTop/profileBottom/onProfile`。组件一律读它，
  不写死颜色，也不去猜 `surfaceVariant` 的语义。
- 阅读页另有一层 `ReaderColors`（`ui/reader/ReaderColors.kt`）：纸面 `background/text/secondaryText`、
  控件面 `chrome/control/controlButton/track`、动作 `accent/onAccent/selectedControl/progress`、`quoteHighlight`。
  阅读面是「不跟随 App 主题、只跟随阅读主题」的一层，所以它必须独立。
- `ColorScheme.floatingSurface`（`Theme.kt` 扩展）：浮动面板底色；暗色/亮色下都配 1 dp `outlineVariant`
  描边，保证和阅读纸面区分开。
- `AnimatedColorScheme.kt`：换主题时颜色插值过渡，避免整屏闪一下。

### 3.2 组件（`ui/components/`，直接可搬）
- `FrogSegmentedControl`：带滑动指示器的分段控件，几何计算抽在 `SegmentedControlGeometry.kt`（可单测）。
- `FrogSnackbar`：自绘 snackbar，`FrogSnackbarHost`。
- `PagerPillInteraction`：翻页胶囊的手势/回弹。
- 业务侧：`MorphingButton`（宽度/形状随内容形变）、`GlassIconButton`、`FrogSearchField`、`SectionLabel`、
  `SelectionCheck`，以及 `SettingsRow/SettingsSwitchRow/SettingsControlRow`
  （`ui/settings/SettingsComponents.kt`，开关行自带 ToggleOn/Off 触感、`Switch(onCheckedChange = null)`）。

### 3.3 尺寸与排版规则（来自 design-qa.md，实测过的）
- 圆角：列表卡 20 dp、行内小卡 14–18 dp、面板 22–26 dp、对话框 28 dp、导航外框 30 dp / 选中 24 dp。
- 底部导航默认 72 dp 高，FAB 60 dp、20 dp 圆角、距导航 12 dp；两者共享一行、居中、横向内边距 20 dp。
- 小标题：12 sp / Medium / letterSpacing 1.2 sp / 全大写（中文大写无副作用，保留同一套样式）。
- 头部是 grid 的第一个 item 并随滚动消失——所以「顶栏」永远不要做成 fixed AppBar；
  选中态用同一个 section row 变身（换控件，不换行高）。
- **行高固定**：`SectionRowHeight = 32.dp` 之类，因为区域在普通态和选中态换控件时高度不同，会让整个网格跳一下。
- 满宽 header 用 `Modifier.bleedHorizontally(SidePadding)` 抵消 grid 的 contentPadding（`layout {}` 手写）。
- 极窄空间里的文字用 `TextAutoSize.StepBased(min, max, step)`，别让它换行。
- 所有图标按钮都有 `contentDescription`；纯装饰图标传 `null`。
- 中文长文本：`maxLines` + `TextOverflow.Ellipsis` 是默认选择，不要靠 `wrapContent` 撑高。

### 3.4 列表/网格
- 网格与列表的切换**不换 item span，只换列数**（`GridCells.Fixed(3)` ↔ `Fixed(1)`）。
  同一个 key 的 item 若 span 变化，LazyGrid 会报 “Place was called on a node which was placed already”。
- item key 用稳定的业务 id（`"b:<bookId>"` / `"s:<shelfId>"` / `"f:<folder>"`），这样切模式时图片节点、remember 都存活。
- 分组标题这类「整行」元素也走 `items(..., span = { it is Header })`，span 与模式无关。
- 长按超时用 `LocalViewConfiguration` 委托覆盖（`combinedClickable` 没有超时参数），
  本项目用 260 ms 而不是平台默认 500 ms：网格上长按的目的是菜单，500 ms 像卡顿。
- 列表项入场动画只对「刚刚创建的」元素分配 `Animatable`/`LaunchedEffect`，其余直接 `1f`，滚动时不额外分配。

---

## 4. 动效与触感（最容易踩坑的部分）

动效常量（`LibraryScreen.kt`，可整段搬）：

```kotlin
private const val ModeSwapOutMillis = 90
private const val ModeSwapInMillis = 210
private const val ModeSwapScale = 0.92f
private val ItemFadeSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow)
private val ItemPlacementSpec = spring(
    stiffness = Spring.StiffnessMedium,
    dampingRatio = Spring.DampingRatioNoBouncy,          // 不要 bouncy：位移过冲会和高频操作打架
    visibilityThreshold = IntOffset.VisibilityThreshold,
)
```

模式切换用 Material 的 fade-through：`animateTo(0f, tween(90, FastOutLinearIn))` → 重排 →
`animateTo(1f, tween(210, LinearOutSlowIn))`；**末尾必须无条件复位**，否则中途切回会把网格永久停在半透明。
切换期间把所有 item 的 `placementSpec` 置 null，让交叉淡入承担运动，否则每个 item 都从旧位置飞一遍。

### 触感的三条铁律

1. **`combinedClickable` 自带长按震动**（`hapticFeedbackEnabled = true` 默认值）。如果 `onLongClick` 里再
   `performHapticFeedback(LongPress)` 就会震两下。凡是自己震的，都要 `hapticFeedbackEnabled = false`。
2. **成功提示不要再震**。点击时已经震过一次（SegmentTick / ContextClick），1 s 后 snackbar 再震一次像 bug。
   规则：snackbar 只在**失败**时 `Reject`；成功静音。
3. 全局开关用 `CompositionLocalProvider(LocalHapticFeedback provides NoOpHapticFeedback)` 包住整棵树
   （`MainActivity.kt`），这样「设置里关掉振动」不需要每个调用点判断。

语义化选择：`SegmentTick`（轻，切换/选中）、`ContextClick`（菜单/进入）、`Confirm`（动作完成）、
`Reject`（失败）、`ToggleOn/Off`（开关）、`LongPress`（拖拽/长按开始）、`TextHandleMove`（滑动刻度）。

### 持久化状态与首帧动画

DataStore 第一帧读不到值。凡是由持久化设置决定的**布局顺序**，都要在 ViewModel 里暴露一个 `…Settled`
标志，标志变 true 前禁用 item 位移动画，否则冷启动会看到「先按默认顺序画一帧，再整体飞成用户的选择」。

---

## 5. 数据层（本项目的核心可复用资产）

### 5.1 三文档拆分（按「谁拥有数据」切）
- `library.json`：解析书籍得到的元数据——**可重建**。
- `userdata.json`：用户写的摘录、书签、笔记、评分、书评、每本书的阅读设置——**不可重建**。
- `progress.json`：阅读位置——**高频写**。

高频写只碰 progress，笔记永远不会被翻页的写操作碰到。合并成一个大 JSON 时，一次翻页要重新序列化整库。

### 5.2 `AtomicJsonFile.kt`（直接搬）
写 `.tmp` → `fd.sync()` → 旧文件转 `.bak` → rename → 失败回退 `.bak`。绝不 `writeText` 裸写。

### 5.3 Repository 做 diff 路由
UI 层只看到合并后的 `Book`；保存时 `BookRepository` 比较新旧对象，自动决定写哪几个文档，调用方不需要声明
「我改了什么」。新增字段一律给默认值（`kotlinx.serialization` 反序列化旧 JSON 不能崩），R8 保留规则在
`app/src/main/keepRules/rules.keep`。

### 5.4 其它可搬的模式
- `SettingsRepository`：一个大 `AppSettings` + `Keys` 常量对象 + `enumOrDefault(...)` 容错 + `updateApp {}`
  统一写回（新增设置项只改三处：字段、Key、`updateApp`/`readAppSettings`）。
- `BackupArchive`：不依赖 Android 类型、可在纯 JVM 单测的 `.zip` 备份格式（manifest + 序列化模型），
  `FORMAT_VERSION` 只在破坏兼容时递增；支持 SAF 单文件/文件夹目标（含云盘）。
- 删除做 tombstone + `updatedAtMillis` 戳，为将来多端同步留位。
- `FirstLaunchRepository` 用「有没有书/字体 + 安装历史」推断首次启动，而不是存一个布尔。

---

## 6. SAF 导入与扫描

- 用户只选一次文件夹权限；旧的 MediaStore/公共目录扫描已删除（那是死代码 + 不该要的存储权限）。
- 扫描是 BFS + `MAX_DEPTH = 8` + `seen` 集合（provider 会报环），边遍历边 emit：列表立刻出现，逐个补全封面/标题。
- 每个候选带**相对所选文件夹的路径**（`Novels/Classics`）：既能做「每子文件夹一个书架」，也能当来源元数据。
- 导入是「暂存 → 问冲突 → 提交」三阶段（`stageImport` / `commitImport` / `discardImport`），
  放弃时必须 `NonCancellable` 里删暂存文件，否则每次取消都留下一个书籍大小的临时文件。
- 记录原始文件名与来源文件夹（`sourceName`/`sourceFolder`），因为 App 内部存的是 `<uuid>.<ext>`，
  用户自己的 `01 xxx.epub` 命名信息不能丢。
- **所有面向用户的异常消息都要本地化**——它们会原样出现在详情/编辑/备份对话框里。

---

## 7. 一个网络请求的正确写法（字体预设下载）

`data/FontDownload.kt` + `ui/reader/PresetFonts.kt`：

- `HttpURLConnection`（不引额外依赖），`instanceFollowRedirects = true`（GitHub release 会 302 到
  `release-assets.githubusercontent.com`），必须带 `User-Agent` 和 `Accept`，20 s 连接 / 60 s 读超时。
- 流式写到 `cacheDir` 的临时文件，**内存里不攒字节数组**；限制最大字节数（本项目 48 MB），
  每个读取块 `coroutineContext.ensureActive()`，关闭界面即取消。
- 进度回调发生在 IO 线程：`onProgress = { f -> scope.launch { progress = f } }` 再回主线程写 Compose 状态。
- 下载后**验真**：先读 4 字节签名（`00010000`/`OTTO`/`ttcf`），再走与手动导入相同的表校验，
  否则会把运营商的 HTML 错误页存成「字体」。
- zip 发行版（如得意黑）支持指定 entry 解包，压缩包用完即删。
- 来源选择：GitHub release 直链（钉住版本号）；`raw.githubusercontent` 在国内常不通时用 jsDelivr
  并钉 commit SHA（`@<sha>`），厂商有自家 CDN 就优先用（MiSans → `cdn.cnbj1.fds.api.mi-img.com`）。
- Android 的 `HttpURLConnection` 会走系统代理，所以用户开 Clash/mihomo 时下载同样生效。
- 目录/清单里每条都标真实体积和许可；`assets/licenses/third-party-notices.txt` 单独列「可下载（不随包分发）」。

---

## 8. CI 与发布签名

### 8.1 CI（`.github/workflows/ci.yml`）
触发：`push` 到 `main`、`alpha-*|beta-*|v*` tag、PR、手动触发（`workflow_dispatch`）。三个 job：

- `quick-check`（push 到 `main`）：JDK 21 + sdkmanager，只跑 `:app:compileDebugKotlin`，20 分钟超时。
- `pull-request-check`（PR / `workflow_dispatch`）：`test` → `assembleDebug` → `:app:lintDebug`，
  不碰签名材料，也不产出 artifact。
  注意：AGP 在这里只生成 debug 单测变体（release 开了 R8 优化，没有单测变体），
  所以 `test` 就是全部；不要写 `:app:testReleaseUnitTest`，那个任务不存在。
- `release`（tag）：`permissions: contents: write`，流程为
  1. JDK 21（temurin，`cache: gradle`）+ sdkmanager 装 `platforms;android-37.0`、`build-tools;37.0.0`
  2. `./gradlew test`
  3. 解码签名密钥到 `$RUNNER_TEMP`（tag 构建四个 secret 缺一即失败，绝不发未签名包）
  4. `./gradlew :app:assembleRelease :app:lintRelease --max-workers=2` —— `assembleRelease` 内含
     `lintVitalRelease`，完整的 `lintRelease` 再给出可读报告；**不传** `-PlegacyReleaseSigning`
  5. `apksigner verify --print-certs` 校验签名，签名缺失或换了 key 直接失败
  6. 按 `version.properties` 的 `name` 重命名资产（`BETA 2` → `FrogReader-BETA-2.apk`）并生成
     `SHA256SUMS.txt`，上传 artifact `FrogReader-release`
  7. `gh release create|upload` 自动发布：标题 `Frog Reader <versionName>`，`alpha-*|beta-*` 标
     `--prerelease`（`/releases/latest` 保持稳定版）

debug 版需要时本地 `./gradlew :app:assembleDebug` 即可；CI 里 debug 只做 PR 验证，不上传 artifact。

### 8.2 签名脚本（`scripts/release-signing.sh`）
无参数运行一次即可：生成 PKCS12 keystore（RSA 4096 / 10000 天 / DN `CN=<appId>`）+ 本地
`release-signing.env`（600，已 gitignore），并打印四个 GitHub secret 与对应的 `gh secret set` 命令。

Gradle 侧契约（`app/build.gradle.kts`）：仅当 `FROGREADER_KEYSTORE_PATH` 存在时创建 signingConfig，
密码来自 `FROGREADER_KEYSTORE_PASSWORD`、`FROGREADER_KEY_ALIAS`、`FROGREADER_KEY_PASSWORD`；
缺任何一个直接报「Missing release signing environment variable」而不是产出半签名包。

**PKCS12 只有一个密码**：`keytool` 接受不同的 `-keypass` 但会忽略它，随后在 `packageRelease` 炸成
`Given final block not properly padded`。所以脚本里 key password 永远等于 store password，
两个 secret 的值是同一个。keystore 丢了等于换了 App 身份（已安装用户只能卸载重装）——务必异地备份。

### 8.3 发版流程（本仓库实际做法）
```bash
git tag -a beta-2-zh -m "BETA 2 (Chinese fork)" && git push origin beta-2-zh
gh release create beta-2-zh app-release.apk SHA256SUMS.txt \
  --title "..." --notes-file notes.md --prerelease
```
- 版本号只在 `version.properties`（`code` / `name`）里，构建不改版本；`resValue` 把版本名暴露给 UI。
- 资产固定「APK + `SHA256SUMS.txt`」，Release 说明写清：上游来源、许可证、包名、**升级是否需要卸载**、校验方式、已知限制。
- 改 `applicationId` 就是换 App 身份：与旧包不能覆盖安装，数据不会迁移，说明里必须提示「先备份再卸载」。
- `gh` 需要 `repo` scope；`gh release view <tag> --json assets` 自查资产是否上传成功。

---

## 9. i18n 约定（本项目是「中文即默认资源」）

- 没有 `values-xx/`：`values/` 里的字符串**本身就是简体中文**，`locales_config.xml` 只声明 `zh`。
  App 名（品牌）不翻译。要加语言时再建 `values-en/` 之类的目录。
- 每条文案都进资源；Compose 里用 `stringResource` / `pluralStringResource`。
- 中文复数只需要 `other`，但资源里 `one`/`other` 都保留同一条文案——
  `values/` 是默认资源，任何 locale 的设备都可能读它。
- 格式化占位符原样保留（`%1$s`、`%1$d`、`%%`）；`&#39;`/`\'` 与 `&amp;` 转义照旧。
- 批量翻译资源后**必须校验 key 一致**：

```bash
git show HEAD:app/src/main/res/values/strings.xml | grep -oP '(?<=<(string|plurals) name=")[^"]+' | sort > /tmp/old
grep -oP '(?<=<(string|plurals) name=")[^"]+' app/src/main/res/values/strings.xml | sort > /tmp/new
comm -23 /tmp/old /tmp/new     # 少了哪些
```
- `lint` 的 `StringFormatMatches` 是这类批量翻译唯一的守门人，务必跑一次。
- 面向开发者的东西可以保留英文：`formatAppDiagnosticInfo` 的诊断报告、代码注释、异常里的断言文本。

---

## 10. 测试约定

- 纯 JVM（JUnit4 + Mockito 5，`testOptions.unitTests.isReturnDefaultValues = true`）；
  `Test` 任务统一挂 mockito agent 并加 `-Xshare:off`（动态 attach 在 JDK 21 上不可用）。
- 测试名用反引号整句描述行为：``fun `numberedNamesSortAsNumbers`()``、``fun `a flat folder becomes one shelf`()``。
- 纯函数优先：排序/分组（`LibraryGrouping.kt`）、书架决策（`ScanShelfPlan`）、分段控件几何都抽成无 Android 依赖的函数再测。
- 网络代码用 JDK 自带 `com.sun.net.httpserver.HttpServer` 起本地服务测（zip 解包、失败路径），不依赖外网。
- `TemporaryFolder` 规则管理文件型测试；合成字体的测试辅助函数在 `CustomFontLibraryTest` 里。
- 资源/文案改动要顺手改断言（本项目的 `BackupArchiveTest`、`BookRepositoryUserDataTest` 断言过中文文案）。
- e2e 分层测试在 `app/src/test/java/com/example/frogreader/e2e/`（Tier1–Tier4 + M3 fixtures）。

---

## 11. 质量门

- `./gradlew :app:lintDebug`：目标是 **0 error**。本项目 85 warning，其中 60 条 `UnusedResources`
  （删掉旧入口后遗留的字符串/图标），属可接受噪音；`ModifierParameter`、`AutoboxingStateCreation`
  之类也别让它们变成 error。
- 提交前的自检：`git diff --check`（尾随空白）、`git status` 里没有 `app/build/`、`*.apk`、keystore、env 文件。
- `.gitignore` 已覆盖：`*.apk`、`*.keystore`、`/signing.properties`、`/release-signing.env`、`AGENTS.md`。

---

## 12. 值得直接复制到新 App 的文件

| 文件 | 用途 |
|---|---|
| `data/AtomicJsonFile.kt` | 原子 JSON 持久化（tmp→fsync→bak→rename） |
| `data/SettingsRepository.kt` | DataStore + AppSettings 全量模型 + 容错枚举 |
| `data/backup/BackupArchive.kt` | 与 Android 无关的备份包格式 |
| `data/FontDownload.kt` | 流式 HTTPS 下载 + 大小上限 + 进度 + 取消 + zip entry |
| `data/CustomFontLibrary.kt` | sfnt 解析、family 分组、按内容去重入库 |
| `ui/library/LibraryGrouping.kt` | 自然排序比较器（`2 卷 < 10 卷`）+ 分组槽位 |
| `ui/components/*` | 分段控件、snackbar、翻页胶囊 |
| `ui/settings/SettingsComponents.kt` | 设置行、开关行、选择行（含触感） |
| `scripts/release-signing.sh` | keystore + GitHub secrets + 本地 env |
| `.github/workflows/ci.yml` | 单测 → 签名 release 构建 + lint → 一个 artifact |
| `app/build.gradle.kts` 的 signingConfig 段 | 环境变量驱动的可选签名 |
| `app/src/main/keepRules/rules.keep` | 序列化模型的 R8 保留 |

---

## 13. 已经踩过的坑（症状 → 原因 → 处理）

1. **长按震两下** → `combinedClickable` 自带长按震动 + 业务层又震一次 → `hapticFeedbackEnabled = false`。
2. **一次点击震两下** → 点击时 SegmentTick + 结果 snackbar 的 Confirm → 成功静音，只失败震。
3. **`packageRelease` 报 `Given final block not properly padded`** → PKCS12 的 key 密码与 store 密码不一致
   （`keytool` 静默忽略 `-keypass`）→ 只用同一个密码。
4. **构建被 OOM kill（exit 143）** → 两个 4 GB daemon + 16 GB 机器 → 降堆 + `--no-parallel` + `--stop`。
5. **jsDelivr 的 `content-length`/`Content-Range` 与真实文件不符**（8.4 MB vs 11.6 MB，`-r 0-3` 返回的是压缩流）
   → 只把 Content-Length 当进度参考，体积以完整下载后 `stat` 为准。
6. **GitHub release 直链必须跟随重定向**，且不带 UA/Accept 可能被拒 → `instanceFollowRedirects` + 两个头。
7. **同一 key 的网格 item 改 span** → LazyGrid 崩溃/重复 place → 只改列数，不改 span。
8. **冷启动先按旧顺序画一帧再重排** → DataStore 首帧为空 → 加 `…Settled` 标志禁用首帧位移动画。
9. **`SourceHanSerifCN-Regular.otf` 用 WOFF2 思路处理会失败** → 它是纯 OTF，只有 4 字节签名校验 + 表解析可用。
10. **中文资源批量替换** → 容易漏 key、破坏 `%1$s` → `comm` 校验 + lint `StringFormatMatches`。
11. **改 `applicationId` 后旧安装无法覆盖** → 签名/包名都变了 → Release 说明里写「先备份、再卸载、后恢复」。
12. **SAF 文档 id 当文件名**（`primary:Download/x.epub`）→ 写入失败且被 `runCatching` 静默吞掉 → 用内容哈希或 uuid 命名。
13. **本地化异常消息会出现在 UI** → 只翻译内部断言不够，所有 `IOException`/`check{}` 文案都要过一遍。
14. **`Bash` 脚本 `set -o pipefail` + `head -c`** → 生成随机密码时上游被 SIGPIPE 杀死（exit 141）→ 用无早退的 `od -N16` 管道。

---

## 14. 代码与提交风格

- 注释解释**为什么**，不解释代码在做什么；复杂决定当场写清取舍，`ARCHITECTURE.md` 只写稳定的结构。
- 不做「以后可能用到」的抽象；空实现、TODO、占位符宁可没有。
- 提交信息用 Conventional Commits（`feat:` / `fix:` / `chore:` / `docs:`），正文用 `-` 列改动与理由；
  改界面/行为时同时在提交正文写明验证方式（哪些命令、多少测试通过）。
- UI 改动先在 `app/src/debug/.../**/*Previews.kt` 里加/更新 Compose Preview（本项目有 40+ 预览文件），
  没有设备也能审版式。
- 新增技能/流程类知识：写到 `docs/` 下的专题文档，不要散落在代码注释里。