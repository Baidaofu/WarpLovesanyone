package io.github.baidaofu.warp_loves_anyone

import android.app.Application
import android.app.Instrumentation
import android.content.Context
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
 * 功能一（强制 WARP 代理）：拦截 [android.net.VpnService.Builder.addDisallowedApplication]，
 * 阻止用户在"连接选项"里配置的强制代理包名进入 VPN 排除列表。
 * 配置存于目标 App 私有的 SharedPreferences（文件 "warp_loves_anyone"），
 * 由注入到 App 设置页里的界面读写（单进程 App，读写即时可见）。
 *
 * 功能二（暗色模式跟随系统）：可由注入的"跟随系统主题"开关控制（默认开启）：
 *  - contains/getBoolean("dark_mode") 按系统状态应答；
 *  - setDefaultNightMode 强制 MODE_NIGHT_FOLLOW_SYSTEM，AppCompat 原生跟随系统；
 *  - 开关关闭时不干预，App 自带的手动深色开关恢复生效。
 */
class HookEntry : XposedModule() {

    companion object {
        private const val TAG = "WarpLovesAnyone"

        /** 目标 App 私有配置文件（注入 UI 与 Hook 共用，单进程） */
        const val CONFIG_PREFS = "warp_loves_anyone"
        const val KEY_FORCE_PROXY_PACKAGES = "force_proxy_packages"
        const val KEY_FOLLOW_SYSTEM_THEME = "follow_system_theme"

        /** Cloudflare App 内暗色模式开关的 SharedPreferences 键 */
        private const val KEY_APP_DARK_MODE = "dark_mode"

        /** androidx AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM（编译期常量已被内联，直接用字面值） */
        private const val MODE_NIGHT_FOLLOW_SYSTEM = -1

        private const val VPN_HOOK_ID = "force_proxy"
        private const val DARK_MODE_CONTAINS_HOOK_ID = "dark_mode_contains"
        private const val DARK_MODE_READ_HOOK_ID = "dark_mode_read"
        private const val DARK_MODE_APPLY_HOOK_ID = "dark_mode_apply"
        private const val CONTEXT_HOOK_ID = "app_context"

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

    private val packageClassLoaders = HashMap<String, ClassLoader>()
    private val nightModeMethods = HashMap<String, Method>()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(
            Log.INFO, TAG,
            "loaded into ${param.processName} " +
                "(${frameworkName} $frameworkVersion, API $apiVersion)"
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName !in targetPackages) return
        packageClassLoaders[param.packageName] = param.classLoader
        installContextHook()
        installVpnHook(param.packageName, param.classLoader)
        installDarkModeFollowSystemHooks(param.packageName, param.classLoader)
        AppUiInjector.install(this, param.packageName, param.classLoader)
    }

    /** 捕获 Application 上下文，供 Hook 与注入 UI 读写 App 私有配置 */
    private fun installContextHook() {
        try {
            val m = Instrumentation::class.java.getDeclaredMethod(
                "callApplicationOnCreate", Application::class.java
            )
            hook(m).setId(CONTEXT_HOOK_ID).intercept { chain ->
                (chain.getArg(0) as? Application)?.let { AppUiInjector.appContext = it }
                chain.proceed()
            }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "failed to hook app context capture", t)
        }
    }

    /** 功能一：拦截 addDisallowedApplication，阻断强制代理名单进 VPN 排除列表 */
    private fun installVpnHook(packageName: String, classLoader: ClassLoader) {
        try {
            val builderClass =
                Class.forName("android.net.VpnService\$Builder", false, classLoader)
            val target: Method =
                builderClass.getDeclaredMethod("addDisallowedApplication", String::class.java)
            hook(target)
                .setId(VPN_HOOK_ID)
                .intercept { chain ->
                    val pkg = chain.getArg(0) as? String
                    val forceProxy = AppUiInjector.forceProxyPackages().orEmpty()
                    if (pkg != null && pkg in forceProxy) {
                        chain.thisObject // 原方法语义为 return this，保持链式调用兼容
                    } else {
                        chain.proceed()
                    }
                }
            log(Log.INFO, TAG, "hooked addDisallowedApplication in $packageName")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "failed to hook addDisallowedApplication in $packageName", t)
        }
    }

    /** 功能二：暗色模式跟随系统（受"跟随系统主题"开关控制） */
    private fun installDarkModeFollowSystemHooks(packageName: String, classLoader: ClassLoader) {
        // 1) contains("dark_mode") 恒真：App 的 PreferencesDelegate 读值前先查 contains，
        //    键不存在时直接用默认值 false 强制浅色，因此跟随模式下必须放行到读取路径
        try {
            val spi = Class.forName("android.app.SharedPreferencesImpl", false, classLoader)
            val contains = spi.getDeclaredMethod("contains", String::class.java)
            hook(contains)
                .setId(DARK_MODE_CONTAINS_HOOK_ID)
                .intercept { chain ->
                    if (chain.getArg(0) == KEY_APP_DARK_MODE && AppUiInjector.followSystemTheme()) {
                        true
                    } else {
                        chain.proceed()
                    }
                }
            log(Log.INFO, TAG, "hooked SharedPreferences.contains in $packageName")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "failed to hook SharedPreferences.contains in $packageName", t)
        }

        // 2) 读取替换：跟随模式下 dark_mode 的读值 = 系统当前深色状态
        try {
            val spi = Class.forName("android.app.SharedPreferencesImpl", false, classLoader)
            val getBoolean = spi.getDeclaredMethod("getBoolean", String::class.java, java.lang.Boolean.TYPE)
            hook(getBoolean)
                .setId(DARK_MODE_READ_HOOK_ID)
                .intercept { chain ->
                    if (chain.getArg(0) == KEY_APP_DARK_MODE && AppUiInjector.followSystemTheme()) {
                        isSystemDarkMode()
                    } else {
                        chain.proceed()
                    }
                }
            log(Log.INFO, TAG, "hooked dark mode reads in $packageName")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "failed to hook dark mode reads in $packageName", t)
        }

        // 3) 应用替换：setDefaultNightMode（名称被混淆，运行期按"唯一 static void (int)"签名解析）
        //    跟随模式下强制 MODE_NIGHT_FOLLOW_SYSTEM，交给 AppCompat 原生跟随系统
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
            nightModeMethods[packageName] = apply
            hook(apply)
                .setId(DARK_MODE_APPLY_HOOK_ID)
                .intercept { chain ->
                    if (AppUiInjector.followSystemTheme()) {
                        chain.proceed(arrayOf(MODE_NIGHT_FOLLOW_SYSTEM))
                    } else {
                        chain.proceed()
                    }
                }
            log(Log.INFO, TAG, "hooked night mode apply (${apply.name}) in $packageName")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "failed to hook night mode apply in $packageName", t)
        }
    }

    /** 系统当前是否为深色模式 */
    fun isSystemDarkMode(): Boolean {
        val uiMode = Resources.getSystem().getConfiguration().uiMode
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
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
        nightModeMethods.clear()
        loaders.forEach { (pkg, loader) ->
            installContextHook()
            installVpnHook(pkg, loader)
            installDarkModeFollowSystemHooks(pkg, loader)
            AppUiInjector.install(this, pkg, loader)
        }
        log(Log.INFO, TAG, "onHotReloaded, re-installed hooks for ${loaders.size} package(s)")
    }
}
