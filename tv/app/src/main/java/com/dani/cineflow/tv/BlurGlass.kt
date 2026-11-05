package com.dani.cineflow.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.view.View

/** One-shot RenderScript blur used for the floating menu's frosted-glass backdrop. */
object BlurGlass {
    fun capture(context: Context, source: View?, radius: Float = 18f): Bitmap? {
        if (source == null || source.width <= 0 || source.height <= 0) return null
        val scale = 0.28f
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val input = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) { return null }
        try {
            val canvas = Canvas(input)
            canvas.scale(scale, scale)
            source.draw(canvas)
        } catch (_: Exception) { return input }
        var rs: RenderScript? = null
        var inAlloc: Allocation? = null
        var outAlloc: Allocation? = null
        return try {
            rs = RenderScript.create(context)
            inAlloc = Allocation.createFromBitmap(rs, input)
            outAlloc = Allocation.createTyped(rs, inAlloc.type)
            val blur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs)).apply {
                setRadius(radius.coerceIn(1f, 25f))
                setInput(inAlloc)
            }
            blur.forEach(outAlloc)
            outAlloc.copyTo(input)
            input
        } catch (_: Exception) {
            null
        } finally {
            outAlloc?.destroy()
            inAlloc?.destroy()
            rs?.destroy()
        }
    }
}
