package com.dani.cineflow.tv

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Offers the Spanish TMDB catalog addon to logged-in users who do not have it yet.
 *
 * The manifest URL is preconfigured (language, catalogs and the TMDB API settings). The user does
 * not configure anything: accepting writes the addon into their Stremio account through the same
 * addonCollectionSet API used by the Addons screen.
 */
object CatalogPrompt {
    private const val PREFS = "cineflow-catalog-prompt"
    private const val LAST_SHOWN = "lastShown"
    private const val LAST_CHECK = "lastCheck"
    private const val DAY = 24 * 60 * 60 * 1000L
    private val busy = AtomicBoolean(false)

    // Preconfigured Spanish TMDB addon. Keep this as the single shared manifest used by CineFlow.
    private const val MANIFEST = "https://tmdb.elfhosted.com/N4IgTgDgJgRg1gUwJ4gFwgC4AYC0AzMBBHSWEgewFcA7KBKHGAG3IGM4BnEAGnGhgCy9AJYBDACpIICLqlARyHDAjBoMYSgl4sA5uTR5RTDlpAxR7KGHIQDRkwF9eGGwqUqOiFOh6ZXi5TAOAGFyajxhHTR5AJVJaTQQN0CcOkNKJgxfJSQmBESAW3I6MGpfDAALSgKYalFhJgAhUSgdBAAFRWEMYTDElwgSSIqs5yqauobm1oQAZWEAL3z0DgKjJnLx2vqmpkpVVENjBCdMAtgAQQhhAGlkRNYADlE8KEfHgCYP58esBEeAGx0KDfBAAVgA7ABGADMFigogBYN8TFE1B0lFEbUSMhwAFFZtl1AhRAVhOi0ABtAC6vFYogwRnIOlklNAwig-XOMAAdKjlEpylJliAigA3YT5Xh1AoigAyDJkGAABAAlBB5UQmLi8DgVcgAdwAktQABLkWVqDQnbjsznoDDcvmKwXOYWJExgSU6kAy+UulXqzXa7L641mi3LdSaJx2rmwHnE2jkqJuhLocWS3x+xLiQjJim6sMm82W1DRm1xh1OpNQFNC9MgT3e7OkkV5hAF1NN4sRssV2MgDnx3kDBsizNS31txKdCAZUSqIuGkuRq0x21D+1nBNjtMi5syVtlkBzhdLnsrvtR62D4fVhNIEkXjDujPkCVTnPoACaz9DV6ljeG5VjuvJPou44eioLbSjOv7-su4ZAeulZbiOzropi2L7oUH5ZnBJ4KlhWJTnqgFruWt6bveYGYRipFQSsMFHoR-okThl7IZRA60iA9JKKENBZKgYJIRcbSqgyKYmgA4p2hCyJg1q+J2ojMAgEkIFJPQUugRwmABBpaTpKYAOrdBURrcqZekgAZZHqMIrAYOqOi9NQABiDSBIkDm+HW7mMkwQYkiY3mZCodjHA4QA/manifest.json"

    fun checkIfNeeded(activity: Activity, onInstalled: () -> Unit) {
        if (!StremioSession.isLoggedIn(activity)) return
        if (!busy.compareAndSet(false, true)) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(LAST_CHECK, 0L) < 10 * 60 * 1000L) {
            busy.set(false)
            return
        }
        prefs.edit().putLong(LAST_CHECK, now).apply()
        thread(name = "cineflow-catalog-check", isDaemon = true) {
            try {
                val descriptors = StremioSession.addonDescriptors(activity)
                val installed = descriptors.any {
                    val manifest = it.optJSONObject("manifest") ?: return@any false
                    manifest.optString("id") == "tmdb-addon" ||
                        manifest.optString("name").contains("Movie Database", ignoreCase = true)
                }
                if (installed) return@thread
                if (now - prefs.getLong(LAST_SHOWN, 0L) < 7 * DAY) return@thread
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    prefs.edit().putLong(LAST_SHOWN, now).apply()
                    show(activity, onInstalled)
                }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun show(activity: Activity, onInstalled: () -> Unit) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 30), dp(activity, 26), dp(activity, 30), dp(activity, 22))
            background = rounded(activity, Color.rgb(18, 20, 26), 16, Color.rgb(60, 63, 74), 1)
        }
        card.addView(TextView(activity).apply {
            text = "Catálogo en español"
            setTextColor(Color.rgb(242, 235, 221))
            textSize = 24f
            typeface = Typeface.create("serif", Typeface.BOLD)
        })
        card.addView(TextView(activity).apply {
            text = "The Movie Database añade títulos, sinopsis y carátulas en castellano."
            setTextColor(Color.rgb(210, 214, 222))
            textSize = 12f
            setPadding(0, dp(activity, 8), 0, dp(activity, 5))
        })
        card.addView(TextView(activity).apply {
            text = "Se instalará en tu cuenta de Stremio. No tendrás que configurar nada."
            setTextColor(Color.rgb(155, 160, 170))
            textSize = 10f
            setPadding(0, 0, 0, dp(activity, 16))
        })
        val status = TextView(activity).apply {
            text = ""
            setTextColor(Color.rgb(145, 227, 207))
            textSize = 9f
            setPadding(0, 0, 0, dp(activity, 8))
        }
        card.addView(status)
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val later = button(activity, "AHORA NO", false)
        val install = button(activity, "ACTIVAR CATÁLOGO", true)
        row.addView(later, LinearLayout.LayoutParams(dp(activity, 150), dp(activity, 42)).apply { rightMargin = dp(activity, 8) })
        row.addView(install, LinearLayout.LayoutParams(dp(activity, 205), dp(activity, 42)))
        card.addView(row)
        dialog.setContentView(card)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(dp(activity, 580), ViewGroup.LayoutParams.WRAP_CONTENT)
        later.setOnClickListener { dialog.dismiss() }
        install.setOnClickListener {
            install.isEnabled = false
            later.isEnabled = false
            install.text = "INSTALANDO…"
            status.text = "Añadiendo el catálogo a tu cuenta…"
            thread(name = "cineflow-catalog-install", isDaemon = true) {
                val result = StremioSession.installAddon(activity, MANIFEST)
                if (result.first) StremioSession.addons(activity, force = true)
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    if (result.first) {
                        status.text = "✓ Catálogo instalado. Actualizando Inicio…"
                        dialog.dismiss()
                        Toast.makeText(activity, "Catálogo TMDB añadido. Actualizando Inicio…", Toast.LENGTH_LONG).show()
                        onInstalled()
                    } else {
                        install.isEnabled = true
                        later.isEnabled = true
                        install.text = "ACTIVAR CATÁLOGO"
                        status.setTextColor(Color.rgb(255, 120, 105))
                        status.text = "No se pudo instalar: ${result.second}"
                    }
                }
            }
        }
        dialog.show()
        dialog.window?.setLayout(dp(activity, 580), ViewGroup.LayoutParams.WRAP_CONTENT)
        install.requestFocus()
    }

    private fun button(activity: Activity, text: String, filled: Boolean): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(if (filled) Color.rgb(12, 13, 16) else Color.rgb(242, 235, 221))
        textSize = 9f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = .08f
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        background = rounded(activity, if (filled) Color.rgb(255, 77, 61) else Color.rgb(28, 31, 38), 10,
            if (filled) Color.rgb(255, 77, 61) else Color.rgb(55, 58, 68), 1)
        setOnFocusChangeListener { v, has ->
            v.background = rounded(activity, if (has) Color.WHITE else if (filled) Color.rgb(255, 77, 61) else Color.rgb(28, 31, 38),
                10, if (has) Color.WHITE else if (filled) Color.rgb(255, 77, 61) else Color.rgb(55, 58, 68), 1)
            v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f).setDuration(120).start()
        }
    }

    private fun rounded(context: Context, color: Int, radius: Int, stroke: Int, width: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(context, radius).toFloat()
        if (width > 0) setStroke(dp(context, width), stroke)
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
