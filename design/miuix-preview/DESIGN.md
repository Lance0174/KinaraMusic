# Kinara · Miuix 重绘提案

日期：2026-11-12。阶段：**HTML 预览，待用户确认后实施正式 App 改造**。

## 本次交付与边界

`index.html` 并排展示手机（390 × 844）与桌面/大屏（1200 × 844）的可交互布局；顶部可切换音乐库、统一搜索、歌单、下载、播放页、设置、动态视界。提供浅色、深色、工业三种外观，以及单独的横屏视界。桌面页面是自适应布局提案，不代表已经开发了桌面原生客户端。

HTML/CSS 模拟 Miuix 的组件外观与交互，**尚未向 Android 工程添加 Miuix 依赖**。动态视界载入仓库实际 Folia Lumiere/Sonnet/Tempera 渲染 bundle，非静态截图或普通滚动歌词的替代效果。原创演示歌词及时间轴仅验证视觉呈现，不作为真实歌曲歌词，也不证明实际音频同步或 Android 帧率。

原 App、版本号、签名、生产依赖、本地音源配置均未由本次预览工作修改。工作区已有的修改保留；本阶段不构建、不发布新 APK，不发布 GitHub Release。

## 界面与交互约定

- 手机保留音乐、搜索、歌单、下载、设置五个带文字的导航项；悬浮迷你播放器承接当前歌曲与队列。
- 桌面/大屏使用侧边导航、中央内容与右侧正在播放区；核心操作与手机一致。
- 播放页封面下方按歌曲信息、播放进度、播放控制、辅助操作分组。封面与歌词可轻点或横向滑动切换。
- 搜索按歌曲汇总，**不按音源拆分搜索页**。自动匹配顺序为网易云 → QQ → 酷狗 → 酷我 → 哔哩哔哩 → 其他。相同歌曲的版本、歌手、时长先通过校验，避免用错误版本替换。预览显示示例结果，不调用服务。
- 设置中的歌词显示有经典、逐字辉光、动态视界。动态视界可选流光（Lumiere）、诗行（Sonnet）、绘境（Tempera）、随歌变化。
- **选择动态视界不会自动旋转屏幕**。用户主动轻点「横屏」进入沉浸模式。横屏中只有渲染画面和一条可拖动的进度控制，不显示标题栏、专辑封面、导航、菜单或额外播放按钮。
- 横屏可轻点画面暂停/继续，进度条定位；Android 返回键应恢复之前的播放页、屏幕方向与系统栏。HTML 对应浏览器返回/Esc。浏览器不支持方向锁定时仍可手动横置，内嵌审阅窗口不强制全屏。
- Kinara 是应用内唯一产品标识。Miuix/Folia 组件来源和许可证在仓库、源码说明与法律材料保留。
- 液态玻璃保留为界面质感选项。HTML 只示意半透明表面与高光，真实 Android 模糊/折射与性能仍需设备验证。

## 原生组件映射

| 界面区域 | 拟使用的 Miuix 组件 | 说明 |
| --- | --- | --- |
| 应用框架与主题 | `Scaffold`, `MiuixTheme` | 统一色彩、内容留白与覆盖层宿主 |
| 大标题与返回栏 | `TopAppBar`, `SmallTopAppBar` | 大标题随滚动收合 |
| 手机导航 | `NavigationBar` | 图标与文字同时显示 |
| 大屏导航 | `NavigationRail` | 宽度不足时只保留图标 |
| 歌曲分组、设置分组 | `Card`, `BasicComponent` | 默认 16 dp 圆角；工业外观使用更克制的角形 |
| 统一搜索 | `SearchBar` / `InputField` | 结果合并、空状态与输入清除 |
| 歌曲/专辑/歌手切换 | `TabRow` | 保持数据与滚动状态 |
| 播放进度 | `Slider` | 手势预览与确认 seek 分开，避免拖动时抖动 |
| 设置开关 | `SwitchPreference` | 单一设置状态驱动完整链路 |
| 歌词、品质、表面选项 | `OverlaySpinnerPreference` | 选择后立即生效并保存 |
| 队列、更多菜单 | `OverlayBottomSheet` | 无重复模态层、遵守返回行为 |
| 确认与提示 | `OverlayDialog`, `Snackbar` | 清晰的操作结果 |

Miuix 文档要求覆盖层由 `Scaffold` 中的 `MiuixPopupHost` 承载；实现时按组件约定统一挂载，不在各页面重复创建弹出层。

## 依赖与兼容性核对

本次核对的 Maven Central 稳定版为 **0.9.4**，拟使用：

```kotlin
implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
```

这只是候选版本，**尚未完成当前工程的构建兼容验证**。当前 Kinara 的 AGP 8.5.2、Kotlin 1.9.24、Compose Compiler 1.5.14 不能直接假定兼容：0.9.4 POM 声明 Kotlin stdlib 2.4.20 和 Compose Foundation Android 1.12.0；上游同版本源码的构建配置为 AGP 9.4.1 / Kotlin 2.4.20。上游使用的 AGP 版本不等于消费者必须使用的最低版本。

用户确认后，先在独立分支验证 Miuix 与 Kotlin/Compose/Gradle/JDK 的一整套可构建组合，选择受支持的稳定组合并锁定版本，再分页面迁移。保留当前 Android 设备支持范围，不因模糊模块而提高最低系统版本。

官方可选 `miuix-blur-android` 文档要求 minSdk 33；用户使用 Android 12 或更新系统，不能直接把这一模块变为全 App 的系统门槛。Android 12 的质感效果优先复用现有 API 31 `RenderEffect` 路径，更低系统使用轻量表面。Android 13+ 是否引入可选新模糊路径，取决于视觉和实测负载。

## 流畅度：已见代码与待测瓶颈

源码检查表明工程已有 Media3 后台播放、独立进度状态流、并发音源恢复及初始解析的 700 ms 延迟竞速；`OnlinePlayback` 当前设置单源搜索 6 s、解析 4 s 的预算。因此不能把当前问题简单归结为所有音源均串行尝试，也不能用 HTML 演示声称已经优化了真实首播延迟。

需要分别测量「音频缓冲/切换等待」与「界面掉帧」，避免为界面卡顿更换音频引擎：

| 需要测量的现象 | 采集依据 | 优先改进方向 |
| --- | --- | --- |
| 点击播放到真实首个音频帧 | Media3 事件、解析/连接/缓冲分段耗时，p50/p95 | 保持并发预算、取消过期解析、短期缓存可播放结果、预解析下一首；不预加载整队列 |
| 死链/拒绝访问后的恢复 | 失效源、恢复源、版本校验与实际恢复耗时 | 隔离长期不可用源；有限并发竞速；播放资格和音源优先级分开评定 |
| 长列表、拖动与迷你播放器的掉帧 | FrameTimeline/Perfetto、JankStats、Compose 重组跟踪 | 精简高频状态订阅、稳定列表 key，封面尺寸与缓存；沿用现有独立进度流 |
| 动态视界的卡顿或黑屏 | WebView/Canvas 可见尺寸、渲染就绪、帧时间与内存 | 保留一个活动 renderer；切换横竖屏不无条件重建；不可见立即暂停；渲染进程异常后可重试并降级 |
| 玻璃效果的 GPU/内存负载 | 同场景开/关效果对照、纹理分配与合成耗时 | 限制背景采样次数、模糊半径与分辨率；按设备与刷新率控制品质 |
| 长时间播放后的温度与电量 | 连续 15 分钟的帧时间、温度、内存、音频欠载 | 限制特效帧率与后台工作，切后台暂停视觉、保持音频服务 |

目前 Folia 桥接每 100 ms 发送时钟并每 250 ms 检查显示状态；这是要测量的负载，不是已经证实的根因。优先在播放端提供带采样时间的权威时钟，Web 内插进度，减少不必要的 UI 通知与 JSON 分配。暂停、seek、变速、切源后必须重新锚定，避免歌词累积偏移。歌词查找以实际播放的歌曲版本绑定，不能用固定提前几秒代替版本核对。

**架构选择：保留 Kotlin/Compose + Media3 + 现有 Folia 渲染器，先做状态与职责隔离。** Miuix 用于原生界面组件，不把整款 App 搬入 WebView。若真实设备测量证实渲染宿主或播放解析架构是瓶颈，再对局部模块做替换试验，并用相同场景对照。不承诺未经测量的 FPS 或提速倍数。

## 实施顺序与验收

1. 用户确认这份双端交互与横屏方案。
2. 在隔离分支完成依赖兼容试验；先迁移主题、导航、设置与分组卡片，随后迁移列表、搜索、播放页和弹出层。
3. 实现用户主动进入横屏及返回恢复；保留此前已加入的 Activity 重建、多窗口、系统栏和方向恢复保护，更新其自动旋转触发逻辑。
4. 同时记录旧版基线与新版数据，逐项优化音源等待、掉帧与视觉负载。使用 Android 12 真机与较新 Android 设备检查普通/动态播放、样式切换、切源、seek、后台恢复和旋转。
5. Release 前检查应用 ID、versionCode 递增、签名一致、可覆盖升级、播放数据库与歌单数据保留；提交复核结果后再按用户已确认的发布范围操作。

目标是无错误歌词版本、无静默黑屏、不可播放源可恢复、样式切换真实生效、横屏仅进度控件。性能目标以同机同曲同网对照为依据，60 Hz 设备以 16.7 ms 帧预算观察慢帧，不能把所有设备视为同一 GPU 性能。HTML 预览验收不替代这些 App 验收。

## 签名不变

沿用现有 release 密钥，禁止重新生成或退回 debug 签名。现有记录的证书 SHA-256 固定为：

```text
592dde74cbe6793743ddc1e52f48b5c20800ed4bae5e1e9569fc9f3f34e095a5
```

工具链/界面库迁移不得修改 signingConfig 的密钥身份。实际发布前从旧 APK 与候选 APK 分别读取证书并对比；不一致即停止发布。密钥、密码、Cookie 和私有音源配置不进入预览包、公开仓库或源码压缩包。

## 参考与许可证

- [Miuix 中文组件文档](https://compose-miuix-ui.github.io/miuix/zh_CN/components/)
- [Miuix 快速开始与模块说明](https://compose-miuix-ui.github.io/miuix/zh_CN/guide/getting-started)
- [Miuix 源码与 Apache-2.0 许可证](https://github.com/compose-miuix-ui/miuix)
- [Maven Central 0.9.4 UI POM](https://repo.maven.apache.org/maven2/top/yukonga/miuix/kmp/miuix-ui-android/0.9.4/miuix-ui-android-0.9.4.pom)
- [Miuix v0.9.4 构建版本目录](https://github.com/compose-miuix-ui/miuix/blob/v0.9.4/gradle/libs.versions.toml)
- [Folia 上游](https://github.com/chthollyphile/folia-major)，AGPL-3.0。预览中保留 `assets/folia/` 下的全部许可证与 NOTICE，具体源码说明见 `assets/folia/SOURCE.md`。
- 本 HTML 原型及原创 SVG/歌词示例：Kinara contributors，AGPL-3.0-only；不使用游戏原画、商业音乐封面或第三方歌曲歌词。
