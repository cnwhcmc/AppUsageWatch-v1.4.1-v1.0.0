package com.usagewatch.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.usagewatch.app.data.model.LogEntry

/**
 * SQLite 存储（原生 SQLiteOpenHelper，刻意不引 Room，控制体积与依赖）。
 * 两张表：usage_events（应用运行事件）、file_events（文件事件）。
 */
class DbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "usage_watch.db"
        const val DB_VERSION = 1

        const val T_USAGE = "usage_events"
        const val T_FILE = "file_events"

        const val COL_ID = "id"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_TYPE = "type"
        const val COL_PACKAGE = "package_name"
        const val COL_LABEL = "app_label"
        const val COL_DETAIL = "detail"
        const val COL_START = "start_time"
        const val COL_END = "end_time"
        const val COL_DURATION = "duration_ms"
        const val COL_PATH = "path"
        const val COL_EXTRA = "extra"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $T_USAGE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_TYPE INTEGER NOT NULL,
                $COL_PACKAGE TEXT NOT NULL,
                $COL_LABEL TEXT,
                $COL_DETAIL TEXT,
                $COL_START INTEGER,
                $COL_END INTEGER,
                $COL_DURATION INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_usage_pkg_time ON $T_USAGE($COL_PACKAGE, $COL_TIMESTAMP)")
        db.execSQL("CREATE INDEX idx_usage_time ON $T_USAGE($COL_TIMESTAMP)")

        db.execSQL(
            """
            CREATE TABLE $T_FILE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_TYPE INTEGER NOT NULL,
                $COL_PATH TEXT NOT NULL,
                $COL_EXTRA TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_file_time ON $T_FILE($COL_TIMESTAMP)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 骨架阶段无迁移；后续版本升级按 oldVersion 分支迁移
        if (oldVersion < 1) {
            db.execSQL("DROP TABLE IF EXISTS $T_USAGE")
            db.execSQL("DROP TABLE IF EXISTS $T_FILE")
            onCreate(db)
        }
    }

    // ==================== 写 ====================

    fun insertUsage(entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            entries.forEach { e ->
                db.insert(T_USAGE, null, usageValues(e))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun insertFile(entry: LogEntry) {
        writableDatabase.insert(T_FILE, null, fileValues(entry))
    }

    fun insertFiles(entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            entries.forEach { e -> db.insert(T_FILE, null, fileValues(e)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun usageValues(e: LogEntry): ContentValues = ContentValues().apply {
        put(COL_TIMESTAMP, e.timestamp)
        put(COL_TYPE, e.type)
        put(COL_PACKAGE, e.packageName)
        put(COL_LABEL, e.appLabel)
        put(COL_DETAIL, e.detail)
        put(COL_START, e.startTime)
        put(COL_END, e.endTime)
        put(COL_DURATION, e.durationMs)
    }

    private fun fileValues(e: LogEntry): ContentValues = ContentValues().apply {
        put(COL_TIMESTAMP, e.timestamp)
        put(COL_TYPE, e.type)
        put(COL_PATH, e.detail)
        put(COL_EXTRA, e.packageName) // 文件事件中 packageName 字段复用为 extra（如源/目标路径）
    }

    // ==================== 读 ====================

    /** 查询应用运行事件；pkg 为空查全部；start/end 为 0 时不过滤时间；excludeBg 排除后台估算条目。 */
    fun queryUsage(pkg: String?, start: Long, end: Long, limit: Int, excludeBg: Boolean = false): List<LogEntry> {
        val selection = buildString {
            val conds = mutableListOf<String>()
            if (!pkg.isNullOrEmpty()) conds.add("$COL_PACKAGE = ?")
            if (start > 0) conds.add("$COL_TIMESTAMP >= ?")
            if (end > 0) conds.add("$COL_TIMESTAMP <= ?")
            if (excludeBg) conds.add("$COL_TYPE != ${LogEntry.TYPE_BG_RUN}")
            if (conds.isNotEmpty()) append(conds.joinToString(" AND "))
        }
        val args = buildList {
            if (!pkg.isNullOrEmpty()) add(pkg)
            if (start > 0) add(start.toString())
            if (end > 0) add(end.toString())
        }.toTypedArray()
        val cursor = readableDatabase.query(
            T_USAGE, null, selection.ifEmpty { null }, args.ifEmpty { null },
            null, null, "$COL_TIMESTAMP DESC", if (limit > 0) limit.toString() else null
        )
        return cursor.use { readUsage(it) }
    }

    /** 仅查询后台估算条目（后台栏目专用：同一时段多个应用并行展示）。 */
    fun queryBackgroundOnly(pkg: String?, start: Long, end: Long, limit: Int): List<LogEntry> {
        val selection = buildString {
            val conds = mutableListOf("$COL_TYPE = ${LogEntry.TYPE_BG_RUN}")
            if (!pkg.isNullOrEmpty()) conds.add("$COL_PACKAGE = ?")
            if (start > 0) conds.add("$COL_TIMESTAMP >= ?")
            if (end > 0) conds.add("$COL_TIMESTAMP <= ?")
            append(conds.joinToString(" AND "))
        }
        val args = buildList {
            if (!pkg.isNullOrEmpty()) add(pkg)
            if (start > 0) add(start.toString())
            if (end > 0) add(end.toString())
        }.toTypedArray()
        val cursor = readableDatabase.query(
            T_USAGE, null, selection, args.ifEmpty { null },
            null, null, "$COL_TIMESTAMP DESC", if (limit > 0) limit.toString() else null
        )
        return cursor.use { readUsage(it) }
    }

    private fun readUsage(c: Cursor): List<LogEntry> {
        val list = ArrayList<LogEntry>(c.count.coerceAtLeast(0))
        val iId = c.getColumnIndexOrThrow(COL_ID)
        val iTs = c.getColumnIndexOrThrow(COL_TIMESTAMP)
        val iType = c.getColumnIndexOrThrow(COL_TYPE)
        val iPkg = c.getColumnIndexOrThrow(COL_PACKAGE)
        val iLabel = c.getColumnIndexOrThrow(COL_LABEL)
        val iDetail = c.getColumnIndexOrThrow(COL_DETAIL)
        val iStart = c.getColumnIndexOrThrow(COL_START)
        val iEnd = c.getColumnIndexOrThrow(COL_END)
        val iDur = c.getColumnIndexOrThrow(COL_DURATION)
        while (c.moveToNext()) {
            list.add(
                LogEntry(
                    id = c.getLong(iId),
                    timestamp = c.getLong(iTs),
                    type = c.getInt(iType),
                    packageName = c.getString(iPkg),
                    appLabel = c.getString(iLabel),
                    detail = c.getString(iDetail),
                    startTime = c.getLong(iStart),
                    endTime = c.getLong(iEnd),
                    durationMs = c.getLong(iDur)
                )
            )
        }
        return list
    }

    fun queryFiles(start: Long, end: Long, limit: Int): List<LogEntry> {
        val selection = buildString {
            val conds = mutableListOf<String>()
            if (start > 0) conds.add("$COL_TIMESTAMP >= ?")
            if (end > 0) conds.add("$COL_TIMESTAMP <= ?")
            if (conds.isNotEmpty()) append(conds.joinToString(" AND "))
        }
        val args = buildList {
            if (start > 0) add(start.toString())
            if (end > 0) add(end.toString())
        }.toTypedArray()
        val cursor = readableDatabase.query(
            T_FILE, null, selection.ifEmpty { null }, args.ifEmpty { null },
            null, null, "$COL_TIMESTAMP DESC", if (limit > 0) limit.toString() else null
        )
        return cursor.use { readFiles(it) }
    }

    private fun readFiles(c: Cursor): List<LogEntry> {
        val list = ArrayList<LogEntry>(c.count.coerceAtLeast(0))
        val iId = c.getColumnIndexOrThrow(COL_ID)
        val iTs = c.getColumnIndexOrThrow(COL_TIMESTAMP)
        val iType = c.getColumnIndexOrThrow(COL_TYPE)
        val iPath = c.getColumnIndexOrThrow(COL_PATH)
        val iExtra = c.getColumnIndexOrThrow(COL_EXTRA)
        while (c.moveToNext()) {
            list.add(
                LogEntry(
                    id = c.getLong(iId),
                    timestamp = c.getLong(iTs),
                    type = c.getInt(iType),
                    packageName = c.getString(iExtra) ?: "",
                    appLabel = null,
                    detail = c.getString(iPath) ?: "",
                    startTime = c.getLong(iTs),
                    endTime = 0,
                    durationMs = 0
                )
            )
        }
        return list
    }

    /** 已记录过运行事件的应用包名（去重，按最近使用排序）。 */
    fun distinctAppPackages(): List<String> {
        val out = ArrayList<String>()
        // 只统计真实应用事件：排除空包名（亮/熄屏、系统提示条目 packageName 为空，
        // 此前会被当成"应用"返回，在下拉里产生空白选项并掩盖真实应用列表），
        // 且只统计应用类型（前台开始/结束/后台估算），文件事件不入应用列表。
        // 注意：不能写 SELECT DISTINCT ... ORDER BY MAX(...)（非法聚合用法），
        // 必须用 GROUP BY 聚合并按组内最大时间戳排序。
        readableDatabase.rawQuery(
            "SELECT $COL_PACKAGE, MAX($COL_TIMESTAMP) AS mt FROM $T_USAGE " +
                "WHERE $COL_PACKAGE != '' AND $COL_TYPE IN " +
                "(${LogEntry.TYPE_FG_START}, ${LogEntry.TYPE_FG_END}, ${LogEntry.TYPE_BG_RUN}) " +
                "GROUP BY $COL_PACKAGE ORDER BY mt DESC",
            null
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    // ==================== 清理 ====================

    /** 删除 timestamp < cutoff 的数据；cutoff <= 0 表示不清理。 */
    fun deleteOlderThan(cutoff: Long) {
        if (cutoff <= 0) return
        writableDatabase.delete(T_USAGE, "$COL_TIMESTAMP < ?", arrayOf(cutoff.toString()))
        writableDatabase.delete(T_FILE, "$COL_TIMESTAMP < ?", arrayOf(cutoff.toString()))
    }

    /** 清空全部日志（用户手动"清理全部日志"按钮；返回删除行数）。 */
    fun deleteAll(): Int {
        val n1 = writableDatabase.delete(T_USAGE, null, null)
        val n2 = writableDatabase.delete(T_FILE, null, null)
        return n1 + n2
    }

    /** VACUUM 收缩数据库文件：删除行后 DB 文件体积不自动缩小，需重建释放空间。 */
    fun vacuum() {
        try {
            writableDatabase.execSQL("VACUUM")
        } catch (_: Exception) {
            // 有并发事务时 VACUUM 可能失败：忽略（下次清理再试）
        }
    }
}
