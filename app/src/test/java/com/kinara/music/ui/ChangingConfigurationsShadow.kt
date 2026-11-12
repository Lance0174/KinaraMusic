/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import android.app.Activity
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowActivity

/**
 * 只在测试里存在的「这个 Activity 正在为了配置变更重建」开关。
 *
 * Robolectric 没有给 [Activity.isChangingConfigurations] 做影子实现，真机上"重建时不复原、
 * 由新实例接着拿住记录"这条分支在单测里永远走不到——于是"重建之后又把锁当成原状记住"
 * 这个 1.4.5 的真实缺陷，任何用例都抓不住。补一个影子，让这条分支能被真的走一遍。
 */
@Implements(Activity::class)
class ChangingConfigurationsShadow : ShadowActivity() {

    companion object {
        @JvmStatic var changingConfigurations: Boolean = false
    }

    @Implementation
    fun isChangingConfigurations(): Boolean = changingConfigurations
}
