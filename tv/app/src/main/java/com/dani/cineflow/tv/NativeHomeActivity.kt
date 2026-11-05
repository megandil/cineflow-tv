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
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
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
    private lateinit var heroMore: TextView
    private lateinit var heroImage: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroLogo: ImageView
    private lateinit var heroBadges: LinearLayout
    private lateinit var heroDots: LinearLayout
    private lateinit var heroKicker: TextView
    private lateinit var heroText: LinearLayout
    private var heroTransitioning = false
    private val heroHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var heroItems: List<Meta> = emptyList()
    private var heroIndex = 0
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

    private var renderedProfile: String? = null
    private lateinit var scrim: View
    private lateinit var breadcrumb: LinearLayout
    private lateinit var contentRoot: View
    private lateinit var homeScroll: ScrollView
    private var railOpen = false
    private val railHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val railHide = Runnable {
        if (railOpen && !railHasFocus()) closeRail()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        renderedProfile = Profiles.activeId(this)
        setContentView(buildUi())
        if (Profiles.list(this).size > 1 && !intent.getBooleanExtra(EXTRA_PROFILE_SELECTED, false)) {
            startActivity(Intent(this, ProfilePickerActivity::class.java))
        }
        loadCatalogs()
        UpdateManager.checkAndPrompt(this, manual = false)
        CatalogPrompt.checkIfNeeded(this) { loadCatalogs() }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        loadingOverlay?.let { if (it.parent != null) homeRoot.removeView(it) }
        loadingOverlay = null
        homeRoot.postDelayed({
            if (!isFinishing && (currentFocus == null || currentFocus === homeRoot)) heroPlay.requestFocus()
        }, 300)
        val activeProfile = Profiles.activeId(this)
        if (activeProfile != renderedProfile) {
            renderedProfile = activeProfile
            moviesCache = emptyList()
            filmsCache = emptyList()
            seriesCache = emptyList()
            extraCatalogsCache = emptyList()
            libraryCache = emptyList()
            loadCatalogs()
        } else if (moviesCache.isNotEmpty() || seriesCache.isNotEmpty()) {
            render(moviesCache, filmsCache, seriesCache, libraryCache, extraCatalogsCache)
        }
        // Covers returning from the QR login screen: the addon check runs again once the
        // account is available. CatalogPrompt rate-limits itself.
        CatalogPrompt.checkIfNeeded(this) { loadCatalogs() }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.keyCode == android.view.KeyEvent.KEYCODE_BACK && railOpen) {
            closeRail()
            return true
        }
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.keyCode == android.view.KeyEvent.KEYCODE_BACK && loadingOverlay != null) {
            homeRoot.removeView(loadingOverlay)
            loadingOverlay = null
            loadingStatus = null
            return true
        }
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
            if (railOpen) return false
            if (currentFocus === heroMore) {
                heroPlay.requestFocus()
                return true
            }
            if (currentFocus === heroPlay) {
                openRail()
                return true
            }
            if (moveLeftInsideRow(currentFocus)) return true
            return true
        }
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (railOpen && railHasFocus()) {
                closeRail()
                return true
            }
        }
        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
            (event.keyCode == android.view.KeyEvent.KEYCODE_MENU || event.keyCode == android.view.KeyEvent.KEYCODE_SETTINGS)
        ) {
            if (railOpen) {
                closeRail()
                return true
            }
            var view: View? = currentFocus
            while (view != null) {
                val meta = view.tag as? Meta
                if (meta != null) {
                    askRemoveFromContinue(meta)
                    return true
                }
                view = view.parent as? View
            }
            openRail()
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

        // Full-width content. The rail floats above it and hides when not needed.
        contentRoot = buildContent()
        root.addView(contentRoot, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        breadcrumb = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(11), dp(4))
            background = rounded(0xCC141416.toInt(), 20, 0x24FFFFFF, 1)
            elevation = dp(10).toFloat()
            isClickable = true
            isFocusable = false
            setOnClickListener { openRail() }
        }
        breadcrumb.addView(TextView(this).apply {
            text = "‹"
            setTextColor(Color.rgb(235,235,238))
            textSize = 14f
            setPadding(0, 0, dp(5), 0)
        })
        breadcrumb.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_cf_home)
            setColorFilter(Color.rgb(235,235,238))
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(5) }
        })
        breadcrumb.addView(TextView(this).apply {
            text = "Inicio"
            setTextColor(Color.rgb(235,235,238))
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        root.addView(breadcrumb, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(22)
            topMargin = dp(22)
            gravity = Gravity.TOP or Gravity.START
        })

        scrim = View(this).apply {
            setBackgroundColor(0xA6000000.toInt())
            alpha = 0f
            visibility = View.GONE
            isClickable = true
            setOnClickListener { closeRail() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        rail = buildRail()
        rail.visibility = View.GONE
        rail.alpha = 0f
        rail.scaleX = 0.96f
        rail.scaleY = 0.96f
        root.addView(rail, FrameLayout.LayoutParams(
            dp(180), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(24)
            topMargin = dp(20)
        })
        return root
    }

    private fun railHasFocus(): Boolean {
        var v = currentFocus ?: return false
        while (v is View) {
            if (v === rail) return true
            v = v.parent as? View ?: return false
        }
        return false
    }

    private fun openRail() {
        if (railOpen) {
            railRows.firstOrNull()?.requestFocus()
            return
        }
        railOpen = true
        railHandler.removeCallbacks(railHide)
        val blurred = BlurGlass.capture(this, contentRoot, 25f)
        if (blurred != null) {
            val glass = androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(resources, blurred)
            glass.cornerRadius = dp(20).toFloat()
            glass.setAntiAlias(true)
            glass.alpha = 38
            val base = rounded(0xED161618.toInt(), 20, 0x24FFFFFF, 1)
            val shade = rounded(0x99000000.toInt(), 20, Color.TRANSPARENT, 0)
            rail.background = android.graphics.drawable.LayerDrawable(arrayOf(base, glass, shade))
        }
        rail.visibility = View.VISIBLE
        rail.translationX = -dp(220).toFloat()
        rail.scaleX = 0.96f
        rail.scaleY = 0.96f
        rail.animate().translationX(0f).scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(240L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        breadcrumb.animate().alpha(0f).setDuration(160L).start()
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(260L).start()
        (railRows.getOrNull(1) ?: railRows.firstOrNull())?.requestFocus()
        railHandler.postDelayed(railHide, 6000L)
    }

    private fun closeRail() {
        if (!railOpen) return
        railOpen = false
        railHandler.removeCallbacks(railHide)
        rail.animate().translationX(-dp(220).toFloat()).scaleX(0.96f).scaleY(0.96f).alpha(0f)
            .setDuration(200L)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                if (!railOpen) {
                    rail.visibility = View.GONE
                    heroPlay.requestFocus()
                }
            }.start()
        breadcrumb.animate().alpha(1f).setDuration(220L).start()
        breadcrumb.isClickable = true
        scrim.animate().alpha(0f).setDuration(220L)
            .withEndAction { if (!railOpen) scrim.visibility = View.GONE }.start()
        heroPlay.requestFocus()
        railHandler.postDelayed({ if (!railOpen) heroPlay.requestFocus() }, 260L)
    }

    private fun buildRail(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            clipChildren = false
            clipToPadding = false
            background = GradientDrawable().apply {
                setColor(0xF21A1A1C.toInt())
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0x2EFFFFFF)
            }
            elevation = dp(20).toFloat()
        }
        fun item(icon: Int, label: String, action: () -> Unit) {
            box.addView(navItem(icon, label, false, action))
        }
        item(R.drawable.ic_cf_search_outline, "Buscar") { startActivity(Intent(this, SearchActivity::class.java)) }
        item(R.drawable.ic_cf_home_outline, "Inicio") { /* already here */ }
        item(R.drawable.ic_cf_series_outline, "Series") {
            startActivity(Intent(this, CatalogActivity::class.java).putExtra(CatalogActivity.EXTRA_TYPE, "series"))
        }
        item(R.drawable.ic_cf_film_outline, "Películas") {
            startActivity(Intent(this, CatalogActivity::class.java).putExtra(CatalogActivity.EXTRA_TYPE, "movie"))
        }
        item(R.drawable.ic_cf_live_outline, "TV") { startActivity(Intent(this, ChannelsActivity::class.java)) }
        item(R.drawable.ic_cf_library_outline, "Biblioteca") { startActivity(Intent(this, NativeLibraryActivity::class.java)) }
        item(R.drawable.ic_cf_settings_outline, "Ajustes") { startActivity(Intent(this, NativeSettingsActivity::class.java)) }
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
            setPadding(dp(12), 0, dp(8), 0)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = navBackground(false)
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply { bottomMargin = dp(1) }
            setOnClickListener { closeRail(); action() }
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(CREAM)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(12) }
        }
        val text = TextView(this).apply {
            this.text = label
            setTextColor(CREAM)
            textSize = 12.5f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(icon)
        row.addView(text)
        row.onFocusChangeListener = View.OnFocusChangeListener { v, has ->
            v.background = navBackground(has)
            text.setTextColor(if (has) Color.rgb(10,10,12) else CREAM)
            icon.setColorFilter(if (has) Color.rgb(10,10,12) else CREAM)
            v.animate().scaleX(1f).scaleY(1f).setDuration(1L).start()
            if (has) {
                railHandler.removeCallbacks(railHide)
            } else if (railOpen) {
                railHandler.postDelayed({
                    if (railOpen && !railHasFocus()) closeRail()
                }, 220L)
            }
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
            homeScroll = this
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
            setBackgroundColor(INK)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(34))
        }
        val hero = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(370))
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            clipChildren = false
        }
        heroImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        hero.addView(heroImage)
        hero.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x55000000, 0x33000000, 0xF207080B.toInt()))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        })
        heroText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(80), dp(24), dp(80), dp(38))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        heroTitle = TextView(this).apply {
            text = "Cargando…"
            setTextColor(Color.WHITE)
            textSize = 44f
            maxLines = 2
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = -0.02f
            includeFontPadding = false
            setPadding(0, dp(6), 0, dp(8))
        }
        heroKicker = TextView(this).apply {
            text = "RECIÉN AÑADIDO"
            setTextColor(Color.rgb(215,215,220))
            textSize = 10f
            letterSpacing = .08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 0, 0, dp(9))
        }
        heroText.addView(heroKicker)
        heroLogo = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(112)).apply { bottomMargin = dp(2) }
            maxWidth = dp(700)
            maxHeight = dp(100)
        }
        heroText.addView(heroLogo)
        heroText.addView(heroTitle)
        heroBadges = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        heroText.addView(heroBadges)
        heroMeta = TextView(this).apply {
            text = ""
            setTextColor(Color.rgb(220,220,225))
            textSize = 12.5f
            letterSpacing = .01f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            visibility = View.GONE
        }
        heroText.addView(heroMeta)
        heroDescription = TextView(this).apply {
            text = ""
            setTextColor(Color.rgb(205,205,210))
            textSize = 14f
            maxLines = 3
            setLineSpacing(dp(2).toFloat(), 1f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setPadding(0, dp(10), 0, dp(16))
        }
        heroText.addView(heroDescription)
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), 0, dp(6), 0)
            clipChildren = false
            clipToPadding = false
        }
        heroPlay = pill("▶   Reproducir", true)
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
        val more = pill("Más información", false)
        heroMore = more
        more.id = View.generateViewId()
        more.setOnClickListener { openDetail(currentHero) }
        heroPlay.nextFocusRightId = more.id
        more.nextFocusLeftId = heroPlay.id
        more.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                heroPlay.requestFocus()
                true
            } else false
        }
        actions.addView(heroPlay, LinearLayout.LayoutParams(dp(170), dp(40)).apply { rightMargin = dp(9) })
        actions.addView(more, LinearLayout.LayoutParams(dp(160), dp(40)))
        heroText.addView(actions)
        hero.addView(heroText)
        heroDots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(44)
                bottomMargin = dp(24)
            }
        }
        repeat(5) { index ->
            heroDots.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (index == 0) Color.WHITE else 0x66FFFFFF)
                }
                layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                    leftMargin = dp(4)
                    rightMargin = dp(4)
                }
            })
        }
        hero.addView(heroDots)
        col.addView(hero)
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(80), dp(18), dp(80), 0)
        }
        col.addView(rows)
        scroll.addView(col)
        return scroll
    }

    private fun showHero(feature: Meta, animate: Boolean = true) {
        if (!animate || currentHero == null || heroTransitioning) {
            applyHero(feature)
            return
        }
        heroTransitioning = true
        heroImage.animate().alpha(0f).setDuration(220L).start()
        heroText.animate().alpha(0f).translationY(dp(10).toFloat()).setDuration(200L).withEndAction {
            applyHero(feature)
            heroText.alpha = 0f
            heroText.translationY = dp(10).toFloat()
            heroText.animate().alpha(1f).translationY(0f).setDuration(340L).start()
            heroImage.alpha = 0f
            heroImage.animate().alpha(1f).setDuration(460L).setStartDelay(180L).start()
            heroTransitioning = false
        }.start()
    }

    private fun applyHero(feature: Meta) {
        currentHero = feature
        heroTitle.text = feature.name
        if (heroIndex == 0 && !feature.logo.isNullOrBlank() && !feature.name.startsWith("The ", true) && !feature.name.startsWith("A ", true)) {
            heroLogo.visibility = View.VISIBLE
            heroTitle.visibility = View.GONE
            NativeImageLoader.load(heroLogo, feature.logo, 2, "original")
        } else {
            heroLogo.visibility = View.GONE
            heroTitle.visibility = View.VISIBLE
            loadHeroLogo(feature)
        }
        heroMeta.text = ""
        heroBadges.removeAllViews()
        fun addHeroBadge(value: String?) {
            if (value.isNullOrBlank()) return
            heroBadges.addView(TextView(this).apply {
                text = value
                setTextColor(Color.rgb(240,240,244))
                textSize = 9.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(7), dp(3), dp(7), dp(3))
                background = rounded(0x33FFFFFF, 4, 0x66FFFFFF, 1)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }
            })
        }
        addHeroBadge(feature.releaseInfo)
        addHeroBadge(feature.rating?.let { "★ $it" })
        feature.genres?.take(3)?.forEach { addHeroBadge(it) }
        heroDescription.text = feature.description.orEmpty()
        NativeImageLoader.load(heroImage, feature.background ?: feature.poster, 2, "original")
        if (heroText.alpha > 0f) heroImage.alpha = 1f
        for (i in 0 until heroDots.childCount) {
            val dot = heroDots.getChildAt(i)
            (dot.background as? GradientDrawable)?.setColor(if (i == heroIndex % 5) Color.WHITE else 0x66FFFFFF)
        }
    }

    private fun startHeroRotation() {
        heroHandler.removeCallbacksAndMessages(null)
        if (heroItems.size <= 1) return
        val tick = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                if (!railOpen && !heroTransitioning && heroItems.isNotEmpty()) {
                    heroIndex = (heroIndex + 1) % heroItems.size
                    showHero(heroItems[heroIndex])
                }
                heroHandler.postDelayed(this, 8000L)
            }
        }
        heroHandler.postDelayed(tick, 8000L)
    }

    override fun onDestroy() {
        heroHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun loadHeroLogo(feature: Meta) {
        if (!feature.logo.isNullOrBlank()) return
        if (heroIndex != 0) return
        if (feature.name.startsWith("The ", true) || feature.name.startsWith("A ", true)) return
        thread(name = "cineflow-hero-logo", isDaemon = true) {
            val json = StremioSession.metaJson(this, feature.type, feature.id) ?: return@thread
            val logo = json.optString("logo").takeIf { it.isNotBlank() } ?: return@thread
            runOnUiThread {
                if (isFinishing || isDestroyed || currentHero?.id != feature.id) return@runOnUiThread
                heroLogo.visibility = View.VISIBLE
                heroTitle.visibility = View.GONE
                NativeImageLoader.load(heroLogo, logo, 2, "original")
            }
        }
    }

    private fun render(
        movies: List<Meta>,
        films: List<Meta>,
        series: List<Meta>,
        library: List<StremioSession.LibraryItem> = emptyList(),
        extraCatalogs: List<ExtraRow> = emptyList(),
    ) {
        if (isFinishing || isDestroyed) return
        heroItems = (movies + films + series).distinctBy { it.id }.take(8)
        heroIndex = 0
        heroItems.firstOrNull()?.let { showHero(it, animate = false) }
        startHeroRotation()
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
            continueTargets[item.id] = DirectPlayback.Target(item.id, item.type, item.name, item.poster, item.videoId, item.position, item.season, item.episode, item.episodeTitle, item.videoId?.substringBefore(":")?.takeIf { it.startsWith("tt") })
        }
        for (item in library.filter { it.timeOffset > 0 }) {
            val parts = item.videoId?.substringAfterLast(':')?.let { _ -> item.videoId.split(':') } ?: emptyList()
            val season = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 0
            val episode = parts.getOrNull(parts.size - 1)?.toIntOrNull() ?: 0
            continueTargets.putIfAbsent(item.id, DirectPlayback.Target(item.id, item.type, item.name, item.poster, item.videoId, item.timeOffset, season, episode, null, item.videoId?.substringBefore(":")?.takeIf { it.startsWith("tt") }))
        }
        if (continueItems.isNotEmpty()) rows.addView(section("", "CONTINUAR VIENDO", continueItems, "movie", autoResume = true))
        if (movies.isNotEmpty()) rows.addView(section("", "TENDENCIAS", movies, "movie"))
        if (films.isNotEmpty()) rows.addView(section("", "PELÍCULAS", films, "movie"))
        if (series.isNotEmpty()) rows.addView(section("", "SERIES PARA ENGANCHARSE", series, "series"))
        for (row in extraCatalogs) {
            if (row.items.isNotEmpty()) rows.addView(section("", row.title.uppercase(Locale.ROOT), row.items, row.type))
        }
        heroPlay.post { heroPlay.requestFocus() }
        for (row in railRows) row.setNextFocusRightId(heroPlay.id)
    }

    /**
     * Horizontal cards are handled manually: when the previous card is off-screen the platform
     * FocusFinder otherwise picks the side rail (Inicio/Explorar/...) on the left. Keep Left
     * inside the row, scrolling to the previous card or staying on the first one.
     */
    private fun moveLeftInsideRow(start: View?): Boolean {
        var view = start ?: return false
        var card: View = view
        while (card.parent is View) {
            val parent = card.parent as View
            if (parent is RecyclerView) {
                val position = parent.getChildAdapterPosition(card)
                if (position <= 0) {
                    parent.scrollToPosition(0)
                    openRail()
                    return true
                }
                // Always scroll the previous card into view first. Focusing a card that is
                // clipped/off-screen fails and Android then falls back to the side rail.
                // Anchor focus on the row itself during the scroll, then restore its card.
                parent.isFocusable = true
                parent.requestFocus()
                parent.scrollToPosition(position - 1)
                focusHolderAfterScroll(parent, position - 1, 0)
                return true
            }
            card = parent
        }
        return false
    }

    /** Waits for the RecyclerView to lay out the target position, then focuses its card. */
    private fun focusHolderAfterScroll(parent: RecyclerView, position: Int, attempts: Int) {
        val holder = parent.findViewHolderForAdapterPosition(position)
        if (holder != null) {
            holder.itemView.requestFocus()
            // The row only needed to be focusable while it anchored the focus.
            parent.isFocusable = false
            return
        }
        if (attempts >= 12) {
            parent.isFocusable = false
            return
        }
        parent.postDelayed({ focusHolderAfterScroll(parent, position, attempts + 1) }, 40L)
    }

    /** Long OK on a remote needs its own timer; View's long-click is unreliable for D-pad. */
    private fun installLongPress(view: View, action: () -> Unit) {
        var fired = false
        val runnable = Runnable {
            fired = true
            action()
        }
        view.setOnKeyListener { v, keyCode, event ->
            if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_CENTER && keyCode != android.view.KeyEvent.KEYCODE_ENTER) {
                false
            } else {
                when (event.action) {
                    android.view.KeyEvent.ACTION_DOWN -> {
                        if (event.isLongPress && !fired) {
                            v.removeCallbacks(runnable)
                            runnable.run()
                            true
                        } else {
                            if (event.repeatCount == 0) {
                                fired = false
                                v.removeCallbacks(runnable)
                                v.postDelayed(runnable, 650L)
                            }
                            false
                        }
                    }
                    android.view.KeyEvent.ACTION_UP -> {
                        v.removeCallbacks(runnable)
                        if (fired) {
                            fired = false
                            true
                        } else false
                    }
                    else -> false
                }
            }
        }
        view.setOnLongClickListener { action(); true }
    }

    private fun askRemoveFromContinue(meta: Meta) {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(20), dp(26), dp(16))
            background = rounded(Color.rgb(17,19,25), 18, Color.rgb(64,68,79), 1)
            clipChildren = false
            clipToPadding = false
        }
        panel.addView(TextView(this).apply {
            text = "CONTINUAR VIENDO"
            setTextColor(CORAL)
            textSize = 8f
            letterSpacing = .18f
            typeface = Typeface.DEFAULT_BOLD
        })
        panel.addView(TextView(this).apply {
            text = meta.name
            setTextColor(CREAM)
            textSize = 19f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(14))
        })
        fun option(icon: String, title: String, subtitle: String, action: () -> Unit): TextView {
            val row = TextView(this).apply {
                text = "$icon   $title\n      $subtitle"
                setTextColor(CREAM)
                textSize = 12f
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                background = rounded(Color.rgb(23,25,32), 11, Color.rgb(53,57,68), 1)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
                setOnClickListener { dialog.dismiss(); action() }
                onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                    v.background = rounded(if (has) Color.rgb(42,46,55) else Color.rgb(23,25,32), 11,
                        if (has) CORAL else Color.rgb(53,57,68), if (has) 2 else 1)
                }
            }
            panel.addView(row)
            return row
        }
        val remove = option("✕", "Quitar de Continuar viendo", "Se borrará el progreso de este perfil") {
            NativeProgressStore.remove(this, meta.id)
            Toast.makeText(this, "Quitado de Continuar viendo", Toast.LENGTH_SHORT).show()
            thread(name = "cineflow-clear-progress", isDaemon = true) {
                StremioSession.clearContinueProgress(this, meta.id)
                runOnUiThread { loadCatalogs() }
            }
        }
        val cancel = TextView(this).apply {
            text = "CANCELAR"
            setTextColor(Color.rgb(160,164,174))
            textSize = 10f
            letterSpacing = .08f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(8))
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setOnClickListener { dialog.dismiss() }
            onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                (v as TextView).setTextColor(if (has) CORAL else Color.rgb(160,164,174))
            }
        }
        panel.addView(cancel)
        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.72f)
            setLayout(dp(600), WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnShowListener { remove.requestFocus() }
        dialog.show()
    }

    private fun section(number: String, title: String, list: List<Meta>, type: String, autoResume: Boolean = false): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, dp(10))
        }
        head.addView(TextView(this).apply {
            val locale = Locale.forLanguageTag("es-ES")
            text = title.lowercase(locale).replaceFirstChar { it.titlecase(locale) }
            setTextColor(Color.WHITE)
            textSize = 19f
            letterSpacing = 0f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val verTodoId = View.generateViewId()
        val seeAll = when {
            title.contains("TENDENCIA", true) ->
                Intent(this, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_FILTER, "Tendencias")
            type == "movie" && title.contains("PELÍCULA", true) ->
                Intent(this, CatalogActivity::class.java).putExtra(CatalogActivity.EXTRA_TYPE, "movie")
            type == "series" ->
                Intent(this, CatalogActivity::class.java).putExtra(CatalogActivity.EXTRA_TYPE, "series")
            else -> Intent(this, SearchActivity::class.java)
        }
        box.addView(head)
        val rv = RecyclerView(this).apply {
            id = View.generateViewId()
            layoutManager = LinearLayoutManager(this@NativeHomeActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = CardAdapter(list, type, autoResume, verTodoId, seeAll)
            if (firstRow == null) firstRow = this
            isFocusable = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // The focused card scales slightly and has a red outline: leave room for it
            // inside the row instead of letting the RecyclerView clip its left edge.
            clipToPadding = false
            clipChildren = false
            setPadding(dp(8), dp(6), dp(28), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170))
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
        head.addView(TextView(this).apply {
            text = "En directo"; setTextColor(Color.WHITE); textSize = 19f; letterSpacing = 0f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        box.addView(head)
        val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cards.addView(liveCard("CANALES", "759 señales · AceStream", CORAL) { startActivity(Intent(this, ChannelsActivity::class.java)) }, LinearLayout.LayoutParams(0, dp(92), 1f).apply { rightMargin = dp(10) })
        cards.addView(liveCard("GUÍA", "EPG · ahora y después", MINT) { startActivity(Intent(this, GuideActivity::class.java)) }, LinearLayout.LayoutParams(0, dp(92), 1f).apply { leftMargin = dp(10) })
        box.addView(cards)
        return box
    }

    private fun buildDiscovery(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(15), 0, dp(8)) }
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_cf_compass)
            setColorFilter(CORAL)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(10) }
        })
        head.addView(TextView(this).apply {
            text = "Descubrir"; setTextColor(Color.WHITE); textSize = 19f; letterSpacing = 0f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        box.addView(head)
        val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cards.addView(liveCard("COLECCIONES", "Acción · Comedia · Drama · Terror", CORAL) {
            startActivity(Intent(this, NativeCollectionsActivity::class.java))
        }, LinearLayout.LayoutParams(0, dp(92), 1f).apply { rightMargin = dp(10) })
        cards.addView(liveCard("CALENDARIO", "Próximos episodios de tus series", MINT) {
            startActivity(Intent(this, NativeCalendarActivity::class.java))
        }, LinearLayout.LayoutParams(0, dp(92), 1f).apply { leftMargin = dp(10) })
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
        setTextColor(if (filled) Color.rgb(10,10,12) else Color.WHITE)
        textSize = 13f
        letterSpacing = 0f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        background = if (filled) rounded(Color.rgb(245,245,247), 23, Color.TRANSPARENT, 0)
        else rounded(0x99424244.toInt(), 23, 0x44FFFFFF, 1)
        setOnFocusChangeListener { v, has ->
            v.background = if (filled) rounded(Color.WHITE, 23, Color.TRANSPARENT, 0)
            else rounded(if (has) Color.rgb(245,245,247) else 0x99424244.toInt(), 23,
                if (has) Color.TRANSPARENT else 0x44FFFFFF, if (has) 0 else 1)
            (v as TextView).setTextColor(if (has) Color.rgb(10,10,12) else if (filled) Color.rgb(10,10,12) else Color.WHITE)
            v.animate().scaleX(if (has) 1.03f else 1f).scaleY(if (has) 1.03f else 1f).setDuration(110L).start()
        }
    }

    private inner class CardAdapter(
        private val items: List<Meta>,
        private val fallbackType: String,
        private val autoResume: Boolean = false,
        private val upFocusId: Int = View.NO_ID,
        private val seeAllIntent: Intent? = null,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        inner class Holder(val card: LinearLayout, val frame: FrameLayout, val image: ImageView, val name: TextView, val sub: TextView, val description: TextView, val progress: View) : RecyclerView.ViewHolder(card)
        inner class ArrowHolder(val card: FrameLayout) : RecyclerView.ViewHolder(card)

        override fun getItemCount(): Int = items.size + 1
        override fun getItemViewType(position: Int): Int = if (position == items.size) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 1) {
                val wrap = FrameLayout(this@NativeHomeActivity).apply {
                    id = upFocusId
                    nextFocusRightId = id
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isClickable = true
                    clipChildren = false
                    clipToPadding = false
                    layoutParams = RecyclerView.LayoutParams(dp(96), dp(155)).apply { rightMargin = dp(8) }
                }
                val circle = TextView(this@NativeHomeActivity).apply {
                    text = "›"
                    setTextColor(Color.WHITE)
                    textSize = 30f
                    gravity = Gravity.CENTER
                    background = rounded(0x33FFFFFF, 28, 0x44FFFFFF, 1)
                    layoutParams = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER)
                }
                wrap.addView(circle)
                wrap.setOnClickListener {
                    startActivity(seeAllIntent ?: Intent(this@NativeHomeActivity, SearchActivity::class.java))
                }
                wrap.onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                    v.background = null
                    circle.background = rounded(if (has) Color.WHITE else 0x33FFFFFF, 28,
                        if (has) Color.TRANSPARENT else 0x44FFFFFF, if (has) 0 else 1)
                    circle.setTextColor(if (has) Color.rgb(10,10,12) else Color.WHITE)
                    v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f)
                        .setDuration(180L).setInterpolator(android.view.animation.OvershootInterpolator(0.3f)).start()
                }
                return ArrowHolder(wrap)
            }
            val card = LinearLayout(this@NativeHomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                id = View.generateViewId()
                setNextFocusUpId(upFocusId)
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(2), dp(2), dp(2), dp(2))
                clipChildren = false
                clipToPadding = false
                layoutParams = RecyclerView.LayoutParams(dp(180), dp(155)).apply { rightMargin = dp(8) }
            }
            val frame = FrameLayout(this@NativeHomeActivity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110))
                background = rounded(Color.TRANSPARENT, 13, Color.TRANSPARENT, 0)
                clipToOutline = true
                outlineProvider = roundedOutline(dp(13))
            }
            val image = ImageView(this@NativeHomeActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
            frame.addView(image)
            val description = TextView(this@NativeHomeActivity).apply {
                setTextColor(Color.rgb(245,245,247))
                textSize = 7.8f
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(8), dp(6), dp(8), dp(7))
                setBackgroundColor(0xD907080B.toInt())
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            }
            frame.addView(description)
            val progress = View(this@NativeHomeActivity).apply {
                setBackgroundColor(Color.WHITE)
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(0, dp(3), Gravity.BOTTOM)
            }
            frame.addView(progress)
            val name = TextView(this@NativeHomeActivity).apply {
                setTextColor(CREAM)
                textSize = 13f
                maxLines = 1
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(3), dp(8), dp(3), 0)
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
            return Holder(card, frame, image, name, sub, description, progress)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is ArrowHolder || position >= items.size) return
            val h = holder as Holder
            val meta = items[position]
            h.card.tag = if (autoResume) meta else null
            // The first card must keep Left on itself: the framework's default focus search
            // would otherwise land on the side rail (Inicio/Explorar/...).
            h.card.nextFocusLeftId = if (position == 0) h.card.id else View.NO_ID
            h.card.nextFocusRightId = if (position == items.size - 1) upFocusId else View.NO_ID
            h.name.text = meta.name
            h.sub.text = listOfNotNull(meta.releaseInfo, meta.rating?.let { "★ $it" }).joinToString("   ")
            h.description.text = meta.description.orEmpty()
            h.description.visibility = View.GONE
            h.progress.visibility = View.GONE
            if (autoResume) {
                val entry = NativeProgressStore.get(this@NativeHomeActivity, meta.id)
                if (entry != null && entry.duration > 0L) {
                    val fraction = (entry.position.toFloat() / entry.duration.toFloat()).coerceIn(0f, 1f)
                    h.progress.visibility = View.VISIBLE
                    h.frame.post {
                        val lp = h.progress.layoutParams as FrameLayout.LayoutParams
                        lp.width = (h.frame.width * fraction).toInt()
                        h.progress.layoutParams = lp
                    }
                }
            }
            NativeImageLoader.load(h.image, MediaArt.poster(meta.id, meta.background ?: meta.poster), 1)
            h.card.background = rounded(Color.TRANSPARENT, 13, Color.TRANSPARENT, 0)
            h.card.onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                h.card.background = rounded(Color.TRANSPARENT, 10,
                    if (has) Color.WHITE else Color.TRANSPARENT, if (has) 2 else 0)
                h.name.setTextColor(if (has) Color.WHITE else CREAM)
                h.description.visibility = if (has && h.description.text.isNotBlank()) View.VISIBLE else View.GONE
                v.animate().scaleX(if (has) 1.045f else 1f).scaleY(if (has) 1.045f else 1f)
                    .setDuration(190L)
                    .setInterpolator(android.view.animation.OvershootInterpolator(0.35f))
                    .start()
                if (has) v.bringToFront()
            }
            h.card.setOnClickListener {
                if (autoResume) playContinue(meta) else openDetail(meta, fallbackType)
            }
            if (autoResume) {
                installLongPress(h.card) { askRemoveFromContinue(meta) }
            } else {
                h.card.setOnLongClickListener(null)
                h.card.setOnKeyListener(null)
            }
        }

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
        }, onError = { error ->
            loadingOverlay?.let { if (it.parent != null) homeRoot.removeView(it) }
            loadingOverlay = null
            loadingStatus = null
            android.widget.Toast.makeText(this, error, android.widget.Toast.LENGTH_LONG).show()
        })
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
            val addonCatalogs = if (StremioSession.isLoggedIn(this)) StremioSession.catalogs(this) else emptyList()
            val tmdbCatalogs = addonCatalogs.filter {
                it.addon.contains("Movie Database", ignoreCase = true) ||
                    it.addon.contains("TMDB", ignoreCase = true)
            }
            // Prefer the Spanish TMDB catalogs over Cinemeta's English rows.
            val tmdbMovies = tmdbCatalogs.filter { it.type == "movie" }.distinctBy { it.title.lowercase(Locale.ROOT) }
            val tmdbSeries = tmdbCatalogs.filter { it.type == "series" }.distinctBy { it.title.lowercase(Locale.ROOT) }
            var movies = tmdbMovies.getOrNull(0)?.let { fetchCatalog(it.url, it.type) }.orEmpty()
            var films = tmdbMovies.getOrNull(1)?.let { fetchCatalog(it.url, it.type) }.orEmpty()
            var series = tmdbSeries.getOrNull(0)?.let { fetchCatalog(it.url, it.type) }.orEmpty()
            if (movies.isEmpty() && films.isEmpty() && series.isEmpty()) {
                movies = fetch("movie", "top")
                films = fetch("movie", "imdbRating")
                series = fetch("series", "top")
            }
            val library = if (Profiles.isMain(this) && StremioSession.isLoggedIn(this)) StremioSession.library(this) else emptyList()
            runOnUiThread {
                moviesCache = movies
                filmsCache = films
                seriesCache = series
                libraryCache = library
                extraCatalogsCache = emptyList()
                render(movies, films, series, library, emptyList())
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
                    logo = o.optString("logo").takeIf { it.isNotBlank() },
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
                    logo = o.optString("logo").takeIf { it.isNotBlank() },
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
        val logo: String? = null,
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

    private fun navBackground(focused: Boolean): GradientDrawable =
        rounded(if (focused) Color.rgb(242,242,244) else Color.TRANSPARENT, 19, Color.TRANSPARENT, 0)

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PROFILE_SELECTED = "cineflow.profile.selected"
        private val INK = Color.rgb(5, 5, 7)
        private val RAIL = Color.rgb(10, 10, 12)
        private val CARD = Color.rgb(18, 18, 20)
        private val CREAM = Color.rgb(245, 245, 247)
        private val CORAL = Color.rgb(245, 245, 247)
        private val MINT = Color.rgb(145, 227, 207)
    }
}
