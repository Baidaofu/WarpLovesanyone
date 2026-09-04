# Warp Loves Anyone

[中文](README.zh-CN.md)

An Xposed module (libxposed API 102) that forces apps of your choice through the Cloudflare WARP tunnel, and makes the Cloudflare apps' dark mode follow the system setting.

## Features

- Force any package through WARP: blocks selected packages from being added to the VPN's disallowed applications list (`VpnService.Builder.addDisallowedApplication`)
- Dark mode of 1.1.1.1 / Cloudflare One Agent follows the system setting; the in-app toggle is managed by the system
- UI panel to manage the package list, stored in the framework's Remote Preferences
- Scope: `com.cloudflare.onedotonedotonedotone`, `com.cloudflare.cloudflareoneagent`

## Requirements

- A libxposed-compatible framework with API 102 support (e.g. LSPosed 2.x+)

## Usage

1. Enable the module in LSPosed (scope is fixed to the two Cloudflare apps)
2. Open the app and add package names to force through WARP (e.g. `com.android.vending`)
3. Reconnect the VPN to apply

## Build

```bash
./gradlew assembleRelease
```

CI produces an unsigned APK; sign it before installing, e.g.:

```bash
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out app-release.apk app-release-unsigned.apk
```

## License

Same as the upstream project: [WarpLovesPlayStore](https://github.com/BruceZhang1993/WarpLovesPlayStore) (MIT).
