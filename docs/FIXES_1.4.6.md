# 1.4.6 横屏锁的范围与复原

本文记录 1.4.6 的历史行为。1.5.0 已改为播放页主动点击「横屏」，只显示动态歌词与进度条，见 [当前说明](MIUIX_1.5.0.md)。

本文只记录「锁横屏」这一件事：范围怎么定、复原怎么写、怎么验证。其余 1.4.6 改动见 README。

## 约束（产品原话）

> 锁横屏这事，仅限在开启动态视界并且点击歌曲进入播放页的时候，播放页的背景可以是专辑封面或歌曲封面。

"点击歌曲进入播放页"指的就是**点导航栏上方那台迷你播放器**（上面放着正在播的那首）。拆成三条可验收的规则：

1. 触发条件 = 动态视界（`LyricRenderer.FOLIA_LUMIERE`）**且** 本次播放页是从迷你播放器打开的。
2. 锁的对象是**整张播放页**，不是歌词渲染器：停在封面页也要保持横屏。
3. 播放页的背景允许是封面本身，不要求歌词引擎在屏。

## 1.4.5 / 1.4.6 旧实现为什么不合规

| 现象 | 旧代码 | 后果 |
| --- | --- | --- |
| 锁挂在歌词页上 | `PlayScreen.kt` 的 `LandscapeImmersive(fullscreenVisual)`，`isFullscreenVisual(mode, renderer)` 要求 `mode != COVER` | 停在封面页立刻解锁，「锁整张播放页」根本做不到 |
| 只看渲染器、不看入口 | 只要 `settings.lyricRenderer == FOLIA_LUMIERE` 就锁 | 「仅限」失效：从设置里的「查看当前歌曲效果」进预览也会横屏 |
| 复原被跳过 | `DisposableEffect(enabled)` 的 `onDispose` 里 `if (!enabled)`，闭包捕获的是旧值 `true` | 「返回封面」后方向锁还在，只能靠关闭播放页兜底 |
| 复原写哨兵值 | 没锁过也会走复原分支，把 `ORIENTATION_UNSET = -99` 写进 `requestedOrientation` | 写入非法方向常量 |

## 现在的实现

- **谓词**：`ImmersiveMode.kt` 的 `locksLandscape(entry, renderer)`，只认 `PlayerEntry.MINI_PLAYER` + 动态视界。`SETTINGS_PREVIEW` 不锁；点歌曲行根本不开播放页，也就进不到这个判断。
- **入口**：迷你播放器点击 → `openPlayer(PlayerEntry.MINI_PLAYER)` 并打开播放页。点歌曲行、「播放全部／随机」、搜索、下载、导入预览都只 `playQueue`，**不开播放页**，因此也不进入锁的判断。设置里的「查看当前歌曲效果」→ `SETTINGS_PREVIEW`，同样不开锁。
- **宿主**：`LandscapeImmersive` 挂在 `KinaraApp`（不是播放页里），所以播放页因为锁横屏重建、或因为下载/歌词设置跳转消失，都不会把锁留在系统上。
- **复原**：`enabled` 变假立即复原；页面关闭时在 `onDispose` 复原；转屏重建（`isChangingConfigurations`）不复原，由新实例按 `rememberSaveable` 里保存的原状重新上锁。`orientationToRestore` 保证没记录过就什么都不写。
- **返回键**：只有"整页沉浸"（渲染器全屏或锁横屏）时由播放页接管：歌词页 → 封面页，封面页 → 关页。经典／逐字辉光的歌词页仍由宿主返回键直接关页，返回语义不因横屏锁被顺手改掉。
- **系统栏**：进入时隐藏并允许边缘唤出；队列面板／校准／倍速等独立窗口关闭、窗口重新拿到焦点时补一次 `hide`。
- **多窗口**：`activity.isInMultiWindowMode` 为真时不上锁（系统本来就会忽略 `requestedOrientation`，硬锁只会白隐藏系统栏）。
- **渲染**：`PlayScreen.kt` 的横屏沉浸分支把封面画成两层——裁切 + 压暗 + 模糊打底、`ContentScale.Fit` 前景，方形封面在 20:9 窗口里不再被裁掉上下半张；顶栏与控制条切亮色前景（`chromeSpec`），并在锁横屏时补 `displayCutout` 内边距（Folia 歌词页系统栏还在，不补第二次）。
- **点击落点**："查看歌词"的可点区域只有中间那块画布，不铺在整屏——否则顶栏文字、控制条之间的空隙都会被当成"点封面"。

## 验证

- `:app:testDebugUnitTest`
  - `PlayScreenVisualModeTest.landscape lock only applies to the mini player entry with dynamic visual`：入口 × 渲染器的判定矩阵（迷你播放器／设置预览 × 动态视界，迷你播放器 × 经典／逐字辉光）。
  - `ImmersiveOrientationTest.nothing recorded means nothing is written back`：哨兵值不再写回。
  - `LandscapeLockEffectTest`：真的翻转 `enabled`，断言进入写 `SENSOR_LANDSCAPE`、退出写回进入前的方向；从未上锁的宿主离开时一个字都不写；重建用例配合测试专用的 `ChangingConfigurationsShadow` 让"重建时不复原"那条分支真的走到，断言记住的仍是真实原状（把 `recordOrientationOnce` 换成"每次现读"该用例会红，已做变异验证）。
  - `LandscapeLockDisposeTest`：`enabled` 没变假、整个宿主被移出组合（关页）时也必须复原。
  - `MultiWindowLandscapeTest`：分屏下一行都不写。
  - `LockedLandscapeBackTest`：锁横屏停在封面页按返回必须真的关页，且只关一次。
  - `FullscreenVisualTest.locked landscape play page shows the cover as its background`：封面页即是背景、无翻页容器，点画布能进歌词（点完顶栏变成「返回封面」）。
- 手测（需要真机/模拟器，本仓库的 Robolectric 只验证写入的整数，不旋转窗口）：
  1. 动态视界开启 → 点导航栏上方的迷你播放器 → 播放页横屏、封面铺满、系统栏隐藏。
  2. 同一次进入后按「返回封面」→ 仍横屏，背景是封面；再按返回 → 关页并回到竖屏。
  3. 关页后再从迷你播放器进入（动态视界仍开）→ 仍然横屏；把渲染器切到经典 → 进入不再横屏。
  4. 设置里「查看当前歌曲效果」→ 不横屏。
  5. 点任意歌曲行、「播放全部」「随机播放」→ 只开始播放，不跳播放页、不横屏。
  6. 横屏状态下打开队列面板再关闭 → 系统栏重新隐藏。
  7. 分屏／自由窗口 → 不横屏、不隐藏系统栏。
  8. 锁定状态下旋转设备 → 仍横屏；退出后回到进入前的方向。
  9. 锁定态停在封面页，点顶栏中间的「已切换至…」文字或控制条之间的空隙 → 不跳歌词页；点中间封面区域 → 进歌词页。
