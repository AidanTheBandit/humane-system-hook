package com.penumbraos.hook

import android.util.Log
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiAuthenticationState
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * YouTube Music account auth for [YtmRadio], read from /sdcard/aipin_ytm.json.
 *
 * ytm-kt authenticates by replaying a header set (see YoutubeiAuthenticationState:
 * REQUIRED_HEADERS = cookie + authorization). YouTube's `authorization` is a
 * time-based SAPISIDHASH, so a statically copied header expires. We derive it from
 * the cookie's SAPISID ourselves, freshly per call, so only the cookie ever needs
 * to be supplied. Auth is optional — unauthenticated song radio still works.
 */
object YtmAuth {
    private const val TAG = "PenumbraHook"
    private const val PATH = "/sdcard/aipin_ytm.json"
    private const val ORIGIN = "https://music.youtube.com"
    private const val TTL_MS = 5000L

    @Volatile private var cache: JSONObject = JSONObject()
    @Volatile private var cachedAt = 0L

    private fun config(): JSONObject {
        val now = System.currentTimeMillis()
        // Range check so a backward clock jump (now < cachedAt) invalidates the cache.
        if ((now - cachedAt) in 0 until TTL_MS) return cache
        cache = try {
            val f = File(PATH)
            if (f.exists()) JSONObject(f.readText()) else JSONObject()
        } catch (t: Throwable) {
            Log.e(TAG, "YtmAuth read failed: ${t.message}")
            JSONObject()
        }
        cachedAt = now
        return cache
    }

    /** Max radio tracks to queue (default 25). */
    fun radioLimit(): Int = try {
        config().optInt("radio_limit", 25).coerceIn(1, 100)
    } catch (t: Throwable) { 25 }

    private fun cookie(): String = config().optString("cookie", "").trim()

    /**
     * Build an authenticated state for [api] from the configured cookie, or null if no
     * cookie is set (or SAPISID can't be found). Recomputes a fresh SAPISIDHASH each
     * call so the authorization header never goes stale.
     */
    fun buildAuthState(api: YoutubeiApi): YoutubeiAuthenticationState? {
        val cookie = cookie()
        if (cookie.isEmpty()) return null

        val sapisid = extractSapisid(cookie) ?: run {
            Log.w(TAG, "YtmAuth: cookie has no SAPISID/__Secure-3PAPISID; radio stays unauthenticated")
            return null
        }
        val authuser = config().optString("authuser", "0").ifBlank { "0" }
        val channelId = config().optString("channel_id", "").ifBlank { null }

        return try {
            val authorization = sapisidHash(sapisid, ORIGIN, System.currentTimeMillis() / 1000L)
            YoutubeiAuthenticationState.fromMap(
                api,
                mapOf(
                    "cookie" to cookie,
                    "authorization" to authorization,
                    "x-goog-authuser" to authuser,
                ),
                channelId,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "YtmAuth.buildAuthState failed: ${t.message}")
            null
        }
    }

    /** Google's SAPISIDHASH scheme: "SAPISIDHASH <ts>_<sha1hex(\"<ts> <sapisid> <origin>\")>". */
    fun sapisidHash(sapisid: String, origin: String, epochSeconds: Long): String {
        val payload = "$epochSeconds $sapisid $origin"
        val digest = MessageDigest.getInstance("SHA-1").digest(payload.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${epochSeconds}_$hex"
    }

    /** Pull SAPISID (preferred) or __Secure-3PAPISID out of a raw Cookie header. */
    private fun extractSapisid(cookie: String): String? {
        cookieValue(cookie, "SAPISID")?.let { return it }
        return cookieValue(cookie, "__Secure-3PAPISID")
    }

    private fun cookieValue(cookie: String, name: String): String? {
        val match = Regex("(?:^|;\\s*)${Regex.escape(name)}=([^;]+)").find(cookie) ?: return null
        return match.groupValues[1].trim().ifEmpty { null }
    }
}
