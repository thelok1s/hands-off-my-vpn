package dev.lok1s.handoffmyvpn.hook

import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam
import java.net.Proxy
import java.net.URI

object ProxyHook {

    private const val TAG = "HandsOffMyVPN/Proxy"

    fun register(scope: PackageParam) = scope.apply {
        hookProxySelectorGetDefault()
        hookAndroidProxyGetHost()
    }

    private fun PackageParam.hookProxySelectorGetDefault() {
        try {
            val defaultSelector = java.net.ProxySelector.getDefault()
            if (defaultSelector != null) {
                defaultSelector.javaClass.method {
                    name = "select"
                    param(URI::class.java)
                    returnType = java.util.List::class.java
                }.hook {
                    before {
                        val uri = args[0] as? URI
                        YLog.debug(msg = "ProxySelector.select($uri) → NO_PROXY", tag = TAG)
                        result = listOf(Proxy.NO_PROXY)
                        LogDispatcher.dispatch("ProxySelector.select($uri)", "→ NO_PROXY (spoofed)")
                    }
                }
            }
        } catch (e: Throwable) {
            YLog.warn(msg = "hookProxySelectorGetDefault failed: ${e.message}", tag = TAG)
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageParam.hookAndroidProxyGetHost() {
        try {
            android.net.Proxy::class.java.method {
                name = "getHost"
                param(android.content.Context::class.java)
                returnType = String::class.java
            }.hook {
                before {
                    YLog.debug(msg = "Proxy.getHost() → \"\" (spoofed)", tag = TAG)
                    result = ""
                    LogDispatcher.dispatch("Proxy.getHost()", "→ \"\" (spoofed)")
                }
            }
        } catch (e: Throwable) {
            YLog.warn(msg = "ProxyHook: android.net.Proxy.getHost not found", tag = TAG)
        }
    }
}
