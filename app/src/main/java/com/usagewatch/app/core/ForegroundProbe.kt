package com.usagewatch.app.core

import android.util.Log

/**
 * 前台应用探针（SHELL/ROOT 增强）：
 * 通过 `dumpsys activity activities` 解析当前真正在前台的 Activity 包名，
 * 用于校准 UsageStats 事件流（系统事件可能延迟/丢失导致的段错位）。
 *
 * 解析失败/无提权通道一律返回 null，调用方按"无校准"降级。
 */
object ForegroundProbe {

    private const val TAG = "ForegroundProbe"

    /** 锁屏/系统界面等不参与校准的包名特征（避免把系统界面误判为前台应用） */
    private val IGNORE_PATTERNS = listOf(
        "keyguard", "systemui", "com.android.systemui", "launcher", "trebuchet"
    )

    /**
     * 获取当前前台 Activity 的包名；无提权通道或解析失败返回 null。
     * 只读命令，不触发授权弹窗。
     *
     * 命令在 su 内用 grep 精简输出（部分 ROM 的 dumpsys activity activities 全量
     * 输出可达数 MB，读全量会超时/占内存）——只取前台 Activity 行，秒级返回。
     */
    fun currentForegroundPackage(): String? {
        if (!CapabilityManager.canQueryProcesses()) return null
        val out = ShellExecutor.execute(
            "dumpsys activity activities | grep -E 'mResumedActivity|ResumedActivity'"
        ) ?: return null
        val pkg = parse(out) ?: return null
        if (IGNORE_PATTERNS.any { pkg.contains(it, ignoreCase = true) }) return null
        return pkg
    }

    /**
     * 解析 dumpsys 输出中的前台 Activity 包名。
     * 兼容两种常见行格式：
     *   mResumedActivity: ActivityRecord{u0 com.pkg/.Activity t123}
     *   ResumedActivity: ActivityRecord{u0 com.pkg/.Activity t123}
     */
    private fun parse(dumpsysOut: String): String? {
        val regex = Regex("""(?:mResumedActivity|ResumedActivity): ActivityRecord\{[^}]*\s([\w.]+)/""")
        return regex.find(dumpsysOut)?.groupValues?.getOrNull(1)
    }
}
