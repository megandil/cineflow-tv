package com.dani.cineflow.tv

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Native Stremio account link. The TV creates the same short link code used by the Stremio app,
 * shows its QR, polls until the phone links it, then stores the auth key and loads the addons.
 */
class NativeAccountActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        setContentView(buildUi())
        if (StremioSession.isLoggedIn(this)) showLoggedIn() else startQr()
    }

    override fun onDestroy() {
        generation++
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(INK) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(40), dp(30), dp(40), dp(26))
            layoutParams = FrameLayout.LayoutParams(dp(650), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            background = rounded(CARD, 18, BORDER, 1)
        }
        content.addView(TextView(this).apply {
            text = "CINEFLOW / CUENTA"
            setTextColor(CORAL)
            textSize = 9f
            letterSpacing = .2f
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Vincula tu cuenta"
            setTextColor(CREAM)
            textSize = 27f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, dp(7), 0, dp(4))
        })
        content.addView(TextView(this).apply {
            text = "Vincula la cuenta para usar tus addons, tu biblioteca y Continuar viendo."
            setTextColor(Color.rgb(160,164,174))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        })
        status = TextView(this).apply {
            setTextColor(MINT)
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        content.addView(status)
        root.addView(content)
        return root
    }

    private fun clearContent() {
        while (content.childCount > 4) content.removeViewAt(content.childCount - 1)
    }

    private fun showLoggedIn() {
        clearContent()
        val email = StremioSession.email(this).orEmpty()
        content.addView(TextView(this).apply {
            text = if (email.isBlank()) "Cuenta vinculada" else email
            setTextColor(CREAM)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        })
        status.text = "Cargando addons…"
        thread(name = "cineflow-account-addons", isDaemon = true) {
            val addons = StremioSession.addons(this, force = true)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                status.text = if (addons.isEmpty()) "Cuenta vinculada · sin addons" else "Cuenta vinculada · ${addons.size} addons"
                if (addons.isNotEmpty()) {
                    content.addView(TextView(this).apply {
                        text = addons.take(8).joinToString("  ·  ") { it.name }
                        setTextColor(Color.rgb(170,174,184))
                        textSize = 9f
                        gravity = Gravity.CENTER
                        setPadding(dp(8), 0, dp(8), dp(10))
                    })
                }
                content.addView(action("ACTUALIZAR ADDONS") {
                    status.text = "Actualizando…"
                    thread(isDaemon = true) {
                        StremioSession.addons(this, force = true)
                        runOnUiThread { if (!isFinishing) showLoggedIn() }
                    }
                })
                content.addView(action("CERRAR SESIÓN") {
                    StremioSession.logout(this)
                    recreate()
                })
            }
        }
    }

    private fun startQr() {
        clearContent()
        generation++
        val gen = generation
        status.text = "Creando código…"
        val qr = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(240), dp(240)).apply { topMargin = dp(6); bottomMargin = dp(6) }
            setBackgroundColor(Color.WHITE)
        }
        val link = TextView(this).apply {
            setTextColor(Color.rgb(180,184,194))
            textSize = 9f
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(8))
        }
        content.addView(qr)
        content.addView(link)
        content.addView(action("CERRAR") { finish() })
        thread(name = "cineflow-qr-create", isDaemon = true) {
            try {
                val raw = get("https://link.stremio.com/api/v2/create?type=Create")
                val result = JSONObject(raw ?: "").optJSONObject("result")
                val code = result?.optString("code").orEmpty()
                if (code.isBlank()) {
                    runOnUiThread { status.text = "No se pudo crear el código." }
                    return@thread
                }
                runOnUiThread {
                    if (gen != generation) return@runOnUiThread
                    NativeImageLoader.load(qr, result.optString("qrcode"), 1)
                    link.text = result.optString("link")
                    status.text = "Escanea el QR con el móvil e inicia sesión en Stremio"
                }
                poll(gen, code)
            } catch (e: Exception) {
                runOnUiThread { status.text = "No se pudo conectar con Stremio." }
            }
        }
    }

    private fun poll(gen: Int, code: String) {
        if (gen != generation) return
        thread(isDaemon = true) {
            if (gen != generation) return@thread
            try {
                val raw = get("https://link.stremio.com/api/v2/read?type=Read&code=" + java.net.URLEncoder.encode(code, "UTF-8"))
                val result = JSONObject(raw ?: "").optJSONObject("result")
                val key = result?.optString("authKey")?.takeIf { it.isNotBlank() }
                    ?: result?.optString("auth_key")?.takeIf { it.isNotBlank() }
                if (key != null) {
                    runOnUiThread { status.text = "Cuenta vinculada. Cargando…" }
                    val (ok, message) = StremioSession.loginWithToken(this, key)
                    runOnUiThread {
                        if (ok) {
                            status.text = "Cuenta vinculada · cargando addons…"
                            StremioSession.addons(this, force = true)
                            showLoggedIn()
                        } else {
                            status.text = "Stremio rechazó la sesión: $message"
                        }
                    }
                    return@thread
                }
            } catch (_: Exception) { /* still waiting */ }
            main.postDelayed({ poll(gen, code) }, 2000)
        }
    }

    private fun action(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(CREAM)
        textSize = 9f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = .08f
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        background = rounded(CARD, 8, BORDER, 1)
        layoutParams = LinearLayout.LayoutParams(dp(210), dp(38)).apply { topMargin = dp(7) }
        setOnClickListener { onClick() }
        onFocusChangeListener = View.OnFocusChangeListener { v, has ->
            v.background = rounded(if (has) Color.rgb(42,46,55) else CARD, 8, if (has) CORAL else BORDER, if (has) 2 else 1)
        }
    }

    private fun get(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "CineFlow-TV")
        }
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) { null } finally { connection.disconnect() }
    }

    private fun rounded(color: Int, radius: Int, stroke: Int, strokeWidth: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (strokeWidth > 0) setStroke(dp(strokeWidth), stroke)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val INK = Color.rgb(5,5,7)
        private val CARD = Color.rgb(18,18,20)
        private val BORDER = Color.rgb(48,51,60)
        private val CREAM = Color.rgb(245,245,247)
        private val CORAL = Color.rgb(245,245,247)
        private val MINT = Color.rgb(145,227,207)
    }
}
