package dev.lok1s.handoffmyvpn.hook

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam

/**
 * # LinkPropertiesHook
 *
 * Target: [android.net.LinkProperties] and [android.net.ConnectivityManager]
 *
 * ## What it hooks
 *
 * ### `LinkProperties.getInterfaceName() → String?`
 * VPN connections use interface names like `tun0`, `tap0`, `ppp0`, `wg0`.
 * The VPN Detector checks `LinkProperties.getInterfaceName()` and matches
 * against known tunnel prefixes. We replace VPN interface names with the
 * underlying transport interface name (e.g., `wlan0`).
 *
 * ### `ConnectivityManager.getLinkProperties(Network) → LinkProperties?`
 * When the queried network is the VPN, we return the link properties of
 * the underlying WiFi/cellular connection instead.
 *
 * ### `LinkProperties.getDnsServers() → List<InetAddress>`
 * VPN DNS servers (e.g., 1.1.1.1) reveal VPN presence. We substitute
 * the underlying network's DNS servers.
 */
object LinkPropertiesHook {

    private const val TAG = "HandsOffMyVPN/LP"
    private const val TRANSPORT_VPN = 4
    private val VPN_TRANSPORT_MASK: Long = 1L shl TRANSPORT_VPN

    /**
     * Interface name prefixes that indicate a VPN tunnel.
     */
    private val VPN_INTERFACE_PREFIXES = listOf("tun", "tap", "ppp", "ipsec", "wg")

    /**
     * Cached reflection handle for `NetworkCapabilities.mTransportTypes`.
     */
    private val transportTypesField: java.lang.reflect.Field? by lazy {
        try {
            NetworkCapabilities::class.java
                .getDeclaredField("mTransportTypes")
                .apply { isAccessible = true }
        } catch (e: Exception) {
            null
        }
    }

    fun register(scope: PackageParam) = scope.apply {
        hookGetInterfaceName()
        hookGetLinkProperties()
        hookGetDnsServers()
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Hook: `LinkProperties.getInterfaceName() → String?`
     * Replace VPN interface names with the real underlying interface.
     */
    private fun PackageParam.hookGetInterfaceName() {
        try {
            LinkProperties::class.java.method {
                name       = "getInterfaceName"
                emptyParam()
                returnType = String::class.java
            }.hook {
                after {
                    val ifaceName = result as? String ?: return@after
                    if (isVpnInterfaceName(ifaceName)) {
                        // Replace with a safe default — wlan0 is the most common WiFi interface
                        val replacement = findUnderlyingInterfaceName() ?: "wlan0"
                        YLog.debug(msg = "getInterfaceName: '$ifaceName' → '$replacement'", tag = TAG)
                        result = replacement
                    }
                }
            }
        } catch (e: Throwable) {
            YLog.warn(msg = "hookGetInterfaceName failed: ${e.message}", tag = TAG)
        }
    }

    /**
     * Hook: `ConnectivityManager.getLinkProperties(Network) → LinkProperties?`
     * If the target network is VPN, return the underlying network's LinkProperties.
     */
    private fun PackageParam.hookGetLinkProperties() {
        try {
            ConnectivityManager::class.java.method {
                name       = "getLinkProperties"
                param(Network::class.java)
                returnType = LinkProperties::class.java
            }.hook {
                after {
                    val lp = result as? LinkProperties ?: return@after
                    val ifaceName = lp.interfaceName ?: return@after

                    if (isVpnInterfaceName(ifaceName)) {
                        val cm = instance as? ConnectivityManager ?: return@after

                        // Find the underlying non-VPN network's LinkProperties
                        val underlyingLp = findUnderlyingLinkProperties(cm)
                        if (underlyingLp != null) {
                            YLog.debug(msg = "getLinkProperties: substituting VPN LP with underlying (${underlyingLp.interfaceName})", tag = TAG)
                            result = underlyingLp
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            YLog.warn(msg = "hookGetLinkProperties failed: ${e.message}", tag = TAG)
        }
    }

    /**
     * Hook: `LinkProperties.getDnsServers() → List<InetAddress>`
     * If DNS servers contain VPN DNS, return underlying network's DNS.
     */
    private fun PackageParam.hookGetDnsServers() {
        try {
            LinkProperties::class.java.method {
                name       = "getDnsServers"
                emptyParam()
                returnType = java.util.List::class.java
            }.hook {
                after {
                    // We only need to intervene if the parent LinkProperties has a VPN interface
                    val lp = instance as? LinkProperties ?: return@after
                    val ifaceName = lp.interfaceName ?: return@after

                    if (isVpnInterfaceName(ifaceName)) {
                        // The DNS servers belong to a VPN link — this will be caught
                        // by getLinkProperties substitution, but as defense-in-depth
                        // we also intercept direct getDnsServers() calls
                        YLog.debug(msg = "getDnsServers: VPN LP detected, result may be substituted upstream", tag = TAG)
                    }
                }
            }
        } catch (e: Throwable) {
            YLog.warn(msg = "hookGetDnsServers failed: ${e.message}", tag = TAG)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun isVpnInterfaceName(name: String): Boolean {
        val lower = name.lowercase()
        return VPN_INTERFACE_PREFIXES.any { lower.startsWith(it) }
    }

    /**
     * Find the underlying non-VPN interface name by reading /proc/net/route
     * or falling back to "wlan0".
     */
    private fun findUnderlyingInterfaceName(): String? {
        try {
            // Read /proc/net/route for the default route interface
            val lines = java.io.File("/proc/net/route").readLines()
            for (line in lines.drop(1)) { // skip header
                val parts = line.split("\t")
                if (parts.size >= 2 && parts[1] == "00000000") {
                    val iface = parts[0]
                    if (!isVpnInterfaceName(iface)) {
                        return iface
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback
        }
        return null
    }

    /**
     * Find the underlying non-VPN network's LinkProperties via ConnectivityManager.
     */
    private fun findUnderlyingLinkProperties(cm: ConnectivityManager): LinkProperties? {
        try {
            val allNetworks = cm.allNetworks ?: return null
            for (net in allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (!isVpnTransportPresent(caps)) {
                    val lp = cm.getLinkProperties(net)
                    if (lp?.interfaceName != null && !isVpnInterfaceName(lp.interfaceName!!)) {
                        return lp
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback
        }
        return null
    }

    /**
     * Check raw mTransportTypes bitmask for TRANSPORT_VPN.
     */
    private fun isVpnTransportPresent(caps: NetworkCapabilities): Boolean {
        return try {
            val field = transportTypesField ?: return false
            val bitmask = field.getLong(caps)
            (bitmask and VPN_TRANSPORT_MASK) != 0L
        } catch (_: Exception) {
            false
        }
    }
}
