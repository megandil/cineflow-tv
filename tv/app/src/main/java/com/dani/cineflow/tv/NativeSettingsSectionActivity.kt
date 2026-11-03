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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale

/** One settings section; the main settings screen opens this per category. */
class NativeSettingsSectionActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var section: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        section = intent.getStringExtra(EXTRA_SECTION) ?: "cuenta"
        setContentView(buildUi())
        render()
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(INK) }
        val scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(54), dp(26), dp(54), dp(30))
        }
        content.addView(TextView(this).apply {
            text = "‹   AJUSTES / " + section.uppercase(Locale("es", "ES"))
            setTextColor(CORAL)
            textSize = 9f
            letterSpacing = .16f
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(0, 0, 0, dp(8))
            setOnClickListener { finish() }
        })
        val titles = mapOf("imagen" to "Imagen", "reproduccion" to "Reproducción", "audio" to "Audio", "experiencia" to "Experiencia", "cuenta" to "Cuenta")
        content.addView(TextView(this).apply {
            text = titles[section] ?: "Ajustes"
            setTextColor(CREAM)
            textSize = 29f
            typeface = Typeface.create("serif", Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(12))
        })
        scroll.addView(content)
        root.addView(scroll)
        return root
    }

    private fun render() {
        when (section) {
            "cuenta" -> {
                info(StremioSession.email(this) ?: "Sin vincular")
                action("GESTIONAR CUENTA") { startActivity(Intent(this, NativeAccountActivity::class.java)) }
                action("VER ADDONS") { startActivity(Intent(this, NativeAddonsActivity::class.java)) }
                action("CAMBIAR DE PERFIL") { startActivity(Intent(this, ProfilePickerActivity::class.java)) }
                action("VER BIBLIOTECA") { startActivity(Intent(this, NativeLibraryActivity::class.java)) }
                action("CERRAR SESIÓN") { StremioSession.logout(this); recreate() }
            }
            "imagen" -> {
                toggle("Rendimiento de TV", "performance", "Desactiva efectos que pesan en el stick.")
                toggle("Valoraciones IMDb", "ratings", "Muestra la nota en las tarjetas.")
                toggle("Reseñas", "reviews", "Reseñas de los títulos.")
                toggle("Previews al pasar", "previews", "Animaciones y vídeo al enfocar un título.")
                toggle("Billboard de Inicio", "billboard", "Película promocional grande arriba.")
                toggle("Continuar viendo ancho", "wideContinue", "Tarjetas horizontales en la fila.")
            }
            "reproduccion" -> {
                choice("Calidad máxima", "maxQuality", listOf("2160" to "4K", "1080" to "1080p", "720" to "720p"))
                toggle("Elegir mejor proveedor", "autoPick", "Autoplay del primer proveedor y Proveedor manual.")
                toggle("Reproducir torrents en la TV", "tvTorrents", "Sin debrid ni servidor externo.")
                toggle("Reloj en el reproductor", "playerClock", "Muestra la hora durante la reproducción.")
                toggle("Tiempo restante", "remainingTime", "Tiempo restante en la barra.")
                toggle("Botones de saltar", "skipButtons", "Intro y créditos.")
                choice("Auto-saltar", "autoSkip", listOf("off" to "Desactivado", "intro" to "Intro", "all" to "Todo"))
                toggle("Precargar siguiente", "preloadNext", "Precarga el siguiente episodio.")
                choice("Up next", "upNext", listOf("0" to "Desactivado", "10" to "10 s", "20" to "20 s"))
            }
            "audio" -> {
                choice("Idioma de audio preferido", "audioLanguage", listOf("spa" to "Español", "eng" to "Inglés", "original" to "Original"))
                choice("Subtítulos", "subtitlesLanguage", listOf("off" to "Desactivados", "spa" to "Español", "eng" to "Inglés"))
                choice("Estilo de subtítulos", "subtitleStyle", listOf("stremio" to "Stremio", "cinema" to "Cine", "large" to "Grande"))
                choice("Subtítulos al iniciar", "subtitlesStart", listOf("off" to "Desactivados", "spa" to "Español", "eng" to "Inglés"))
                choice("Volumen de sonidos", "soundVolume", listOf("subtle" to "Sutil", "normal" to "Normal", "loud" to "Alto"))
                toggle("Sonidos de interfaz", "uiSounds", "Sonidos suaves al navegar.")
            }
            "experiencia" -> {
                choice("Idioma de interfaz", "interfaceLanguage", listOf("es-ES" to "Español", "en-US" to "English"))
                toggle("Herramientas de streams", "streamTools", "Ordenar y filtrar proveedores.")
                toggle("Herramientas de addons", "addonTools", "Ordenar y gestionar addons.")
                toggle("Sonido de intro", "introSound", "Música de la animación.")
                toggle("Animación de inicio", "splash", "Muestra la animación de CineFlow.")
                action("BORRAR CONTINUAR VIENDO") { NativeProgressStore.clear(this) }
            }
        }
        content.post { firstFocusable()?.requestFocus() }
    }

    private fun firstFocusable(): View? {
        for (i in 0 until content.childCount) {
            val v = content.getChildAt(i)
            if (v.isFocusable) return v
        }
        return null
    }

    private fun info(value: String) {
        content.addView(TextView(this).apply {
            text = value
            setTextColor(CREAM)
            textSize = 12f
            setPadding(dp(2), 0, 0, dp(8))
        })
    }

    private fun toggle(label: String, key: String, description: String) {
        val row = settingRow(label, description)
        val value = row.tag as TextView
        fun paint() { val on = NativeSettings.bool(this, key); value.text = if (on) "SÍ" else "NO"; value.setTextColor(if (on) MINT else Color.rgb(130,135,145)) }
        paint()
        row.setOnClickListener { NativeSettings.set(this, key, if (NativeSettings.bool(this, key)) "false" else "true"); paint() }
        content.addView(row)
    }

    private fun choice(label: String, key: String, options: List<Pair<String, String>>) {
        val row = settingRow(label, "")
        val value = row.tag as TextView
        fun paint() {
            val current = NativeSettings.value(this, key)
            value.text = options.firstOrNull { it.first == current }?.second ?: current
            value.setTextColor(CREAM)
        }
        paint()
        row.setOnClickListener {
            val current = NativeSettings.value(this, key)
            val index = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
            NativeSettings.set(this, key, options[(index + 1) % options.size].first)
            paint()
        }
        content.addView(row)
    }

    private fun settingRow(label: String, description: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = rounded(CARD, 9, BORDER, 1)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(this).apply { text = label; setTextColor(CREAM); textSize = 11f; typeface = Typeface.DEFAULT_BOLD })
        if (description.isNotBlank()) info.addView(TextView(this).apply { text = description; setTextColor(Color.rgb(140,145,155)); textSize = 8f; setPadding(0, dp(2), 0, 0) })
        val value = TextView(this).apply { text = ""; textSize = 10f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, 0, 0) }
        row.tag = value
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(value)
        row.onFocusChangeListener = View.OnFocusChangeListener { v, has ->
            v.background = rounded(if (has) Color.rgb(40,43,52) else CARD, 9, if (has) CORAL else BORDER, if (has) 2 else 1)
        }
        return row
    }

    private fun action(text: String, onClick: () -> Unit) {
        content.addView(TextView(this).apply {
            this.text = text
            setTextColor(CREAM)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = rounded(CARD, 9, BORDER, 1)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { bottomMargin = dp(6) }
            setOnClickListener { onClick() }
            onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                v.background = rounded(if (has) Color.rgb(40,43,52) else CARD, 9, if (has) CORAL else BORDER, if (has) 2 else 1)
            }
        })
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
        const val EXTRA_SECTION = "cineflow.settings.section"
        private val INK = Color.rgb(7,8,11)
        private val CARD = Color.rgb(18,20,26)
        private val BORDER = Color.rgb(48,51,60)
        private val CREAM = Color.rgb(242,235,221)
        private val CORAL = Color.rgb(255,77,61)
        private val MINT = Color.rgb(145,227,207)
    }
}
