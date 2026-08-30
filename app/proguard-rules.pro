# libxposed 模块入口：框架通过 META-INF/xposed/java_init.list 中的类名反射实例化。
# 允许混淆重命名（-adaptresourcefilecontents 会同步改写 java_init.list 里的类名），
# 但必须保留 public 无参构造函数。
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# libxposed api / service 为 compileOnly（运行时由框架提供），忽略缺失类与注解警告
-dontwarn io.github.libxposed.annotation.**
