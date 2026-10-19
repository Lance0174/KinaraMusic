# Kinara

原生 Android 音乐客户端，Kotlin / Jetpack Compose / Media3。支持本地音乐、多来源搜索、本机收藏和歌单。默认外观为液态玻璃，Android 12 及以上使用真实背景模糊。

当前版本 **1.3.0 / versionCode 8**。支持系统文件导入、便携歌单 JSON、播放缓存、系统后台下载、定时关闭和播放诊断。工业主题以《明日方舟：终末地》的工业黄、黑灰、切角与标签字体为视觉方向，不使用游戏美术资源。

播放页保留整页封面、歌曲信息、细进度条和五键控制区。封面与歌词左右滑动切换，底部两个入口也可轻点或横滑。歌词样式在**设置 → 歌词与歌曲信息 → 歌词视觉模式**选择「经典」「逐字辉光」「Folia 流光」，不再把渲染器列成第三个页面。

「Folia 流光」集成 Folia 0.7.20 的实际 Lumiere Pixi/WebGL 渲染器，包括段落编排、动态字形、光束、雾和粒子。歌词时间由 Media3 音频时钟驱动，光场响应实际解码音频的强弱；资源离线打包，WebView 不访问网络。提供省电、均衡、精细三档画质；离页与后台停止渲染，WebGL 失败时回退原生辉光，减少动效时使用静态歌词。30/60 fps 是上限，不代表真机测得帧率。

「逐字辉光」保留原生 Compose 的局部辉光和平滑跟随。真实 YRC 词时间驱动逐字高亮，只有 LRC 时整行点亮，两个动态样式均不虚构逐字时间。所有样式使用统一插值时钟，暂停、缓冲、seek、倍速和旧采样隔离遵守相同时间规则。播放菜单「歌词校准」按实际音源与歌曲 ID 保存单曲偏移；设置中的全局偏移另计。译文、罗马音和离线歌词缓存继续可用。

播放服务在切歌和网络恢复时自动补全当前歌曲信息与封面，同步更新队列、迷你播放器和系统媒体信息，保留正在播放的 URI 和进度。网易云歌单导入直接读取歌曲专辑图；旧歌单与收藏中的缺图也会在联网后补齐、保存，最多同时请求两首。在线歌曲要求同平台 ID；本地歌曲仅在歌名、完整歌手、时长和已有专辑名明确匹配、候选唯一时更新。内嵌封面优先，不改音频文件标签；设置可关闭联网更新。元信息缓存上限 300 首，有封面的成功结果 7 天内复用，封面图片由 Coil 缓存。

## 本机构建

```powershell
$env:JAVA_HOME='<path-to-jdk-17>'
./gradlew.bat --console=plain testDebugUnitTest lintDebug assembleRelease
node --test worker/worker.test.mjs
```

修改 Folia 渲染源码时，先在 `visualizer/` 执行 `npm ci`、`npm run build`、`npm test`、`npm run test:browser`。Android 的 `verifyFoliaBundle` 会核验源码和打包资源 SHA256；未改渲染器时无需重新安装 npm。跨平台浏览器测试与依赖说明见 [visualizer/README.md](visualizer/README.md)。

SDK、发布签名和其他本机路径来自未提交的 `local.properties` 或外部属性文件；正式安装包只包含 arm64-v8a。Release 缺少签名配置会明确失败，不会自动采用 debug 密钥；Debug 和显式 `-PciUnsigned=true` 的 CI 构建不要求发布密钥。

### 音源配置

公开源码只包含协议实现和不带凭据的官方音源端点。网易云、QQ、酷狗、酷我和登录后的 B 站直连协议可以直接使用；Solara 与匿名 B 站 Worker 属于部署者自己的服务，公开构建默认不携带它们。需要在本机复制 `local.properties.example` 为 `local.properties`，设置 `kinara.solaraBaseUrl` / `kinara.solaraSecret` 和可选的 `kinara.biliWorkerBases`（多个 HTTPS 地址用逗号分隔），或使用对应的 `KINARA_*` 环境变量，再构建 APK。构建配置会写入本地 APK 的 `BuildConfig`，不会写入 Git；因此本地打包会包含你配置的音源入口，远端维护者必须配置自己的音源和发布签名。

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
| `account` | B站官方扫码协议、登录状态机、设备加密凭证 |
| `data` | 本机收藏、歌单、设置与统一曲目模型 |

主导航只有音乐库、搜索、设置。音乐库包含本地、收藏、歌单、下载。「我喜欢」直接使用收藏数据，避免维护两份列表。歌单导入从歌单页进入；账户登录在设置页。各页只持有文本输入、菜单等界面状态，播放与持久数据以 ViewModel 为准。

## 玻璃渲染

背景和页面内容分别录制为 Compose `GraphicsLayer`。页面内的面板采样背景，浮动播放条和导航采样完整页面，避免玻璃自我采样。Android API 31+ 对面板范围及边距应用 `BlurEffect`，配合轻微放大、透色、高光和描边。它是基于模糊及放大的光学近似，不是物理光线追踪。

面板的绘制指令和 `BlurEffect` 通过 `drawWithCache` 复用，背景源依然随页面内容变化。工业主题不创建玻璃采样层；整页播放器不再采样或重复解码封面。启用 Compose strong skipping，进度时钟与队列/页面状态分开，播放事件仅在时间线变化时重新解析队列。本地搜索、队列构建与歌单序列化移出主线程，网络状态使用系统回调。

列表通过 `LocalDockInset` 获得浮动底栏的实际高度，末尾歌曲可以完整滚动到播放条上方。普通歌曲行不创建独立模糊面板。

## B站登录与数据边界

- 官方 `passport.bilibili.com` 生成二维码并轮询；支持当前返回的 `account.bilibili.com` 二维码链接。
- 凭证从官方响应的 `Set-Cookie` 读取，不调用外部二维码生成服务。
- Cookie 用 Android Keystore 的 AES-GCM 密钥加密，保存在应用私有 `noBackupFilesDir`，不会进入备份、日志、歌单、APK 或公共 Worker。
- 已登录请求直接到 `api.bilibili.com`，搜索和取流带 WBI 签名。匿名状态可回退现有 Worker。
- 音频 CDN 请求只携带 Referer / User-Agent，不携带账号 Cookie。取流结果仅接受受信 HTTPS B站音频域名。
- 二维码支持待扫码、待确认、过期刷新、取消和退出登录。网页登录可能受网络限制；扫码成功本身不能保证解除上游网络风控。

测试不运行用户的手机或模拟器。Robolectric 的原生绘制和交互检查覆盖 API 31/34，但不能替代设备音频输出、Keystore、蓝牙和系统媒体通知验收。

## 本地文件、缓存与下载

系统文件选择器可多选音频并持久化读取授权。文件提供方不支持持久授权时，先复制到应用私有目录，再加入歌单；缺少标签时使用文件名。同名「本地音乐」歌单自动合并，批次内部与既有曲目都按稳定标识去重。1.2.0 可为明确匹配的本地歌曲补全网络封面；纯本地文件不猜测歌词，带原在线标识的下载文件可获取或读取缓存歌词。

JSON 使用 `{meta, items}`，兼容 Solara 的常见在线曲目信息及 artist 数组。导出跳过纯本地曲目，下载曲目只导出原在线标识；不导出设备 URI、路径、Cookie 或签名地址。文件选择器状态仅保存歌单 ID，避免大歌单触发 Android 状态包大小限制。

在线播放缓存默认 1 GB，可选 2/5 GB；按平台、歌曲 ID 和音质区分，缓存条目最多保留 7 天，播放不会延长期限。完整缓存无需请求解析接口即可离线读取；缺少的字节仍需联网。容量按最近使用回收，手动清理会延后正在读取的曲目。缓存可由系统回收；需要长期离线保留时使用下载。

Android 10+ 下载交给系统 DownloadManager，保存至公共 `Music/Kinara`，可在音乐库查看进度、取消、重试和离线播放。应用重建后恢复系统任务；重试重新解析地址。下载前后按文件头识别音频，网页或 JSON 错误不算成功，失败的无效音频会移除。所选音质是请求档位，不保证上游一定返回该码率。Android 7–9 可使用本地播放和缓存，下载入口会提示最低版本。

播放错误最多自动重新解析重试一次，手动重试会重置该次尝试。B站账号与 Solara 端点保持 HTTPS，酷我/酷狗既有接口维持限定域名策略；音源返回 HTTP 音频地址时，先尝试 HTTPS，再允许网易/酷我/酷狗/QQ 的明确音频域名回退。B站 CDN 不接收账号 Cookie。

定时关闭由播放服务持有，支持 15/30/45/60/90 分钟和当前曲目结束后暂停；不会主动退出应用。诊断在内存中保留最近 200 条类型化数字事件，包含播放、输出增益、音频焦点状态和错误码；用户主动导出，不记录音频、账号凭证、音频地址或设备名称。

## 自动构建

`.github/workflows/android.yml` 提供 GitHub Actions 校验工作流，执行 Android 测试、Lint、Release 构建、Worker 测试和 Folia 浏览器测试，上传报告与 APK。工作流默认使用 `-PciUnsigned=true` 生成未签名包，避免 CI 临时 debug 密钥冒充本地升级签名；发布前使用上述固定密钥签名。密钥与密码均不上传 CI。本轮未推送仓库或发布 GitHub Release。

## 参考与许可

1.3.0 直接集成 [Folia Major](https://github.com/chthollyphile/folia-major) 0.7.20 的 Lumiere 渲染器及其实际依赖；并保留已有原生辉光样式。上游为 AGPL-3.0，版权与原始文件哈希保存在 `visualizer/vendor/folia/`。Kinara 原有代码保留 GPL-3.0，桥接代码为 AGPL-3.0，组合分发遵守 GPLv3 第 13 条及适用的 AGPL 要求。APK 随附完整对应源码、锁文件、构建脚本和许可说明，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。本次只集成流光，不包含 Folia 全部主题或桌面应用。

此次交互研究阅读了 [NeriPlayer](https://github.com/cwuom/NeriPlayer)、[Metrolist](https://github.com/MetrolistGroup/Metrolist) 与 [Auxio](https://github.com/OxygenCobalt/Auxio) 的源码。借鉴音乐库分类、浮动播放条、曲目菜单与账号模块组织；未移植竞品代码或美术素材。具体路径、下载快照与观察记录见外部开发报告目录。

1.1.0 对照 [Lance0174/Solara 发布页](https://github.com/Lance0174/Solara/releases) 中 v1.7.0 至 android-v1.8.2 的 Android 功能与缺陷，以及该标签的 Android README。按 Kinara 的架构独立实现，未移植其 CC BY-NC-SA 源码或美术资源；Solara 网站登录同步和 Docker 启动脚本不属于本项目的 Android 登录链路。

Kinara 源码保留 GPL-3.0 版权声明。新增 QR 编码依赖 ZXing 3.5.3，Apache-2.0 许可随 APK 的 `assets/licenses` 提供。完整依赖由 Gradle 文件定义。
