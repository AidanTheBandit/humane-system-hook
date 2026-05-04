package com.penumbraos.hook

import android.util.Log

/**
 * Hooks for the system navigation experience APK (package: humane.experience.systemnavigation).
 */
object SystemNavigationHooks {

    private const val TAG = "PenumbraHook"

    fun install(cl: ClassLoader) {
        Log.w(TAG, "Installing system navigation hooks...")

        TcmSilencer.install(cl)

        ConnectivityCheckBypass.install(cl)

        Log.w(TAG, "System navigation hooks installed")
    }
}
