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

/** Explore: a 4-column catalog grid with a normal, explicit search (no autosearch). */
class NativeSearchActivity : AppCompatActivity() {
    private lateinit var input: EditText
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ResultAdapter
    private lateinit var status: TextView
    private var generation = 0

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
        showDefaults()
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(INK) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(54), dp(24), dp(54), dp(22))
        }
        content.addView(TextView(this).apply {
            text = "CINEFLOW / EXPLORAR"
            setTextColor(CORAL)
            textSize = 9f
            letterSpacing = .2f
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Explorar"
            setTextColor(CREAM)
            textSize = 30f
            typeface = Typeface.create("serif", Typeface.BOLD)
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
            text = "BUSCAR"
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
        content.addView(searchRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(9) })
        status = TextView(this).apply {
            text = "POPULARES · cargando…"
            setTextColor(Color.rgb(155,160,170))
            textSize = 9f
            setPadding(0, 0, 0, dp(8))
        }
        content.addView(status)

        adapter = ResultAdapter(emptyList())
        recycler = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@NativeSearchActivity, 4)
            adapter = this@NativeSearchActivity.adapter
            setPadding(0, 0, 0, dp(8))
            clipToPadding = false
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            setHasFixedSize(true)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        content.addView(recycler)
        root.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return root
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
        generation++
        val gen = generation
        status.text = "Buscando «$text»…"
        thread(name = "cineflow-search", isDaemon = true) {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val movies = fetchSearch("movie", encoded)
            val series = fetchSearch("series", encoded)
            runOnUiThread {
                if (gen != generation || isFinishing || isDestroyed) return@runOnUiThread
                renderResults((movies + series).distinctBy { it.id }, "RESULTADOS")
            }
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
                connectTimeout = 10_000
                readTimeout = 18_000
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
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private inner class ResultAdapter(private var items: List<Meta>) : RecyclerView.Adapter<ResultAdapter.Holder>() {
        inner class Holder(val card: LinearLayout, val art: FrameLayout, val image: ImageView, val name: TextView, val sub: TextView) : RecyclerView.ViewHolder(card)

        fun replace(list: List<Meta>) { items = list; notifyDataSetChanged() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = LinearLayout(this@NativeSearchActivity).apply {
                orientation = LinearLayout.VERTICAL
                setNextFocusUpId(input.id)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setPadding(dp(5), dp(5), dp(5), dp(5))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(158)).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }
            }
            val art = FrameLayout(this@NativeSearchActivity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(108))
                background = rounded(CARD, 12, BORDER, 1)
                clipToOutline = true
                outlineProvider = roundedOutline(dp(12))
            }
            val image = ImageView(this@NativeSearchActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
            art.addView(image)
            val name = TextView(this@NativeSearchActivity).apply {
                setTextColor(CREAM)
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
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
            return Holder(card, art, image, name, sub)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val meta = items[position]
            holder.name.text = meta.name
            holder.sub.text = listOfNotNull(meta.releaseInfo, meta.rating?.let { "★ $it" }).joinToString("  ·  ")
            NativeImageLoader.load(holder.image, meta.background ?: meta.poster, 3)
            holder.card.onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                holder.art.background = rounded(if (has) Color.rgb(40,43,52) else CARD, 12, if (has) CORAL else BORDER, if (has) 2 else 1)
                holder.name.setTextColor(if (has) Color.WHITE else CREAM)
                v.animate().scaleX(if (has) 1.035f else 1f).scaleY(if (has) 1.035f else 1f).setDuration(120).start()
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
        val normal = rounded(Color.rgb(18,20,26), 10, BORDER, 1)
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

    private data class Meta(
        val id: String, val type: String, val name: String, val poster: String?, val background: String?,
        val releaseInfo: String?, val rating: String?,
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
        private val INK = Color.rgb(7,8,11)
        private val CARD = Color.rgb(18,20,26)
        private val BORDER = Color.rgb(48,51,60)
        private val CREAM = Color.rgb(242,235,221)
        private val CORAL = Color.rgb(255,77,61)
    }
}
