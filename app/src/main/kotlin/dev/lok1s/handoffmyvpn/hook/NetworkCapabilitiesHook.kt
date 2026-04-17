package dev.lok1s.handoffmyvpn.hook

import android.net.NetworkCapabilities
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.type.java.IntType

/**
 * # NetworkCapabilitiesHook
 *
 * Target: [android.net.NetworkCapabilities]
 *
 * ## What it hooks
 *
 * ### `hasTransport(int transportType) → boolean`
 * When the transport queried is TRANSPORT_VPN (= 4), we return `false`.
 *
 * ### `hasCapability(int capability) → boolean`
 * When queried for NET_CAPABILITY_NOT_VPN (= 15), we return `true`.
 * VPN networks lack this capability, so returning true hides VPN status.
 *
 * ### `getTransportInfo() → TransportInfo?`
 * Returns null to suppress VPN transport metadata that would leak
 * "VPN (platform)" via NetworkCapabilities.getTransportInfo().
 */
object NetworkCapabilitiesHook {

    private const val TAG = "HandsOffMyVPN/NC"

    /** VPN transport constant — [NetworkCapabilities.TRANSPORT_VPN] = 4 */
    private const val TRANSPORT_VPN = 4

    /** NET_CAPABILITY_NOT_VPN = 15 — capability present on non-VPN networks */
    private const val NET_CAPABILITY_NOT_VPN = 15

    fun register(scope: PackageParam) = scope.apply {
        hookHasTransport()
        hookHasCapability()
        hookGetTransportInfo()
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Hooks [NetworkCapabilities.hasTransport].
     * When transportType == TRANSPORT_VPN → return false.
     */
    private fun PackageParam.hookHasTransport() {
        NetworkCapabilities::class.java.method {
            name       = "hasTransport"
            param(IntType)
            returnType = Boolean::class.java
        }.hook {
            before {
                val transportType = args[0] as? Int ?: return@before

                if (transportType == TRANSPORT_VPN) {
                    YLog.debug(msg = "hasTransport(TRANSPORT_VPN=4) intercepted → returning false", tag = TAG)
                    result = false
                }
            }
        }
    }

    /**
     * Hooks [NetworkCapabilities.hasCapability].
     * When capability == NET_CAPABILITY_NOT_VPN → return true.
     * This makes VPN networks appear as non-VPN networks.
     */
    private fun PackageParam.hookHasCapability() {
        try {
            NetworkCapabilities::class.java.method {
                name       = "hasCapability"
                param(IntType)
                returnType = Boolean::class.java
            }.hook {
                before {
                    val capability = args[0] as? Int ?: return@before

                    if (capability == NET_CAPABILITY_NOT_VPN) {
                        YLog.debug(msg = "hasCapability(NET_CAPABILITY_NOT_VPN=15) intercepted → returning true", tag = TAG)
                        result = true
                    }
                }
            }
        } catch (e: Throwable) {
            YLog.warn(msg = "hookHasCapability failed: ${e.message}", tag = TAG)
        }
    }

    /**
     * Hooks [NetworkCapabilities.getTransportInfo].
     * Returns null to suppress VPN transport info metadata.
     */
    private fun PackageParam.hookGetTransportInfo() {
        try {
            NetworkCapabilities::class.java.method {
                name       = "getTransportInfo"
                emptyParam()
            }.hook {
                after {
                    if (result != null) {
                        val info = result.toString()
                        // Only suppress if it looks like VPN transport info
                        if (info.lowercase().contains("vpn")) {
                            YLog.debug(msg = "getTransportInfo() → null (was: $info)", tag = TAG)
                            result = null
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            // getTransportInfo was added in API 29, may not exist on older devices
            YLog.warn(msg = "hookGetTransportInfo failed: ${e.message}", tag = TAG)
        }
    }
}
