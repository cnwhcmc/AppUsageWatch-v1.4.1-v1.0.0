package com.usagewatch.app.core

import com.usagewatch.app.collector.TimelineProcessor
import com.usagewatch.app.data.model.LogEntry

/**
 * 前台轮询跟踪器（SHELL/ROOT 增强、UsageStats 无数据时的独立兜底数据源）：
 *
 * 阉割/特殊 ROM 可能没有"使用情况访问"能力（设置页缺失或系统无事件源），
 * UsageStats 查询永远返回空 → 前台时间线无数据。此时若用户有 ROOT/SHELL，
 * 用 dumpsys 轮询当前前台包：包变化时输出「上一包前台结束 + 新包前台开始」。
 *
 * 稳定性：
 *  - 探测失败（null）保持现状，下次成功时再闭合，不产生残缺段；
 *  - 与 UsageStats 事件流互斥：仅当事件流为空时启用（CollectorManager 控制），避免重复；
 *  - 内部无异常可抛（全部空安全），不参与崩溃面。
 */
class PollingForegroundTracker {

    private var lastPkg: String? = null
    private var lastStart = 0L

    /** 已产生过至少一段（用于 UI 状态判断，暂未使用，保留字段便于扩展） */
    var hasOutput: Boolean = false
        private set

    // ---- 当日聚合（后台估算数据源，跨天重置） ----
    private var aggDay = dayOf(System.currentTimeMillis())
    /** 包 → 当日前台累计毫秒（已闭合段之和） */
    private val fgTotal = HashMap<String, Long>()
    /** 当日活跃窗口：该包最早前台探测 ~ 最后前台探测 */
    private var windowStart = Long.MAX_VALUE
    private var windowEnd = 0L
    /** 包 → 上次已输出的后台量（毫秒）：增量口径，防重复输出 */
    private val emittedBg = HashMap<String, Long>()
    /** 当日最小增量输出阈值（与主数据源口径一致：1 分钟） */
    private val bgIncrementMs = 60_000L

    private fun dayOf(ts: Long): Int = (ts / 86_400_000L).toInt()

    /**
     * 处理一次轮询结果。
     * @param pkg 当前前台包（已过滤系统界面；null 表示本次探测失败）
     * @param now 探测时刻
     * @param appLabel 包名 → 显示名解析（复用 AppInfoResolver）
     */
    fun onPoll(
        pkg: String?,
        now: Long,
        appLabel: (String) -> String?,
        out: MutableList<LogEntry>
    ) {
        if (pkg.isNullOrBlank()) return // 探测失败：保持现状，下次闭合
        ensureDay(now)
        if (pkg == lastPkg) return      // 前台未变化：不重复输出
        // 闭合上一段（成对输出，保证线性日志完整）
        val prev = lastPkg
        if (prev != null && lastStart > 0) {
            val duration = (now - lastStart).coerceAtLeast(0L)
            out.add(
                LogEntry(
                    timestamp = now,
                    type = LogEntry.TYPE_FG_END,
                    packageName = prev,
                    appLabel = appLabel(prev),
                    detail = "持续 ${TimelineProcessor.formatDuration(duration)}（轮询）",
                    startTime = lastStart,
                    endTime = now,
                    durationMs = duration
                )
            )
            // 计入当日聚合（后台估算用）
            fgTotal[prev] = (fgTotal[prev] ?: 0L) + duration
            windowStart = minOf(windowStart, lastStart)
            windowEnd = maxOf(windowEnd, now)
            hasOutput = true
        }
        lastPkg = pkg
        lastStart = now
        out.add(
            LogEntry(
                timestamp = now,
                type = LogEntry.TYPE_FG_START,
                packageName = pkg,
                appLabel = appLabel(pkg),
                detail = "前台开始（轮询）",
                startTime = now,
                endTime = 0,
                durationMs = 0
            )
        )
        hasOutput = true
    }

    /**
     * 轮询模式后台估算实时结算（每轮采样后调用）：
     * 对"当日有前台记录、当前不在前台"的包，后台 = 当日活跃窗口 − 前台累计；
     * 与上次输出相比增量 ≥ 1 分钟才输出（当天即可见，不刷屏）。
     * 与主数据源口径一致，保证后台 Tab 在无 UsageStats 的阉割系统上也有数据。
     */
    fun settleBackground(now: Long, out: MutableList<LogEntry>) {
        ensureDay(now)
        val cur = lastPkg
        val windowLen = windowEnd - windowStart
        if (windowLen < bgIncrementMs) return // 窗口太短无意义
        fgTotal.forEach { (pkg, fgMs) ->
            if (pkg == cur) return@forEach // 正在前台，不计后台
            val bg = (windowLen - fgMs).coerceAtLeast(0L)
            val prev = emittedBg[pkg] ?: 0L
            if (bg - prev >= bgIncrementMs) {
                emittedBg[pkg] = bg
                out.add(
                    LogEntry(
                        timestamp = now,
                        type = LogEntry.TYPE_BG_RUN,
                        packageName = pkg,
                        appLabel = null,
                        detail = "估算 ${TimelineProcessor.formatDuration(bg)}（活跃窗口 ${TimelineProcessor.formatDuration(windowLen)} 减前台，轮询）",
                        startTime = 0,
                        endTime = 0,
                        durationMs = bg
                    )
                )
                hasOutput = true
            }
        }
    }

    /** 跨天或服务重启时重置聚合与段状态。 */
    private fun ensureDay(now: Long) {
        val d = dayOf(now)
        if (d != aggDay) {
            aggDay = d
            fgTotal.clear()
            windowStart = Long.MAX_VALUE
            windowEnd = 0L
            emittedBg.clear()
        }
    }

    /** 服务停止/模式切换时清空状态，避免跨会话残留错位段。 */
    fun reset() {
        lastPkg = null
        lastStart = 0L
        hasOutput = false
        aggDay = dayOf(System.currentTimeMillis())
        fgTotal.clear()
        windowStart = Long.MAX_VALUE
        windowEnd = 0L
        emittedBg.clear()
    }
}
