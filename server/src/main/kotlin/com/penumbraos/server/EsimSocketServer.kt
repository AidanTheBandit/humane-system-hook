package com.penumbraos.server

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

// Server for external communication of eSIM operations
// Rust connects to this server
object EsimSocketServer {

    private const val TAG = "PenumbraEsimEvents"
    const val TCP_PORT = 16789

    private val running = AtomicBoolean(false)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }

        try {
            val socket = ServerSocket(TCP_PORT, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            acceptThread = Thread({
                acceptLoop(socket)
            }, "penumbra-esim-socket").apply {
                isDaemon = true
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, error ->
                    Log.e(TAG, "Uncaught on ${thread.name}", error)
                }
                start()
            }
            Log.w(TAG, "Started eSIM TCP server on 127.0.0.1:$TCP_PORT")
        } catch (t: Throwable) {
            running.set(false)
            serverSocket = null
            acceptThread = null
            Log.e(TAG, "Failed to start eSIM TCP server", t)
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to close eSIM TCP server", t)
        }
        serverSocket = null
        acceptThread = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (t: Throwable) {
                if (running.get()) {
                    Log.w(TAG, "Accept loop failed", t)
                }
                break
            }

            try {
                BufferedReader(InputStreamReader(client.getInputStream())).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        try {
                            EsimEventStore.onEvent(JSONObject(line))
                        } catch (t: Throwable) {
                            Log.w(TAG, "Failed to parse eSIM event: $line", t)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to read eSIM event client", t)
            } finally {
                try {
                    client.close()
                } catch (_: Throwable) {
                }
            }
        }
    }
}
