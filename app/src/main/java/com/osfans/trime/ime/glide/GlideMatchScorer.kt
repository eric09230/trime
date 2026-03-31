/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.glide

import kotlin.math.sqrt

/**
 * SHARK2 dual-channel scoring for glide typing.
 *
 * Two scoring channels:
 * - **Shape channel**: measures how closely the gesture shape matches the template shape
 *   (average Euclidean distance between corresponding normalized points).
 * - **Location channel**: measures how close the gesture start/end are to the expected
 *   first/last key centers on the keyboard layout.
 *
 * The combined score blends both channels with configurable weights.
 * Lower scores indicate better matches.
 */
object GlideMatchScorer {
    const val SHAPE_WEIGHT = 0.7f
    const val LOCATION_WEIGHT = 0.3f
    const val SHORT_SEQUENCE_THRESHOLD = 3

    /**
     * Shape channel: compute the average Euclidean distance between corresponding
     * points in the [gesture] and [template] sequences.
     *
     * Both lists are expected to have the same length (as produced by [TraceNormalizer]).
     * If lengths differ, the shorter length is used.
     */
    fun shapeDistance(
        gesture: List<Pair<Float, Float>>,
        template: List<Pair<Float, Float>>,
    ): Float {
        if (gesture.isEmpty() || template.isEmpty()) return Float.MAX_VALUE

        val count = minOf(gesture.size, template.size)
        var totalDistance = 0f

        for (i in 0 until count) {
            val (gx, gy) = gesture[i]
            val (tx, ty) = template[i]
            val dx = gx - tx
            val dy = gy - ty
            totalDistance += sqrt(dx * dx + dy * dy)
        }

        return totalDistance / count
    }

    /**
     * Location channel: compute a score based on how close the trace's start and
     * end points are to the first and last key centers in the key sequence.
     *
     * The score is the average of the two endpoint distances, normalized by
     * dividing by the average key width/height to produce a unit-independent metric.
     *
     * Returns [Float.MAX_VALUE] if the trace or key sequence is invalid.
     */
    fun locationScore(
        trace: GlideTrace,
        keySequence: String,
        layout: QwertyLayout,
    ): Float {
        if (keySequence.isEmpty()) return Float.MAX_VALUE

        val startPoint = trace.startPoint ?: return Float.MAX_VALUE
        val endPoint = trace.endPoint ?: return Float.MAX_VALUE

        val firstKeyCenter = layout.getKeyCenterOf(keySequence.first()) ?: return Float.MAX_VALUE
        val lastKeyCenter = layout.getKeyCenterOf(keySequence.last()) ?: return Float.MAX_VALUE

        val startDx = startPoint.x - firstKeyCenter.first
        val startDy = startPoint.y - firstKeyCenter.second
        val startDist = sqrt(startDx * startDx + startDy * startDy)

        val endDx = endPoint.x - lastKeyCenter.first
        val endDy = endPoint.y - lastKeyCenter.second
        val endDist = sqrt(endDx * endDx + endDy * endDy)

        return (startDist + endDist) / 2f
    }

    /**
     * Combined score blending shape and location channels.
     *
     * For short key sequences (<= [SHORT_SEQUENCE_THRESHOLD] keys), location
     * becomes more important (0.5 / 0.5 weighting) because the shape signal is
     * less discriminative with fewer points.
     *
     * For longer sequences, the default [SHAPE_WEIGHT] / [LOCATION_WEIGHT] is used.
     *
     * Lower scores indicate better matches.
     */
    fun combinedScore(
        gesture: List<Pair<Float, Float>>,
        template: List<Pair<Float, Float>>,
        trace: GlideTrace,
        keySequence: String,
        layout: QwertyLayout,
    ): Float {
        val shapeDist = shapeDistance(gesture, template)
        val locScore = locationScore(trace, keySequence, layout)

        if (shapeDist == Float.MAX_VALUE || locScore == Float.MAX_VALUE) {
            return Float.MAX_VALUE
        }

        val isShortSequence = keySequence.length <= SHORT_SEQUENCE_THRESHOLD

        val shapeW: Float
        val locW: Float

        if (isShortSequence) {
            shapeW = 0.5f
            locW = 0.5f
        } else {
            shapeW = SHAPE_WEIGHT
            locW = LOCATION_WEIGHT
        }

        return shapeW * shapeDist + locW * locScore
    }

    /** Frequency rank penalty weight. */
    const val FREQUENCY_ALPHA = 0.05f

    /**
     * Combined score with a frequency rank penalty.
     * Lower rank (more common) = lower penalty = better score.
     *
     * @param frequencyRank 0-based rank in word list (0 = most common). Use -1 for unranked.
     */
    fun combinedScoreWithFrequency(
        gesture: List<Pair<Float, Float>>,
        template: List<Pair<Float, Float>>,
        trace: GlideTrace,
        keySequence: String,
        layout: QwertyLayout,
        frequencyRank: Int,
    ): Float {
        val base = combinedScore(gesture, template, trace, keySequence, layout)
        if (base == Float.MAX_VALUE) return Float.MAX_VALUE
        val rank = if (frequencyRank >= 0) frequencyRank else 10000
        return base + FREQUENCY_ALPHA * kotlin.math.ln(1f + rank.toFloat())
    }
}
