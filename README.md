# Warp Loves Anyone

An Xposed module (based on **libxposed API 102**) that forces **any app you choose** through the Cloudflare 1.1.1.1 (WARP) VPN tunnel.

The 1.1.1.1 app adds some packages to its VPN "disallowed applications" list (e.g. Play Store), so their traffic bypasses WARP. This module hooks `VpnService.Builder.addDisallowedApplication` and blocks the packages you select from being added, making their traffic go through WARP.

## Features

- **UI panel** (launcher icon) to manually add/remove package names
- No built-in package list — only the packages you add are forced through WARP
- Migrated to **libxposed API 102** (`META-INF/xposed/module.prop` + `java_init.list` + `scope.list`, interceptor-chain hook model, hook ids, hot reload support)
- Configuration is stored in the framework's Remote Preferences (`getRemotePreferences`) instead of the deprecated New XSharedPreferences — this also clears the deprecation warning shown on the module page for legacy modules (New XSharedPreferences is scheduled for removal in 2.3.0)
- **Dark mode of the Cloudflare apps now follows the system setting** (the apps' built-in toggle is overridden by the module on behalf of the system)
- Existing package lists are migrated automatically (pushed to Remote Preferences) on first launch of the module app
- Supports both Cloudflare VPN clients: **1.1.1.1** (`com.cloudflare.onedotonedotonedotone`) and **Cloudflare One Agent** (`com.cloudflare.cloudflareoneagent`)
- GitHub Actions builds an **unsigned, minified release APK** automatically on push

## Requirements

- A libxposed-compatible framework implementing **API 102** (e.g. LSPosed 2.x+)

## Usage

1. Install the module APK and enable it in your Xposed framework (scope is static: the two Cloudflare VPN apps)
2. Open the **Warp Loves Anyone** app from the launcher
3. Add the package names you want to force through WARP (e.g. `com.android.vending`)
4. Reconnect the VPN (disconnect and connect) to apply

> Note: when upgrading from v2.4 (YukiHookAPI-based), open the module app once so the old package list gets migrated to Remote Preferences.

## Build

```bash
./gradlew assembleRelease
# 未签名 APK: app/build/outputs/apk/release/app-release-unsigned.apk
```

CI 产出的 APK 未签名，安装前需要自行签名（例如使用 Android SDK 的 apksigner）：

```bash
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out app-release.apk app-release-unsigned.apk
```

## License

Same as the upstream project: [WarpLovesPlayStore](https://github.com/BruceZhang1993/WarpLovesPlayStore) (MIT).
