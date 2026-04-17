package dev.lok1s.handoffmyvpn.hook

import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam
import java.net.NetworkInterface
import java.util.Collections
import java.util.Enumeration

/**
 * # NetworkInterfaceHook
 *
 * Target: [java.net.NetworkInterface]
 *
 * ## What it hooks
 *
 * ### `NetworkInterface.getNetworkInterfaces() → Enumeration<NetworkInterface>?`
 *
 * This static method returns *all* network interfaces visible to the process.
 * VPN clients create virtual tunnel interfaces with predictable naming schemes:
 *
 * | Prefix | VPN technology |
 * |--------|----------------|
 * | `tun`  | TUN/TAP — used by OpenVPN, WireGuard, OpenConnect, etc. |
 * | `ppp`  | PPP-over-XYZ — used by older L2TP/PPTP and some carrier VPNs |
 * | `ipsec`| IPSec — used by IKEv2 / StrongSwan setups (bonus filter) |
 *
 * We intercept the enumeration after it is built, remove matching interfaces,
 * and return a clean [Enumeration] to the caller.
 *
 * ## Why Enumeration and not a List?
 *
 * The JVM API contract for `getNetworkInterfaces()` specifies
 * `Enumeration<NetworkInterface>?`, so we must return the same type.
 * [Collections.enumeration] converts our filtered [List] back to one.
 *
 * ## Edge cases handled
 * - The method may return `null` (no interfaces at all).  We leave `null` as-is.
 * - If filtering removes ALL interfaces we still return an empty [Enumeration]
 *   rather than `null`, which is the safest option for most callers.
 */
object NetworkInterfaceHook {

    private const val TAG = "HandsOffMyVPN/NI"

    /**
     * Interface name prefixes that indicate a VPN tunnel.
     * Extend this list if you encounter other VPN interface names in the wild.
     */
    private val VPN_INTERFACE_PREFIXES = listOf(
        "tun",    // TUN/TAP (OpenVPN, WireGuard userspace, etc.)
        "tap",    // TAP mode (layer-2 VPN tunnels)
        "ppp",    // PPP-based VPN (L2TP, PPTP)
        "ipsec",  // IPSec tunnels from StrongSwan / system VPN
        "wg",     // WireGuard kernel module interface (wg0, wg1, …)
    )

    /**
     * Registers all hooks in this object into the supplied [PackageParam] scope.
     * Called from [dev.lok1s.handoffmyvpn.HookEntry].
     */
    fun register(scope: PackageParam) = scope.apply {
        hookGetNetworkInterfaces()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private hook implementations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Hooks [NetworkInterface.getNetworkInterfaces].
     *
     * This is a `static` method — YukiHookAPI handles that transparently when
     * you hook the class; no special syntax is needed.
     *
     * Flow:
     * ```
     * original getNetworkInterfaces() runs
     *   └─► afterHook fires
     *         result == null?  → leave as-is (no interfaces at all)
     *         result != null?  → convert Enumeration → List
     *                            filter out VPN tunnels
     *                            convert List → Enumeration
     *                            replace result
     * ```
     */
    private fun PackageParam.hookGetNetworkInterfaces() {
        NetworkInterface::class.java.method {
            name       = "getNetworkInterfaces"
            emptyParam()
            // Erasure of Enumeration<NetworkInterface> is just Enumeration
            returnType = Enumeration::class.java
        }.hook {
            after {
                @Suppress("UNCHECKED_CAST")
                val enumeration = result as? Enumeration<NetworkInterface> ?: return@after

                // Convert to a mutable list so we can filter it.
                val allInterfaces: List<NetworkInterface> =
                    enumeration.toList()

                val cleanInterfaces: List<NetworkInterface> = allInterfaces.filter { iface ->
                    val name = iface.name.lowercase()
                    val isVpnTunnel = VPN_INTERFACE_PREFIXES.any { prefix -> name.startsWith(prefix) }
                    if (isVpnTunnel) {
                        YLog.debug(msg = "getNetworkInterfaces: hiding tunnel interface '${iface.name}'", tag = TAG)
                    }
                    !isVpnTunnel
                }

                YLog.debug(
                    msg = "getNetworkInterfaces: ${allInterfaces.size} → ${cleanInterfaces.size} interfaces after VPN filter",
                    tag = TAG
                )

                // Return a fresh Enumeration wrapping only the clean interfaces.
                result = Collections.enumeration(cleanInterfaces)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Extension helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Drains an [Enumeration] into a [List].  Equivalent to
     * `Collections.list(enum)` but written as an extension for clarity.
     */
    private fun <T> Enumeration<T>.toList(): List<T> {
        val list = mutableListOf<T>()
        while (hasMoreElements()) list.add(nextElement())
        return list
    }
}
