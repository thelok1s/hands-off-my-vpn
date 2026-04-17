package dev.lok1s.handoffmyvpn.hook

import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.type.java.StringClass

/**
 * # SystemPropertyHook
 *
 * Target: [java.lang.System]
 *
 * ## What it hooks
 *
 * ### `System.getProperty(String key) → String?`
 *
 * VPN applications often set JVM-level HTTP/SOCKS proxy properties so that
 * all Java HTTP traffic is routed through the VPN tunnel.  Some detection
 * SDKs query these properties directly:
 *
 * | Property           | Meaning                          |
 * |--------------------|----------------------------------|
 * | `http.proxyHost`   | HTTP proxy hostname (VPN local)  |
 * | `http.proxyPort`   | HTTP proxy port                  |
 * | `https.proxyHost`  | HTTPS proxy hostname             |
 * | `https.proxyPort`  | HTTPS proxy port                 |
 * | `socksProxyHost`   | SOCKS proxy hostname             |
 * | `socksProxyPort`   | SOCKS proxy port                 |
 *
 * For any of these keys we return `null`, making the app believe no proxy
 * is configured.
 */
object SystemPropertyHook {

    private const val TAG = "HandsOffMyVPN/SysProp"

    /** Property keys that expose VPN proxy configuration. */
    private val PROXY_KEYS = setOf(
        "http.proxyHost",
        "http.proxyPort",
        "https.proxyHost",
        "https.proxyPort",
        "socksProxyHost",
        "socksProxyPort",
    )

    fun register(scope: PackageParam) = scope.apply {
        hookGetProperty()
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun PackageParam.hookGetProperty() {
        System::class.java.method {
            name       = "getProperty"
            param(StringClass)
            returnType = StringClass
        }.hook {
            before {
                val key = args[0] as? String ?: return@before

                if (key in PROXY_KEYS) {
                    YLog.debug(msg = "System.getProperty(\"$key\") → null (spoofed)", tag = TAG)
                    result = null
                }
            }
        }
    }
}
