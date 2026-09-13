package com.usagewatch.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.usagewatch.app.data.model.LogEntry
import com.usagewatch.app.util.LogFileWriter
import com.usagewatch.app.util.Permissions
import com.usagewatch.app.util.SettingsStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 仓储层：采集结果统一经此入库并同步写日志文件。
 * 所有 UI 查询也走这里，屏蔽 DbHelper 细节。
 */
class LogRepository(private val context: Context) {

    private val db = DbHelper(context)

    // ==================== 写入 ====================

    fun insertUsage(entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        db.insertUsage(entries)
        LogFileWriter.append(context, entries)
    }

    fun insertFiles(entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        db.insertFiles(entries)
        LogFileWriter.append(context, entries)
    }

    // ==================== 查询 ====================

    /** 全部时间线（排除后台估算，后台条目由后台栏目单独展示）。 */
    fun queryUsage(pkg: String?, start: Long, end: Long, limit: Int = 5000): List<LogEntry> =
        db.queryUsage(pkg, start, end, limit, excludeBg = true)

    /** 后台估算条目（后台栏目）。 */
    fun queryBackgroundOnly(pkg: String?, start: Long, end: Long, limit: Int = 5000): List<LogEntry> =
        db.queryBackgroundOnly(pkg, start, end, limit)

    fun queryFiles(start: Long, end: Long, limit: Int = 5000): List<LogEntry> =
        db.queryFiles(start, end, limit)

    fun distinctAppPackages(): List<String> = db.distinctAppPackages()

    // ==================== 清理 ====================

    /** 按保留天数清理 DB 与日志文件。返回清理是否执行。 */
    fun cleanup(now: Long): Boolean {
        val cutoff = SettingsStore.retentionCutoffMillis(now)
        if (cutoff <= 0) return false
        db.deleteOlderThan(cutoff)
        LogFileWriter.cleanup(context, cutoff)
        return true
    }

    /** 清空全部日志（DB + 落盘文件）并收缩 DB 文件。返回删除的 DB 行数。 */
    fun deleteAll(): Int {
        val n = db.deleteAll()
        LogFileWriter.deleteAll(context)
        db.vacuum() // 删除后立即收缩，防止"越用越大"
        return n
    }

    // ==================== 导出 ====================

    /**
     * 导出为文本文件（跟随当前显示模式：图形模式导出人话行，纯代码模式导出原始字段行）。
     * 通过 FileProvider 分享，返回可分享的 Uri。
     */
    fun export(start: Long, end: Long): Uri? {
        // 上限 100k：防止一次导出在低端机内存溢出（每天数千条正常数据远低于此）
        val entries = (db.queryUsage(null, start, end, 100_000) +
            db.queryFiles(start, end, 100_000)).sortedBy { it.timestamp }
        if (entries.isEmpty()) return null

        val codeMode = SettingsStore.displayMode() == SettingsStore.DisplayMode.CODE
        val lines = entries.map { e -> LogFileWriter.formatLine(context, e, codeMode) }

        val dir = context.getExternalFilesDir("exports") ?: context.filesDir
        val file = File(dir, "export_${System.currentTimeMillis()}.txt")
        file.writeText(lines.joinToString("\n") + "\n")

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    fun shareExport(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * 导出到用户目录（默认公共 Download；设置中可自定义 SAF 目录）。
     * 返回可直接展示/分享的文件路径或 Uri 字符串；失败返回 null。
     * Android 11+ 未授予"所有文件访问"时默认目录降级为应用专属 Download（文件管理器可见）。
     */
    fun exportToFile(start: Long, end: Long): String? {
        val entries = (db.queryUsage(null, start, end, 100_000) +
            db.queryFiles(start, end, 100_000)).sortedBy { it.timestamp }
        if (entries.isEmpty()) return null

        val codeMode = SettingsStore.displayMode() == SettingsStore.DisplayMode.CODE
        val lines = entries.map { e -> LogFileWriter.formatLine(context, e, codeMode) }
        val text = lines.joinToString("\n") + "\n"
        val name = "使用日志_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}.txt"

        val customUri = SettingsStore.exportDirUri()
        if (!customUri.isNullOrEmpty()) {
            // 自定义目录（SAF tree uri）
            return try {
                val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, android.net.Uri.parse(customUri))
                    ?: return null
                val file = tree.createFile("text/plain", name) ?: return null
                context.contentResolver.openOutputStream(file.uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                file.uri.toString()
            } catch (_: Exception) {
                null
            }
        }
        // 默认 Download（Android 11+ 无所有文件访问权限时写应用专属目录）
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Permissions.hasAllFilesAccess()) {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } ?: return null
        return try {
            dir.mkdirs()
            val f = java.io.File(dir, name)
            f.writeText(text, Charsets.UTF_8)
            f.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        // 毫秒级对使用记录无意义，统一到秒，减少视觉噪音
        // 不用 ThreadLocal.withInitial（API 26+，minSdk 25 会崩溃），用 initialValue() 覆盖（API 1 兼容）
        private val fmt = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        }

        fun formatTimestamp(ts: Long): String = fmt.get()?.format(Date(ts)) ?: ""
    }
}
