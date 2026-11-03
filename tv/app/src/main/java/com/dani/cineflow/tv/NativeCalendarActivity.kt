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
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Upcoming/recent episodes from the series in the active profile's library. */
class NativeCalendarActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
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
            text = "CINEFLOW / CALENDARIO"
            setTextColor(CORAL); textSize = 9f; letterSpacing = .2f; typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Calendario"
            setTextColor(CREAM); textSize = 30f; typeface = Typeface.create("serif", Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(3))
        })
        content.addView(TextView(this).apply {
            text = "Próximos episodios de las series de tu biblioteca."
            setTextColor(Color.rgb(155,160,170)); textSize = 11f; setPadding(0, 0, 0, dp(10))
        })
        status = TextView(this).apply { setTextColor(MINT); textSize = 10f; setPadding(0, 0, 0, dp(10)) }
        content.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        scroll.addView(content)
        root.addView(scroll)
        return root
    }

    private data class Release(val series: String, val id: String, val type: String, val season: Int, val episode: Int, val title: String, val date: Date)

    private fun load() {
        status.text = "Buscando próximos episodios…"
        thread(name = "cineflow-calendar", isDaemon = true) {
            val series = if (Profiles.isMain(this)) {
                StremioSession.library(this).filter { it.type == "series" }.map { it.id to it.name }
            } else {
                ProfileLibrary.list(this).filter { it.type == "series" }.map { it.id to it.name }
            }.distinctBy { it.first }.take(12)
            val releases = mutableListOf<Release>()
            val now = System.currentTimeMillis()
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            fun parse(value: String?): Date? = try {
                if (value.isNullOrBlank()) null else parser.parse(value.take(19))
            } catch (_: Exception) { null }
            for ((id, name) in series) {
                val meta = StremioSession.metaJson(this, "series", id) ?: continue
                val videos = meta.optJSONArray("videos") ?: continue
                for (i in 0 until videos.length()) {
                    val v = videos.optJSONObject(i) ?: continue
                    val date = parse(v.optString("released").ifBlank { v.optString("firstAired") }) ?: continue
                    val delta = date.time - now
                    if (delta < -7L * 24 * 60 * 60 * 1000 || delta > 60L * 24 * 60 * 60 * 1000) continue
                    releases += Release(
                        series = name,
                        id = id,
                        type = "series",
                        season = v.optInt("season", 0),
                        episode = v.optInt("episode", v.optInt("number", 0)),
                        title = v.optString("name").ifBlank { v.optString("title") },
                        date = date,
                    )
                }
            }
            val sorted = releases.sortedBy { it.date.time }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                list.removeAllViews()
                status.text = if (sorted.isEmpty()) "No hay estrenos próximos en tu biblioteca." else "${sorted.size} episodios · próximos 60 días"
                val dateFormat = SimpleDateFormat("EEE d MMM", Locale.forLanguageTag("es-ES"))
                for ((index, r) in sorted.withIndex()) {
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(14), dp(10), dp(14), dp(10))
                        background = rounded(if (index % 2 == 0) CARD else Color.rgb(15,17,22), 9, BORDER, 1)
                        isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)).apply { bottomMargin = dp(7) }
                        setOnClickListener {
                            startActivity(Intent(this@NativeCalendarActivity, NativeDetailActivity::class.java)
                                .putExtra(NativeDetailActivity.EXTRA_ID, r.id)
                                .putExtra(NativeDetailActivity.EXTRA_TYPE, r.type))
                        }
                        onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                            v.background = rounded(if (has) Color.rgb(42,46,55) else if (index % 2 == 0) CARD else Color.rgb(15,17,22), 9, if (has) CORAL else BORDER, if (has) 2 else 1)
                        }
                    }
                    val date = TextView(this).apply {
                        text = dateFormat.format(r.date).uppercase(Locale.forLanguageTag("es-ES"))
                        setTextColor(CORAL); textSize = 9f; typeface = Typeface.DEFAULT_BOLD
                        layoutParams = LinearLayout.LayoutParams(dp(105), ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                    val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    info.addView(TextView(this).apply {
                        text = r.series
                        setTextColor(CREAM); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
                    })
                    info.addView(TextView(this).apply {
                        text = "T${r.season} E${r.episode}" + if (r.title.isNotBlank()) " · ${r.title}" else ""
                        setTextColor(Color.rgb(155,160,170)); textSize = 9f; setPadding(0, dp(3), 0, 0)
                    })
                    row.addView(date)
                    row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    list.addView(row)
                }
                list.post { list.getChildAt(0)?.requestFocus() }
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
        private val INK = Color.rgb(7,8,11)
        private val CARD = Color.rgb(18,20,26)
        private val BORDER = Color.rgb(48,51,60)
        private val CREAM = Color.rgb(242,235,221)
        private val CORAL = Color.rgb(255,77,61)
        private val MINT = Color.rgb(145,227,207)
    }
}
