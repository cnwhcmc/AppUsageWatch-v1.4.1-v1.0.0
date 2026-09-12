package com.usagewatch.app.collector

import com.usagewatch.app.data.model.LogEntry
import com.usagewatch.app.util.SettingsStore

/**
 * 时间线处理器：把原始事件流重建为"前台开始/前台结束/后台估算"日志条目。
 *
 * 口径：
 *  - 前台段：RESUME→PAUSE/STOP 之间的精确区间（秒级），实时输出；
 *  - 后台估算：按"天"聚合（当日活跃窗口 − 前台段总和），**实时增量结算**——
 *    应用离开前台（无进行中前台段）后，当轮采样即结算该应用的后台估算并写入日志，
 *    不再等跨天（旧版只在跨天输出，导致当天日志看不到后台信息）；
 *    窗口继续增长且后台增量 ≥ 1 分钟时输出下一条，避免条目膨胀；
 *  - 锁屏/灭屏（screenAway）强制结束全部进行中的前台段。
 */
class TimelineProcessor(
    private val collector: UsageCollector,
    private val appLabelResolver: (String) -> String?
) {

    /** 每个包名当前进行中的前台段起点 */
    private val activeSessions = HashMap<String, Long>()

    /** 当日后台估算聚合（包名 → 当日累计），跨天结算后清空 */
    private val dayAccs = HashMap<String, DayAcc>()

    /** 当前正在统计的"日"（timestamp/DAY_MS），0 表示尚未开始 */
    private var activeDayKey = 0L

    private data class DayAcc(
        var windowStart: Long = Long.MAX_VALUE,
        var windowEnd: Long = 0L,
        var fgTotal: Long = 0L,
        /** 上次输出后台估算条目时的窗口终点（增量结算防重复） */
        var lastEmittedEnd: Long = 0L,
        /** 上次输出后台估算条目时已结算的前台时长（增量口径） */
        var fgSettled: Long = 0L
    )

    /**
     * 处理一批增量事件，输出待入库的日志条目。
     * 调用方需保证同一 collector 实例串行调用（增量状态在内存中维护）。
     */
    fun process(events: List<UsageCollector.RawEvent>): List<LogEntry> {
        if (events.isEmpty()) return emptyList()

        val out = ArrayList<LogEntry>()
        for (e in events) {
            val dayKey = e.timeStamp / DAY_MS
            if (activeDayKey == 0L) activeDayKey = dayKey
            if (dayKey != activeDayKey) {
                // 跨天：清空聚合（后台估算已由实时增量输出，旧版整段结算已移除）
                flushDay()
                activeDayKey = dayKey
            }

            when {
                collector.isForegroundStart(e.eventType) -> {
                    // 总是以最新 RESUME 时间为段起点：
                    // 若上一段因事件丢失未闭合，新 RESUME 应开启新段而非沿用旧起点，
                    // 否则会把中间非前台时间错误计入前台时长。
                    activeSessions[e.packageName] = e.timeStamp
                    dayAccs.getOrPut(e.packageName) { DayAcc() }.let { acc ->
                        acc.windowStart = minOf(acc.windowStart, e.timeStamp)
                    }
                }
                collector.isForegroundEnd(e.eventType) -> {
                    closeSession(e.packageName, e.timeStamp, out)
                }
                collector.isScreenOn(e.eventType) -> {
                    // 亮屏：独立日志条目（仅 API 29+ 有该事件源）
                    out.add(
                        LogEntry(
                            timestamp = e.timeStamp,
                            type = LogEntry.TYPE_SCREEN_ON,
                            packageName = "",
                            appLabel = null,
                            detail = "",
                            startTime = 0,
                            endTime = 0,
                            durationMs = 0
                        )
                    )
                }
                collector.isScreenAway(e.eventType) -> {
                    // 锁屏/灭屏：结束所有进行中的前台段
                    activeSessions.keys.toList().forEach { pkg ->
                        closeSession(pkg, e.timeStamp, out)
                    }
                    // 熄屏/锁屏：独立日志条目（DEVICE_SHUTDOWN 只闭合不输出）
                    if (collector.isScreenOff(e.eventType)) {
                        out.add(
                            LogEntry(
                                timestamp = e.timeStamp,
                                type = LogEntry.TYPE_SCREEN_OFF,
                                packageName = "",
                                appLabel = null,
                                detail = "",
                                startTime = 0,
                                endTime = 0,
                                durationMs = 0
                            )
                        )
                    }
                }
            }
        }

        out.sortBy { it.timestamp }
        return out
    }

    /**
     * SHELL/ROOT 实时前台校准（提权增强）：
     * 以 dumpsys 解析出的真实前台包名为准，修正 UsageStats 事件延迟/丢失导致的段错位。
     * 保守策略：
     *  - 时间线当前无活跃段 → 不凭空开段（避免制造错误记录）；
     *  - 当前活跃段与实测前台一致 → 无操作；
     *  - 不一致 → 结束其他包的段，并确保实测前台包有进行中的段。
     */
    fun calibrateForeground(pkg: String, ts: Long, out: MutableList<LogEntry>) {
        try {
            if (pkg.isEmpty()) return
            if (activeSessions.isEmpty()) return
            val others = activeSessions.keys.filter { it != pkg }
            others.forEach { closeSession(it, ts, out) }
            if (!activeSessions.containsKey(pkg)) {
                activeSessions[pkg] = ts
                dayAccs.getOrPut(pkg) { DayAcc() }.let { acc ->
                    acc.windowStart = minOf(acc.windowStart, ts)
                }
            }
        } catch (_: Exception) {
            // 校准失败不影响主流程
        }
    }

    /**
     * 屏幕熄灭兜底（API 25-28 广播路径，高版本走 UsageStats SCREEN 事件）：
     * 闭合所有进行中的前台段（段在熄屏时刻闭合，避免虚高），并输出熄屏条目。
     * 与系统 MOVE_TO_BACKGROUND 双路触发时天然幂等（closeSession 已 remove 后返回）。
     */
    fun onScreenOff(ts: Long, out: MutableList<LogEntry>) {
        try {
            activeSessions.keys.toList().forEach { pkg ->
                closeSession(pkg, ts, out)
            }
            out.add(
                LogEntry(
                    timestamp = ts,
                    type = LogEntry.TYPE_SCREEN_OFF,
                    packageName = "",
                    appLabel = null,
                    detail = "",
                    startTime = 0,
                    endTime = 0,
                    durationMs = 0
                )
            )
        } catch (_: Exception) {
            // 广播兜底失败不影响主流程
        }
    }

    private fun closeSession(
        pkg: String,
        end: Long,
        out: MutableList<LogEntry>
    ) {
        val start = activeSessions.remove(pkg) ?: return
        val duration = (end - start).coerceAtLeast(0L)

        // 过滤不足 1 秒的瞬时段：应用内部 Activity 快速切换/弹窗覆盖会产生
        // "前台开始 → 持续 0 秒"的噪音条目，对使用统计无意义，直接丢弃（也不计入聚合）。
        if (duration < 1_000L) return

        // 前台开始（取段起点，供线性日志展示"几点开始运行"）
        out.add(
            LogEntry(
                timestamp = start,
                type = LogEntry.TYPE_FG_START,
                packageName = pkg,
                appLabel = appLabelResolver(pkg),
                detail = "前台开始",
                startTime = start,
                endTime = 0,
                durationMs = 0
            )
        )
        // 前台结束（含持续时长）
        out.add(
            LogEntry(
                timestamp = end,
                type = LogEntry.TYPE_FG_END,
                packageName = pkg,
                appLabel = appLabelResolver(pkg),
                detail = "持续 ${formatDuration(duration)}",
                startTime = start,
                endTime = end,
                durationMs = duration
            )
        )
        // 计入当日聚合（用于跨天结算后台估算）
        val acc = dayAccs.getOrPut(pkg) { DayAcc() }
        acc.fgTotal += duration
        acc.windowStart = minOf(acc.windowStart, start)
        acc.windowEnd = maxOf(acc.windowEnd, end)
    }

    /**
     * 实时结算当日后台估算（每轮采样后调用）。
     *
     * 增量口径：对"当前不在前台"（无进行中前台段）的包，把窗口推进到
     * 最后已知事件时间，后台 = 窗口 − 前台；与上次输出相比的后台增量 ≥ 1 分钟才输出，
     * 保证当天就能在日志看到后台信息，同时不会每轮刷屏。
     * 窗口继续增长（该应用当天又用过）后，后台增量再次达到阈值会再输出一条。
     */
    fun settleCurrentDay(now: Long, out: MutableList<LogEntry>) {
        if (dayAccs.isEmpty()) return
        if (!SettingsStore.backgroundEstimateEnabled()) {
            dayAccs.clear()
            return
        }
        dayAccs.forEach { (pkg, acc) ->
            try {
                // 正在前台的应用不结算（它还在占用前台，谈不上后台）
                if (activeSessions.containsKey(pkg)) return@forEach
                if (acc.windowStart == Long.MAX_VALUE) return@forEach
                val end = acc.windowEnd.coerceAtMost(now)
                if (end <= acc.lastEmittedEnd) return@forEach
                if (end - acc.windowStart < 60_000L) return@forEach // 窗口太短无意义

                val totalBg = ((end - acc.windowStart) - acc.fgTotal).coerceAtLeast(0L)
                if (totalBg < 60_000L) return@forEach
                val settledBg = ((acc.lastEmittedEnd - acc.windowStart) - acc.fgSettled)
                    .coerceAtLeast(0L)
                val inc = (totalBg - settledBg).coerceAtLeast(0L)
                if (inc < 60_000L) return@forEach // 增量未达阈值，等窗口继续增长

                out.add(
                    LogEntry(
                        timestamp = end,
                        type = LogEntry.TYPE_BG_RUN,
                        packageName = pkg,
                        appLabel = appLabelResolver(pkg),
                        detail = "估算 ${formatDuration(totalBg)}（当日活跃窗口减前台）",
                        startTime = acc.windowStart,
                        endTime = end,
                        durationMs = totalBg
                    )
                )
                acc.lastEmittedEnd = end
                acc.fgSettled = acc.fgTotal
            } catch (_: Exception) {
                // 单包结算异常不影响其余包
            }
        }
    }

    /**
     * 跨天：重置聚合。后台估算已由 [settleCurrentDay] 实时增量输出，
     * 跨天只需清空（旧版在此整段输出，与实时增量重复，已移除）。
     */
    private fun flushDay() {
        dayAccs.clear()
    }

    companion object {
        private const val DAY_MS = 86_400_000L

        fun formatDuration(ms: Long): String {
            val totalSec = ms / 1000L
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return when {
                h > 0 -> "${h}小时${m}分${s}秒"
                m > 0 -> "${m}分${s}秒"
                else -> "${s}秒"
            }
        }
    }
}
