package com.usagewatch.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.usagewatch.app.MainActivity
import com.usagewatch.app.R
import com.usagewatch.app.collector.CollectorManager
import com.usagewatch.app.util.MonitoringChannels
import com.usagewatch.app.util.Permissions
import com.usagewatch.app.util.SettingsStore

/**
 * 常驻采集前台服务。
 *
 * 逻辑：
 *  - 前台服务 + 低重要性常驻通知，保证采集进程不被系统直接回收；
 *  - 独立 HandlerThread 串行执行：应用事件增量采集 → 文件监控注册 → 定期清理；
 *  - 采样间隔取自设置（默认 30s），事件驱动为主、定时兜底；
 *  - 单次采集异常全部隔离，不因数据问题导致服务崩溃。
 */
class MonitoringService : Service() {

    companion object {
        private const val NOTIF_ID = 1001
        private const val ACTION_START = "com.usagewatch.app.action.START"
        private const val ACTION_STOP = "com.usagewatch.app.action.STOP"
        /** 通知栏可见性变更后重建通知（设置页调用）。 */
        const val ACTION_REBUILD_NOTIF = "com.usagewatch.app.action.REBUILD_NOTIF"
        private const val TAG = "MonitoringService"

        /** 服务运行状态（内存标志；供 UI 显示"服务是否在跑"，避免用户无从判断） */
        @Volatile
        private var running = false

        /** 最近一次成功采集时间戳（供 UI 显示心跳，判断服务是否真的在采集） */
        @Volatile
        private var lastTickAt = 0L

        fun isRunning(): Boolean = running

        fun lastTickAt(): Long = lastTickAt

        fun start(context: android.content.Context) {
            if (running) return // 幂等：已运行不再重复拉起
            running = true
            val intent = Intent(context, MonitoringService::class.java).setAction(ACTION_START)
            try {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // 部分 ROM/系统限制下 startForegroundService 可能抛异常：复位标志并记录，
                // onResume 的下一次同步会重试，不让应用崩溃。
                running = false
                android.util.Log.e(TAG, "服务启动失败", e)
            }
        }

        fun stop(context: android.content.Context) {
            if (!running) return
            running = false
            try {
                context.startService(
                    Intent(context, MonitoringService::class.java).setAction(ACTION_STOP)
                )
            } catch (_: Exception) {
                // 停止失败无碍：进程/服务会在系统回收时自然结束
            }
        }
    }

    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null
    private var collector: CollectorManager? = null
    private var lastCleanupDay = 0
    /** 低版本（API 25-28）屏幕广播接收器：兜底熄屏段闭合与亮灭屏记录 */
    private var screenReceiver: android.content.BroadcastReceiver? = null
    private val loopRunnable = object : Runnable {
        override fun run() {
            tick()
            val interval = SettingsStore.sampleIntervalMs()
            worker?.postDelayed(this, interval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        try {
            Permissions.ensureNotificationChannel(this)
        } catch (e: Exception) {
            // 阉割系统可能无通知渠道能力：不影响服务主体
            android.util.Log.e(TAG, "通知渠道创建失败（已隔离）", e)
        }
        startForegroundCompat()
        collector = CollectorManager(this)
        registerScreenReceiverCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                running = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REBUILD_NOTIF -> {
                // 通知栏可见性设置变更：重建常驻通知（含是否显示停止按钮）
                startForegroundCompat()
                return START_STICKY
            }
        }
        // 总开关关闭时拒绝运行：防止用户关闭后系统因 START_STICKY 重建服务
        if (!SettingsStore.monitoringEnabled()) {
            running = false
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        ensureWorkerStarted()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        unregisterScreenReceiverCompat()
        worker?.removeCallbacksAndMessages(null)
        workerThread?.quitSafely()
        workerThread = null
        worker = null
        collector?.stopFileMonitoring()
        collector?.onServiceStop()
        collector = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== 内部 ====================

    private fun ensureWorkerStarted() {
        if (worker != null) return
        val thread = HandlerThread("monitor-worker").apply { start() }
        workerThread = thread
        worker = Handler(thread.looper)
        worker?.post {
            // 串行初始化：能力检测 → 自动保活（ROOT）→ 启动自驱动采样循环。
            // 文件监控不在初始化里做：全盘遍历可达数千目录（数秒~数十秒），
            // 放在初始化会阻塞首轮采集与心跳；tick() 首轮会幂等启动文件监控。
            val cm = collector ?: return@post
            cm.refreshCapability()
            applyAutoKeepAliveOnce(cm)
            worker?.post(loopRunnable) // 循环自身负责后续调度，避免双循环重复采集
        }
    }

    /** 进程内一次性标记：ROOT 保活结果只写一条日志提示，防刷屏 */
    private var keepAliveNoteShown = false

    /** 服务启动时自动应用 root 保活（阉割系统无省电设置页的替代通道）。 */
    private fun applyAutoKeepAliveOnce(cm: CollectorManager) {
        try {
            if (keepAliveNoteShown) return
            val applied = com.usagewatch.app.core.KeepAliveHelper.applyRootKeepAlive()
            if (!applied) return // 非 ROOT 或无权限：静默跳过
            keepAliveNoteShown = true
            cm.repository().insertUsage(
                listOf(
                    com.usagewatch.app.data.model.LogEntry(
                        timestamp = System.currentTimeMillis(),
                        type = com.usagewatch.app.data.model.LogEntry.TYPE_SYS_NOTE,
                        packageName = "",
                        appLabel = null,
                        detail = "已自动应用 Root 保活（Doze 白名单+后台豁免）",
                        startTime = 0,
                        endTime = 0,
                        durationMs = 0
                    )
                )
            )
        } catch (_: Exception) {
            // 保活失败不影响服务启动
        }
    }

    /** 单轮任务：采集 + 文件监控范围同步 + 每日清理 + 权限模式刷新。 */
    private fun tick() {
        try {
            val cm = collector ?: return
            cm.collectUsageOnce()
            cm.ensureFileMonitoring() // 范围/目录变更后自动重启文件监控（幂等，无变更则空操作）
            lastTickAt = System.currentTimeMillis() // 心跳：证明服务确实在采集
            cm.refreshCapability()
            val today = System.currentTimeMillis() / 86_400_000L
            if (today != lastCleanupDay.toLong()) {
                lastCleanupDay = today.toInt()
                cm.cleanupIfNeeded()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "tick 失败", e)
            com.usagewatch.app.util.LogFileWriter.logException(this, TAG, "tick", e)
        }
    }

    /**
     * 前台通知（版本兼容：API 29+ 需指定前台服务类型 dataSync）。
     * 异常隔离：阉割系统若 startForeground 失败（通知能力缺失等），记录后停止服务，
     * 避免 Android 8+ 未进前台导致 5 秒 ANR 弹窗循环；不向上抛，服务不崩溃。
     */
    private fun startForegroundCompat() {
        val notification = try {
            buildNotification()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "通知构建失败（已隔离）", e)
            com.usagewatch.app.util.LogFileWriter.logException(this, TAG, "buildNotification", e)
            null
        } ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "startForeground 失败（已隔离）", e)
            com.usagewatch.app.util.LogFileWriter.logException(this, TAG, "startForeground", e)
            // 前台标记未生效：继续驻留会触发 ANR，安全做法是停止本次运行，下次启动再试
            running = false
            stopSelf()
        }
    }

    /**
     * 注册屏幕广播（仅 API 25-28）：
     * 这代系统无 SCREEN 事件源且熄屏不保证发 MOVE_TO_BACKGROUND，
     * 用 ACTION_SCREEN_OFF/ON 兜底——熄屏即闭合所有前台段（防时长虚高），并补齐亮灭屏记录。
     * API 29+ 由 UsageStats SCREEN 事件覆盖，不注册，避免与事件流重复。
     */
    @Suppress("UnspecifiedRegisterReceiverFlag")
    private fun registerScreenReceiverCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        try {
            val filter = android.content.IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
                addAction(Intent.ACTION_SCREEN_ON)
            }
            screenReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: Intent) {
                    try {
                        when (i.action) {
                            Intent.ACTION_SCREEN_OFF ->
                                worker?.post { collector?.handleScreenOff() }
                            Intent.ACTION_SCREEN_ON ->
                                worker?.post { collector?.handleScreenOn() }
                        }
                    } catch (_: Exception) {
                        // 广播处理失败不影响服务
                    }
                }
            }
            registerReceiver(screenReceiver, filter)
        } catch (e: Exception) {
            screenReceiver = null
            android.util.Log.e(TAG, "屏幕广播注册失败（不影响采集）", e)
        }
    }

    private fun unregisterScreenReceiverCompat() {
        val r = screenReceiver ?: return
        screenReceiver = null
        try {
            unregisterReceiver(r)
        } catch (_: Exception) {
            // 未注册/已注销场景忽略
        }
    }

    /** 移除前台通知（服务即将停止时调用，避免残留）。 */
    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
            // 服务可能尚未进入前台状态，忽略
        }
    }

    /** 常驻通知：按"通知栏可见"设置构建——开=显示停止按钮；关=最小化（无按钮，防误触）。 */
    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val visible = SettingsStore.notificationVisible()
        val builder = NotificationCompat.Builder(this, MonitoringChannels.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(getString(R.string.notify_title))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(false)
        if (visible) {
            builder.setContentText(getString(R.string.notify_text))
            val stopIntent = PendingIntent.getService(
                this, 1,
                Intent(this, MonitoringService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.notify_stop), stopIntent)
        } else {
            // 最小化：无按钮、最低优先级、自动折叠，尽量不打扰（文案同正常状态，不标"已最小化"）
            builder.setContentText(getString(R.string.notify_text))
                .setPriority(NotificationCompat.PRIORITY_MIN)
        }
        return builder.build()
    }
}
