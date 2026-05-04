package com.penumbraos.hook

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * Bypass synchronous IWlcService.disableTx(...) binder calls.
 *
 * Method-name caveat:
 *   The IWlcService AIDL has two overloads. In the *actual* installed bytecode
 *   (verified via `dexdump` on humane_photography.apk's IWlcService$Stub$Proxy)
 *   they are emitted with DIFFERENT method names — not as Java overloads:
 *     - disableTx(int)            -> ()B
 *     - disableTx_2(String, int)  -> ()B    <-- the photography hot path
 *   So a name-equality check against "disableTx" misses the one we actually
 *   need. We accept any method whose name starts with "disableTx".
 *
 * Side effects:
 *   The wireless charger keeps transmitting during capture. The pin already
 *   handles concurrent charging fine for short durations; the original guard
 *   exists to avoid magnetic interference with the camera, which is not a
 *   concern in our bench/dev usage. If image quality regresses, revisit.
 */
object WirelessChargingBypass {

    private const val TAG = "PenumbraHook"
    private const val PROXY_CLASS = "humane.wlc.IWlcService\$Stub\$Proxy"

    @Volatile
    private var installed = false

    fun install(cl: ClassLoader) {
        if (installed) return

        val proxyClass = try {
            cl.loadClass(PROXY_CLASS)
        } catch (_: ClassNotFoundException) {
            Log.w(TAG, "  $PROXY_CLASS not found, skipping WLC bypass")
            return
        }

        var hooked = 0
        for (method in proxyClass.declaredMethods) {
            // Accept both `disableTx` and `disableTx_2` (see method-name caveat above).
            if (!method.name.startsWith("disableTx")) continue
            try {
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // Both overloads return byte; (byte) 0 = success in WlcResult.
                        param.result = 0.toByte()
                    }
                })
                val sig = method.parameterTypes.joinToString(",") { it.simpleName }
                Log.w(TAG, "  Hooked IWlcService\$Stub\$Proxy.${method.name}($sig) — short-circuited")
                hooked++
            } catch (t: Throwable) {
                Log.e(TAG, "  Failed to hook ${method.name} overload: ${t.message}")
            }
        }

        if (hooked == 0) {
            Log.w(TAG, "  No disableTx* overloads found on $PROXY_CLASS")
        } else {
            installed = true
            Log.w(TAG, "  WLC disableTx bypass installed ($hooked overload(s))")
        }
    }
}
