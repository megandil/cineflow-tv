package com.dani.cineflow.tv

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Native film/series screen. It reads Cinemeta, asks Torrentio for streams and hands the chosen
 * stream to the existing PlayerActivity. No WebView and no Stremio route are involved.
 */
class NativeDetailActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var backdrop: ImageView
    private lateinit var title: TextView
    private lateinit var logoView: ImageView
    private lateinit var badges: LinearLayout
    private lateinit var metaLine: TextView
    private lateinit var description: TextView
    private lateinit var actions: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private var meta: Meta? = null
    private var streams: List<Stream> = emptyList()
    private var streamsLoading = false
    private var selectedVideo: Video? = null
    private var resumeMs = 0L
    private var pendingAutoPlay = false
    private var playButton: TextView? = null
    private var providerButton: TextView? = null
    private var libraryButton: TextView? = null
    private var inLibrary = false
    private var firstEpisodeView: View? = null
    private var autoCover: View? = null
    private var overlay: View? = null
    private var overlayGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        val type = intent.getStringExtra(EXTRA_TYPE) ?: "movie"
        val id = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        pendingAutoPlay = intent.getBooleanExtra(EXTRA_AUTOPLAY, false)
        setContentView(buildUi(type, id))
        load(type, id)
    }

    override fun onResume() {
        super.onResume()
        playButton?.post {
            if (!isFinishing && overlay == null) playButton?.requestFocus()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK && overlay != null) {
            closeOverlay()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && currentFocus === playButton) {
            val target = firstEpisodeView ?: providerButton
            if (target != null) { target.requestFocus(); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun buildUi(type: String, id: String): View {
        root = FrameLayout(this).apply { setBackgroundColor(INK) }
        // One full-screen backdrop: the hero image never ends halfway down the page.
        backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        root.addView(backdrop)
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x6607080B, 0xB307080B.toInt(), 0xF207080B.toInt(), 0xFF07080B.toInt()))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        })
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(34))
            clipChildren = false
            clipToPadding = false
        }
        val hero = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(350))
            clipChildren = false
        }
        hero.addView(TextView(this).apply {
            text = "‹  Inicio"
            setTextColor(Color.rgb(240,240,244))
            textSize = 13f
            letterSpacing = 0f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(14), dp(7), dp(18), dp(7))
            background = rounded(0xCC141416.toInt(), 20, 0x24FFFFFF, 1)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(30)
                topMargin = dp(18)
            }
            setOnClickListener { finish() }
        })
        val heroText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(80), dp(20), dp(80), dp(22))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        heroText.addView(TextView(this).apply {
            text = if (type == "series") "Serie" else "Película"
            setTextColor(CORAL)
            textSize = 11f
            letterSpacing = .22f
            typeface = Typeface.DEFAULT_BOLD
        })
        logoView = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
            visibility = View.GONE
            maxWidth = dp(700)
            maxHeight = dp(100)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(100)).apply { bottomMargin = dp(4) }
        }
        heroText.addView(logoView)
        title = TextView(this).apply {
            text = "Cargando…"
            setTextColor(CREAM)
            textSize = 36f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            maxLines = 2
            includeFontPadding = false
            setPadding(0, dp(3), 0, dp(5))
        }
        heroText.addView(title)
        badges = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(6))
        }
        heroText.addView(badges)
        metaLine = TextView(this).apply {
            setTextColor(Color.rgb(190,195,202)); textSize = 10f; letterSpacing = .05f
            visibility = View.GONE
        }
        heroText.addView(metaLine)
        actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // Buttons scale on focus; keep their rounded left edge inside the container.
            setPadding(dp(10), dp(14), dp(10), 0)
            clipChildren = false
            clipToPadding = false
        }
        heroText.clipChildren = false
        heroText.clipToPadding = false
        heroText.addView(actions)
        hero.addView(heroText)
        content.addView(hero)
        description = TextView(this).apply {
            setTextColor(Color.rgb(196,200,208))
            textSize = 12f
            maxLines = 5
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(54), dp(18), dp(96), dp(8))
        }
        content.addView(description)
        status = TextView(this).apply {
            setTextColor(Color.rgb(145,227,207))
            textSize = 10f
            letterSpacing = .04f
            setPadding(dp(54), 0, dp(54), dp(10))
        }
        content.addView(status)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(54), 0, dp(54), 0)
            clipChildren = false
            clipToPadding = false
        }
        content.addView(body)
        scroll.addView(content)
        root.addView(scroll)
        return root
    }

    private fun load(type: String, id: String) {
        thread(name = "cineflow-detail", isDaemon = true) {
            val loaded = fetchMeta(type, id)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (loaded == null) {
                    status.text = "No se pudo cargar la ficha. Comprueba la conexión."
                    return@runOnUiThread
                }
                meta = loaded
                val progress = NativeProgressStore.get(this, loaded.id)
                resumeMs = progress?.position ?: 0L
                if (progress?.videoId != null) selectedVideo = loaded.videos?.firstOrNull { it.id == progress.videoId }
                render(loaded)
            }
        }
    }

    private fun render(meta: Meta) {
        title.text = meta.name
        if (!meta.logo.isNullOrBlank() && !meta.name.startsWith("The ", true) && !meta.name.startsWith("A ", true)) {
            logoView.visibility = View.VISIBLE
            title.visibility = View.GONE
            NativeImageLoader.load(logoView, meta.logo, 2, "original")
        } else {
            logoView.visibility = View.GONE
            title.visibility = View.VISIBLE
        }
        metaLine.text = ""
        badges.removeAllViews()
        fun addBadge(value: String?) {
            if (value.isNullOrBlank()) return
            badges.addView(TextView(this).apply {
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
        addBadge(meta.releaseInfo)
        addBadge(meta.rating?.let { "★ $it" })
        addBadge(meta.runtime)
        meta.genres?.take(3)?.forEach { addBadge(it) }
        description.text = meta.description.orEmpty()
        NativeImageLoader.load(backdrop, meta.background ?: meta.poster, 2, "original")
        actions.removeAllViews()
        val play = pill("▶   Reproducir", true)
        play.id = View.generateViewId()
        playButton = play
        play.setOnClickListener { playFromStart() }
        val provider = circleButton("⋯") { chooseProvider() }
        providerButton = provider
        actions.addView(play, LinearLayout.LayoutParams(dp(175), dp(42)).apply { rightMargin = dp(9) })
        actions.addView(provider, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(9) })
        val library = circleButton("+") { }
        libraryButton = library
        library.setOnClickListener {
            val local = !Profiles.isMain(this)
            if (!local && !StremioSession.isLoggedIn(this)) {
                startActivity(Intent(this, NativeAccountActivity::class.java))
                return@setOnClickListener
            }
            val target = !inLibrary
            if (local) {
                if (target) {
                    ProfileLibrary.add(this, ProfileLibrary.Item(meta.id, meta.type, meta.name, meta.poster, meta.background, meta.releaseInfo))
                } else {
                    ProfileLibrary.remove(this, meta.id)
                }
                inLibrary = target
                library.text = if (target) "✓" else "+"
                return@setOnClickListener
            }
            library.text = "…"
            thread(name = "cineflow-library-toggle", isDaemon = true) {
                val (ok, message) = if (target)
                    StremioSession.addToLibrary(this, StremioSession.MetaInput(meta.id, meta.type, meta.name, meta.poster, meta.background, meta.releaseInfo))
                else StremioSession.removeFromLibrary(this, meta.id)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (ok) {
                        inLibrary = target
                        library.text = if (target) "✓" else "+"
                    } else {
                        library.text = if (inLibrary) "✓" else "+"
                        status.text = message
                    }
                }
            }
        }
        actions.addView(library, LinearLayout.LayoutParams(dp(44), dp(44)))
        thread(name = "cineflow-library-check", isDaemon = true) {
            val has = if (Profiles.isMain(this)) StremioSession.isInLibrary(this, meta.id) else ProfileLibrary.contains(this, meta.id)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    inLibrary = has
                    if (has) library.text = "✓"
                }
            }
        }
        val progress = NativeProgressStore.get(this, meta.id)
        if (progress != null && progress.season > 0 && progress.episode > 0) {
            playButton?.text = "▶  CONTINUAR T${progress.season} · E${progress.episode}"
        }
        if (!meta.videos.isNullOrEmpty()) renderEpisodes(meta)
        if (pendingAutoPlay) {
            pendingAutoPlay = false
            playFromStart()
        } else if (meta.videos.isNullOrEmpty()) {
            status.text = "Preparando proveedores…"
            loadStreams(meta.id, autoplay = false)
        }
        play.post { if (!isFinishing) play.requestFocus() }
    }

    private fun renderEpisodes(meta: Meta) {
        val seasons = meta.videos!!.mapNotNull { it.season }.distinct().sorted()
        if (seasons.isEmpty()) return
        var currentSeason = seasons.first()
        val seasonBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(7))
        }
        val episodeGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val seasonTitle = TextView(this).apply {
            setTextColor(CREAM)
            textSize = 15f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, 0, 0, dp(2))
        }
        val seasonCount = TextView(this).apply {
            setTextColor(Color.rgb(150,155,165))
            textSize = 9f
            setPadding(0, 0, 0, dp(7))
        }
        val chips = mutableListOf<Pair<Int, TextView>>()

        fun drawEpisodes(season: Int) {
            episodeGrid.removeAllViews()
            firstEpisodeView = null
            val episodes = meta.videos!!.filter { it.season == season }.sortedBy { it.episode }
            seasonTitle.text = "Temporada $season"
            seasonCount.text = "${episodes.size} episodios · elige uno para ver los proveedores"
            var row: LinearLayout? = null
            for ((index, video) in episodes.withIndex()) {
                if (index % 2 == 0) {
                    row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(2)) }
                    episodeGrid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                val card = episodeCard(meta, video)
                val lp = LinearLayout.LayoutParams(0, dp(154), 1f).apply { setMargins(dp(4), dp(2), dp(4), dp(2)) }
                row?.addView(card, lp)
                if (firstEpisodeView == null) firstEpisodeView = card
            }
            if (episodes.size % 2 == 1) {
                row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
        }

        for (season in seasons) {
            val chip = TextView(this).apply {
                text = "T$season"
                setTextColor(if (season == currentSeason) Color.rgb(12,13,16) else CREAM)
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                isFocusable = true
                isFocusableInTouchMode = true
                background = rounded(if (season == currentSeason) CORAL else CARD, 999, if (season == currentSeason) CORAL else BORDER, 1)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)).apply { rightMargin = dp(6) }
                setOnClickListener {
                    currentSeason = season
                    chips.forEach { (s, c) ->
                        c.background = rounded(if (s == season) CORAL else CARD, 999, if (s == season) CORAL else BORDER, 1)
                        c.setTextColor(if (s == season) Color.rgb(12,13,16) else CREAM)
                    }
                    drawEpisodes(season)
                    episodeGrid.post { firstEpisodeView?.requestFocus() }
                }
                onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                    v.background = rounded(if (season == currentSeason) CORAL else if (has) Color.rgb(40,43,52) else CARD, 999, if (has || season == currentSeason) CORAL else BORDER, if (has) 2 else 1)
                }
            }
            chips += season to chip
            seasonBox.addView(chip)
        }

        body.addView(TextView(this).apply {
            text = "Episodios"
            setTextColor(CORAL)
            textSize = 11f
            letterSpacing = .18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(15), 0, dp(1))
        })
        body.addView(seasonTitle)
        body.addView(seasonCount)
        val seasonScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38))
        }
        seasonScroll.addView(seasonBox)
        body.addView(seasonScroll)
        body.addView(episodeGrid)
        drawEpisodes(currentSeason)
        status.text = "Elige un episodio para ver los proveedores."
        if (selectedVideo == null) {
            selectedVideo = meta.videos!!.firstOrNull { it.season == currentSeason && it.episode == 1 } ?: meta.videos!!.first()
        }
    }

    private fun episodeCard(meta: Meta, video: Video): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = rounded(CARD, 11, BORDER, 1)
            clipToOutline = true
            outlineProvider = roundedOutline(dp(11))
            setOnClickListener {
                selectedVideo = video
                loadStreams(video.id, autoplay = true)
            }
            setOnLongClickListener {
                showEpisodeOptions(meta, video)
                true
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN && event.isLongPress &&
                    (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER)) {
                    showEpisodeOptions(meta, video)
                    true
                } else false
            }
            onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                v.background = rounded(if (has) Color.rgb(42,46,55) else CARD, 11, if (has) CORAL else BORDER, if (has) 2 else 1)
                v.animate().scaleX(if (has) 1.015f else 1f).scaleY(if (has) 1.015f else 1f).setDuration(120).start()
            }
        }
        val art = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84))
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        NativeImageLoader.load(image, video.thumbnail ?: meta.poster, 1)
        art.addView(image)
        art.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x22000000, 0xBB07080B.toInt()))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        })
        val badge = TextView(this).apply {
            text = "T${video.season}  ·  E${video.episode}"
            setTextColor(Color.WHITE)
            textSize = 11f
            letterSpacing = .08f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(0xAA07080B.toInt())
            setPadding(dp(7), dp(3), dp(7), dp(3))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(dp(7), dp(7), 0, 0) }
        }
        art.addView(badge)
        val progress = NativeProgressStore.get(this, meta.id)
        val watched = NativeProgressStore.isWatched(this, video.id)
        art.addView(TextView(this).apply {
            text = "VISTO"
            setTextColor(Color.rgb(12,13,16))
            textSize = 7f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(MINT)
            setPadding(dp(6), dp(3), dp(6), dp(3))
            tag = "watched:${video.id}"
            visibility = if (watched) View.VISIBLE else View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(7), dp(7)) }
        })
        if (progress?.videoId == video.id && !watched) {
            art.addView(TextView(this).apply {
                text = "CONTINUAR"
                setTextColor(Color.rgb(12,13,16))
                textSize = 7f
                typeface = Typeface.DEFAULT_BOLD
                setBackgroundColor(MINT)
                setPadding(dp(6), dp(3), dp(6), dp(3))
                tag = "continue:${video.id}"
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(7), 0, 0, dp(7)) }
            })
        }
        card.addView(art)
        card.addView(TextView(this).apply {
            text = video.title ?: video.name ?: "Episodio ${video.episode}"
            setTextColor(CREAM)
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(9), dp(7), dp(9), 0)
        })
        video.overview?.let { overview ->
            card.addView(TextView(this).apply {
                text = overview
                setTextColor(Color.rgb(148,153,163))
                textSize = 11f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(9), dp(2), dp(9), dp(6))
            })
        }
        return card
    }

    private fun showEpisodeOptions(meta: Meta, video: Video) {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(20), dp(28), dp(16))
            background = rounded(Color.rgb(17,19,25), 18, Color.rgb(64,68,79), 1)
            clipChildren = false
            clipToPadding = false
        }
        panel.addView(TextView(this).apply {
            text = "EPISODIO"
            setTextColor(CORAL)
            textSize = 11f
            letterSpacing = .18f
            typeface = Typeface.DEFAULT_BOLD
        })
        panel.addView(TextView(this).apply {
            text = "T${video.season} E${video.episode} · ${video.title ?: video.name ?: "Episodio ${video.episode}"}"
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
        val restart = option("▶", "Reproducir desde el inicio", "Ignora el progreso guardado") {
            resumeMs = 0L
            selectedVideo = video
            loadStreams(video.id, autoplay = true)
        }
        option("✓", "Marcar como visto", "Quita el episodio de Continuar viendo") {
            NativeProgressStore.markWatched(this, video.id)
            NativeProgressStore.remove(this, meta.id)
            body.findViewWithTag<View>("continue:${video.id}")?.visibility = View.GONE
            body.findViewWithTag<View>("watched:${video.id}")?.visibility = View.VISIBLE
            Toast.makeText(this, "Episodio marcado como visto", Toast.LENGTH_SHORT).show()
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
            setLayout(dp(620), WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnShowListener { restart.requestFocus() }
        dialog.show()
    }

    private fun playFromStart() {
        val current = selectedVideo
        if (current != null) { loadStreams(current.id); return }
        if (streams.isNotEmpty()) { play(streams.first()); return }
        if (streamsLoading) { status.text = "Buscando proveedores…"; return }
        meta?.let { loadStreams(it.id, autoplay = true) }
    }

    private fun chooseProvider() {
        if (streams.isNotEmpty()) { showStreamPicker(); return }
        if (streamsLoading) { status.text = "Buscando proveedores…"; return }
        val m = meta ?: return
        // The picker must ask for the selected episode, not the series root id.
        loadStreams(selectedVideo?.id ?: m.id, openPicker = true, autoplay = false)
    }

    private fun loadStreams(videoId: String, openPicker: Boolean = false, autoplay: Boolean = true) {
        val m = meta ?: return
        // TMDB catalogs use tmdb: ids, while Peerflix/Torrentio expect IMDb ids. The TMDB meta
        // carries imdb_id, so query providers with the IMDb equivalent (keeping :season:episode).
        val lookupId = if (m.imdbId != null && videoId.startsWith("tmdb:")) {
            m.imdbId + videoId.removePrefix(m.id)
        } else videoId
        streamsLoading = true
        streams = emptyList()
        status.text = "Buscando proveedores…"
        thread(name = "cineflow-streams", isDaemon = true) {
            val list = fetchStreams(m.type, lookupId)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                streamsLoading = false
                streams = capStreams(list)
                if (list.isEmpty()) {
                    autoCover?.let { (it.parent as? ViewGroup)?.removeView(it) }
                    autoCover = null
                    status.text = "No hay proveedores disponibles. AllDebrid no ha devuelto enlaces."
                    return@runOnUiThread
                }
                status.text = "${list.size} proveedores · ${list.first().name.ifBlank { "Torrentio" }}"
                if (openPicker) showStreamPicker() else if (autoplay) play(list.first())
            }
        }
    }

    private fun capStreams(list: List<Stream>): List<Stream> {
        if (list.isEmpty()) return list
        val max = PlaybackQuality.effectiveMax(this)
        fun quality(s: Stream): Int {
            val text = (s.name + " " + (s.title ?: "")).lowercase()
            return when {
                text.contains("2160") || text.contains("4k") -> 2160
                text.contains("1080") -> 1080
                text.contains("720") -> 720
                text.contains("480") -> 480
                else -> 0
            }
        }
        val within = list.filter { val q = quality(it); q == 0 || q <= max }
        return (within.ifEmpty { list }).sortedByDescending { quality(it) }
    }

    private fun play(stream: Stream) {
        val m = meta ?: return
        val video = selectedVideo
        val videoLabel = video?.let { "T${it.season} E${it.episode}" }.orEmpty()
        val infoHash = stream.infoHash
        val direct = stream.url
        if (infoHash.isNullOrBlank() && direct.isNullOrBlank()) { status.text = "Ese proveedor no tiene enlace."; return }
        val trackers = stream.sources.filter { it.startsWith("tracker:") }.map { it.removePrefix("tracker:") }
        val url: String
        val torrent: Boolean
        if (!infoHash.isNullOrBlank()) {
            url = TorrentEngine.open(applicationContext, infoHash, stream.fileIdx, trackers, stream.filename.orEmpty())
            torrent = true
            if (url.isBlank()) { status.text = "No se pudo abrir el torrent."; return }
            status.text = "Abriendo ${stream.name.ifBlank { "proveedor" }}…"
        } else {
            url = direct!!; torrent = false
        }
        val ordered = m.videos.orEmpty().sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        val currentIndex = ordered.indexOfFirst { it.id == (video?.id ?: m.id) }
        val next = if (currentIndex >= 0 && currentIndex + 1 < ordered.size) ordered[currentIndex + 1] else null
        val json = JSONObject()
            .put("url", url)
            .put("torrent", torrent)
            .put("title", m.name)
            .put("subtitle", if (videoLabel.isBlank()) stream.name else "$videoLabel · ${stream.name}")
            .put("audioLanguage", if (NativeSettings.value(this, "audioLanguage", "spa") == "original") "" else NativeSettings.value(this, "audioLanguage", "spa"))
            .put("clock", true)
            .put("metaId", m.id)
            .put("metaType", m.type)
            .put("name", m.name)
            .put("poster", m.poster.orEmpty())
            .put("startMs", resumeMs)
            .put("hash", "native:${m.id}")
            .put("videoId", video?.id ?: m.id)
            .put("imdbId", m.imdbId.orEmpty())
            .put("season", video?.season ?: 0)
            .put("episode", video?.episode ?: 0)
            .put("episodeTitle", video?.title ?: video?.name ?: "")
            .put("nextVideoId", next?.id.orEmpty())
            .put("nextSeason", next?.season ?: 0)
            .put("nextEpisode", next?.episode ?: 0)
            .put("nextTitle", next?.title ?: next?.name ?: "")
        autoCover?.let { (it.parent as? ViewGroup)?.removeView(it) }
        autoCover = null
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_STREAM, json.toString()))
    }

    private fun showStreamPicker() {
        closeOverlay()
        val generation = ++overlayGeneration
        val box = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(16))
            background = rounded(Color.rgb(18,18,20), 16, BORDER, 1)
            layoutParams = FrameLayout.LayoutParams(dp(650), dp(520), Gravity.CENTER)
        }
        card.addView(TextView(this).apply {
            text = "Elegir proveedor"
            setTextColor(CORAL)
            textSize = 9f
            letterSpacing = .2f
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            text = "Ordenados por calidad. Elige uno para reproducir."
            setTextColor(Color.rgb(160,164,174))
            textSize = 10f
            setPadding(0, dp(6), 0, dp(10))
        })
        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        streams.forEach { stream ->
            val row = TextView(this).apply {
                text = (stream.name.ifBlank { "Proveedor" }) + "   " + stream.title.lineSequence().firstOrNull().orEmpty().take(70)
                setTextColor(CREAM)
                textSize = 11f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(12), dp(9), dp(12), dp(9))
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                background = rowBackground()
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(5) }
                setOnClickListener { if (generation == overlayGeneration) { closeOverlay(); play(stream) } }
                onFocusChangeListener = View.OnFocusChangeListener { v, has -> v.background = rowBackground(has) }
            }
            list.addView(row)
        }
        scroll.addView(list)
        card.addView(scroll)
        val cancel = TextView(this).apply {
            text = "CANCELAR"
            setTextColor(Color.rgb(190,194,202))
            textSize = 9f
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(0, dp(9), 0, dp(2))
            setOnClickListener { closeOverlay() }
        }
        card.addView(cancel)
        box.addView(card)
        root.addView(box)
        overlay = box
        list.post { list.getChildAt(0)?.requestFocus() ?: cancel.requestFocus() }
    }

    private fun closeOverlay() {
        overlayGeneration++
        (overlay?.parent as? ViewGroup)?.removeView(overlay)
        overlay = null
    }

    private fun fetchMeta(type: String, id: String): Meta? {
        return try {
            val m = StremioSession.metaJson(this, type, id) ?: return null
            val videos = m.optJSONArray("videos")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    Video(
                        id = o.optString("id"),
                        season = if (o.has("season")) o.optInt("season") else null,
                        episode = if (o.has("episode")) o.optInt("episode") else null,
                        name = o.optString("name").takeIf { it.isNotBlank() },
                        title = o.optString("title").takeIf { it.isNotBlank() },
                        thumbnail = o.optString("thumbnail").takeIf { it.isNotBlank() },
                        overview = o.optString("overview").takeIf { it.isNotBlank() },
                    )
                }
            }
            val metaId = m.optString("id").ifBlank { id }
            Meta(
                id = metaId,
                type = m.optString("type").ifBlank { type },
                name = m.optString("name"),
                poster = m.optString("poster").takeIf { it.isNotBlank() },
                background = m.optString("background").takeIf { it.isNotBlank() },
                releaseInfo = m.optString("releaseInfo").takeIf { it.isNotBlank() },
                rating = m.optString("imdbRating").takeIf { it.isNotBlank() },
                runtime = m.optString("runtime").takeIf { it.isNotBlank() },
                description = m.optString("description").takeIf { it.isNotBlank() },
                genres = m.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.optString(it) } },
                videos = videos,
                imdbId = m.optString("imdb_id").takeIf { it.startsWith("tt") },
                logo = m.optString("logo").takeIf { it.isNotBlank() },
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchStreams(type: String, videoId: String): List<Stream> {
        val account = StremioSession.streams(this, type, videoId)
        if (account.isNotEmpty()) return account.map {
            Stream(it.name, it.title, it.infoHash, it.url, it.fileIdx, it.filename, it.sources)
        }
        // Never silently replace a debrid setup with public Torrentio torrents. The user chose
        // AllDebrid; if its addons have no links, the honest result is "no providers", not a
        // different kind of playback.
        if (StremioSession.isLoggedIn(this) && StremioSession.hasStreamAddons(this)) return emptyList()
        return publicTorrentio(type, videoId)
    }

    private fun publicTorrentio(type: String, videoId: String): List<Stream> {
        return try {
            val connection = (URL("https://torrentio.strem.fun/stream/$type/$videoId.json").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 25_000
                setRequestProperty("User-Agent", "CineFlow-TV")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val arr = JSONObject(body).optJSONArray("streams") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                if (StremioSession.isUnavailableStream(o)) return@mapNotNull null
                val hints = o.optJSONObject("behaviorHints")
                Stream(
                    name = o.optString("name").lineSequence().firstOrNull().orEmpty(),
                    title = o.optString("title"),
                    infoHash = o.optString("infoHash").takeIf { it.isNotBlank() },
                    url = o.optString("url").takeIf { it.isNotBlank() },
                    fileIdx = if (o.has("fileIdx")) o.optInt("fileIdx") else -1,
                    filename = hints?.optString("filename")?.takeIf { it.isNotBlank() },
                    sources = o.optJSONArray("sources")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun circleButton(symbol: String, action: () -> Unit): TextView = TextView(this).apply {
        text = symbol
        setTextColor(Color.WHITE)
        textSize = 20f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        background = rounded(0x99424244.toInt(), 23, 0x44FFFFFF, 1)
        setPadding(0, 0, 0, 0)
        setOnClickListener { action() }
        setOnFocusChangeListener { v, has ->
            v.background = rounded(if (has) Color.WHITE else 0x99424244.toInt(), 23,
                if (has) Color.TRANSPARENT else 0x44FFFFFF, if (has) 0 else 1)
            (v as TextView).setTextColor(if (has) Color.rgb(10,10,12) else Color.WHITE)
            v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f)
                .setDuration(180L)
                .setInterpolator(android.view.animation.OvershootInterpolator(0.3f))
                .start()
        }
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
        clipToOutline = false
        stateListAnimator = null
        includeFontPadding = false
        setPadding(dp(18), 0, dp(18), 0)
        setOnFocusChangeListener { v, has ->
            v.background = if (filled) rounded(Color.WHITE, 23, Color.TRANSPARENT, 0)
            else rounded(if (has) Color.rgb(245,245,247) else 0x99424244.toInt(), 23,
                if (has) Color.TRANSPARENT else 0x44FFFFFF, if (has) 0 else 1)
            (v as TextView).setTextColor(if (has) Color.rgb(10,10,12) else if (filled) Color.rgb(10,10,12) else Color.WHITE)
            v.animate().scaleX(if (has) 1.045f else 1f).scaleY(if (has) 1.045f else 1f)
                .setDuration(180L)
                .setInterpolator(android.view.animation.OvershootInterpolator(0.3f))
                .start()
        }
    }

    private fun rowBackground(focused: Boolean = false): GradientDrawable =
        rounded(if (focused) Color.rgb(41,44,53) else Color.rgb(21,23,29), 8, if (focused) CORAL else Color.TRANSPARENT, if (focused) 2 else 0)

    private fun roundedOutline(radius: Int): ViewOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius.toFloat())
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
        val releaseInfo: String?, val rating: String?, val runtime: String?, val description: String?,
        val genres: List<String>?, val videos: List<Video>?, val imdbId: String?,
        val logo: String? = null,
    )
    private data class Video(val id: String, val season: Int?, val episode: Int?, val name: String?, val title: String?, val thumbnail: String?, val overview: String?)
    private data class Stream(
        val name: String, val title: String, val infoHash: String?, val url: String?,
        val fileIdx: Int, val filename: String?, val sources: List<String>,
    )

    companion object {
        const val EXTRA_ID = "cineflow.id"
        const val EXTRA_TYPE = "cineflow.type"
        const val EXTRA_AUTOPLAY = "cineflow.autoplay"
        private val INK = Color.rgb(5, 5, 7)
        private val CARD = Color.rgb(18, 18, 20)
        private val BORDER = Color.rgb(48, 51, 60)
        private val CREAM = Color.rgb(245, 245, 247)
        private val CORAL = Color.rgb(245, 245, 247)
        private val MINT = Color.rgb(145, 227, 207)
    }
}
