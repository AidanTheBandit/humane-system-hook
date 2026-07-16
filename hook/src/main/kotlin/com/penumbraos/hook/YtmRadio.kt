package com.penumbraos.hook

import android.util.Log
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import kotlinx.coroutines.runBlocking

/**
 * YouTube Music "song radio" for [MusicHooks]. Given a starting track's YouTube video
 * id, returns the radio queue YouTube Music itself would build (the "start radio"
 * continuation), so a played song flows into a real YTM radio instead of NewPipe's
 * generic related-streams list.
 *
 * Runs ytm-kt in-process. Optionally authenticated with the user's account cookie
 * (see [YtmAuth]) for personalized recommendations; otherwise unauthenticated song
 * radio. All failures degrade to an empty list so callers fall back to NewPipe.
 */
object YtmRadio {
    private const val TAG = "PenumbraHook"

    private val api: YoutubeiApi by lazy { YoutubeiApi() }

    data class RadioTrack(val videoId: String, val title: String, val artist: String)

    /**
     * Radio queue seeded by [videoId] (a YouTube video id). Blocking — call off the
     * main thread (MusicHooks already resolves collections on an IO worker). Returns
     * an empty list on any error/empty result.
     */
    fun radioForVideo(videoId: String, limit: Int = YtmAuth.radioLimit()): List<RadioTrack> {
        if (videoId.isBlank()) return emptyList()
        return try {
            runBlocking {
                // Refresh auth each call so the SAPISIDHASH stays valid; null => unauthenticated.
                api.userAuthState = YtmAuth.buildAuthState(api)
                val radio = api.SongRadio.getSongRadio(videoId, null).getOrThrow()
                radio.items.mapNotNull { song ->
                    val id = song.id.ifBlank { return@mapNotNull null }
                    RadioTrack(
                        videoId = id,
                        title = song.name.orEmpty(),
                        artist = song.artists?.firstOrNull()?.name.orEmpty(),
                    )
                }.take(limit)
            }.also { Log.w(TAG, "YtmRadio: $videoId -> ${it.size} radio tracks") }
        } catch (t: Throwable) {
            Log.w(TAG, "YtmRadio.radioForVideo('$videoId') failed: ${t.message}")
            emptyList()
        }
    }
}
