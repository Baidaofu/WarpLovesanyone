package io.github.baidaofu.warp_loves_anyone

import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.type.java.StringClass
import com.highcapable.yukihookapi.hook.type.java.StringType
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit

@InjectYukiHookWithXposed
object HookEntry : IYukiHookXposedInit {

    /** 需要挂载的 VPN 客户端（在其进程内拦截 addDisallowedApplication）
     *
     * 注：WireGuard (com.wireguard.android) 默认全量路由、无服务端强制排除，
     * 且其排除列表完全由用户手动配置，故无需（也不应）适配。
     */
    private val vpnPackages = setOf(
        "com.cloudflare.onedotonedotonedotone", // Cloudflare 1.1.1.1 WARP
        "com.cloudflare.cloudflareoneagent"     // Cloudflare One Agent (Zero Trust)
    )

    override fun onInit() = configs {
        isDebug = false
    }

    override fun onHook() = YukiHookAPI.encase {
        vpnPackages.forEach { pkg ->
            loadApp(pkg) {
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
}
