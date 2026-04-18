package dev.lok1s.handoffmyvpn.hook

import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.type.java.StringClass

object SystemPropertyHook {

    private const val TAG = "HandsOffMyVPN/SysProp"

    private val PROXY_KEYS = setOf(
        "http.proxyHost", "http.proxyPort",
        "https.proxyHost", "https.proxyPort",
        "socksProxyHost", "socksProxyPort",
    )

    fun register(scope: PackageParam) = scope.apply {
        hookGetProperty()
    }

    private fun PackageParam.hookGetProperty() {
        System::class.java.method {
            name = "getProperty"
            param(StringClass)
            returnType = StringClass
        }.hook {
            before {
                val key = args[0] as? String ?: return@before
                if (key in PROXY_KEYS) {
                    YLog.debug(msg = "System.getProperty(\"$key\") → null", tag = TAG)
                    result = null
                    LogDispatcher.dispatch("System.getProperty(\"$key\")", "→ null (spoofed)")
                }
            }
        }
    }
}
