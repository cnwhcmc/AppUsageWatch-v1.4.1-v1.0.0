package com.usagewatch.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.usagewatch.app.util.SettingsStore

/**
 * 开机自启：设备重启后恢复采集（仅当总开关开启时）。
 * 注意：BOOT_COMPLETED 是系统允许启动前台服务的少数例外之一；
 *       国产 ROM 需要用户在"自启动管理"中允许本应用（保活向导引导）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // 总开关关闭时不恢复采集，避免用户关闭后又被开机拉起
        if (!SettingsStore.monitoringEnabled()) {
            Log.i("BootReceiver", "总开关关闭，跳过开机自启")
            return
        }
        Log.i("BootReceiver", "开机自启，恢复采集服务")
        MonitoringService.start(context)
    }
}
