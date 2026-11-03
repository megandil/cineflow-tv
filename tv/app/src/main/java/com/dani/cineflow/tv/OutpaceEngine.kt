package com.dani.cineflow.tv

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Starts the bundled, open-source Outpace AceStream engine (armv7 static binary) on localhost.
 * The channel M3U already points at 127.0.0.1:6878/ace/getstream, so no stream URLs change.
 */
object OutpaceEngine {
    private const val HOST = "127.0.0.1"
    const val PORT = 6978
    @Volatile private var process: Process? = null
    @Volatile private var starting = false

    private fun binary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "liboutpace.so")

    fun isHealthy(): Boolean {
        return try {
            val c = (URL("http://$HOST:$PORT/healthz").openConnection() as HttpURLConnection).apply {
                connectTimeout = 700
                readTimeout = 700
                requestMethod = "GET"
            }
            val ok = c.responseCode == 200
            c.disconnect()
            ok
        } catch (_: Exception) { false }
    }

    /** Blocking: starts the daemon if needed and waits for its HTTP listener. */
    fun ensureStarted(context: Context): Boolean {
        if (isHealthy()) return true
        return synchronized(this) {
            if (isHealthy()) return true
            if (starting) {
                repeat(40) {
                    if (isHealthy()) return true
                    Thread.sleep(250)
                }
                return false
            }
            starting = true
            try {
                val bin = binary(context)
                if (!bin.exists()) return false
                val root = File(context.filesDir, "outpace").apply { mkdirs() }
                val data = File(root, "data").apply { mkdirs() }
                val cache = File(root, "cache").apply { mkdirs() }
                val log = File(root, "engine.log")
                val pb = ProcessBuilder(bin.absolutePath, "serve")
                pb.directory(root)
                pb.redirectErrorStream(true)
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                pb.environment().apply {
                    put("OUTPACE_BIND", "$HOST:$PORT")
                    // Do not collide with an installed AceStream engine.
                    put("OUTPACE_PEER_LISTEN", "0.0.0.0:8721")
                    put("OUTPACE_RTMP_BIND", "127.0.0.1:1936")
                    put("OUTPACE_DATA_DIR", data.absolutePath)
                    put("OUTPACE_CACHE_DIR", cache.absolutePath)
                    put("OUTPACE_ACE_COMPAT", "1")
                    // Small startup queue: the TV has 2 GB and the player can tolerate jitter.
                    put("OUTPACE_PREBUFFER_MS", "5000")
                    put("OUTPACE_PREBUFFER_BYTES", "188000")
                    put("OUTPACE_CACHE_TYPE", "memory")
                }
                process = pb.start()
                repeat(60) {
                    if (isHealthy()) return true
                    Thread.sleep(250)
                }
                false
            } catch (e: Exception) {
                Log.e("OUTPACE", "engine start failed", e)
                false
            } finally {
                starting = false
            }
        }
    }

    fun ensureStartedAsync(context: Context, callback: (Boolean) -> Unit) {
        thread(name = "cineflow-outpace", isDaemon = true) {
            callback(ensureStarted(context.applicationContext))
        }
    }

    fun engineLog(context: Context): String =
        try { File(File(context.filesDir, "outpace"), "engine.log").readText().takeLast(4000) }
        catch (_: Exception) { "" }
}
