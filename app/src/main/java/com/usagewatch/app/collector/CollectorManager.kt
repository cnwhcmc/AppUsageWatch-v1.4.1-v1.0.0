package com.usagewatch.app.collector

import android.content.Context
import android.util.Log
import com.usagewatch.app.core.CapabilityManager
import com.usagewatch.app.core.ForegroundProbe
import com.usagewatch.app.core.PollingForegroundTracker
import com.usagewatch.app.core.ProcessMonitor
import com.usagewatch.app.data.LogRepository
import com.usagewatch.app.data.model.LogEntry
import com.usagewatch.app.util.AppInfoResolver
import com.usagewatch.app.util.LogFileWriter
import com.usagewatch.app.util.SettingsStore

/**
 * 采集调度管理：统一持有采集器与仓储，向 MonitoringService 暴露最小接口。
 *
 * 模式自适应：
 *  - NORMAL：UsageStats 前台时间线 + 公共目录文件事件（保底，功能完整）；
 *  - SHELL / ROOT：提权增强（实时前台校准 dumpsys、进程存活确认 ps），
 *    全部尽力而为、失败静默降级；强制普通模式（省电）时不启用。
 */
class CollectorManager(private val context: Context) {

    companion object {
        private const val TAG = "CollectorManager"
    }

    private val repo = LogRepository(context)
    private val usageCollector = UsageCollector(context)
    private val timeline = TimelineProcessor(usageCollector) { pkg ->
        AppInfoResolver.resolve(context, pkg).label
    }
    private val pollingTracker = PollingForegroundTracker()
    private val fileCollector = FileEventCollector(context) { entries ->
        persistFileEntries(entries)
    }

    /** 文件监控范围的签名（全盘标志 + 目录集合），用于检测设置变化后自动重启监听 */
    private var fileScopeSig = ""

    // ---- 数据源状态与节流 ----
    /** 是否已输出过"使用情况无数据"提示（一次性，防刷屏） */
    private var usageSourceNoteShown = false
    /** 上次提权探测（dumpsys/ps）时间：正常模式 2 分钟一次（省电），无 UsageStats 时每轮一次（数据源） */
    private var lastProbeAt = 0L
    private val probeNormalIntervalMs = 120_000L
    private val probeFallbackIntervalMs = 30_000L

    private fun fileScopeSignature(): String =
        "${SettingsStore.monitorWholeStorage()}|${SettingsStore.monitorDirs().sorted().joinToString(",")}"

    private fun persistFileEntries(entries: List<LogEntry>) {
        try {
            repo.insertFiles(entries)
        } catch (e: Exception) {
            Log.e(TAG, "文件事件入库失败", e)
            LogFileWriter.logException(context, TAG, "insertFiles", e)
        }
    }

    /**
     * 确保文件监控与当前设置一致：首次启动或范围/目录变更时重启监听。
     * 由服务在后台线程调用，设置页改完"监控范围/目录"后自动生效，无需重启应用。
     */
    fun ensureFileMonitoring() {
        try {
            val sig = fileScopeSignature()
            if (sig == fileScopeSig) return
            fileCollector.start()
            fileScopeSig = sig
        } catch (e: Exception) {
            Log.e(TAG, "文件监控启动失败", e)
            LogFileWriter.logException(context, TAG, "fileMonitoring", e)
        }
    }

    fun stopFileMonitoring() {
        try {
            fileCollector.stop()
        } catch (e: Exception) {
            Log.e(TAG, "文件监控停止失败", e)
            LogFileWriter.logException(context, TAG, "fileMonitoringStop", e)
        }
    }

    /**
     * 屏幕熄灭广播兜底（API 25-28 无 SCREEN 事件源时由服务广播触发）：
     * 闭合所有进行中的前台段（熄屏时刻闭合，修复"前台段挂起导致时长虚高"）+ 写熄屏记录。
     * 服务 worker 线程串行调用。
     */
    fun handleScreenOff() {
        try {
            val entries = mutableListOf<LogEntry>()
            timeline.onScreenOff(System.currentTimeMillis(), entries)
            if (entries.isNotEmpty()) {
                repo.insertUsage(entries)
            }
        } catch (e: Exception) {
            Log.e(TAG, "熄屏兜底失败", e)
            LogFileWriter.logException(context, TAG, "screenOff", e)
        }
    }

    /** 亮屏广播（API 25-28 兜底）：写亮屏记录（高版本走 UsageStats SCREEN 事件，不重复）。 */
    fun handleScreenOn() {
        try {
            val now = System.currentTimeMillis()
            repo.insertUsage(
                listOf(
                    LogEntry(
                        timestamp = now,
                        type = LogEntry.TYPE_SCREEN_ON,
                        packageName = "",
                        appLabel = null,
                        detail = "",
                        startTime = 0,
                        endTime = 0,
                        durationMs = 0
                    )
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "亮屏兜底失败", e)
            LogFileWriter.logException(context, TAG, "screenOn", e)
        }
    }

    /**
     * 执行一次应用事件增量采集。
     * 异常隔离：任何失败只记录日志，不抛出，保证前台服务不因单次采集崩溃。
     *
     * 数据源策略：
     *  - 正常设备：UsageStats 事件流为主，提权（dumpsys/ps）每 2 分钟校准一次（省电）；
     *  - 阉割/特殊 ROM（无"使用情况访问"，UsageStats 恒空）：若有 ROOT/SHELL，
     *    转用 dumpsys 轮询作为独立前台数据源（每轮一次）；并输出一次性提示条目；
     *  - 普通模式且 UsageStats 为空：输出一次性提示，说明原因（无数据源）。
     * @return 本次新增条目数
     */
    fun collectUsageOnce(): Int {
        return try {
            val entries = mutableListOf<LogEntry>()
            val now = System.currentTimeMillis()
            val events = usageCollector.fetchEvents()
            val usageEmpty = events.isEmpty()

            // 提权探测节流：无 UsageStats 时每轮探测（兜底数据源）；正常时 2 分钟一次
            val probeInterval =
                if (usageEmpty) probeFallbackIntervalMs else probeNormalIntervalMs
            var fg: String? = null
            if (CapabilityManager.canQueryProcesses() && now - lastProbeAt >= probeInterval) {
                lastProbeAt = now
                fg = ForegroundProbe.currentForegroundPackage()
                if (fg != null) {
                    ProcessMonitor.refresh()
                }
            }

            if (!usageEmpty) {
                // 正常数据源：事件流为主，提权结果仅校准
                entries.addAll(timeline.process(events))
                fg?.let { timeline.calibrateForeground(it, now, entries) }
            } else {
                // UsageStats 无数据：一次性提示 + 提权轮询兜底
                if (!usageSourceNoteShown) {
                    usageSourceNoteShown = true
                    entries.add(
                        LogEntry(
                            timestamp = now,
                            type = LogEntry.TYPE_SYS_NOTE,
                            packageName = "",
                            appLabel = null,
                            detail = if (CapabilityManager.canQueryProcesses()) {
                                "系统无使用情况数据，已启用提权轮询记录（ROOT/Shell）"
                            } else {
                                "系统无使用情况数据（未授权或系统阉割），请检查权限或授权后重试"
                            },
                            startTime = 0,
                            endTime = 0,
                            durationMs = 0
                        )
                    )
                }
                if (CapabilityManager.canQueryProcesses()) {
                    pollingTracker.onPoll(fg, now, { pkg ->
                        AppInfoResolver.resolve(context, pkg).label
                    }, entries)
                    // 轮询模式后台估算：基于当日活跃窗口减前台，当天即可见后台数据
                    pollingTracker.settleBackground(now, entries)
                }
            }
            // 后台估算实时结算：正常数据源走 UsageStats 窗口；轮询模式已在上方结算
            if (!usageEmpty) {
                timeline.settleCurrentDay(now, entries)
            }
            if (entries.isNotEmpty()) {
                repo.insertUsage(entries)
            }
            entries.size
        } catch (e: Exception) {
            Log.e(TAG, "应用事件采集失败", e)
            LogFileWriter.logException(context, TAG, "collectUsage", e)
            0
        }
    }

    /** 每日/定期清理过期日志。 */
    fun cleanupIfNeeded() {
        try {
            repo.cleanup(System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "日志清理失败", e)
            LogFileWriter.logException(context, TAG, "cleanup", e)
        }
    }

    /** 刷新权限模式（后台线程调用）。 */
    fun refreshCapability() {
        CapabilityManager.refresh()
    }

    /** 服务停止：清空轮询跟踪状态，避免跨会话残留错位段。 */
    fun onServiceStop() {
        try {
            pollingTracker.reset()
        } catch (_: Exception) {
            // 清空失败不影响服务停止
        }
    }

    fun repository(): LogRepository = repo
}
