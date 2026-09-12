package com.usagewatch.app.util

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.core.content.ContextCompat

/**
 * 应用图标/名称解析（仅用于图形辨识模式的展示层）。
 * 纯代码模式不需要本类。
 *
 * 说明：
 *  - 图标**不是从网络下载**的：目标应用的图标随其 APK 安装在本机系统包管理中，
 *    本类只读取一次并放入 LruCache 缓存，此后同包名直接复用同一 Drawable，
 *    滚动/重复行不会重复解析（一次进程内最多解析一次）。
 *  - 读取其他应用信息依赖 Manifest 中 <queries> 的包可见性声明；
 *    解析失败（包被卸载/不可见/ROM 异常）一律回退：名称用包名、图标用系统默认图标，
 *    绝不抛异常影响列表渲染。
 */
object AppInfoResolver {

    data class AppInfo(val label: String, val icon: Drawable)

    private val cache = LruCache<String, AppInfo>(256)

    fun resolve(context: Context, packageName: String): AppInfo {
        cache.get(packageName)?.let { return it }
        var label = packageName
        var icon: Drawable? = null
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            label = pm.getApplicationLabel(appInfo).toString()
            @Suppress("DEPRECATION")
            icon = appInfo.loadIcon(pm)
        } catch (_: PackageManager.NameNotFoundException) {
            // 包已卸载或不可见：回退包名
        } catch (_: Exception) {
            // 任何解析异常都不应影响日志展示
        }
        // 图标兜底：解析失败给系统默认应用图标，保证图形模式每行都有辨识占位
        val safeIcon = icon ?: runCatching {
            ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon)
        }.getOrNull()
        val info = AppInfo(label, safeIcon ?: DefaultIconHolder.placeholder(context))
        cache.put(packageName, info)
        return info
    }
}

/** 双保险兜底：极端情况下连系统默认图标都拿不到时，退化为一个纯色圆角占位 Drawable。 */
internal object DefaultIconHolder {
    fun placeholder(context: Context): Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xFF9E9E9E.toInt())
            setSize(dp(context, 24), dp(context, 24))
        }

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}
