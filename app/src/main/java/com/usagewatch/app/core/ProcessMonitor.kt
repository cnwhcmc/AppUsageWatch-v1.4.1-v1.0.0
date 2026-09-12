package com.usagewatch.app.core

import android.util.Log

/**
 * 进程存活监控（SHELL/ROOT 增强）：
 * 定期执行 `ps -A -o NAME` 获取全部运行中进程的包名集合，
 * 用于后台估算条目的"进程存活确认"标注（估算 → 进程存活确认，可信度更高）。
 *
 * 仅 ROOT（su）/SHELL（Shizuku）可看到其他应用真实进程名；普通模式为空集。
 * 解析失败/无通道时保持旧集合，不影响任何功能。
 */
object ProcessMonitor {

    private const val TAG = "ProcessMonitor"

    @Volatile
    private var alive: Set<String> = emptySet()

    fun update(pkgs: Set<String>) {
        alive = pkgs
    }

    /** 某包名当前是否有存活进程（尽力口径：进程名即包名或包名前缀）。 */
    fun isAlive(pkg: String): Boolean = pkg in alive

    /** 后台线程调用：采集一次存活进程集合（失败静默降级）。 */
    fun refresh() {
        if (!CapabilityManager.canQueryProcesses()) {
            return // 普通模式/强制省电：不执行 ps，保持空集
        }
        val out = ShellExecutor.execute("ps -A -o NAME") ?: return
        val pkgs = try {
            out.lineSequence()
                .map { it.trim() }
                .filter { it.contains('.') }       // 进程名含点才可能是应用包名（排除内核/系统线程）
                .map { it.substringBefore(':') }   // 多进程后缀（:push/:remote）归一到主包名
                .toSet()
        } catch (e: Exception) {
            Log.e(TAG, "进程列表解析失败", e)
            return
        }
        update(pkgs)
    }
}
