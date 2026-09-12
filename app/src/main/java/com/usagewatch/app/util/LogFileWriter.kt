package com.usagewatch.app.util

import android.content.Context
import com.usagewatch.app.data.model.LogEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 按天滚动的日志文件（写入应用专属外部目录，无需存储权限）。
 * 目录：/sdcard/Android/data/<包名>/files/logs/app_YYYYMMdd.log
 *
 * 文件内容始终写"原始字段"格式（信息完整、对程序员友好）；
 * 展示层的图形/纯代码切换不影响落盘格式。
 */
object LogFileWriter {

    private val dayFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd", Locale.US) }
    // 毫秒级对使用记录无意义，落盘日志统一到秒
    private val lineFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }

    private fun logsDir(context: Context): File =
        context.getExternalFilesDir("logs") ?: context.filesDir.resolve("logs")

    private fun fileFor(context: Context, timestamp: Long): File =
        File(logsDir(context), "app_${dayFmt.get()?.format(Date(timestamp)) ?: timestamp}.log")

    @Synchronized
    fun append(context: Context, entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        val groups = entries.groupBy { it.timestamp / 86_400_000L } // 按天分组
        groups.forEach { (_, group) ->
            val file = fileFor(context, group.first().timestamp)
            file.parentFile?.mkdirs()
            val lines = group.sortedBy { it.timestamp }
                .joinToString("\n") { formatLine(context, it, codeMode = true) }
            file.appendText(lines + "\n")
        }
    }

    /** 单行格式化。codeMode=true 输出原始字段；false 输出人话行。 */
    fun formatLine(context: Context, e: LogEntry, codeMode: Boolean): String {
        val time = lineFmt.get()?.format(Date(e.timestamp)) ?: e.timestamp.toString()
        return if (codeMode) {
            "${time} | ${e.packageName.ifEmpty { "-" }} | ${LogEntry.typeCode(e.type)} | ${e.detail}"
        } else {
            "${time} | ${e.appLabel ?: e.packageName.ifEmpty { "-" }} | " +
                context.getString(LogEntry.typeNameRes(e.type)) + " | " + e.detail
        }
    }

    /** 删除 cutoff 之前的日志文件。 */
    fun cleanup(context: Context, cutoff: Long) {
        logsDir(context).listFiles()?.forEach { f ->
            val name = f.name.removePrefix("app_").removeSuffix(".log")
            val day = try {
                SimpleDateFormat("yyyyMMdd", Locale.US).parse(name)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
            if (day in 1 until cutoff) f.delete()
        }
    }

    /** 删除全部日志文件（用户手动"清理全部日志"按钮）。 */
    fun deleteAll(context: Context) {
        logsDir(context).listFiles()?.forEach { f -> f.delete() }
    }

    /**
     * 记录运行异常到当天的日志文件（与事件日志同文件，前缀 [ERROR]），
     * 供用户直接查看"哪里出了异常"。任何失败都静默，不递归记录。
     */
    @Synchronized
    fun logException(context: Context, tag: String, source: String, t: Throwable) {
        val now = System.currentTimeMillis()
        try {
            val file = fileFor(context, now)
            file.parentFile?.mkdirs()
            val head = "${lineFmt.get()?.format(Date(now)) ?: now} | [ERROR] | $tag/$source | " +
                "${t.javaClass.simpleName}: ${t.message ?: ""}"
            val stack = t.stackTrace.take(8).joinToString(" -> ") {
                "${it.className}.${it.methodName}(${it.lineNumber})"
            }
            file.appendText("$head\n  at $stack\n")
        } catch (_: Exception) {
            // 写入失败不再递归
        }
    }
}
