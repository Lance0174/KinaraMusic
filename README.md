# Kinara

原生 Android 音乐客户端，Kotlin / Jetpack Compose / Media3。支持本地音乐、多来源搜索、本机收藏和歌单。默认外观为液态玻璃，Android 12 及以上使用真实背景模糊。

当前版本 **1.4.2 / versionCode 12**。支持系统文件导入、便携歌单 JSON、播放缓存、系统后台下载、定时关闭、播放诊断和在线更新。工业主题以《明日方舟：终末地》的工业黄、黑灰、切角与标签字体为视觉方向，不使用游戏美术资源。

播放页保留整页封面、歌曲信息、细进度条和五键控制区。封面与歌词左右滑动切换，底部两个入口也可轻点或横滑。歌词样式在**设置 → 歌词与歌曲信息 → 歌词视觉模式**选择「经典」「逐字辉光」「动态视界」。动态视界下可选择「随曲变化」「流光」「动态排字」「凝彩」，设置立即保存；「查看当前歌曲效果」直接打开歌词页。

「动态视界」集成 Folia 0.7.20 的三个实际 Pixi/WebGL 引擎。流光（Lumiere）提供光束、雾、粒子和段落编排；动态排字（Sonnet）提供文字冲击、镜头跟踪和海报构图；凝彩（Tempera）提供色块拼贴、文字动态反色和多种分镜转场。随曲变化按实际录音身份稳定选择引擎，拖动进度或刷新信息不会随机换风格。这些是不同的歌词编排程序，不只是背景换色。

歌词时间由 Media3 音频时钟驱动；支持音频响应的引擎读取实际解码音频的强弱。资源离线打包，WebView 不访问网络。提供省电、均衡、精细三档画质；离页与后台停止渲染。减少动效或关闭系统动画时保留所选编排，使用引擎的静态模式，不再替换为普通歌词。WebGL 失败时明确提示并暂用原生辉光，可重新加载或切换样式恢复。30/60 fps 是上限，不代表真机测得帧率。软件品牌与模式入口统一使用 Kinara；Folia 的来源、版权与许可保留在仓库及开源许可内容中。

「逐字辉光」保留原生 Compose 的局部辉光和平滑跟随。真实 YRC 词时间驱动逐字高亮，只有 LRC 时整行点亮；各动态引擎都不虚构逐字时间。所有样式使用统一插值时钟，暂停、缓冲、seek、倍速和旧采样隔离遵守相同时间规则。播放菜单「歌词校准」按实际音源与歌曲 ID 保存单曲偏移；设置中的全局偏移另计。译文、罗马音和离线歌词缓存继续可用。

**设置 → 应用更新**可主动检查 GitHub 正式版本、阅读说明、下载并安装。下载逐跳限制 HTTPS 来源，核对文件大小、SHA-256、包名、版本号与当前签名后才交给系统安装；首次安装须由用户允许安装来源。没有正式 Release 时显示没有可用更新。发布者需提供与本机签名相同的正式 APK，具体命名、分发源码与 fork 配置见 [在线更新说明](docs/UPDATES.md)。

播放服务在切歌和网络恢复时自动补全当前歌曲信息与封面，同步更新队列、迷你播放器和系统媒体信息，保留正在播放的 URI 和进度。网易云歌单导入直接读取歌曲专辑图；旧歌单与收藏中的缺图也会在联网后补齐、保存，最多同时请求两首。在线歌曲要求同平台 ID；本地歌曲仅在歌名、完整歌手、时长和已有专辑名明确匹配、候选唯一时更新。内嵌封面优先，不改音频文件标签；设置可关闭联网更新。元信息缓存上限 300 首，有封面的成功结果 7 天内复用，封面图片由 Coil 缓存。

## 本机构建

```powershell
$env:JAVA_HOME='<path-to-jdk-17>'
./gradlew.bat --console=plain testDebugUnitTest lintDebug assembleRelease
node --test worker/worker.test.mjs
```

修改 Folia 渲染源码时，先在 `visualizer/` 执行 `npm ci --ignore-scripts`、`npm run build`、`npm test`、`npm run test:browser`。Android 的 `verifyFoliaBundle` 会核验源码和打包资源 SHA256；未改渲染器时无需重新安装 npm。跨平台浏览器测试与依赖说明见 [visualizer/README.md](visualizer/README.md)。

SDK、发布签名和其他本机路径来自未提交的 `local.properties` 或外部属性文件；正式安装包只包含 arm64-v8a。Release 缺少签名配置会明确失败，不会自动采用 debug 密钥；Debug 和显式 `-PciUnsigned=true` 的 CI 构建不要求发布密钥。

### 音源配置

公开构建默认使用免费公开音源，不需要配置私人服务即可搜索和尝试播放；上游限制、版权和地区条件仍可能影响可用性。GitHub CI 强制清空私有配置。本地打包可通过受 Git 忽略的 `local.properties` 注入 `kinara.solaraBaseUrl` / `kinara.solaraSecret`、`kinara.biliWorkerBases` 和 `kinara.biliWorkerToken`。最后一项只是 CF 服务访问密钥，B站 Cookie 仅保存在 CF Secret，绝不能写入 APK。私有配置会进入本地 APK，因此该包不作为公共 GitHub 资产。可用配置项见 `local.properties.example`，完整恢复规则及服务边界见 [播放恢复说明](docs/PLAYBACK_RECOVERY.md)。

## 代码入口

| 文件/目录 | 职责 |
| --- | --- |
| `MainActivity.kt` | Android 权限、音乐扫描、前台进度同步 |
| `ui/KinaraApp.kt` | 页面路由、ViewModel 状态接线、播放页与加入歌单交互 |
| `ui/KinaraShell.kt` | 系统栏、键盘、底部浮动导航与播放条、玻璃背景采样 |
| `ui/MusicComponents.kt` | 曲目行、搜索框、空态、分类按钮；滚动内容共用底部留白 |
| `ui/KinaraIcons.kt` | 统一矢量图标和无障碍描述，不依赖 Unicode 播放符号 |
| `ui/theme` | 配色与材质令牌、真实模糊、背景渐变 |
| `MainViewModel.kt` | 设置、搜索代次、导入预览、歌词请求 |
| `player` | Media3 会话与稳定队列 URI；音频加载线程解析地址 |
| `player/PlayerProgress.kt` | 独立进度时钟，只有进度条和歌词订阅，音乐库不随进度重组 |
| `player/LyricClock.kt`、`data/LyricOffsets.kt` | 歌词插值时钟、实际曲目身份与逐曲校准 |
| `ui/FoliaLyrics.kt`、`visualizer/` | Folia 流光原生桥、离线渲染器源码与构建校验 |
| `player/ReactiveAudio.kt` | 按播放时间读取的 PCM 音频强弱，供流光响应音乐 |
| `player/AudioCache.kt` | 在解析 URL 之前读取持久字节缓存，1/2/5 GB 容量与 7 天过期清理 |
| `player/AudioResolver.kt` | 短期地址缓存、HTTPS 优先与限定音频域名的 HTTP 回退 |
| `player/SleepTimer.kt` | 服务持有的单调时钟计时器；与 Activity 生命周期解耦 |
| `download/Downloads.kt` | DownloadManager 任务、取消/重试/恢复、音频文件头校验；不保存签名直链到项目数据 |
| `data/LibraryRepository.kt` | 串行收藏/歌单事务，IO 线程解析和保存，SAF/JSON 导入导出 |
| `online` | 各来源协议、搜索归约与取流策略 |
| `account` | B站官方匿名 API、WBI 签名与受限媒体请求 |
| `data` | 本机收藏、歌单、设置与统一曲目模型 |

主导航只有音乐库、搜索、设置。音乐库包含本地、收藏、歌单、下载。「我喜欢」直接使用收藏数据，避免维护两份列表。歌单导入从歌单页进入；应用内不提供 B站扫码或账号凭证存储。各页只持有文本输入、菜单等界面状态，播放与持久数据以 ViewModel 为准。

## 玻璃渲染

背景和页面内容分别录制为 Compose `GraphicsLayer`。页面内的面板采样背景，浮动播放条和导航采样完整页面，避免玻璃自我采样。Android API 31+ 对面板范围及边距应用 `BlurEffect`，配合轻微放大、透色、高光和描边。它是基于模糊及放大的光学近似，不是物理光线追踪。

面板的绘制指令和 `BlurEffect` 通过 `drawWithCache` 复用，背景源依然随页面内容变化。工业主题不创建玻璃采样层；整页播放器不再采样或重复解码封面。启用 Compose strong skipping，进度时钟与队列/页面状态分开，播放事件仅在时间线变化时重新解析队列。本地搜索、队列构建与歌单序列化移出主线程，网络状态使用系统回调。

列表通过 `LocalDockInset` 获得浮动底栏的实际高度，末尾歌曲可以完整滚动到播放条上方。普通歌曲行不创建独立模糊面板。

## B站服务与数据边界

公开包使用官方匿名接口；已配置私有服务的本地包优先请求 CF，失败后尝试公开请求及其他音乐来源。Cookie 只保存在 Worker Secret，并且只发送到官方 API。应用只接收歌曲信息和音频流，升级时清除旧版会话文件；不再显示扫码登录。

私有服务要求专用访问密钥。播放与系统下载使用绑定单曲、分集和音质的短期凭证，长期服务密钥不进入 CDN 或系统下载请求。Worker 对音频目标、重定向、请求方法、Range 和响应大小进行限制。

2026-10-19 的部署验证中，CF 出口仍收到 B站 `-412` 风控响应，不能宣称当前 B站私有音源已恢复；见 [验证记录和限制](docs/PLAYBACK_RECOVERY.md)。

测试不运行用户的手机或模拟器。Robolectric 的原生绘制和交互检查覆盖 API 31/34，但不能替代设备音频输出、蓝牙和系统媒体通知验收。

## 本地文件、缓存与下载

系统文件选择器可多选音频并持久化读取授权。文件提供方不支持持久授权时，先复制到应用私有目录，再加入歌单；缺少标签时使用文件名。同名「本地音乐」歌单自动合并，批次内部与既有曲目都按稳定标识去重。1.2.0 可为明确匹配的本地歌曲补全网络封面；纯本地文件不猜测歌词，带原在线标识的下载文件可获取或读取缓存歌词。

JSON 使用 `{meta, items}`，兼容 Solara 的常见在线曲目信息及 artist 数组。导出跳过纯本地曲目，下载曲目只导出原在线标识；不导出设备 URI、路径、Cookie 或签名地址。文件选择器状态仅保存歌单 ID，避免大歌单触发 Android 状态包大小限制。

在线播放缓存默认 1 GB，可选 2/5 GB；按平台、歌曲 ID 和音质区分，缓存条目最多保留 7 天，播放不会延长期限。完整缓存无需请求解析接口即可离线读取；缺少的字节仍需联网。容量按最近使用回收，手动清理会延后正在读取的曲目。缓存可由系统回收；需要长期离线保留时使用下载。

Android 10+ 下载交给系统 DownloadManager，保存至公共 `Music/Kinara`，可在音乐库查看进度、取消、重试和离线播放。应用重建后恢复系统任务；重试重新解析地址。下载前后按文件头识别音频，网页或 JSON 错误不算成功，失败的无效音频会移除。所选音质是请求档位，不保证上游一定返回该码率。Android 7–9 可使用本地播放和缓存，下载入口会提示最低版本。

播放错误后并行查找其他启用来源，验证少量实际音频字节后再交给播放器。各平台共用一轮并发等待，已就绪的低优先级来源最多额外等待 200 ms；不再先对同一失败地址完整重试一遍。手动重试会重置该次尝试。B站账号与 Solara 端点保持 HTTPS，酷我/酷狗既有接口维持限定域名策略；音源返回 HTTP 地址时，优先选择可连接的 HTTPS 地址，限定音乐域名才允许 HTTP 回退。B站 CDN 不接收账号 Cookie。

定时关闭由播放服务持有，支持 15/30/45/60/90 分钟和当前曲目结束后暂停；不会主动退出应用。诊断在内存中保留最近 200 条类型化数字事件，包含播放、输出增益、音频焦点状态和错误码；用户主动导出，不记录音频、账号凭证、音频地址或设备名称。

## 自动构建

`.github/workflows/android.yml` 提供 GitHub Actions 校验工作流，执行 Android 测试、Lint、Release 构建、Worker 测试和 Folia 浏览器测试，上传报告与 APK。工作流默认使用 `-PciUnsigned=true` 生成未签名包，避免 CI 临时 debug 密钥冒充本地升级签名；发布前使用上述固定密钥签名。密钥与密码均不上传 CI。公开仓库与审查跟踪见 [KinaraMusic](https://github.com/Lance0174/KinaraMusic) 和 [Issue #1](https://github.com/Lance0174/KinaraMusic/issues/1)。

## 参考与许可

1.4.0 直接集成 [Folia Major](https://github.com/chthollyphile/folia-major) 0.7.20 的 Lumiere、Sonnet 和 Tempera 渲染器及其实际依赖，并保留原生辉光样式。上游为 AGPL-3.0，版权与原始文件哈希保存在 `visualizer/vendor/folia/`。Kinara 原有代码保留 GPL-3.0，桥接代码为 AGPL-3.0，组合分发遵守 GPLv3 第 13 条及适用的 AGPL 要求。APK 内含许可说明；分发 APK 时应同时提供对应版本的完整源码归档、锁文件和构建脚本，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。没有移植 Folia 全部 14 个模式、桌面外壳、主题市场或外部图片池。

此次交互研究阅读了 [NeriPlayer](https://github.com/cwuom/NeriPlayer)、[Metrolist](https://github.com/MetrolistGroup/Metrolist) 与 [Auxio](https://github.com/OxygenCobalt/Auxio) 的源码。借鉴音乐库分类、浮动播放条、曲目菜单与账号模块组织；未移植竞品代码或美术素材。具体路径、下载快照与观察记录见外部开发报告目录。

1.1.0 对照 [Lance0174/Solara 发布页](https://github.com/Lance0174/Solara/releases) 中 v1.7.0 至 android-v1.8.2 的 Android 功能与缺陷，以及该标签的 Android README。按 Kinara 的架构独立实现，未移植其 CC BY-NC-SA 源码或美术资源；Solara 网站登录同步和 Docker 启动脚本不属于本项目的 Android 登录链路。

Kinara 源码保留 GPL-3.0 版权声明。新增 QR 编码依赖 ZXing 3.5.3，Apache-2.0 许可随 APK 的 `assets/licenses` 提供。完整依赖由 Gradle 文件定义。

全仓库、恶意植入与供应链检查的范围、证据和未覆盖项见 [安全复审记录](docs/SUPPLY_CHAIN_REVIEW.md)。Worker 开发/部署工具要求 Node.js 22+，在 `worker/` 执行 `npm ci --ignore-scripts` 后使用已锁定的 Wrangler；不要调用未指定版本的远程部署工具。
