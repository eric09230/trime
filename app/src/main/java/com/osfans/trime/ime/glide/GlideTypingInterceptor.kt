/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.glide

import android.view.KeyCharacterMap
import android.view.MotionEvent
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.keyboard.KeyBehavior
import com.osfans.trime.ime.keyboard.Keyboard
import kotlin.math.hypot

/**
 * Intercepts touch events on KeyboardView and detects glide typing gestures.
 *
 * Key detection logic:
 * - The START key (where the finger first touches) is always included.
 * - The END key (where the finger lifts) is always included.
 * - Intermediate keys are only included when the finger **dwells** on them
 *   (stays on the same key for >= [DWELL_TIME_MS] milliseconds),
 *   indicating the user deliberately targeted that key.
 * - Keys that the finger merely passes through quickly are ignored.
 */
class GlideTypingInterceptor(
    private val keyboard: Keyboard,
    private val listener: Listener,
    private val minKeyCrossings: Int = DEFAULT_MIN_KEY_CROSSINGS,
    private val minGestureLength: Float = DEFAULT_MIN_GESTURE_LENGTH,
) {
    companion object {
        const val DEFAULT_MIN_KEY_CROSSINGS = 2
        const val DEFAULT_MIN_GESTURE_LENGTH = 40f

        /** Default dwell time; overridden by user preference. */
        const val DEFAULT_DWELL_TIME_MS = 120L
    }

    enum class GlideMode {
        /** Bopomofo: dwell-based key detection */
        DWELL,
        /** English: full trace capture, no dwell filtering */
        TRACE,
    }

    private val dwellTimeMs: Long
        get() = AppPrefs.defaultInstance().keyboard.glideDwellTime.getValue().toLong()

    /** Current glide mode. Set by GlideTypingManager per gesture. */
    var glideMode: GlideMode = GlideMode.DWELL

    interface Listener {
        fun onGlideStart(trace: GlideTrace)
        fun onGlideUpdate(trace: GlideTrace)
        fun onGlideComplete(trace: GlideTrace)
        fun onGlideCancelled()
    }

    // ---- internal state ----

    private var isGliding = false
    private var isTracking = false

    private val tracePoints = mutableListOf<GlidePoint>()
    private val crossedKeyIndices = mutableSetOf<Int>()

    // Dwell-based key detection
    private var currentKeyIndex = -1
    private var currentKeyEnteredTime = 0L
    private val intentionalKeys = mutableListOf<Int>()  // ordered, only keys with dwell
    private var startKeyIndex = -1  // always included

    /** Preserved after resetState() for the manager to read. */
    private var lastCompletedKeySequence: List<Int> = emptyList()

    /** Preserved after resetState() for getFirstLastChars(). */
    private var lastStartKeyIndex = -1
    private var lastEndKeyIndex = -1

    private var totalDistance = 0f
    private var lastX = 0f
    private var lastY = 0f

    // ---- public API ----

    fun onInterceptTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetState()
                isTracking = true
                recordPoint(ev)
                // The start key is always intentional
                val keyIdx = findKeyAt(ev.x, ev.y)
                if (keyIdx >= 0) {
                    startKeyIndex = keyIdx
                    currentKeyIndex = keyIdx
                    currentKeyEnteredTime = ev.eventTime
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isTracking) return false
                recordPoint(ev)
                updateDwell(ev)
                if (!isGliding && shouldStartGlide()) {
                    isGliding = true
                    listener.onGlideStart(buildTrace())
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isTracking && !isGliding) {
                    resetState()
                }
            }
        }
        return isGliding
    }

    fun onTouch(ev: MotionEvent): Boolean {
        if (!isGliding) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                recordPoint(ev)
                updateDwell(ev)
                listener.onGlideUpdate(buildTrace())
            }
            MotionEvent.ACTION_UP -> {
                recordPoint(ev)
                updateDwell(ev)

                // Finalize: flush current key if dwelled, then add end key
                flushCurrentKeyIfDwelled(ev.eventTime)
                val endKeyIdx = findKeyAt(ev.x, ev.y)

                // Build final key sequence: start + dwelled intermediates + end
                val result = buildFinalKeySequence(endKeyIdx)

                // Save indices before resetState() clears them
                val savedStartKey = startKeyIndex

                val trace = buildTrace()
                resetState()
                lastCompletedKeySequence = result
                lastStartKeyIndex = savedStartKey
                lastEndKeyIndex = endKeyIdx
                listener.onGlideComplete(trace)
            }
            MotionEvent.ACTION_CANCEL -> {
                resetState()
                listener.onGlideCancelled()
            }
        }
        return true
    }

    val isActive: Boolean get() = isGliding

    /** Returns the intentional key sequence from the last completed glide. */
    fun getOrderedKeySequence(): List<Int> = lastCompletedKeySequence

    /**
     * Returns the characters of the first and last keys crossed during the last completed glide.
     * Uses [lastStartKeyIndex] and [lastEndKeyIndex] which are preserved across resetState().
     * Returns null if either key is non-alphabetic.
     */
    fun getFirstLastChars(): Pair<Char, Char>? {
        val kb = keyboard
        val startIdx = lastStartKeyIndex
        val endIdx = lastEndKeyIndex
        if (startIdx < 0 || endIdx < 0) return null
        if (startIdx >= kb.keys.size || endIdx >= kb.keys.size) return null

        val vkm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)

        fun keyToChar(idx: Int): Char? {
            val key = kb.keys[idx]
            val code = key.keyActions[KeyBehavior.CLICK]?.code ?: return null
            val label = vkm.getDisplayLabel(code)
            val ch = label.lowercaseChar()
            return if (ch.isLetter()) ch else null
        }

        val first = keyToChar(startIdx) ?: return null
        val last = keyToChar(endIdx) ?: return null
        return first to last
    }

    // ---- dwell detection ----

    private fun updateDwell(ev: MotionEvent) {
        val keyIdx = findKeyAt(ev.x, ev.y)

        if (keyIdx != currentKeyIndex) {
            // Finger moved to a different key — check if we dwelled on the previous one
            flushCurrentKeyIfDwelled(ev.eventTime)
            currentKeyIndex = keyIdx
            currentKeyEnteredTime = ev.eventTime
        }
    }

    private fun flushCurrentKeyIfDwelled(now: Long) {
        if (glideMode == GlideMode.TRACE) return // TRACE mode: no dwell filtering
        if (currentKeyIndex >= 0 && currentKeyIndex != startKeyIndex) {
            val dwellTime = now - currentKeyEnteredTime
            if (dwellTime >= dwellTimeMs) {
                if (intentionalKeys.isEmpty() || intentionalKeys.last() != currentKeyIndex) {
                    intentionalKeys.add(currentKeyIndex)
                }
            }
        }
    }

    private fun buildFinalKeySequence(endKeyIdx: Int): List<Int> {
        val result = mutableListOf<Int>()

        // 1. Start key (always included)
        if (startKeyIndex >= 0) {
            result.add(startKeyIndex)
        }

        // 2. Intermediate keys that had dwell (in order)
        for (k in intentionalKeys) {
            if (k != startKeyIndex && (result.isEmpty() || result.last() != k)) {
                result.add(k)
            }
        }

        // 3. End key (always included, if different from last)
        if (endKeyIdx >= 0 && (result.isEmpty() || result.last() != endKeyIdx)) {
            result.add(endKeyIdx)
        }

        return result
    }

    // ---- basic tracking ----

    private fun recordPoint(ev: MotionEvent) {
        val x = ev.x
        val y = ev.y
        tracePoints.add(GlidePoint(x, y, ev.eventTime))

        if (tracePoints.size > 1) {
            totalDistance += hypot(x - lastX, y - lastY)
        }
        lastX = x
        lastY = y

        val keyIndex = findKeyAt(x, y)
        if (keyIndex >= 0) {
            crossedKeyIndices.add(keyIndex)
        }
    }

    private fun findKeyAt(x: Float, y: Float): Int {
        val keys = keyboard.keys
        val ix = x.toInt()
        val iy = y.toInt()
        for (i in keys.indices) {
            val key = keys[i]
            if (ix >= key.x && ix < key.x + key.width &&
                iy >= key.y && iy < key.y + key.height
            ) {
                return i
            }
        }
        return -1
    }

    private fun shouldStartGlide(): Boolean =
        crossedKeyIndices.size >= minKeyCrossings && totalDistance >= minGestureLength

    private fun buildTrace(): GlideTrace = GlideTrace(tracePoints.toList())

    private fun resetState() {
        isGliding = false
        isTracking = false
        tracePoints.clear()
        crossedKeyIndices.clear()
        intentionalKeys.clear()
        currentKeyIndex = -1
        currentKeyEnteredTime = 0L
        startKeyIndex = -1
        totalDistance = 0f
        lastX = 0f
        lastY = 0f
    }
}
