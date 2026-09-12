package com.usagewatch.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.usagewatch.app.core.CapabilityManager
import com.usagewatch.app.data.LogRepository
import com.usagewatch.app.service.MonitoringService
import com.usagewatch.app.ui.LogAdapter
import com.usagewatch.app.ui.SettingsBuilder
import com.usagewatch.app.util.AppInfoResolver
import com.usagewatch.app.util.Permissions
import com.usagewatch.app.util.SettingsStore
import java.util.Calendar

/**
 * 主界面：三页 Tab（运行日志 / 文件事件 / 设置）+ 顶部筛选 + 刷新 + 监控总开关。
 *
 * 运行规则（修复"给了权限但没记录"问题）：
 *  - 记录由"总开关"控制：开启才启动采集服务并在后台保活，关闭则停止服务；
 *  - onResume 每次回到前台都同步服务状态：总开关开 → 确保服务运行（幂等）；
 *    用户从系统权限设置返回后无需重新打开应用即可开始记录；
 *  - 总开关开启时自动申请运行时权限（通知 / 存储，按版本分层）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var swMaster: Switch
    private lateinit var pageLogs: View
    private lateinit var pageFiles: View
    private lateinit var pageSettings: View
    private lateinit var spApp: Spinner
    private lateinit var spRange: Spinner
    private lateinit var listLogs: ListView
    private lateinit var listFiles: ListView
    private lateinit var settingsContainer: LinearLayout
    private lateinit var tabLogs: TextView
    private lateinit var tabFiles: TextView
    private lateinit var tabSettings: TextView

    private lateinit var logAdapter: LogAdapter
    private lateinit var fileAdapter: LogAdapter
    private lateinit var repo: LogRepository
    private lateinit var settingsBuilder: SettingsBuilder
    private lateinit var subTabAll: TextView
    private lateinit var subTabBg: TextView
    private var appPackages: List<String> = emptyList()
    private var refreshing = false

    /** 运行日志子页签：true=后台估算栏目；false=全部时间线 */
    private var bgOnly = false

    /** 刷新代次：后台查询结果只允许最新一代应用，防止快速切换筛选时旧结果覆盖新结果 */
    private var refreshGeneration = 0

    /** 已安装应用列表缓存（查询 PackageManager 较重；进程内缓存 5 分钟） */
    private var installedAppsCache: List<String>? = null
    private var installedAppsCachedAt = 0L

    /** 导出目录选择器（SAF）：选择后持久化授权并保存设置。 */
    private val exportDirLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // 授权持久化失败不致命：当前会话仍可用
                }
                SettingsStore.setExportDirUri(uri.toString())
                settingsBuilder.refreshValues()
                Toast.makeText(this, "导出目录已更新", Toast.LENGTH_SHORT).show()
            }
        }

    /** 运行时权限请求（通知 + 存储按版本分层），结果不需要额外处理：服务照常启动 */
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (!Permissions.hasNotificationPermission(this)) {
            Toast.makeText(this, R.string.toast_notification_perm, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swMaster = findViewById(R.id.swMaster)
        pageLogs = findViewById(R.id.pageLogs)
        pageFiles = findViewById(R.id.pageFiles)
        pageSettings = findViewById(R.id.pageSettings)
        spApp = findViewById(R.id.spApp)
        spRange = findViewById(R.id.spRange)
        listLogs = findViewById(R.id.listLogs)
        listFiles = findViewById(R.id.listFiles)
        settingsContainer = findViewById(R.id.settingsContainer)
        tabLogs = findViewById(R.id.tabLogs)
        tabFiles = findViewById(R.id.tabFiles)
        tabSettings = findViewById(R.id.tabSettings)
        subTabAll = findViewById(R.id.subTabAll)
        subTabBg = findViewById(R.id.subTabBg)

        repo = LogRepository(this)
        logAdapter = LogAdapter(this)
        listLogs.adapter = logAdapter
        fileAdapter = LogAdapter(this)
        listFiles.adapter = fileAdapter

        findViewById<TextView>(R.id.btnRefresh).setOnClickListener { hardRefresh() }

        initTabs()
        initSpinners()
        settingsBuilder = SettingsBuilder(this, settingsContainer) {
            refresh()
            settingsBuilder.refreshValues() // 设置变更后立即重算所有设置行显示值
        }
        settingsBuilder.build()
        initMasterSwitch()

        refresh()
        checkUsageAccess()
    }

    override fun onResume() {
        super.onResume()
        // 关键修复：每次回到前台都同步服务状态。
        // 用户在系统设置授权后返回，无需重新打开应用即可开始记录。
        syncMonitoring()
        refresh()
        // 从系统设置页返回后刷新权限/服务状态等设置行显示值
        settingsBuilder.refreshValues()
    }

    // ==================== 监控总开关 ====================

    private fun initMasterSwitch() {
        swMaster.isChecked = SettingsStore.monitoringEnabled()
        swMaster.setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
            if (on) {
                onMasterSwitchEnabled()
            } else {
                SettingsStore.setMonitoringEnabled(false)
                MonitoringService.stop(this)
                Toast.makeText(this, R.string.toast_monitoring_stopped, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
    }

    /**
     * 总开关打开：写设置 → 申请运行时权限 → 启动采集服务。
     * 供主界面开关与设置页开关共用。
     */
    fun onMasterSwitchEnabled() {
        SettingsStore.setMonitoringEnabled(true)
        swMaster.isChecked = true
        requestRuntimePermissionsIfNeeded()
        MonitoringService.start(this)
        Toast.makeText(this, R.string.toast_monitoring_started, Toast.LENGTH_SHORT).show()
        // 未开启使用情况访问则弹引导
        checkUsageAccess()
        refresh()
    }

    /** 申请本机需要的运行时权限（通知 + 存储，按版本分层；无则跳过）。设置页通知权限行也会调用。 */
    fun requestRuntimePermissionsIfNeeded() {
        val perms = mutableListOf<String>()
        perms.addAll(Permissions.requiredStoragePermissions())
        if (!Permissions.hasNotificationPermission(this)) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 已授予的过滤掉，避免重复弹窗
        perms.removeAll { p ->
            ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        }
        if (perms.isNotEmpty()) {
            permLauncher.launch(perms.toTypedArray())
        }
    }

    /** 同步服务状态：总开关开 → 确保服务在跑（幂等）；关 → 服务已由开关回调停止。 */
    private fun syncMonitoring() {
        swMaster.isChecked = SettingsStore.monitoringEnabled()
        if (SettingsStore.monitoringEnabled()) {
            // 未授权时也启动服务：文件监控不依赖使用情况访问；
            // 前台时间线数据由权限引导后自然补齐
            MonitoringService.start(this)
            checkUsageAccess()
        }
    }

    // ==================== Tab ====================

    private fun initTabs() {
        tabLogs.setOnClickListener { showPage(0) }
        tabFiles.setOnClickListener { showPage(1) }
        tabSettings.setOnClickListener { showPage(2) }
        // 运行日志子页签：全部时间线 / 后台估算（后台同窗口多应用并行，独立栏目展示）
        subTabAll.setOnClickListener { showSubTab(false) }
        subTabBg.setOnClickListener { showSubTab(true) }
        showPage(0)
    }

    private fun showSubTab(bg: Boolean) {
        if (bgOnly == bg) return
        bgOnly = bg
        subTabAll.setTextColor(getColor(if (bg) R.color.text_sub else R.color.primary))
        subTabBg.setTextColor(getColor(if (bg) R.color.primary else R.color.text_sub))
        refresh()
    }

    private fun showPage(index: Int) {
        pageLogs.visibility = if (index == 0) View.VISIBLE else View.GONE
        pageFiles.visibility = if (index == 1) View.VISIBLE else View.GONE
        pageSettings.visibility = if (index == 2) View.VISIBLE else View.GONE
        val active = getColor(R.color.primary)
        val inactive = getColor(R.color.text_sub)
        tabLogs.setTextColor(if (index == 0) active else inactive)
        tabFiles.setTextColor(if (index == 1) active else inactive)
        tabSettings.setTextColor(if (index == 2) active else inactive)
    }

    // ==================== 筛选 ====================

    /** 设置页"选择导出目录"入口（SAF 文件夹选择器）。 */
    fun openExportDirPicker() {
        try {
            exportDirLauncher.launch(null)
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开目录选择器", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 全部已安装应用（可启动的 launcher 应用，含系统应用）。
     * 查询 PackageManager 较重，进程内缓存 5 分钟避免每次刷新都全量扫描。
     */
    private fun installedApps(): List<String> {
        val now = System.currentTimeMillis()
        installedAppsCache?.let {
            if (now - installedAppsCachedAt < 5 * 60_000L) return it
        }
        val list = try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
                .sortedWith(compareBy { AppInfoResolver.resolve(this, it).label.lowercase() })
        } catch (_: Exception) {
            emptyList()
        }
        installedAppsCache = list
        installedAppsCachedAt = now
        return list
    }

    private fun initSpinners() {
        // 应用筛选：全部应用 + 所有已安装应用（记录过的按最近打开在前，其余按字母序）
        spApp.adapter = object : BaseAdapter() {
            override fun getCount(): Int = appPackages.size + 1
            override fun getItem(pos: Int): Any = if (pos == 0) "" else appPackages[pos - 1]
            override fun getItemId(pos: Int): Long = pos.toLong()
            override fun getView(pos: Int, v: View?, parent: ViewGroup): View {
                val tv = TextView(this@MainActivity).apply { textSize = 15f }
                tv.text = if (pos == 0) {
                    getString(R.string.filter_all_apps)
                } else {
                    AppInfoResolver.resolve(this@MainActivity, appPackages[pos - 1]).label
                }
                return tv
            }
        }
        spRange.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            arrayOf(
                getString(R.string.filter_today),
                getString(R.string.filter_yesterday),
                getString(R.string.filter_7d),
                getString(R.string.filter_all)
            )
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        spApp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { if (!refreshing) refresh() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spRange.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { if (!refreshing) refresh() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /**
     * 顶部刷新按钮：重启采集服务 + 全量刷新。
     * 若服务因系统限制/异常卡死（心跳停止、日志不更新），点此可让采集链路整体重建恢复，
     * 而非仅仅重查列表。重启在 150ms 后执行，避免与旧实例销毁竞争。
     */
    private fun hardRefresh() {
        Toast.makeText(this, "正在重启采集服务并刷新…", Toast.LENGTH_SHORT).show()
        if (MonitoringService.isRunning()) {
            MonitoringService.stop(this)
        }
        if (SettingsStore.monitoringEnabled()) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                MonitoringService.start(this)
            }, 150)
        }
        refresh()
        settingsBuilder.refreshValues()
    }

    /** 刷新列表与权限模式显示。查询在后台线程执行，避免主线程 SQLite IO 卡顿。 */
    private fun refresh() {
        refreshing = true
        val gen = ++refreshGeneration
        // 主线程捕获筛选状态，后台线程只读数据
        val pkgSel = spApp.selectedItemPosition
        val rangeSel = spRange.selectedItemPosition
        val bg = bgOnly
        Thread {
            // 应用下拉：记录过的（最近优先）+ 全部已安装应用（未记录的按字母序补在后面）
            val packages = try {
                val recorded = repo.distinctAppPackages()
                val all = installedApps()
                (recorded + all.filterNot { it in recorded })
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "应用列表查询失败", e)
                emptyList()
            }
            val (start, end) = rangeBounds(rangeSel)
            val pkg = if (pkgSel <= 0) null else packages.getOrNull(pkgSel - 1)
            val usage = try {
                if (bg) repo.queryBackgroundOnly(pkg, start, end)
                else repo.queryUsage(pkg, start, end)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "运行日志查询失败", e)
                emptyList()
            }
            val files = try {
                repo.queryFiles(start, end)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "文件日志查询失败", e)
                emptyList()
            }
            runOnUiThread {
                refreshing = false // 无论结果是否过期，先复位标志，保证后续筛选可用
                if (gen != refreshGeneration) return@runOnUiThread // 丢弃过期结果
                appPackages = packages
                (spApp.adapter as BaseAdapter).notifyDataSetChanged()
                logAdapter.submit(usage)
                fileAdapter.submit(files)
                settingsBuilder.updateStatus() // 服务状态行跟随刷新
            }
        }.start()
    }

    /** 时间范围：今天 / 昨天 / 近 7 天 / 全部。 */
    private fun rangeBounds(pos: Int): Pair<Long, Long> = when (pos) {
        0 -> startOfDay(0) to Long.MAX_VALUE
        1 -> startOfDay(-1) to startOfDay(0)
        2 -> startOfDay(-6) to Long.MAX_VALUE
        else -> 0L to Long.MAX_VALUE
    }

    private fun startOfDay(offsetDays: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, offsetDays)
        return cal.timeInMillis
    }

    // ==================== 权限引导 ====================

    private fun checkUsageAccess() {
        if (Permissions.hasUsageAccess(this)) return
        // 一次性引导：避免每次打开应用/每次回前台都弹窗打扰（设置页仍有入口）
        if (SettingsStore.isUsageGuideShown()) return
        SettingsStore.setUsageGuideShown(true)
        AlertDialog.Builder(this)
            .setTitle(R.string.title_usage_access)
            .setMessage(R.string.msg_usage_access)
            .setPositiveButton(R.string.btn_goto_settings) { _, _ ->
                Permissions.openUsageAccessSettings(this)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}
