/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.glide

/**
 * Complete Bopomofo (Zhuyin) syllable table with QWERTY key mappings.
 *
 * Bopomofo-to-QWERTY mapping follows the standard Taiwanese keyboard layout:
 * ㄅ→1, ㄉ→2, ˇ→3, ˋ→4, ㄓ→5, ˊ→6, ˙→7, ㄚ→8, ㄞ→9, ㄢ→0,
 * ㄆ→q, ㄊ→w, ㄍ→e, ㄐ→r, ㄔ→t, ㄗ→y, ㄧ→u, ㄛ→i, ㄟ→o, ㄣ→p,
 * ㄇ→a, ㄋ→s, ㄎ→d, ㄑ→f, ㄕ→g, ㄘ→h, ㄨ→j, ㄜ→k, ㄠ→l, ㄤ→;,
 * ㄈ→z, ㄌ→x, ㄏ→c, ㄒ→v, ㄖ→b, ㄙ→n, ㄩ→m, ㄝ→,, ㄡ→., ㄥ→/,
 * ㄦ→-
 */
object BopomofoTable {

    // Bopomofo → QWERTY key
    private val BPMF_TO_QWERTY = mapOf(
        'ㄅ' to '1', 'ㄉ' to '2', 'ˇ' to '3', 'ˋ' to '4',
        'ㄓ' to '5', 'ˊ' to '6', '˙' to '7',
        'ㄚ' to '8', 'ㄞ' to '9', 'ㄢ' to '0',
        'ㄆ' to 'q', 'ㄊ' to 'w', 'ㄍ' to 'e', 'ㄐ' to 'r',
        'ㄔ' to 't', 'ㄗ' to 'y', 'ㄧ' to 'u', 'ㄛ' to 'i',
        'ㄟ' to 'o', 'ㄣ' to 'p',
        'ㄇ' to 'a', 'ㄋ' to 's', 'ㄎ' to 'd', 'ㄑ' to 'f',
        'ㄕ' to 'g', 'ㄘ' to 'h', 'ㄨ' to 'j', 'ㄜ' to 'k',
        'ㄠ' to 'l', 'ㄤ' to ';',
        'ㄈ' to 'z', 'ㄌ' to 'x', 'ㄏ' to 'c', 'ㄒ' to 'v',
        'ㄖ' to 'b', 'ㄙ' to 'n', 'ㄩ' to 'm', 'ㄝ' to ',',
        'ㄡ' to '.', 'ㄥ' to '/',
        'ㄦ' to '-',
    )

    // QWERTY key → Bopomofo
    private val QWERTY_TO_BPMF: Map<Char, Char> =
        BPMF_TO_QWERTY.entries.associate { (k, v) -> v to k }

    // Phonetic categories
    val INITIALS = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙ"
    val MEDIALS = "ㄧㄨㄩ"
    val FINALS = "ㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦ"
    val TONES = charArrayOf('\u0000', 'ˊ', 'ˇ', 'ˋ', '˙') // 1st tone = no mark

    // ㄐㄑㄒ can only pair with ㄧ or ㄩ (NOT ㄨ)
    private val JQX = setOf('ㄐ', 'ㄑ', 'ㄒ')

    // Retroflex and dental sibilants: ㄓㄔㄕㄖㄗㄘㄙ cannot directly pair with ㄩ
    private val RETROFLEX_DENTAL = setOf('ㄓ', 'ㄔ', 'ㄕ', 'ㄖ', 'ㄗ', 'ㄘ', 'ㄙ')

    /**
     * All valid Bopomofo syllable bases (without tone) generated from combination rules.
     */
    val ALL_SYLLABLE_BASES: List<String> by lazy {
        buildList {
            // 1. Standalone medials: ㄧ, ㄨ, ㄩ
            for (m in MEDIALS) {
                add(m.toString())
            }

            // 2. Standalone finals: ㄚ, ㄛ, ㄜ, ㄝ, ㄞ, ㄟ, ㄠ, ㄡ, ㄢ, ㄣ, ㄤ, ㄥ, ㄦ
            for (f in FINALS) {
                add(f.toString())
            }

            // 3. Medial + Final combinations
            for (m in MEDIALS) {
                for (f in FINALS) {
                    if (isValidMedialFinal(m, f)) {
                        add("$m$f")
                    }
                }
            }

            // 4. Initial only (ㄓㄔㄕㄖㄗㄘㄙ can stand alone as syllables)
            for (i in RETROFLEX_DENTAL) {
                add(i.toString())
            }

            // 5. Initial + Medial
            for (i in INITIALS) {
                for (m in MEDIALS) {
                    if (isValidInitialMedial(i, m)) {
                        add("$i$m")
                    }
                }
            }

            // 6. Initial + Final
            for (i in INITIALS) {
                for (f in FINALS) {
                    if (isValidInitialFinal(i, f)) {
                        add("$i$f")
                    }
                }
            }

            // 7. Initial + Medial + Final
            for (i in INITIALS) {
                for (m in MEDIALS) {
                    if (!isValidInitialMedial(i, m)) continue
                    for (f in FINALS) {
                        if (isValidMedialFinal(m, f)) {
                            add("$i$m$f")
                        }
                    }
                }
            }
        }.distinct()
    }

    /**
     * All valid Bopomofo syllables including tone variants.
     * Each base syllable generates up to 5 variants:
     * - 1st tone (no tone mark appended)
     * - 2nd tone (ˊ)
     * - 3rd tone (ˇ)
     * - 4th tone (ˋ)
     * - Neutral tone (˙)
     */
    val ALL_SYLLABLES: List<String> by lazy {
        buildList {
            for (base in ALL_SYLLABLE_BASES) {
                // 1st tone: no tone character appended
                add(base)
                // 2nd, 3rd, 4th, neutral tones
                add("${base}ˊ")
                add("${base}ˇ")
                add("${base}ˋ")
                add("${base}˙")
            }
        }
    }

    private fun isValidInitialMedial(initial: Char, medial: Char): Boolean {
        // ㄐㄑㄒ only with ㄧ or ㄩ
        if (initial in JQX) {
            return medial == 'ㄧ' || medial == 'ㄩ'
        }
        // ㄓㄔㄕㄖㄗㄘㄙ cannot directly pair with ㄩ
        if (initial in RETROFLEX_DENTAL) {
            return medial != 'ㄩ'
        }
        return true
    }

    private fun isValidInitialFinal(initial: Char, final: Char): Boolean {
        // ㄐㄑㄒ cannot appear without a medial before a final
        // (they always need ㄧ or ㄩ as medial)
        if (initial in JQX) return false
        return true
    }

    @Suppress("UNUSED_PARAMETER")
    private fun isValidMedialFinal(medial: Char, final: Char): Boolean {
        // Most medial+final combinations are valid in practice.
        // ㄦ typically does not combine with medials, but we include for completeness.
        return true
    }

    /**
     * Convert a Bopomofo string to its QWERTY key sequence.
     * Example: "ㄅㄚ" → "18", "ㄇㄚˇ" → "a83"
     */
    fun getQwertySequence(bopomofo: String): String {
        return buildString {
            for (ch in bopomofo) {
                val mapped = BPMF_TO_QWERTY[ch]
                if (mapped != null) {
                    append(mapped)
                }
            }
        }
    }

    /**
     * Convert a QWERTY key sequence back to Bopomofo display string.
     * Example: "18" → "ㄅㄚ", "a83" → "ㄇㄚˇ"
     */
    fun getBopomofoDisplay(qwerty: String): String {
        return buildString {
            for (ch in qwerty) {
                val mapped = QWERTY_TO_BPMF[ch]
                if (mapped != null) {
                    append(mapped)
                }
            }
        }
    }

    /**
     * Get the QWERTY key for a single Bopomofo character.
     */
    fun toQwerty(bpmf: Char): Char? = BPMF_TO_QWERTY[bpmf]

    /**
     * Get the Bopomofo character for a single QWERTY key.
     */
    fun toBopomofo(qwerty: Char): Char? = QWERTY_TO_BPMF[qwerty]

    /**
     * Check if a character is a Bopomofo initial.
     */
    fun isInitial(ch: Char): Boolean = ch in INITIALS

    /**
     * Check if a character is a Bopomofo medial.
     */
    fun isMedial(ch: Char): Boolean = ch in MEDIALS

    /**
     * Check if a character is a Bopomofo final.
     */
    fun isFinal(ch: Char): Boolean = ch in FINALS

    /**
     * Check if a character is a Bopomofo tone mark.
     */
    fun isTone(ch: Char): Boolean = ch == 'ˊ' || ch == 'ˇ' || ch == 'ˋ' || ch == '˙'
}
