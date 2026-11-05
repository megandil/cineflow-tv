package com.dani.cineflow.tv

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale
import kotlin.concurrent.thread

/** Installs an addon natively from a manifest URL or a stremio:// addon link. */
class AddonInstallActivity : AppCompatActivity() {
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var install: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        setContentView(buildUi())
        val fromLink = intent?.data?.toString().orEmpty().ifBlank {
            intent?.getStringExtra(EXTRA_URL).orEmpty()
        }
        if (fromLink.isNotBlank()) {
            input.setText(fromLink)
            input.setSelection(input.text.length)
            installAddon()
        } else {
            input.requestFocus()
        }
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(INK) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(54), dp(28), dp(54), dp(28))
        }
        content.addView(TextView(this).apply {
            text = "CINEFLOW / ADDONS"
            setTextColor(CORAL)
            textSize = 9f
            letterSpacing = .2f
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Instalar addon"
            setTextColor(CREAM)
            textSize = 30f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(4))
        })
        content.addView(TextView(this).apply {
            text = "Pega la dirección del manifiesto o abre un enlace stremio://addon/…"
            setTextColor(Color.rgb(160,164,174))
            textSize = 11f
            setPadding(0, 0, 0, dp(14))
        })
        input = EditText(this).apply {
            hint = "https://…/manifest.json"
            setHintTextColor(Color.rgb(120,124,134))
            setTextColor(Color.WHITE)
            textSize = 13f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(Color.rgb(18,18,20), 10, BORDER, 1)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                    hideKeyboard(); installAddon(); true
                } else false
            }
        }
        content.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        install = action("INSTALAR") { hideKeyboard(); installAddon() }
        row.addView(install, LinearLayout.LayoutParams(dp(150), dp(42)).apply { rightMargin = dp(8) })
        row.addView(action("CANCELAR") { finish() }, LinearLayout.LayoutParams(dp(130), dp(42)))
        content.addView(row)
        status = TextView(this).apply {
            setTextColor(MINT)
            textSize = 10f
            setPadding(0, dp(14), 0, 0)
        }
        content.addView(status)
        content.addView(TextView(this).apply {
            text = "Los addons se instalan en tu cuenta de Stremio. Al volver a Inicio o Addons se cargan sus catálogos y proveedores."
            setTextColor(Color.rgb(130,135,145))
            textSize = 9f
            setPadding(0, dp(10), 0, 0)
        })
        root.addView(content)
        return root
    }

    private fun action(label: String, click: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(CREAM)
        textSize = 9f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = .08f
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        background = rounded(CARD, 10, BORDER, 1)
        setOnClickListener { click() }
        onFocusChangeListener = View.OnFocusChangeListener { v, has ->
            v.background = rounded(if (has) Color.rgb(40,43,52) else CARD, 10, if (has) CORAL else BORDER, if (has) 2 else 1)
        }
    }

    private fun installAddon() {
        val url = input.text.toString().trim()
        if (url.isBlank()) { status.text = "Escribe una dirección de addon."; return }
        install.isEnabled = false
        install.text = "INSTALANDO…"
        status.text = "Descargando manifiesto…"
        thread(name = "cineflow-install-addon", isDaemon = true) {
            val (ok, message) = StremioSession.installAddon(this, url)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                install.isEnabled = true
                install.text = "INSTALAR"
                status.text = if (ok) "✓ $message. Ya puedes volver a Addons." else "✕ $message"
                status.setTextColor(if (ok) MINT else CORAL)
            }
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
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
        const val EXTRA_URL = "cineflow.addon.url"
        private val INK = Color.rgb(5,5,7)
        private val CARD = Color.rgb(18,18,20)
        private val BORDER = Color.rgb(48,51,60)
        private val CREAM = Color.rgb(245,245,247)
        private val CORAL = Color.rgb(245,245,247)
        private val MINT = Color.rgb(145,227,207)
    }
}
