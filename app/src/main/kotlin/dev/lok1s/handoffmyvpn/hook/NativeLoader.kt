package dev.lok1s.handoffmyvpn.hook

import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * # NativeLoader
 *
 * Responsible for loading `libhomvpn_native.so` into the target process
 * exactly once.  The native library's `JNI_OnLoad` function then installs
 * Dobby inline hooks on libc's `open`, `openat`, `fopen`, `read`, `pread64`,
 * `close`, `getifaddrs`, `if_nametoindex`, and `ioctl` to intercept and
 * sanitize reads from sensitive `/proc` files and hide VPN interfaces.
 *
 * ## Loading strategy (v2.2)
 *
 * In an Xposed module context, `System.loadLibrary("homvpn_native")` does not
 * work because the classloader is the module's, but the native library lives
 * in the **module APK's** lib directory — not the target app's.  We try:
 *
 * 1. Resolve the module APK path from `PackageParam.moduleAppFilePath`
 * 2. Try standard ABI subdirectories: `lib/arm64-v8a/`, `lib/armeabi-v7a/`
 * 3. Try APK-extracted lib path (without ABI): `lib/`
 * 4. Try `nativeLibraryDir` from ApplicationInfo
 * 5. Fall back to `System.loadLibrary()` (works if LSPosed extracts libs)
 */
object NativeLoader {

    private const val TAG = "HandsOffMyVPN/Native"
    private const val LIB_NAME = "libhomvpn_native.so"

    /** Guard: set to true after the first successful load. */
    private val loaded = AtomicBoolean(false)

    /**
     * Attempts to load the native payload into the current process.
     *
     * @param scope The current [PackageParam] from YukiHookAPI's `encase` block.
     *              Used to resolve the module APK path.
     */
    fun loadIntoProcess(scope: PackageParam) {
        if (loaded.getAndSet(true)) {
            LOGD("already loaded into this process, skipping")
            return
        }

        try {
            // Try multiple strategies to find the .so
            val paths = collectCandidatePaths(scope)
            LOGD("candidate paths: ${paths.joinToString(", ")}")

            for (path in paths) {
                try {
                    System.load(path)
                    LOGD("loaded native payload from: $path")
                    return
                } catch (e: UnsatisfiedLinkError) {
                    LOGD("path failed: $path (${e.message})")
                }
            }

            // Fallback: try standard loadLibrary (works if LSPosed extracts libs)
            System.loadLibrary("homvpn_native")
            LOGD("loaded native payload via System.loadLibrary")
        } catch (e: Throwable) {
            loaded.set(false)  // allow retry on next hook cycle
            LOGW("failed to load native payload: ${e.message}")
        }
    }

    /**
     * Collect all possible paths where the .so could exist.
     */
    private fun collectCandidatePaths(scope: PackageParam): List<String> {
        val candidates = mutableListOf<String>()
        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

        // Strategy 1: moduleAppFilePath-based resolution
        val modulePath = scope.moduleAppFilePath
        if (modulePath.isNotBlank()) {
            val moduleDir = File(modulePath).parentFile
            if (moduleDir != null) {
                // Try lib/<abi>/ subdirectories
                for (abi in abis) {
                    candidates.add(File(moduleDir, "lib/$abi/$LIB_NAME").absolutePath)
                }
                // Try flat lib/ directory
                candidates.add(File(moduleDir, "lib/$LIB_NAME").absolutePath)
            }
        }

        // Strategy 2: Try to resolve from the module's ApplicationInfo nativeLibraryDir
        try {
            val modulePackage = "dev.lok1s.handoffmyvpn"
            val pm = scope.appContext?.packageManager
            if (pm != null) {
                val ai = pm.getApplicationInfo(modulePackage, 0)
                val nativeDir = ai.nativeLibraryDir
                if (nativeDir != null) {
                    candidates.add(File(nativeDir, LIB_NAME).absolutePath)
                }
            }
        } catch (_: Throwable) {
            // PackageManager may not resolve module package
        }

        // Strategy 3: Common LSPosed module extraction paths
        val commonBaseDir = "/data/app"
        try {
            val appsDir = File(commonBaseDir)
            if (appsDir.exists()) {
                appsDir.listFiles()?.filter {
                    it.isDirectory && it.name.contains("handoffmyvpn", ignoreCase = true)
                }?.forEach { appDir ->
                    for (abi in abis) {
                        candidates.add(File(appDir, "lib/$abi/$LIB_NAME").absolutePath)
                    }
                }
            }
        } catch (_: Throwable) {
            // Directory listing may fail due to SELinux
        }

        // Only return candidates that actually exist
        return candidates.filter { File(it).exists() && File(it).canRead() }
    }

    private fun LOGD(msg: String) = YLog.debug(msg = msg, tag = TAG)
    private fun LOGW(msg: String) = YLog.warn(msg = msg, tag = TAG)
}
