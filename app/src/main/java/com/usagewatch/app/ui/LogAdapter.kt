package com.usagewatch.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.usagewatch.app.R
import com.usagewatch.app.data.LogRepository
import com.usagewatch.app.data.model.LogEntry
import com.usagewatch.app.util.AppInfoResolver
import com.usagewatch.app.util.SettingsStore

/**
 * 统一日志列表适配器（应用运行日志与文件事件共用）。
 *
 * 双模式渲染（依据设置）：
 *  - GUI 模式：小图标（仅应用事件）+ 名称 + 时间 + 人话详情；
 *  - CODE 模式：tvCode 占满整行，monospace 原始字段（时间 | 包名 | TYPE | 详情），无图标。
 *
 * 整行长按复制：两种模式都支持，把当前行文本复制到剪贴板（ListView 会拦截
 * textIsSelectable 的长按选择，因此用显式长按回调实现，保证可复制）。
 */
class LogAdapter(
    private val context: Context
) : BaseAdapter() {

    private var items: List<LogEntry> = emptyList()

    fun submit(list: List<LogEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): LogEntry = items[position]

    override fun getItemId(position: Int): Long = items[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: View.inflate(context, R.layout.item_log, null)
        val e = try {
            getItem(position)
        } catch (_: Exception) {
            return v
        }

        val ivIcon = v.findViewById<ImageView>(R.id.ivIcon)
        val llMain = v.findViewById<View>(R.id.llMain)
        val tvMain = v.findViewById<TextView>(R.id.tvMain)
        val tvDetail = v.findViewById<TextView>(R.id.tvDetail)
        val tvCode = v.findViewById<TextView>(R.id.tvCode)

        val codeMode = SettingsStore.displayMode() == SettingsStore.DisplayMode.CODE
        val time = LogRepository.formatTimestamp(e.timestamp)

        // 渲染异常兜底：任何一条记录的解析失败都不能拖垮整个列表，回退为可见文本
        try {
            val detail = decorateDetail(e)
            if (codeMode) {
                ivIcon.visibility = View.GONE
                llMain.visibility = View.GONE // 关键：隐藏图形区，纯代码行占满整行，消除空白
                tvCode.visibility = View.VISIBLE
                tvCode.text = LogFileWriterCompat.format(e, detail)
            } else {
                ivIcon.visibility = if (LogEntry.isAppType(e.type)) View.VISIBLE else View.INVISIBLE
                llMain.visibility = View.VISIBLE
                tvCode.visibility = View.GONE

                if (LogEntry.isAppType(e.type)) {
                    val info = AppInfoResolver.resolve(context, e.packageName) // 内部已兜底，图标不为 null
                    ivIcon.setImageDrawable(info.icon)
                    tvMain.text = "${time}  ${info.label}"
                } else {
                    tvMain.text = "${time}  ${context.getString(LogEntry.typeNameRes(e.type))}"
                }
                tvDetail.text = detail
            }

            // 整行长按复制（两种模式通用；convertView 复用时重新绑定）
            v.setOnLongClickListener {
                val text = if (codeMode) tvCode.text.toString()
                else "${tvMain.text}\n${tvDetail.text}"
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("usage_log", text))
                    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }
                true
            }
        } catch (_: Exception) {
            // 极低概率的渲染异常：退化为纯文本行，保证列表继续可用
            ivIcon.visibility = View.GONE
            llMain.visibility = View.GONE
            tvCode.visibility = View.VISIBLE
            tvCode.text = "${time} | ${e.packageName.ifEmpty { "-" }} | ${LogEntry.typeCode(e.type)} | ${e.detail}"
        }
        return v
    }

    /**
     * 详情标注增强（SHELL/ROOT 提权增益的可视化体现）：
     * 后台估算条目若检测到该应用进程当前存活，追加"进程存活确认"标注——
     * 普通模式无此能力（看不到其他应用进程），标注自然不出现。
     */
    private fun decorateDetail(e: LogEntry): String {
        return try {
            if (e.type == LogEntry.TYPE_BG_RUN &&
                com.usagewatch.app.core.ProcessMonitor.isAlive(e.packageName)
            ) {
                "${e.detail}（进程存活确认）"
            } else {
                e.detail
            }
        } catch (_: Exception) {
            e.detail
        }
    }
}

/** 纯代码行的格式化，避免 UI 层直接依赖 LogFileWriter 的 IO 语义。 */
internal object LogFileWriterCompat {
    fun format(e: LogEntry, detail: String = e.detail): String =
        "${LogRepository.formatTimestamp(e.timestamp)} | ${e.packageName.ifEmpty { "-" }} | " +
            "${LogEntry.typeCode(e.type)} | $detail"
}
