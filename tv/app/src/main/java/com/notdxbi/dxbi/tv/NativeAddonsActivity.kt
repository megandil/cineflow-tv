package com.notdxbi.dxbi.tv

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale
import kotlin.concurrent.thread

/** The addons installed on the linked Stremio account, with no WebView. */
class NativeAddonsActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private val installLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        setContentView(buildUi())
        load()
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(INK) }
        val scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(54), dp(26), dp(54), dp(30))
        }
        content.addView(TextView(this).apply {
            text = "CINEFLOW / ADDONS"
            setTextColor(CORAL)
            textSize = 9f
            letterSpacing = .2f
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Tus addons"
            setTextColor(CREAM)
            textSize = 30f
            typeface = Typeface.create("serif", Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(3))
        })
        content.addView(TextView(this).apply {
            text = "Instalados en tu cuenta de Stremio. CineFlow los usa para buscar proveedores."
            setTextColor(Color.rgb(160,164,174))
            textSize = 11f
            setPadding(0, 0, 0, dp(8))
        })
        val add = TextView(this).apply {
            text = "＋  INSTALAR ADDON"
            setTextColor(CREAM)
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .08f
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = rounded(CARD, 10, BORDER, 1)
            setOnClickListener {
                installLauncher.launch(Intent(this@NativeAddonsActivity, AddonInstallActivity::class.java))
            }
            onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                v.background = rounded(if (has) Color.rgb(40,43,52) else CARD, 10, if (has) CORAL else BORDER, if (has) 2 else 1)
            }
        }
        content.addView(add, LinearLayout.LayoutParams(dp(210), dp(42)).apply { bottomMargin = dp(10) })
        status = TextView(this).apply { setTextColor(MINT); textSize = 10f; setPadding(0, dp(5), 0, dp(10)) }
        content.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        scroll.addView(content)
        root.addView(scroll)
        return root
    }

    private fun load() {
        if (!StremioSession.isLoggedIn(this)) {
            status.text = "Vincula tu cuenta en Cuenta para cargar los addons."
            return
        }
        status.text = "Cargando addons…"
        thread(name = "cineflow-addons", isDaemon = true) {
            val addons = StremioSession.addons(this, force = true)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                list.removeAllViews()
                status.text = if (addons.isEmpty()) "No hay addons instalados." else "${addons.size} addons instalados"
                for ((index, addon) in addons.withIndex()) {
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(14), dp(11), dp(14), dp(11))
                        background = rounded(if (index % 2 == 0) CARD else Color.rgb(15,17,22), 9, BORDER, 1)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(7) }
                    }
                    val icon = ImageView(this).apply {
                        setImageResource(R.drawable.ic_cf_addons)
                        setColorFilter(if (addon.resources.contains("stream")) CORAL else Color.rgb(150,155,165))
                        layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(14) }
                    }
                    val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    text.addView(TextView(this).apply {
                        this.text = addon.name
                        setTextColor(CREAM)
                        textSize = 13f
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    text.addView(TextView(this).apply {
                        this.text = (if (addon.resources.contains("stream")) "Proveedores" else "Addon") + "  ·  " + addon.types.joinToString(" / ")
                        setTextColor(Color.rgb(150,155,165))
                        textSize = 9f
                    })
                    row.addView(icon)
                    row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    val remove = TextView(this).apply {
                        this.text = "QUITAR"
                        setTextColor(Color.rgb(255,120,105))
                        textSize = 8f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        isFocusable = true
                        isFocusableInTouchMode = true
                        isClickable = true
                        setPadding(dp(10), dp(7), dp(10), dp(7))
                        background = rounded(Color.rgb(30,20,22), 7, Color.rgb(80,40,40), 1)
                        setOnClickListener { confirmRemove(addon) }
                        onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                            v.background = rounded(if (has) Color.rgb(70,30,30) else Color.rgb(30,20,22), 7, if (has) CORAL else Color.rgb(80,40,40), if (has) 2 else 1)
                        }
                    }
                    row.addView(remove, LinearLayout.LayoutParams(dp(88), dp(32)).apply { leftMargin = dp(10) })
                    row.onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                        v.background = rounded(if (has) Color.rgb(38,42,50) else if (index % 2 == 0) CARD else Color.rgb(15,17,22), 9, if (has) CORAL else BORDER, if (has) 2 else 1)
                    }
                    list.addView(row)
                }
                list.post { list.getChildAt(0)?.requestFocus() }
            }
        }
    }

    private fun confirmRemove(addon: StremioSession.Addon) {
        AlertDialog.Builder(this)
            .setTitle("Quitar addon")
            .setMessage("¿Eliminar «${addon.name}» de tu cuenta de Stremio?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Quitar") { _, _ ->
                status.text = "Quitando ${addon.name}…"
                thread(name = "cineflow-remove-addon", isDaemon = true) {
                    val (ok, message) = StremioSession.removeAddon(this, addon.transportUrl)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        status.text = if (ok) "✓ $message" else "✕ $message"
                        if (ok) load() else status.setTextColor(CORAL)
                    }
                }
            }
            .show()
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
        private val INK = Color.rgb(7,8,11)
        private val CARD = Color.rgb(18,20,26)
        private val BORDER = Color.rgb(48,51,60)
        private val CREAM = Color.rgb(242,235,221)
        private val CORAL = Color.rgb(255,77,61)
        private val MINT = Color.rgb(145,227,207)
    }
}
