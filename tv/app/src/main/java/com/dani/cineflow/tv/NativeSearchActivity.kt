package com.dani.cineflow.tv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Explore: a 4-column catalog grid with a normal, explicit search (no autosearch). */
class NativeSearchActivity : AppCompatActivity() {
    private lateinit var input: EditText
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ResultAdapter
    private lateinit var status: TextView
    private lateinit var categoryRow: LinearLayout
    private var generation = 0
    private val searchCache = HashMap<String, List<Meta>>()
    private var tmdbBase: String? = null
    private var categories: List<Category> = emptyList()
    private var categoryViews: MutableList<TextView> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        setContentView(buildUi())
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                if (input.hasFocus() || imm.isAcceptingText) {
                    hideKeyboard()
                    input.clearFocus()
                    recycler.requestFocus()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        val catalogUrl = intent.getStringExtra(EXTRA_CATALOG_URL)
        if (catalogUrl.isNullOrBlank()) {
            setupExplore()
        } else {
            categoryRow.visibility = View.GONE
            loadCatalog(
                catalogUrl,
                intent.getStringExtra(EXTRA_CATALOG_TITLE).orEmpty().ifBlank { "Catálogo" },
                intent.getStringExtra(EXTRA_CATALOG_TYPE).orEmpty().ifBlank { "movie" },
            )
        }
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(INK) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(80), dp(26), dp(80), dp(22))
        }
        content.addView(TextView(this).apply {
            text = "CINEFLOW"
            setTextColor(CORAL)
            textSize = 9f
            letterSpacing = .2f
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Explorar"
            setTextColor(CREAM)
            textSize = 30f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(8))
        })
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = BackAwareEditText(this).apply {
            hint = "Buscar película, serie o persona…"
            setHintTextColor(Color.rgb(120,124,134))
            setTextColor(Color.WHITE)
            textSize = 13f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(16), 0, dp(16), 0)
            background = fieldBackground()
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { requestFocus(); showKeyboard() }
            onImeBack = {
                hideKeyboard()
                clearFocus()
                recycler.requestFocus()
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    // No live search and no automatic defaults on every edit: the viewer
                    // decides when to search, and Back only closes the keyboard.
                }
            })
            setOnEditorActionListener { _, actionId, _ ->
                // Fire TV's system keyboard labels its submit key "Siguiente"/"Next" even when
                // imeOptions is Search; treat every submit-like action as a search.
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_GO
                ) {
                    hideKeyboard()
                    search(text.toString())
                    true
                } else false
            }
            onImeBack = {
                hideKeyboard()
                clearFocus()
                recycler.requestFocus()
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                    (keyCode == android.view.KeyEvent.KEYCODE_ENTER || keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
                ) {
                    hideKeyboard()
                    search(text.toString())
                    true
                } else false
            }
        }
        input.id = View.generateViewId()
        searchRow.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
        searchRow.addView(TextView(this).apply {
            text = "Buscar"
            setTextColor(Color.rgb(12,13,16))
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .08f
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = searchButtonBackground(false)
            layoutParams = LinearLayout.LayoutParams(dp(100), dp(48))
            setOnClickListener { hideKeyboard(); search(input.text.toString()) }
            setOnFocusChangeListener { v, has ->
                v.background = searchButtonBackground(has)
                (v as TextView).setTextColor(if (has) CORAL else Color.rgb(12,13,16))
                v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(120).start()
            }
        })
        content.addView(searchRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(7) })
        val categoryScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply { bottomMargin = dp(5) }
        }
        categoryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        categoryScroll.addView(categoryRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(categoryScroll)
        status = TextView(this).apply {
            text = "POPULARES · cargando…"
            setTextColor(Color.rgb(155,160,170))
            textSize = 9f
            setPadding(0, 0, 0, dp(8))
        }
        content.addView(status)

        adapter = ResultAdapter(emptyList())
        recycler = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@NativeSearchActivity, 5)
            adapter = this@NativeSearchActivity.adapter
            setPadding(dp(6), dp(4), dp(6), dp(10))
            clipToPadding = false
            clipChildren = false
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            setHasFixedSize(true)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        content.addView(recycler)
        root.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return root
    }

    private fun setupExplore() {
        status.text = "Cargando categorías…"
        thread(name = "cineflow-explore-setup", isDaemon = true) {
            val catalogs = if (StremioSession.isLoggedIn(this)) StremioSession.catalogs(this) else emptyList()
            val tmdb = catalogs.filter {
                it.addon.contains("Movie Database", ignoreCase = true) ||
                    it.addon.contains("TMDB", ignoreCase = true)
            }
            val built = if (tmdb.isNotEmpty()) {
                tmdbBase = tmdb.first().url.substringBefore("/catalog/")
                tmdb.distinctBy { it.title.lowercase(Locale.ROOT) + "|" + it.type }.map {
                    Category(
                        label = it.title.uppercase(Locale.ROOT) + if (it.type == "series") " · SERIES" else " · PELÍCULAS",
                        type = it.type,
                        url = it.url,
                    )
                }
            } else {
                listOf(
                    Category("PELÍCULAS · POPULARES", "movie", "https://v3-cinemeta.strem.io/catalog/movie/top.json"),
                    Category("SERIES · POPULARES", "series", "https://v3-cinemeta.strem.io/catalog/series/top.json"),
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                categories = built
                renderCategories()
                val mode = intent.getStringExtra(EXTRA_MODE)
                val start = when (mode) {
                    "series" -> categories.firstOrNull { it.type == "series" && it.label.contains("POPULAR") }
                        ?: categories.firstOrNull { it.type == "series" }
                    "movie" -> categories.firstOrNull { it.type == "movie" && it.label.contains("POPULAR") }
                        ?: categories.firstOrNull { it.type == "movie" }
                    else -> categories.firstOrNull { it.label.contains("POPULAR") }
                } ?: categories.firstOrNull()
                if (start != null) loadCategory(start) else showDefaults()
            }
        }
    }

    private fun renderCategories() {
        categoryRow.removeAllViews()
        categoryViews.clear()
        for (category in categories) {
            val shortLabel = when {
                category.label.contains("ÚLTIMOS LANZAMIENTOS", true) && category.type == "series" -> "Nuevas series"
                category.label.contains("ÚLTIMOS LANZAMIENTOS", true) -> "Novedades"
                category.label.contains("TENDENCIAS", true) && category.type == "series" -> "Series en tendencia"
                category.label.contains("TENDENCIAS", true) -> "Tendencias"
                category.label.contains("POPULAR", true) && category.type == "series" -> "Series populares"
                category.label.contains("POPULAR", true) -> "Populares"
                else -> category.label
            }
            val chip = TextView(this).apply {
                text = shortLabel
                setTextColor(Color.rgb(205,205,212))
                textSize = 13f
                letterSpacing = 0f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                val padH = resources.displayMetrics.density.toInt() * 12
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setPadding(padH, dp(6), padH, dp(6))
                background = rounded(Color.TRANSPARENT, 18, Color.TRANSPARENT, 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { rightMargin = dp(6) }
                setOnClickListener { loadCategory(category) }
                onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                    v.background = rounded(if (has) Color.WHITE else Color.TRANSPARENT, 18, Color.TRANSPARENT, 0)
                    (v as TextView).setTextColor(if (has) Color.rgb(10,10,12) else Color.rgb(205,205,212))
                }
            }
            categoryViews.add(chip)
            categoryRow.addView(chip)
        }
    }

    private fun markCategory(category: Category) {
        val index = categories.indexOf(category)
        categoryViews.forEachIndexed { i, chip ->
            val active = i == index
            chip.background = rounded(if (active) Color.WHITE else Color.TRANSPARENT, 18, Color.TRANSPARENT, 0)
            chip.setTextColor(if (active) Color.rgb(10,10,12) else Color.rgb(205,205,212))
        }
    }

    private fun loadCategory(category: Category) {
        markCategory(category)
        generation++
        val gen = generation
        status.text = "Cargando · ${category.label}"
        thread(name = "cineflow-explore-category", isDaemon = true) {
            val items = fetchJson(category.url, category.type)
            runOnUiThread {
                if (gen != generation || isFinishing || isDestroyed) return@runOnUiThread
                renderResults(items, category.label)
            }
        }
    }

    private fun loadCatalog(url: String, title: String, type: String) {
        generation++
        val gen = generation
        status.text = "CARGANDO · " + title.uppercase(Locale.ROOT)
        thread(name = "cineflow-addon-catalog", isDaemon = true) {
            val items = fetchJson(url, type)
            runOnUiThread {
                if (gen != generation || isFinishing || isDestroyed) return@runOnUiThread
                renderResults(items, title.uppercase(Locale.ROOT))
            }
        }
    }

    private fun showDefaults() {
        generation++
        val gen = generation
        status.text = "POPULARES · cargando…"
        thread(name = "cineflow-explore-defaults", isDaemon = true) {
            val movies = fetchTop("movie")
            val series = fetchTop("series")
            runOnUiThread {
                if (gen != generation || isFinishing || isDestroyed) return@runOnUiThread
                renderResults((movies + series).distinctBy { it.id }, "POPULARES AHORA", focusResults = true)
            }
        }
    }

    private fun search(query: String) {
        val text = query.trim()
        if (text.length < 2) {
            status.text = "Escribe al menos 2 letras y pulsa Buscar."
            return
        }
        val cacheKey = text.lowercase(Locale.ROOT)
        searchCache[cacheKey]?.let { cached ->
            generation++
            renderResults(cached, "RESULTADOS")
            return
        }
        generation++
        val gen = generation
        status.text = "Buscando «$text»…"
        val encoded = URLEncoder.encode(text, "UTF-8")
        val base = tmdbBase
        val results = mutableListOf<Meta>()
        val remaining = AtomicInteger(2)
        val lock = Any()

        fun publish(found: List<Meta>) {
            runOnUiThread {
                if (gen != generation || isFinishing || isDestroyed) return@runOnUiThread
                synchronized(lock) {
                    results += found
                    val combined = results.distinctBy { it.id }
                    if (combined.isNotEmpty()) {
                        renderResults(combined, "RESULTADOS", focusResults = results.size == found.size)
                    }
                    if (remaining.decrementAndGet() == 0) {
                        searchCache[cacheKey] = combined.take(80)
                        if (results.size > 20) {
                            while (searchCache.size > 20) searchCache.remove(searchCache.keys.first())
                        }
                        if (combined.isEmpty()) {
                            adapter.replace(emptyList())
                            status.text = "No hay resultados para «$text»."
                        }
                    }
                }
            }
        }

        // Movie and series searches are independent: launch both at once instead of waiting
        // for the movie request to finish before asking for series.
        thread(name = "cineflow-search-movies", isDaemon = true) {
            val movies = if (base != null) fetchJson("$base/catalog/movie/tmdb.search/search=$encoded.json", "movie")
            else fetchSearch("movie", encoded)
            publish(movies)
        }
        thread(name = "cineflow-search-series", isDaemon = true) {
            val series = if (base != null) fetchJson("$base/catalog/series/tmdb.search/search=$encoded.json", "series")
            else fetchSearch("series", encoded)
            publish(series)
        }
    }

    private fun renderResults(all: List<Meta>, heading: String, focusResults: Boolean = true) {
        adapter.replace(all.take(60))
        status.text = if (all.isEmpty()) "No hay contenido." else "$heading · ${all.size} títulos"
        if (focusResults) recycler.post { recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    private fun fetchTop(type: String): List<Meta> =
        fetchJson("https://v3-cinemeta.strem.io/catalog/$type/top.json", type)

    private fun fetchSearch(type: String, query: String): List<Meta> =
        fetchJson("https://v3-cinemeta.strem.io/catalog/$type/top/search=$query.json", type)

    private fun fetchJson(url: String, fallbackType: String): List<Meta> {
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "CineFlow-TV")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val arr = JSONObject(body).optJSONArray("metas") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Meta(
                    id = o.optString("imdb_id").ifBlank { o.optString("id") },
                    type = o.optString("type").ifBlank { fallbackType },
                    name = o.optString("name"),
                    poster = o.optString("poster").takeIf { it.isNotBlank() },
                    background = o.optString("background").takeIf { it.isNotBlank() },
                    releaseInfo = o.optString("releaseInfo").takeIf { it.isNotBlank() },
                    rating = o.optString("imdbRating").takeIf { it.isNotBlank() },
                    description = o.optString("description").takeIf { it.isNotBlank() },
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private inner class ResultAdapter(private var items: List<Meta>) : RecyclerView.Adapter<ResultAdapter.Holder>() {
        inner class Holder(val card: LinearLayout, val art: FrameLayout, val image: ImageView, val name: TextView, val sub: TextView, val desc: TextView) : RecyclerView.ViewHolder(card)

        fun replace(list: List<Meta>) { items = list; notifyDataSetChanged() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = LinearLayout(this@NativeSearchActivity).apply {
                orientation = LinearLayout.VERTICAL
                setNextFocusUpId(input.id)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setPadding(dp(4), dp(4), dp(4), dp(4))
                clipChildren = false
                clipToPadding = false
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(292)).apply { setMargins(dp(5), dp(6), dp(5), dp(6)) }
            }
            val art = FrameLayout(this@NativeSearchActivity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250))
                background = rounded(CARD, 8, Color.TRANSPARENT, 0)
                clipToOutline = true
                outlineProvider = roundedOutline(dp(8))
            }
            val image = ImageView(this@NativeSearchActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
            art.addView(image)
            val desc = TextView(this@NativeSearchActivity).apply {
                setTextColor(Color.rgb(245,245,247))
                textSize = 7.2f
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(7), dp(5), dp(7), dp(5))
                setBackgroundColor(0xD907080B.toInt())
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            }
            art.addView(desc)
            val name = TextView(this@NativeSearchActivity).apply {
                setTextColor(CREAM)
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(4), dp(6), dp(4), 0)
            }
            val sub = TextView(this@NativeSearchActivity).apply {
                setTextColor(Color.rgb(148,153,163))
                textSize = 8f
                maxLines = 1
                setPadding(dp(4), dp(2), dp(4), 0)
            }
            card.addView(art)
            card.addView(name)
            card.addView(sub)
            return Holder(card, art, image, name, sub, desc)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val meta = items[position]
            holder.name.text = meta.name
            holder.sub.text = listOfNotNull(meta.releaseInfo, meta.rating?.let { "★ $it" }).joinToString("  ·  ")
            holder.desc.text = meta.description.orEmpty()
            holder.desc.visibility = View.GONE
            NativeImageLoader.load(holder.image, meta.background ?: meta.poster, 1)
            holder.card.background = rounded(Color.TRANSPARENT, 12, Color.TRANSPARENT, 0)
            holder.card.onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                holder.card.background = rounded(if (has) Color.rgb(31,20,21) else Color.TRANSPARENT, 12,
                    if (has) CORAL else Color.TRANSPARENT, if (has) 2 else 0)
                holder.desc.visibility = if (has && holder.desc.text.isNotBlank()) View.VISIBLE else View.GONE
                holder.name.setTextColor(if (has) Color.WHITE else CREAM)
                v.animate().scaleX(if (has) 1.045f else 1f).scaleY(if (has) 1.045f else 1f)
                    .setDuration(190L)
                    .setInterpolator(android.view.animation.OvershootInterpolator(0.35f))
                    .start()
            }
            holder.card.setOnClickListener {
                startActivity(Intent(this@NativeSearchActivity, NativeDetailActivity::class.java)
                    .putExtra(NativeDetailActivity.EXTRA_ID, meta.id)
                    .putExtra(NativeDetailActivity.EXTRA_TYPE, meta.type))
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private fun showKeyboard() {
        input.requestFocus()
        input.postDelayed({
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_FORCED)
            // Fire OS sometimes ignores showSoftInput when the focus was just moved from the grid.
            if (!imm.isAcceptingText) imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
        }, 180)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun roundedOutline(radius: Int): ViewOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius.toFloat())
        }
    }

    private fun searchButtonBackground(focused: Boolean): GradientDrawable =
        rounded(if (focused) Color.WHITE else CORAL, 10, if (focused) Color.WHITE else CORAL, 1)

    private fun fieldBackground(): android.graphics.drawable.StateListDrawable {
        val focused = rounded(Color.rgb(24,26,33), 10, CORAL, 2)
        val normal = rounded(Color.rgb(18,18,20), 10, BORDER, 1)
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
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

    private data class Category(val label: String, val type: String, val url: String)

    private data class Meta(
        val id: String, val type: String, val name: String, val poster: String?, val background: String?,
        val releaseInfo: String?, val rating: String?, val description: String?,
    )

    private class BackAwareEditText(context: Context) : androidx.appcompat.widget.AppCompatEditText(context) {
        var onImeBack: (() -> Unit)? = null
        override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onImeBack?.invoke()
                return true
            }
            return super.onKeyPreIme(keyCode, event)
        }
    }

    companion object {
        const val EXTRA_CATALOG_URL = "cineflow.catalog.url"
        const val EXTRA_CATALOG_TITLE = "cineflow.catalog.title"
        const val EXTRA_CATALOG_TYPE = "cineflow.catalog.type"
        const val EXTRA_MODE = "cineflow.explore.mode"
        private val INK = Color.rgb(5,5,7)
        private val CARD = Color.rgb(18,18,20)
        private val BORDER = Color.rgb(48,51,60)
        private val CREAM = Color.rgb(245,245,247)
        private val CORAL = Color.rgb(245,245,247)
    }
}
