package io.github.baidaofu.warp_loves_anyone

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Method

/**
 * libxposed API 102 模块入口。
 *
 * 在 WARP 客户端进程内拦截 [android.net.VpnService.Builder.addDisallowedApplication]，
 * 阻止用户手动添加的包名被加入 VPN 的"排除应用"列表，使其流量继续走 WARP 隧道。
 *
 * 配置读取采用框架远程偏好（Remote Preferences）：
 * 模块 UI 侧经 libxposed Service（XposedService.getRemotePreferences）写入，
 * 本类在目标进程内经 [getRemotePreferences] 只读读取，彻底取代即将废弃的 New XSharedPreferences。
 */
class HookEntry : XposedModule() {

    companion object {
        private const val TAG = "WarpLovesAnyone"

        /** 远程偏好分组名，需与 MainActivity 保持一致 */
        const val PREFS_GROUP = "config"
        const val KEY_FORCE_PROXY_PACKAGES = "force_proxy_packages"

        /** 需要挂载的 VPN 客户端（在其进程内拦截 addDisallowedApplication）
         *
         * 注：WireGuard (com.wireguard.android) 默认全量路由、无服务端强制排除，
         * 且其排除列表完全由用户手动配置，故无需（也不应）适配。
         */
        private val vpnPackages = setOf(
            "com.cloudflare.onedotonedotonedotone", // Cloudflare 1.1.1.1 WARP
            "com.cloudflare.cloudflareoneagent"     // Cloudflare One Agent (Zero Trust)
        )
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(
            Log.INFO, TAG,
            "loaded into ${param.processName} " +
                "(${frameworkName} $frameworkVersion, API $apiVersion)"
        )
        if ((frameworkProperties and XposedInterface.PROP_CAP_REMOTE) == 0L) {
            log(Log.WARN, TAG, "framework lacks remote preference support, configuration unavailable")
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName !in vpnPackages) return
        if ((frameworkProperties and XposedInterface.PROP_CAP_REMOTE) == 0L) {
            log(Log.ERROR, TAG, "cannot read configuration: remote preferences unsupported")
            return
        }
        try {
            val builderClass =
                Class.forName("android.net.VpnService\$Builder", false, param.classLoader)
            val target: Method =
                builderClass.getDeclaredMethod("addDisallowedApplication", String::class.java)
            hook(target)
                .setId("force_proxy") // 固定 id：后续可用新 Hooker 原子替换（API 102）
                .intercept(forceProxyHooker(getRemotePreferences(PREFS_GROUP)))
            log(Log.INFO, TAG, "hooked addDisallowedApplication in ${param.packageName}")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "failed to hook in ${param.packageName}", t)
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        // 本模块无自有线程 / 原生钩子，无需清理，直接允许热重载
        log(Log.INFO, TAG, "onHotReloading")
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        // 热重载后包生命周期回调不会重放：
        // 用新一代代码构造的 Hooker 原子替换旧代钩子（保持原可执行方法、优先级与 id）
        val hooker = forceProxyHooker(getRemotePreferences(PREFS_GROUP))
        param.oldHookHandles.forEach { it.replaceHook(hooker) }
        log(Log.INFO, TAG, "onHotReloaded, replaced ${param.oldHookHandles.size} hook(s)")
    }

    /**
     * 构造 addDisallowedApplication 的拦截器：
     * 包名在用户强制代理列表中 → 跳过原方法直接返回 Builder 本身
     * （原方法语义为 return this，保持链式调用兼容），该包即不会被加入排除列表；
     * 否则继续原始调用链。
     */
    private fun forceProxyHooker(prefs: SharedPreferences): XposedInterface.Hooker =
        XposedInterface.Hooker { chain ->
            val pkg = chain.getArg(0) as? String
            val forceProxy = prefs.getStringSet(KEY_FORCE_PROXY_PACKAGES, emptySet()).orEmpty()
            if (pkg != null && pkg in forceProxy) chain.thisObject else chain.proceed()
        }
}
