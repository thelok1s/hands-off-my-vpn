package dev.lok1s.handoffmyvpn.hook

import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam
import java.net.Proxy
import java.net.URI

/**
 * # ProxyHook
 *
 * Targets: [java.net.ProxySelector] and [android.net.Proxy]
 *
 * ## What it hooks
 *
 * ### `ProxySelector.getDefault().select(URI) → List<Proxy>`
 *
 * Many HTTP libraries (OkHttp, HttpURLConnection) call `ProxySelector.select()`
 * to decide whether to route traffic through a proxy.  VPN apps configure a
 * proxy selector that returns their local tunnel endpoint.  We intercept this
 * and always return `listOf(Proxy.NO_PROXY)`.
 *
 * NOTE: We cannot hook `ProxySelector.select()` directly because it's abstract.
 * Instead, we hook the concrete `sun.net.spi.DefaultProxySelector.select()`,
 * and also wrap `ProxySelector.getDefault()` to return a non-VPN selector.
 *
 * ### `android.net.Proxy.getHost(Context) → String`  (Deprecated API)
 *
 * Some older detection code still uses the Android framework `Proxy` helper.
 * We return an empty string to indicate "no proxy configured."
 */
object ProxyHook {

    private const val TAG = "HandsOffMyVPN/Proxy"

    fun register(scope: PackageParam) = scope.apply {
        hookProxySelectorGetDefault()
        hookAndroidProxyGetHost()
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Hook: `ProxySelector.getDefault() → ProxySelector`
     *
     * Instead of hooking the abstract `select()` method (which crashes with
     * "Cannot hook abstract methods"), we wrap the return value of getDefault()
     * and intercept its `select()` call result. This is done by hooking the
     * concrete `select()` on the runtime class of the default selector.
     */
    private fun PackageParam.hookProxySelectorGetDefault() {
        try {
            // Hook the concrete select() method on whatever class getDefault() returns
            val defaultSelector = java.net.ProxySelector.getDefault()
            if (defaultSelector != null) {
                val concreteClass = defaultSelector.javaClass
                concreteClass.method {
                    name       = "select"
                    param(URI::class.java)
                    returnType = java.util.List::class.java
                }.hook {
                    before {
                        val uri = args[0] as? URI
                        YLog.debug(
                            msg = "ProxySelector.select(${uri}) → NO_PROXY (spoofed)",
                            tag = TAG
                        )
                        result = listOf(Proxy.NO_PROXY)
                    }
                }
            }
        } catch (e: Throwable) {
            // Non-fatal — proxy detection is secondary
            YLog.warn(msg = "hookProxySelectorGetDefault failed: ${e.message}", tag = TAG)
        }
    }

    /**
     * Hook: `android.net.Proxy.getHost(Context) → String`
     *
     * Deprecated since API 14 but still checked by some older SDK code.
     */
    @Suppress("DEPRECATION")
    private fun PackageParam.hookAndroidProxyGetHost() {
        try {
            android.net.Proxy::class.java.method {
                name       = "getHost"
                param(android.content.Context::class.java)
                returnType = String::class.java
            }.hook {
                before {
                    YLog.debug(msg = "Proxy.getHost() → \"\" (spoofed)", tag = TAG)
                    result = ""
                }
            }
        } catch (e: Throwable) {
            // Method may not exist on all API levels — non-fatal
            YLog.warn(msg = "ProxyHook: android.net.Proxy.getHost not found, skipping", tag = TAG)
        }
    }
}
