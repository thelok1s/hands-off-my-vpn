package dev.lok1s.handoffmyvpn.hook

import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam
import java.net.NetworkInterface
import java.util.Collections
import java.util.Enumeration

object NetworkInterfaceHook {

    private const val TAG = "HandsOffMyVPN/NI"

    private val VPN_INTERFACE_PREFIXES = listOf("tun", "tap", "ppp", "ipsec", "wg")

    fun register(scope: PackageParam) = scope.apply {
        hookGetNetworkInterfaces()
    }

    private fun PackageParam.hookGetNetworkInterfaces() {
        NetworkInterface::class.java.method {
            name = "getNetworkInterfaces"
            emptyParam()
            returnType = Enumeration::class.java
        }.hook {
            after {
                @Suppress("UNCHECKED_CAST")
                val enumeration = result as? Enumeration<NetworkInterface> ?: return@after

                val all = enumeration.toList()
                val clean = all.filter { iface ->
                    val name = iface.name.lowercase()
                    val isVpn = VPN_INTERFACE_PREFIXES.any { name.startsWith(it) }
                    if (isVpn) YLog.debug(msg = "hiding tunnel interface '${iface.name}'", tag = TAG)
                    !isVpn
                }

                YLog.debug(msg = "getNetworkInterfaces: ${all.size} → ${clean.size} after VPN filter", tag = TAG)
                if (clean.size < all.size) {
                    LogDispatcher.dispatch(
                        "getNetworkInterfaces()",
                        "hid ${all.size - clean.size} VPN interface(s)"
                    )
                }
                result = Collections.enumeration(clean)
            }
        }
    }

    private fun <T> Enumeration<T>.toList(): List<T> {
        val list = mutableListOf<T>()
        while (hasMoreElements()) list.add(nextElement())
        return list
    }
}
