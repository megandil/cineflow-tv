package com.dani.cineflow.tv

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread

/** Apple TV-like search with integrated categories, Spanish metadata and paging. */
class SearchActivity : AppCompatActivity() {
    private lateinit var input: EditText
    private lateinit var searchButton: TextView
    private lateinit var status: TextView
    private lateinit var adapter: ResultAdapter
    private lateinit var grid: RecyclerView
    private lateinit var categoryRow: LinearLayout
    private lateinit var footer: LinearLayout
    private lateinit var verMas: TextView

    private var moviePopular: String? = null
    private var seriesPopular: String? = null
    private var movieSearch: String? = null
    private var seriesSearch: String? = null
    private var movieTrending: String? = null
    private var seriesTrending: String? = null
    private var activeCategory = "Todo"
    private var lastMode = "category"
    private var lastQuery = ""
    private var page = 1
    private var loadingMore = false
    private var exhausted = false
    private var generation = 0
    private val categoryViews = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        hideSystemBars()
        setContentView(buildUi())
        loadCatalogs()
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(INK) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(80), dp(26), dp(80), dp(14))
        }
        content.addView(TextView(this).apply {
            text = "CINEFLOW"
            setTextColor(Color.rgb(170, 170, 178))
            textSize = 10f
            letterSpacing = .08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        content.addView(TextView(this).apply {
            text = "Buscar"
            setTextColor(Color.WHITE)
            textSize = 34f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(14))
        })
        val field = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xFF1B1B1E.toInt(), 14, 0x22FFFFFF, 1)
            setPadding(dp(16), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(10) }
        }
        field.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_cf_search_outline)
            setColorFilter(Color.rgb(200, 200, 207))
            layoutParams = LinearLayout.LayoutParams(dp(19), dp(19)).apply { rightMargin = dp(11) }
        })
        input = EditText(this).apply {
            hint = "Buscar películas, series o personas…"
            setHintTextColor(Color.rgb(125, 125, 133))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            setBackgroundColor(Color.TRANSPARENT)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(0, 0, 0, 0)
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    hideKeyboard(); search(text.toString()); true
                } else false
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                    hideKeyboard(); categoryViews.firstOrNull()?.requestFocus(); true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        field.addView(input)
        searchButton = TextView(this).apply {
            text = "Buscar"
            setTextColor(Color.rgb(10, 10, 12))
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = rounded(Color.rgb(245, 245, 247), 18, Color.TRANSPARENT, 0)
            layoutParams = LinearLayout.LayoutParams(dp(94), dp(36))
            setOnClickListener { hideKeyboard(); search(input.text.toString()) }
            onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                v.background = rounded(if (has) Color.WHITE else Color.rgb(245, 245, 247), 18, Color.TRANSPARENT, 0)
                v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f)
                    .setDuration(160L).setInterpolator(android.view.animation.OvershootInterpolator(0.3f)).start()
            }
        }
        field.addView(searchButton)
        content.addView(field)
        categoryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { bottomMargin = dp(8) }
        }
        val scroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
        }
        scroll.addView(categoryRow)
        content.addView(scroll)
        status = TextView(this).apply {
            setTextColor(Color.rgb(160, 160, 168)); textSize = 12f
            setPadding(0, dp(2), 0, dp(6))
            text = "Cargando catálogo…"
        }
        content.addView(status)
        adapter = ResultAdapter()
        grid = RecyclerView(this).apply {
            id = View.generateViewId()
            layoutManager = GridLayoutManager(this@SearchActivity, 5)
            adapter = this@SearchActivity.adapter
            setPadding(0, dp(4), 0, 0)
            clipToPadding = false
            clipChildren = false
            itemAnimator = null
            isFocusable = true
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        grid.onFocusChangeListener = View.OnFocusChangeListener { _, has ->
            if (has && adapter.itemCount() > 0) {
                grid.post { grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
            }
        }
        content.addView(grid)
        footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
        }
        verMas = TextView(this).apply {
            id = View.generateViewId()
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
            setOnClickListener { loadMore() }
            onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                v.background = rounded(if (has) Color.WHITE else Color.rgb(245, 245, 247), 23, Color.TRANSPARENT, 0)
                v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f)
                    .setDuration(170L).setInterpolator(android.view.animation.OvershootInterpolator(0.3f)).start()
            }
        }
        footer.addView(verMas)
        content.addView(footer)
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateFooter()
        })
        input.nextFocusDownId = grid.id
        searchButton.nextFocusDownId = grid.id
        root.addView(content)
        renderCategories()
        return root
    }

    private fun loadCatalogs() {
        thread(name = "cineflow-search-setup", isDaemon = true) {
            val catalogs = StremioSession.catalogs(this)
                .filter { it.addon.contains("Movie Database", true) }
            moviePopular = catalogs.firstOrNull { it.type == "movie" && it.title.contains("Popular", true) }?.url
            seriesPopular = catalogs.firstOrNull { it.type == "series" && it.title.contains("Popular", true) }?.url
            movieSearch = catalogs.firstOrNull { it.type == "movie" && it.title.contains("Búsqueda", true) }?.url
            seriesSearch = catalogs.firstOrNull { it.type == "series" && it.title.contains("Búsqueda", true) }?.url
            movieTrending = catalogs.firstOrNull { it.type == "movie" && it.title.contains("Tendencias", true) }?.url
            seriesTrending = catalogs.firstOrNull { it.type == "series" && it.title.contains("Tendencias", true) }?.url
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val filter = intent.getStringExtra(EXTRA_FILTER)
                selectCategory(if (filter == "Tendencias") "Tendencias" else "Todo")
            }
        }
    }

    private fun renderCategories() {
        categoryRow.removeAllViews(); categoryViews.clear()
        val categories = listOf("Todo", "Tendencias", "Películas", "Series", "Acción", "Comedia", "Drama", "Terror", "Ciencia ficción", "Animación", "Suspense")
        for (category in categories) {
            val chip = TextView(this).apply {
                text = category
                setTextColor(Color.rgb(205, 205, 212))
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = rounded(Color.TRANSPARENT, 19, Color.TRANSPARENT, 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { rightMargin = dp(4) }
                nextFocusDownId = grid.id
                nextFocusUpId = input.id
                setOnClickListener { selectCategory(category) }
                onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                    if (category == activeCategory) return@OnFocusChangeListener
                    v.background = rounded(if (has) Color.rgb(245, 245, 247) else Color.TRANSPARENT, 19, Color.TRANSPARENT, 0)
                    (v as TextView).setTextColor(if (has) Color.rgb(10, 10, 12) else Color.rgb(205, 205, 212))
                }
            }
            categoryViews += chip
            categoryRow.addView(chip)
        }
        markCategory()
    }

    private fun markCategory() {
        categoryViews.forEach { chip ->
            val active = chip.text.toString() == activeCategory
            chip.background = rounded(if (active) Color.rgb(245, 245, 247) else Color.TRANSPARENT, 19, Color.TRANSPARENT, 0)
            chip.setTextColor(if (active) Color.rgb(10, 10, 12) else Color.rgb(205, 205, 212))
        }
    }

    private fun selectCategory(category: String) {
        activeCategory = category
        lastMode = "category"
        lastQuery = category
        page = 1
        exhausted = false
        markCategory()
        val generation = ++this.generation
        adapter.clear(); updateFooter()
        status.text = "Cargando…"
        thread(name = "cineflow-search-category", isDaemon = true) {
            val items = fetchCategory(category, 0)
            runOnUiThread {
                if (generation != this.generation || isFinishing || isDestroyed) return@runOnUiThread
                adapter.set(items)
                exhausted = items.isEmpty()
                status.text = if (items.isEmpty()) "No hay resultados." else "${items.size} títulos"
                updateFooter()
            }
        }
    }

    private fun search(query: String) {
        val q = query.trim()
        if (q.length < 2) { status.text = "Escribe al menos 2 letras."; return }
        hideKeyboard()
        lastMode = "search"; lastQuery = q; page = 1; exhausted = false
        val generation = ++this.generation
        adapter.clear(); updateFooter()
        status.text = "Buscando…"
        thread(name = "cineflow-search-query", isDaemon = true) {
            val items = fetchSearch(q, 0)
            runOnUiThread {
                if (generation != this.generation || isFinishing || isDestroyed) return@runOnUiThread
                adapter.set(items)
                exhausted = items.isEmpty()
                status.text = if (items.isEmpty()) "Sin resultados para «$q»." else "${items.size} resultados para «$q»"
                updateFooter()
            }
        }
    }

    private fun loadMore() {
        if (loadingMore || exhausted) return
        loadingMore = true
        verMas.text = "Cargando…"
        val generation = this.generation
        val next = page
        thread(name = "cineflow-search-more", isDaemon = true) {
            val items = if (lastMode == "search") fetchSearch(lastQuery, next) else fetchCategory(lastQuery, next)
            runOnUiThread {
                if (generation != this.generation || isFinishing || isDestroyed) return@runOnUiThread
                val added = adapter.append(items)
                loadingMore = false
                page = next + 1
                if (added == 0) exhausted = true
                verMas.text = if (exhausted) "No hay más" else "Ver más"
                verMas.isEnabled = !exhausted
                status.text = "${adapter.itemCount()} títulos"
                updateFooter()
            }
        }
    }

    private fun fetchCategory(category: String, skip: Int): List<Meta> {
        return when (category) {
            "Todo" -> fetchMany(withSkip(moviePopular, skip), withSkip(seriesPopular, skip))
            "Tendencias" -> fetchMany(
                withSkip(movieTrending ?: moviePopular, skip),
                withSkip(seriesTrending ?: seriesPopular, skip),
            )
            "Películas" -> fetch(withSkip(moviePopular, skip))
            "Series" -> fetch(withSkip(seriesPopular, skip))
            else -> {
                val encoded = URLEncoder.encode(category, "UTF-8").replace("+", "%20")
                fetchMany(withGenreSkip(moviePopular, encoded, skip), withGenreSkip(seriesPopular, encoded, skip))
            }
        }
    }

    private fun fetchSearch(query: String, skip: Int): List<Meta> {
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val movie = movieSearch?.let { searchUrl(it, encoded, skip) }
        val series = seriesSearch?.let { searchUrl(it, encoded, skip) }
        if (movie != null || series != null) return fetchMany(movie, series)
        return fetchMany(
            withSkip("https://v3-cinemeta.strem.io/catalog/movie/top.json", skip)?.replace("top/skip", "top/search=$encoded/skip"),
            withSkip("https://v3-cinemeta.strem.io/catalog/series/top.json", skip)?.replace("top/skip", "top/search=$encoded/skip"),
        )
    }

    private fun withSkip(url: String?, skip: Int): String? {
        if (url.isNullOrBlank() || skip <= 0) return url
        val clean = url.substringBefore('?').removeSuffix(".json").removeSuffix("/")
        return "$clean/skip=$skip.json"
    }

    private fun withGenreSkip(url: String?, genre: String, skip: Int): String? {
        if (url.isNullOrBlank()) return null
        val clean = url.substringBefore('?').removeSuffix(".json").removeSuffix("/")
        val base = "$clean/genre=$genre"
        return if (skip <= 0) "$base.json" else "$base/skip=$skip.json"
    }

    private fun searchUrl(url: String, query: String, skip: Int): String {
        val clean = url.substringBefore('?').removeSuffix(".json").removeSuffix("/")
        val base = "$clean/search=$query"
        return if (skip <= 0) "$base.json" else "$base/skip=$skip.json"
    }

    private fun fetchMany(vararg urls: String?): List<Meta> {
        val out = LinkedHashMap<String, Meta>()
        for (url in urls) if (!url.isNullOrBlank()) for (meta in fetch(url)) out[meta.id] = meta
        return out.values.toList()
    }

    private fun fetch(url: String?): List<Meta> {
        if (url.isNullOrBlank()) return emptyList()
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
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
                    type = o.optString("type").ifBlank { "movie" },
                    name = o.optString("name"),
                    poster = cleanPoster,
                    background = o.optString("background").takeIf { it.isNotBlank() },
                    description = o.optString("description").takeIf { it.isNotBlank() },
                    rating = o.optString("imdbRating").takeIf { it.isNotBlank() },
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun updateFooter() {
        if (loadingMore || exhausted || adapter.itemCount() == 0) {
            footer.visibility = View.GONE
            footer.layoutParams = footer.layoutParams.apply { height = 0 }
            grid.nextFocusDownId = View.NO_ID
            return
        }
        val last = (grid.layoutManager as? GridLayoutManager)?.findLastVisibleItemPosition() ?: 0
        val show = last >= adapter.itemCount() - 6
        footer.visibility = if (show) View.VISIBLE else View.GONE
        footer.layoutParams = footer.layoutParams.apply { height = if (show) dp(62) else 0 }
        grid.nextFocusDownId = if (show) verMas.id else View.NO_ID
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    private fun moveSearchFocus(position: Int, keyCode: Int): Boolean {
        val columns = 5
        fun focusAt(target: Int): Boolean {
            if (target < 0 || target >= adapter.itemCount()) return false
            grid.scrollToPosition(target)
            grid.post { grid.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
            return true
        }
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (focusAt(position + columns)) true
                else if (footer.visibility == View.VISIBLE) { verMas.requestFocus(); true }
                else true
            }
            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                if (focusAt(position - columns)) true
                else { input.requestFocus(); true }
            }
            else -> false
        }
    }

    private inner class ResultAdapter : RecyclerView.Adapter<ResultAdapter.Holder>() {
        private val items = ArrayList<Meta>()
        inner class Holder(val card: FrameLayout, val image: ImageView, val info: LinearLayout, val name: TextView, val desc: TextView) : RecyclerView.ViewHolder(card)
        fun clear() { items.clear(); notifyDataSetChanged() }
        fun set(list: List<Meta>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
        fun append(list: List<Meta>): Int {
            val existing = items.map { it.id }.toSet()
            val fresh = list.filter { it.id !in existing }
            if (fresh.isEmpty()) return 0
            val start = items.size
            items.addAll(fresh)
            notifyItemRangeInserted(start, fresh.size)
            return fresh.size
        }
        fun itemCount(): Int = items.size
        override fun getItemCount(): Int = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = FrameLayout(this@SearchActivity).apply {
                isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                clipChildren = false; clipToPadding = false
                setPadding(dp(4), dp(4), dp(4), dp(4))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(285)).apply { setMargins(dp(5), dp(6), dp(5), dp(6)) }
            }
            val image = ImageView(this@SearchActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                    }
                }
            }
            val shade = View(this@SearchActivity).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0x00000000, 0xEE000000.toInt()))
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160), Gravity.BOTTOM)
            }
            val info = LinearLayout(this@SearchActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(14))
                background = rounded(0xE6101012.toInt(), 9, 0x22FFFFFF, 1)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                    leftMargin = dp(8); rightMargin = dp(8); bottomMargin = dp(8)
                }
                visibility = View.GONE
            }
            val name = TextView(this@SearchActivity).apply {
                setTextColor(Color.WHITE); textSize = 14.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); maxLines = 2
            }
            val desc = TextView(this@SearchActivity).apply {
                setTextColor(Color.rgb(242, 242, 246)); textSize = 12f; maxLines = 4
                setLineSpacing(dp(1).toFloat(), 1f)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            }
            info.addView(name); info.addView(desc)
            card.addView(image); card.addView(shade); card.addView(info)
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
            holder.card.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN) moveSearchFocus(position, keyCode) else false
            }
            holder.card.setOnClickListener {
                startActivity(Intent(this@SearchActivity, NativeDetailActivity::class.java)
                    .putExtra(NativeDetailActivity.EXTRA_ID, meta.id)
                    .putExtra(NativeDetailActivity.EXTRA_TYPE, meta.type))
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
        val background: String?, val description: String?, val rating: String? = null,
    )

    companion object {
        const val EXTRA_FILTER = "cineflow.search.filter"
        private val INK = Color.rgb(5, 5, 7)
    }
}
