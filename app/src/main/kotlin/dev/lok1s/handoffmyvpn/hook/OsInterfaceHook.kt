package dev.lok1s.handoffmyvpn.hook

import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam

/**
 * Hooks android.system.Os.getifaddrs() — the Java/Kotlin-accessible wrapper
 * around libc's getifaddrs(). Detection apps (e.g. IfconfigTermuxLikeDetector)
 * call this instead of going through native JNI directly, which bypasses
 * Dobby inline hooks on libc.so. This Xposed hook covers that path.
 */
object OsInterfaceHook {

    private const val TAG = "HandsOffMyVPN/OsIface"

    private val VPN_PREFIXES = listOf("tun", "tap", "ppp", "ipsec", "wg")

    fun register(scope: PackageParam) = scope.apply {
        hookOsGetIfAddrs()
    }

    private fun PackageParam.hookOsGetIfAddrs() {
        try {
            Class.forName("android.system.Os").method {
                name = "getifaddrs"
                emptyParam()
            }.hook {
                after {
                    val addrs = result as? Array<*> ?: return@after
                    if (addrs.isEmpty()) return@after

                    val structClass = addrs.javaClass.componentType ?: return@after
                    val nameField = try {
                        structClass.getDeclaredField("ifa_name").also { it.isAccessible = true }
                    } catch (_: Throwable) { return@after }

                    val filtered = addrs.filter { addr ->
                        if (addr == null) return@filter true
                        val name = try { nameField.get(addr) as? String } catch (_: Throwable) { null }
                        name == null || VPN_PREFIXES.none { name.startsWith(it) }
                    }

                    if (filtered.size < addrs.size) {
                        val newArray = java.lang.reflect.Array.newInstance(structClass, filtered.size)
                        filtered.forEachIndexed { i, item ->
                            java.lang.reflect.Array.set(newArray, i, item)
                        }
                        result = newArray
                        val hidden = addrs.size - filtered.size
                        YLog.debug(msg = "Os.getifaddrs: hid $hidden VPN interface(s)", tag = TAG)
                        LogDispatcher.dispatch("Os.getifaddrs()", "hid $hidden VPN iface(s) (spoofed)")
                    }
                }
            }
        } catch (e: Throwable) {
            YLog.warn(msg = "hookOsGetIfAddrs failed: ${e.message}", tag = TAG)
        }
    }
}
