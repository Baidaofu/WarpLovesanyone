package io.github.baidaofu.warp_loves_anyone

import android.app.Activity
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class MainActivity : Activity() {

    companion object {
        /** 远程偏好分组名，需与 HookEntry 保持一致 */
        private const val PREFS_GROUP = "config"
        private const val KEY_FORCE_PROXY_PACKAGES = "force_proxy_packages"

        /** 旧版本 (YukiHookAPI) 遗留偏好文件名（<包名>_preferences），用于升级后一次性迁移 */
        private const val LEGACY_PREFS_NAME = "io.github.baidaofu.warp_loves_anyone_preferences"

        /** 进程级持有已连接的 Xposed 服务，避免 Activity 重建后丢失绑定 */
        @Volatile
        private var boundService: XposedService? = null
    }

    private val userPackages = mutableSetOf<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var statusView: TextView

    /** 框架远程偏好（模块 app 侧可写）；服务未连接 / 不支持远程能力时回退本地偏好 */
    private var remotePrefs: SharedPreferences? = null

    /** dp 转 px，适配不同屏幕密度 */
    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    /** 取当前主题下的颜色状态列表（亮/暗色自动适配） */
    private fun themeColorStateList(attr: Int): android.content.res.ColorStateList {
        val value = TypedValue()
        if (theme.resolveAttribute(attr, value, true)) {
            // 资源引用 → 读 ColorStateList；直接值 → 包装成单色
            return if (value.resourceId != 0) getColorStateList(value.resourceId)
            else android.content.res.ColorStateList.valueOf(value.data)
        }
        return android.content.res.ColorStateList.valueOf(0xFF808080.toInt())
    }

    private fun localPrefs(): SharedPreferences = getSharedPreferences(PREFS_GROUP, MODE_PRIVATE)

    /** 读取配置：优先框架远程偏好，其次本地偏好（含旧版本遗留文件） */
    private fun loadPackages(): Set<String> {
        remotePrefs?.getStringSet(KEY_FORCE_PROXY_PACKAGES, null)?.let { return HashSet(it) }
        for (name in arrayOf(PREFS_GROUP, LEGACY_PREFS_NAME)) {
            val stored = getSharedPreferences(name, MODE_PRIVATE)
                .getStringSet(KEY_FORCE_PROXY_PACKAGES, null)
            if (!stored.isNullOrEmpty()) return HashSet(stored)
        }
        return emptySet()
    }

    /** 一次性迁移：远程配置为空而本地（含旧版本 YukiHookAPI 文件）有数据时，推送到框架 */
    private fun migrateLocalToRemote(remote: SharedPreferences) {
        if (!remote.getStringSet(KEY_FORCE_PROXY_PACKAGES, null).isNullOrEmpty()) return
        for (name in arrayOf(PREFS_GROUP, LEGACY_PREFS_NAME)) {
            val stored = getSharedPreferences(name, MODE_PRIVATE)
                .getStringSet(KEY_FORCE_PROXY_PACKAGES, null)
            if (!stored.isNullOrEmpty()) {
                remote.edit().putStringSet(KEY_FORCE_PROXY_PACKAGES, stored).apply()
                return
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        // Android 15+ 强制 edge-to-edge：必须手动让出系统栏（状态栏/导航栏），否则内容会被遮挡
        val basePadding = 16.dp()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(
                    bars.left + basePadding,
                    bars.top + basePadding,
                    bars.right + basePadding,
                    bars.bottom + basePadding
                )
                WindowInsets.CONSUMED
            }
        } else {
            root.setPadding(basePadding, basePadding, basePadding, basePadding)
        }

        root.addView(TextView(this).apply {
            text = "WARP 强制代理应用"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })

        root.addView(TextView(this).apply {
            text = "在此添加需要强制走 WARP 代理的应用包名（如 com.google.android.youtube）。" +
                "修改后请断开并重新连接 WARP 以生效。点击列表项可移除。"
            textSize = 13f
            setTextColor(themeColorStateList(android.R.attr.textColorSecondary))
            setPadding(0, 8.dp(), 0, 16.dp())
        })

        // Xposed 框架服务连接状态
        statusView = TextView(this).apply {
            text = getString(R.string.status_waiting)
            textSize = 12f
            setTextColor(themeColorStateList(android.R.attr.textColorTertiary))
            setPadding(0, 0, 0, 16.dp())
        }
        root.addView(statusView)

        val input = EditText(this).apply {
            hint = "例如 com.google.android.apps.gmail"
            setSingleLine(true)
        }
        root.addView(
            input,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val addButton = Button(this).apply { text = "添加包名" }
        root.addView(
            addButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 16.dp() }
        )

        userPackages.addAll(loadPackages())

        // 关键：传入 ArrayList（可变列表），否则 ArrayAdapter.clear() 会抛只读异常
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList(userPackages))
        val listView = ListView(this).apply {
            adapter = this@MainActivity.adapter
        }
        // 列表紧贴按钮下方，占满剩余空间（无空白间隔）
        root.addView(
            listView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        )

        listView.setOnItemClickListener { _, _, position, _ ->
            val pkg = adapter.getItem(position) ?: return@setOnItemClickListener
            userPackages.remove(pkg)
            saveAndRefresh()
            Toast.makeText(this, "已移除 $pkg（需重连 WARP 生效）", Toast.LENGTH_SHORT).show()
        }

        addButton.setOnClickListener {
            val pkg = input.text.toString().trim()
            when {
                pkg.isEmpty() -> Toast.makeText(this, "请输入包名", Toast.LENGTH_SHORT).show()
                userPackages.add(pkg) -> {
                    saveAndRefresh()
                    input.text.clear()
                    input.clearFocus()
                    hideKeyboard(input)
                    Toast.makeText(this, "已添加 $pkg", Toast.LENGTH_SHORT).show()
                }
                else -> Toast.makeText(this, "该包名已在列表中", Toast.LENGTH_SHORT).show()
            }
        }

        setContentView(root)

        // 绑定 libxposed 服务（框架在模块 app 内预置的 ContentProvider 会回传 binder）
        val service = boundService
        if (service != null) {
            onServiceBound(service)
        } else {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    if (boundService == null) boundService = service
                    runOnUiThread { onServiceBound(service) }
                }

                override fun onServiceDied(service: XposedService) {
                    boundService = null
                    runOnUiThread { onServiceDied() }
                }
            })
        }
    }

    private fun onServiceBound(service: XposedService) {
        remotePrefs = try {
            service.getRemotePreferences(PREFS_GROUP).also { migrateLocalToRemote(it) }
        } catch (t: Throwable) {
            null
        }
        userPackages.clear()
        userPackages.addAll(loadPackages())
        refreshList()
        statusView.text = if (remotePrefs != null)
            getString(R.string.status_connected, service.frameworkName)
        else
            getString(R.string.status_no_remote)
    }

    private fun onServiceDied() {
        remotePrefs = null
        statusView.text = getString(R.string.status_waiting)
    }

    private fun hideKeyboard(view: android.view.View) {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun refreshList() {
        adapter.clear()
        adapter.addAll(userPackages)
        adapter.notifyDataSetChanged()
    }

    private fun saveAndRefresh() {
        // 始终写本地镜像；框架远程偏好可用时同步写入（目标进程经 HookEntry 只读读取）
        localPrefs().edit().putStringSet(KEY_FORCE_PROXY_PACKAGES, userPackages).apply()
        try {
            remotePrefs?.edit()?.putStringSet(KEY_FORCE_PROXY_PACKAGES, userPackages)?.apply()
        } catch (t: Throwable) {
            // 服务已失效等场景：忽略，本地镜像仍保留数据
        }
        refreshList()
    }
}
