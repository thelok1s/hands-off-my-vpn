package dev.lok1s.handoffmyvpn.hook

import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam

/**
 * Hooks IfconfigTermuxLikeDetector's JNI methods at the Java/Xposed layer.
 *
 * VPN Detector's native library (libifconfigdetector.so) calls ::getifaddrs() directly
 * from C++, bypassing both our Dobby libc hook and android.system.Os.getifaddrs().
 * Hooking the JNI boundary (after the native call returns to Java) is the only
 * reliable intercept point.
 *
 * Methods hooked:
 *   getInterfacesNative()       → Array<String>  (ifconfig-like blocks, one per interface)
 *   getKernelRoutesNative()     → Array<String>  ("<iface>: <dest>/<prefix> [via <gw>]")
 *   getKernelIpv6RoutesNative() → Array<String>  (same format, IPv6)
 */
object IfconfigDetectorHook {

    private const val TAG = "HandsOffMyVPN/Ifcfg"
    private const val TARGET = "com.cherepavel.vpndetector.detector.IfconfigTermuxLikeDetector"

    private val VPN_PREFIXES = listOf(
        "tun", "tap", "ppp", "wg", "utun", "ipsec", "xfrm", "zt",
        "tailscale", "svpn", "ovpn", "l2tp", "gre", "he-ipv6"
    )

    private fun isVpnIface(name: String): Boolean {
        val lower = name.trim().lowercase()
        return VPN_PREFIXES.any { lower.startsWith(it) } || lower.contains("vpn")
    }

    fun register(scope: PackageParam) = scope.apply {
        val cls = try {
            appClassLoader?.loadClass(TARGET) ?: return@apply
        } catch (_: ClassNotFoundException) {
            return@apply
        } catch (e: Throwable) {
            YLog.warn(msg = "IfconfigDetectorHook load failed: ${e.message}", tag = TAG)
            return@apply
        }
        hookInterfaces(cls)
        hookRoutes(cls, "getKernelRoutesNative")
        hookRoutes(cls, "getKernelIpv6RoutesNative")
    }

    private fun PackageParam.hookInterfaces(cls: Class<*>) {
        try {
            cls.method { name = "getInterfacesNative"; emptyParam() }.hook {
                after {
                    val arr = result as? Array<*> ?: return@after
                    val clean = arr.filterIsInstance<String>().filter { block ->
                        val ifName = block.lineSequence().firstOrNull()
                            ?.substringBefore(':')?.trim().orEmpty()
                        !isVpnIface(ifName)
                    }
                    if (clean.size < arr.size) {
                        result = clean.toTypedArray()
                        val n = arr.size - clean.size
                        YLog.debug(msg = "getInterfacesNative: hid $n VPN block(s)", tag = TAG)
                        LogDispatcher.dispatch("getInterfacesNative()", "hid $n VPN interface(s) (spoofed)")
                    }
                }
            }
        } catch (e: Throwable) {
            YLog.warn(msg = "hookInterfaces failed: ${e.message}", tag = TAG)
        }
    }

    private fun PackageParam.hookRoutes(cls: Class<*>, methodName: String) {
        try {
            cls.method { name = methodName; emptyParam() }.hook {
                after {
                    val arr = result as? Array<*> ?: return@after
                    val clean = arr.filterIsInstance<String>().filter { route ->
                        val ifName = route.substringBefore(':').trim()
                        !isVpnIface(ifName)
                    }
                    if (clean.size < arr.size) {
                        result = clean.toTypedArray()
                        val n = arr.size - clean.size
                        YLog.debug(msg = "$methodName: hid $n VPN route(s)", tag = TAG)
                        LogDispatcher.dispatch("$methodName()", "hid $n VPN route(s) (spoofed)")
                    }
                }
            }
        } catch (e: Throwable) {
            YLog.warn(msg = "hookRoutes($methodName) failed: ${e.message}", tag = TAG)
        }
    }
}
