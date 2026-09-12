package com.usagewatch.app.core

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference

/**
 * 提权命令执行通道（仅 SHELL/ROOT 模式使用，普通模式不调用）。
 *
 * 通道选择：
 *  - ROOT ：`su -c <cmd>`（需用户已在 Magisk/KernelSU 授权本应用；只读探测不触发弹窗）；
 *  - SHELL：Shizuku 官方 API 执行（需 Shizuku 服务激活 + 本应用已授权）；
 *  - 任一环节失败一律返回 null，调用方按"无增强"降级，不影响普通功能。
 *
 * 兼容性（重要）：
 *  - 不使用 `Process.waitFor(long, TimeUnit)`（API 26+，Android 7.1.1 上调用会
 *    NoSuchMethodError 崩溃）；统一用"读流线程 + Thread.join(超时) + isAlive 判断 +
 *    destroy 兜底"，Thread.join(long) 为 API 1+，全版本可用；
 *  - `redirectErrorStream(true)` 合并 stderr，避免子进程错误输出写满管道阻塞；
 *  - 执行失败进入冷却（[EXEC_FAIL_COOLDOWN_MS]），防止授权被拒/命令异常时
 *    每轮采样重复触发 su 授权弹窗或反复执行失败命令；
 *  - 反射调用 Shizuku.newProcess：签名固定、属应用自身 classpath，不受隐藏 API 限制。
 *
 * SHELL 通道说明：rikka Shizuku API 13.x 中 `Shizuku.newProcess` 为 private 方法
 * （未暴露公开执行入口），此处通过反射调用官方库自身方法完成命令执行。
 */
object ShellExecutor {

    private const val TAG = "ShellExecutor"
    private const val CMD_TIMEOUT_MS = 5_000L
    /** 失败冷却：该时长内不再尝试提权命令（防弹窗轰炸/反复失败） */
    private const val EXEC_FAIL_COOLDOWN_MS = 5 * 60 * 1000L

    /** 反射缓存的 Shizuku.newProcess(String[], String[], String) */
    private val newProcessMethod: Method? = try {
        Shizuku::class.java
            .getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            .apply { isAccessible = true }
    } catch (_: Exception) {
        null
    }

    /** 上次执行失败时间（冷却依据） */
    @Volatile
    private var lastExecFailAt = 0L

    /** Shizuku 是否已激活且本应用已授权（可真正执行命令）。 */
    fun shizukuCanExec(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    /**
     * 以当前提权通道执行命令并读取标准输出（去尾部空白）。
     * 失败/超时返回 null；失败后进入冷却，冷却期内直接返回 null。
     */
    fun execute(cmd: String): String? {
        val now = System.currentTimeMillis()
        if (now - lastExecFailAt < EXEC_FAIL_COOLDOWN_MS) return null
        val out = when (CapabilityManager.get()) {
            Capability.ROOT -> execViaRoot(cmd)
            Capability.SHELL -> execViaShizuku(cmd)
            else -> null
        }
        if (out == null) lastExecFailAt = System.currentTimeMillis()
        return out
    }

    /**
     * 通用"进程 → 输出"读取：读流线程 + join 超时 + destroy 兜底。
     * 兼容 API 25：不使用 Process.waitFor(long, TimeUnit)。
     * 输出过大/进程挂起时在超时后 destroy 并返回 null，绝不 OOM 或死锁。
     */
    private fun readProcessOutput(p: Process): String? {
        val outRef = AtomicReference<String?>(null)
        val reader = Thread {
            try {
                outRef.set(p.inputStream.bufferedReader().use { it.readText() })
            } catch (_: Exception) {
                outRef.set(null)
            }
        }
        reader.start()
        try {
            p.waitFor() // 无参 waitFor：API 1+；su 授权弹窗挂起时靠下方 join 超时兜底
        } catch (_: Exception) {
            // 进程异常时继续走 join 超时路径
        }
        reader.join(CMD_TIMEOUT_MS)
        if (reader.isAlive) {
            // 命令仍在输出/挂起（如授权弹窗未响应）：杀掉并放弃，避免线程泄漏
            runCatching { p.destroy() }
            return null
        }
        return outRef.get()?.trim()?.ifEmpty { null }
    }

    private fun execViaRoot(cmd: String): String? {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            try {
                readProcessOutput(p)
            } finally {
                runCatching { p.destroy() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun execViaShizuku(cmd: String): String? {
        if (!shizukuCanExec()) return null
        val method = newProcessMethod ?: return null
        return try {
            val p = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as? Process
                ?: return null
            try {
                readProcessOutput(p)
            } finally {
                runCatching { p.destroy() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku 执行失败（未授权或服务不可用），降级无增强", e)
            null
        }
    }
}
