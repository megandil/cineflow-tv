package com.dani.cineflow.tv

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/** Large Apple TV-style catalogue for Series or Movies, with endless "Ver más". */
class CatalogActivity : AppCompatActivity() {
    private lateinit var grid: RecyclerView
    private lateinit var status: TextView
    private lateinit var adapter: CatalogAdapter
    private lateinit var verMas: TextView
    private lateinit var footerView: LinearLayout
    private var type = "movie"
    private var loading = false
    private var exhausted = false
    private val rows = mutableListOf<StremioSession.CatalogRow>()
    private val skips = HashMap<String, Int>()
    private val seen = LinkedHashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        type = intent.getStringExtra(EXTRA_TYPE) ?: "movie"
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        hideSystemBars()
        adapter = CatalogAdapter()
        setContentView(buildUi())
        loadCatalogs()
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(INK) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(80), dp(26), dp(80), dp(18))
        }
        content.addView(TextView(this).apply {
            text = "CINEFLOW"
            setTextColor(Color.rgb(170, 170, 178))
            textSize = 10f
            letterSpacing = .08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        content.addView(TextView(this).apply {
            text = if (type == "series") "Series" else "Películas"
            setTextColor(Color.WHITE)
            textSize = 34f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(2))
        })
        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        status = TextView(this).apply {
            setTextColor(Color.rgb(170, 170, 176))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        stats.addView(status)
        content.addView(stats, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)).apply { bottomMargin = dp(4) })
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@CatalogActivity, 5)
            adapter = this@CatalogActivity.adapter
            setPadding(0, dp(4), 0, dp(18))
            clipToPadding = false
            clipChildren = false
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            setHasFixedSize(false)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        content.addView(grid)
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
        }
        footerView = footer
        verMas = TextView(this).apply {
            text = "Ver más"
            setTextColor(Color.rgb(10, 10, 12))
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = rounded(Color.rgb(245, 245, 247), 23, Color.TRANSPARENT, 0)
            layoutParams = LinearLayout.LayoutParams(dp(220), dp(46))
            setOnClickListener { loadNext() }
            onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                v.background = rounded(if (has) Color.WHITE else Color.rgb(245, 245, 247), 23, Color.TRANSPARENT, 0)
                v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f)
                    .setDuration(170L).setInterpolator(android.view.animation.OvershootInterpolator(0.3f)).start()
            }
        }
        footer.addView(verMas)
        content.addView(footer)
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateFooter(footer)
            }
        })
        root.addView(content)
        return root
    }

    private fun loadCatalogs() {
        status.text = "Cargando catálogo…"
        thread(name = "cineflow-catalog-setup", isDaemon = true) {
            val catalogs = StremioSession.catalogs(this)
                .filter { it.type == type && it.addon.contains("Movie Database", true) }
                .distinctBy { it.title.lowercase(Locale.ROOT) }
                .take(5)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                rows.clear(); rows.addAll(catalogs)
                if (rows.isEmpty()) {
                    status.text = "No hay catálogos disponibles."
                    return@runOnUiThread
                }
                loadNext()
            }
        }
    }

    private fun loadNext() {
        if (loading || exhausted || rows.isEmpty()) return
        loading = true
        verMas.text = "Cargando…"
        status.text = "Cargando catálogo…"
        thread(name = "cineflow-catalog-page", isDaemon = true) {
            val added = ArrayList<Meta>()
            for (row in rows) {
                val skip = skips[row.url] ?: 0
                val page = fetchPage(row.url, skip)
                skips[row.url] = skip + 20
                for (meta in page) if (seen.add(meta.id)) added.add(meta)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (added.isEmpty()) exhausted = true else adapter.append(added)
                loading = false
                verMas.text = if (exhausted) "No hay más" else "Ver más"
                verMas.isEnabled = !exhausted
                status.text = "${adapter.itemCount()} títulos"
                grid.post {
                    if (currentFocus == null) grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    updateFooter(footerView)
                }
            }
        }
    }

    private fun fetchPage(url: String, skip: Int): List<Meta> {
        val clean = url.substringBefore('?').removeSuffix(".json").removeSuffix("/")
        val target = if (skip <= 0) "$clean.json" else "$clean/skip=$skip.json"
        return try {
            val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000; readTimeout = 12_000
                setRequestProperty("User-Agent", "CineFlow-TV")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val arr = JSONObject(body).optJSONArray("metas") ?: return emptyList()
            (0 until arr.length()).mapNotNull { index ->
                val o = arr.optJSONObject(index) ?: return@mapNotNull null
                var imdb: String? = null
                val links = o.optJSONArray("links")
                if (links != null) for (i in 0 until links.length()) {
                    val link = links.optJSONObject(i) ?: continue
                    if (link.optString("category").equals("imdb", true)) {
                        imdb = Regex("tt\\d+").find(link.optString("url"))?.value
                        if (imdb != null) break
                    }
                }
                val cleanPoster = imdb?.let { "https://images.metahub.space/poster/large/$it/img" }
                    ?: o.optString("poster").takeIf { it.isNotBlank() }
                Meta(
                    id = o.optString("imdb_id").ifBlank { imdb ?: o.optString("id") },
                    type = o.optString("type").ifBlank { type },
                    name = o.optString("name"),
                    poster = cleanPoster,
                    background = o.optString("background").takeIf { it.isNotBlank() },
                    description = o.optString("description").takeIf { it.isNotBlank() },
                    releaseInfo = o.optString("releaseInfo").takeIf { it.isNotBlank() },
                    rating = o.optString("imdbRating").takeIf { it.isNotBlank() },
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun updateFooter(footer: LinearLayout) {
        if (loading || exhausted || adapter.itemCount() == 0) {
            footer.visibility = View.GONE
            footer.layoutParams = footer.layoutParams.apply { height = 0 }
            return
        }
        val last = (grid.layoutManager as? GridLayoutManager)?.findLastVisibleItemPosition() ?: 0
        val show = last >= adapter.itemCount() - 8
        footer.visibility = if (show) View.VISIBLE else View.GONE
        footer.layoutParams = footer.layoutParams.apply { height = if (show) dp(64) else 0 }
    }

    private inner class CatalogAdapter : RecyclerView.Adapter<CatalogAdapter.Holder>() {
        private val items = ArrayList<Meta>()
        inner class Holder(val card: FrameLayout, val image: ImageView, val info: LinearLayout, val name: TextView, val desc: TextView) : RecyclerView.ViewHolder(card)

        fun append(list: List<Meta>) {
            val start = items.size
            items.addAll(list)
            notifyItemRangeInserted(start, list.size)
        }
        fun itemCount(): Int = items.size

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = FrameLayout(this@CatalogActivity).apply {
                isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                clipChildren = false; clipToPadding = false
                setPadding(dp(4), dp(4), dp(4), dp(4))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(285)).apply {
                    setMargins(dp(5), dp(6), dp(5), dp(6))
                }
            }
            val image = ImageView(this@CatalogActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                    }
                }
            }
            card.addView(image)
            val shade = View(this@CatalogActivity).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0x00000000, 0xEE000000.toInt()))
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160), Gravity.BOTTOM)
            }
            card.addView(shade)
            val info = LinearLayout(this@CatalogActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(14))
                background = rounded(0xE6101012.toInt(), 9, 0x22FFFFFF, 1)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                    leftMargin = dp(8); rightMargin = dp(8); bottomMargin = dp(8)
                }
                visibility = View.GONE
            }
            val name = TextView(this@CatalogActivity).apply {
                setTextColor(Color.WHITE); textSize = 14.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); maxLines = 2
            }
            val desc = TextView(this@CatalogActivity).apply {
                setTextColor(Color.rgb(242, 242, 246)); textSize = 12f
                maxLines = 4
                setLineSpacing(dp(1).toFloat(), 1f)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            }
            info.addView(name); info.addView(desc)
            card.addView(info)
            return Holder(card, image, info, name, desc)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val meta = items[position]
            holder.name.text = meta.name
            holder.desc.text = buildString {
                if (!meta.rating.isNullOrBlank()) append("★ ${meta.rating}\n")
                append(meta.description.orEmpty())
            }
            NativeImageLoader.load(holder.image, meta.poster ?: meta.background, 1)
            holder.card.background = rounded(Color.TRANSPARENT, 8, Color.TRANSPARENT, 0)
            holder.card.onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                v.background = rounded(Color.TRANSPARENT, 8, if (has) Color.WHITE else Color.TRANSPARENT, if (has) 2 else 0)
                holder.info.visibility = if (has) View.VISIBLE else View.GONE
                v.animate().scaleX(if (has) 1.045f else 1f).scaleY(if (has) 1.045f else 1f)
                    .setDuration(190L).setInterpolator(android.view.animation.OvershootInterpolator(0.35f)).start()
            }
            holder.card.setOnClickListener {
                startActivity(Intent(this@CatalogActivity, NativeDetailActivity::class.java)
                    .putExtra(NativeDetailActivity.EXTRA_ID, meta.id)
                    .putExtra(NativeDetailActivity.EXTRA_TYPE, meta.type.ifBlank { type }))
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

    private data class Meta(
        val id: String, val type: String, val name: String, val poster: String?,
        val background: String?, val description: String?, val releaseInfo: String?, val rating: String?,
    )

    companion object {
        const val EXTRA_TYPE = "cineflow.catalog.type"
        private val INK = Color.rgb(5, 5, 7)
    }
}
