package com.dani.cineflow.tv

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import kotlin.concurrent.thread

/** Apple TV-style poster library for the active profile. */
class NativeLibraryActivity : AppCompatActivity() {
    private lateinit var grid: RecyclerView
    private lateinit var status: TextView
    private val adapter = LibraryAdapter()

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
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(80), dp(28), dp(80), dp(20))
        }
        content.addView(TextView(this).apply {
            text = "CINEFLOW"
            setTextColor(Color.rgb(170,170,178))
            textSize = 10f
            letterSpacing = .08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        content.addView(TextView(this).apply {
            text = "Biblioteca"
            setTextColor(Color.WHITE)
            textSize = 34f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(2))
        })
        status = TextView(this).apply {
            setTextColor(Color.rgb(165,165,172))
            textSize = 12f
            setPadding(0, dp(2), 0, dp(10))
        }
        content.addView(status)
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@NativeLibraryActivity, 5)
            adapter = this@NativeLibraryActivity.adapter
            setPadding(0, dp(6), 0, dp(20))
            clipToPadding = false
            clipChildren = false
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            setHasFixedSize(true)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        content.addView(grid)
        root.addView(content)
        return root
    }

    private fun load() {
        val local = !Profiles.isMain(this)
        if (!local && !StremioSession.isLoggedIn(this)) {
            status.text = "Vincula tu cuenta en Cuenta para ver tu biblioteca."
            return
        }
        status.text = "Cargando biblioteca…"
        thread(name = "cineflow-library", isDaemon = true) {
            val items = if (local) {
                ProfileLibrary.list(this).map {
                    StremioSession.LibraryItem(it.id, it.type, it.name, it.poster, null, 0, 0)
                }
            } else {
                StremioSession.library(this).sortedByDescending { it.timeOffset }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter.submit(items)
                status.text = if (items.isEmpty()) "Tu biblioteca está vacía." else "${items.size} títulos"
                grid.post { grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
            }
        }
    }

    private inner class LibraryAdapter : RecyclerView.Adapter<LibraryAdapter.Holder>() {
        private var items: List<StremioSession.LibraryItem> = emptyList()
        inner class Holder(val card: LinearLayout, val image: ImageView, val name: TextView) : RecyclerView.ViewHolder(card)

        fun submit(list: List<StremioSession.LibraryItem>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = LinearLayout(this@NativeLibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                clipChildren = false
                clipToPadding = false
                setPadding(dp(4), dp(4), dp(4), dp(4))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(262)).apply {
                    setMargins(dp(5), dp(6), dp(5), dp(6))
                }
            }
            val image = ImageView(this@NativeLibraryActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250))
                clipToOutline = true
                outlineProvider = roundedOutline(dp(8))
                background = rounded(CARD, 8, Color.TRANSPARENT, 0)
            }
            val name = TextView(this@NativeLibraryActivity).apply {
                setTextColor(CREAM)
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(4), dp(8), dp(4), 0)
            }
            card.addView(image)
            return Holder(card, image, name)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.name.text = item.name
            NativeImageLoader.load(holder.image, MediaArt.poster(item.id, item.poster), 1)
            holder.card.background = rounded(Color.TRANSPARENT, 8, Color.TRANSPARENT, 0)
            holder.card.onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                v.background = rounded(Color.TRANSPARENT, 8, if (has) Color.WHITE else Color.TRANSPARENT, if (has) 2 else 0)
                v.animate().scaleX(if (has) 1.045f else 1f).scaleY(if (has) 1.045f else 1f)
                    .setDuration(190L)
                    .setInterpolator(android.view.animation.OvershootInterpolator(0.35f))
                    .start()
            }
            holder.card.setOnClickListener {
                startActivity(Intent(this@NativeLibraryActivity, NativeDetailActivity::class.java)
                    .putExtra(NativeDetailActivity.EXTRA_ID, item.id)
                    .putExtra(NativeDetailActivity.EXTRA_TYPE, item.type))
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private fun rounded(color: Int, radius: Int, stroke: Int, strokeWidth: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (strokeWidth > 0) setStroke(dp(strokeWidth), stroke)
    }

    private fun roundedOutline(radius: Int): ViewOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, dp(radius).toFloat())
        }
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
        private val CREAM = Color.rgb(245,245,247)
    }
}
