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
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread

/** Curated genre collections backed by the installed metadata addon. */
class NativeCollectionsActivity : AppCompatActivity() {
    private lateinit var grid: LinearLayout
    private lateinit var status: TextView

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
            text = "CINEFLOW / COLECCIONES"
            setTextColor(CORAL); textSize = 9f; letterSpacing = .2f; typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Colecciones"
            setTextColor(CREAM); textSize = 30f; typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(3))
        })
        content.addView(TextView(this).apply {
            text = "Elige un género y descubre películas o series."
            setTextColor(Color.rgb(155,160,170)); textSize = 11f; setPadding(0, 0, 0, dp(12))
        })
        status = TextView(this).apply { setTextColor(MINT); textSize = 10f; setPadding(0, 0, 0, dp(8)) }
        content.addView(status)
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(grid)
        scroll.addView(content)
        root.addView(scroll)
        return root
    }

    private fun load() {
        status.text = "Cargando colecciones…"
        thread(name = "cineflow-collections", isDaemon = true) {
            val catalogs = if (StremioSession.isLoggedIn(this)) StremioSession.catalogs(this) else emptyList()
            val tmdb = catalogs.firstOrNull { it.addon.contains("Movie Database", true) || it.addon.contains("TMDB", true) }
            val base = tmdb?.url?.substringBefore("/catalog/") ?: "https://v3-cinemeta.strem.io"
            val prefix = if (tmdb != null) "tmdb.top" else "top"
            val spanish = tmdb != null
            val genres = if (spanish) {
                listOf("Acción", "Aventura", "Comedia", "Drama", "Terror", "Ciencia ficción", "Animación", "Romance", "Misterio", "Documental", "Fantasía", "Crimen")
            } else {
                listOf("Action", "Adventure", "Comedy", "Drama", "Horror", "Sci-Fi", "Animation", "Romance", "Mystery", "Documentary", "Fantasy", "Crime")
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                status.text = if (tmdb != null) "12 colecciones · catálogo TMDB" else "12 colecciones · Cinemeta"
                var row: LinearLayout? = null
                for ((index, genre) in genres.withIndex()) {
                    if (index % 3 == 0) {
                        row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(86)).apply { bottomMargin = dp(8) }
                        }
                        grid.addView(row)
                    }
                    val encoded = URLEncoder.encode(genre, "UTF-8")
                    val url = if (tmdb != null) "$base/catalog/movie/$prefix/genre=$encoded.json"
                    else "$base/catalog/movie/$prefix/genre=$encoded.json"
                    val card = TextView(this).apply {
                        text = genre
                        setTextColor(CREAM)
                        textSize = 15f
                        typeface = Typeface.create("sans-serif", Typeface.BOLD)
                        gravity = Gravity.CENTER
                        isFocusable = true
                        isFocusableInTouchMode = true
                        isClickable = true
                        background = rounded(Color.rgb(22,24,31), 13, Color.rgb(55,58,68), 1)
                        layoutParams = LinearLayout.LayoutParams(0, dp(86), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
                        setOnClickListener {
                            startActivity(Intent(this@NativeCollectionsActivity, NativeSearchActivity::class.java)
                                .putExtra(NativeSearchActivity.EXTRA_CATALOG_URL, url)
                                .putExtra(NativeSearchActivity.EXTRA_CATALOG_TITLE, genre)
                                .putExtra(NativeSearchActivity.EXTRA_CATALOG_TYPE, "movie"))
                        }
                        onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                            v.background = rounded(if (has) Color.rgb(42,46,55) else Color.rgb(22,24,31), 13, if (has) CORAL else Color.rgb(55,58,68), if (has) 2 else 1)
                            v.animate().scaleX(if (has) 1.03f else 1f).scaleY(if (has) 1.03f else 1f).setDuration(120).start()
                        }
                    }
                    row?.addView(card)
                }
                grid.post { grid.getChildAt(0)?.let { (it as? LinearLayout)?.getChildAt(0)?.requestFocus() } }
            }
        }
    }

    private fun rounded(color: Int, radius: Int, stroke: Int, width: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (width > 0) setStroke(dp(width), stroke)
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
        private val CREAM = Color.rgb(245,245,247)
        private val CORAL = Color.rgb(245,245,247)
        private val MINT = Color.rgb(145,227,207)
    }
}
