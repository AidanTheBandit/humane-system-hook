package com.penumbraos.server

import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Advertises the Penumbra HTTP server over mDNS using JmDNS.
 *
 * Hostname is hardcoded to `penumbra`, so the resolvable URL is
 * `http://penumbra.local:<port>/`. JmDNS performs RFC 6762 hostname
 * conflict probing; if another `penumbra.local` is on the LAN, JmDNS
 * will append `-2`, `-3`, etc. to the announced hostname automatically.
 */
class JmDnsAdvertiser {

    companion object {
        private const val TAG = "PenumbraServer"
        private const val SERVICE_TYPE = "_penumbra._tcp.local."
        private const val HOSTNAME = "penumbra"
        private const val VERSION = "1"
    }

    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "penumbra-jmdns").apply { isDaemon = true }
    }

    @Volatile
    private var jmdns: JmDNS? = null

    @Volatile
    private var serviceInfo: ServiceInfo? = null

    fun start(port: Int, displayName: String) {
        executor.execute {
            synchronized(lock) {
                if (jmdns != null) {
                    Log.w(TAG, "JmDNS advertiser already running, ignoring start")
                    return@execute
                }

                val bindAddress = pickBindAddress()
                if (bindAddress == null) {
                    Log.w(TAG, "No suitable IPv4 interface; aborting mDNS registration")
                    return@execute
                }

                val instance = JmDNS.create(bindAddress, HOSTNAME)
                val info = ServiceInfo.create(
                    SERVICE_TYPE,
                    displayName,
                    port,
                    "path=/ version=$VERSION",
                )
                instance.registerService(info)
                jmdns = instance
                serviceInfo = info
                Log.w(
                    TAG,
                    "Registered mDNS service: name='$displayName' type=$SERVICE_TYPE " +
                        "host=$HOSTNAME.local addr=${bindAddress.hostAddress} port=$port",
                )
            }
        }
    }

    fun stop() {
        executor.execute {
            synchronized(lock) {
                val instance = jmdns ?: return@execute
                val info = serviceInfo
                try {
                    if (info != null) {
                        instance.unregisterService(info)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to unregister mDNS service", t)
                }
                try {
                    instance.close()
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to close JmDNS", t)
                }
                jmdns = null
                serviceInfo = null
                Log.w(TAG, "JmDNS advertiser stopped")
            }
        }
    }

    /**
     * Selects a non-loopback, non-link-local IPv4 address from an `up`
     * multicast-capable interface. Prefers site-local (RFC 1918) addresses,
     * which on this device will be the Wi-Fi STA address.
     */
    private fun pickBindAddress(): InetAddress? {
        val candidates = mutableListOf<Inet4Address>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback || !iface.supportsMulticast()) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address &&
                        !addr.isLoopbackAddress &&
                        !addr.isLinkLocalAddress &&
                        !addr.isAnyLocalAddress
                    ) {
                        candidates += addr
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to enumerate network interfaces", t)
            return null
        }
        return candidates.firstOrNull { it.isSiteLocalAddress } ?: candidates.firstOrNull()
    }
}

