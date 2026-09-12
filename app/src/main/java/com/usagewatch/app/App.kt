package com.usagewatch.app

import android.app.Application
import android.util.Log
import com.usagewatch.app.util.LogFileWriter
import com.usagewatch.app.util.SettingsStore

/**
 * 应用入口：只做全局轻量初始化 + 全局未捕获异常留痕。
 * 采集服务由 MainActivity 在用户交互后启动，不在 onCreate 中自启，
 * 避免冷启动开销与后台限制。
 */
class App : Application() {

    companion object {
        private const val TAG = "App"
    }

    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        installUncaughtExceptionLog()
    }

    /**
     * 全局未捕获异常：先写入日志文件留痕（用户可导出查看），
     * 再交还系统默认处理器（不吞掉致命异常，避免进程状态不一致）。
     * 普通可捕获异常由各模块 try/catch 隔离并记录日志，进程继续运行。
     */
    private fun installUncaughtExceptionLog() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                LogFileWriter.logException(this, TAG, "Uncaught@${thread.name}", throwable)
                Log.e(TAG, "未捕获异常: ${throwable.javaClass.simpleName} @${thread.name}", throwable)
            } catch (_: Exception) {
                // 崩溃现场写入失败则放弃
            }
            prev?.uncaughtException(thread, throwable)
        }
    }
}
