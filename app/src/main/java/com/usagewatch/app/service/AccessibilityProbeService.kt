package com.usagewatch.app.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍探针（手动可选增强，Manifest 中默认 disabled）。
 *
 * 设计约束（按需求）：
 *  - 默认不启用、不做任何自动引导，避免与按钮/界面操作冲突；
 *  - 用户仅在设置页手动开启后生效；
 *  - 只做"窗口切换监听"（TYPE_WINDOW_STATE_CHANGED），用于前台切换的实时交叉验证，
 *    不拦截按钮、不抢焦点、不自动点击任何控件。
 *
 * POC 阶段用途：与 UsageStats 事件流比对，评估实时性差距；暂不写入主日志，
 * 避免与 UsageStats 数据重复。
 */
class AccessibilityProbeService : AccessibilityService() {

    companion object {
        private const val TAG = "AccessibilityProbe"

        /** 最近一次前台窗口所属包（交叉验证数据源，POC 阶段供比对） */
        @Volatile
        var lastForegroundPackage: String? = null
            private set
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        Log.d(TAG, "窗口切换 → $pkg @ ${System.currentTimeMillis()}")
        lastForegroundPackage = pkg
    }

    override fun onInterrupt() {
        // 系统要求中断时清理状态
        lastForegroundPackage = null
    }
}
