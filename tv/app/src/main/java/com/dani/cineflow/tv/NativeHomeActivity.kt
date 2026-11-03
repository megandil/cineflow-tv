package com.dani.cineflow.tv

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/**
 * CineFlow's own front door. Deliberately not Stremio's board: a vertical CineFlow rail, an
 * editorial hero and wide, cinematic cards. Catalog data is Cinemeta; playback and the native
 * Canales/Guía screens stay where they are.
 */
class NativeHomeActivity : AppCompatActivity() {

    private lateinit var rail: LinearLayout
    private lateinit var heroPlay: TextView
    private lateinit var heroImage: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroDescription: TextView
    private lateinit var homeRoot: FrameLayout
    private var loadingOverlay: View? = null
    private var loadingStatus: TextView? = null
    private val continueTargets = HashMap<String, DirectPlayback.Target>()
    private lateinit var rows: LinearLayout
    private val railRows = mutableListOf<LinearLayout>()
    private var currentHero: Meta? = null
    private var moviesCache: List<Meta> = emptyList()
    private var filmsCache: List<Meta> = emptyList()
    private var seriesCache: List<Meta> = emptyList()
    private var extraCatalogsCache: List<ExtraRow> = emptyList()
    private var libraryCache: List<StremioSession.LibraryItem> = emptyList()
    private var firstRow: RecyclerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        setContentView(buildUi())
        loadCatalogs()
        UpdateManager.checkAndPrompt(this, manual = false)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        loadingOverlay?.let { if (it.parent != null) homeRoot.removeView(it) }
        loadingOverlay = null
        homeRoot.postDelayed({
            if (!isFinishing && (currentFocus == null || currentFocus === homeRoot)) heroPlay.requestFocus()
        }, 300)
        if (moviesCache.isNotEmpty() || seriesCache.isNotEmpty()) {
            render(moviesCache, filmsCache, seriesCache, libraryCache, extraCatalogsCache)
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.keyCode == android.view.KeyEvent.KEYCODE_BACK && loadingOverlay != null) {
            homeRoot.removeView(loadingOverlay)
            loadingOverlay = null
            loadingStatus = null
            return true
        }
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
            val focus = currentFocus
            if (focus === heroPlay || focus == null || focus === homeRoot) {
                val row = firstRow
                val view = row?.layoutManager?.findViewByPosition(0) ?: row?.getChildAt(0)
                if (view != null) {
                    view.requestFocus()
                    return true
                }
                heroPlay.requestFocus()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(INK) }
        homeRoot = root
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        rail = buildRail()
        shell.addView(rail, LinearLayout.LayoutParams(dp(194), ViewGroup.LayoutParams.MATCH_PARENT))
        shell.addView(buildContent(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(shell, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return root
    }

    private fun buildRail(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(RAIL)
            setPadding(dp(22), dp(20), dp(18), dp(12))
        }
        val mark = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mark.addView(TextView(this).apply {
            text = "CINE"
            setTextColor(CREAM)
            textSize = 21f
            letterSpacing = .16f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            includeFontPadding = false
        })
        mark.addView(TextView(this).apply {
            text = "FLOW"
            setTextColor(CORAL)
            textSize = 21f
            letterSpacing = .16f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            includeFontPadding = false
        })
        box.addView(mark)
        box.addView(View(this), LinearLayout.LayoutParams(1, dp(20)))
        box.addView(railLine())
        box.addView(navItem(R.drawable.ic_cf_home, "Inicio", true) { /* already here */ })
        box.addView(navItem(R.drawable.ic_cf_compass, "Explorar", false) { startActivity(Intent(this, NativeSearchActivity::class.java)) })
        box.addView(railSection("EN DIRECTO"))
        box.addView(navItem(R.drawable.ic_cf_live, "Canales", false) { startActivity(Intent(this, ChannelsActivity::class.java)) })
        box.addView(navItem(R.drawable.ic_cf_guide, "Guía", false) { startActivity(Intent(this, GuideActivity::class.java)) })
        box.addView(railSection("TU CUENTA"))
        box.addView(navItem(R.drawable.ic_cf_library, "Biblioteca", false) { startActivity(Intent(this, NativeLibraryActivity::class.java)) })
        box.addView(navItem(R.drawable.ic_cf_addons, "Addons", false) { startActivity(Intent(this, NativeAddonsActivity::class.java)) })
        box.addView(navItem(R.drawable.ic_cf_account, "Cuenta", false) { startActivity(Intent(this, NativeAccountActivity::class.java)) })
        box.addView(navItem(R.drawable.ic_cf_settings, "Ajustes", false) { startActivity(Intent(this, NativeSettingsActivity::class.java)) })
        box.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        box.addView(railLine())
        // Logo only, by request. The edition mark is intentionally left out.
        return box
    }


    private fun railSection(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(CORAL)
        textSize = 7f
        letterSpacing = .16f
        setPadding(dp(13), dp(8), 0, dp(3))
    }

    private fun navItem(iconRes: Int, label: String, selected: Boolean, action: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = navBackground(selected)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply { bottomMargin = dp(3) }
            setOnClickListener { action() }
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(if (selected) CREAM else Color.rgb(166,170,180))
            layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).apply { rightMargin = dp(11) }
        }
        val text = TextView(this).apply {
            this.text = label
            setTextColor(if (selected) CREAM else Color.rgb(180,183,192))
            textSize = 10.5f
            letterSpacing = .03f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(icon)
        row.addView(text)
        row.onFocusChangeListener = View.OnFocusChangeListener { v, has ->
            v.background = navBackground(selected, has)
            text.setTextColor(if (has) Color.WHITE else if (selected) CREAM else Color.rgb(180,183,192))
            icon.setColorFilter(if (has) CORAL else if (selected) CREAM else Color.rgb(166,170,180))
        }
        railRows += row
        return row
    }

    private fun railLine(): View = View(this).apply {
        setBackgroundColor(Color.rgb(35,38,46))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { bottomMargin = dp(12) }
    }

    private fun buildContent(): ScrollView {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
            setBackgroundColor(INK)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(22), dp(32), dp(30))
        }
        col.addView(TextView(this).apply {
            text = "SELECCIÓN DE HOY"
            setTextColor(Color.rgb(132,136,146))
            textSize = 8f
            letterSpacing = .2f
            setPadding(0, 0, 0, dp(10))
        })
        val hero = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(286))
            background = rounded(CARD, 18, Color.TRANSPARENT, 0)
            clipToOutline = true
            outlineProvider = roundedOutline(dp(18))
        }
        heroImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        hero.addView(heroImage)
        hero.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x22000000, 0x22000000, 0xF207080B.toInt()))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        })
        val heroText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(18))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        heroText.addView(TextView(this).apply {
            text = "DESTACADO"
            setTextColor(CORAL)
            textSize = 8f
            letterSpacing = .22f
            typeface = Typeface.DEFAULT_BOLD
        })
        heroTitle = TextView(this).apply {
            text = "Cargando…"
            setTextColor(CREAM)
            textSize = 34f
            maxLines = 2
            typeface = Typeface.create("serif", Typeface.BOLD)
            includeFontPadding = false
            setPadding(0, dp(4), 0, dp(5))
        }
        heroText.addView(heroTitle)
        heroMeta = TextView(this).apply {
            text = ""
            setTextColor(Color.rgb(190,195,202))
            textSize = 10f
            letterSpacing = .05f
        }
        heroText.addView(heroMeta)
        heroDescription = TextView(this).apply {
            text = ""
            setTextColor(Color.rgb(170,174,183))
            textSize = 11f
            maxLines = 3
            setPadding(0, dp(7), 0, dp(11))
        }
        heroText.addView(heroDescription)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        heroPlay = pill("▶   VER AHORA", true)
        heroPlay.id = View.generateViewId()
        heroPlay.setOnClickListener { openDetail(currentHero) }
        heroPlay.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN || keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            ) {
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                    val row = firstRow
                    val view = row?.layoutManager?.findViewByPosition(0) ?: row?.getChildAt(0)
                    if (view != null) {
                        view.requestFocus()
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
        val more = pill("MÁS INFORMACIÓN", false)
        more.setOnClickListener { openDetail(currentHero) }
        actions.addView(heroPlay, LinearLayout.LayoutParams(dp(170), dp(38)).apply { rightMargin = dp(8) })
        actions.addView(more, LinearLayout.LayoutParams(dp(160), dp(38)))
        heroText.addView(actions)
        hero.addView(heroText)
        col.addView(hero)
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(15), 0, 0)
        }
        col.addView(rows)
        scroll.addView(col)
        return scroll
    }

    private fun render(
        movies: List<Meta>,
        films: List<Meta>,
        series: List<Meta>,
        library: List<StremioSession.LibraryItem> = emptyList(),
        extraCatalogs: List<ExtraRow> = emptyList(),
    ) {
        if (isFinishing || isDestroyed) return
        val feature = movies.firstOrNull() ?: series.firstOrNull()
        currentHero = feature
        if (feature != null) {
            heroTitle.text = feature.name
            heroMeta.text = listOfNotNull(
                feature.releaseInfo?.takeIf { it.isNotBlank() },
                feature.rating?.let { "★ $it" },
                feature.genres?.take(2)?.joinToString("  ·  "),
            ).joinToString("     ")
            heroDescription.text = feature.description.orEmpty()
            NativeImageLoader.load(heroImage, feature.background ?: feature.poster, 2)
        }
        rows.removeAllViews()
        firstRow = null
        val local = NativeProgressStore.list(this)
        val seen = mutableSetOf<String>()
        val continueList = ArrayList<Meta>()
        for (item in local) {
            if (!seen.add(item.id)) continue
            continueList.add(Meta(item.id, item.type, item.name, item.poster, item.poster, if (item.season > 0) "T${item.season} E${item.episode}" else null, null, null, null))
        }
        for (item in library.filter { it.timeOffset > 0 }.sortedByDescending { it.timeOffset }) {
            if (!seen.add(item.id)) continue
            val parts = item.videoId?.split(':').orEmpty()
            val season = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 0
            val episode = parts.getOrNull(parts.size - 1)?.toIntOrNull() ?: 0
            continueList.add(Meta(item.id, item.type, item.name, item.poster, item.poster, if (season > 0) "T$season E$episode" else "Continuar", null, null, null))
        }
        val continueItems = continueList.take(12)
        continueTargets.clear()
        for (item in local) {
            continueTargets[item.id] = DirectPlayback.Target(item.id, item.type, item.name, item.poster, item.videoId, item.position, item.season, item.episode, item.episodeTitle)
        }
        for (item in library.filter { it.timeOffset > 0 }) {
            val parts = item.videoId?.substringAfterLast(':')?.let { _ -> item.videoId.split(':') } ?: emptyList()
            val season = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 0
            val episode = parts.getOrNull(parts.size - 1)?.toIntOrNull() ?: 0
            continueTargets.putIfAbsent(item.id, DirectPlayback.Target(item.id, item.type, item.name, item.poster, item.videoId, item.timeOffset, season, episode, null))
        }
        if (continueItems.isNotEmpty()) rows.addView(section("", "CONTINUAR VIENDO", continueItems, "movie", autoResume = true))
        if (movies.isNotEmpty()) rows.addView(section("", "TENDENCIAS", movies, "movie"))
        if (films.isNotEmpty()) rows.addView(section("", "PELÍCULAS", films, "movie"))
        if (series.isNotEmpty()) rows.addView(section("", "SERIES PARA ENGANCHARSE", series, "series"))
        for (row in extraCatalogs) {
            if (row.items.isNotEmpty()) rows.addView(section("", row.title.uppercase(Locale.ROOT), row.items, row.type))
        }
        rows.addView(buildLive())
        rows.addView(TextView(this).apply {
            text = "CINEFLOW · UNA EXPERIENCIA PROPIA"
            setTextColor(Color.rgb(92,96,106))
            textSize = 7f
            letterSpacing = .18f
            setPadding(0, dp(22), 0, 0)
        })
        heroPlay.post { heroPlay.requestFocus() }
        for (row in railRows) row.setNextFocusRightId(heroPlay.id)
    }

    private fun section(number: String, title: String, list: List<Meta>, type: String, autoResume: Boolean = false): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(15), 0, dp(8))
        }
        head.addView(ImageView(this).apply {
            setImageResource(
                when {
                    title.contains("CONTINUAR", ignoreCase = true) -> R.drawable.ic_cf_history
                    title.contains("TENDENCIA", ignoreCase = true) -> R.drawable.ic_cf_flame
                    type == "series" -> R.drawable.ic_cf_series
                    else -> R.drawable.ic_cf_film
                }
            )
            setColorFilter(CORAL)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(10) }
        })
        head.addView(TextView(this).apply {
            text = title
            setTextColor(CREAM)
            textSize = 11f
            letterSpacing = .12f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val verTodoId = View.generateViewId()
        head.addView(TextView(this).apply {
            text = "VER TODO  →"
            id = verTodoId
            setTextColor(CREAM)
            textSize = 8f
            letterSpacing = .08f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = rounded(Color.rgb(20,22,28), 8, Color.rgb(60,63,74), 1)
            setOnClickListener { startActivity(Intent(this@NativeHomeActivity, NativeSearchActivity::class.java)) }
            onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                v.background = rounded(if (has) Color.WHITE else Color.rgb(20,22,28), 8,
                    if (has) Color.WHITE else Color.rgb(60,63,74), if (has) 2 else 1)
                (v as TextView).setTextColor(if (has) Color.rgb(12,13,16) else CREAM)
                v.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f).setDuration(120).start()
            }
        })
        box.addView(head)
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@NativeHomeActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = CardAdapter(list, type, autoResume, verTodoId)
            if (firstRow == null) firstRow = this
            isFocusable = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, dp(2), dp(28), dp(2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(164))
        }
        box.addView(rv)
        return box
    }

    private fun buildLive(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(15), 0, dp(8)) }
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_cf_live)
            setColorFilter(CORAL)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(10) }
        })
        head.addView(TextView(this).apply { text = "EN DIRECTO"; setTextColor(CREAM); textSize = 11f; letterSpacing = .12f; typeface = Typeface.DEFAULT_BOLD })
        box.addView(head)
        val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cards.addView(liveCard("CANALES", "759 señales · AceStream", CORAL) { startActivity(Intent(this, ChannelsActivity::class.java)) }, LinearLayout.LayoutParams(0, dp(92), 1f).apply { rightMargin = dp(10) })
        cards.addView(liveCard("GUÍA", "EPG · ahora y después", MINT) { startActivity(Intent(this, GuideActivity::class.java)) }, LinearLayout.LayoutParams(0, dp(92), 1f).apply { leftMargin = dp(10) })
        box.addView(cards)
        return box
    }

    private fun liveCard(label: String, sub: String, accent: Int, action: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = rounded(Color.rgb(17,19,25), 14, accent, 1)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            clipToOutline = true
            outlineProvider = roundedOutline(dp(14))
            setOnClickListener { action() }
        }
        box.addView(TextView(this).apply { text = label; setTextColor(CREAM); textSize = 16f; letterSpacing = .08f; typeface = Typeface.DEFAULT_BOLD })
        box.addView(TextView(this).apply { text = sub; setTextColor(Color.rgb(154,158,168)); textSize = 9f; setPadding(0, dp(4), 0, 0) })
        box.addView(TextView(this).apply { text = "ENTRAR  →"; setTextColor(accent); textSize = 8f; letterSpacing = .12f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(9), 0, 0) })
        box.onFocusChangeListener = View.OnFocusChangeListener { v, has ->
            v.background = rounded(if (has) Color.rgb(27,30,38) else Color.rgb(17,19,25), 14, if (has) accent else Color.rgb(45,48,56), if (has) 2 else 1)
            v.animate().scaleX(if (has) 1.02f else 1f).scaleY(if (has) 1.02f else 1f).setDuration(130).start()
        }
        return box
    }

    private fun pill(text: String, filled: Boolean): TextView = TextView(this).apply {
        this.text = text
        setTextColor(if (filled) Color.rgb(12,13,16) else CREAM)
        textSize = 8f
        letterSpacing = .1f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        background = if (filled) rounded(CORAL, 9, Color.TRANSPARENT, 0) else rounded(Color.rgb(20,22,28), 9, Color.rgb(80,84,94), 1)
        setOnFocusChangeListener { v, has ->
            v.background = if (filled) rounded(if (has) Color.WHITE else CORAL, 9, Color.TRANSPARENT, 0)
            else rounded(if (has) Color.rgb(44,47,56) else Color.rgb(20,22,28), 9, if (has) CREAM else Color.rgb(80,84,94), if (has) 2 else 1)
            v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f).setDuration(120).start()
        }
    }

    private inner class CardAdapter(
        private val items: List<Meta>,
        private val fallbackType: String,
        private val autoResume: Boolean = false,
        private val upFocusId: Int = View.NO_ID,
    ) : RecyclerView.Adapter<CardAdapter.Holder>() {
        inner class Holder(val card: LinearLayout, val frame: FrameLayout, val image: ImageView, val name: TextView, val sub: TextView) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = LinearLayout(this@NativeHomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                setNextFocusUpId(upFocusId)
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(2), dp(2), dp(2), dp(2))
                layoutParams = RecyclerView.LayoutParams(dp(218), dp(158)).apply { rightMargin = dp(10) }
            }
            val frame = FrameLayout(this@NativeHomeActivity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(118))
                background = rounded(Color.TRANSPARENT, 13, Color.TRANSPARENT, 0)
                clipToOutline = true
                outlineProvider = roundedOutline(dp(13))
            }
            val image = ImageView(this@NativeHomeActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
            frame.addView(image)
            val name = TextView(this@NativeHomeActivity).apply {
                setTextColor(CREAM)
                textSize = 11f
                maxLines = 1
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(3), dp(6), dp(3), 0)
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val sub = TextView(this@NativeHomeActivity).apply {
                setTextColor(Color.rgb(132,136,146))
                textSize = 8f
                maxLines = 1
                setPadding(dp(3), dp(2), dp(3), 0)
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            card.addView(frame)
            card.addView(name)
            card.addView(sub)
            return Holder(card, frame, image, name, sub)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val meta = items[position]
            holder.name.text = meta.name
            holder.sub.text = listOfNotNull(meta.releaseInfo, meta.rating?.let { "★ $it" }).joinToString("   ")
            NativeImageLoader.load(holder.image, meta.background ?: meta.poster, 3)
            holder.card.onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                holder.frame.background = rounded(Color.TRANSPARENT, 13, if (has) CORAL else Color.TRANSPARENT, if (has) 2 else 0)
                holder.name.setTextColor(if (has) Color.WHITE else CREAM)
                v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f).setDuration(120).start()
                if (has) v.bringToFront()
            }
            holder.card.setOnClickListener {
                if (autoResume) playContinue(meta) else openDetail(meta, fallbackType)
            }
        }

        override fun getItemCount(): Int = items.size
    }


    private fun playContinue(meta: Meta) {
        val target = continueTargets[meta.id] ?: return
        loadingOverlay?.let { if (it.parent != null) homeRoot.removeView(it) }
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xF207080B.toInt())
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(34), dp(28), dp(34), dp(26))
            background = rounded(Color.rgb(17,19,25), 16, Color.rgb(58,61,70), 1)
            layoutParams = FrameLayout.LayoutParams(dp(390), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        box.addView(ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(CORAL)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { gravity = Gravity.CENTER; bottomMargin = dp(14) }
        })
        val label = TextView(this).apply {
            text = "Abriendo ${target.name}…"
            setTextColor(CREAM)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(20), 0, dp(20), 0)
        }
        box.addView(label)
        val hint = TextView(this).apply {
            text = "Buscando proveedores…"
            setTextColor(Color.rgb(174,178,188))
            textSize = 9.5f
            gravity = Gravity.CENTER
            setMaxLines(2)
            setPadding(0, dp(7), 0, 0)
        }
        box.addView(hint)
        box.addView(TextView(this).apply {
            text = "Atrás para cancelar"
            setTextColor(Color.rgb(118,123,133))
            textSize = 8f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        overlay.addView(box)
        loadingStatus = hint
        loadingOverlay = overlay
        homeRoot.addView(overlay)
        DirectPlayback.play(this, target, onStatus = { status ->
            runOnUiThread { loadingStatus?.text = status }
        }) { error ->
            loadingOverlay?.let { if (it.parent != null) homeRoot.removeView(it) }
            loadingOverlay = null
            loadingStatus = null
            android.widget.Toast.makeText(this, error, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        loadingOverlay?.let { if (it.parent != null) homeRoot.removeView(it) }
        loadingOverlay = null
        loadingStatus = null
        super.onPause()
    }

    private fun openDetail(meta: Meta?, type: String = "movie", autoResume: Boolean = false) {
        if (meta == null) return
        startActivity(Intent(this, NativeDetailActivity::class.java)
            .putExtra(NativeDetailActivity.EXTRA_ID, meta.id)
            .putExtra(NativeDetailActivity.EXTRA_TYPE, meta.type ?: type)
            .putExtra(NativeDetailActivity.EXTRA_AUTOPLAY, autoResume))
    }


    private fun loadCatalogs() {
        thread(name = "cineflow-native-home", isDaemon = true) {
            // Refresh the addon list once on launch so removals made in Stremio are reflected here.
            if (StremioSession.isLoggedIn(this)) StremioSession.addons(this, force = true)
            val movies = fetch("movie", "top")
            val films = fetch("movie", "imdbRating")
            val series = fetch("series", "top")
            val library = if (StremioSession.isLoggedIn(this)) StremioSession.library(this) else emptyList()
            runOnUiThread {
                moviesCache = movies
                filmsCache = films
                seriesCache = series
                libraryCache = library
                render(movies, films, series, library, extraCatalogsCache)
            }
            // Addon catalogs load after Home is usable: a slow catalog must never block
            // the hero and the Cinemeta rows.
            if (StremioSession.isLoggedIn(this)) {
                val extras = StremioSession.catalogs(this)
                    .filter { !it.addon.equals("Cinemeta", ignoreCase = true) }
                    .distinctBy { it.title.lowercase(Locale.ROOT) }
                    .sortedBy { if (it.addon.contains("TMDB", ignoreCase = true)) 0 else 1 }
                    .take(2)
                    .mapNotNull { cat ->
                        val items = fetchCatalog(cat.url, cat.type)
                        if (items.isEmpty()) null else ExtraRow(cat.title, cat.type, items)
                    }
                if (extras.isNotEmpty()) runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    extraCatalogsCache = extras
                    render(moviesCache, filmsCache, seriesCache, libraryCache, extras)
                }
            }
        }
    }

    private fun fetchCatalog(url: String, type: String): List<Meta> {
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 7_000
                setRequestProperty("User-Agent", "CineFlow-TV")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val metas = JSONObject(body).optJSONArray("metas") ?: return emptyList()
            val parsed = (0 until metas.length()).mapNotNull { i ->
                val o = metas.optJSONObject(i) ?: return@mapNotNull null
                Meta(
                    id = o.optString("imdb_id").ifBlank { o.optString("id") },
                    type = o.optString("type").ifBlank { type },
                    name = o.optString("name"),
                    poster = o.optString("poster").takeIf { it.isNotBlank() },
                    background = o.optString("background").takeIf { it.isNotBlank() },
                    releaseInfo = o.optString("releaseInfo").takeIf { it.isNotBlank() },
                    rating = o.optString("imdbRating").takeIf { it.isNotBlank() },
                    description = o.optString("description").takeIf { it.isNotBlank() },
                    genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.optString(it) } },
                )
            }
            parsed
        } catch (_: Exception) { emptyList() }
    }

    private data class ExtraRow(val title: String, val type: String, val items: List<Meta>)

    private fun fetch(type: String, catalog: String = "top"): List<Meta> {
        return try {
            val connection = (URL("https://v3-cinemeta.strem.io/catalog/$type/$catalog.json").openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "CineFlow-TV")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val metas = JSONObject(body).optJSONArray("metas") ?: return emptyList()
            val parsed = (0 until metas.length()).mapNotNull { i ->
                val o = metas.optJSONObject(i) ?: return@mapNotNull null
                Meta(
                    id = o.optString("imdb_id").ifBlank { o.optString("id") },
                    type = o.optString("type").ifBlank { type },
                    name = o.optString("name"),
                    poster = o.optString("poster").takeIf { it.isNotBlank() },
                    background = o.optString("background").takeIf { it.isNotBlank() },
                    releaseInfo = o.optString("releaseInfo").takeIf { it.isNotBlank() },
                    rating = o.optString("imdbRating").takeIf { it.isNotBlank() },
                    description = o.optString("description").takeIf { it.isNotBlank() },
                    genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.optString(it) } },
                )
            }
            parsed
        } catch (_: Exception) {
            emptyList()
        }
    }

    private data class Meta(
        val id: String,
        val type: String,
        val name: String,
        val poster: String?,
        val background: String?,
        val releaseInfo: String?,
        val rating: String?,
        val description: String?,
        val genres: List<String>?,
    )

    private fun rounded(color: Int, radius: Int, stroke: Int, strokeWidth: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (strokeWidth > 0) setStroke(dp(strokeWidth), stroke)
    }

    private fun roundedOutline(radius: Int): ViewOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius.toFloat())
        }
    }

    private fun navBackground(selected: Boolean, focused: Boolean = false): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(Color.rgb(29,32,40), 8, CORAL, 1))
        addState(intArrayOf(), if (selected) rounded(Color.rgb(24,26,33), 8, Color.TRANSPARENT, 0) else rounded(Color.TRANSPARENT, 8, Color.TRANSPARENT, 0))
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
        private val INK = Color.rgb(7, 8, 11)
        private val RAIL = Color.rgb(11, 13, 18)
        private val CARD = Color.rgb(18, 20, 26)
        private val CREAM = Color.rgb(242, 235, 221)
        private val CORAL = Color.rgb(255, 77, 61)
        private val MINT = Color.rgb(145, 227, 207)
    }
}
