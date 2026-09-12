package com.usagewatch.app.data.model

/**
 * 统一日志条目（应用事件与文件事件共用一张展示模型）。
 *
 * 渲染规则（LogAdapter）：
 *  - 图形模式：图标 + appLabel + 时间 + detail（人话）；
 *  - 纯代码模式：timestamp | packageName | TYPE_XXX | detail（原始字段直出）。
 */
data class LogEntry(
    val id: Long = 0,
    val timestamp: Long,
    val type: Int,
    val packageName: String,
    val appLabel: String?,
    val detail: String,
    val startTime: Long = 0,
    val endTime: Long = 0,
    val durationMs: Long = 0
) {
    companion object {
        // ---- 应用事件类型 ----
        const val TYPE_FG_START = 1      // 前台开始
        const val TYPE_FG_END = 2        // 前台结束
        const val TYPE_BG_RUN = 3        // 后台运行（估算口径）

        // ---- 文件事件类型 ----
        const val TYPE_FILE_WRITE = 10   // 文件写入
        const val TYPE_FILE_MOVE = 11    // 文件移动（含重命名）
        const val TYPE_FILE_CREATE = 12  // 文件创建
        const val TYPE_FILE_DELETE = 13  // 文件删除
        const val TYPE_MEDIA_CHANGE = 14 // 媒体库变化

        // ---- 屏幕事件类型 ----
        const val TYPE_SCREEN_ON = 15    // 亮屏
        const val TYPE_SCREEN_OFF = 16   // 熄屏/锁屏
        const val TYPE_SYS_NOTE = 17     // 系统提示（数据源状态等一次性说明）

        /** 事件类型 → 展示文案 key（strings.xml） */
        fun typeNameRes(type: Int): Int = when (type) {
            TYPE_FG_START, TYPE_FG_END -> com.usagewatch.app.R.string.event_foreground
            TYPE_BG_RUN -> com.usagewatch.app.R.string.event_background
            TYPE_FILE_WRITE -> com.usagewatch.app.R.string.event_file_write
            TYPE_FILE_MOVE -> com.usagewatch.app.R.string.event_file_move
            TYPE_FILE_CREATE -> com.usagewatch.app.R.string.event_file_create
            TYPE_FILE_DELETE -> com.usagewatch.app.R.string.event_file_delete
            TYPE_MEDIA_CHANGE -> com.usagewatch.app.R.string.event_media_change
            TYPE_SCREEN_ON -> com.usagewatch.app.R.string.event_screen_on
            TYPE_SCREEN_OFF -> com.usagewatch.app.R.string.event_screen_off
            TYPE_SYS_NOTE -> com.usagewatch.app.R.string.event_sys_note
            else -> com.usagewatch.app.R.string.unknown_app
        }

        /** 纯代码模式用的原始事件名 */
        fun typeCode(type: Int): String = when (type) {
            TYPE_FG_START -> "FOREGROUND_START"
            TYPE_FG_END -> "FOREGROUND_END"
            TYPE_BG_RUN -> "BACKGROUND_RUN"
            TYPE_FILE_WRITE -> "FILE_WRITE"
            TYPE_FILE_MOVE -> "FILE_MOVE"
            TYPE_FILE_CREATE -> "FILE_CREATE"
            TYPE_FILE_DELETE -> "FILE_DELETE"
            TYPE_MEDIA_CHANGE -> "MEDIA_CHANGE"
            TYPE_SCREEN_ON -> "SCREEN_ON"
            TYPE_SCREEN_OFF -> "SCREEN_OFF"
            TYPE_SYS_NOTE -> "SYS_NOTE"
            else -> "UNKNOWN($type)"
        }

        fun isAppType(type: Int): Boolean = type in 1..3
        fun isFileType(type: Int): Boolean = type in 10..14
        fun isScreenType(type: Int): Boolean = type == TYPE_SCREEN_ON || type == TYPE_SCREEN_OFF
        fun isSysNote(type: Int): Boolean = type == TYPE_SYS_NOTE
    }
}
