package com.penumbraos.hook

import android.util.Log

/**
 * Optional YouTube Music radio shim.
 *
 * ytm-kt pulls the full Compose/AndroidX UI stack into the injected hook APK. Those
 * classes shadow Humane music's own AndroidX/Media3 classes in the shared classloader,
 * causing ExoPlayer to crash with a NoSuchMethodError. PipePipe/NewPipe related
 * streams are already the caller's fallback, so keep this API surface but return no
 * YTM-specific tracks.
 */
object YtmRadio {
    private const val TAG = "PenumbraHook"

    data class RadioTrack(val videoId: String, val title: String, val artist: String)

    fun radioForVideo(videoId: String, limit: Int = 25): List<RadioTrack> {
        if (videoId.isNotBlank()) {
            Log.d(TAG, "YtmRadio disabled; using PipePipe related-stream fallback")
        }
        return emptyList()
    }
}
