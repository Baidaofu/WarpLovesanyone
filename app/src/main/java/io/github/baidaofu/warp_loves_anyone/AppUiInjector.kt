package io.github.baidaofu.warp_loves_anyone

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import java.util.Locale

/**
 * 注入到 Cloudflare App 设置界面的 UI 与相关读写逻辑。
 *
 * - ConnectionOptionsActivity（设置-高级-连接选项）："管理被排除的应用"行下方注入
 *   "强制代理列表"行；点击进入列表页（复用原生行布局：图标 + 名称 + ✕ 移除），
 *   页内"管理"按钮打开原生样式的应用多选选择器（图标 + 复选框，保存生效）。
 * - SettingsActivity（设置-高级）："使用深色主题"行下方注入"跟随系统主题"开关；
 *   开启后 App 自带的深色开关置灰失效、显示系统状态，主题由系统代管。
 *
 * 注入复用的全部是目标 App 自带资源：layout_disabled_app_user_blocked_header /
 * layout_disabled_app / layout_disabled_app_no_items / layout_exclude_application_entry /
 * fragment_dialog_applications_list / CloudflareAlertDialogStyle。
 *
 * 配置统一存于目标 App 私有 SharedPreferences（[HookEntry.CONFIG_PREFS]），
 * 本注入 UI 与 HookEntry 的 Hook 同进程读写，即时生效。
 */
object AppUiInjector {

    private const val TAG = "WarpLovesAnyone"
    private const val FOLLOW_ROW_TAG = "wla_follow_system_row"
    private const val FORCE_ROW_TAG = "wla_force_proxy_row"

    var appContext: Context? = null

    private var module: XposedInterface? = null

    fun install(module: XposedInterface, packageName: String, classLoader: ClassLoader) {
        this.module = module
        val settings = runCatching {
            Class.forName("com.cloudflare.app.presentation.settings.SettingsActivity", false, classLoader)
        }.getOrNull()
        val connection = runCatching {
            Class.forName("com.cloudflare.app.presentation.settings.ConnectionOptionsActivity", false, classLoader)
        }.getOrNull()
        if (settings != null) hookActivityOnCreate(settings, "settings_ui") { injectSettingsScreen(it) }
        if (connection != null) hookActivityOnCreate(connection, "connection_ui") { injectConnectionScreen(it) }
        if (settings == null || connection == null) {
            module.log(Log.WARN, TAG, "settings activities not found in $packageName")
        }
    }

    private fun hookActivityOnCreate(clazz: Class<*>, hookId: String, after: (Activity) -> Unit) {
        val m = clazz.getDeclaredMethod("onCreate", Bundle::class.java)
        module?.hook(m)?.setId(hookId)?.intercept { chain ->
            chain.proceed()
            try {
                after(chain.thisObject as Activity)
            } catch (t: Throwable) {
                module?.log(Log.WARN, TAG, "UI inject failed", t)
            }
            null // onCreate 为 void
        }
    }

    /** 一次性迁移：v2.6 及之前存在框架远程偏好里的强制代理列表 → App 本地配置 */
    private fun migrateRemoteConfigIfNeeded() {
        if (remoteMigrated) return
        val m = module ?: return
        remoteMigrated = true
        try {
            val ctx = currentContext() ?: return
            val remote = m.getRemotePreferences("config")
                .getStringSet(HookEntry.KEY_FORCE_PROXY_PACKAGES, null)
            if (!remote.isNullOrEmpty()) {
                val prefs = ctx.getSharedPreferences(HookEntry.CONFIG_PREFS, Context.MODE_PRIVATE)
                if (prefs.getStringSet(HookEntry.KEY_FORCE_PROXY_PACKAGES, null).isNullOrEmpty()) {
                    prefs.edit().putStringSet(HookEntry.KEY_FORCE_PROXY_PACKAGES, remote).apply()
                    m.log(Log.INFO, TAG, "migrated ${remote.size} package(s) from remote prefs")
                }
            }
        } catch (t: Throwable) {
            m.log(Log.WARN, TAG, "remote config migration failed", t)
        }
    }

    private var remoteMigrated = false

    // ---------- 配置读写 ----------

    fun currentContext(): Context? = appContext ?: runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()

    private fun configPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(HookEntry.CONFIG_PREFS, Context.MODE_PRIVATE)

    fun forceProxyPackages(): Set<String>? = currentContext()?.let {
        configPrefs(it).getStringSet(HookEntry.KEY_FORCE_PROXY_PACKAGES, null)
    }

    fun followSystemTheme(): Boolean = currentContext()?.let {
        configPrefs(it).getBoolean(HookEntry.KEY_FOLLOW_SYSTEM_THEME, true)
    } ?: true

    private fun setFollowSystemTheme(context: Context, value: Boolean) {
        configPrefs(context).edit().putBoolean(HookEntry.KEY_FOLLOW_SYSTEM_THEME, value).apply()
    }

    private fun saveForceProxyPackages(context: Context, packages: Set<String>) {
        configPrefs(context).edit()
            .putStringSet(HookEntry.KEY_FORCE_PROXY_PACKAGES, packages)
            .apply()
    }

    private fun currentForceList(activity: Activity): Set<String> =
        configPrefs(activity).getStringSet(HookEntry.KEY_FORCE_PROXY_PACKAGES, null) ?: emptySet()

    private fun isSystemDarkMode(): Boolean {
        val uiMode = android.content.res.Resources.getSystem().getConfiguration().uiMode
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun zh(): Boolean = Locale.getDefault().language.equals("zh", ignoreCase = true)

    private fun Int.dp(activity: Activity): Int =
        (this * activity.resources.displayMetrics.density).toInt()

    // ---------- 设置页："跟随系统主题" 开关 ----------

    private fun injectSettingsScreen(activity: Activity) {
        migrateRemoteConfigIfNeeded()
        val res = activity.resources
        val containerId = res.getIdentifier("darkModeContainer", "id", activity.packageName)
        if (containerId == 0) return
        val darkContainer = activity.findViewById<View>(containerId) ?: return
        val parent = darkContainer.parent as? ViewGroup ?: return
        if (parent.findViewWithTag<View>(FOLLOW_ROW_TAG) != null) return

        // 克隆原生「使用深色主题」整行：整体 inflate 设置页布局后摘出该节点，
        // 背景、内边距、Switch 样式与原生完全一致
        var row: View? = null
        var rowSwitch: Switch? = null
        try {
            val layoutId = res.getIdentifier("activity_settings", "layout", activity.packageName)
            if (layoutId != 0) {
                val tmp = LinearLayout(activity)
                activity.layoutInflater.inflate(layoutId, tmp, true)
                val cloned = tmp.findViewById<ViewGroup?>(containerId)
                if (cloned != null) {
                    (cloned.parent as? ViewGroup)?.removeView(cloned)
                    var sw: Switch? = null
                    var tv: TextView? = null
                    collectViews(cloned) {
                        if (it is Switch && sw == null) sw = it
                        if (it is TextView && tv == null) tv = it
                    }
                    clearIds(cloned)
                    tv?.text = if (zh()) "跟随系统主题" else "Follow system theme"
                    cloned.tag = FOLLOW_ROW_TAG
                    row = cloned
                    rowSwitch = sw
                }
            }
        } catch (t: Throwable) {
            module?.log(Log.WARN, TAG, "clone native row failed, fallback to manual", t)
        }

        // 兜底：克隆失败时手工构建
        var theSwitch = rowSwitch
        if (row == null) {
            val titleSrc = res.getIdentifier("useDarkThemeTv", "id", activity.packageName)
                .let { if (it != 0) activity.findViewById<TextView>(it) else null }
            val manual = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                tag = FOLLOW_ROW_TAG
                setPadding(
                    darkContainer.paddingLeft, darkContainer.paddingTop,
                    darkContainer.paddingRight, darkContainer.paddingBottom
                )
                background = darkContainer.background
            }
            val title = TextView(activity).apply {
                text = if (zh()) "跟随系统主题" else "Follow system theme"
                titleSrc?.let { copyTextStyle(it, this) }
            }
            manual.addView(
                title,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            manual.addView(Switch(activity))
            theSwitch = manual.getChildAt(1) as Switch
            row = manual
        }

        parent.addView(row, parent.indexOfChild(darkContainer) + 1, copyLp(darkContainer.layoutParams))
        theSwitch!!.isChecked = followSystemTheme()
        theSwitch.setOnCheckedChangeListener { _, checked ->
            setFollowSystemTheme(activity, checked)
            if (checked) {
                // 让 App 自带开关的显示与系统一致（写入其原始偏好）
                rawAppDarkMode(activity)?.let { stored ->
                    if (stored != isSystemDarkMode()) writeRawAppDarkMode(activity, isSystemDarkMode())
                }
            }
            syncDarkSwitch(activity)
            applyNightMode(activity)
        }
        syncDarkSwitch(activity)
    }

    private fun collectViews(root: View, onView: (View) -> Unit) {
        onView(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) collectViews(root.getChildAt(i), onView)
        }
    }

    private fun clearIds(root: View) {
        root.id = View.NO_ID
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) clearIds(root.getChildAt(i))
        }
    }

    /** 同步 App 自带"使用深色主题"开关：跟随模式下置灰失效、显示系统状态 */
    private fun syncDarkSwitch(activity: Activity) {
        val res = activity.resources
        val switchId = res.getIdentifier("darkModeSwitch", "id", activity.packageName)
        if (switchId == 0) return
        val sw = activity.findViewById<Switch>(switchId) ?: return
        if (followSystemTheme()) {
            sw.isEnabled = false
            sw.alpha = 0.4f
            sw.isChecked = isSystemDarkMode()
        } else {
            sw.isEnabled = true
            sw.alpha = 1f
            sw.isChecked = rawAppDarkMode(activity) ?: false
        }
    }

    /** 读取 App 原始 dark_mode 值（文件 <包名>_preferences；跟随关闭时 Hook 放行，读到真实存储值） */
    private fun rawAppDarkMode(activity: Activity): Boolean? {
        val prefs = activity.getSharedPreferences(
            "${activity.packageName}_preferences", Context.MODE_PRIVATE
        )
        if (!prefs.contains("dark_mode")) return null
        return prefs.getBoolean("dark_mode", false)
    }

    private fun writeRawAppDarkMode(activity: Activity, value: Boolean) {
        activity.getSharedPreferences(
            "${activity.packageName}_preferences", Context.MODE_PRIVATE
        ).edit().putBoolean("dark_mode", value).apply()
    }

    /** 立即应用当前模式：跟随系统 → FOLLOW_SYSTEM；否则应用 App 原始存储值 */
    private fun applyNightMode(activity: Activity) {
        try {
            val follow = followSystemTheme()
            val mode = if (follow) {
                -1 // MODE_NIGHT_FOLLOW_SYSTEM
            } else {
                if (rawAppDarkMode(activity) == true) 2 else 1 // MODE_NIGHT_YES / NO
            }
            val delegate = Class.forName(
                "androidx.appcompat.app.AppCompatDelegate", false, activity.classLoader
            )
            val candidates = delegate.declaredMethods.filter {
                java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                    it.parameterTypes.size == 1 && it.parameterTypes[0] == java.lang.Integer.TYPE
            }
            if (candidates.size == 1) {
                candidates[0].invoke(null, mode)
            }
        } catch (t: Throwable) {
            module?.log(Log.WARN, TAG, "apply night mode failed", t)
        }
    }

    // ---------- 连接选项页："强制代理列表" 行 ----------

    private fun injectConnectionScreen(activity: Activity) {
        migrateRemoteConfigIfNeeded()
        val res = activity.resources
        val btnId = res.getIdentifier("excludeAppsBtn", "id", activity.packageName)
        if (btnId == 0) return
        val excludeBtn = activity.findViewById<TextView>(btnId) ?: return
        val parent = excludeBtn.parent as? ViewGroup ?: return
        if (parent.findViewWithTag<View>(FORCE_ROW_TAG) != null) return

        val idx = parent.indexOfChild(excludeBtn)
        val separator = parent.getChildAt(idx + 1)

        val row = TextView(activity).apply {
            tag = FORCE_ROW_TAG
            text = if (zh()) "强制代理列表" else "Force proxy list"
            copyTextStyle(excludeBtn, this)
            isClickable = true
            isFocusable = true
            setOnClickListener { showForceProxyListDialog(activity) }
        }
        val sep = View(activity).apply { background = separator.background }

        parent.addView(row, idx + 2, copyLp(excludeBtn.layoutParams))
        parent.addView(sep, idx + 3, copyLp(separator.layoutParams))
    }

    // ---------- 强制代理列表页（照抄原生"管理被排除的应用"布局与交互） ----------

    private class AppEntry(val pkg: String, val label: String, val icon: Drawable)

    private fun showForceProxyListDialog(activity: Activity) {
        val res = activity.resources
        val pkgName = activity.packageName
        fun layoutId(name: String) = res.getIdentifier(name, "layout", pkgName)
        fun id(name: String) = res.getIdentifier(name, "id", pkgName)
        val pm = activity.packageManager

        fun loadEntries(): ArrayList<AppEntry> = ArrayList(
            currentForceList(activity).sorted().map { pkg ->
                val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                AppEntry(
                    pkg,
                    info?.loadLabel(pm)?.toString() ?: pkg,
                    info?.loadIcon(pm) ?: res.getDrawable(android.R.drawable.sym_def_app_icon, activity.theme)
                )
            }
        )
        val entries = loadEntries()

        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        // 头部：复用原生 layout_disabled_app_user_blocked_header（"您已排除的应用 + 管理 ⚙"）
        val header = activity.layoutInflater.inflate(
            layoutId("layout_disabled_app_user_blocked_header"), root, false
        ) as ViewGroup
        var headerTitle: TextView? = null
        collectViews(header) { if (it is TextView && headerTitle == null) headerTitle = it }
        headerTitle?.text = if (zh()) "已强制代理的应用" else "Force proxied apps"
        root.addView(header)

        val emptyView = activity.layoutInflater.inflate(
            layoutId("layout_disabled_app_no_items"), root, false
        )
        root.addView(emptyView)

        fun renderRows() {
            while (root.childCount > 2) root.removeViewAt(root.childCount - 1)
            if (entries.isEmpty()) {
                emptyView.visibility = View.VISIBLE
                return
            }
            emptyView.visibility = View.GONE
            entries.forEach { entry ->
                val row = activity.layoutInflater.inflate(
                    layoutId("layout_disabled_app"), root, false
                ) as ViewGroup
                row.findViewById<ImageView>(id("applicationIcon")).setImageDrawable(entry.icon)
                row.findViewById<TextView>(id("applicationLabel")).text = entry.label
                row.findViewById<View>(id("applicationRemoveBtn")).setOnClickListener {
                    entries.remove(entry)
                    saveForceProxyPackages(activity, entries.map { it.pkg }.toSet())
                    renderRows()
                }
                root.addView(row)
            }
        }

        header.findViewById<View>(id("manageBlockedApps"))?.setOnClickListener {
            showAppPickerDialog(activity) {
                entries.clear()
                entries.addAll(loadEntries())
                renderRows()
            }
        }
        renderRows()

        val dialog = AlertDialog.Builder(
            activity, res.getIdentifier("CloudflareAlertDialogStyle", "style", pkgName)
        )
            .setTitle(if (zh()) "强制代理列表" else "Force proxy list")
            .setView(root)
            .setNegativeButton(if (zh()) "完成" else "Done", null)
            .create()
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (activity.resources.displayMetrics.heightPixels * 0.8).toInt()
        )
    }

    /** 应用选择器（照抄 ApplicationsListDialogFragment：原生对话框 + 图标 + 多选复选框 + 保存/取消） */
    private fun showAppPickerDialog(activity: Activity, onSaved: () -> Unit) {
        val res = activity.resources
        val pkgName = activity.packageName
        fun layoutId(name: String) = res.getIdentifier(name, "layout", pkgName)
        fun id(name: String) = res.getIdentifier(name, "id", pkgName)
        val pm = activity.packageManager

        val selected = currentForceList(activity).toMutableSet()
        val apps = pm.getInstalledApplications(0)
            .filter {
                pm.getLaunchIntentForPackage(it.packageName) != null &&
                    !it.packageName.startsWith("com.cloudflare.")
            }
            .map { AppEntry(it.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
            .sortedBy { it.label.lowercase() }

        // 照抄 fragment_dialog_applications_list（FrameLayout{RecyclerView, ProgressBar}）：
        // RecyclerView 需要 androidx 依赖，替换为外观一致的 ListView
        val root = activity.layoutInflater.inflate(layoutId("fragment_dialog_applications_list"), null, false) as ViewGroup
        val recycler = root.findViewById<View>(id("applicationsRecyclerView"))
        val recyclerIndex = root.indexOfChild(recycler)
        val recyclerLp = recycler.layoutParams
        root.removeView(recycler)
        val list = ListView(activity)
        root.addView(list, recyclerIndex, recyclerLp)
        root.findViewById<View>(id("progressBar"))?.visibility = View.GONE

        list.adapter = object : BaseAdapter() {
            override fun getCount(): Int = apps.size
            override fun getItem(position: Int): AppEntry = apps[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val entry = getItem(position)
                val row = (convertView as? ViewGroup)
                    ?: activity.layoutInflater.inflate(layoutId("layout_exclude_application_entry"), parent, false) as ViewGroup
                row.findViewById<ImageView>(id("applicationIcon")).setImageDrawable(entry.icon)
                row.findViewById<TextView>(id("applicationLabel")).text = entry.label
                val checkbox = row.findViewById<CheckBox>(id("applicationCheckbox"))
                checkbox.setOnCheckedChangeListener(null)
                checkbox.isChecked = entry.pkg in selected
                checkbox.setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(entry.pkg) else selected.remove(entry.pkg)
                }
                return row
            }
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val entry = apps[position]
            val checkState = entry.pkg in selected
            if (checkState) selected.remove(entry.pkg) else selected.add(entry.pkg)
            (list.adapter as BaseAdapter).notifyDataSetChanged()
        }

        val dialog = AlertDialog.Builder(
            activity, res.getIdentifier("CloudflareAlertDialogStyle", "style", pkgName)
        )
            .setTitle(if (zh()) "选择应用" else "Select apps")
            .setView(root)
            .setNegativeButton(if (zh()) "取消" else "Cancel", null)
            .setPositiveButton(if (zh()) "保存" else "Save") { _, _ ->
                saveForceProxyPackages(activity, selected)
                onSaved()
            }
            .create()
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (activity.resources.displayMetrics.heightPixels * 0.85).toInt()
        )
    }

    // ---------- 样式克隆 ----------

    private fun copyTextStyle(src: TextView, dst: TextView) {
        dst.setTextColor(src.textColors)
        // getTextSize() 返回像素，setTextSize(float) 默认按 sp 解释 —— 必须显式指定 px 单位，
        // 否则在高密度屏上字号会被放大 density 倍
        dst.setTextSize(TypedValue.COMPLEX_UNIT_PX, src.textSize)
        dst.typeface = src.typeface
        dst.gravity = src.gravity
        dst.includeFontPadding = src.includeFontPadding
        dst.letterSpacing = src.letterSpacing
        dst.ellipsize = src.ellipsize
        dst.minLines = 1
        dst.maxLines = src.maxLines
        dst.background = src.background
        dst.setPadding(src.paddingLeft, src.paddingTop, src.paddingRight, src.paddingBottom)
        dst.minimumHeight = src.minimumHeight
    }

    private fun copyLp(src: ViewGroup.LayoutParams?): ViewGroup.MarginLayoutParams {
        val out = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        if (src is ViewGroup.MarginLayoutParams) {
            out.width = src.width
            out.height = src.height
            out.setMargins(src.leftMargin, src.topMargin, src.rightMargin, src.bottomMargin)
        } else if (src != null) {
            out.width = src.width
            out.height = src.height
        }
        return out
    }
}
