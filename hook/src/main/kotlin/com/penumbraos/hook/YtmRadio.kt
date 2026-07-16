package com.penumbraos.hook

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lightweight YouTube Music song radio.
 *
 * Calls the same unauthenticated Innertube `next` endpoint used by YTM's Android
 * client with the standard RDAMVM<videoId> mix id. This intentionally uses only
 * java.net + org.json: ytm-kt pulled Compose/AndroidX into the injected APK and
 * collided with Humane music's Media3 classes.
 *
 * Blocking; MusicHooks always invokes this from its Rx IO worker.
 */
object YtmRadio {
    private const val TAG = "PenumbraHook"
    private const val NEXT_URL = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false"
    private const val CLIENT_VERSION = "7.27.52"
    private const val CLIENT_NAME_ID = "21"
    private const val USER_AGENT = "com.google.android.apps.youtube.music/"

    data class RadioTrack(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationSec: Int,
    )

    fun radioForVideo(videoId: String, limit: Int = 25): List<RadioTrack> {
        if (videoId.isBlank()) return emptyList()
        return try {
            val body = JSONObject()
                .put("context", JSONObject().put("client", JSONObject()
                    .put("clientName", "ANDROID_MUSIC")
                    .put("clientVersion", CLIENT_VERSION)
                    .put("androidSdkVersion", 34)
                    .put("hl", "en")
                    .put("gl", "US")))
                .put("videoId", videoId)
                .put("playlistId", "RDAMVM$videoId")
                .put("enablePersistentPlaylistPanel", true)
                .put("isAudioOnly", true)

            val conn = (URL(NEXT_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("X-YouTube-Client-Name", CLIENT_NAME_ID)
                setRequestProperty("X-YouTube-Client-Version", CLIENT_VERSION)
                setRequestProperty("Origin", "https://music.youtube.com")
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(300)
                    throw java.io.IOException("YTM next HTTP $code: $detail")
                }
                val root = conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                parseQueue(root, videoId, limit)
            } finally {
                conn.disconnect()
            }.also { tracks ->
                Log.w(TAG, "YtmRadio: $videoId -> ${tracks.size} tracks, ${tracks.map { it.artist }.distinct().size} artists")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "YtmRadio.radioForVideo('$videoId') failed: ${t.message}")
            emptyList()
        }
    }

    private fun parseQueue(root: JSONObject, seedVideoId: String, limit: Int): List<RadioTrack> {
        val panel = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
            ?.optJSONObject("tabbedRenderer")
            ?.optJSONObject("watchNextTabbedResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicQueueRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("playlistPanelRenderer")
            ?.optJSONArray("contents")
            ?: findRendererArrays(root).firstOrNull()
            ?: return emptyList()

        val seen = HashSet<String>()
        val tracks = ArrayList<RadioTrack>()
        for (i in 0 until panel.length()) {
            val renderer = panel.optJSONObject(i)?.optJSONObject("playlistPanelVideoRenderer") ?: continue
            val id = renderer.optString("videoId").ifBlank {
                renderer.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("watchEndpoint")?.optString("videoId").orEmpty()
            }
            if (id.isBlank() || id == seedVideoId || !seen.add(id)) continue
            val title = runsText(renderer.optJSONObject("title")).trim()
            if (title.isBlank()) continue
            val byline = runsText(renderer.optJSONObject("longBylineText")
                ?: renderer.optJSONObject("shortBylineText"))
            val artist = byline.split(" • ", " · ", "•", "·")
                .firstOrNull()?.trim().orEmpty().ifBlank { "Unknown artist" }
            val duration = parseDuration(runsText(renderer.optJSONObject("lengthText")))
            tracks += RadioTrack(id, title, artist, duration)
            if (tracks.size >= limit.coerceIn(1, 50)) break
        }
        return tracks
    }

    /** Defensive fallback for response shape drift; returns arrays containing queue renderers. */
    private fun findRendererArrays(value: Any?): List<JSONArray> {
        val found = ArrayList<JSONArray>()
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val keys = node.keys()
                    while (keys.hasNext()) walk(node.opt(keys.next()))
                }
                is JSONArray -> {
                    var hasQueueItem = false
                    for (i in 0 until node.length()) {
                        if (node.optJSONObject(i)?.has("playlistPanelVideoRenderer") == true) hasQueueItem = true
                        walk(node.opt(i))
                    }
                    if (hasQueueItem) found += node
                }
            }
        }
        walk(value)
        return found
    }

    private fun runsText(obj: JSONObject?): String {
        val runs = obj?.optJSONArray("runs") ?: return obj?.optString("simpleText").orEmpty()
        return buildString {
            for (i in 0 until runs.length()) append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
    }

    private fun parseDuration(text: String): Int {
        val parts = text.trim().split(':').mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return 0
        return parts.fold(0) { total, part -> total * 60 + part }
    }
}
