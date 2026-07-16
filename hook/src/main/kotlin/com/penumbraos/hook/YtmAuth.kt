package com.penumbraos.hook

/**
 * Retained for config compatibility after removing the ytm-kt runtime from the
 * injected APK. PipePipe/NewPipe remains the active YouTube playback backend.
 */
object YtmAuth {
    fun radioLimit(): Int = 25
}
