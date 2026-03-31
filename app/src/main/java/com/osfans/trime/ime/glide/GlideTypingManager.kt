/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.glide

import android.view.KeyCharacterMap
import android.widget.FrameLayout
import com.osfans.trime.core.RimeKeyMapping
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyBehavior
import com.osfans.trime.ime.keyboard.Keyboard
import com.osfans.trime.ime.keyboard.KeyboardView
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Orchestrator for glide typing.
 *
 * For Bopomofo: instead of complex template matching, we directly track which keys
 * the finger crosses (in order, deduplicated) and send them to RIME one by one.
 * RIME handles phonological validation, syllable composition, and candidate ranking.
 * This also supports abbreviated input (e.g., ㄋㄏ → 你好) natively.
 */
class GlideTypingManager(
    private val service: TrimeInputMethodService,
    private val rime: RimeSession,
) {
    private var interceptor: GlideTypingInterceptor? = null
    private var overlayView: GlideOverlayView? = null
    private var currentKeyboard: Keyboard? = null
    private var attachedView: KeyboardView? = null

    // ---- English glide typing (SHARK2 pipeline) ----
    private var englishLayout: QwertyLayout? = null
    private var englishSokgraph: GlideSokgraph? = null
    private var englishDecoder: GlideDecoder? = null
    private val englishTemplatesReady = AtomicBoolean(false)

    // ---- auto-space ----
    var lastCommitWasGlide = false
        private set

    // ---- public API ----

    fun attach(keyboardView: KeyboardView, keyboard: Keyboard) {
        detach()

        if (!AppPrefs.defaultInstance().keyboard.glideTypingEnabled.getValue()) {
            Timber.d("Glide typing disabled in settings")
            return
        }

        currentKeyboard = keyboard

        // Create the overlay for drawing the trail
        val overlay = GlideOverlayView(keyboardView.context)
        overlayView = overlay

        keyboardView.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // Create interceptor
        val glideInterceptor = GlideTypingInterceptor(keyboard, glideListener)
        interceptor = glideInterceptor

        keyboardView.glideInterceptor = glideInterceptor
        attachedView = keyboardView

        // Initialize English glide pipeline
        val layout = QwertyLayout.buildFromKeys(keyboard.keys)
        val alphabeticKeyCount = layout.keys.count { it.char.isLetter() }
        if (alphabeticKeyCount >= 26) {
            englishLayout = layout
            val skg = GlideSokgraph(layout)
            englishSokgraph = skg
            englishDecoder = GlideDecoder(layout, skg, isBopomofoMode = false)

            // Preload word list + templates on background thread
            englishTemplatesReady.set(false)
            val appContext = keyboardView.context.applicationContext
            thread(name = "glide-preload") {
                EnglishWordList.load(appContext)
                val words = EnglishWordList.getAllWords()
                skg.preloadEnglishTemplates(words)
                englishTemplatesReady.set(true)
                Timber.d("English glide templates preloaded: ${words.size} words")
            }
        } else {
            Timber.d("Keyboard has < 26 alphabetic keys ($alphabeticKeyCount), English glide disabled")
        }

        Timber.d("GlideTypingManager attached (key-sequence mode)")
    }

    fun detach() {
        attachedView?.let { view ->
            view.glideInterceptor = null
            overlayView?.let { view.removeView(it) }
        }
        interceptor = null
        overlayView = null
        currentKeyboard = null
        attachedView = null

        englishLayout = null
        englishSokgraph = null
        englishDecoder = null
        englishTemplatesReady.set(false)
        lastCommitWasGlide = false
    }

    // ---- glide listener ----

    private val glideListener = object : GlideTypingInterceptor.Listener {
        override fun onGlideStart(trace: GlideTrace) {
            // Set glide mode based on current RIME ascii mode
            val isAscii = rime.run { statusCached }.isAsciiMode
            interceptor?.glideMode = if (isAscii && englishTemplatesReady.get()) {
                GlideTypingInterceptor.GlideMode.TRACE
            } else {
                GlideTypingInterceptor.GlideMode.DWELL
            }
            overlayView?.updateTrail(trace.points)
        }

        override fun onGlideUpdate(trace: GlideTrace) {
            overlayView?.updateTrail(trace.points)
        }

        override fun onGlideComplete(trace: GlideTrace) {
            overlayView?.clearTrail()
            // Dispatch based on mode set at gesture start (not re-checking isAsciiMode)
            if (interceptor?.glideMode == GlideTypingInterceptor.GlideMode.TRACE) {
                decodeAndCommitEnglish(trace)
            } else {
                sendCrossedKeys()
            }
        }

        override fun onGlideCancelled() {
            overlayView?.clearTrail()
        }
    }

    // ---- send crossed keys to RIME ----

    private fun sendCrossedKeys() {
        val inter = interceptor ?: return
        val kb = currentKeyboard ?: return

        // Get the ordered key indices the finger crossed
        val keyIndices = inter.getOrderedKeySequence()
        if (keyIndices.isEmpty()) return

        // Convert key indices to KeyActions and send via processKey
        val actions = mutableListOf<Pair<Int, Int>>()  // (code, modifier)

        for (idx in keyIndices) {
            if (idx < 0 || idx >= kb.keys.size) continue
            val key = kb.keys[idx]
            val clickAction = key.keyActions[KeyBehavior.CLICK] ?: continue
            val code = clickAction.code
            val modifier = clickAction.modifier
            // Only include printing keys (skip function keys, modifiers, etc.)
            if (code <= 0 || code == android.view.KeyEvent.KEYCODE_FUNCTION) continue
            actions.add(code to modifier)
        }

        if (actions.isEmpty()) return

        // Debug log
        val vkm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val debugStr = actions.map { (code, _) ->
            val label = vkm.getDisplayLabel(code)
            if (label.code > 0) label else '?'
        }.joinToString("")
        Timber.d("Glide intentional keys: $debugStr (${actions.size} keys, codes=${actions.map { it.first }})")

        // Convert Android keycodes to RIME key values, then send
        // Android KEYCODE_S (47) → RIME 's' (115)
        service.postRimeJob {
            for ((androidCode, modifier) in actions) {
                val rimeValue = RimeKeyMapping.keyCodeToVal(androidCode)
                if (rimeValue != RimeKeyMapping.RimeKey_VoidSymbol) {
                    Timber.d("Glide sendKey: android=$androidCode → rime=$rimeValue (${rimeValue.toChar()})")
                    processKey(rimeValue, modifier.toUInt())
                }
            }
        }
    }

    // ---- English glide decode + commit ----

    private fun decodeAndCommitEnglish(trace: GlideTrace) {
        val inter = interceptor ?: return
        val decoder = englishDecoder ?: return
        val skg = englishSokgraph ?: return

        // In TRACE mode, sendCrossedKeys() would produce garbage (no dwell filtering
        // means intentionalKeys is empty, only start+end). So we silently return on
        // failure rather than falling back to sendCrossedKeys().

        val (firstChar, lastChar) = inter.getFirstLastChars() ?: run {
            Timber.w("English glide: could not determine first/last chars")
            return
        }

        val bucketWords = EnglishWordList.getWordsForBucket(firstChar, lastChar)
        if (bucketWords.isEmpty()) {
            Timber.d("English glide: empty bucket for ($firstChar, $lastChar)")
            return
        }

        val filteredTemplates = skg.getTemplatesForWords(bucketWords)
        if (filteredTemplates.isEmpty()) {
            Timber.d("English glide: no templates for bucket ($firstChar, $lastChar)")
            return
        }

        val candidates = decoder.decodeWithTemplates(
            trace = trace,
            templates = filteredTemplates,
            topN = 5,
            frequencyRankProvider = { EnglishWordList.getRank(it) },
        )

        if (candidates.isEmpty()) {
            Timber.d("English glide: no candidates matched")
            return
        }

        val bestWord = candidates[0].displayText
        Timber.d("English glide: best='$bestWord' (score=${candidates[0].score}), ${candidates.size} candidates")

        // Commit best candidate with trailing space
        service.commitText(bestWord + " ")
        lastCommitWasGlide = true

        // TODO: inject candidates[1..4] into RIME candidate bar (stretch goal, see spec Section 7)
    }

    // ---- auto-space punctuation hook ----

    private val punctuationChars = setOf('.', ',', ';', ':', '!', '?', '\'', '"', ')', '-', ']', '}')

    /**
     * Called by CommonKeyboardActionListener before non-glide key commits.
     * If the last commit was a glide word (with trailing space) and the next
     * input is punctuation, delete the trailing space first.
     *
     * Note: this only covers punctuation via action.commit path.
     * Punctuation entered via onText or RIME processKey will not trigger
     * auto-space removal. Acceptable for MVP.
     */
    fun onKeyAction(commitText: String?) {
        if (lastCommitWasGlide && !commitText.isNullOrEmpty()) {
            val firstChar = commitText[0]
            if (firstChar in punctuationChars) {
                // Delete the trailing space we added after the glide word
                service.currentInputConnection?.deleteSurroundingText(1, 0)
                Timber.d("English glide: removed trailing space before punctuation '$firstChar'")
            }
        }
        lastCommitWasGlide = false
    }
}
