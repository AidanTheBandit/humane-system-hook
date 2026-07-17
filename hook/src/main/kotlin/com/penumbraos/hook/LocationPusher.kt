package com.penumbraos.hook

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pushes the pin's GPS location to the Penumbra server so the native Nearby
 * experience and GeoLocate gRPC handler can return real coordinates instead
 * of "can't locate you".
 *
 * Runs in ironman (system_server process) which has system UID and can access
 * LocationManager. Polls every 30 seconds and on significant movement.
 */
object LocationPusher {
    private const val TAG = "PenumbraHook"
    private const val SERVER_URL = "http://127.0.0.1:8080/api/location"
    private const val INTERVAL_MS = 30_000L
    private const val MIN_DISTANCE_M = 20.0f

    private var lastSentLat = 0.0
    private var lastSentLon = 0.0

    fun start() {
        val handler = Handler(Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                pushLocation()
                handler.postDelayed(this, INTERVAL_MS)
            }
        })
        Log.w(TAG, "LocationPusher started (interval=${INTERVAL_MS}ms)")
    }

    @SuppressLint("MissingPermission")
    private fun pushLocation() {
        try {
            val ctx = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? Context ?: return

            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            // Try GPS first, then network, then passive
            val location = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            ).firstNotNullOfOrNull { provider ->
                runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
            }

            if (location == null) {
                Log.d(TAG, "LocationPusher: no last known location")
                return
            }

            val lat = location.latitude
            val lon = location.longitude

            // Only push if we moved significantly
            val moved = FloatArray(1)
            Location.distanceBetween(lastSentLat, lastSentLon, lat, lon, moved)
            if (lastSentLat != 0.0 && moved[0] < MIN_DISTANCE_M) return

            lastSentLat = lat
            lastSentLon = lon

            val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 50.0

            // Push to server in a background thread
            Thread {
                try {
                    val conn = (URL(SERVER_URL).openConnection() as HttpURLConnection).apply {
                        requestMethod = "PUT"
                        connectTimeout = 5000
                        readTimeout = 5000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                    val body = """{"latitude":$lat,"longitude":$lon,"accuracy":$accuracy}"""
                    OutputStreamWriter(conn.outputStream).use { it.write(body) }
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code in 200..299) {
                        Log.w(TAG, "LocationPusher: pushed lat=$lat lon=$lon acc=$accuracy")
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "LocationPusher: push failed: ${t.message}")
                }
            }.start()
        } catch (t: Throwable) {
            Log.d(TAG, "LocationPusher: ${t.message}")
        }
    }
}
