/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.glide

import android.content.Context
import timber.log.Timber

/**
 * Lazy-loaded English word list with bucket indexing by (firstChar, lastChar).
 *
 * Words are loaded from assets/english_10k.txt on first access.
 * The bucket index reduces template matching candidates from ~10K to ~200-500.
 */
object EnglishWordList {
    private var words: List<String> = emptyList()
    private var bucketIndex: Map<Pair<Char, Char>, List<String>> = emptyMap()
    private var wordRanks: Map<String, Int> = emptyMap()
    @Volatile
    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        try {
            val allWords = context.assets.open("english_10k.txt")
                .bufferedReader()
                .useLines { lines ->
                    lines
                        .map { it.trim().lowercase() }
                        .filter { it.length >= 3 && it.all(Char::isLetter) }
                        .toList()
                }

            words = allWords
            wordRanks = allWords.withIndex().associate { (i, w) -> w to i }

            // Build bucket index keyed by (first letter, last letter)
            bucketIndex = allWords.groupBy { it.first() to it.last() }

            loaded = true
            Timber.d("EnglishWordList loaded: ${words.size} words, ${bucketIndex.size} buckets")
        } catch (e: Exception) {
            Timber.e(e, "Failed to load English word list")
        }
    }

    fun getAllWords(): List<String> = words

    fun getWordsForBucket(first: Char, last: Char): List<String> =
        bucketIndex[first.lowercaseChar() to last.lowercaseChar()] ?: emptyList()

    /** Returns the frequency rank of a word (0 = most common). -1 if not found. */
    fun getRank(word: String): Int = wordRanks[word.lowercase()] ?: -1

    fun isLoaded(): Boolean = loaded
}
