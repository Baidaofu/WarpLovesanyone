# Warp Loves Anyone

[English](README.md)

一个 Xposed 模块（libxposed API 102）：让任意 App 的流量强制走 Cloudflare WARP 隧道，并让 Cloudflare App 的暗色模式跟随系统设置。

## 功能

- 强制任意包名走 WARP：阻止所选包名进入 VPN 的「排除应用」列表
- 在 App 的 设置 → 高级 → 连接选项 内注入「强制代理列表」入口（原生风格列表 + 图标多选选择器）
- 在 设置 → 高级 内注入「跟随系统主题」开关；开启后 App 的深色主题由系统代管
- 作用域：`com.cloudflare.onedotonedotonedotone`、`com.cloudflare.cloudflareoneagent`

## 要求

- 支持 libxposed API 102 的框架（如 LSPosed 2.x+）

## 使用

1. 在 LSPosed 中启用模块（作用域固定为上述两个 App）
2. 在 App 内管理强制代理列表：设置 → 高级 → 连接选项
3. 修改列表后断开并重连 VPN 生效

## 构建

```bash
./gradlew assembleRelease
```

CI 产出未签名 APK，安装前自行签名，例如：

```bash
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out app-release.apk app-release-unsigned.apk
```

## 许可

同上游项目：[WarpLovesPlayStore](https://github.com/BruceZhang1993/WarpLovesPlayStore)（MIT）。
