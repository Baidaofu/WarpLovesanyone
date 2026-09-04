# Warp Loves Anyone

[English](README.md)

一个 Xposed 模块（libxposed API 102）：让任意 App 的流量强制走 Cloudflare WARP 隧道，并让 Cloudflare App 的暗色模式跟随系统设置。

## 功能

- 强制任意包名走 WARP：拦截 `VpnService.Builder.addDisallowedApplication`，阻止所选包名进入 VPN 的「排除应用」列表
- 1.1.1.1 / Cloudflare One Agent 的暗色模式跟随系统设置，App 内开关由系统代管
- 界面管理包名列表，配置存于框架 Remote Preferences
- 作用域：`com.cloudflare.onedotonedotonedotone`、`com.cloudflare.cloudflareoneagent`

## 要求

- 支持 libxposed API 102 的框架（如 LSPosed 2.x+）

## 使用

1. 在 LSPosed 中启用模块（作用域固定为上述两个 App）
2. 打开模块界面，添加要强制走 WARP 的包名（如 `com.android.vending`）
3. 断开并重连 VPN 生效

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
