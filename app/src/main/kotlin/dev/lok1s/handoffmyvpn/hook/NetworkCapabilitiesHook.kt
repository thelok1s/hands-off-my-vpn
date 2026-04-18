package dev.lok1s.handoffmyvpn.hook

import android.net.NetworkCapabilities
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.type.java.IntType

object NetworkCapabilitiesHook {

    private const val TAG = "HandsOffMyVPN/NC"
    private const val TRANSPORT_VPN = 4
    private const val NET_CAPABILITY_NOT_VPN = 15

    fun register(scope: PackageParam) = scope.apply {
        hookHasTransport()
        hookHasCapability()
        hookGetTransportInfo()
    }

    private fun PackageParam.hookHasTransport() {
        NetworkCapabilities::class.java.method {
            name = "hasTransport"
            param(IntType)
            returnType = Boolean::class.java
        }.hook {
            before {
                val transportType = args[0] as? Int ?: return@before
                if (transportType == TRANSPORT_VPN) {
                    YLog.debug(msg = "hasTransport(TRANSPORT_VPN=4) → false", tag = TAG)
                    result = false
                    LogDispatcher.dispatch("hasTransport(TRANSPORT_VPN)", "→ false (spoofed)")
                }
            }
        }
    }

    private fun PackageParam.hookHasCapability() {
        try {
            NetworkCapabilities::class.java.method {
                name = "hasCapability"
                param(IntType)
                returnType = Boolean::class.java
            }.hook {
                before {
                    val capability = args[0] as? Int ?: return@before
                    if (capability == NET_CAPABILITY_NOT_VPN) {
                        YLog.debug(msg = "hasCapability(NOT_VPN=15) → true", tag = TAG)
                        result = true
                        LogDispatcher.dispatch("hasCapability(NOT_VPN)", "→ true (spoofed)")
                    }
                }
            }
        } catch (e: Throwable) {
            YLog.warn(msg = "hookHasCapability failed: ${e.message}", tag = TAG)
        }
    }

    private fun PackageParam.hookGetTransportInfo() {
        try {
            NetworkCapabilities::class.java.method {
                name = "getTransportInfo"
                emptyParam()
            }.hook {
                after {
                    if (result != null) {
                        val info = result.toString()
                        if (info.lowercase().contains("vpn")) {
                            YLog.debug(msg = "getTransportInfo() → null (was: $info)", tag = TAG)
                            result = null
                            LogDispatcher.dispatch("getTransportInfo()", "→ null (suppressed)")
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            YLog.warn(msg = "hookGetTransportInfo failed: ${e.message}", tag = TAG)
        }
    }
}
