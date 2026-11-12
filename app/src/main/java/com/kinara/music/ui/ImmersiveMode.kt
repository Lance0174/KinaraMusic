/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kinara.music.data.LyricRenderer

/** 「还没有记录过进入前的方向」的哨兵值；用 0 以上的真实常量都不合适，所以取一个不可能的负数。 */
internal const val ORIENTATION_UNSET = -99

/** Opening a player never rotates it; landscape requires its explicit entry button. */
internal enum class PlayerEntry { MINI_PLAYER, SETTINGS_PREVIEW, EXPLICIT_LANDSCAPE }

/** Only the selected visualizer supports the explicit landscape experience. */
internal fun locksLandscape(entry: PlayerEntry, renderer: LyricRenderer): Boolean =
    entry == PlayerEntry.EXPLICIT_LANDSCAPE && renderer == LyricRenderer.FOLIA_LUMIERE

/**
 * 进入前的方向只记录一次，之后就保持不变。
 *
 * 这个函数存在的唯一理由是一个真实踩到的坑：锁横屏会重建 Activity（manifest 没有 configChanges），
 * 重建后系统已经把 requestedOrientation 恢复成 SENSOR_LANDSCAPE。如果此时再"现读当前值当原状"，
 * 记住的就是自己刚写下去的锁——退出时"复原"成 SENSOR_LANDSCAPE 等于什么都没做，
 * 用户退出动态视界后整机仍然锁在横屏，转到竖屏毫无反应，直到进程结束。
 * 所以必须是「已记录就不再覆盖」，而不是「每次重新读一遍」。
 */
internal fun recordOrientationOnce(recorded: Int, current: Int): Int =
    if (recorded == ORIENTATION_UNSET) current else recorded

/**
 * 退出时到底该写回哪个方向。
 *
 * 另一个真实缺陷：旧实现在"从来没有锁过"的情况下也会走复原分支，把哨兵值 -99 直接写进
 * `requestedOrientation`。那不是任何合法常量，等于把方向交给系统去猜。没记录过就返回 null，
 * 调用方什么都不写。
 */
internal fun orientationToRestore(recorded: Int): Int? = recorded.takeIf { it != ORIENTATION_UNSET }

/** 从任意 Context 往上找宿主 Activity；Compose 预览等场景可能拿不到。 */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * 横屏沉浸全屏（对齐 Folia 网页版的做法）。
 *
 * 进入时把窗口锁成横屏并隐藏状态栏/导航栏，退出时恢复进入前的方向与系统栏行为。
 *
 * 这个 effect 由 [KinaraApp] 持有，而不是由播放页持有：播放页可能因为锁横屏重建、
 * 也可能因为下载跳转/歌词设置跳转直接消失。只要宿主还在组合里，"退出即复原"就一定会发生，
 * 不会把锁泄漏到进程结束。
 *
 * 复原只发生在两种情形：`enabled` 从 true 变 false（当前实例的旧 effect 先 dispose），
 * 或者组合整体离开（页面关闭）。**转屏重建不复原**——旧实例正在死掉，
 * 写方向只会制造一次多余的翻转；新实例会用 rememberSaveable 里保存的原状重新上锁。
 */
@Composable
internal fun LandscapeImmersive(enabled: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    val view = LocalView.current
    // Subscribe to configuration changes, but do not dispose/recreate for ordinary rotation.
    val configuration = LocalConfiguration.current
    val multiWindow = remember(configuration, activity) { activity.isInMultiWindowMode }
    // rememberSaveable 能跨 Activity 重建存活，这是"只记一次"和"退出必复原"能同时成立的前提。
    var orientationBefore by rememberSaveable { mutableStateOf(ORIENTATION_UNSET) }
    var barsBehaviorBefore by rememberSaveable { mutableStateOf(ORIENTATION_UNSET) }
    DisposableEffect(enabled, activity, multiWindow) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        fun restore() {
            val target = orientationToRestore(orientationBefore) ?: return
            activity.requestedOrientation = target
            if (barsBehaviorBefore != ORIENTATION_UNSET) controller.systemBarsBehavior = barsBehaviorBefore
            controller.show(WindowInsetsCompat.Type.systemBars())
            orientationBefore = ORIENTATION_UNSET
            barsBehaviorBefore = ORIENTATION_UNSET
        }
        if (!enabled || multiWindow) {
            // 多窗口/分屏下 requestedOrientation 本来就会被系统忽略，硬锁只会白隐藏系统栏。
            // 上一次实例可能是在重建/退出过程中把复原留给了这里；没有记录就什么都不写，
            // 绝不能把哨兵值当方向写回去。
            restore()
            return@DisposableEffect onDispose {}
        }
        orientationBefore = recordOrientationOnce(orientationBefore, activity.requestedOrientation)
        if (barsBehaviorBefore == ORIENTATION_UNSET) barsBehaviorBefore = controller.systemBarsBehavior
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // 允许从屏幕边缘临时唤出系统栏，避免全屏后用户回不到桌面。
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        // 队列面板、歌词校准、倍速选择都是独立窗口：它们拿到焦点时系统会把系统栏放回来，
        // 而且不会自己再藏。回到本窗口时补一次 hide，否则锁还在、栏却一直露着。
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        onDispose {
            view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            if (!activity.isChangingConfigurations) restore()
        }
    }
}
