package com.usagewatch.app.core

/**
 * Root 保活助手（仅 ROOT 模式可用；普通/SHELL 模式返回不可用）：
 *
 * 阉割/定制 ROM 常缺失"省电白名单/电池优化"设置页（系统设置应用无此页面，
 * 点击即闪退），此时用 root 直接写入系统级保活配置，绕过缺失的设置入口：
 *  - Doze 白名单（`dumpsys deviceidle whitelist +pkg`）：熄屏/深度休眠后应用
 *    仍可运行，是"后台长期存活"最有效的系统配置；
 *  - 后台运行允许（`cmd appops set` RUN_IN_BACKGROUND / RUN_ANY_IN_BACKGROUND）：
 *    Android 7+ 的后台运行限制豁免，防止被 standby/后台限制冻结。
 *
 * 稳定性：
 *  - 命令通过 ShellExecutor 执行（带超时/冷却/防反复弹窗），失败一律返回 false，
 *    不影响主流程；
 *  - 命令以 `&& echo OK` 结尾以区分"成功但无输出"与"失败"（读输出通道拿不到退出码）；
 *  - 幂等：重复执行无副作用；check 只读不写。
 */
object KeepAliveHelper {

    private const val PKG = "com.usagewatch.app"

    /** 是否可使用 root 保活：仅 ROOT 模式（shell/Shizuku 无 CHANGE_DEVICE_IDLE_MODE 权限）。 */
    fun canUseRootKeepAlive(): Boolean =
        CapabilityManager.get() == Capability.ROOT && CapabilityManager.canQueryProcesses()

    /**
     * 应用 root 保活（Doze 白名单 + 后台运行豁免）。
     * @return 是否有命令成功执行（任一成功即视为已应用）
     */
    fun applyRootKeepAlive(): Boolean {
        if (!canUseRootKeepAlive()) return false
        val r1 = ShellExecutor.execute("dumpsys deviceidle whitelist +$PKG && echo OK")
        val r2 = ShellExecutor.execute("cmd appops set $PKG RUN_IN_BACKGROUND allow && echo OK")
        val r3 = ShellExecutor.execute("cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow && echo OK")
        return r1 == "OK" || r2 == "OK" || r3 == "OK"
    }

    /**
     * 检查 root 保活是否已生效（只读：Doze 白名单是否包含本包）。
     * @return true=已生效；false/无权限/检查失败=未生效或未知
     */
    fun checkRootKeepAlive(): Boolean {
        if (!canUseRootKeepAlive()) return false
        val out = ShellExecutor.execute("dumpsys deviceidle whitelist | grep '$PKG'")
        return !out.isNullOrEmpty()
    }
}
