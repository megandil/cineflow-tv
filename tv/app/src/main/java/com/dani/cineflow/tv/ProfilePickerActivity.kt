package com.dani.cineflow.tv

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale

/** “Who’s watching?” and local profile management. */
class ProfilePickerActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        setContentView(buildUi())
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(INK) }
        val scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(54), dp(34), dp(54), dp(30))
        }
        content.addView(TextView(this).apply {
            text = "CINEFLOW"
            setTextColor(CORAL)
            textSize = 9f
            letterSpacing = .2f
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "¿Quién está viendo?"
            setTextColor(CREAM)
            textSize = 34f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(4))
        })
        content.addView(TextView(this).apply {
            text = "Cada perfil tiene su historial, Continuar viendo y ajustes. Los addons se comparten."
            setTextColor(Color.rgb(155,160,170))
            textSize = 11f
            setPadding(0, 0, 0, dp(20))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(list)
        scroll.addView(content)
        root.addView(scroll)
        return root
    }

    private fun render() {
        list.removeAllViews()
        val profiles = Profiles.list(this)
        val active = Profiles.activeId(this)
        for (profile in profiles) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                tag = profile
                layoutParams = LinearLayout.LayoutParams(dp(150), dp(155)).apply { rightMargin = dp(12) }
                background = rounded(if (profile.id == active) Color.rgb(34,38,47) else CARD, 14,
                    if (profile.id == active) CORAL else BORDER, if (profile.id == active) 2 else 1)
                setOnClickListener {
                    Profiles.setActive(this@ProfilePickerActivity, profile.id)
                    // Restart Home in a clean task: Android does not reliably re-run the
                    // profile-aware onResume when the picker was opened from Settings.
                    startActivity(Intent(this@ProfilePickerActivity, NativeHomeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra(NativeHomeActivity.EXTRA_PROFILE_SELECTED, true))
                    finishAffinity()
                }
                onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                    v.background = rounded(if (has) Color.rgb(43,47,56) else if (profile.id == active) Color.rgb(34,38,47) else CARD, 14,
                        if (has || profile.id == active) CORAL else BORDER, if (has || profile.id == active) 2 else 1)
                }
            }
            card.addView(TextView(this).apply {
                text = profile.name.take(1).uppercase(Locale.ROOT)
                setTextColor(Color.WHITE)
                textSize = 30f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(profile.color) }
                layoutParams = LinearLayout.LayoutParams(dp(74), dp(74))
            })
            card.addView(TextView(this).apply {
                text = profile.name
                setTextColor(CREAM)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            })
            if (profile.main) card.addView(TextView(this).apply {
                text = "PRINCIPAL · sincroniza con Stremio"
                setTextColor(Color.rgb(145,227,207))
                textSize = 7f
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
            list.addView(card)
        }
        val add = TextView(this).apply {
            text = "＋\nAÑADIR\nPERFIL"
            setTextColor(CREAM)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .06f
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = rounded(CARD, 14, BORDER, 1)
            layoutParams = LinearLayout.LayoutParams(dp(150), dp(155)).apply { rightMargin = dp(12) }
            setOnClickListener { addProfile() }
            onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                v.background = rounded(if (has) Color.rgb(43,47,56) else CARD, 14, if (has) CORAL else BORDER, if (has) 2 else 1)
            }
        }
        list.addView(add)
        list.post { list.getChildAt(0)?.requestFocus() }
    }

    private fun addProfile() {
        val input = EditText(this).apply {
            hint = "Nombre del perfil"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(130,135,145))
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Nuevo perfil")
            .setView(input)
            .setNegativeButton("CANCELAR", null)
            .setPositiveButton("CREAR") { _, _ ->
                val name = input.text.toString().ifBlank { "Perfil ${Profiles.list(this).size + 1}" }
                Profiles.create(this, name)
                render()
            }
            .create()
        dialog.setOnShowListener { input.requestFocus() }
        dialog.show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_SETTINGS)) {
            var view = currentFocus
            while (view != null) {
                val profile = view.tag as? Profiles.Profile
                if (profile != null && !profile.main) {
                    AlertDialog.Builder(this)
                        .setMessage("¿Eliminar el perfil «${profile.name}»? Se borrarán su historial y sus ajustes locales.")
                        .setNegativeButton("CANCELAR", null)
                        .setPositiveButton("ELIMINAR") { _, _ -> Profiles.delete(this, profile.id); render() }
                        .show()
                    return true
                }
                view = view.parent as? View
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun rounded(color: Int, radius: Int, stroke: Int, width: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (width > 0) setStroke(dp(width), stroke)
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
        private val INK = Color.rgb(5,5,7)
        private val CARD = Color.rgb(18,18,20)
        private val BORDER = Color.rgb(48,51,60)
        private val CREAM = Color.rgb(245,245,247)
        private val CORAL = Color.rgb(245,245,247)
    }
}
