package com.usagewatch.app.collector

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import com.usagewatch.app.util.Permissions
import com.usagewatch.app.util.SettingsStore

/**
 * 应用使用事件拉取（UsageStatsManager）。
 *
 * 精度口径：
 *  - 前台开始/结束：由系统事件流重建，秒级精确；
 *  - 边界处理：系统不会为"查询区间开始前已在前台/结束后仍在运行"合成事件，
 *    因此查询窗口向前扩展 BUFFER_MS，并在每次处理后推进持久化游标，减小边界丢失；
 *  - 事件类型数值兼容：Android 10(API 29) 引入的新事件常量（ACTIVITY_*）与旧常量
 *    （MOVE_TO_*）存在"数值相同、语义相反"的复用，必须按 SDK_INT 分支解释，不能只看数值。
 */
class UsageCollector(private val context: Context) {

    data class RawEvent(val packageName: String, val timeStamp: Long, val eventType: Int)

    /** 向前扩展量：覆盖"窗口开始前已在前台"的段头 */
    private val bufferMs: Long = 15 * 60 * 1000L

    /**
     * 首次运行回填窗口：不查询安装前的全量历史事件（queryEvents 返回整段内存列表，
     * 设备使用数月后可能有数十万条，直接查询存在内存溢出风险）。
     * 只回填最近一段，足够兜住进行中的段头，记录从启用时刻开始。
     */
    private val initialBackfillMs: Long = 2 * 60 * 60 * 1000L

    private val usm: UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    /** 是否有数据可用（未开启使用情况访问则返回空）。 */
    fun isAvailable(): Boolean = Permissions.hasUsageAccess(context)

    /**
     * 增量拉取事件，返回 [from, to) 区间内的原始事件（已按时间排序）。
     * 同时推进 SettingsStore 中的游标。
     *
     * 去重口径：查询窗口向前扩展 bufferMs 是为了兜住"进行中前台段的段头"，
     * 因此 [lastEventTime, from) 区间的事件会重复出现，必须按时间戳跳过已处理事件，
     * 否则 TimelineProcessor 会重复生成前台段/后台估算条目。
     */
    fun fetchEvents(): List<RawEvent> {
        if (!isAvailable()) return emptyList()

        val to = System.currentTimeMillis()
        var lastTs = SettingsStore.lastEventTime()
        if (lastTs == 0L) {
            // 首次：初始化游标为"当前时刻回填窗口"，避免全量历史查询
            lastTs = (to - initialBackfillMs).coerceAtLeast(0L)
            SettingsStore.setLastEventTime(lastTs)
        }
        // 游标向回扩展，兜住进行中的前台段头
        val from = (lastTs - bufferMs).coerceAtLeast(0L)

        val events = usm?.queryEvents(from, to) ?: return emptyList()

        val result = ArrayList<RawEvent>()
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            // 屏幕事件（亮屏/熄屏/锁屏）包名为空，但必须保留（用于输出亮灭屏日志与闭合前台段）
            val screenEvt = isScreenEventType(ev.eventType)
            if (ev.packageName.isNullOrEmpty() && !screenEvt) continue
            // 监测范围过滤：仅监测模式且该包未勾选 → 直接丢弃，不进入时间线处理
            // （源头过滤：未监测应用的事件不占内存、不参与状态重建，日志体积显著缩小）
            if (!screenEvt && !SettingsStore.shouldMonitor(ev.packageName ?: "")) continue
            // 跳过已处理过的事件；同一毫秒内的不同事件（如 RESUME+PAUSE 对）必须都保留，
            // 否则段尾事件被吞会造成前台段挂起、起点漂移（表现为"前台开始持续零秒"或时长虚高）。
            if (ev.timeStamp < lastTs) continue
            result.add(RawEvent(ev.packageName ?: "", ev.timeStamp, ev.eventType))
            if (ev.timeStamp > lastTs) lastTs = ev.timeStamp
        }
        // 推进游标（以最后一个事件时间而非当前时间，避免乱序事件被跳过）
        SettingsStore.setLastEventTime(lastTs)
        result.sortBy { it.timeStamp }
        return result
    }

    /** 前台开始判定。 */
    @Suppress("DEPRECATION")
    fun isForegroundStart(type: Int): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            type == UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            type == UsageEvents.Event.MOVE_TO_FOREGROUND
        }
    }

    /** 前台结束判定。 */
    @Suppress("DEPRECATION")
    fun isForegroundEnd(type: Int): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            type == UsageEvents.Event.ACTIVITY_PAUSED || type == UsageEvents.Event.ACTIVITY_STOPPED
        } else {
            type == UsageEvents.Event.MOVE_TO_BACKGROUND
        }
    }

    /** 屏幕/锁屏状态事件（任一发生都视为所有前台段结束）。 */
    @Suppress("DEPRECATION")
    fun isScreenAway(type: Int): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            type == UsageEvents.Event.SCREEN_NON_INTERACTIVE ||
                type == UsageEvents.Event.KEYGUARD_SHOWN ||
                type == UsageEvents.Event.DEVICE_SHUTDOWN
        } else {
            // API 25-28 无 SCREEN 事件常量，锁屏时系统会发 MOVE_TO_BACKGROUND 兜底
            false
        }
    }

    /** 亮屏事件判定（API 29+ 才有 SCREEN 事件常量）。 */
    @Suppress("DEPRECATION")
    fun isScreenOn(type: Int): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            type == UsageEvents.Event.SCREEN_INTERACTIVE
    }

    /** 熄屏/锁屏事件判定（用于输出熄屏日志；DEVICE_SHUTDOWN 只参与闭合不输出）。 */
    @Suppress("DEPRECATION")
    fun isScreenOff(type: Int): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            (type == UsageEvents.Event.SCREEN_NON_INTERACTIVE ||
                type == UsageEvents.Event.KEYGUARD_SHOWN)
    }

    /** 屏幕事件类型（包名为空但需保留的事件）。 */
    @Suppress("DEPRECATION")
    fun isScreenEventType(type: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return type == UsageEvents.Event.SCREEN_INTERACTIVE ||
            type == UsageEvents.Event.SCREEN_NON_INTERACTIVE ||
            type == UsageEvents.Event.KEYGUARD_SHOWN ||
            type == UsageEvents.Event.DEVICE_SHUTDOWN
    }
}
