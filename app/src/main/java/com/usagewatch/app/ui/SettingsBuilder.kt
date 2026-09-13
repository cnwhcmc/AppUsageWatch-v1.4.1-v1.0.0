package com.usagewatch.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.usagewatch.app.MainActivity
import com.usagewatch.app.R
import com.usagewatch.app.core.CapabilityManager
import com.usagewatch.app.data.DbHelper
import com.usagewatch.app.data.LogRepository
import com.usagewatch.app.service.MonitoringService
import com.usagewatch.app.util.Permissions
import com.usagewatch.app.util.SettingsStore
import java.io.File

/**
 * 设置页动态构建器（对应 activity_main.xml 中空的 settingsContainer）。
 * 全部设置项集中于此，变更后通过 [onSettingsChanged] 刷新界面与服务。
 */
class SettingsBuilder(
    private val activity: AppCompatActivity,
    private val container: LinearLayout,
    private val onSettingsChanged: () -> Unit
) {

    private val ctx: Context = activity

    /** 服务状态行的值视图引用，供外部在服务状态变化时刷新（updateStatus） */
    private var statusValueTv: TextView? = null

    /** 各设置行的值刷新器（值文本 + 取值函数），refreshValues 时统一重算 */
    private val valueRefreshers = mutableListOf<Pair<TextView, () -> String>>()

    fun build() {
        container.removeAllViews()
        valueRefreshers.clear()
        addSection("监控")
        // 总开关统一在主界面顶部（swMaster），此处仅保留服务状态行，避免重复开关
        statusValueTv = addStatusRow()
        addSection("权限与保活")
        addRow(R.string.setting_usage_access, valueProvider = { usageAccessValue() }) {
            Permissions.openUsageAccessSettings(ctx)
            onSettingsChanged()
        }
        addRow(R.string.setting_notification, valueProvider = { notificationValue() }) {
            (activity as? MainActivity)?.requestRuntimePermissionsIfNeeded()
            onSettingsChanged()
        }
        addRow(R.string.setting_storage_access, valueProvider = { storageAccessValue() }) {
            onStorageAccessClick()
        }
        addRow(R.string.setting_battery, valueProvider = { batteryValue() }) {
            if (com.usagewatch.app.core.KeepAliveHelper.canUseRootKeepAlive()) {
                applyRootKeepAlive()
            } else {
                Permissions.requestIgnoreBatteryOptimizations(ctx)
            }
        }
        addRow(R.string.setting_autostart, valueProvider = { "无标准 API，按厂商设置" }) {
            Permissions.openAppDetailsSettings(ctx)
        }
        addRow(R.string.setting_keepalive, valueProvider = { "逐项检测与跳转" }) { showKeepAliveDialog() }
        // 权限模式：直接拨动开关切换（开=普通省电，关=自动检测高权限增强）
        addSwitchRowWithValue(
            R.string.setting_privilege,
            SettingsStore.forceNormalMode(),
            valueProvider = { CapabilityManager.label() }
        ) { on ->
            SettingsStore.setForceNormalMode(on)
            // 探测执行（进程/命令）放后台线程，避免卡 UI；完成后刷新设置行显示值并反馈
            Thread {
                try {
                    CapabilityManager.refresh()
                } catch (_: Exception) {
                }
                runOnUi {
                    onSettingsChanged()
                    refreshValues()
                    Toast.makeText(
                        ctx,
                        if (on) "已切换：普通模式（省电）" else "已切换：自动模式（提权增强）",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.start()
        }

        addSection("日志")
        addRow(R.string.setting_display_mode, valueProvider = { displayModeValue() }) { showDisplayModeDialog() }
        addRow(R.string.setting_retention_days, valueProvider = { retentionValue() }) { showRetentionDialog() }
        addSwitchRow(R.string.setting_bg_estimate, SettingsStore.backgroundEstimateEnabled()) { on ->
            SettingsStore.setBackgroundEstimateEnabled(on)
            onSettingsChanged()
        }
        addRow(R.string.setting_monitor_scope, valueProvider = { monitorScopeValue() }) { showMonitorScopeDialog() }
        addRow(R.string.setting_monitor_dirs, valueProvider = { monitorDirsValue() }) { showMonitorDirsDialog() }
        addRow(R.string.setting_sample_interval, valueProvider = { sampleIntervalValue() }) { showSampleIntervalDialog() }

        addSection("其他")
        // 通知栏常驻通知：开=显示（含"停止"按钮）；关=最小化（无按钮，防误触）
        addSwitchRowWithValue(
            R.string.setting_notification_visible,
            SettingsStore.notificationVisible(),
            valueProvider = {
                if (SettingsStore.notificationVisible()) "显示" else "最小化"
            }
        ) { on ->
            SettingsStore.setNotificationVisible(on)
            if (on) {
                Toast.makeText(ctx, "通知栏已恢复显示", Toast.LENGTH_SHORT).show()
            } else {
                // 前台服务通知无法由应用彻底移除：说明 + 引导关闭系统通知权限
                AlertDialog.Builder(activity)
                    .setTitle("通知已最小化")
                    .setMessage("“停止”按钮已隐藏。前台服务必须保留最小通知；彻底隐藏请到系统设置关闭本应用通知。")
                    .setPositiveButton("去系统设置") { _, _ -> openSystemNotificationSettings() }
                    .setNegativeButton("知道了", null)
                    .show()
            }
            rebuildNotification()
            onSettingsChanged()
        }
        addRow(R.string.setting_accessibility, valueProvider = { accessibilityValue() }) { toggleAccessibility() }
        addRow(R.string.setting_version_log, valueProvider = { versionLogValue() }) { showVersionLogDialog() }
        addRow(R.string.setting_storage, valueProvider = { storageValue() }) {
            // 清空全部日志 + 缓存 + DB 收缩（用户主动操作，放后台线程避免主线程卡顿）
            AlertDialog.Builder(activity)
                .setTitle(R.string.setting_storage)
                .setMessage("将删除全部运行日志、文件日志与缓存（不可恢复），确定继续？")
                .setPositiveButton("清空") { _, _ ->
                    Thread {
                        try {
                            val before = storageBytes()
                            val repo = LogRepository(ctx)
                            val n = repo.deleteAll()
                            clearAppCaches()
                            val freedKb = (before - storageBytes()) / 1024
                            runOnUi {
                                Toast.makeText(
                                    ctx,
                                    "已清理：$n 条日志 + 缓存，释放约 ${freedKb} KB",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } catch (e: Exception) {
                            runOnUi { Toast.makeText(ctx, "清理失败：${e.message}", Toast.LENGTH_SHORT).show() }
                        }
                    }.start()
                    onSettingsChanged()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        addRow(R.string.setting_export, valueProvider = { exportValue() }) { showExportDialog() }
    }

    private fun runOnUi(block: () -> Unit) {
        activity.runOnUiThread(block)
    }

    /** 通知栏可见性变更后重建常驻通知（服务在运行才需要；异常静默）。 */
    private fun rebuildNotification() {
        try {
            if (com.usagewatch.app.service.MonitoringService.isRunning()) {
                ctx.startService(
                    Intent(ctx, com.usagewatch.app.service.MonitoringService::class.java)
                        .setAction(com.usagewatch.app.service.MonitoringService.ACTION_REBUILD_NOTIF)
                )
            }
        } catch (_: Exception) {
        }
    }

    /** 跳转系统通知设置（API 26+ 专用页；低版本/阉割系统兜底应用详情页）。 */
    private fun openSystemNotificationSettings() {
        try {
            val i = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (_: Exception) {
            try {
                val i = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            } catch (_: Exception) {
                Toast.makeText(ctx, "无法打开系统通知设置", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== 行构建 ====================

    private fun addSection(title: String) {
        val tv = TextView(ctx).apply {
            text = title
            setTextColor(ctx.getColor(R.color.text_sub))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(20), 0, dp(6))
        }
        container.addView(tv)
    }

    /** 服务状态专用行（值可刷新），返回值视图供 updateStatus 更新。 */
    private fun addStatusRow(): TextView {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val title = TextView(ctx).apply {
            text = ctx.getString(R.string.setting_service_status)
            setTextColor(ctx.getColor(R.color.text_main))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueTv = TextView(ctx).apply {
            setTextColor(ctx.getColor(R.color.text_sub))
            textSize = 13f
            setPadding(dp(8), 0, 0, 0)
        }
        row.addView(title)
        row.addView(valueTv)
        container.addView(row)
        addDivider()
        valueTv.text = serviceStatusValue()
        return valueTv
    }

    /** 刷新服务状态行（MainActivity 在刷新/总开关切换后调用）。 */
    fun updateStatus() {
        statusValueTv?.text = serviceStatusValue()
    }

    /**
     * 刷新所有设置行的值：设置变更后（对话框/开关）与从系统设置页返回后调用，
     * 保证"采样频率改 1 分钟立即显示 1 分钟""授权返回后立即显示已开启"等场景即时生效。
     */
    fun refreshValues() {
        valueRefreshers.forEach { (tv, provider) ->
            try {
                tv.text = provider()
            } catch (_: Exception) {
                // 单行取值异常不影响其余行
            }
        }
        updateStatus()
    }

    private fun serviceStatusValue(): String {
        val last = MonitoringService.lastTickAt()
        val time = if (last > 0) LogRepository.formatTimestamp(last) else "—"
        return if (MonitoringService.isRunning()) {
            ctx.getString(R.string.service_running) + " · " +
                ctx.getString(R.string.service_last_tick, time)
        } else {
            ctx.getString(R.string.service_stopped)
        }
    }

    private fun addRow(titleRes: Int, valueProvider: () -> String, onClick: () -> Unit) {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { onClick() }
        }
        val title = TextView(ctx).apply {
            text = ctx.getString(titleRes)
            setTextColor(ctx.getColor(R.color.text_main))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueTv = TextView(ctx).apply {
            text = valueProvider()
            setTextColor(ctx.getColor(R.color.text_sub))
            textSize = 13f
            setPadding(dp(8), 0, 0, 0)
        }
        row.addView(title)
        row.addView(valueTv)
        container.addView(row)
        addDivider()
        // 收集值刷新器：设置变更 / 从系统设置返回后统一重算该行显示值（修复"改完仍显示旧值"）
        valueRefreshers.add(valueTv to valueProvider)
    }

    private fun addSwitchRow(titleRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val title = TextView(ctx).apply {
            text = ctx.getString(titleRes)
            setTextColor(ctx.getColor(R.color.text_main))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = Switch(ctx).apply {
            isChecked = checked
            setOnCheckedChangeListener { _: CompoundButton, on: Boolean -> onChange(on) }
        }
        row.addView(title)
        row.addView(sw)
        container.addView(row)
        addDivider()
    }

    /** 带状态值的开关行：title + 状态值 + Switch（权限模式等需同时展示当前状态的场景）。 */
    private fun addSwitchRowWithValue(
        titleRes: Int,
        checked: Boolean,
        valueProvider: () -> String,
        onChange: (Boolean) -> Unit
    ) {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val title = TextView(ctx).apply {
            text = ctx.getString(titleRes)
            setTextColor(ctx.getColor(R.color.text_main))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueTv = TextView(ctx).apply {
            text = valueProvider()
            setTextColor(ctx.getColor(R.color.text_sub))
            textSize = 12f
            setPadding(dp(8), 0, dp(4), 0)
        }
        val sw = Switch(ctx).apply {
            isChecked = checked
            setOnCheckedChangeListener { _: CompoundButton, on: Boolean -> onChange(on) }
        }
        row.addView(title)
        row.addView(valueTv)
        row.addView(sw)
        container.addView(row)
        addDivider()
        valueRefreshers.add(valueTv to valueProvider)
    }

    private fun addDivider() {
        val div = View(ctx).apply {
            setBackgroundColor(ctx.getColor(R.color.divider))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1
            )
        }
        container.addView(div)
    }

    private fun dp(v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    // ==================== 值与对话框 ====================

    private fun usageAccessValue(): String =
        if (Permissions.hasUsageAccess(ctx)) "已开启" else "未开启"

    private fun notificationValue(): String = when {
        android.os.Build.VERSION.SDK_INT < 33 -> ctx.getString(R.string.notification_auto)
        Permissions.hasNotificationPermission(ctx) -> ctx.getString(R.string.notification_granted)
        else -> ctx.getString(R.string.notification_denied)
    }

    private fun batteryValue(): String {
        // ROOT 模式：显示 root 保活生效状态（阉割系统无省电设置页时的替代通道）
        if (com.usagewatch.app.core.KeepAliveHelper.canUseRootKeepAlive()) {
            return if (com.usagewatch.app.core.KeepAliveHelper.checkRootKeepAlive()) {
                "Root 保活已生效"
            } else {
                "未生效"
            }
        }
        return if (Permissions.isIgnoringBatteryOptimizations(ctx)) "已在白名单" else "未加入"
    }

    /** 点击执行 root 保活并反馈结果（仅 ROOT 模式调用）。 */
    private fun applyRootKeepAlive() {
        val ok = com.usagewatch.app.core.KeepAliveHelper.applyRootKeepAlive()
        android.widget.Toast.makeText(
            ctx,
            if (ok) "Root 保活已应用（白名单+后台豁免）" else "保活命令执行失败（检查 Root 授权）",
            android.widget.Toast.LENGTH_SHORT
        ).show()
        onSettingsChanged()
    }

    private fun accessibilityValue(): String =
        if (Permissions.isAccessibilityEnabled(ctx)) "已开启" else "未开启"

    private fun displayModeValue(): String =
        if (SettingsStore.displayMode() == SettingsStore.DisplayMode.CODE) "纯代码" else "图形辨识"

    private fun retentionValue(): String {
        val d = SettingsStore.retentionDays()
        return if (d <= 0) "永久保留" else "$d 天"
    }

    private fun monitorScopeValue(): String =
        if (SettingsStore.monitorWholeStorage()) "整个公共存储（全盘）"
        else "指定目录（${SettingsStore.monitorDirs().size} 个）"

    private fun monitorDirsValue(): String {
        if (SettingsStore.monitorWholeStorage()) return "全盘模式（此配置在指定目录模式生效）"
        return "${SettingsStore.monitorDirs().size} 个目录"
    }

    private fun storageAccessValue(): String = when {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R ->
            if (Permissions.hasAllFilesAccess()) "所有文件访问：已开启"
            else "所有文件访问：未开启"
        else -> if (Permissions.hasStorageAccess(ctx)) "存储权限：已授予" else "存储权限：未授予"
    }

    private fun sampleIntervalValue(): String {
        val ms = SettingsStore.sampleIntervalMs()
        return when (ms) {
            10_000L -> "10 秒"
            30_000L -> "30 秒"
            60_000L -> "1 分钟"
            300_000L -> "5 分钟"
            else -> "${ms / 1000} 秒"
        }
    }

    private fun storageValue(): String {
        val total = storageBytes()
        return "${total / 1024} KB"
    }

    /** 存储占用（DB + 日志文件 + 应用缓存），用于展示与清理前后对比。 */
    private fun storageBytes(): Long {
        var total = 0L
        try {
            val db = File(ctx.applicationContext.getDatabasePath(DbHelper.DB_NAME).absolutePath)
            if (db.exists()) total += db.length()
            ctx.getExternalFilesDir("logs")?.listFiles()?.forEach { total += it.length() }
            ctx.cacheDir.listFiles()?.forEach { total += it.length() }
            ctx.externalCacheDir?.listFiles()?.forEach { total += it.length() }
        } catch (_: Exception) {
            // 统计失败返回已累计值
        }
        return total
    }

    /** 清空应用缓存目录（日志文件已由 LogRepository.deleteAll 处理）。 */
    private fun clearAppCaches() {
        try {
            ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            ctx.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        } catch (_: Exception) {
            // 缓存清理失败不影响主流程
        }
    }

    private fun showDisplayModeDialog() {
        val options = arrayOf("图形辨识（图标+名称）", "纯代码（原始字段）")
        val current = if (SettingsStore.displayMode() == SettingsStore.DisplayMode.CODE) 1 else 0
        AlertDialog.Builder(activity)
            .setTitle(R.string.setting_display_mode)
            .setSingleChoiceItems(options, current) { d, which ->
                SettingsStore.setDisplayMode(
                    if (which == 0) SettingsStore.DisplayMode.GUI else SettingsStore.DisplayMode.CODE
                )
                d.dismiss()
                onSettingsChanged()
            }
            .show()
    }

    private fun showRetentionDialog() {
        val labels = arrayOf("1 天", "3 天", "7 天", "30 天", "永久保留")
        val values = intArrayOf(1, 3, 7, 30, 0)
        val current = values.indexOf(SettingsStore.retentionDays()).coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle(R.string.setting_retention_days)
            .setSingleChoiceItems(labels, current) { d, which ->
                SettingsStore.setRetentionDays(values[which])
                d.dismiss()
                onSettingsChanged()
            }
            .show()
    }

    /** 监控范围：指定目录 ↔ 整个公共存储（全盘）。全盘需存储/所有文件访问权限，未授权则引导授权。 */
    private fun showMonitorScopeDialog() {
        val options = arrayOf("指定目录（默认 6 个）", "整个公共存储（全盘）")
        val current = if (SettingsStore.monitorWholeStorage()) 1 else 0
        AlertDialog.Builder(activity)
            .setTitle(R.string.setting_monitor_scope)
            .setSingleChoiceItems(options, current) { d, which ->
                val whole = which == 1
                if (whole && !Permissions.hasStorageAccess(ctx)) {
                    d.dismiss()
                    Toast.makeText(ctx, "全盘监控需先授予存储/所有文件访问权限", Toast.LENGTH_LONG).show()
                    onStorageAccessClick()
                    return@setSingleChoiceItems
                }
                SettingsStore.setMonitorWholeStorage(whole)
                d.dismiss()
                onSettingsChanged()
            }
            .show()
    }

    /** 存储访问入口：Android 11+ 走"所有文件访问"系统页；低版本申请运行时存储权限。 */
    private fun onStorageAccessClick() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (Permissions.hasAllFilesAccess()) {
                Toast.makeText(ctx, "已开启所有文件访问", Toast.LENGTH_SHORT).show()
            } else {
                Permissions.openAllFilesAccessSettings(ctx)
            }
        } else {
            (activity as? MainActivity)?.requestRuntimePermissionsIfNeeded()
        }
        onSettingsChanged()
    }

    /** 指定目录管理：默认目录多选 + 自定义目录（相对名/绝对路径）添加，取消勾选即移除。 */
    private fun showMonitorDirsDialog() {
        val current = SettingsStore.monitorDirs()
        val all = (SettingsStore.DEFAULT_MONITOR_DIRS + current).toList()
        val checked = BooleanArray(all.size) { i -> current.contains(all[i]) }
        AlertDialog.Builder(activity)
            .setTitle(R.string.setting_monitor_dirs)
            .setMultiChoiceItems(all.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNeutralButton("添加目录") { _, _ ->
                showAddDirDialog()
            }
            .setPositiveButton("确定") { d, _ ->
                SettingsStore.setMonitorDirs(all.filterIndexed { i, _ -> checked[i] }.toSet())
                d.dismiss()
                onSettingsChanged()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddDirDialog() {
        val input = android.widget.EditText(ctx).apply {
            hint = "相对名（如 Download/MyFiles）或绝对路径"
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        AlertDialog.Builder(activity)
            .setTitle("添加自定义目录")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val name = input.text.toString().trim().trimEnd('/')
                if (name.isEmpty()) {
                    Toast.makeText(ctx, "目录不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val root = android.os.Environment.getExternalStorageDirectory()
                val dir = if (name.startsWith("/")) java.io.File(name) else java.io.File(root, name)
                if (!dir.isDirectory) {
                    Toast.makeText(ctx, "目录不存在：$name", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val next = SettingsStore.monitorDirs() + name
                SettingsStore.setMonitorDirs(next)
                Toast.makeText(ctx, "已添加：$name", Toast.LENGTH_SHORT).show()
                onSettingsChanged()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSampleIntervalDialog() {
        val labels = arrayOf("10 秒", "30 秒", "1 分钟", "5 分钟")
        val values = longArrayOf(10_000L, 30_000L, 60_000L, 300_000L)
        val current = values.indexOf(SettingsStore.sampleIntervalMs()).coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle(R.string.setting_sample_interval)
            .setSingleChoiceItems(labels, current) { d, which ->
                SettingsStore.setSampleIntervalMs(values[which])
                d.dismiss()
                onSettingsChanged()
            }
            .show()
    }

    /** 保活向导：逐项显示状态并提供跳转。 */
    private fun showKeepAliveDialog() {
        val items = arrayOf(
            "使用情况访问：${usageAccessValue()}",
            "省电白名单：${batteryValue()}",
            "自启动管理：需按厂商手动开启",
            "无障碍服务：${accessibilityValue()}"
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.setting_keepalive)
            .setItems(items) { d, which ->
                when (which) {
                    0 -> Permissions.openUsageAccessSettings(ctx)
                    1 -> if (com.usagewatch.app.core.KeepAliveHelper.canUseRootKeepAlive()) {
                        applyRootKeepAlive()
                    } else {
                        Permissions.requestIgnoreBatteryOptimizations(ctx)
                    }
                    2 -> Permissions.openAppDetailsSettings(ctx)
                    3 -> toggleAccessibility()
                }
                d.dismiss()
            }
            .show()
    }

    /** 无障碍：切换组件启用状态并跳转系统设置（默认关闭，完全手动）。 */
    private fun toggleAccessibility() {
        val target = !Permissions.isAccessibilityEnabled(ctx)
        val pm = ctx.packageManager
        pm.setComponentEnabledSetting(
            Permissions.accessibilityComponent(),
            if (target) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        Permissions.openAccessibilitySettings(ctx)
        onSettingsChanged()
    }

    /**
     * 权限模式选择：自动（检测到高权限自动启用增强）/ 普通模式（省电，不使用提权）。
     * 普通模式时不做任何 su/dumpsys/ps 探测与执行，适合不需要高权限、更在意耗电的场景。
     */
    private fun exportValue(): String =
        "导出到 ${if (SettingsStore.isDefaultExportDir()) "默认 Download" else "自定义目录"}"

    /** 导出日志入口：导出 / 修改导出目录（空值 = 默认 Download）合并在一处，不多余设一行。 */
    private fun showExportDialog() {
        val options = arrayOf(
            "立即导出到当前目录",
            "修改导出目录（当前：${if (SettingsStore.isDefaultExportDir()) "默认 Download" else "自定义"}）"
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.setting_export)
            .setItems(options) { d, which ->
                when (which) {
                    0 -> doExport()
                    1 -> chooseExportDir()
                }
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doExport() {
        Thread {
            try {
                val repo = LogRepository(ctx)
                val path = repo.exportToFile(0L, Long.MAX_VALUE)
                runOnUi {
                    if (path != null) {
                        Toast.makeText(ctx, "已导出：$path", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(ctx, R.string.export_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUi { Toast.makeText(ctx, "导出失败：${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    /** 导出目录选择：默认 Download / 系统文件夹选择器（SAF）。 */
    private fun chooseExportDir() {
        val options = arrayOf("默认 Download 目录", "选择其他目录（系统文件夹选择器）")
        AlertDialog.Builder(activity)
            .setTitle("导出目录")
            .setItems(options) { d, which ->
                when (which) {
                    0 -> {
                        SettingsStore.setExportDirUri(null)
                        onSettingsChanged()
                        Toast.makeText(ctx, "已设为默认 Download 目录", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        val act = activity as? MainActivity
                        if (act != null) {
                            act.openExportDirPicker()
                        } else {
                            Toast.makeText(ctx, "无法打开目录选择器", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun versionLogValue(): String = "查看全部版本修改"

    /** 版本日志：列出所有历史版本的大致修改（长期维护，随版本迭代追加）。 */
    private fun showVersionLogDialog() {
        val sb = StringBuilder()
        sb.append("v1.5.1（当前）：切回“全部应用”即恢复监测全部（取消应用过滤，与勾选语义闭环）；移除文件事件页遗留的“全部应用”提示文字；修复 Android 7.1.1 崩溃隐患（ThreadLocal 改用低版本兼容写法）\n\n")
        sb.append("v1.5.0：勾选弹窗修复——首次打开即可弹出（不再依赖服务启动）；勾选列表新增搜索框（按名称/包名实时过滤）+ 行复用防卡顿；文件事件页隐藏“全部应用”筛选（仅保留时间段）\n\n")
        sb.append("v1.4.2：仅监测所选应用——首页应用下拉可勾选监测范围，未勾选应用的前台/后台数据后台真实过滤（源头丢弃，日志体积显著缩小）；勾选框可直接点击\n\n")
        sb.append("v1.4.1：通知栏可隐藏（设置开关，关=最小化无停止按钮）；通知链路异常隔离（阉割系统不发通知不崩溃）\n\n")
        sb.append("v1.4.0：文件监控加固——符号链接防环、节流表防膨胀、全盘遍历不阻塞首轮采集；导出目录并入导出日志入口\n\n")
        sb.append("v1.3.9：权限模式改为设置页直接开关切换；应用下拉显示全部已安装应用（记录过的在前）；导出改为保存到目录（默认 Download，可自定义）\n\n")
        sb.append("v1.3.8：修复应用识别——应用下拉不再混入空包名空白项；轮询模式补后台估算（后台 Tab 有数据）\n\n")
        sb.append("v1.3.7：权限模式切换交互优化——点击选项立即切换+明确反馈；当前模式/切换入口显示更清晰\n\n")
        sb.append("v1.3.6：刷新按钮改为重启采集服务+全量刷新（防卡死）；清理日志改为真正清空全部+缓存+DB收缩；轮询兜底补持续时长\n\n")
        sb.append("v1.3.5：Root 强制保活——Doze 白名单+后台运行豁免（阉割系统无省电页时的替代通道）；服务启动自动保活\n\n")
        sb.append("v1.3.4：阉割系统适配——无使用情况数据时自动转提权轮询记录；dumpsys 输出精简秒回；提示条目说明数据源状态\n\n")
        sb.append("v1.3.3：低版本（7.1~9）熄屏段闭合兜底+亮灭屏记录；修复 7.1.1 提权崩溃（API26 误用）；提权命令加固（超时/冷却/防反复弹窗）\n\n")
        sb.append("v1.3.2：后台估算独立栏目（全部/后台切换）；新增亮屏/熄屏记录\n\n")
        sb.append("v1.3.1：后台估算实时结算（离开前台即输出，无需跨天）\n\n")
        sb.append("v1.3.0：权限判定修正（Shizuku 需激活授权）；Shell/Root 增强（前台校准/进程存活）；省电切换；版本日志\n\n")
        sb.append("v1.2.0：图标缓存复用；全局异常弹性；设置值即时刷新\n\n")
        sb.append("v1.1.2：文件监控范围扩展（全盘+自定义目录）\n\n")
        sb.append("v1.1.1：纯代码布局修复；长按复制；排序倒序；时间到秒；零秒段过滤\n\n")
        sb.append("v1.1.0：监控总开关；权限引导；后台估算按天聚合\n\n")
        sb.append("v1.0.0：首版（前台时间线+文件事件+双显示+导出）")
        AlertDialog.Builder(activity)
            .setTitle(R.string.setting_version_log)
            .setMessage(sb.toString())
            .setPositiveButton("知道了", null)
            .show()
    }
}
