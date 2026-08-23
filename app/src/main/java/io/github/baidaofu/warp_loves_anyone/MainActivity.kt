package io.github.baidaofu.warp_loves_anyone

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.highcapable.yukihookapi.hook.factory.prefs

class MainActivity : Activity() {

    private val userPackages = mutableSetOf<String>()
    private lateinit var adapter: ArrayAdapter<String>

    /** dp 转 px，适配不同屏幕密度 */
    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    /** 取当前主题下的颜色属性（亮/暗色自动适配） */
    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.data
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        userPackages.addAll(prefs().getStringSet("force_proxy_packages", emptySet()))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 16.dp(), 16.dp(), 16.dp())
        }

        // 表单区：包在 ScrollView 里，任何屏幕尺寸/字体缩放下都不会被裁掉
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        form.addView(TextView(this).apply {
            text = "WARP 强制代理应用"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })

        form.addView(TextView(this).apply {
            text = "在此添加需要强制走 WARP 代理的应用包名（如 com.google.android.youtube）。" +
                "修改后请断开并重新连接 WARP 以生效。点击列表项可移除。"
            textSize = 13f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            setPadding(0, 8.dp(), 0, 16.dp())
        })

        val input = EditText(this).apply {
            hint = "例如 com.google.android.apps.gmail"
            setSingleLine(true)
        }
        form.addView(
            input,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val addButton = Button(this).apply { text = "添加包名" }
        form.addView(
            addButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 16.dp() }
        )

        val formScroll = ScrollView(this).apply {
            // 权重 1：占满除列表外的空间，内容超高时内部滚动
            isFillViewport = true
        }
        formScroll.addView(form)
        root.addView(
            formScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        )

        // 关键：传入 ArrayList（可变列表），否则 ArrayAdapter.clear() 会抛只读异常
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList(userPackages))
        val listView = ListView(this).apply {
            adapter = this@MainActivity.adapter
        }
        root.addView(
            listView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 280.dp())
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
    }

    private fun hideKeyboard(view: android.view.View) {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun saveAndRefresh() {
        prefs().putStringSet("force_proxy_packages", userPackages)
        adapter.clear()
        adapter.addAll(userPackages)
        adapter.notifyDataSetChanged()
    }
}
