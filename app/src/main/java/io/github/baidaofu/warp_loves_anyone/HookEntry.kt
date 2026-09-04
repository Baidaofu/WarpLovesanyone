package io.github.baidaofu.warp_loves_anyone

import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * libxposed API 102 模块入口。
 *
 * 功能一（强制 WARP 代理）：在 WARP 客户端进程内拦截
 * [android.net.VpnService.Builder.addDisallowedApplication]，阻止用户手动添加的
 * 包名被加入 VPN 的"排除应用"列表，使其流量继续走 WARP 隧道。
 *
 * 功能二（暗色模式跟随系统）：Cloudflare 两个 App（1.1.1.1 / One Agent）自带暗色开关
 * 但从不跟随系统设置。其内部实现为：App.onCreate 读取 SharedPreferences 键
 * "dark_mode"（布尔，位于 `<包名>_preferences`），再调用
 * `AppCompatDelegate.setDefaultNightMode(1/2)`（混淆后为唯一的 static void (int)）。
 * 本模块做三件事：
 *  1. contains("dark_mode") 恒真 —— 用户从未动过开关时 App 会读默认值 false 强制浅色；
 *  2. getBoolean("dark_mode") 返回系统当前深色状态（Resources.getSystem().uiMode）；
 *  3. setDefaultNightMode 被调用时强制改为 MODE_NIGHT_FOLLOW_SYSTEM，
 *     让 AppCompat 原生跟随系统（含运行中实时切换），App 内开关由此由系统代管。
 *
 * 配置读取采用框架远程偏好（Remote Preferences）：模块 UI 侧经 libxposed Service 写入，
 * 本类在目标进程内经 [getRemotePreferences] 只读读取。
 */
class HookEntry : XposedModule() {

    companion object {
        private const val TAG = "WarpLovesAnyone"

        /** 远程偏好分组名，需与 MainActivity 保持一致 */
        const val PREFS_GROUP = "config"
        const val KEY_FORCE_PROXY_PACKAGES = "force_proxy_packages"

        /** Cloudflare App 内暗色模式开关的 SharedPreferences 键 */
        private const val KEY_APP_DARK_MODE = "dark_mode"

        /** androidx AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM（编译期常量已被内联，直接用字面值） */
        private const val MODE_NIGHT_FOLLOW_SYSTEM = -1

        private const val VPN_HOOK_ID = "force_proxy"
        private const val DARK_MODE_CONTAINS_HOOK_ID = "dark_mode_contains"
        private const val DARK_MODE_READ_HOOK_ID = "dark_mode_read"
        private const val DARK_MODE_APPLY_HOOK_ID = "dark_mode_apply"

        /** 需要挂载的 App（Cloudflare VPN 客户端）
         *
         * 注：WireGuard (com.wireguard.android) 默认全量路由、无服务端强制排除，
         * 且其排除列表完全由用户手动配置，故无需（也不应）适配。
         */
        private val targetPackages = setOf(
            "com.cloudflare.onedotonedotonedotone", // Cloudflare 1.1.1.1 WARP
            "com.cloudflare.cloudflareoneagent"     // Cloudflare One Agent (Zero Trust)
        )
    }

    /** 各目标包的 classloader，热重载后重装钩子用（App 类加载器对象允许跨代持有） */
    private val packageClassLoaders = HashMap<String, ClassLoader>()

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
        if (param.packageName !in targetPackages) return
        if ((frameworkProperties and XposedInterface.PROP_CAP_REMOTE) == 0L) {
            log(Log.ERROR, TAG, "cannot read configuration: remote preferences unsupported")
            return
        }
        packageClassLoaders[param.packageName] = param.classLoader
        installVpnHook(param.packageName, param.classLoader)
        installDarkModeFollowSystemHooks(param.packageName, param.classLoader)
    }

    /** 功能一：拦截 addDisallowedApplication，阻断用户强制代理名单进 VPN 排除列表 */
    private fun installVpnHook(packageName: String, classLoader: ClassLoader) {
        try {
            val builderClass =
                Class.forName("android.net.VpnService\$Builder", false, classLoader)
            val target: Method =
                builderClass.getDeclaredMethod("addDisallowedApplication", String::class.java)
            hook(target)
                .setId(VPN_HOOK_ID)
                .intercept(forceProxyHooker(getRemotePreferences(PREFS_GROUP)))
            log(Log.INFO, TAG, "hooked addDisallowedApplication in $packageName")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "failed to hook addDisallowedApplication in $packageName", t)
        }
    }

    /** 功能二：让 App 暗色模式跟随系统 */
    private fun installDarkModeFollowSystemHooks(packageName: String, classLoader: ClassLoader) {
        // 1) contains("dark_mode") 恒真：App 的 PreferencesDelegate 读值前先查 contains，
        //    键不存在时直接用默认值 false 强制浅色，因此必须放行到读取路径
        try {
            val spi = Class.forName("android.app.SharedPreferencesImpl", false, classLoader)
            val contains = spi.getDeclaredMethod("contains", String::class.java)
            hook(contains)
                .setId(DARK_MODE_CONTAINS_HOOK_ID)
                .intercept { chain ->
                    if (chain.getArg(0) == KEY_APP_DARK_MODE) true else chain.proceed()
                }
            log(Log.INFO, TAG, "hooked SharedPreferences.contains in $packageName")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "failed to hook SharedPreferences.contains in $packageName", t)
        }

        // 2) 读取替换：dark_mode 的读值 = 系统当前深色状态
        try {
            val spi = Class.forName("android.app.SharedPreferencesImpl", false, classLoader)
            val getBoolean = spi.getDeclaredMethod("getBoolean", String::class.java, java.lang.Boolean.TYPE)
            hook(getBoolean)
                .setId(DARK_MODE_READ_HOOK_ID)
                .intercept { chain ->
                    if (chain.getArg(0) == KEY_APP_DARK_MODE) isSystemDarkMode() else chain.proceed()
                }
            log(Log.INFO, TAG, "hooked dark mode reads in $packageName")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "failed to hook dark mode reads in $packageName", t)
        }

        // 3) 应用替换：setDefaultNightMode（名称被混淆，运行期按"唯一 static void (int)"签名解析）
        //    强制传 MODE_NIGHT_FOLLOW_SYSTEM，交给 AppCompat 原生跟随系统
        try {
            val delegate =
                Class.forName("androidx.appcompat.app.AppCompatDelegate", false, classLoader)
            val candidates = delegate.declaredMethods.filter {
                Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                    it.parameterTypes.size == 1 && it.parameterTypes[0] == java.lang.Integer.TYPE
            }
            if (candidates.size != 1) {
                log(
                    Log.WARN, TAG,
                    "AppCompatDelegate static (I)V methods=${candidates.size}, skip apply hook in $packageName"
                )
                return
            }
            val apply = candidates[0]
            hook(apply)
                .setId(DARK_MODE_APPLY_HOOK_ID)
                .intercept { chain ->
                    log(Log.INFO, TAG, "night mode request ${chain.getArg(0)} -> FOLLOW_SYSTEM")
                    chain.proceed(arrayOf(MODE_NIGHT_FOLLOW_SYSTEM))
                }
            log(Log.INFO, TAG, "hooked night mode apply (${apply.name}) in $packageName")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "failed to hook night mode apply in $packageName", t)
        }
    }

    /** 系统当前是否为深色模式 */
    private fun isSystemDarkMode(): Boolean {
        val uiMode = Resources.getSystem().getConfiguration().uiMode
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * addDisallowedApplication 拦截器：
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

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        // 本模块无自有线程 / 原生钩子，无需清理，直接允许热重载
        log(Log.INFO, TAG, "onHotReloading")
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        // 热重载后包生命周期回调不会重放：卸掉旧钩子并基于保留的 classloader 重装全部钩子
        param.oldHookHandles.forEach { it.unhook() }
        val loaders = HashMap(packageClassLoaders)
        packageClassLoaders.clear()
        loaders.forEach { (pkg, loader) ->
            installVpnHook(pkg, loader)
            installDarkModeFollowSystemHooks(pkg, loader)
        }
        log(Log.INFO, TAG, "onHotReloaded, re-installed hooks for ${loaders.size} package(s)")
    }
}
