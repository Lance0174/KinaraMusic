# Kinara R8 规则
# Compose 自带 consumer rules；此处补齐"反射可达但静态分析不可达"的媒体播放域。
#
# 为什么整域 keep（教训：release 3.1MB 版缺 PlaybackService/ExoPlayer/MediaController）：
# MediaController 由 MediaController.Builder 经 SessionToken 反射连接 PlaybackService，
# 系统媒体控件也经 manifest 服务名反射拉起——R8 全模式下静态可达性分析不到这条边。

# ---- 项目播放域：入口服务与 VM（防"不可达"误删）----
-keep class com.kinara.music.player.** { *; }

# ---- Media3 全量保留（体积可接受：核心 dex ~1.5MB）----
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ---- 崩溃堆栈可读性 ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
