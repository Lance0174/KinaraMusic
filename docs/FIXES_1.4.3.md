# 1.4.3 动态视界黑屏修复

## 复现与原因

在 MuMu Android 15、Android System WebView 110.0.5481.154.1 中，使用实际 `PlayScreen` 和 `FoliaLyrics` 复现了 1.4.2 的整块黑屏。页面已加载、WebGL 2 已创建、引擎状态为 `ready`，但 CSS 舞台高度为 **0**；同一时刻 WebView 网页视口高度为 **520 CSS px**。原有 `html, body, #stage { height: 100% }` 在 Android WebView 的内容测量下没有得到有效的父级高度。

直接在该 WebView 内把容器固定到视口后，同一录音、同一歌词时间立即显示出原版光束、粒子和动态排字。并非歌词缺失或音源无法播放。此前桌面 WebGL 测试和 Robolectric 没有执行这条 Android 布局链路，因此漏掉了问题。

## 修改

- 舞台使用 `position: fixed; inset: 0`，不再依赖网页祖先的百分比高度；原生 WebView 明确使用 `MATCH_PARENT`。
- 加载就绪检查同时检查舞台、画布的可见尺寸和显示状态，避免“引擎已创建”被当作“画面已显示”。
- 恢复已集成 Folia 引擎的默认视觉装饰：Lumiere 取景框、Sonnet 背景装饰与完整边框、Tempera 构图标记。保留原版镜头、布局、粒子与转场程序。
- 从 Android 已有封面解码链路传入三个封面颜色，用于视觉主题和 Tempera 配色输入；WebView 不请求封面 URL，不接触账户凭据。
- 三个实际渲染器继续对应「流光」「动态排字」「凝彩」。逐字时间来自真实歌词；普通 LRC 不编造逐字时间。应用标识保持 Kinara，仓库和许可说明保留 Folia 署名。

## 验证方法

浏览器新增回归主动将 HTML/body 高度压成零，要求三个模式仍占满真实视口；隐藏画布时不得报告显示就绪，横竖尺寸变化后也必须重新匹配视口。

新增仅 Debug 构建包含的 `FoliaProbeActivity`，直接挂载生产播放页、真实 WebView 和离线歌词样本。`visualizer/android-test/smoke.mjs` 在指定测试模拟器检查三个模式、横竖屏、播放与暂停、减少动效和普通 LRC，保存 Android 整页及 WebView 实际截图，并读取绘制后的 GPU 像素排除空白画布。该入口和 WebView 调试开关不进入 Release APK。

本轮本机结果：Android 模拟器 6 组全部通过，竖屏舞台为 600 × 672 CSS px，横屏为 534 × 520 CSS px；三个模式的 GPU 像素均有实际内容，播放画面变化且暂停画面稳定。WebGL 浏览器回归 9 项、Node 时钟与歌词测试 4 项通过；Android 单元测试 241 项中 240 项通过、1 项因 Windows 环境跳过，Lint 0 错误、7 警告。截图使用 Android 系统合成画面裁剪，避开旧 WebView 调试截图遗漏 GPU 图层的问题。

这些验证使用 Android 模拟器，不等同于用户手机、所有 GPU 或 Android 12 系统 WebView 的实测。复现和回归脚本随完整源码交付，执行方法见 `visualizer/README.md`。

## 分发

版本为 1.4.3 / 13，沿用固定发布签名。公开构建清空私有服务配置；本地交付包保留已有私有音源配置。B站 Cookie 仍只存 CF Secret，不写入本地或 APK。本次显示修复不改变 CF B站上游 `-412` 的处理状态。
