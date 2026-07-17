package com.penumbraos.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.search.filter.FilterItem
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy
import java.util.Optional
import java.util.concurrent.Callable

/**
 * MVP: replace the dead Tidal music backend with NewPipe (YouTube), reusing the
 * music app's existing ExoPlayer and UI. No extra player APK.
 *
 * Strategy (see [[humane-music-newpipe-feasibility]]): hook TidalProvider's methods
 * and return our own results, since MediaProvider/MediaCollection are abstract
 * classes we can't subclass — but MediaItem / MediaItem.PlaybackInfo /
 * MediaCollectionQuery are INTERFACES (dynamic Proxy), and MediaItemCollection is a
 * concrete class we instantiate.
 *
 * Flow: queryWithTrackName(q) -> NewPipe YouTube search -> MediaItemCollection of
 * MediaItem proxies (getId() carries the YouTube watch URL). playbackInfoForMediaItem(item)
 * -> NewPipe stream extraction -> PlaybackInfo.playbackUri() = direct audio URL ->
 * MediaManager hands it to ExoPlayer.setUri(). Network runs on Single.subscribeOn(io).
 *
 * MVP scope: queryWithTrackName/queryTopHits/queryWithArtistName + playback. The other
 * ~14 query methods still fall through to (dead) Tidal until implemented.
 */
object MusicHooks {
    private const val TAG = "PenumbraHook"
    private const val YOUTUBE_SERVICE_ID = 0

    @Volatile private var inited = false
    private lateinit var cl: ClassLoader

    // Humane interface/class handles (loaded from the music process classloader).
    private lateinit var mediaItemCls: Class<*>
    private lateinit var playbackInfoCls: Class<*>
    private lateinit var mcqCls: Class<*>
    private lateinit var mediaItemCollectionCtor: java.lang.reflect.Constructor<*>

    // RxJava (bundled in the music app).
    private lateinit var singleCls: Class<*>
    private lateinit var schedulerCls: Class<*>
    private lateinit var ioScheduler: Any
    private lateinit var mainScheduler: Any   // deliver results on main (ExoPlayer thread)

    // Title/artist of the track being resolved — stamped onto the player's MediaItem so
    // Bluetooth/AVRCP (car head unit) shows song info.
    @Volatile private var currentTitle: String? = null
    @Volatile private var currentArtist: String? = null
    @Volatile private var currentDurationMs: Long = 0L

    // Spotify track durations in milliseconds, keyed by spotify: uri. The Humane MediaItem
    // only carries whole-second duration, so we keep the exact ms here to size the WAV stream
    // precisely (a round-trip through seconds truncates up to ~1s off the end of each track).
    private val spotifyDurationMs = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun install(classLoader: ClassLoader) {
        cl = classLoader
        try {
            initNewPipe()

            mediaItemCls = cl.loadClass("humane.experience.music.MediaItem")
            playbackInfoCls = cl.loadClass("humane.experience.music.MediaItem\$PlaybackInfo")
            mcqCls = cl.loadClass("humane.experience.music.MediaCollectionQuery")
            mediaItemCollectionCtor = cl.loadClass("humane.experience.music.MediaItemCollection")
                .getConstructor(List::class.java)

            singleCls = cl.loadClass("io.reactivex.rxjava3.core.Single")
            schedulerCls = cl.loadClass("io.reactivex.rxjava3.core.Scheduler")
            ioScheduler = cl.loadClass("io.reactivex.rxjava3.schedulers.Schedulers")
                .getMethod("io").invoke(null)!!
            mainScheduler = cl.loadClass("io.reactivex.rxjava3.android.schedulers.AndroidSchedulers")
                .getMethod("mainThread").invoke(null)!!

            val provider = cl.loadClass("humane.experience.music.provider.tidal.TidalProvider")
            // 1-arg String queries — all map to a YouTube search of the term.
            // A named track plays the song then flows into a radio of similar tracks
            // (radio_autoplay, default on); the rest stay plain multi-result searches.
            hookStringQuery(provider, "queryWithTrackName", asRadio = true)
            for (name in listOf(
                "queryTopHits", "queryWithArtistName",
                "queryWithAlbumName", "queryWithGenreName",
            )) hookStringQuery(provider, name)
            // Playlist name resolves an imported Spotify playlist (else falls back to search).
            hookPlaylistQuery(provider)
            // Radio / recommendations = NewPipe related streams of the current track.
            hookRadioQuery(provider, "queryRadioWithTrackId")
            hookRadioQuery(provider, "queryRecommendationsWithTrackId")
            // Recents / "play these track ids" — queryWithTrackIds(List) goes to dead Tidal.
            // Rebuild from OUR ids (spotify:/youtube/ytsearch) so Recents populates + plays.
            hookTrackIdsQuery(provider)
            // 2-arg (term, artist) — search the combination.
            for (name in listOf("queryWithAlbumAndArtistName", "queryTopHitsWithArtistName")) {
                hookTwoArgQuery(provider, name)
            }
            // 0-arg browse — no Tidal data, fall back to a popular-music search.
            hookNoArgQuery(provider, "queryFavoriteTracks", "popular songs")
            hookNoArgQuery(provider, "queryFeaturedPlaylist", "top hits this week")
            hookPlayback(provider)
            hookMediaPlayerMetadata()
            registerShuffleReceiver()
            registerRepeatReceiver()
            // NOTE: librespot is now hosted in ironman (SpotifyControl) — the music process
            // is on-demand/dead so it couldn't reliably host it. Don't start it here too
            // (two instances = two "Ai Pin" Connect devices).
            Log.w(TAG, "MusicHooks installed (NewPipe provider, MVP)")
        } catch (t: Throwable) {
            Log.e(TAG, "MusicHooks install failed: ${t.message}", t)
        }
    }

    private fun initNewPipe() {
        if (inited) return
        NewPipe.init(NewPipeDownloader())
        inited = true
        Log.w(TAG, "  NewPipe initialized")
    }

    private fun youtube(): StreamingService = NewPipe.getService(YOUTUBE_SERVICE_ID)

    /** music.youtube.com -> www.youtube.com (NewPipe's YouTube link handler rejects the music host). */
    private fun normalizeUrl(url: String): String = url.replace("music.youtube.com", "www.youtube.com")

    /** youtube() for YT URLs (the proven matcher), getServiceByUrl only for non-YT (SoundCloud). */
    private fun serviceFor(url: String): StreamingService =
        if (url.contains("youtube.com") || url.contains("youtu.be")) youtube() else NewPipe.getServiceByUrl(url)

    /** Extract the 11-char YouTube video id from a watch/youtu.be URL, or null for non-YT. */
    private fun youtubeVideoId(url: String): String? {
        val u = normalizeUrl(url)
        Regex("[?&]v=([A-Za-z0-9_-]{11})").find(u)?.let { return it.groupValues[1] }
        Regex("youtu\\.be/([A-Za-z0-9_-]{11})").find(u)?.let { return it.groupValues[1] }
        return null
    }

    /** Real YTM catalogue search + automix radio, converted to Humane MediaItems. */
    private fun ytmRadioItems(query: String): List<Any> {
        return YtmRadio.radioForQuery(query).map {
            buildMediaItem(
                "https://www.youtube.com/watch?v=${it.videoId}",
                it.title,
                it.artist,
                it.durationSec,
            )
        }
    }

    // ---- hooks ----

    private fun hookStringQuery(provider: Class<*>, methodName: String, asRadio: Boolean = false) {
        try {
            val m = provider.getDeclaredMethod(methodName, String::class.java)
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val query = param.args.getOrNull(0) as? String ?: return
                    // For a named track, "radio:" makes the queue = the song + similar tracks
                    // (autoplay). Skip if already a radio query or the user disabled it.
                    val effective = if (asRadio && VoiceConfig.radioAutoplay() && !query.startsWith("radio:"))
                        "radio:$query" else query
                    Log.w(TAG, "MusicHooks: $methodName('$query') -> NewPipe ('$effective')")
                    param.result = buildCollectionQuery(effective)
                }
            })
            Log.w(TAG, "  Hooked TidalProvider.$methodName")
        } catch (t: Throwable) {
            Log.e(TAG, "  hook $methodName failed: ${t.message}")
        }
    }

    private fun hookTwoArgQuery(provider: Class<*>, methodName: String) {
        try {
            val m = provider.getDeclaredMethod(methodName, String::class.java, String::class.java)
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val a = cleanMusicQuery(param.args.getOrNull(0) as? String ?: return)
                    val b = cleanMusicQuery(param.args.getOrNull(1) as? String ?: "")
                    // Humane sometimes includes the artist in both arguments.
                    val query = when {
                        b.isBlank() -> a
                        a.contains(b, ignoreCase = true) -> a
                        b.contains(a, ignoreCase = true) -> b
                        else -> "$a $b"
                    }.trim()
                    Log.w(TAG, "MusicHooks: $methodName('$query') -> NewPipe")
                    param.result = buildCollectionQuery(query)
                }
            })
            Log.w(TAG, "  Hooked TidalProvider.$methodName")
        } catch (t: Throwable) {
            Log.e(TAG, "  hook $methodName failed: ${t.message}")
        }
    }

    private fun hookNoArgQuery(provider: Class<*>, methodName: String, defaultQuery: String) {
        try {
            val m = provider.getDeclaredMethod(methodName)
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Log.w(TAG, "MusicHooks: $methodName() -> NewPipe('$defaultQuery')")
                    param.result = buildCollectionQuery(defaultQuery)
                }
            })
            Log.w(TAG, "  Hooked TidalProvider.$methodName")
        } catch (t: Throwable) {
            Log.e(TAG, "  hook $methodName failed: ${t.message}")
        }
    }

    private fun hookPlayback(provider: Class<*>) {
        try {
            val m = provider.getDeclaredMethod("playbackInfoForMediaItem", mediaItemCls)
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val item = param.args.getOrNull(0) ?: return
                    val id = runCatching { mediaItemCls.getMethod("getId").invoke(item) as? String }
                        .getOrNull() ?: return
                    // Capture title/artist so we can stamp the player's MediaItem metadata
                    // (for Bluetooth/AVRCP song info on the car).
                    runCatching {
                        currentTitle = mediaItemCls.getMethod("getTitle").invoke(item) as? String
                        @Suppress("UNCHECKED_CAST")
                        currentArtist = (mediaItemCls.getMethod("getArtistNames").invoke(item) as? List<String>)?.firstOrNull()
                        currentDurationMs = ((mediaItemCls.getMethod("getDuration").invoke(item) as? Number)
                            ?.toLong() ?: 0L).coerceAtLeast(0L) * 1000L
                    }
                    // Spotify: getId() is a spotify: uri -> play it on "Ai Pin" via the local
                    // librespot stream (no NewPipe extraction). The ironman server does the
                    // Web-API play + sync when ExoPlayer connects to this URL.
                    if (id.startsWith("spotify:")) {
                        // Prefer the exact ms captured at search time; fall back to the item's
                        // whole-second duration (older/rebuilt items not in the map).
                        val ms = spotifyDurationMs[id]
                            ?: (runCatching { mediaItemCls.getMethod("getDuration").invoke(item) as? Int }.getOrNull()?.times(1000) ?: 0)
                        Log.w(TAG, "MusicHooks: playbackInfoForMediaItem id=$id -> Spotify stream (${ms}ms)")
                        param.result = ioThenMain(Callable { buildPlaybackInfo(spotifyStreamUrl(id, ms)) })
                        return
                    }
                    Log.w(TAG, "MusicHooks: playbackInfoForMediaItem id=$id -> NewPipe extract")
                    param.result = ioThenMain(Callable { buildPlaybackInfo(extractAudioUrl(id)) })
                }
            })
            Log.w(TAG, "  Hooked TidalProvider.playbackInfoForMediaItem")
        } catch (t: Throwable) {
            Log.e(TAG, "  hookPlayback failed: ${t.message}")
        }
    }

    // ---- NewPipe ----

    private fun search(query: String): List<StreamInfoItem> {
        val cleanedQuery = cleanMusicQuery(query)
        // PipePipe REQUIRES a content filter — its YoutubeFilters.evaluateSelectedFilters throws
        // "we have a problem here" if getSearchExtractor(query) is called with no filter selected
        // (vanilla NewPipe tolerated it). So pass a content FilterItem ("videos"/"music_songs").
        val extractor = when (VoiceConfig.musicSource()) {
            // SoundCloud: the 1-arg getSearchExtractor injects a DEFAULT sort filter whose item
            // type isn't SoundcloudSortFilterItem -> ClassCastException in evaluateSelectedSortFilters
            // (SoundcloudFilters.java:117). Pass an explicit content filter + EMPTY sort list.
            "soundcloud" -> NewPipe.getService(1).let { it.getSearchExtractor(cleanedQuery, contentFilter(it, "tracks", "all"), emptyList()) }
            "youtube" -> youtube().let { it.getSearchExtractor("$cleanedQuery music", contentFilter(it, "videos", "all"), emptyList()) }
            else -> youtube().let { it.getSearchExtractor(cleanedQuery, contentFilter(it, "music_songs", "videos", "all"), emptyList()) } // ytmusic
        }
        extractor.fetchPage()
        val results = extractor.initialPage.items.filterIsInstance<StreamInfoItem>()
        return rankSearchResults(cleanedQuery, results)
    }

    /** Repair common Pin speech errors and normalize whitespace before searching. */
    private fun cleanMusicQuery(raw: String): String = raw
        .replace(Regex("\\belectric high orchestra\\b", RegexOption.IGNORE_CASE), "electric light orchestra")
        .replace(Regex("\\bbi\\b", RegexOption.IGNORE_CASE), "by")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Prefer exact requested title/artist over YouTube's default ranking. */
    private fun rankSearchResults(query: String, results: List<StreamInfoItem>): List<StreamInfoItem> {
        val tokens = normalizeSearchText(query)
            .split(' ')
            .filter { it.length > 1 && it !in setOf("by", "the", "music", "song", "track") }
            .distinct()
        if (tokens.isEmpty()) return results

        val titleToken = tokens.first()
        return results.withIndex().sortedWith(
            compareByDescending<IndexedValue<StreamInfoItem>> { indexed ->
                val item = indexed.value
                val title = normalizeSearchText(item.name ?: "")
                val haystack = "$title ${normalizeSearchText(item.uploaderName ?: "")}".trim()
                val matched = tokens.count { token ->
                    Regex("(?:^| )${Regex.escape(token)}(?: |$)").containsMatchIn(haystack)
                }
                matched * 20 +
                    (if (matched == tokens.size) 100 else 0) +
                    (if (Regex("(?:^| )${Regex.escape(titleToken)}(?: |$)").containsMatchIn(title)) 80 else 0)
            }.thenBy { it.index }
        ).map { it.value }
    }

    private fun normalizeSearchText(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Resolve a search content filter by name (first match) from the service's filter set. */
    private fun contentFilter(service: StreamingService, vararg names: String): List<FilterItem> {
        return try {
            val all = service.searchQHFactory.availableContentFilter
                ?.filterGroups?.flatMap { it.filterItems.asList() } ?: return emptyList()
            for (n in names) all.firstOrNull { it.name.equals(n, ignoreCase = true) }?.let { return listOf(it) }
            emptyList()
        } catch (t: Throwable) {
            Log.e(TAG, "  contentFilter('${names.joinToString()}') failed: ${t.message}"); emptyList()
        }
    }

    /**
     * Extract a single playable audio URL (Zenith's proven approach): use the stream
     * extractor, keep only direct-URL audio streams, prefer PROGRESSIVE_HTTP (a single
     * file ExoPlayer can play via setUri — not DASH segments), highest bitrate.
     */
    private fun extractAudioUrl(id: String): String {
        // Playlist tracks carry a lazy "ytsearch:<track> <artist>" id (no upfront search);
        // resolve it to a video URL at play time.
        val rawUrl = if (id.startsWith("ytsearch:")) {
            val q = id.removePrefix("ytsearch:")
            search(q).firstOrNull()?.url ?: throw java.io.IOException("no result for $q")
        } else id
        // YouTube Music results are valid YouTube videos, but NewPipe's YouTube link
        // handler REJECTS the music.youtube.com host (returns no extractor -> "no audio
        // streams"). Normalize to www.youtube.com so the regular extractor accepts it.
        val videoUrl = normalizeUrl(rawUrl)
        // Do NOT cache the resolved stream URL. googlevideo URLs carry an `&expire=` and
        // stop working once it passes (well under an hour), so a cached URL hands ExoPlayer
        // a dead link -> silent 403 -> "can't play" -> LLM. The earlier 3h-TTL cache (added
        // to cut Rhino-deciphering heat) is exactly what broke playback. Always extract
        // fresh — same as the known-good build. (Heat: re-add an expire-aware cache later.)
        // YouTube stream deciphering is intermittently flaky (sometimes returns no
        // usable progressive stream), so retry with a fresh extractor a few times.
        var lastErr: Throwable? = null
        repeat(3) { attempt ->
            try {
                // Use youtube() directly for YT URLs (the proven path) — getServiceByUrl
                // is a weaker matcher; only fall back to it for non-YT (SoundCloud) URLs.
                val ex = serviceFor(videoUrl).getStreamExtractor(videoUrl)
                ex.fetchPage()
                val all = ex.audioStreams
                val urlStreams = all.filter { it.isUrl }
                if (urlStreams.isEmpty()) {
                    Log.w(TAG, "  no isUrl audio: total=${all.size} " +
                        "delivery=[${all.joinToString(",") { runCatching { it.deliveryMethod.name }.getOrDefault("?") }}]")
                }
                if (urlStreams.isNotEmpty()) {
                    val progressive = urlStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                    val sorted = progressive.ifEmpty { urlStreams }.sortedBy { it.averageBitrate }
                    // Pick by quality setting: 0=low(first) 1=medium(middle) 2=high(last).
                    val idx = when (VoiceConfig.streamQuality()) {
                        0 -> 0
                        2 -> sorted.lastIndex
                        else -> sorted.size / 2
                    }.coerceIn(0, sorted.lastIndex)
                    return sorted[idx].content
                }
                lastErr = java.io.IOException("no audio streams (attempt ${attempt + 1})")
            } catch (t: Throwable) {
                lastErr = t
            }
            Log.w(TAG, "  extract attempt ${attempt + 1} failed: ${lastErr?.message}")
        }
        throw lastErr ?: java.io.IOException("no audio stream for $videoUrl")
    }

    // ---- Humane type builders (reflection / proxy) ----

    /** MediaCollectionQuery proxy whose collection() lazily searches the selected source off-thread. */
    private fun buildCollectionQuery(query: String): Any {
        if (VoiceConfig.musicSource() == "spotify") return buildSpotifyCollectionQuery(query)
        return Proxy.newProxyInstance(mcqCls.classLoader, arrayOf(mcqCls)) { proxy, method, args ->
            when (method.name) {
                "collection" -> ioThenMain(Callable {
                    val items = if (query.startsWith("radio:")) {
                        // YTM chooses the exact seed from its song catalogue, then creates its
                        // own automix queue and continuations. NewPipe is only the fallback.
                        val seed = query.removePrefix("radio:").trim()
                        val ytm = ytmRadioItems(seed)
                        if (ytm.isNotEmpty()) {
                            ytm.take(50)
                        } else {
                            val first = search(seed).firstOrNull()
                            val firstItem = first?.let { buildMediaItem(it) }
                            val related = first?.let {
                                val u = normalizeUrl(it.url)
                                runCatching {
                                    StreamInfo.getInfo(serviceFor(u), u)
                                        .relatedItems.filterIsInstance<StreamInfoItem>()
                                }.getOrDefault(emptyList())
                            } ?: emptyList()
                            (listOfNotNull(firstItem) + related.map { buildMediaItem(it) }).take(30)
                        }
                    } else {
                        search(query).take(25).map { buildMediaItem(it) }
                    }
                    Log.w(TAG, "  collection '$query' -> ${items.size} tracks")
                    mediaItemCollectionCtor.newInstance(items)
                })
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "NewPipeCollectionQuery($query)"
                else -> defaultValue(method.returnType)
            }
        }
    }

    // ---- Spotify (rides the same pipeline; audio via librespot local stream) ----

    /** Collection of Spotify search results (first hit + the rest as a queue). */
    private fun buildSpotifyCollectionQuery(rawQuery: String): Any =
        Proxy.newProxyInstance(mcqCls.classLoader, arrayOf(mcqCls)) { proxy, method, args ->
            when (method.name) {
                "collection" -> ioThenMain(Callable {
                    val q = rawQuery.removePrefix("radio:").trim()
                    val tracks = SpotifyApi.searchTracks(q, 25)
                    Log.w(TAG, "  Spotify collection '$q' -> ${tracks.size} tracks")
                    mediaItemCollectionCtor.newInstance(tracks.map { spotifyMediaItem(it) })
                })
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "SpotifyCollectionQuery($rawQuery)"
                else -> defaultValue(method.returnType)
            }
        }

    /** Collection of a Spotify playlist's tracks. */
    private fun buildSpotifyPlaylistQuery(name: String): Any =
        Proxy.newProxyInstance(mcqCls.classLoader, arrayOf(mcqCls)) { proxy, method, args ->
            when (method.name) {
                "collection" -> ioThenMain(Callable {
                    val tracks = SpotifyApi.playlistTracks(name, 50)
                        .ifEmpty { SpotifyApi.searchTracks(name, 25) }
                    Log.w(TAG, "  Spotify playlist '$name' -> ${tracks.size} tracks")
                    mediaItemCollectionCtor.newInstance(tracks.map { spotifyMediaItem(it) })
                })
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "SpotifyPlaylistQuery($name)"
                else -> defaultValue(method.returnType)
            }
        }

    /** MediaItem for a Spotify track — getId() carries the spotify: uri (resolved to a local stream URL). */
    private fun spotifyMediaItem(t: SpotifyApi.Track): Any {
        if (t.uri.isNotBlank() && t.durationMs > 0) spotifyDurationMs[t.uri] = t.durationMs
        return buildMediaItem(t.uri, t.title, t.artist, t.durationMs / 1000)
    }

    /** ironman's local librespot WAV stream for [uri] ([durationMs] drives the WAV Content-Length). */
    private fun spotifyStreamUrl(uri: String, durationMs: Int): String =
        "http://127.0.0.1:${SpotifyControl.PORT}/t/${java.net.URLEncoder.encode(uri, "UTF-8")}?ms=$durationMs"

    // ---- Bluetooth/AVRCP metadata: stamp title+artist onto the player's MediaItem ----

    /**
     * The music app's MediaPlayer.setUri() builds MediaItem.fromUri(url) with NO metadata,
     * so the media3 MediaSession has nothing to broadcast over Bluetooth/AVRCP (no song
     * info on a car). We hook setMediaItem and rebuild the MediaItem with MediaMetadata
     * (title/artist captured at playback resolution).
     */
    private fun hookMediaPlayerMetadata() {
        try {
            val mpCls = cl.loadClass("humane.experience.music.playback.MediaPlayer")
            val mediaItem3 = cl.loadClass("androidx.media3.common.MediaItem")
            val setMediaItem = mpCls.getDeclaredMethod("setMediaItem", mediaItem3)
            setMediaItem.isAccessible = true
            XposedBridge.hookMethod(setMediaItem, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val item = param.args.getOrNull(0) ?: return
                        val title = currentTitle ?: return
                        withMetadata(item, title, currentArtist ?: "", currentDurationMs)
                            ?.let { param.args[0] = it }
                    } catch (t: Throwable) {
                        Log.e(TAG, "  metadata enrich failed: ${t.message}")
                    }
                }
            })
            Log.w(TAG, "  Hooked MediaPlayer.setMediaItem for BT metadata")
        } catch (t: Throwable) {
            Log.e(TAG, "  hookMediaPlayerMetadata failed: ${t.message}")
        }
    }

    private fun withMetadata(item: Any, title: String, artist: String, durationMs: Long): Any? {
        return try {
            val miCls = cl.loadClass("androidx.media3.common.MediaItem")
            val mmCls = cl.loadClass("androidx.media3.common.MediaMetadata")
            val mmbCls = cl.loadClass("androidx.media3.common.MediaMetadata\$Builder")
            val mmb = mmbCls.getConstructor().newInstance()
            mmbCls.getMethod("setTitle", CharSequence::class.java).invoke(mmb, title)
            if (artist.isNotEmpty()) mmbCls.getMethod("setArtist", CharSequence::class.java).invoke(mmb, artist)
            // Humane's projected progress UI reads the MediaSession item's metadata.
            // Without durationMs it treats extracted streams as unknown-length even though
            // ExoPlayer eventually discovers the container duration internally.
            if (durationMs > 0L) {
                runCatching {
                    mmbCls.getMethod("setDurationMs", java.lang.Long.TYPE).invoke(mmb, durationMs)
                }.onFailure {
                    Log.d(TAG, "  MediaMetadata.setDurationMs unavailable: ${it.message}")
                }
            }
            val mm = mmbCls.getMethod("build").invoke(mmb)
            val builder = miCls.getMethod("buildUpon").invoke(item)
            builder.javaClass.getMethod("setMediaMetadata", mmCls).invoke(builder, mm)
            builder.javaClass.getMethod("build").invoke(builder).also {
                Log.w(TAG, "  media metadata: title='$title' artist='$artist' durationMs=$durationMs")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "  withMetadata failed: ${t.message}"); null
        }
    }

    // ---- Shuffle (voice "shuffle on/off" -> MediaManager.setShuffle) ----

    /**
     * Register a context receiver, RETRYING until the music app's Context exists. At hook-install
     * time currentApplication() is still null, so the old one-shot `?: return` silently no-op'd —
     * which is why SHUFFLE/REPEAT never registered and voice shuffle/repeat did nothing. (Same
     * root cause + fix as the caller-announce receiver in VoiceAnnounceHooks.)
     */
    private fun registerWhenReady(action: String, handle: (Intent) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        main.post(object : Runnable {
            var tries = 0
            override fun run() {
                val ctx = runCatching {
                    Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
                }.getOrNull()
                if (ctx == null) { if (tries++ < 30) main.postDelayed(this, 2000); return }
                runCatching {
                    ctx.registerReceiver(object : BroadcastReceiver() {
                        override fun onReceive(c: Context, intent: Intent) { main.post { runCatching { handle(intent) } } }
                    }, IntentFilter(action))
                    Log.w(TAG, "  Registered $action receiver")
                }.onFailure { Log.e(TAG, "  register $action failed: ${it.message}") }
            }
        })
    }

    /** Voice "shuffle on/off" -> MediaManager.setShuffle (same call the native shuffle button makes). */
    private fun registerShuffleReceiver() =
        registerWhenReady("com.penumbraos.hook.SHUFFLE") { setShuffle(it.getBooleanExtra("on", true)) }

    /** Voice "repeat (one/all/off)" -> MediaManager.setRepeat (the native repeat button). */
    private fun registerRepeatReceiver() =
        registerWhenReady("com.penumbraos.hook.REPEAT") { setRepeat(it.getStringExtra("mode") ?: "RepeatOff") }

    @Suppress("UNCHECKED_CAST")
    private fun setRepeat(mode: String) {
        try {
            val mmCls = cl.loadClass("humane.experience.music.playback.MediaManager")
            val mm = mmCls.getMethod("sharedInstance").invoke(null) ?: return
            val rsCls = cl.loadClass("humane.experience.music.playback.MediaManager\$RepeatSetting")
            // mode is one of the RepeatSetting enum names: RepeatOne / RepeatAll / RepeatOff.
            val value = java.lang.Enum.valueOf(rsCls as Class<out Enum<*>>, mode)
            val maybe = mmCls.getMethod("setRepeat", rsCls).invoke(mm, value)
            maybe?.javaClass?.methods?.firstOrNull { it.name == "subscribe" && it.parameterTypes.isEmpty() }
                ?.invoke(maybe)
            Log.w(TAG, "  setRepeat($mode)")
        } catch (t: Throwable) {
            Log.e(TAG, "  setRepeat failed: ${t.message}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setShuffle(on: Boolean) {
        try {
            val mmCls = cl.loadClass("humane.experience.music.playback.MediaManager")
            val mm = mmCls.getMethod("sharedInstance").invoke(null) ?: return
            val ssCls = cl.loadClass("humane.experience.music.playback.MediaManager\$ShuffleSetting")
            val value = java.lang.Enum.valueOf(ssCls as Class<out Enum<*>>, if (on) "ShuffleOn" else "ShuffleOff")
            val maybe = mmCls.getMethod("setShuffle", ssCls).invoke(mm, value)
            // setShuffle returns a Maybe; subscribe() to execute it.
            maybe?.javaClass?.methods?.firstOrNull { it.name == "subscribe" && it.parameterTypes.isEmpty() }
                ?.invoke(maybe)
            Log.w(TAG, "  setShuffle($on)")
        } catch (t: Throwable) {
            Log.e(TAG, "  setShuffle failed: ${t.message}")
        }
    }

    // ---- Playlists (imported Spotify CSV -> /sdcard/aipin_playlists.json) ----

    private const val PLAYLISTS_PATH = "/sdcard/aipin_playlists.json"

    /** Hook queryWithPlaylistName: resolve an imported playlist, else fall back to search. */
    private fun hookPlaylistQuery(provider: Class<*>) {
        try {
            val m = provider.getDeclaredMethod("queryWithPlaylistName", String::class.java)
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val name = param.args.getOrNull(0) as? String ?: return
                    if (VoiceConfig.musicSource() == "spotify") {
                        Log.w(TAG, "MusicHooks: playlist '$name' -> Spotify")
                        param.result = buildSpotifyPlaylistQuery(name)
                        return
                    }
                    val tracks = loadPlaylist(name)
                    param.result = if (!tracks.isNullOrEmpty()) {
                        Log.w(TAG, "MusicHooks: playlist '$name' -> ${tracks.size} tracks (imported)")
                        buildPlaylistCollectionQuery(name, tracks)
                    } else {
                        Log.w(TAG, "MusicHooks: playlist '$name' not found -> search fallback")
                        buildCollectionQuery("$name playlist")
                    }
                }
            })
            Log.w(TAG, "  Hooked TidalProvider.queryWithPlaylistName")
        } catch (t: Throwable) {
            Log.e(TAG, "  hookPlaylistQuery failed: ${t.message}")
        }
    }

    /** Radio: queryRadioWithTrackId(url)/queryRecommendationsWithTrackId(url) -> related streams. */
    private fun hookRadioQuery(provider: Class<*>, methodName: String) {
        try {
            val m = provider.getDeclaredMethod(methodName, String::class.java)
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val trackId = param.args.getOrNull(0) as? String ?: return
                    Log.w(TAG, "MusicHooks: $methodName('$trackId') -> NewPipe related")
                    param.result = buildRelatedCollectionQuery(trackId)
                }
            })
            Log.w(TAG, "  Hooked TidalProvider.$methodName")
        } catch (t: Throwable) {
            Log.e(TAG, "  hook $methodName failed: ${t.message}")
        }
    }

    /**
     * queryWithTrackIds(List<String>) — used by Recents / "play these ids". The native impl hits
     * dead Tidal. Our MediaItem ids are spotify:/youtube-url/ytsearch:, so rebuild from them.
     * (Logs the ids so we can see the exact format Recents passes.)
     */
    private fun hookTrackIdsQuery(provider: Class<*>) {
        try {
            val m = provider.getDeclaredMethod("queryWithTrackIds", List::class.java)
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    @Suppress("UNCHECKED_CAST")
                    val ids = (param.args.getOrNull(0) as? List<String>) ?: return
                    Log.w(TAG, "MusicHooks: queryWithTrackIds(${ids.size}) ids=${ids.take(6)}")
                    if (ids.any { it.startsWith("spotify:") || it.contains("youtube.com") || it.contains("youtu.be") || it.startsWith("ytsearch:") }) {
                        param.result = buildIdsCollectionQuery(ids)
                    }
                    // else: Tidal numeric ids -> leave native (logged above for diagnosis).
                }
            })
            Log.w(TAG, "  Hooked TidalProvider.queryWithTrackIds")
        } catch (t: Throwable) {
            Log.e(TAG, "  hookTrackIdsQuery failed: ${t.message}")
        }
    }

    /** Build a collection from our own track ids — Spotify ids get batch metadata; YouTube/lazy
     *  ids resolve their stream at play time (title best-effort for display). Order preserved. */
    private fun buildIdsCollectionQuery(ids: List<String>): Any =
        Proxy.newProxyInstance(mcqCls.classLoader, arrayOf(mcqCls)) { proxy, method, args ->
            when (method.name) {
                "collection" -> ioThenMain(Callable {
                    val spotifyMeta = runCatching {
                        SpotifyApi.tracksByIds(ids.filter { it.startsWith("spotify:") }).associateBy { it.uri }
                    }.getOrDefault(emptyMap())
                    val items = ids.mapNotNull { id ->
                        when {
                            id.startsWith("spotify:") -> spotifyMeta[id]?.let { spotifyMediaItem(it) }
                            id.startsWith("ytsearch:") -> buildMediaItem(id, id.removePrefix("ytsearch:").trim(), "", 0)
                            id.contains("youtube.com") || id.contains("youtu.be") -> buildMediaItem(id, "", "", 0)
                            else -> null
                        }
                    }
                    Log.w(TAG, "  queryWithTrackIds -> ${items.size}/${ids.size} resolved")
                    mediaItemCollectionCtor.newInstance(items)
                })
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "IdsCollectionQuery(${ids.size})"
                else -> defaultValue(method.returnType)
            }
        }

    /** Collection of streams related to [videoUrl] (a radio station / autoplay queue). */
    private fun buildRelatedCollectionQuery(videoUrl: String): Any =
        Proxy.newProxyInstance(mcqCls.classLoader, arrayOf(mcqCls)) { proxy, method, args ->
            when (method.name) {
                "collection" -> ioThenMain(Callable {
                    // Spotify ids aren't NewPipe URLs — true Spotify radio (recommendations) is
                    // deprecated for new apps, so return an empty queue (autoplay just stops) v1.
                    if (videoUrl.startsWith("spotify:")) return@Callable mediaItemCollectionCtor.newInstance(emptyList<Any>())
                    // Prefer real YouTube Music radio (like the YTM app); fall back to NewPipe related.
                    val ytm = ytmRadioItems(videoUrl)
                    if (ytm.isNotEmpty()) {
                        Log.w(TAG, "  radio: ${ytm.size} YTM tracks")
                        return@Callable mediaItemCollectionCtor.newInstance(ytm)
                    }
                    val u = normalizeUrl(videoUrl)
                    val info = StreamInfo.getInfo(serviceFor(u), u)
                    val related = info.relatedItems.filterIsInstance<StreamInfoItem>()
                        .take(25).map { buildMediaItem(it) }
                    Log.w(TAG, "  radio: ${related.size} related tracks (NewPipe fallback)")
                    mediaItemCollectionCtor.newInstance(related)
                })
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "NewPipeRadio"
                else -> defaultValue(method.returnType)
            }
        }

    /** Returns the playlist's tracks as (track, artist) pairs, or null if not found. */
    private fun loadPlaylist(name: String): List<Pair<String, String>>? {
        return try {
            val f = File(PLAYLISTS_PATH)
            if (!f.exists()) return null
            val obj = JSONObject(f.readText())
            val key = obj.keys().asSequence().firstOrNull { it.equals(name, ignoreCase = true) }
                ?: obj.keys().asSequence().firstOrNull { it.contains(name, ignoreCase = true) }
                ?: return null
            val arr = obj.getJSONArray(key)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                o.optString("t") to o.optString("a")
            }.filter { it.first.isNotBlank() }
        } catch (t: Throwable) {
            Log.e(TAG, "  loadPlaylist failed: ${t.message}"); null
        }
    }

    /** Collection of playlist tracks — each resolves its stream lazily at play time. */
    private fun buildPlaylistCollectionQuery(name: String, tracks: List<Pair<String, String>>): Any =
        Proxy.newProxyInstance(mcqCls.classLoader, arrayOf(mcqCls)) { proxy, method, args ->
            when (method.name) {
                "collection" -> ioThenMain(Callable {
                    val items = tracks.map { (t, a) ->
                        buildMediaItem("ytsearch:$t $a".trim(), t, a, 0)
                    }
                    mediaItemCollectionCtor.newInstance(items)
                })
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "NewPipePlaylist($name)"
                else -> defaultValue(method.returnType)
            }
        }

    /** MediaItem proxy from a YouTube search result (getId() = watch URL). */
    private fun buildMediaItem(item: StreamInfoItem): Any =
        buildMediaItem(item.url, item.name ?: "Unknown", item.uploaderName ?: "", item.duration.toInt())

    /**
     * MediaItem proxy. [id] is either a YouTube watch URL (direct search result) or a
     * lazy "ytsearch:<query>" id (playlist track) resolved to a stream at play time.
     */
    private fun buildMediaItem(id: String, title: String, artist: String, durationSec: Int): Any {
        var collectionIndex = 0
        var shuffleIndex = 0
        val url = id
        return Proxy.newProxyInstance(mediaItemCls.classLoader, arrayOf(mediaItemCls)) { proxy, method, args ->
            when (method.name) {
                "getId" -> url
                "getTitle", "getDisplayTitle", "logString" -> title
                "getArtistNames" -> listOf(artist)
                "getAlbumName" -> ""
                "getDuration" -> durationSec
                "getTrackNumber" -> 0
                "explicit" -> false
                "getAudioQuality" -> Optional.empty<String>()
                "getVersion" -> Optional.empty<String>()
                "getVolumeNumber" -> Optional.empty<Int>()
                "collectionIndex" -> collectionIndex
                "setCollectionIndex" -> { collectionIndex = args!![0] as Int; null }
                "shuffleIndex" -> shuffleIndex
                "setShuffleIndex" -> { shuffleIndex = args!![0] as Int; null }
                "emitNotableEvent" -> null
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "NewPipeMediaItem($title)"
                else -> defaultValue(method.returnType)
            }
        }
    }

    /** PlaybackInfo proxy: playbackUri() returns the direct audio stream URL. */
    private fun buildPlaybackInfo(streamUrl: String): Any =
        Proxy.newProxyInstance(playbackInfoCls.classLoader, arrayOf(playbackInfoCls)) { proxy, method, _ ->
            when (method.name) {
                "playbackUri" -> streamUrl
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "NewPipePlaybackInfo"
                else -> defaultValue(method.returnType)
            }
        }

    // ---- RxJava via reflection ----

    private fun singleFromCallable(callable: Callable<*>): Any =
        singleCls.getMethod("fromCallable", Callable::class.java).invoke(null, callable)!!

    private fun singleSubscribeOnIo(single: Any): Any =
        singleCls.getMethod("subscribeOn", schedulerCls).invoke(single, ioScheduler)!!

    private fun singleObserveOnMain(single: Any): Any =
        singleCls.getMethod("observeOn", schedulerCls).invoke(single, mainScheduler)!!

    /** Network work on io, result delivered on main (so the player is touched on its thread). */
    private fun ioThenMain(callable: Callable<*>): Any =
        singleObserveOnMain(singleSubscribeOnIo(singleFromCallable(callable)))

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        else -> null
    }
}
