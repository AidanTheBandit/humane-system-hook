package com.penumbraos.hook

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Minimal client for the real, unauthenticated YouTube Music Innertube API.
 *
 * Mirrors ytmusicapi's WEB_REMIX search -> watch radio -> continuation flow while
 * keeping Python and Android UI dependencies out of the injected music process.
 * Blocking; MusicHooks invokes this from its Rx IO worker.
 */
object YtmRadio {
    private const val TAG = "PenumbraHook"
    private const val API = "https://music.youtube.com/youtubei/v1/"
    private const val SONGS_FILTER = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    data class RadioTrack(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationSec: Int,
    )

    internal fun searchBody(query: String): JSONObject = baseBody()
        .put("query", query)
        .put("params", SONGS_FILTER)

    internal fun radioBody(videoId: String): JSONObject = baseBody()
        .put("videoId", videoId)
        .put("playlistId", "RDAMVM$videoId")
        .put("params", "wAEB")
        .put("enablePersistentPlaylistPanel", true)
        .put("isAudioOnly", true)
        .put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")

    private fun baseBody(): JSONObject {
        val format = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return JSONObject().put(
            "context",
            JSONObject()
                .put(
                    "client",
                    JSONObject()
                        .put("clientName", "WEB_REMIX")
                        .put("clientVersion", "1.${format.format(Date())}.01.00")
                        .put("hl", "en")
                        .put("gl", "US"),
                )
                .put("user", JSONObject()),
        )
    }

    /** Search the YTM song catalogue, then start YTM's own radio for its exact seed. */
    fun radioForQuery(query: String, limit: Int = 50): List<RadioTrack> {
        if (query.isBlank()) return emptyList()
        return try {
            val seeds = parseSearch(post("search", searchBody(query)), 10)
            val seed = seeds.firstOrNull() ?: return emptyList()
            val tracks = radioForVideo(seed.videoId, limit)
            Log.w(
                TAG,
                "YtmRadio: '$query' seed=${seed.videoId} '${seed.title}' -> " +
                    "${tracks.size} tracks, ${tracks.map { it.artist }.distinct().size} artists",
            )
            tracks
        } catch (t: Throwable) {
            Log.w(TAG, "YtmRadio.radioForQuery('$query') failed: ${t.message}")
            emptyList()
        }
    }

    fun radioForVideo(videoId: String, limit: Int = 50): List<RadioTrack> {
        if (videoId.isBlank()) return emptyList()
        val max = limit.coerceIn(1, 100)
        val body = radioBody(videoId)
        var response = post("next", body)
        var panel = initialPanel(response) ?: return emptyList()
        val seen = LinkedHashSet<String>()
        val tracks = ArrayList<RadioTrack>()

        while (true) {
            parseQueueContents(panel.optJSONArray("contents") ?: JSONArray()).forEach { track ->
                if (seen.add(track.videoId) && tracks.size < max) tracks += track
            }
            if (tracks.size >= max) break
            val token = continuationToken(panel) ?: break
            response = post("next", body, token)
            panel = response.optJSONObject("continuationContents")
                ?.optJSONObject("playlistPanelContinuation") ?: break
        }
        return tracks
    }

    private fun post(endpoint: String, body: JSONObject, continuation: String? = null): JSONObject {
        val suffix = if (continuation == null) {
            "?alt=json"
        } else {
            val token = URLEncoder.encode(continuation, "UTF-8")
            "?alt=json&ctoken=$token&continuation=$token"
        }
        val conn = (URL(API + endpoint + suffix).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Origin", "https://music.youtube.com")
            setRequestProperty("Cookie", "SOCS=CAI")
        }
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(500)
                throw java.io.IOException("YTM $endpoint HTTP $code: $detail")
            }
            conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            conn.disconnect()
        }
    }

    internal fun parseSearch(root: JSONObject, limit: Int): List<RadioTrack> {
        val renderers = ArrayList<JSONObject>()
        walk(root) { obj ->
            obj.optJSONObject("musicResponsiveListItemRenderer")?.let(renderers::add)
        }
        val seen = HashSet<String>()
        val tracks = ArrayList<RadioTrack>()
        for (renderer in renderers) {
            val columns = renderer.optJSONArray("flexColumns") ?: continue
            val titleRuns = columnRuns(columns, 0)
            val detailRuns = columnRuns(columns, 1)
            val title = titleRuns.optJSONObject(0)?.optString("text").orEmpty().trim()
            val videoId = findVideoId(renderer)
            if (videoId.length != 11 || title.isBlank() || !seen.add(videoId)) continue

            val details = (0 until detailRuns.length())
                .mapNotNull { detailRuns.optJSONObject(it)?.optString("text") }
                .map { it.trim() }
                .filter { it.isNotBlank() && it !in setOf("•", "·", "Song") }
            val durationText = details.lastOrNull { DURATION.matches(it) }.orEmpty()
            val artist = details.firstOrNull { !DURATION.matches(it) }
                .orEmpty().ifBlank { "Unknown artist" }
            tracks += RadioTrack(videoId, title, artist, parseDuration(durationText))
            if (tracks.size >= limit.coerceIn(1, 50)) break
        }
        return tracks
    }

    private fun columnRuns(columns: JSONArray, index: Int): JSONArray = columns
        .optJSONObject(index)
        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
        ?.optJSONObject("text")
        ?.optJSONArray("runs") ?: JSONArray()

    private fun findVideoId(root: JSONObject): String {
        var found = ""
        walk(root) { obj ->
            if (found.isEmpty()) {
                val id = obj.optJSONObject("watchEndpoint")?.optString("videoId").orEmpty()
                if (id.length == 11) found = id
            }
        }
        return found
    }

    private fun initialPanel(root: JSONObject): JSONObject? = root.optJSONObject("contents")
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
        ?: findObject(root, "playlistPanelRenderer")

    private fun parseQueueContents(contents: JSONArray): List<RadioTrack> {
        val tracks = ArrayList<RadioTrack>()
        for (i in 0 until contents.length()) {
            val item = contents.optJSONObject(i) ?: continue
            val renderer = item.optJSONObject("playlistPanelVideoRenderer")
                ?: item.optJSONObject("playlistPanelVideoWrapperRenderer")
                    ?.optJSONObject("primaryRenderer")
                    ?.optJSONObject("playlistPanelVideoRenderer")
                ?: continue
            if (renderer.has("unplayableText")) continue
            val id = renderer.optString("videoId")
            val title = runsText(renderer.optJSONObject("title")).trim()
            if (id.length != 11 || title.isBlank()) continue
            val byline = runsText(
                renderer.optJSONObject("longBylineText")
                    ?: renderer.optJSONObject("shortBylineText"),
            )
            val artist = byline.split(" • ", " · ", "•", "·")
                .firstOrNull()?.trim().orEmpty().ifBlank { "Unknown artist" }
            val duration = parseDuration(runsText(renderer.optJSONObject("lengthText")))
            tracks += RadioTrack(id, title, artist, duration)
        }
        return tracks
    }

    private fun continuationToken(panel: JSONObject): String? {
        val continuations = panel.optJSONArray("continuations") ?: return null
        for (i in 0 until continuations.length()) {
            val item = continuations.optJSONObject(i) ?: continue
            item.optJSONObject("nextContinuationData")?.optString("continuation")
                ?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun runsText(obj: JSONObject?): String {
        val runs = obj?.optJSONArray("runs") ?: return obj?.optString("simpleText").orEmpty()
        return buildString {
            for (i in 0 until runs.length()) append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
    }

    private val DURATION = Regex("^(?:\\d+:)?\\d{1,2}:\\d{2}$")

    private fun parseDuration(text: String): Int {
        val parts = text.trim().split(':').mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return 0
        return parts.fold(0) { total, part -> total * 60 + part }
    }

    private fun findObject(root: Any?, key: String): JSONObject? {
        var found: JSONObject? = null
        walk(root) { obj -> if (found == null) found = obj.optJSONObject(key) }
        return found
    }

    private fun walk(value: Any?, visit: (JSONObject) -> Unit) {
        when (value) {
            is JSONObject -> {
                visit(value)
                val keys = value.keys()
                while (keys.hasNext()) walk(value.opt(keys.next()), visit)
            }
            is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i), visit)
        }
    }
}
