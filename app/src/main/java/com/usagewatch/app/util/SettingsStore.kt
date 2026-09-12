package com.usagewatch.app.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 轻量设置存储（SharedPreferences）。
 * 全部设置项集中于此，供 UI 与采集逻辑共用，保证单一数据源。
 */
object SettingsStore {

    /** 日志显示模式：GUI = 图形辨识（图标+名称）；CODE = 纯代码（原始字段） */
    enum class DisplayMode { GUI, CODE }

    private const val PREF_NAME = "app_usage_watch_settings"
    private lateinit var prefs: SharedPreferences

    // ---- Keys ----
    private const val KEY_DISPLAY_MODE = "display_mode"
    private const val KEY_RETENTION_DAYS = "retention_days"
    private const val KEY_EXPORT_DIR_URI = "export_dir_uri"
    private const val KEY_SAMPLE_INTERVAL_MS = "sample_interval_ms"
    private const val KEY_MONITOR_DIRS = "monitor_dirs"
    private const val KEY_BG_ESTIMATE = "bg_estimate_enabled"
    private const val KEY_LAST_EVENT_TIME = "last_event_time"
    private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
    private const val KEY_USAGE_GUIDE_SHOWN = "usage_guide_shown"
    private const val KEY_MONITOR_WHOLE_STORAGE = "monitor_whole_storage"
    private const val KEY_FORCE_NORMAL_MODE = "force_normal_mode"
    private const val KEY_NOTIFICATION_VISIBLE = "notification_visible"

    /** 默认监控目录（相对 /storage/emulated/0 的公共目录） */
    val DEFAULT_MONITOR_DIRS: Set<String> = setOf(
        "Download", "DCIM", "Pictures", "Documents", "Movies", "Music"
    )

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    // ---- 监控总开关（默认开启：装上即记录，关闭后彻底停止后台运行与开机自启）----
    fun monitoringEnabled(): Boolean = prefs.getBoolean(KEY_MONITORING_ENABLED, true)

    fun setMonitoringEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MONITORING_ENABLED, enabled).apply()
    }

    // ---- 显示模式 ----
    fun displayMode(): DisplayMode =
        if (prefs.getString(KEY_DISPLAY_MODE, DisplayMode.GUI.name) == DisplayMode.CODE.name) {
            DisplayMode.CODE
        } else {
            DisplayMode.GUI
        }

    fun setDisplayMode(mode: DisplayMode) {
        prefs.edit().putString(KEY_DISPLAY_MODE, mode.name).apply()
    }

    // ---- 导出目录（null/空 = 默认 Download）----
    fun exportDirUri(): String? = prefs.getString(KEY_EXPORT_DIR_URI, null)

    fun setExportDirUri(uri: String?) {
        prefs.edit().putString(KEY_EXPORT_DIR_URI, uri).apply()
    }

    /** 是否使用默认 Download 目录。 */
    fun isDefaultExportDir(): Boolean = exportDirUri().isNullOrEmpty()

    // ---- 日志保留天数（0 = 永久保留）----
    fun retentionDays(): Int = prefs.getInt(KEY_RETENTION_DAYS, 7)

    fun setRetentionDays(days: Int) {
        prefs.edit().putInt(KEY_RETENTION_DAYS, days).apply()
    }

    /** 清理阈值时间戳（毫秒）；retentionDays=0 时返回 0（表示不清理） */
    fun retentionCutoffMillis(now: Long): Long {
        val days = retentionDays()
        return if (days <= 0) 0L else now - days * 24L * 60L * 60L * 1000L
    }

    // ---- 采样间隔（毫秒）----
    fun sampleIntervalMs(): Long = prefs.getLong(KEY_SAMPLE_INTERVAL_MS, 30_000L)

    fun setSampleIntervalMs(ms: Long) {
        prefs.edit().putLong(KEY_SAMPLE_INTERVAL_MS, ms).apply()
    }

    // ---- 文件监控目录（目录名集合，相对公共根 /storage/emulated/0）----
    fun monitorDirs(): Set<String> =
        prefs.getStringSet(KEY_MONITOR_DIRS, DEFAULT_MONITOR_DIRS) ?: DEFAULT_MONITOR_DIRS

    fun setMonitorDirs(dirs: Set<String>) {
        prefs.edit().putStringSet(KEY_MONITOR_DIRS, dirs).apply()
    }

    // ---- 文件监控范围：false=指定目录；true=整个公共存储（全盘，需存储/所有文件访问权限）----
    fun monitorWholeStorage(): Boolean = prefs.getBoolean(KEY_MONITOR_WHOLE_STORAGE, false)

    fun setMonitorWholeStorage(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MONITOR_WHOLE_STORAGE, enabled).apply()
    }

    // ---- 权限模式：false=自动（检测到高权限自动增强）；true=强制普通模式（省电，不使用提权）----
    fun forceNormalMode(): Boolean = prefs.getBoolean(KEY_FORCE_NORMAL_MODE, false)

    fun setForceNormalMode(force: Boolean) {
        prefs.edit().putBoolean(KEY_FORCE_NORMAL_MODE, force).apply()
    }

    /** 通知栏常驻通知：开=显示（含"停止"按钮）；关=最小化（无按钮，防误触）。 */
    fun notificationVisible(): Boolean = prefs.getBoolean(KEY_NOTIFICATION_VISIBLE, true)

    fun setNotificationVisible(visible: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_VISIBLE, visible).apply()
    }

    // ---- 后台估算开关 ----
    fun backgroundEstimateEnabled(): Boolean = prefs.getBoolean(KEY_BG_ESTIMATE, true)

    fun setBackgroundEstimateEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BG_ESTIMATE, enabled).apply()
    }

    // ---- 增量采集游标：上次已处理的事件时间戳 ----
    fun lastEventTime(): Long = prefs.getLong(KEY_LAST_EVENT_TIME, 0L)

    fun setLastEventTime(t: Long) {
        prefs.edit().putLong(KEY_LAST_EVENT_TIME, t).apply()
    }

    /** 使用情况访问引导是否已展示过（避免每次返回应用都弹窗） */
    fun isUsageGuideShown(): Boolean = prefs.getBoolean(KEY_USAGE_GUIDE_SHOWN, false)

    fun setUsageGuideShown(shown: Boolean) {
        prefs.edit().putBoolean(KEY_USAGE_GUIDE_SHOWN, shown).apply()
    }
}
