package dev.lok1s.handoffmyvpn.hook

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam

/**
 * # ConnectivityManagerHook
 *
 * Target: [android.net.ConnectivityManager]
 *
 * Hooks three methods that are widely used to detect the existence of an
 * active VPN connection:
 *
 * | # | Method | Strategy |
 * |---|--------|----------|
 * | 1 | `getNetworkCapabilities(Network)` | Strip TRANSPORT_VPN from the returned [NetworkCapabilities] via reflection on the internal `mTransportTypes` bitmask. |
 * | 2 | `getAllNetworks()` | Filter out networks that carry TRANSPORT_VPN. |
 * | 3 | `getAllNetworkInfo()` | Filter out [NetworkInfo] entries whose type is TYPE_VPN (17) or whose type-name is "VPN". |
 *
 * ## Internal bitmask layout
 *
 * `NetworkCapabilities` stores transports in a private `long mTransportTypes`
 * field (confirmed across AOSP from API 23 through 34).  Each transport T sets
 * bit `(1L shl T)`.  TRANSPORT_VPN = 4, so the mask for VPN is `1L shl 4 = 16`.
 * We clear that bit with a bitwise AND of its complement.
 *
 * Reference:
 * https://cs.android.com/android/platform/superproject/+/main:frameworks/base/core/java/android/net/NetworkCapabilities.java
 */
@Suppress("DEPRECATION") // getAllNetworkInfo() is deprecated but still used by older apps
@SuppressLint("MissingPermission", "SoonBlockedPrivateApi") // We run in the target app's process, so we inherit its permissions and bypass hidden API restrictions via Xposed context where applicable
object ConnectivityManagerHook {

    private const val TAG = "HandsOffMyVPN/CM"

    /** [NetworkCapabilities.TRANSPORT_VPN] = 4 */
    private const val TRANSPORT_VPN = 4

    /** [ConnectivityManager.TYPE_VPN] = 17 */
    private const val TYPE_VPN = 17

    /** Bitmask for TRANSPORT_VPN inside `NetworkCapabilities.mTransportTypes`. */
    private const val VPN_TRANSPORT_MASK: Long = 1L shl TRANSPORT_VPN   // == 0x10L

    /**
     * Cached reflection handle for `NetworkCapabilities.mTransportTypes`.
     * Resolved lazily on first use; subsequent calls skip the getDeclaredField
     * + setAccessible overhead.  If reflection fails (e.g. field renamed on a
     * future API level) this stays null and the reflection helpers fall back
     * gracefully.
     */
    private val transportTypesField: java.lang.reflect.Field? by lazy {
        try {
            NetworkCapabilities::class.java
                .getDeclaredField("mTransportTypes")
                .apply { isAccessible = true }
        } catch (e: Exception) {
            YLog.warn(msg = "Failed to cache mTransportTypes field: ${e.message}", tag = TAG)
            null
        }
    }

    /**
     * Registers all hooks in this object into the supplied [PackageParam] scope.
     * Called from [dev.lok1s.handoffmyvpn.HookEntry].
     */
    fun register(scope: PackageParam) = scope.apply {
        hookGetNetworkCapabilities()
        hookGetAllNetworks()
        hookGetAllNetworkInfo()
        hookGetActiveNetwork()
        hookGetActiveNetworkInfo()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private hook implementations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Hook: `ConnectivityManager.getNetworkCapabilities(Network) → NetworkCapabilities?`
     *
     * After the real method runs we receive its [NetworkCapabilities] result and
     * scrub the TRANSPORT_VPN bit from the private `mTransportTypes` field via
     * reflection.  This makes every downstream `hasTransport(TRANSPORT_VPN)` call
     * on the same object return false even without the class-level hook in
     * [NetworkCapabilitiesHook] (defence in depth).
     */
    private fun PackageParam.hookGetNetworkCapabilities() {
        ConnectivityManager::class.java.method {
            name       = "getNetworkCapabilities"
            param(Network::class.java)
            returnType = NetworkCapabilities::class.java
        }.hook {
            after {
                val nc = result as? NetworkCapabilities ?: return@after
                val hadVpn = isVpnTransportPresent(nc)
                stripVpnTransport(nc)
                if (hadVpn) LogDispatcher.dispatch("getNetworkCapabilities()", "VPN transport stripped")
            }
        }
    }

    /**
     * Hook: `ConnectivityManager.getAllNetworks() → Array<Network>`
     *
     * Iterates the returned network array.  For each network it calls
     * [ConnectivityManager.getNetworkCapabilities] (on the *real* object, so we
     * get the un-stripped caps at this point) and drops any network that reports
     * TRANSPORT_VPN.
     *
     * Note: we call `getNetworkCapabilities` through the [ConnectivityManager]
     * instance captured in [instanceAs] to avoid calling our own hooked version
     * recursively — instead we check the native bitmask directly.
     */
    private fun PackageParam.hookGetAllNetworks() {
        ConnectivityManager::class.java.method {
            name       = "getAllNetworks"
            emptyParam()
            returnType = java.lang.reflect.Array.newInstance(Network::class.java, 0).javaClass
        }.hook {
            after {
                @Suppress("UNCHECKED_CAST")
                val networks = result as? Array<Network> ?: return@after
                val cm = instance as? ConnectivityManager ?: return@after

                val filtered = networks.filter { network ->
                    // Use the system call (not our hooked variant) to check the real transport.
                    val caps = cm.getNetworkCapabilities(network)
                    val isVpn = caps != null && isVpnTransportPresent(caps)
                    if (isVpn) YLog.debug(msg = "getAllNetworks: hiding VPN network $network", tag = TAG)
                    !isVpn
                }.toTypedArray()

                YLog.debug(msg = "getAllNetworks: ${networks.size} → ${filtered.size} networks after VPN filter", tag = TAG)
                if (filtered.size < networks.size)
                    LogDispatcher.dispatch("getAllNetworks()", "hid ${networks.size - filtered.size} VPN network(s)")
                result = filtered
            }
        }
    }

    /**
     * Hook: `ConnectivityManager.getAllNetworkInfo() → Array<NetworkInfo>?`
     *
     * Deprecated API, but many apps (and detection SDKs) still call it.
     * We filter out any [NetworkInfo] entry that represents a VPN:
     *  - `type == TYPE_VPN` (== 17), or
     *  - `typeName.equals("VPN", ignoreCase = true)`
     */
    private fun PackageParam.hookGetAllNetworkInfo() {
        ConnectivityManager::class.java.method {
            name       = "getAllNetworkInfo"
            emptyParam()
            returnType = java.lang.reflect.Array.newInstance(NetworkInfo::class.java, 0).javaClass
        }.hook {
            after {
                @Suppress("UNCHECKED_CAST")
                val infos = result as? Array<NetworkInfo> ?: return@after

                val filtered = infos.filter { info ->
                    val isVpn = info.type == TYPE_VPN ||
                                info.typeName.equals("VPN", ignoreCase = true)
                    if (isVpn) YLog.debug(msg = "getAllNetworkInfo: hiding VPN NetworkInfo (type=${info.type})", tag = TAG)
                    !isVpn
                }.toTypedArray()

                YLog.debug(msg = "getAllNetworkInfo: ${infos.size} → ${filtered.size} entries after VPN filter", tag = TAG)
                if (filtered.size < infos.size)
                    LogDispatcher.dispatch("getAllNetworkInfo()", "hid ${infos.size - filtered.size} VPN entry(s)")
                result = filtered
            }
        }
    }

    /**
     * Hook: `ConnectivityManager.getActiveNetwork() → Network?`
     *
     * If the currently active network is a VPN, return the first non-VPN network
     * from getAllNetworks() instead.  This prevents apps from discovering the VPN
     * network object via getActiveNetwork() and then querying its capabilities.
     */
    private fun PackageParam.hookGetActiveNetwork() {
        try {
            ConnectivityManager::class.java.method {
                name       = "getActiveNetwork"
                emptyParam()
                returnType = Network::class.java
            }.hook {
                after {
                    val activeNet = result as? Network ?: return@after
                    val cm = instance as? ConnectivityManager ?: return@after
                    val caps = cm.getNetworkCapabilities(activeNet) ?: return@after

                    if (!isVpnTransportPresent(caps)) return@after

                    // Active network is VPN — find a non-VPN fallback
                    val allNetworks = cm.allNetworks ?: return@after
                    for (net in allNetworks) {
                        val netCaps = cm.getNetworkCapabilities(net) ?: continue
                        if (!isVpnTransportPresent(netCaps)) {
                            YLog.debug(msg = "getActiveNetwork: VPN active, returning non-VPN fallback $net", tag = TAG)
                            result = net
                            return@after
                        }
                    }
                    // No non-VPN fallback found — leave as-is
                }
            }
        } catch (e: Throwable) {
            YLog.warn(msg = "hookGetActiveNetwork failed: ${e.message}", tag = TAG)
        }
    }

    /**
     * Hook: `ConnectivityManager.getActiveNetworkInfo() → NetworkInfo?`
     *
     * Deprecated API — suppress VPN type from the returned info.
     */
    private fun PackageParam.hookGetActiveNetworkInfo() {
        try {
            ConnectivityManager::class.java.method {
                name       = "getActiveNetworkInfo"
                emptyParam()
                returnType = NetworkInfo::class.java
            }.hook {
                after {
                    val info = result as? NetworkInfo ?: return@after
                    val isVpn = info.type == TYPE_VPN ||
                                info.typeName.equals("VPN", ignoreCase = true)
                    if (isVpn) {
                        YLog.debug(msg = "getActiveNetworkInfo: active is VPN, returning null", tag = TAG)
                        result = null
                    }
                }
            }
        } catch (e: Throwable) {
            YLog.warn(msg = "hookGetActiveNetworkInfo failed: ${e.message}", tag = TAG)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reflection helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** NET_CAPABILITY_NOT_VPN = 15 */
    private const val NET_CAPABILITY_NOT_VPN = 15

    /** TRANSPORT_WIFI = 1 */
    private const val TRANSPORT_WIFI = 1

    /**
     * Cached reflection handle for `NetworkCapabilities.mNetworkCapabilities`.
     * This field stores the capability bitmask (NET_CAPABILITY_NOT_VPN, etc.)
     */
    private val networkCapabilitiesField: java.lang.reflect.Field? by lazy {
        try {
            NetworkCapabilities::class.java
                .getDeclaredField("mNetworkCapabilities")
                .apply { isAccessible = true }
        } catch (e: Exception) {
            YLog.warn(msg = "Failed to cache mNetworkCapabilities field: ${e.message}", tag = TAG)
            null
        }
    }

    /**
     * Reads the raw `mTransportTypes` bitmask from [caps] and checks whether
     * bit [TRANSPORT_VPN] is set.
     *
     * Using reflection here (rather than [NetworkCapabilities.hasTransport])
     * prevents an infinite hook recursion when [hookGetAllNetworks] calls back
     * into the ConnectivityManager.
     */
    private fun isVpnTransportPresent(caps: NetworkCapabilities): Boolean {
        return try {
            val field = transportTypesField ?: return false
            val bitmask = field.getLong(caps)
            (bitmask and VPN_TRANSPORT_MASK) != 0L
        } catch (e: Exception) {
            YLog.warn(msg = "isVpnTransportPresent reflection failed: ${e.message}", tag = TAG)
            false
        }
    }

    /**
     * Clears the [TRANSPORT_VPN] bit in [caps]'s internal `mTransportTypes` field
     * via reflection, adds TRANSPORT_WIFI as fallback, and ensures the
     * NET_CAPABILITY_NOT_VPN bit is set in `mNetworkCapabilities`.
     *
     * This mutates [caps] in-place, which is acceptable because
     * [ConnectivityManager.getNetworkCapabilities] always returns a fresh copy.
     */
    private fun stripVpnTransport(caps: NetworkCapabilities) {
        try {
            val field = transportTypesField ?: return

            val original = field.getLong(caps)
            if ((original and VPN_TRANSPORT_MASK) == 0L) return

            // Strip VPN transport and add WiFi as fallback so transport isn't empty
            var stripped = original and VPN_TRANSPORT_MASK.inv()
            if (stripped == 0L) {
                // If VPN was the only transport, add WiFi so the network doesn't look anomalous
                stripped = stripped or (1L shl TRANSPORT_WIFI)
            }
            field.setLong(caps, stripped)

            YLog.debug(msg = "stripVpnTransport: mTransportTypes 0x${original.toString(16)} → 0x${stripped.toString(16)}", tag = TAG)

            // Also set NET_CAPABILITY_NOT_VPN in the capabilities bitmask
            addNotVpnCapability(caps)
        } catch (e: Exception) {
            YLog.warn(msg = "stripVpnTransport reflection failed: ${e.message}", tag = TAG)
        }
    }

    /**
     * Sets the NET_CAPABILITY_NOT_VPN bit (15) in `mNetworkCapabilities`.
     * VPN networks don't have this capability, so setting it makes the
     * network appear non-VPN to any code checking `hasCapability(NOT_VPN)`.
     */
    private fun addNotVpnCapability(caps: NetworkCapabilities) {
        try {
            val field = networkCapabilitiesField ?: return
            val original = field.getLong(caps)
            val notVpnBit = 1L shl NET_CAPABILITY_NOT_VPN
            if ((original and notVpnBit) != 0L) return // already set

            val patched = original or notVpnBit
            field.setLong(caps, patched)
            YLog.debug(msg = "addNotVpnCapability: mNetworkCapabilities 0x${original.toString(16)} → 0x${patched.toString(16)}", tag = TAG)
        } catch (e: Exception) {
            YLog.warn(msg = "addNotVpnCapability reflection failed: ${e.message}", tag = TAG)
        }
    }
}

