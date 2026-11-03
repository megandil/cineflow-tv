package com.notdxbi.dxbi.tv

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.util.Xml
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * CineFlow Canales: the bundled M3U lists merged into one native TV grid, with category filters
 * and search. AceStream entries are handed to AceStream's own app; plain HTTP entries play in the
 * native player.
 */
class ChannelsActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ChannelAdapter
    private lateinit var count: TextView
    private lateinit var guideStatus: TextView
    private lateinit var search: EditText
    private lateinit var categoryRow: LinearLayout
    private val chips = LinkedHashMap<String, TextView>()
    private var all: List<Channel> = emptyList()
    private var groupCounts: Map<String, Int> = emptyMap()
    private var selectedGroup = "Todos"
    @Volatile private var guideSchedule: List<GuideProgram> = emptyList()
    private val guideHandler = Handler(Looper.getMainLooper())
    private val guideTick = object : Runnable {
        override fun run() {
            applyGuideNow()
            guideHandler.postDelayed(this, GUIDE_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        supportActionBar?.hide()
        setContentView(buildUi())
        loadChannels()
    }

    override fun onDestroy() {
        guideHandler.removeCallbacks(guideTick)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Holding OK on a channel must open it once, not launch AceStream repeatedly.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && isActionKey(event.keyCode)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun isActionKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(44), dp(24), dp(44), dp(8))
        }

        root.addView(TextView(this).apply {
            text = "CINEFLOW"
            setTextColor(ACCENT)
            textSize = 12f
            letterSpacing = .18f
            typeface = Typeface.DEFAULT_BOLD
        })

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        titleRow.addView(TextView(this).apply {
            text = "Canales"
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        })
        count = TextView(this).apply {
            text = "Cargando…"
            setTextColor(MUTED)
            textSize = 13f
            setPadding(dp(14), 0, 0, 0)
        }
        titleRow.addView(count)
        root.addView(titleRow)

        root.addView(TextView(this).apply {
            text = "AceStream encendido en la TV · categorías y búsqueda"
            setTextColor(MUTED)
            textSize = 12f
            setPadding(0, dp(2), 0, 0)
        })
        guideStatus = TextView(this).apply {
            text = "Guía EPG: cargando…"
            setTextColor(PROGRAM)
            textSize = 11f
            setPadding(0, dp(2), 0, dp(10))
        }
        root.addView(guideStatus)

        search = EditText(this).apply {
            hint = "Buscar canal o categoría…"
            setHintTextColor(MUTED)
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(16), 0, dp(16), 0)
            background = fieldBackground()
            isFocusable = true
            isFocusableInTouchMode = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = applyFilter(false)
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    hideKeyboard()
                    applyFilter(true)
                    true
                } else false
            }
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(10) })

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
        }
        categoryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scroll.addView(categoryRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll)

        recycler = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = GridLayoutManager(this@ChannelsActivity, 4)
            setPadding(0, dp(14), 0, dp(16))
            clipToPadding = false
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            setHasFixedSize(true)
        }
        root.addView(recycler)
        adapter = ChannelAdapter(emptyList()) { play(it) }
        recycler.adapter = adapter
        return root
    }

    private fun loadChannels() {
        thread(name = "cineflow-channels", isDaemon = true) {
            val channels = try {
                assets.open("channels.m3u").use { parseM3u(it) }
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                all = channels
                buildCategories()
                applyFilter(true)
                loadGuide(channels)
            }
        }
    }

    private fun parseM3u(input: InputStream): List<Channel> {
        val out = ArrayList<Channel>(1100)
        var currentGroup = "Otros"
        var pendingName: String? = null
        var pendingGroup: String? = null
        var pendingEpg: String? = null
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.startsWith("#EXTGRP:")) {
                    val g = attribute(line, "group-title")
                    if (!g.isNullOrBlank()) currentGroup = cleanGroup(g)
                } else if (line.startsWith("#EXTINF")) {
                    pendingGroup = attribute(line, "group-title")
                    pendingEpg = attribute(line, "tvg-id")?.takeIf { it.isNotBlank() }
                    pendingName = line.substringAfter(',', "").trim()
                } else if (line.isNotEmpty() && !line.startsWith("#")) {
                    val scheme = line.substringBefore(':', "").lowercase()
                    if (scheme == "http" || scheme == "https" || scheme == "acestream") {
                        val name = (pendingName ?: "Canal").ifBlank { "Canal" }
                        out.add(Channel(name, line, cleanGroup(pendingGroup ?: currentGroup), pendingEpg))
                    }
                    pendingName = null
                    pendingGroup = null
                    pendingEpg = null
                }
            }
        }
        return out
    }

    private fun attribute(line: String, key: String): String? =
        Regex("""\b${Regex.escape(key)}="([^"]*)"""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)

    private fun cleanGroup(value: String): String {
        val g = value.trim().ifBlank { "Otros" }.replace('"', '\'')
        return if (g.uppercase() in setOf("VACIO", "SIN CATEGORÍA", "SIN CATEGORIA", "1")) "Otros" else g
    }

    private fun buildCategories() {
        groupCounts = all.groupingBy { it.group }.eachCount()
        val priority = CATEGORY_PRIORITY.filter { groupCounts.containsKey(it) }
        val rest = groupCounts.entries.filter { it.key !in CATEGORY_PRIORITY }.sortedByDescending { it.value }.map { it.key }
        val groups = listOf("Todos") + priority + rest
        categoryRow.removeAllViews()
        chips.clear()
        for (g in groups) {
            val chip = TextView(this).apply {
                text = if (g == "Todos") "Todos" else g
                setTextColor(MUTED)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                isFocusable = true
                isFocusableInTouchMode = true
                background = chipBackground(false)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { rightMargin = dp(8) }
                setOnClickListener { selectGroup(g) }
            }
            chips[g] = chip
            categoryRow.addView(chip)
        }
        updateChipStyles()
    }

    private fun selectGroup(group: String) {
        selectedGroup = group
        updateChipStyles()
        applyFilter(true)
    }

    private fun updateChipStyles() {
        for ((group, chip) in chips) {
            val selected = group == selectedGroup
            chip.isSelected = selected
            chip.background = chipBackground(selected)
            chip.setTextColor(if (selected) Color.WHITE else MUTED)
        }
    }

    /**
     * The list headers point at guiatv.xml; its La Liga / Movistar / DAZN ids match the M3U
     * tvg-ids. The schedule is cached, and a one-minute ticker picks the programme that is on
     * now, so a match ending at 21:00 changes to the next one without reopening Canales.
     */
    private fun loadGuide(channels: List<Channel>) {
        val wanted = channels.mapNotNull { it.epgId }.toSet()
        if (wanted.isEmpty()) {
            guideStatus.text = "Guía EPG: sin datos"
            return
        }
        thread(name = "cineflow-epg", isDaemon = true) {
            val cached = readGuideCache()
            if (cached != null) {
                guideSchedule = cached
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        applyGuideNow()
                        startGuideTicker()
                    }
                }
                val age = System.currentTimeMillis() - File(cacheDir, "epg-schedule.json").lastModified()
                if (age < EPG_SCHEDULE_TTL_MS) return@thread
            }
            val schedule = try {
                val file = epgFile()
                parseEpg(file, wanted)
            } catch (_: Exception) {
                emptyList<GuideProgram>()
            }
            if (schedule.isNotEmpty()) {
                guideSchedule = schedule
                writeGuideCache(schedule)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                applyGuideNow()
                startGuideTicker()
            }
        }
    }

    private fun startGuideTicker() {
        guideHandler.removeCallbacks(guideTick)
        guideHandler.post(guideTick)
    }

    private fun applyGuideNow() {
        if (!::guideStatus.isInitialized) return
        val now = currentGuide(guideSchedule)
        adapter.setPrograms(now)
        val cleanGuide = now.mapValues { (_, title) -> teamsFromTitle(title).orEmpty() }.filterValues { it.isNotBlank() }
        ChannelStore.setGuide(cleanGuide)
        ChannelStore.refreshPrograms(this, cleanGuide)
        guideStatus.text = when {
            guideSchedule.isEmpty() -> "Guía EPG: cargando…"
            now.isEmpty() -> "Guía EPG: sin programación ahora"
            else -> "Guía EPG: ${now.size} canales con programación ahora"
        }
    }

    private fun currentGuide(schedule: List<GuideProgram>): Map<String, String> {
        val now = System.currentTimeMillis()
        val out = HashMap<String, String>()
        for (programme in schedule) {
            if (programme.start <= now && now < programme.stop) out[programme.channel] = programme.title
        }
        return out
    }

    private fun readGuideCache(): List<GuideProgram>? {
        val file = File(cacheDir, "epg-schedule.json")
        if (!file.exists()) return null
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                val o = array.optJSONObject(index) ?: return@mapNotNull null
                val channel = o.optString("channel")
                val title = o.optString("title")
                if (channel.isBlank() || title.isBlank()) null
                else GuideProgram(channel, o.optLong("start"), o.optLong("stop"), title)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeGuideCache(schedule: List<GuideProgram>) {
        try {
            val array = JSONArray()
            for (programme in schedule) {
                array.put(JSONObject()
                    .put("channel", programme.channel)
                    .put("start", programme.start)
                    .put("stop", programme.stop)
                    .put("title", programme.title))
            }
            File(cacheDir, "epg-schedule.json").writeText(array.toString())
        } catch (_: Exception) { /* guide is optional */ }
    }

    private fun epgFile(): File {
        val target = File(cacheDir, "guiatv.xml.gz")
        val fresh = target.exists() && System.currentTimeMillis() - target.lastModified() < EPG_MAX_AGE_MS
        if (fresh) return target
        val tmp = File(cacheDir, "guiatv.xml.gz.tmp")
        try {
            val connection = (URL(EPG_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "CineFlow-TV")
            }
            connection.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            connection.disconnect()
            if (tmp.length() > 10_000) {
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                return target
            }
        } catch (_: Exception) {
            tmp.delete()
        }
        if (target.exists()) return target
        throw IllegalStateException("EPG unavailable")
    }

    private fun parseEpg(file: File, wanted: Set<String>): List<GuideProgram> {
        val out = ArrayList<GuideProgram>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        GZIPInputStream(FileInputStream(file)).use { input ->
            parser.setInput(input, null)
            var channel: String? = null
            var start: Long? = null
            var stop: Long? = null
            var title: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "programme" -> {
                            channel = parser.getAttributeValue(null, "channel")
                            start = epgTime(parser.getAttributeValue(null, "start"))
                            stop = epgTime(parser.getAttributeValue(null, "stop"))
                            title = null
                        }
                        "title" -> if (channel != null) title = parser.nextText().trim()
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name == "programme") {
                    val id = channel
                    if (id != null && id in wanted && start != null && stop != null && stop > start) {
                        out.add(GuideProgram(id, start, stop, title?.takeIf { it.isNotBlank() } ?: "Sin título"))
                    }
                    channel = null
                }
                event = parser.next()
            }
        }
        return out
    }

    private fun epgTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { isLenient = true }.parse(value.trim())?.time
        } catch (_: Exception) {
            null
        }
    }

    /** The guide's title is an editorial headline; the card only wants the two teams. */
    private fun teamsFromTitle(raw: String): String? {
        var text = raw
            .replace(Regex("""\[/?COLOR[^]]*]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[/?[A-Z][A-Z0-9 _-]*[^]]*]"""), "")
            .replace(Regex("<[^>]+>"), "")
            .trim()
        if (text.isEmpty()) return null
        // Prefer the part after the last separator when it contains a fixture.
        val afterDot = text.substringAfterLast('·').trim()
        if (afterDot.contains(" - ") || afterDot.contains(" vs ")) text = afterDot
        if (text.contains(':')) text = text.substringAfterLast(':').trim()
        text = text.replace(Regex("""^(DIRECTO|PREVIA|PARTIDO|EN DIRECTO)\s*:?\s*""", RegexOption.IGNORE_CASE), "")
        val separator = when {
            text.contains(" - ") -> " - "
            text.contains(" vs ") -> " vs "
            else -> return null
        }
        val parts = text.split(separator, limit = 2)
        if (parts.size < 2) return null
        var left = parts[0].trim().substringAfterLast(':').trim()
        var right = parts[1].substringBefore(" ·").substringBefore(" T2").substringBefore(" (").trim()
        left = left.replace(Regex("""\s+T\d{2}/\d{2}.*$"""), "").trim()
        right = right.replace(Regex("""\s+T\d{2}/\d{2}.*$"""), "").trim()
        if (left.length < 2 || right.length < 2) return null
        return "$left - $right"
    }

    private fun applyFilter(focusGrid: Boolean) {
        if (all.isEmpty() && !::search.isInitialized) return
        val query = search.text?.toString()?.trim()?.lowercase().orEmpty()
        // A search is global: reset the category so "DAZN" finds DAZN channels even when the
        // viewer was in SOCCER a moment ago.
        if (query.isNotEmpty() && selectedGroup != "Todos") {
            selectedGroup = "Todos"
            updateChipStyles()
        }
        val filtered = all.filter { channel ->
            (selectedGroup == "Todos" || channel.group == selectedGroup) &&
                (query.isEmpty() || channel.name.lowercase().contains(query) || channel.group.lowercase().contains(query))
        }
        adapter.replace(filtered)
        recycler.scrollToPosition(0)
        count.text = if (query.isEmpty() && selectedGroup == "Todos") "${filtered.size} canales" else "${filtered.size} de ${all.size} canales"
        if (focusGrid) {
            recycler.post {
                recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
    }

    private fun play(channel: Channel) {
        val rawGuide = currentGuide(guideSchedule)[channel.epgId]
        val program = rawGuide?.let { teamsFromTitle(it) }.orEmpty()
        ChannelStore.remember(this, channel.name, channel.url, channel.group, channel.epgId, program)
        ChannelStore.play(this, JSONObject()
            .put("url", channel.url)
            .put("name", channel.name)
            .toString())
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(search.windowToken, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun chipBackground(selected: Boolean): StateListDrawable {
        val selectedBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(ACCENT)
            cornerRadius = dp(999).toFloat()
        }
        val focusedBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.rgb(37, 37, 41))
            setStroke(dp(2), Color.WHITE)
            cornerRadius = dp(999).toFloat()
        }
        val normalBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.rgb(23, 23, 25))
            setStroke(dp(1), BORDER)
            cornerRadius = dp(999).toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), selectedBg)
            addState(intArrayOf(android.R.attr.state_focused), focusedBg)
            addState(intArrayOf(), normalBg)
        }
    }

    private fun fieldBackground(): StateListDrawable {
        val focused = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(CARD)
            setStroke(dp(2), ACCENT)
            cornerRadius = dp(9).toFloat()
        }
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(CARD)
            setStroke(dp(1), BORDER)
            cornerRadius = dp(9).toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun cardBackground(): StateListDrawable {
        val focused = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(ACCENT)
            cornerRadius = dp(10).toFloat()
        }
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(CARD)
            setStroke(dp(1), BORDER)
            cornerRadius = dp(10).toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    private data class Channel(val name: String, val url: String, val group: String, val epgId: String?)
    private data class GuideProgram(val channel: String, val start: Long, val stop: Long, val title: String)

    private inner class ChannelAdapter(
        private var items: List<Channel>,
        private val onPlay: (Channel) -> Unit,
    ) : RecyclerView.Adapter<ChannelAdapter.Holder>() {

        private var programs: Map<String, String> = emptyMap()

        inner class Holder(
            val card: LinearLayout,
            val name: TextView,
            val tag: TextView,
            val program: TextView,
        ) : RecyclerView.ViewHolder(card)

        fun replace(list: List<Channel>) {
            items = list
            notifyDataSetChanged()
        }

        fun setPrograms(map: Map<String, String>) {
            programs = map
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = LinearLayout(this@ChannelsActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isFocusable = true
                isFocusableInTouchMode = true
                background = cardBackground()
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(122)
                ).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
            }
            val name = TextView(this@ChannelsActivity).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            }
            val tag = TextView(this@ChannelsActivity).apply {
                setTextColor(MUTED)
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = .12f
                setPadding(0, dp(3), 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val program = TextView(this@ChannelsActivity).apply {
                setTextColor(PROGRAM)
                textSize = 10f
                setPadding(0, dp(3), 0, 0)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            card.addView(name)
            card.addView(tag)
            card.addView(program)
            return Holder(card, name, tag, program)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val channel = items[position]
            holder.name.text = channel.name
            holder.tag.text = channel.group.uppercase()
            val guide = programs[channel.epgId]?.let { teamsFromTitle(it) }
            val fallback = if (channel.group == "La Liga") "Sin datos de equipos" else null
            val text = guide ?: fallback
            holder.program.text = text ?: ""
            holder.program.setTextColor(if (guide != null) PROGRAM else MUTED)
            holder.program.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            holder.card.contentDescription = "${channel.name}, ${channel.group}" + if (text.isNullOrBlank()) "" else ", ahora: $text"
            holder.card.setOnClickListener { onPlay(channel) }
        }

        override fun getItemCount(): Int = items.size
    }

    companion object {
        private val BG = Color.rgb(10, 10, 11)
        private val CARD = Color.rgb(23, 23, 25)
        private val BORDER = Color.rgb(48, 48, 52)
        private val MUTED = Color.rgb(163, 163, 170)
        private val ACCENT = Color.rgb(229, 9, 20)
        private val PROGRAM = Color.rgb(167, 218, 184)
        private val CATEGORY_PRIORITY = listOf("La Liga", "Liga de Campeones", "Fútbol")
        private const val EPG_URL = "https://raw.githubusercontent.com/davidmuma/EPG_dobleM/master/guiatv.xml.gz"
        private const val EPG_MAX_AGE_MS = 6 * 60 * 60 * 1000L
        private const val EPG_SCHEDULE_TTL_MS = 2 * 60 * 60 * 1000L
        private const val GUIDE_TICK_MS = 60_000L
    }
}
