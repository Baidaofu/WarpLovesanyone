# Warp Loves Anyone

An LSPosed/Xposed module that forces **any app you choose** through the Cloudflare 1.1.1.1 (WARP) VPN tunnel.

The 1.1.1.1 app adds some packages to its VPN "disallowed applications" list (e.g. Play Store), so their traffic bypasses WARP. This module hooks `VpnService.Builder.addDisallowedApplication` and blocks the packages you select from being added, making their traffic go through WARP.

## Features

- **UI panel** (launcher icon) to manually add/remove package names
- No built-in package list — only the packages you add are forced through WARP
- Reads the list cross-process via LSPosed New XSharedPreferences
- Supports both Cloudflare VPN clients: **1.1.1.1** (`com.cloudflare.onedotonedotonedotone`) and **Cloudflare One Agent** (`com.cloudflare.cloudflareoneagent`)
- GitHub Actions builds an **unsigned, minified release APK** automatically on push

> Note: WireGuard is intentionally **not** supported — it routes all apps by default and has no server-side forced exclusion list, so there is nothing to intercept.

## Usage

1. Install the module APK and enable it in LSPosed
2. Enable the module scope for the Cloudflare VPN apps you use:
   - `com.cloudflare.onedotonedotonedotone` (1.1.1.1)
   - `com.cloudflare.cloudflareoneagent` (Cloudflare One Agent)
3. Open the **Warp Loves Anyone** app from the launcher
4. Add the package names you want to force through WARP (e.g. `com.android.vending`)
5. Reconnect the VPN (disconnect and connect) to apply

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
