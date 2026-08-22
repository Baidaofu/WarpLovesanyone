# Warp Loves Anyone

An LSPosed/Xposed module that forces **any app you choose** through the Cloudflare 1.1.1.1 (WARP) VPN tunnel.

The 1.1.1.1 app adds some packages to its VPN "disallowed applications" list (e.g. Play Store), so their traffic bypasses WARP. This module hooks `VpnService.Builder.addDisallowedApplication` and blocks the packages you select from being added, making their traffic go through WARP.

## Features

- **UI panel** (launcher icon) to manually add/remove package names
- No built-in package list — only the packages you add are forced through WARP
- Reads the list cross-process via LSPosed New XSharedPreferences
- GitHub Actions builds the debug APK automatically on push

## Usage

1. Install the module APK and enable it in LSPosed
2. Scope it to `com.cloudflare.onedotonedotonedotone` (1.1.1.1)
3. Open the **Warp Loves Anyone** app from the launcher
4. Add the package names you want to force through WARP (e.g. `com.android.vending`)
5. Reconnect WARP (disconnect and connect) to apply

## Build

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## License

Same as the upstream project: [WarpLovesPlayStore](https://github.com/BruceZhang1993/WarpLovesPlayStore) (MIT).
