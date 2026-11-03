package com.notdxbi.dxbi.tv

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
import kotlin.concurrent.thread

/** Full Stremio library for the linked account. */
class NativeLibraryActivity : AppCompatActivity() {
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
            text = "CINEFLOW / BIBLIOTECA"
            setTextColor(CORAL)
            textSize = 9f
            letterSpacing = .2f
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Tu biblioteca"
            setTextColor(CREAM)
            textSize = 30f
            typeface = Typeface.create("serif", Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(3))
        })
        status = TextView(this).apply { setTextColor(MINT); textSize = 10f; setPadding(0, dp(4), 0, dp(12)) }
        content.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        scroll.addView(content)
        root.addView(scroll)
        return root
    }

    private fun load() {
        if (!StremioSession.isLoggedIn(this)) {
            status.text = "Vincula tu cuenta en Cuenta para ver tu biblioteca."
            return
        }
        status.text = "Cargando biblioteca…"
        thread(name = "cineflow-library", isDaemon = true) {
            val items = StremioSession.library(this).sortedByDescending { it.timeOffset }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                list.removeAllViews()
                status.text = if (items.isEmpty()) "Tu biblioteca está vacía." else "${items.size} títulos"
                for ((index, item) in items.withIndex()) {
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), dp(8), dp(14), dp(8))
                        background = rounded(if (index % 2 == 0) CARD else Color.rgb(15,17,22), 9, BORDER, 1)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        isClickable = true
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(86)).apply { bottomMargin = dp(7) }
                        setOnClickListener {
                            startActivity(Intent(this@NativeLibraryActivity, NativeDetailActivity::class.java)
                                .putExtra(NativeDetailActivity.EXTRA_ID, item.id)
                                .putExtra(NativeDetailActivity.EXTRA_TYPE, item.type))
                        }
                        onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                            v.background = rounded(if (has) Color.rgb(40,43,52) else if (index % 2 == 0) CARD else Color.rgb(15,17,22), 9, if (has) CORAL else BORDER, if (has) 2 else 1)
                        }
                    }
                    val poster = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams = LinearLayout.LayoutParams(dp(52), dp(70)).apply { rightMargin = dp(14) }
                        clipToOutline = true
                        outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: android.graphics.Outline) {
                                outline.setRoundRect(0, 0, view.width, view.height, dp(7).toFloat())
                            }
                        }
                    }
                    NativeImageLoader.load(poster, item.poster, 3)
                    val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    info.addView(TextView(this).apply {
                        setText(item.name)
                        setTextColor(CREAM)
                        textSize = 13f
                        typeface = Typeface.DEFAULT_BOLD
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    info.addView(TextView(this).apply {
                        setText(if (item.duration > 0) "${(item.timeOffset * 100 / item.duration)}% visto · ${if (item.type == "series") "Serie" else "Película"}" else if (item.type == "series") "Serie" else "Película")
                        setTextColor(Color.rgb(155,160,170))
                        textSize = 9f
                        setPadding(0, dp(3), 0, 0)
                    })
                    row.addView(poster)
                    row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    list.addView(row)
                }
                list.post { list.getChildAt(0)?.requestFocus() }
            }
        }
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
