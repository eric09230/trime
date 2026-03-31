/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.glide

import kotlin.math.sqrt

/**
 * Normalizes a [GlideTrace] by resampling to equidistant points and
 * scaling to a unit bounding box centered at the origin.
 */
object TraceNormalizer {
    const val RESAMPLE_COUNT = 64

    /**
     * Walk along the path defined by [trace] and place [n] equidistant points
     * via linear interpolation.
     *
     * @return list of (x, y) pairs representing the resampled path.
     */
    fun resample(trace: GlideTrace, n: Int = RESAMPLE_COUNT): List<Pair<Float, Float>> {
        if (trace.isEmpty) return emptyList()
        if (trace.points.size == 1) {
            val p = trace.points[0]
            return List(n) { p.x to p.y }
        }

        // Compute segment lengths
        val pts = trace.points
        val segLengths = FloatArray(pts.size - 1) { i ->
            val dx = pts[i + 1].x - pts[i].x
            val dy = pts[i + 1].y - pts[i].y
            sqrt(dx * dx + dy * dy)
        }
        val totalLength = segLengths.sum()

        if (totalLength == 0f) {
            val p = pts[0]
            return List(n) { p.x to p.y }
        }

        val interval = totalLength / (n - 1)
        val result = mutableListOf<Pair<Float, Float>>()
        result.add(pts[0].x to pts[0].y)

        var segIndex = 0
        var distIntoSeg = 0f
        var accumulated = 0f

        for (i in 1 until n) {
            val targetDist = i * interval
            while (segIndex < segLengths.size) {
                val segLen = segLengths[segIndex]
                val remaining = segLen - distIntoSeg
                val needed = targetDist - accumulated

                if (needed <= remaining) {
                    // Interpolation point is within the current segment
                    val t = if (segLen > 0f) (distIntoSeg + needed) / segLen else 0f
                    val x = pts[segIndex].x + t * (pts[segIndex + 1].x - pts[segIndex].x)
                    val y = pts[segIndex].y + t * (pts[segIndex + 1].y - pts[segIndex].y)
                    result.add(x to y)
                    accumulated = targetDist
                    distIntoSeg += needed
                    break
                } else {
                    // Move past the current segment
                    accumulated += remaining
                    distIntoSeg = 0f
                    segIndex++
                }
            }

            // Safety: if we ran out of segments, add the last point
            if (result.size <= i) {
                val last = pts.last()
                result.add(last.x to last.y)
            }
        }

        return result
    }

    /**
     * Scale [points] to a unit bounding box and center at origin.
     * If the bounding box has zero width or height, that dimension is left at 0.
     *
     * @return list of normalized (x, y) pairs.
     */
    fun normalize(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (points.isEmpty()) return emptyList()

        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE

        for ((x, y) in points) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }

        val rangeX = maxX - minX
        val rangeY = maxY - minY
        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f

        // Use the larger range for uniform scaling to preserve aspect ratio
        val scale = maxOf(rangeX, rangeY)

        return if (scale == 0f) {
            // All points are the same; center at origin
            points.map { 0f to 0f }
        } else {
            points.map { (x, y) ->
                ((x - centerX) / scale) to ((y - centerY) / scale)
            }
        }
    }

    /**
     * Full processing pipeline: resample then normalize.
     *
     * @return list of normalized (x, y) pairs with [n] points.
     */
    fun process(trace: GlideTrace, n: Int = RESAMPLE_COUNT): List<Pair<Float, Float>> {
        return normalize(resample(trace, n))
    }
}
