/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.glide

/**
 * A single glide-typing candidate with its matched key sequence,
 * human-readable display text, and match score (lower is better).
 */
data class GlideCandidate(
    val keySequence: String,
    val displayText: String,
    val score: Float,
)

/**
 * Top-level glide decoder that matches user traces against precomputed
 * sokgraph templates and returns ranked candidates.
 *
 * Supports two modes:
 * - **Bopomofo mode**: candidates are Bopomofo syllables; display text shows
 *   Bopomofo symbols. Multi-syllable beam search is available via [decodeMultiSyllable].
 * - **English mode**: candidates are English words; display text is the key sequence itself.
 */
class GlideDecoder(
    private val layout: QwertyLayout,
    private val sokgraph: GlideSokgraph,
    private val isBopomofoMode: Boolean,
) {
    /**
     * Decode a single gesture [trace] against all cached sokgraph templates.
     *
     * Steps:
     * 1. Normalize the user's trace via [TraceNormalizer.process].
     * 2. Score against every template in the sokgraph cache.
     * 3. Sort by score (ascending, lower = better).
     * 4. Return the top [topN] candidates.
     */
    fun decode(trace: GlideTrace, topN: Int = 10): List<GlideCandidate> {
        if (trace.isEmpty) return emptyList()

        val normalizedGesture = TraceNormalizer.process(trace)
        if (normalizedGesture.isEmpty()) return emptyList()

        val allTemplates = sokgraph.getAllTemplates()
        if (allTemplates.isEmpty()) return emptyList()

        val scored = allTemplates.mapNotNull { (keySequence, template) ->
            if (template.isEmpty()) return@mapNotNull null

            val score = GlideMatchScorer.combinedScore(
                gesture = normalizedGesture,
                template = template,
                trace = trace,
                keySequence = keySequence,
                layout = layout,
            )

            if (score == Float.MAX_VALUE) return@mapNotNull null

            val displayText = if (isBopomofoMode) {
                BopomofoTable.getBopomofoDisplay(keySequence)
            } else {
                keySequence
            }

            GlideCandidate(
                keySequence = keySequence,
                displayText = displayText,
                score = score,
            )
        }

        return scored.sortedBy { it.score }.take(topN)
    }

    /**
     * Decode a gesture trace as a multi-syllable Bopomofo sequence using beam search.
     *
     * The algorithm:
     * 1. Start with the full trace.
     * 2. For each beam entry, try every Bopomofo syllable template as a prefix match:
     *    - Proportionally split the remaining trace based on the syllable's key count
     *      relative to the total remaining trace points.
     *    - Score the prefix portion of the trace against the syllable template.
     *    - Extend the beam with the new syllable appended.
     * 3. Keep the top [beamWidth] partial hypotheses at each step.
     * 4. Combine single-syllable results from [decode] with multi-syllable results.
     * 5. Sort by total score and return top [topN].
     */
    fun decodeMultiSyllable(
        trace: GlideTrace,
        topN: Int = 10,
        beamWidth: Int = 15,
    ): List<GlideCandidate> {
        if (!isBopomofoMode) return decode(trace, topN)
        if (trace.isEmpty) return emptyList()

        val singleResults = decode(trace, topN)

        // Beam search state: (consumed point count, accumulated key sequence, accumulated score)
        data class BeamEntry(
            val consumedPoints: Int,
            val keySequence: String,
            val totalScore: Float,
            val syllableCount: Int,
        )

        val totalPoints = trace.points.size
        var beam = mutableListOf(BeamEntry(0, "", 0f, 0))
        val completedCandidates = mutableListOf<GlideCandidate>()

        val allSyllables = BopomofoTable.ALL_SYLLABLES
        val maxIterations = 6 // max number of syllables in a single glide

        for (iteration in 0 until maxIterations) {
            val nextBeam = mutableListOf<BeamEntry>()

            for (entry in beam) {
                if (entry.consumedPoints >= totalPoints) {
                    // This hypothesis has consumed the entire trace
                    if (entry.syllableCount > 1) {
                        val displayText = BopomofoTable.getBopomofoDisplay(entry.keySequence)
                        completedCandidates.add(
                            GlideCandidate(
                                keySequence = entry.keySequence,
                                displayText = displayText,
                                score = entry.totalScore / entry.syllableCount,
                            )
                        )
                    }
                    continue
                }

                val remainingPoints = totalPoints - entry.consumedPoints

                for (syllable in allSyllables) {
                    val syllableQwerty = BopomofoTable.getQwertySequence(syllable)
                    val syllableKeyCount = syllableQwerty.length
                    if (syllableKeyCount == 0) continue

                    val template = sokgraph.getTemplate(syllableQwerty) ?: continue
                    if (template.isEmpty()) continue

                    // Proportionally allocate trace points for this syllable
                    val proportion = syllableKeyCount.toFloat() / maxOf(syllableKeyCount, remainingPoints)
                    val pointsForSyllable = maxOf(
                        2,
                        (remainingPoints * proportion).toInt()
                            .coerceAtMost(remainingPoints)
                    )

                    val subTraceEnd = minOf(entry.consumedPoints + pointsForSyllable, totalPoints)
                    if (subTraceEnd <= entry.consumedPoints) continue

                    val subTrace = trace.subTrace(entry.consumedPoints, subTraceEnd)
                    if (subTrace.isEmpty) continue

                    val normalizedSubGesture = TraceNormalizer.process(subTrace)
                    if (normalizedSubGesture.isEmpty()) continue

                    val score = GlideMatchScorer.combinedScore(
                        gesture = normalizedSubGesture,
                        template = template,
                        trace = subTrace,
                        keySequence = syllableQwerty,
                        layout = layout,
                    )

                    if (score == Float.MAX_VALUE) continue

                    nextBeam.add(
                        BeamEntry(
                            consumedPoints = subTraceEnd,
                            keySequence = entry.keySequence + syllableQwerty,
                            totalScore = entry.totalScore + score,
                            syllableCount = entry.syllableCount + 1,
                        )
                    )
                }
            }

            if (nextBeam.isEmpty()) break

            // Prune to beam width
            beam = nextBeam
                .sortedBy { it.totalScore / maxOf(1, it.syllableCount) }
                .take(beamWidth)
                .toMutableList()

            // Add completed hypotheses (those that consumed all or nearly all points)
            for (entry in beam) {
                val remaining = totalPoints - entry.consumedPoints
                if (remaining <= 2 && entry.syllableCount > 1) {
                    val displayText = BopomofoTable.getBopomofoDisplay(entry.keySequence)
                    completedCandidates.add(
                        GlideCandidate(
                            keySequence = entry.keySequence,
                            displayText = displayText,
                            score = entry.totalScore / entry.syllableCount,
                        )
                    )
                }
            }
        }

        // Merge single-syllable and multi-syllable results, sort by score
        val allCandidates = (singleResults + completedCandidates)
            .distinctBy { it.keySequence }
            .sortedBy { it.score }
            .take(topN)

        return allCandidates
    }

    /**
     * Decode a gesture against a pre-filtered set of templates (for bucket-filtered English decode).
     *
     * Unlike [decode], this method accepts an external template map instead of using the
     * sokgraph's full cache. Normalization of the trace is done internally.
     *
     * @param trace The raw user gesture trace.
     * @param templates Pre-filtered templates, keyed by word (lowercase).
     * @param topN Maximum number of candidates to return.
     * @param frequencyRankProvider Optional function to get frequency rank for a word. Returns -1 if unknown.
     */
    fun decodeWithTemplates(
        trace: GlideTrace,
        templates: Map<String, List<Pair<Float, Float>>>,
        topN: Int = 5,
        frequencyRankProvider: ((String) -> Int)? = null,
    ): List<GlideCandidate> {
        if (trace.isEmpty) return emptyList()

        val normalizedGesture = TraceNormalizer.process(trace)
        if (normalizedGesture.isEmpty()) return emptyList()
        if (templates.isEmpty()) return emptyList()

        val scored = templates.mapNotNull { (keySequence, template) ->
            if (template.isEmpty()) return@mapNotNull null

            val score = if (frequencyRankProvider != null) {
                val rank = frequencyRankProvider(keySequence)
                GlideMatchScorer.combinedScoreWithFrequency(
                    gesture = normalizedGesture,
                    template = template,
                    trace = trace,
                    keySequence = keySequence,
                    layout = layout,
                    frequencyRank = rank,
                )
            } else {
                GlideMatchScorer.combinedScore(
                    gesture = normalizedGesture,
                    template = template,
                    trace = trace,
                    keySequence = keySequence,
                    layout = layout,
                )
            }

            if (score == Float.MAX_VALUE) return@mapNotNull null

            GlideCandidate(
                keySequence = keySequence,
                displayText = keySequence, // English mode: display the word itself
                score = score,
            )
        }

        return scored.sortedBy { it.score }.take(topN)
    }
}
