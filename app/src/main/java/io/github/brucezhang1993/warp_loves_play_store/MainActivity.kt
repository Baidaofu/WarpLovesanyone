package io.github.brucezhang1993.warp_loves_play_store

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.highcapable.yukihookapi.hook.factory.prefs

class MainActivity : Activity() {

    private val userPackages = mutableSetOf<String>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        userPackages.addAll(prefs().getStringSet("force_proxy_packages", emptySet()))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "WARP 强制代理应用"
            textSize = 22f
        })

        root.addView(TextView(this).apply {
            text = "内置强制代理（始终生效）："
            textSize = 14f
            setPadding(0, 16, 0, 4)
        })

        root.addView(TextView(this).apply {
            text = "com.android.vending\ncom.google.android.youtube\ncom.google.android.apps.photos"
            textSize = 13f
        })

        root.addView(TextView(this).apply {
            text = "自定义包名（点击列表项移除）："
            textSize = 14f
            setPadding(0, 16, 0, 4)
        })

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
                .apply { topMargin = 16 }
        )

        // 关键：传入 ArrayList（可变列表），否则 ArrayAdapter.clear() 会抛只读异常
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList(userPackages))
        val listView = ListView(this).apply { adapter = this@MainActivity.adapter }
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
                    Toast.makeText(this, "已添加 $pkg", Toast.LENGTH_SHORT).show()
                }
                else -> Toast.makeText(this, "该包名已在列表中", Toast.LENGTH_SHORT).show()
            }
        }

        setContentView(root)
    }

    private fun saveAndRefresh() {
        prefs().putStringSet("force_proxy_packages", userPackages)
        adapter.clear()
        adapter.addAll(userPackages)
        adapter.notifyDataSetChanged()
    }
}
