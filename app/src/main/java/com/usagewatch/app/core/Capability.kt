package com.usagewatch.app.core

/**
 * 运行权限模式（自动检测，普通模式保底）。
 *
 *  - NORMAL：普通用户权限。核心功能（前台时间线 / 公共目录文件事件）完整可用。
 *  - SHELL ：Shizuku 已激活授权（或进程以 shell 身份运行）。增强：进程级前后台、实时前台、更广目录。
 *  - ROOT  ：su 存在或进程以 root 身份运行。增强：其他应用私有目录文件事件、audit 文件访问归属（POC 验证）。
 */
enum class Capability {
    NORMAL, SHELL, ROOT
}
