# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug      # Debug APK
./gradlew assembleRelease    # Release APK (minified + resource-shrunk)
./gradlew build              # Full build (Kotlin + native C++)
./gradlew clean              # Clean build artifacts
```

**Requirements:** JDK 17, Android SDK API 35. NDK r27 is auto-downloaded by AGP.

There are no unit tests in this project.

## Architecture

HandsOffMyVPN is an **LSPosed/Xposed module** that prevents apps from detecting an active VPN connection. It operates on two layers simultaneously:

### Java Layer (YukiHookAPI)
`HookEntry.kt` is the `@InjectYukiHookWithXposed` entry point — KSP generates the `xposed_init` asset from this annotation. Six hook objects in `hook/` are registered in its `loadApp` block:

- `NetworkCapabilitiesHook` — spoofs `hasTransport(TRANSPORT_VPN)→false`, strips VPN transport flags
- `ConnectivityManagerHook` — intercepts `getNetworkCapabilities()` to remove VPN indicators
- `NetworkInterfaceHook` — hides VPN interfaces (tun0, wg0, etc.) from enumeration
- `LinkPropertiesHook` — replaces VPN interface names with WiFi names (e.g. tun0→wlan0)
- `ProxyHook` — spoofs proxy detection to `NO_PROXY`
- `SystemPropertyHook` — intercepts system property reads
- `NativeLoader` — loads `libhomvpn_native.so` into the hooked process (multi-strategy path resolution for LSPosed compatibility)

Each hook module is an object with a `register(scope: PackageParam)` function. Hooks follow the YukiHookAPI pattern: `method { ... }.hook { before { ... } }`.

### Native Layer (C++ + Dobby)
`app/src/main/cpp/homvpn_native.cpp` registers Dobby inline hooks on libc functions at `JNI_OnLoad`:
- `open`, `openat`, `fopen`, `read`, `pread64`, `close` — sanitize `/proc/self/maps`, `/proc/net/tcp`, `/proc/net/dev` to remove VPN-related entries
- `getifaddrs`, `if_nametoindex`, `ioctl(SIOCGIFCONF)` — hide tunnel interfaces at the native level

`fd_tracker.h` provides thread-safe FD tracking across hook boundaries. `sanitizer.cpp` contains the `/proc` file filtering logic. Dobby is a git submodule in `app/src/main/cpp/Dobby/`.

### UI Layer (Jetpack Compose + Material3)
`MainActivity.kt` hosts a 3-tab bottom navigation:
1. **StatusScreen** — module active/inactive status and build info
2. **AppsScreen** — lists enabled target apps from `res/values/arrays.xml` (`xposed_scope`), checks if installed
3. **LogScreen** — in-process detection event log from `DetectionLog` (thread-safe ring buffer, max 500 entries)

### Scope Configuration
Target app packages are listed in `res/values/arrays.xml` under the `xposed_scope` array. This is what LSPosed Manager reads to determine which apps the module is injected into.

## Key Dependencies

- **YukiHookAPI 1.2.0** — Java-layer hooking runtime + KSP codegen for Xposed metadata
- **Xposed API 82** — compile-only; provided by LSPosed at runtime
- **Dobby** — inline hooking engine for native libc interception (submodule)
- **Compose BOM 2024.06.00** — declarative UI
- **JitPack + Xposed Maven** — configured in `settings.gradle.kts` for YukiHookAPI and Xposed API resolution
