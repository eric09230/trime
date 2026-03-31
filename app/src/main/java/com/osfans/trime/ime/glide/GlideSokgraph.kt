/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.glide

/**
 * Generates and caches ideal gesture templates (sokgraphs) for key sequences.
 * A sokgraph is the normalized ideal trace that a user would draw to input
 * a particular key sequence via glide typing.
 */
class GlideSokgraph(private val layout: QwertyLayout) {
    private val templateCache = java.util.concurrent.ConcurrentHashMap<String, List<Pair<Float, Float>>>()

    companion object {
        const val INTERP_POINTS_PER_SEGMENT = 8
    }

    /**
     * Generate raw points for a key sequence by connecting key centers with interpolation.
     * Between consecutive key centers, [INTERP_POINTS_PER_SEGMENT] intermediate points
     * are linearly interpolated. Characters not found in the layout are skipped.
     */
    private fun generateRawPoints(keySequence: String): List<Pair<Float, Float>> {
        val centers = keySequence.mapNotNull { char ->
            layout.getKeyCenterOf(char)
        }
        if (centers.isEmpty()) return emptyList()
        if (centers.size == 1) return listOf(centers.first())

        val points = mutableListOf<Pair<Float, Float>>()
        points.add(centers.first())

        for (i in 0 until centers.size - 1) {
            val (x1, y1) = centers[i]
            val (x2, y2) = centers[i + 1]
            for (j in 1..INTERP_POINTS_PER_SEGMENT) {
                val t = j.toFloat() / (INTERP_POINTS_PER_SEGMENT + 1).toFloat()
                val ix = x1 + (x2 - x1) * t
                val iy = y1 + (y2 - y1) * t
                points.add(Pair(ix, iy))
            }
            points.add(centers[i + 1])
        }
        return points
    }

    /**
     * Generate a normalized template for the given key sequence.
     * Steps: generateRawPoints -> create a synthetic [GlideTrace] -> [TraceNormalizer.process].
     * The result is cached for subsequent lookups.
     */
    fun generateTemplate(keySequence: String): List<Pair<Float, Float>> {
        templateCache[keySequence]?.let { return it }

        val rawPoints = generateRawPoints(keySequence)
        if (rawPoints.isEmpty()) return emptyList()

        // Build a synthetic GlideTrace from the raw points
        val glidePoints = rawPoints.mapIndexed { index, (x, y) ->
            GlidePoint(x, y, timestamp = index.toLong())
        }
        val trace = GlideTrace(glidePoints)
        val normalized = TraceNormalizer.process(trace)

        templateCache[keySequence] = normalized
        return normalized
    }

    /**
     * Preload templates for all Bopomofo syllables defined in [BopomofoTable].
     */
    fun preloadBopomofoTemplates() {
        for (syllable in BopomofoTable.ALL_SYLLABLES) {
            val qwertyKeys = BopomofoTable.getQwertySequence(syllable)
            if (qwertyKeys.isNotEmpty()) {
                generateTemplate(qwertyKeys)
            }
        }
    }

    /**
     * Preload templates for a list of English words.
     * Each word is treated as a QWERTY key sequence (lowercased).
     */
    fun preloadEnglishTemplates(words: List<String>) {
        for (word in words) {
            generateTemplate(word.lowercase())
        }
    }

    /**
     * Retrieve a previously generated/cached template for the given key sequence.
     * Returns null if the template has not been generated yet.
     */
    fun getTemplate(keySequence: String): List<Pair<Float, Float>>? {
        return templateCache[keySequence]
    }

    /**
     * Return all currently cached templates.
     */
    fun getAllTemplates(): Map<String, List<Pair<Float, Float>>> {
        return templateCache.toMap()
    }

    /**
     * Return cached templates only for the given words.
     * Used for bucket-filtered decode in English glide typing.
     */
    fun getTemplatesForWords(words: List<String>): Map<String, List<Pair<Float, Float>>> {
        val result = mutableMapOf<String, List<Pair<Float, Float>>>()
        for (word in words) {
            val key = word.lowercase()
            templateCache[key]?.let { result[key] = it }
        }
        return result
    }

    /**
     * Clear the template cache.
     */
    fun clearCache() {
        templateCache.clear()
    }
}
