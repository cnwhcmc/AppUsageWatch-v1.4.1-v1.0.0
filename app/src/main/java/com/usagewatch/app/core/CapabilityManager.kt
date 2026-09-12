package com.usagewatch.app.core

import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicReference
import com.usagewatch.app.util.SettingsStore

/**
 * 权限能力检测与状态管理。
 *
 * 设计原则：
 *  - 普通权限是保底基线，检测失败或未授权一律回退 NORMAL，不阻塞任何功能；
 *  - 检测只读、轻量、有异常兜底，避免卡 UI 或骚扰用户；
 *  - **判断准确性**：只凭"Shizuku 安装了"不算有 shell 权限（装而未激活＝无权限）；
 *    必须 Shizuku 服务激活且 binder 可用才判定 SHELL；
 *  - **省电切换**：设置中可强制普通模式（forceNormalMode），此时不做任何提权探测，
 *    也不启用任何提权增强（dumpsys/ps/su 全部跳过），适合不需要高权限的场景。
 *
 * 判定顺序（自动模式，从上到下）：
 *  1. 进程实际身份：`id -u` 为 0 → ROOT；为 2000（shell uid）→ SHELL（调试/特殊环境）；
 *  2. `su` 存在于 PATH 或常见安装路径 → ROOT（存在即具备提权能力，不执行 su 避免触发授权弹窗）；
 *  3. Shizuku 已激活（官方 pingBinder）→ SHELL；
 *  4. 其余 → NORMAL。
 */
object CapabilityManager {

    private val current = AtomicReference(Capability.NORMAL)

    fun get(): Capability = current.get()

    /** 供 UI 显示的模式名（含"手动/自动"标注）。 */
    fun label(): String {
        return when (get()) {
            Capability.NORMAL -> if (SettingsStore.forceNormalMode()) "普通模式（手动省电）" else "普通模式"
            Capability.SHELL -> "Shell 模式（Shizuku 授权）"
            Capability.ROOT -> "Root 模式"
        }
    }

    /** 是否启用提权增强（SHELL/ROOT 且未强制普通模式）。 */
    fun canUseEnhancements(): Boolean = !SettingsStore.forceNormalMode() && get() != Capability.NORMAL

    /** 重新检测能力（后台线程调用；强制普通模式时直接返回 NORMAL 且不做探测）。 */
    fun refresh(): Capability {
        val detected = if (SettingsStore.forceNormalMode()) {
            Capability.NORMAL
        } else {
            detect()
        }
        current.set(detected)
        return detected
    }

    private fun detect(): Capability {
        // 1) 进程身份实测（兜住"无 Shizuku 但有 shell/root 身份"的特殊环境）
        processUidCapability()?.let { return it }
        // 2) su 存在（具备提权能力）
        if (hasRootBinary()) return Capability.ROOT
        // 3) Shizuku 已激活（binder 可用）
        if (isShizukuActive()) return Capability.SHELL
        return Capability.NORMAL
    }

    // ---- 能力谓词：上层功能按需查询，避免到处判断模式 ----

    /** 能否读取其他应用私有目录（/data/data 目录）文件事件 */
    fun canWatchPrivateDirs(): Boolean = canUseEnhancements() && get() == Capability.ROOT

    /** 能否读取系统审计日志（文件访问归属）——POC 验证项 */
    fun canReadAuditLog(): Boolean = canUseEnhancements() && get() == Capability.ROOT

    /** 能否做进程级前后台检测（dumpsys / ps） */
    fun canQueryProcesses(): Boolean = canUseEnhancements()

    /**
     * 进程实际 uid 检测：`id -u` 输出 0 → root 身份；2000 → shell 身份。
     * 覆盖"无 Shizuku 但进程以提权身份运行"的调试/特殊环境。
     */
    private fun processUidCapability(): Capability? = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "id -u"))
        val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
        p.waitFor()
        when (out) {
            "0" -> Capability.ROOT
            "2000" -> Capability.SHELL
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 检测 root：探测 su 是否可用（PATH 或常见安装路径），不执行 su
     * （避免触发 Magisk/KernelSU 授权弹窗）。su 存在即具备提权能力，判定为 ROOT 设备。
     * 覆盖 KernelSU(/data/adb/ksu/bin)/APatch/定制 ROM(/system/xbin 等)的非常规路径。
     */
    private fun hasRootBinary(): Boolean = try {
        val p = Runtime.getRuntime().exec(
            arrayOf(
                "sh", "-c",
                "command -v su || ls /system/bin/su /system/xbin/su /sbin/su /vendor/bin/su " +
                    "/data/adb/ksu/bin/su /data/adb/ksu/bin/ksud 2>/dev/null"
            )
        )
        val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
        p.waitFor()
        try {
            p.destroy()
        } catch (_: Exception) {
        }
        out.isNotEmpty()
    } catch (_: Exception) {
        false
    }

    /**
     * 检测 Shizuku 是否**激活且可用**（不是仅安装）：
     * 官方 rikka API 的 pingBinder 在服务激活后返回 true；未激活返回 false。
     */
    private fun isShizukuActive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Exception) {
        false
    }
}
