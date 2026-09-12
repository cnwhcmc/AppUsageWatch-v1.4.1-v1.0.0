package com.usagewatch.app.collector

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import com.usagewatch.app.data.model.LogEntry
import com.usagewatch.app.util.Permissions
import com.usagewatch.app.util.SettingsStore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 文件事件监听（普通权限：公共目录 FileObserver + 媒体库 ContentObserver）。
 *
 * 两种范围（设置页可切换）：
 *  - 指定目录：监控 SettingsStore.monitorDirs() 所列目录（默认 6 个公共目录，可自定义增删），
 *    每个目录递归深度 2 层；
 *  - 整个公共存储（全盘）：授权后递归监控 /storage/emulated/0 下所有目录，
 *    受系统 inotify watch 上限约束（[WHOLE_WATCH_LIMIT] 硬上限），新建目录动态补监听，超出上限的目录跳过并记录。
 *
 * 边界（与无 root 限制一致）：
 *  - FileObserver 基于 inotify，只能覆盖"本应用有权访问的目录"；
 *  - Android 11+ 的 Android/data 不可访问，即使开启"所有文件访问"；
 *  - 只能记录"文件在何时发生什么变化"，无法获知"哪个应用操作"（归属需 Root + audit，POC）。
 *
 * 稳定性：启动全程 try/catch，单个目录失败不影响整体；inotify 上限保护防止 watch 耗尽拖垮系统。
 */
class FileEventCollector(
    private val context: Context,
    private val onEvents: (List<LogEntry>) -> Unit
) {

    companion object {
        private const val TAG = "FileEventCollector"
        private const val MAX_DEPTH = 2
        private const val MIN_BG_INTERVAL = 1000L // 事件节流，防止瞬时风暴
        /** 全盘模式 inotify 硬上限：预留余量给系统与其他应用（默认 max_user_watches≈8192） */
        private const val WHOLE_WATCH_LIMIT = 6000
        /** 全盘模式跳过的系统目录/不可访问目录 */
        private val SKIP_DIRS = setOf("Android", "LOST.DIR")
    }

    private var observers = ArrayList<RecursiveFileObserver>()
    private var mediaObserver: ContentObserver? = null
    private var mediaThread: HandlerThread? = null
    /** 按"路径"节流：inotify 对同一操作常多次回调；不同路径的事件不应互相丢弃 */
    private val lastEmitByPath = HashMap<String, Long>()

    /** 是否有权限启动文件监控。 */
    fun canStart(): Boolean = Permissions.hasStorageAccess(context)

    /** 启动目录监听 + 媒体库监听。异常隔离：任何失败只记录，不向上抛。 */
    fun start() {
        try {
            stop()
            if (!canStart()) {
                Log.w(TAG, "存储权限未满足，跳过文件监控")
                return
            }
            val root = Environment.getExternalStorageDirectory()
            val whole = SettingsStore.monitorWholeStorage()
            if (whole) {
                // 全盘模式：递归 /storage/emulated/0，深度不限，受 watch 上限约束
                val obs = RecursiveFileObserver(
                    root.absolutePath,
                    Int.MAX_VALUE,
                    WHOLE_WATCH_LIMIT,
                    onEvent = { path, mask -> emit(path, eventFromMask(path, mask)) }
                )
                observers.add(obs)
                obs.startWatching()
                Log.i(TAG, "全盘文件监控已启动，实际监控目录=${obs.watchCount()}")
            } else {
                // 指定目录模式：相对名按公共根解析，绝对路径（自定义）直接使用
                val names = SettingsStore.monitorDirs()
                var started = 0
                for (name in names) {
                    val dir = if (name.startsWith("/")) File(name) else File(root, name)
                    if (!dir.isDirectory) continue
                    val obs = RecursiveFileObserver(
                        dir.absolutePath,
                        MAX_DEPTH,
                        0,
                        onEvent = { path, mask -> emit(path, eventFromMask(path, mask)) }
                    )
                    observers.add(obs)
                    obs.startWatching()
                    started++
                }
                Log.i(TAG, "目录文件监控已启动，目录数=$started")
            }
            startMediaObserver()
        } catch (e: Exception) {
            Log.e(TAG, "文件监控启动异常（已隔离）", e)
        }
    }

    fun stop() {
        try {
            observers.forEach { it.stopWatching() }
        } catch (_: Exception) {
        }
        observers.clear()
        stopMediaObserver()
        lastEmitByPath.clear()
    }

    // ==================== 事件 → 日志条目 ====================

    private fun eventFromMask(path: String, mask: Int): LogEntry? {
        val ts = System.currentTimeMillis()
        val type = when {
            mask and android.os.FileObserver.MOVED_TO != 0 ->
                LogEntry.TYPE_FILE_MOVE to "移动至 $path"
            mask and android.os.FileObserver.MOVED_FROM != 0 ->
                LogEntry.TYPE_FILE_MOVE to "移动自 $path"
            mask and android.os.FileObserver.CLOSE_WRITE != 0 ->
                LogEntry.TYPE_FILE_WRITE to "写入 $path"
            mask and android.os.FileObserver.CREATE != 0 ->
                LogEntry.TYPE_FILE_CREATE to "创建 $path"
            mask and android.os.FileObserver.DELETE != 0 ->
                LogEntry.TYPE_FILE_DELETE to "删除 $path"
            else -> return null
        }
        return LogEntry(
            timestamp = ts,
            type = type.first,
            packageName = "",
            appLabel = null,
            detail = type.second
        )
    }

    /** 按路径节流：同一路径同一类型 1 秒内只记一次（inotify 对同一操作常多次回调）。 */
    private fun emit(key: String, e: LogEntry?) {
        if (e == null) return
        // 防无界增长：全盘模式长期运行会产生海量不同路径键，超上限重建（节流短暂失效可接受）
        if (lastEmitByPath.size > 5000) lastEmitByPath.clear()
        val now = System.currentTimeMillis()
        val last = lastEmitByPath[key] ?: 0L
        if (now - last < MIN_BG_INTERVAL) return
        lastEmitByPath[key] = now
        onEvents(listOf(e))
    }

    // ==================== 媒体库监听（ContentObserver） ====================

    private fun startMediaObserver() {
        try {
            val thread = HandlerThread("media-observer").apply { start() }
            mediaThread = thread
            val handler = Handler(thread.looper)
            val resolver = context.contentResolver
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    emit(
                        "media",
                        LogEntry(
                            timestamp = System.currentTimeMillis(),
                            type = LogEntry.TYPE_MEDIA_CHANGE,
                            packageName = "",
                            appLabel = null,
                            detail = "媒体库变化 ${uri ?: "（未知 URI）"}"
                        )
                    )
                }
            }
            mediaObserver = observer
            registerMediaUris(resolver, observer)
        } catch (e: Exception) {
            Log.e(TAG, "媒体库监听启动异常（已隔离）", e)
        }
    }

    @Suppress("DEPRECATION") // EXTERNAL_CONTENT_URI 在低版本唯一可用，API 29+ 建议 VOLUME_EXTERNAL
    private fun registerMediaUris(
        resolver: android.content.ContentResolver,
        observer: ContentObserver
    ) {
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
    }

    private fun stopMediaObserver() {
        try {
            mediaObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        } catch (_: Exception) {
        }
        mediaObserver = null
        mediaThread?.quitSafely()
        mediaThread = null
    }

    // ==================== 递归 FileObserver ====================

    /**
     * 递归目录监听：FileObserver 单实例只监听一个目录，需为子目录逐个注册。
     * - [maxDepth]：递归深度上限（指定目录模式 2；全盘模式不限）；
     * - [maxWatches]：目录数硬上限（0 = 不限；全盘模式 6000），超出即停止扩展；
     * - 新建子目录动态补监听（CREATE 事件回调中检测），保证运行期间新建目录也被覆盖；
     * - 单目录注册/启动失败仅跳过，不中断整体；[stopped] 置位后不再扩展。
     */
    private class RecursiveFileObserver(
        private val path: String,
        private val maxDepth: Int,
        maxWatches: Int,
        private val onEvent: (String, Int) -> Unit
    ) {
        private val watches = ArrayList<android.os.FileObserver>()
        private val watchLimit = maxWatches.coerceAtLeast(0)
        private val count = AtomicInteger(0)
        /** 已访问目录（canonical 路径）：防符号链接环/重复注册导致的无限递归 */
        private val visited = HashSet<String>()
        @Volatile
        private var stopped = false

        fun startWatching() {
            visited.clear()
            walk(File(path), 0)
        }

        fun stopWatching() {
            stopped = true
            synchronized(watches) {
                watches.forEach { w ->
                    try {
                        w.stopWatching()
                    } catch (_: Exception) {
                    }
                }
                watches.clear()
            }
            visited.clear()
        }

        fun watchCount(): Int = count.get()

        private fun walk(dir: File, depth: Int) {
            if (stopped) return
            if (depth > maxDepth) return
            if (watchLimit > 0 && count.get() >= watchLimit) return
            if (!dir.isDirectory) return
            // 防环：canonical 路径去重（符号链接指向祖先/自身时 absolutePath 不同但 canonical 相同）
            val key = try {
                dir.canonicalPath
            } catch (_: Exception) {
                dir.absolutePath
            }
            if (!visited.add(key)) return
            // 不可读目录（listFiles 为 null）跳过，避免权限不足时继续下钻
            val children = dir.listFiles() ?: return
            @Suppress("DEPRECATION") // (String, Int) 构造跨版本兼容；(File, Int) 需 API 29+
            val watcher = object : android.os.FileObserver(
                dir.absolutePath,
                android.os.FileObserver.CREATE or
                    android.os.FileObserver.DELETE or
                    android.os.FileObserver.MOVED_FROM or
                    android.os.FileObserver.MOVED_TO or
                    android.os.FileObserver.CLOSE_WRITE
            ) {
                override fun onEvent(event: Int, filePath: String?) {
                    if (stopped) return
                    if (filePath.isNullOrEmpty()) return
                    // 动态跟踪新建目录：CREATE 目录时继续下钻（全盘模式保持覆盖范围）
                    if (event and android.os.FileObserver.CREATE != 0) {
                        val child = File(dir, filePath)
                        if (child.isDirectory && !filePath.startsWith(".") &&
                            filePath !in SKIP_DIRS && !isSymlink(child)
                        ) {
                            walk(child, depth + 1)
                        }
                    }
                    onEvent("${dir.absolutePath}/$filePath", event)
                }
            }
            val added = synchronized(watches) {
                if (stopped) return
                if (watchLimit > 0 && count.get() >= watchLimit) return
                if (watchLimit > 0 && count.incrementAndGet() > watchLimit) {
                    count.decrementAndGet()
                    return
                }
                watches.add(watcher)
                true
            }
            if (added) {
                try {
                    watcher.startWatching()
                } catch (_: Exception) {
                    // inotify 资源耗尽或系统限制：跳过该目录，不影响其余
                    synchronized(watches) { watches.remove(watcher) }
                    count.decrementAndGet()
                    return
                }
            }
            for (child in children) {
                if (!child.isDirectory) continue
                if (child.name.startsWith(".")) continue
                if (child.name in SKIP_DIRS) continue
                if (isSymlink(child)) continue // 跳过符号链接（防环 + 防重复监控）
                walk(child, depth + 1)
            }
        }

        /** 符号链接检测：canonical 解析后与自身路径不一致即为链接（跨版本，无需 java.nio）。 */
        private fun isSymlink(f: File): Boolean = try {
            f.canonicalPath != f.absolutePath
        } catch (_: Exception) {
            false
        }
    }
}
