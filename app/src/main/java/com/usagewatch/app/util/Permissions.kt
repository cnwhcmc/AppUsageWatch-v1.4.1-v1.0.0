package com.usagewatch.app.util

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.usagewatch.app.R

/**
 * 权限检测与请求入口（全部按 Android 版本分层，与 Manifest 声明一致）。
 * 统一在此判断，避免 UI 层散落版本分支。
 */
object Permissions {

    // ==================== 使用情况访问（核心，特殊权限） ====================

    /**
     * 是否已开启"使用情况访问"。未开启则前台时间线无数据。
     *
     * 稳定性：AppOps 服务可能抛异常、部分 ROM 返回异常值，全部兜底为 false（视为未开启），
     * 由 UI 引导用户前往系统设置确认；避免异常崩溃或误判为已开启。
     */
    @Suppress("DEPRECATION") // 三参 checkOpNoThrow 为跨版本兼容的唯一选择
    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
            // MODE_DEFAULT（未明确设置）在绝大多数 ROM 上等同于未授权，视为 false 引导用户确认
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    fun openUsageAccessSettings(context: Context) {
        safeStart(context) {
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    // ==================== 存储（按版本分层） ====================

    /** 本机当前需要运行时申请的存储权限（无则返回空数组）。 */
    fun requiredStoragePermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> emptyArray() // 走 MANAGE_EXTERNAL_STORAGE（见下）
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    /** Android 11+："所有文件访问"是否已开启。 */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    fun openAllFilesAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // 部分 ROM 无应用级入口：退回系统总入口
            val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(fallback)
            } catch (_: Exception) {
                openAppDetailsSettings(context)
            }
        }
    }

    /** 存储权限是否基本满足（能读公共目录即可启动文件监控）。 */
    fun hasStorageAccess(context: Context): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
                    PackageManager.PERMISSION_GRANTED
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> hasAllFilesAccess()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
            else ->
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    // ==================== 保活：省电白名单 ====================

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 请求加入省电白名单：优先系统授权框，失败则跳设置页。 */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (isIgnoringBatteryOptimizations(context)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ==================== 无障碍（手动可选，默认关闭） ====================

    /** 我们的无障碍服务组件（Manifest 中默认 disabled，由设置页开关启用）。 */
    fun accessibilityComponent(): ComponentName =
        ComponentName("com.usagewatch.app", "com.usagewatch.app.service.AccessibilityProbeService")

    /** 是否已在系统无障碍设置中开启。 */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityEvent.TYPES_ALL_MASK)
        return enabled.any {
            it.resolveInfo?.serviceInfo?.packageName == context.packageName &&
                it.resolveInfo?.serviceInfo?.name == accessibilityComponent().className
        }
    }

    fun openAccessibilitySettings(context: Context) {
        safeStart(context) {
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    // ==================== 通知权限（Android 13+） ====================

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    // ==================== 自启动管理（无标准 API，按厂商引导） ====================

    /**
     * 自启动/后台无限制没有统一 API，按厂商引导用户手动开启。
     * 骨架阶段提供通用应用详情页跳转；厂商深链接（MIUI/ColorOS 等）在 POC 阶段按需补充。
     */
    fun openAppDetailsSettings(context: Context) {
        safeStart(context) {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
        }
    }

    /**
     * 统一安全跳转：部分 ROM 缺失某些系统设置页（ActivityNotFoundException），
     * 捕获后回退到应用详情页；连详情页都无法打开则静默放弃。绝不因跳转失败崩溃。
     */
    private fun safeStart(context: Context, intentBuilder: () -> Intent) {
        try {
            context.startActivity(intentBuilder().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                // 连详情页都无法打开：静默放弃，不影响应用
            }
        }
    }

    /** 是否已授予通知渠道建立所需权限（用于服务启动时的兜底判断）。 */
    fun ensureNotificationChannel(context: Context) {
        // NotificationChannel 为 API 26+ 类，低版本（minSdk 25）直接返回
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return // 阉割系统可能无通知服务：不崩溃，静默跳过
            val channel = android.app.NotificationChannel(
                MonitoringChannels.CHANNEL_ID,
                context.getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        } catch (_: Exception) {
            // 通知能力缺失/异常：调用方已隔离，这里兜底不抛
        }
    }
}

/** 通知渠道常量集中定义，供服务与工具共用。 */
object MonitoringChannels {
    const val CHANNEL_ID = "monitor_service"
}
