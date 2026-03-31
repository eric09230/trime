/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.glide

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.osfans.trime.data.prefs.AppPrefs
import splitties.dimensions.dp

/**
 * A transparent overlay [View] that draws the gesture trail during glide typing.
 *
 * Call [updateTrail] with the current list of [GlidePoint]s to redraw the path,
 * and [clearTrail] to fade out and remove the trail after a short delay.
 */
class GlideOverlayView(context: Context) : View(context) {

    companion object {
        /** Duration (ms) of the fade-out animation when [clearTrail] is called. */
        private const val FADE_DURATION_MS = 200L

        private const val TRAIL_STROKE_WIDTH = 6f
        private const val TRAIL_COLOR = 0xAA4285F4.toInt() // semi-transparent blue
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TRAIL_COLOR
        style = Paint.Style.STROKE
        strokeWidth = context.dp(AppPrefs.defaultInstance().keyboard.glideTrailWidth.getValue()).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val trailPath = Path()
    private var fadeAnimator: ValueAnimator? = null

    init {
        // This view is transparent and does not consume touch events.
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
    }

    // ---- public API ----

    /**
     * Redraws the gesture trail with the given [points].
     */
    fun updateTrail(points: List<GlidePoint>) {
        // Cancel any pending fade
        fadeAnimator?.cancel()
        trailPaint.alpha = (TRAIL_COLOR ushr 24)

        trailPath.reset()
        if (points.isNotEmpty()) {
            trailPath.moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) {
                trailPath.lineTo(points[i].x, points[i].y)
            }
        }
        invalidate()
    }

    /**
     * Fades out the trail over [FADE_DURATION_MS] milliseconds, then clears it.
     */
    fun clearTrail() {
        val startAlpha = trailPaint.alpha
        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofInt(startAlpha, 0).apply {
            duration = FADE_DURATION_MS
            addUpdateListener { animator ->
                trailPaint.alpha = animator.animatedValue as Int
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    trailPath.reset()
                    invalidate()
                }
            })
            start()
        }
    }

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!trailPath.isEmpty) {
            canvas.drawPath(trailPath, trailPaint)
        }
    }
}
