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
        // 文件事件与应用无关联：该页隐藏"全部应用"筛选下拉，仅保留时间段筛选（时间限制对文件日志同样生效）
        spApp.visibility = if (index == 1) View.GONE else View.VISIBLE
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
        // 应用筛选：全部应用 / 仅监测所选应用（可勾选，真实控制后台采集范围）/ 单个已安装应用
        spApp.adapter = object : BaseAdapter() {
            override fun getCount(): Int = appPackages.size + 2
            override fun getItem(pos: Int): Any = when (pos) {
                0 -> ""
                1 -> "\u0000monitor"
                else -> appPackages[pos - 2]
            }
            override fun getItemId(pos: Int): Long = pos.toLong()
            override fun getView(pos: Int, v: View?, parent: ViewGroup): View {
                val tv = TextView(this@MainActivity).apply { textSize = 15f }
                tv.text = when (pos) {
                    0 -> getString(R.string.filter_all_apps)
                    1 -> monitorSpinnerLabel()
                    else -> AppInfoResolver.resolve(this@MainActivity, appPackages[pos - 2]).label
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
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == 1) {
                    // "仅监测所选应用"：不受 refreshing 标志限制，任何时候点击都必须弹窗
                    // （首次打开时 refresh 正在后台跑，若被 refreshing 拦截会导致弹窗出不来）
                    showMonitorAppsDialog()
                } else if (pos == 0) {
                    // 切回"全部应用" = 恢复监测全部应用（取消应用过滤），与勾选语义闭环
                    if (SettingsStore.monitorFiltered()) {
                        SettingsStore.setMonitorFiltered(false)
                        SettingsStore.setMonitorPackages(emptySet())
                        (spApp.adapter as BaseAdapter).notifyDataSetChanged()
                        Toast.makeText(this@MainActivity, "已恢复：监测全部应用", Toast.LENGTH_SHORT).show()
                    }
                    if (!refreshing) refresh()
                } else if (!refreshing) {
                    refresh()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spRange.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { if (!refreshing) refresh() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /** 监测下拉第二项文案：未启用=入口提示；已启用=当前监测数量。 */
    private fun monitorSpinnerLabel(): String {
        return if (SettingsStore.monitorFiltered()) {
            val n = SettingsStore.monitorPackages().size
            "仅监测所选应用（$n 个）"
        } else {
            "仅监测所选应用…"
        }
    }

    /**
     * 勾选监测应用入口：首次弹出带"不再提示"的说明；之后直接进勾选列表。
     * 确定后写入设置：后台采集（UsageStats / 轮询 / 校准）在源头丢弃未勾选应用的数据，
     * 日志体积随监测范围缩小；取消则恢复为"全部应用"。
     */
    private fun showMonitorAppsDialog() {
        val apps = try { installedApps() } catch (_: Exception) { emptyList() }
        if (apps.isEmpty()) {
            Toast.makeText(this, "未找到可监测的应用", Toast.LENGTH_SHORT).show()
            spApp.setSelection(0)
            return
        }
        if (!SettingsStore.monitorHintDismissed()) {
            showMonitorHintOnce(apps)
        } else {
            showMonitorPicker(apps)
        }
    }

    /** 一次性说明（可勾选"不再提示"）：一句说明 + 勾选"不再提示"，之后直接进勾选列表。 */
    private fun showMonitorHintOnce(apps: List<String>) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        val tip = TextView(this).apply {
            text = "只记录勾选的应用，未勾选的不再产生新日志。"
            textSize = 14f
            setTextColor(getColor(R.color.text_main))
        }
        val dismissBox = android.widget.CheckBox(this).apply {
            text = "不再提示"
            textSize = 14f
            setPadding(0, dp(12), 0, 0)
        }
        body.addView(tip)
        body.addView(dismissBox)
        AlertDialog.Builder(this)
            .setTitle("仅监测所选应用")
            .setView(body)
            .setPositiveButton("知道了") { d, _ ->
                SettingsStore.setMonitorHintDismissed(dismissBox.isChecked)
                d.dismiss()
                showMonitorPicker(apps)
            }
            .setNegativeButton("取消") { d, _ ->
                d.dismiss()
                // 保持当前下拉项：取消=不改动监测状态
            }
            .show()
    }

    /**
     * 勾选列表（自定义 View）：顶部搜索框 + 每行 CheckBox + 应用图标 + 应用名。
     * 勾选框本身可点击（点框即勾选），点击行其他区域也可切换，两种入口互不冲突。
     * 搜索按应用名/包名实时过滤；底部"全选/清空"就地刷新；确定后写入设置，取消则回退全部应用。
     */
    private fun showMonitorPicker(apps: List<String>) {
        val selected = SettingsStore.monitorPackages()
        val checked = BooleanArray(apps.size) { i -> selected.contains(apps[i]) }
        val filteredApps = ArrayList(apps)

        // 搜索框：实时按名称/包名过滤（小写忽略大小写；空输入显示全部）
        val searchInput = android.widget.EditText(this).apply {
            hint = "搜索应用"
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }

        val listView = ListView(this)
        listView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = filteredApps.size
            override fun getItem(pos: Int): Any = filteredApps[pos]
            override fun getItemId(pos: Int): Long = pos.toLong()
            override fun getView(pos: Int, v: View?, parent: ViewGroup): View {
                // ViewHolder 复用：滚动/过滤时不重复创建子控件，大列表不卡顿
                val holder: PickerHolder
                val row: LinearLayout
                if (v == null) {
                    row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(dp(8), dp(10), dp(8), dp(10))
                    }
                    val cb = android.widget.CheckBox(this@MainActivity).apply {
                        isFocusable = false // 可点击但不抢焦点框；状态由自身点击直接同步
                        setPadding(0, 0, dp(10), 0)
                    }
                    val icon = android.widget.ImageView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
                    }
                    val label = TextView(this@MainActivity).apply {
                        textSize = 15f
                        setPadding(dp(10), 0, 0, 0)
                    }
                    row.addView(cb)
                    row.addView(icon)
                    row.addView(label)
                    holder = PickerHolder(cb, icon, label)
                    row.tag = holder
                    // 整行点击切换勾选（点击勾选框时由勾选框自身处理，不会走到这里）
                    row.setOnClickListener {
                        val ai = holder.appIdx
                        if (ai in checked.indices) {
                            checked[ai] = !checked[ai]
                            notifyDataSetChanged()
                        }
                    }
                    // 勾选框自身点击：状态已翻转，直接同步数组（不重建行，视觉即时）
                    cb.setOnClickListener {
                        val ai = holder.appIdx
                        if (ai in checked.indices) checked[ai] = cb.isChecked
                    }
                } else {
                    @Suppress("UNCHECKED_CAST")
                    row = v as LinearLayout
                    holder = row.tag as PickerHolder
                }
                val pkg = filteredApps[pos]
                val appIdx = apps.indexOf(pkg)
                holder.appIdx = appIdx
                holder.cb.isChecked = checked[appIdx]
                holder.icon.setImageDrawable(AppInfoResolver.resolve(this@MainActivity, pkg).icon)
                holder.label.text = AppInfoResolver.resolve(this@MainActivity, pkg).label
                return row
            }
        }
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim()?.lowercase() ?: ""
                filteredApps.clear()
                if (q.isEmpty()) {
                    filteredApps.addAll(apps)
                } else {
                    for (pkg in apps) {
                        val label = runCatching {
                            AppInfoResolver.resolve(this@MainActivity, pkg).label.lowercase()
                        }.getOrDefault(pkg)
                        if (label.contains(q) || pkg.contains(q)) filteredApps.add(pkg)
                    }
                }
                (listView.adapter as BaseAdapter).notifyDataSetChanged()
            }
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        val btnAll = android.widget.Button(this).apply {
            text = "全选"
            setOnClickListener {
                for (i in checked.indices) checked[i] = true
                (listView.adapter as BaseAdapter).notifyDataSetChanged()
            }
        }
        val btnClear = android.widget.Button(this).apply {
            text = "清空"
            setOnClickListener {
                for (i in checked.indices) checked[i] = false
                (listView.adapter as BaseAdapter).notifyDataSetChanged()
            }
        }
        btnRow.addView(btnAll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(btnClear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val emptyTv = TextView(this).apply {
            text = "无匹配应用"
            gravity = android.view.Gravity.CENTER
            setTextColor(getColor(R.color.text_sub))
            textSize = 14f
            setPadding(0, dp(28), 0, 0)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        root.addView(searchInput)
        root.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(emptyTv)
        root.addView(btnRow)
        listView.emptyView = emptyTv // 过滤无结果时显示空态，列表隐藏

        AlertDialog.Builder(this)
            .setTitle("仅监测所选应用")
            .setView(root)
            .setPositiveButton("确定") { d, _ ->
                val chosen = apps.filterIndexed { i, _ -> checked[i] }.toSet()
                if (chosen.isEmpty()) {
                    SettingsStore.setMonitorFiltered(false)
                    SettingsStore.setMonitorPackages(emptySet())
                    Toast.makeText(this, "未勾选应用，保持监测全部", Toast.LENGTH_SHORT).show()
                    d.dismiss()
                    spApp.setSelection(0) // 显示"全部应用"（filtered 已关，选中项仅触发刷新）
                } else {
                    SettingsStore.setMonitorFiltered(true)
                    SettingsStore.setMonitorPackages(chosen)
                    Toast.makeText(this, "已设置：仅监测 ${chosen.size} 个应用", Toast.LENGTH_SHORT).show()
                    d.dismiss()
                    (spApp.adapter as BaseAdapter).notifyDataSetChanged()
                    refresh()
                }
            }
            .setNegativeButton("取消") { d, _ ->
                d.dismiss()
                // 保持当前下拉项：取消=不改动监测状态（若这里回 0 会在已过滤时误触发"恢复全部"）
            }
            .show()
    }

    /** 勾选列表行复用器：缓存子控件与当前行对应的全量列表索引。 */
    private class PickerHolder(
        val cb: android.widget.CheckBox,
        val icon: android.widget.ImageView,
        val label: TextView,
        var appIdx: Int = -1
    )

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

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
            // pos0=全部应用、pos1=仅监测所选应用（查看全部）：均不按单应用过滤
            val pkg = if (pkgSel <= 1) null else packages.getOrNull(pkgSel - 2)
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
