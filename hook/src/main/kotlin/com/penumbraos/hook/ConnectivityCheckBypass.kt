package com.penumbraos.hook

import android.util.Log

/**
 * Block Humane's custom connectivity check.
 *
 * NetworkUtils.validateNetworkConnection(Network) makes an HTTP GET to
 * http://connectivity-check.prod.humane.cloud and expects HTTP 204.
 * The result is used only for WiFi status display in the UI
 */
object ConnectivityCheckBypass {

    private const val TAG = "PenumbraHook"

    fun install(cl: ClassLoader) {
        val className = "humaneinternal.system.network.NetworkUtils"
        val clazz = try {
            cl.loadClass(className)
        } catch (_: ClassNotFoundException) {
            Log.w(TAG, "  $className not found, skipping connectivity check bypass")
            return
        }

        // Load the NetworkRequestResult enum to get the CONNECTED constant
        val resultEnumClass = try {
            cl.loadClass("$className\$NetworkRequestResult")
        } catch (_: ClassNotFoundException) {
            Log.w(TAG, "  $className\$NetworkRequestResult not found, skipping")
            return
        }

        // TODO: Make this actually work
        val connectedValue = try {
            val valueOf = resultEnumClass.getDeclaredMethod("valueOf", String::class.java)
            valueOf.invoke(null, "CONNECTED")
        } catch (t: Throwable) {
            Log.e(TAG, "  Could not resolve NetworkRequestResult.CONNECTED: ${t.message}")
            return
        }

        // validateNetworkConnection(android.net.Network) -> NetworkRequestResult
        HookUtils.hookMethodBefore(
            clazz,
            "validateNetworkConnection",
            arrayOf(android.net.Network::class.java),
        ) { param ->
            param.result = connectedValue
        }

        Log.i(TAG, "  Connectivity check bypass installed (always CONNECTED)")
    }
}
