package com.notdxbi.dxbi.tv

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Native update checker against the CineFlow GitHub releases.
 *
 * Release assets must be called CineFlow.apk. The APK must be signed with the same key as the
 * installed app; Android will otherwise refuse the package installer update.
 */
object UpdateManager {
    private const val REPO = "megandil/cineflow-tv"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PREFS = "cineflow-updates"

    data class Release(val version: String, val notes: String, val apkUrl: String, val pageUrl: String)
    sealed class CheckResult {
        data class Available(val release: Release) : CheckResult()
        object UpToDate : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    fun check(context: Context): CheckResult {
        return try {
            val connection = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "CineFlow-TV")
            }
            val code = connection.responseCode
            if (code != 200) {
                connection.disconnect()
                return CheckResult.Error(if (code == 404) "Todavía no hay releases publicados." else "GitHub respondió $code.")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val json = JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v").removePrefix("V")
            val current = BuildConfigVersion.name(context).removePrefix("v")
            if (tag.isBlank()) return CheckResult.Error("La release no tiene versión.")
            val assets = json.optJSONArray("assets") ?: return CheckResult.Error("La release no tiene archivos.")
            var apk: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                if (name == "CineFlow.apk" || name.endsWith(".apk", ignoreCase = true)) {
                    apk = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                    if (apk != null) break
                }
            }
            if (apk == null) return CheckResult.Error("La release no contiene un APK.")
            if (compareVersions(tag, current) <= 0) return CheckResult.UpToDate
            CheckResult.Available(Release(
                version = tag,
                notes = json.optString("body").take(900),
                apkUrl = apk,
                pageUrl = json.optString("html_url"),
            ))
        } catch (e: Exception) {
            CheckResult.Error(e.message ?: "No se pudo comprobar la actualización.")
        }
    }

    fun checkAndPrompt(activity: Activity, manual: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return
        thread(name = "cineflow-update-check", isDaemon = true) {
            val result = check(activity)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                when (result) {
                    is CheckResult.Available -> {
                        if (manual || shouldPrompt(activity, result.release.version)) {
                            markPrompted(activity, result.release.version)
                            showDialog(activity, result.release)
                        }
                    }
                    is CheckResult.UpToDate -> if (manual) {
                        Toast.makeText(activity, "CineFlow está actualizado (${BuildConfigVersion.name(activity)}).", Toast.LENGTH_LONG).show()
                    }
                    is CheckResult.Error -> if (manual) {
                        Toast.makeText(activity, "No se pudo comprobar: ${result.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun shouldPrompt(context: Context, version: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastVersion = prefs.getString("lastVersion", null)
        val lastAt = prefs.getLong("lastAt", 0L)
        return lastVersion != version || System.currentTimeMillis() - lastAt > 6 * 60 * 60 * 1000L
    }

    private fun markPrompted(context: Context, version: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("lastVersion", version)
            .putLong("lastAt", System.currentTimeMillis())
            .apply()
    }

    private fun showDialog(activity: Activity, release: Release) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 30), dp(activity, 26), dp(activity, 30), dp(activity, 22))
            background = rounded(activity, Color.rgb(18, 20, 26), 16, Color.rgb(60, 63, 74), 1)
        }
        card.addView(TextView(activity).apply {
            text = "Nueva versión de CineFlow"
            setTextColor(Color.rgb(242, 235, 221))
            textSize = 23f
            typeface = Typeface.create("serif", Typeface.BOLD)
        })
        card.addView(TextView(activity).apply {
            text = "Instalada ${BuildConfigVersion.name(activity)}  ·  Disponible ${release.version}"
            setTextColor(Color.rgb(155, 160, 170))
            textSize = 10f
            setPadding(0, dp(activity, 6), 0, dp(activity, 12))
        })
        card.addView(TextView(activity).apply {
            text = release.notes.ifBlank { "Mejoras y correcciones de CineFlow." }
            setTextColor(Color.rgb(210, 214, 222))
            textSize = 11f
            maxLines = 7
            setPadding(0, 0, 0, dp(activity, 14))
        })
        val status = TextView(activity).apply {
            text = ""
            setTextColor(Color.rgb(145, 227, 207))
            textSize = 9f
            setPadding(0, 0, 0, dp(activity, 8))
        }
        card.addView(status)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val later = button(activity, "MÁS TARDE", false)
        val update = button(activity, "ACTUALIZAR", true)
        row.addView(later, LinearLayout.LayoutParams(dp(activity, 140), dp(activity, 42)).apply { rightMargin = dp(activity, 8) })
        row.addView(update, LinearLayout.LayoutParams(dp(activity, 160), dp(activity, 42)))
        card.addView(row)
        dialog.setContentView(card)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(dp(activity, 560), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.setCancelable(true)
        later.setOnClickListener { dialog.dismiss() }
        update.setOnClickListener {
            update.isEnabled = false
            later.isEnabled = false
            update.text = "DESCARGANDO…"
            status.text = "Descargando actualización…"
            downloadAndInstall(activity, release, onProgress = { pct ->
                activity.runOnUiThread { if (dialog.isShowing) status.text = "Descargando… $pct%" }
            }, onReady = { file ->
                activity.runOnUiThread {
                    if (dialog.isShowing) dialog.dismiss()
                    install(activity, file)
                }
            }, onError = { error ->
                activity.runOnUiThread {
                    update.isEnabled = true
                    later.isEnabled = true
                    update.text = "ACTUALIZAR"
                    status.text = error
                    status.setTextColor(Color.rgb(255, 120, 105))
                }
            })
        }
        dialog.show()
        dialog.window?.setLayout(dp(activity, 560), ViewGroup.LayoutParams.WRAP_CONTENT)
        update.requestFocus()
    }

    private fun downloadAndInstall(activity: Activity, release: Release, onProgress: (Int) -> Unit, onReady: (File) -> Unit, onError: (String) -> Unit) {
        thread(name = "cineflow-update-download", isDaemon = true) {
            try {
                val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "CineFlow-TV")
                }
                if (connection.responseCode !in 200..299) {
                    onError("No se pudo descargar (${connection.responseCode}).")
                    connection.disconnect()
                    return@thread
                }
                val total = connection.contentLengthLong
                val file = File(activity.cacheDir, "CineFlow-update.apk")
                file.parentFile?.mkdirs()
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        var lastPct = -1
                        while (input.read(buffer).also { read = it } > 0) {
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) {
                                val pct = ((done * 100) / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                            }
                        }
                    }
                }
                connection.disconnect()
                if (file.length() < 1_000_000L) {
                    onError("El APK descargado está incompleto.")
                    return@thread
                }
                onReady(file)
            } catch (e: Exception) {
                onError(e.message ?: "Error descargando la actualización.")
            }
        }
    }

    private fun install(activity: Activity, file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
            activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(activity, "No se pudo abrir el instalador: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun button(activity: Activity, text: String, filled: Boolean): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(if (filled) Color.rgb(12, 13, 16) else Color.rgb(242, 235, 221))
        textSize = 9f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = .08f
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        background = rounded(activity, if (filled) Color.rgb(255, 77, 61) else Color.rgb(28, 31, 38), 10,
            if (filled) Color.rgb(255, 77, 61) else Color.rgb(55, 58, 68), 1)
        onFocusChangeListener = View.OnFocusChangeListener { v, has ->
            v.background = rounded(activity, if (has) Color.WHITE else if (filled) Color.rgb(255, 77, 61) else Color.rgb(28, 31, 38),
                10, if (has) Color.WHITE else if (filled) Color.rgb(255, 77, 61) else Color.rgb(55, 58, 68), 1)
            v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f).setDuration(120).start()
        }
    }

    private fun rounded(context: Context, color: Int, radius: Int, stroke: Int, width: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(context, radius).toFloat()
        if (width > 0) setStroke(dp(context, width), stroke)
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun compareVersions(a: String, b: String): Int {
        val ap = a.split('.').map { it.toIntOrNull() ?: 0 }
        val bp = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(ap.size, bp.size)) {
            val av = ap.getOrElse(i) { 0 }
            val bv = bp.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }
}
