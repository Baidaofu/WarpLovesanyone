package io.github.brucezhang1993.warp_loves_play_store

import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.type.java.StringClass
import com.highcapable.yukihookapi.hook.type.java.StringType
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit

@InjectYukiHookWithXposed
object HookEntry : IYukiHookXposedInit {

    override fun onInit() = configs {
        isDebug = false
    }

    override fun onHook() = YukiHookAPI.encase {
        loadApp("com.cloudflare.onedotonedotonedotone") {
            val userPrefs = prefs
            "android.net.VpnService\$Builder".toClass().method {
                name = "addDisallowedApplication"
                param(StringClass)
                returnType = "android.net.VpnService\$Builder".toClass()
            }.hook {
                before {
                    val param1 = args().first().string()
                    // 仅用户通过面板手动添加的包名（跨进程读取模块 SharedPreferences）
                    val userForceProxy = userPrefs.getStringSet("force_proxy_packages", emptySet())
                    if (param1 in userForceProxy) {
                        result = instanceOrNull
                        return@before
                    }
                    result = callOriginal()
                }
            }
        }
    }
}
