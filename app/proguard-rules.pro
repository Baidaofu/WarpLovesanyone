# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# 模块自身的所有类（HookEntry / 生成的入口 / MainActivity）：
# - HookEntry 由 assets/xposed_init 与 resources/META-INF/yukihookapi_init
#   按类名字符串反射加载，必须保留原名
-keep class io.github.baidaofu.warp_loves_anyone.** { *; }

# xposed-api 为 compileOnly（运行时由 Xposed 框架提供），忽略缺失类警告
-dontwarn de.robv.android.xposed.**