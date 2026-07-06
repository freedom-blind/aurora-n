package com.nous.aurora.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.View
import kotlin.random.Random

class GlitchOverlay(context: Context) : View(context) {

    private val paint = Paint()
    private val handler = Handler(Looper.getMainLooper())
    private var running = true

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)
        startGlitch()
    }

    private fun startGlitch() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!running) return
                invalidate()
                handler.postDelayed(this, Random.nextLong(50, 300))
            }
        }, 0)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running) return

        val w = width.toFloat()
        val h = height.toFloat()

        // Random horizontal shift lines (glitch bands)
        for (i in 0 until Random.nextInt(1, 5)) {
            val y = Random.nextFloat() * h
            val bandH = Random.nextFloat() * 40f + 4f
            val shiftX = Random.nextFloat() * 60f - 30f

            // Draw shifted copy of the screen content? No — just draw colored bands
            paint.color = when (Random.nextInt(4)) {
                0 -> Color.argb(80, 255, 0, 0)
                1 -> Color.argb(80, 0, 255, 0)
                2 -> Color.argb(80, 0, 0, 255)
                else -> Color.argb(40, 255, 255, 255)
            }
            canvas.drawRect(shiftX, y, w + shiftX, y + bandH, paint)
        }

        // Random scan lines
        paint.color = Color.argb(15, 0, 0, 0)
        for (y in 0..h.toInt() step 4) {
            if (Random.nextFloat() > 0.7f) {
                canvas.drawRect(0f, y.toFloat(), w, (y + 2).toFloat(), paint)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
    }
}
