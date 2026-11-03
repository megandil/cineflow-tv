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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale

/** Settings hub: each section opens its own screen. */
class NativeSettingsActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(INK) }
        val scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(54), dp(26), dp(54), dp(30))
        }
        content.addView(TextView(this).apply {
            text = "CINEFLOW / AJUSTES"
            setTextColor(CORAL)
            textSize = 9f
            letterSpacing = .2f
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Ajustes"
            setTextColor(CREAM)
            textSize = 30f
            typeface = Typeface.create("serif", Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(3))
        })
        content.addView(TextView(this).apply {
            text = "Elige una sección."
            setTextColor(Color.rgb(160,164,174))
            textSize = 11f
            setPadding(0, 0, 0, dp(14))
        })
        card("Cuenta", "Perfil, addons, biblioteca y sesión.", R.drawable.ic_cf_account, "cuenta")
        card("Imagen", "Rendimiento, valoraciones, reseñas y apariencia.", R.drawable.ic_cf_image, "imagen")
        card("Reproducción", "Calidad, proveedor, reproductor y saltos.", R.drawable.ic_cf_play, "reproduccion")
        card("Audio", "Idioma de audio y subtítulos.", R.drawable.ic_cf_audio, "audio")
        card("Experiencia", "Idioma de interfaz, sonidos y extras.", R.drawable.ic_cf_experience, "experiencia")
        updateCard()
        scroll.addView(content)
        root.addView(scroll)
        return root
    }

    private fun card(title: String, desc: String, iconRes: Int, section: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = rounded(CARD, 11, BORDER, 1)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(86)).apply { bottomMargin = dp(9) }
            setOnClickListener {
                startActivity(Intent(this@NativeSettingsActivity, NativeSettingsSectionActivity::class.java)
                    .putExtra(NativeSettingsSectionActivity.EXTRA_SECTION, section))
            }
            onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                v.background = rounded(if (has) Color.rgb(40,43,52) else CARD, 11, if (has) CORAL else BORDER, if (has) 2 else 1)
            }
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(CORAL)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(16) }
        }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(this).apply { text = title; setTextColor(CREAM); textSize = 16f; typeface = Typeface.create("serif", Typeface.BOLD) })
        info.addView(TextView(this).apply { text = desc; setTextColor(Color.rgb(155,160,170)); textSize = 9f; setPadding(0, dp(3), 0, 0) })
        row.addView(icon)
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(row)
    }

    private fun updateCard() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = rounded(CARD, 11, BORDER, 1)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(86)).apply { bottomMargin = dp(9) }
            setOnClickListener { UpdateManager.checkAndPrompt(this@NativeSettingsActivity, manual = true) }
            onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                v.background = rounded(if (has) Color.rgb(40,43,52) else CARD, 11, if (has) CORAL else BORDER, if (has) 2 else 1)
            }
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_cf_update)
            setColorFilter(CORAL)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(16) }
        }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(this).apply {
            text = "Buscar actualizaciones"
            setTextColor(CREAM)
            textSize = 16f
            typeface = Typeface.create("serif", Typeface.BOLD)
        })
        info.addView(TextView(this).apply {
            text = "Comprueba si hay una versión nueva de CineFlow."
            setTextColor(Color.rgb(155,160,170))
            textSize = 9f
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(icon)
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(row)
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
    }
}
