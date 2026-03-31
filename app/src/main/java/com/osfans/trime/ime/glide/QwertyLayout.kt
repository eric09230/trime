/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.glide

data class KeyPosition(
    val char: Char,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
) {
    val left get() = centerX - width / 2f
    val right get() = centerX + width / 2f
    val top get() = centerY - height / 2f
    val bottom get() = centerY + height / 2f

    fun contains(x: Float, y: Float): Boolean =
        x in left..right && y in top..bottom
}

class QwertyLayout(private val keyMap: Map<Char, KeyPosition>) {

    val keys: Collection<KeyPosition> get() = keyMap.values

    fun getKeyAt(x: Float, y: Float): KeyPosition? {
        return keyMap.values.firstOrNull { it.contains(x, y) }
    }

    fun getKeyCenterOf(char: Char): Pair<Float, Float>? {
        val key = keyMap[char.lowercaseChar()] ?: keyMap[char] ?: return null
        return key.centerX to key.centerY
    }

    operator fun get(char: Char): KeyPosition? = keyMap[char.lowercaseChar()] ?: keyMap[char]

    companion object {
        private val ROWS_4 = arrayOf(
            "1234567890",
            "qwertyuiop",
            "asdfghjkl;",
            "zxcvbnm,./",
        )

        private val ROWS_3 = arrayOf(
            "qwertyuiop",
            "asdfghjkl",
            "zxcvbnm",
        )

        /**
         * Build a 4-row layout (used for Bopomofo / Zhuyin keyboards).
         * Row widths: edge keys = 11%, inner keys = 9.75%.
         * Total keyboard height is normalized to 1.0, each row height = 0.25.
         */
        fun buildDefault4Row(): QwertyLayout {
            val map = mutableMapOf<Char, KeyPosition>()
            val rowHeight = 0.25f
            for ((rowIndex, row) in ROWS_4.withIndex()) {
                val centerY = rowHeight * rowIndex + rowHeight / 2f
                // Distribute 10 keys per row: edge keys 11%, inner 9.75%
                // 2 * 11% + 8 * 9.75% = 22% + 78% = 100%
                val edgeWidth = 0.11f
                val innerWidth = 0.0975f
                var x = 0f
                for ((colIndex, ch) in row.withIndex()) {
                    val w = if (colIndex == 0 || colIndex == row.length - 1) edgeWidth else innerWidth
                    val cx = x + w / 2f
                    map[ch] = KeyPosition(ch, cx, centerY, w, rowHeight)
                    x += w
                }
            }
            return QwertyLayout(map)
        }

        /**
         * Build a standard 3-row QWERTY layout (English).
         * Row 1: 10 keys, width 10%, no offset.
         * Row 2: 9 keys, width 10%, offset 5%.
         * Row 3: 7 keys, width 10%, offset 15%.
         * Total keyboard height is normalized to 1.0, each row height = 1/3.
         */
        fun buildDefault3Row(): QwertyLayout {
            val map = mutableMapOf<Char, KeyPosition>()
            val rowHeight = 1f / 3f
            val keyWidth = 0.10f
            val offsets = floatArrayOf(0f, 0.05f, 0.15f)

            for ((rowIndex, row) in ROWS_3.withIndex()) {
                val centerY = rowHeight * rowIndex + rowHeight / 2f
                val offsetX = offsets[rowIndex]
                for ((colIndex, ch) in row.withIndex()) {
                    val cx = offsetX + keyWidth * colIndex + keyWidth / 2f
                    map[ch] = KeyPosition(ch, cx, centerY, keyWidth, rowHeight)
                }
            }
            return QwertyLayout(map)
        }

        /**
         * Build a layout from Trime [com.osfans.trime.ime.keyboard.Key] objects at runtime.
         * Reads x, y, width, height directly.
         * Extracts the QWERTY character by using KeyAction's display label
         * (which maps Android keycodes back to printable characters).
         */
        fun buildFromKeys(keys: List<com.osfans.trime.ime.keyboard.Key>): QwertyLayout {
            val map = mutableMapOf<Char, KeyPosition>()
            for (key in keys) {
                val x = key.x.toFloat()
                val y = key.y.toFloat()
                val width = key.width.toFloat()
                val height = key.height.toFloat()

                val centerX = x + width / 2f
                val centerY = y + height / 2f

                // Get the CLICK action and extract the actual character it sends
                val clickAction = key.keyActions[com.osfans.trime.ime.keyboard.KeyBehavior.CLICK]
                    ?: continue
                val code = clickAction.code

                // Use getDisplayLabel to reliably convert keycode → character
                val ch: Char = try {
                    val label = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
                        .getDisplayLabel(code)
                    if (label.code > 0) label else continue
                } catch (_: Exception) {
                    continue
                }

                val lower = ch.lowercaseChar()
                if (lower.code in 32..126) {
                    map[lower] = KeyPosition(lower, centerX, centerY, width, height)
                }
            }
            return QwertyLayout(map)
        }
    }
}
