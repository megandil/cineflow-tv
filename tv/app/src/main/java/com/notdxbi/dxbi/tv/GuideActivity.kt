package com.notdxbi.dxbi.tv

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Xml
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
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
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * CineFlow Guía: a native TV guide for the channels that have EPG. Each row shows what was on,
 * what is on and what comes next, grouped by the same categories as Canales.
 */
class GuideActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: GuideAdapter
    private lateinit var categoryRow: LinearLayout
    private lateinit var status: TextView
    private lateinit var count: TextView
    private val chips = LinkedHashMap<String, TextView>()
    private var selectedGroup = "Todos"
    private var channels: List<GuideChannel> = emptyList()
    private var schedule: Map<String, List<Program>> = emptyMap()
    private var items: List<GuideItem> = emptyList()
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refreshItems(false)
            ticker.postDelayed(this, 60_000L)
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
        load()
    }

    override fun onResume() {
        super.onResume()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
        }
        recycler.postDelayed({ if (currentFocus == null) focusFirst() }, 250)
    }

    override fun onDestroy() {
        ticker.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || event.keyCode == KeyEvent.KEYCODE_BUTTON_A)
        ) return true
        return super.dispatchKeyEvent(event)
    }

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
            text = "Guía"
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
            text = "Programación anterior, actual y próxima · categorías y partidos"
            setTextColor(MUTED)
            textSize = 12f
            setPadding(0, dp(2), 0, 0)
        })
        status = TextView(this).apply {
            text = "EPG: cargando…"
            setTextColor(PROGRAM)
            textSize = 11f
            setPadding(0, dp(2), 0, dp(10))
        }
        root.addView(status)

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

        val legend = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(19), dp(10), dp(19), dp(2))
        }
        legend.addView(legendCell("ANTERIOR", 1f))
        legend.addView(legendCell("AHORA", 1.2f))
        legend.addView(legendCell("PRÓXIMO", 1f))
        root.addView(legend)

        recycler = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(this@GuideActivity)
            setPadding(0, 0, 0, dp(16))
            clipToPadding = false
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            setHasFixedSize(true)
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        adapter = GuideAdapter(emptyList()) { play(it) }
        recycler.adapter = adapter
        root.addView(recycler)
        return root
    }

    private fun legendCell(text: String, weight: Float): TextView = TextView(this).apply {
        this.text = text
        setTextColor(MUTED)
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = .14f
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
    }

    private fun load() {
        thread(name = "cineflow-guide", isDaemon = true) {
            val loadedChannels = try {
                assets.open("channels.m3u").use { parseM3u(it) }
            } catch (_: Exception) {
                emptyList()
            }
            val wanted = loadedChannels.mapNotNull { it.epgId }.toSet()
            var programmes = readCache()
            if (programmes.isEmpty() && wanted.isNotEmpty()) {
                runOnUiThread { status.text = "EPG: descargando guía…" }
                programmes = try {
                    val file = epgFile()
                    parseEpg(file, wanted).also { if (it.isNotEmpty()) writeCache(it) }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            val byChannel = programmes.groupBy { it.channel }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                schedule = byChannel
                val withGuide = loadedChannels
                    .filter { ch -> ch.epgId?.let { byChannel[it]?.isNotEmpty() == true } == true }
                    .distinctBy { it.epgId ?: displayName(it.name) }
                channels = withGuide
                if (withGuide.isEmpty()) {
                    status.text = if (programmes.isEmpty()) "EPG: no se pudo cargar la guía" else "EPG: sin programación para estos canales"
                } else {
                    status.text = "EPG: ${withGuide.size} canales · ${programmes.size} programas"
                }
                buildCategories(withGuide)
                refreshItems(false)
                recycler.postDelayed({ focusFirst() }, 250)
                ticker.removeCallbacks(tick)
                ticker.post(tick)
            }
        }
    }

    private fun buildCategories(guideChannels: List<GuideChannel>) {
        val counts = guideChannels.groupingBy { it.group }.eachCount()
        val priority = CATEGORY_PRIORITY.filter { counts.containsKey(it) }
        val rest = counts.entries.filter { it.key !in CATEGORY_PRIORITY }.sortedByDescending { it.value }.map { it.key }
        val groups = listOf("Todos", "Partidos") + priority + rest
        categoryRow.removeAllViews()
        chips.clear()
        for (group in groups) {
            val chip = TextView(this).apply {
                text = group
                setTextColor(MUTED)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                isFocusable = true
                isFocusableInTouchMode = true
                background = chipBackground(group == selectedGroup)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { rightMargin = dp(8) }
                setOnClickListener { selectGroup(group) }
            }
            chips[group] = chip
            categoryRow.addView(chip)
        }
    }

    private fun selectGroup(group: String) {
        selectedGroup = group
        for ((g, chip) in chips) {
            val selected = g == group
            chip.isSelected = selected
            chip.background = chipBackground(selected)
            chip.setTextColor(if (selected) Color.WHITE else MUTED)
        }
        refreshItems(true)
    }

    private fun refreshItems(focusFirst: Boolean) {
        val now = System.currentTimeMillis()
        val built = ArrayList<GuideItem>()
        for (channel in channels) {
            val id = channel.epgId ?: continue
            val programmes = schedule[id]?.sortedBy { it.start } ?: continue
            if (programmes.isEmpty()) continue
            val current = programmes.firstOrNull { it.start <= now && now < it.stop }
            val previous = programmes.lastOrNull { it.stop <= (current?.start ?: now) }
            val next = programmes.firstOrNull {
                it !== current && it.start >= (current?.stop ?: now)
            }
            if (current == null && previous == null && next == null) continue
            built.add(GuideItem(channel, previous, current, next))
        }
        items = built
        val visible = when (selectedGroup) {
            "Todos" -> built
            "Partidos" -> built.filter { item -> isMatch(item.current) || isMatch(item.next) }
            else -> built.filter { it.channel.group == selectedGroup }
        }
        adapter.replace(visible)
        count.text = "${visible.size} canales"
        if (visible.isEmpty() && items.isNotEmpty()) {
            status.text = "EPG: sin canales en ${selectedGroup.lowercase(Locale("es", "ES"))}"
        } else if (items.isNotEmpty()) {
            status.text = "EPG: ${items.size} canales · actualizado ${formatTime(now)}"
        }
        if (focusFirst) recycler.postDelayed({ focusFirst() }, 180)
    }

    private fun focusFirst() {
        val holder = recycler.findViewHolderForAdapterPosition(0)
        if (holder != null) holder.itemView.requestFocus()
        else recycler.requestFocus()
    }

    private fun play(item: GuideItem) {
        val channel = item.channel
        val current = item.current?.let { cleanTitle(it.title) }.orEmpty()
        ChannelStore.remember(this, channel.name, channel.url, channel.group, channel.epgId, current)
        ChannelStore.play(this, JSONObject()
            .put("url", channel.url)
            .put("name", channel.name)
            .toString())
    }

    private fun isMatch(programme: Program?): Boolean =
        programme != null && teamsFromTitle(cleanTitle(programme.title)) != null

    private fun parseM3u(input: InputStream): List<GuideChannel> {
        val out = ArrayList<GuideChannel>(1100)
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
                        out.add(GuideChannel((pendingName ?: "Canal").ifBlank { "Canal" }, line, cleanGroup(pendingGroup ?: currentGroup), pendingEpg))
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

    private fun readCache(): List<Program> {
        val file = File(cacheDir, "epg-schedule.json")
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val channel = o.optString("channel")
                val title = o.optString("title")
                if (channel.isBlank() || title.isBlank()) null
                else Program(channel, o.optLong("start"), o.optLong("stop"), title)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeCache(programmes: List<Program>) {
        try {
            val array = JSONArray()
            for (p in programmes) array.put(JSONObject()
                .put("channel", p.channel)
                .put("start", p.start)
                .put("stop", p.stop)
                .put("title", p.title))
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
            connection.inputStream.use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
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

    private fun parseEpg(file: File, wanted: Set<String>): List<Program> {
        val out = ArrayList<Program>()
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
                        out.add(Program(id, start, stop, title?.takeIf { it.isNotBlank() } ?: "Sin título"))
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

    private fun cleanTitle(raw: String): String = raw
        .replace(Regex("""\[/?COLOR[^]]*]""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\[/?[A-Z][A-Z0-9 _-]*[^]]*]"""), "")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("""\s+-->.*$"""), "")
        .trim()

    private fun teamsFromTitle(raw: String): String? {
        var text = cleanTitle(raw)
        if (text.isEmpty()) return null
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

    private fun formatTime(time: Long): String =
        SimpleDateFormat("HH:mm", Locale("es", "ES")).format(Date(time))

    private fun displayName(raw: String): String =
        raw.replace(Regex("""\s+-->\s+.*$"""), "").replace(Regex("""\s+[0-9a-f]{3,8}$""", RegexOption.IGNORE_CASE), "").trim()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun chipBackground(selected: Boolean): StateListDrawable {
        val selectedBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(ACCENT)
            cornerRadius = dp(999).toFloat()
        }
        val focusedBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.rgb(58, 20, 24))
            setStroke(dp(2), Color.WHITE)
            cornerRadius = dp(999).toFloat()
        }
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(CARD)
            setStroke(dp(1), BORDER)
            cornerRadius = dp(999).toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusedBg)
            addState(intArrayOf(android.R.attr.state_selected), selectedBg)
            addState(intArrayOf(), normal)
        }
    }

    private fun rowBackground(): StateListDrawable {
        val focused = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.rgb(38, 38, 43))
            setStroke(dp(2), ACCENT)
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

    private data class GuideChannel(val name: String, val url: String, val group: String, val epgId: String?)
    private data class Program(val channel: String, val start: Long, val stop: Long, val title: String)
    private data class GuideItem(val channel: GuideChannel, val previous: Program?, val current: Program?, val next: Program?)

    private inner class GuideAdapter(
        private var rows: List<GuideItem>,
        private val onClick: (GuideItem) -> Unit,
    ) : RecyclerView.Adapter<GuideAdapter.Holder>() {

        inner class Holder(val card: LinearLayout, val name: TextView, val group: TextView,
                           val prevTime: TextView, val prevTitle: TextView,
                           val nowTime: TextView, val nowTitle: TextView,
                           val nextTime: TextView, val nextTitle: TextView) : RecyclerView.ViewHolder(card)

        fun replace(list: List<GuideItem>) {
            rows = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = LinearLayout(this@GuideActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(9), dp(14), dp(9))
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                background = rowBackground()
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(126)).apply { setMargins(dp(5), dp(4), dp(5), dp(4)) }
            }
            val top = LinearLayout(this@GuideActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = TextView(this@GuideActivity).apply {
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val group = TextView(this@GuideActivity).apply {
                setTextColor(MUTED)
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = .12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10), 0, 0, 0)
            }
            top.addView(name)
            top.addView(group)
            card.addView(top)

            val columns = LinearLayout(this@GuideActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(5), 0, 0)
            }
            val (prevCell, prevTime, prevTitle) = cell(1f)
            val (nowCell, nowTime, nowTitle) = cell(1.2f, true)
            val (nextCell, nextTime, nextTitle) = cell(1f)
            columns.addView(prevCell)
            columns.addView(nowCell)
            columns.addView(nextCell)
            card.addView(columns)
            return Holder(card, name, group, prevTime, prevTitle, nowTime, nowTitle, nextTime, nextTitle)
        }

        private fun cell(weight: Float, now: Boolean = false): Triple<LinearLayout, TextView, TextView> {
            val box = LinearLayout(this@GuideActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(if (now) 8 else 2), dp(if (now) 4 else 0), dp(if (now) 8 else 2), dp(if (now) 3 else 0))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
                if (now) background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.rgb(28, 40, 32))
                    cornerRadius = dp(7).toFloat()
                }
            }
            val time = TextView(this@GuideActivity).apply {
                setTextColor(if (now) PROGRAM else MUTED)
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                maxLines = 1
            }
            val title = TextView(this@GuideActivity).apply {
                setTextColor(if (now) Color.WHITE else Color.rgb(196, 196, 202))
                textSize = if (now) 12f else 11f
                typeface = if (now) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
                includeFontPadding = false
            }
            box.addView(time)
            box.addView(title)
            return Triple(box, time, title)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = rows[position]
            holder.name.text = displayName(item.channel.name)
            holder.group.text = item.channel.group.uppercase(Locale("es", "ES"))
            bind(holder.prevTime, holder.prevTitle, item.previous, false)
            bind(holder.nowTime, holder.nowTitle, item.current, true)
            bind(holder.nextTime, holder.nextTitle, item.next, false)
            holder.card.contentDescription = buildString {
                append(displayName(item.channel.name))
                append(", ahora: ")
                append(item.current?.let { cleanTitle(it.title) } ?: "sin programación")
            }
            holder.card.setOnClickListener { onClick(item) }
        }

        private fun bind(time: TextView, title: TextView, programme: Program?, now: Boolean) {
            if (programme == null) {
                time.text = "—"
                title.text = if (now) "Sin programación" else ""
                title.setTextColor(MUTED)
                return
            }
            time.text = "${formatTime(programme.start)}–${formatTime(programme.stop)}"
            val clean = cleanTitle(programme.title)
            val match = if (now) teamsFromTitle(clean) else null
            title.text = match ?: clean
            title.setTextColor(if (now) Color.WHITE else Color.rgb(196, 196, 202))
        }

        override fun getItemCount(): Int = rows.size
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
    }
}
