package com.penumbraos.hook

import android.util.Log

/**
 * Hooks for the photography experience APK (package: humane.experience.photography).
 *
 * The photography process runs the upload pipeline (MemoryUploadWorkerImpl,
 * AssetUploadWorkerImpl) which encrypts data via DataProtectorWrapper before
 * sending it to the server. We install the encryption bypass hooks here so
 * thumbnails, locations, and file uploads arrive as plaintext.
 */
object PhotographyHooks {

    private const val TAG = "PenumbraHook"

    fun install(cl: ClassLoader) {
        Log.w(TAG, "Installing photography hooks...")

        TcmSilencer.install(cl)
        ChannelFactoryBypass.install(cl)
        DataProtectorBypass.install(cl)
        WirelessChargingBypass.install(cl)

        Log.w(TAG, "Photography hooks installed")
    }
}
