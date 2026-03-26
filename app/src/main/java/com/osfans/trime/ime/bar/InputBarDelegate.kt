/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar

import android.content.Context
import android.os.Build
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ViewAnimator
import android.widget.inline.InlineContentView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.RimeConfig
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.KeyActionManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.bar.ui.AlwaysUi
import com.osfans.trime.ime.bar.ui.CandidateUi
import com.osfans.trime.ime.bar.ui.TabUi
import com.osfans.trime.ime.bar.ui.switches.InlineSwitchEntry
import com.osfans.trime.ime.bar.ui.switches.SwitchesAdapter
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.candidates.compact.CompactCandidateDelegate
import com.osfans.trime.ime.candidates.unrolled.window.FlexboxUnrolledCandidateWindow
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dependency.InputDependencyManager
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.GestureFrame
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.switches.SwitchOptionWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.ClipEditActivity
import com.osfans.trime.util.AppUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class InputBarDelegate : InputBroadcastReceiver {
    private val di = InputDependencyManager.getInstance().di
    private val context: Context by di.instance()
    private val service: TrimeInputMethodService by di.instance()
    private val theme: Theme by di.instance()
    private val windowManager: BoardWindowManager by di.instance()
    private val commonKeyboardActionListener: CommonKeyboardActionListener by di.instance()
    private val candidate: CompactCandidateDelegate by di.instance()
    private val rime: RimeSession by di.instance()
    private var hasSwitches = false

    private val saveOptions by lazy {
        RimeConfig.openConfig("default").use {
            it.getList("switcher/save_options", RimeConfig::getString).toSet()
        }
    }

    private val emojiAction = InlineSwitchEntry.ActionItem("\uD83D\uDE00", "Keyboard_bqrw1")

    private fun insertActionItems(entries: List<InlineSwitchEntry>): List<InlineSwitchEntry> {
        val result = entries.toMutableList()
        // Insert emoji button after the first switch (中/英)
        val insertPos = if (result.isNotEmpty()) 1 else 0
        result.add(insertPos, emojiAction)
        return result
    }

    private fun loadSwitches() {
        rime.launchOnReady { api ->
            val switches = api.currentSchema().switches
            val entries = switches.mapNotNull { InlineSwitchEntry.SwitchItem.fromSwitch(rime, it) }
            val allEntries = insertActionItems(entries)
            service.lifecycleScope.launch {
                hasSwitches = allEntries.isNotEmpty()
                alwaysUi.switchesUi.setSwitches(allEntries)
                evalAlwaysUiState()
            }
        }
    }

    private fun refreshSwitchStates() {
        val switches = rime.run { schemaCached }.switches
        val entries = switches.mapNotNull { InlineSwitchEntry.SwitchItem.fromSwitch(rime, it) }
        val allEntries = insertActionItems(entries)
        hasSwitches = allEntries.isNotEmpty()
        alwaysUi.switchesUi.setSwitches(allEntries)
    }

    val themedHeight = theme.generalStyle.run { candidateViewHeight + commentHeight }

    private val prefs = AppPrefs.defaultInstance()

    private val hideQuickBar by prefs.keyboard.hideInputBar

    private val clipboardSuggestion by prefs.clipboard.clipboardSuggestion

    private val clipboardSuggestionTimeout by prefs.clipboard.clipboardSuggestionTimeout

    private var clipboardTimeoutJob: Job? = null

    private var isClipboardFresh: Boolean = false
    private var isInlineSuggestionPresent: Boolean = false

    @Keep
    private val onClipboardUpdateListener = ClipboardHelper.OnClipboardUpdateListener {
        if (!clipboardSuggestion) return@OnClipboardUpdateListener
        service.lifecycleScope.launch {
            if (it.text.isNullOrEmpty()) {
                isClipboardFresh = false
            } else {
                alwaysUi.clipboardUi.text.text = it.text.take(42)
                isClipboardFresh = true
                launchClipboardTimeoutJob()
            }
            evalAlwaysUiState()
        }
    }

    private fun launchClipboardTimeoutJob() {
        clipboardTimeoutJob?.cancel()
        val timeout = clipboardSuggestionTimeout * 1000L
        if (timeout < 0L) return
        clipboardTimeoutJob = service.lifecycleScope.launch {
            delay(timeout)
            isClipboardFresh = false
            clipboardTimeoutJob = null
            evalAlwaysUiState()
        }
    }

    private fun evalAlwaysUiState() {
        val newState =
            when {
                isClipboardFresh -> AlwaysUi.State.Clipboard
                isInlineSuggestionPresent -> AlwaysUi.State.InlineSuggestion
                hasSwitches -> AlwaysUi.State.Switches
                else -> AlwaysUi.State.Toolbar
            }
        if (newState == alwaysUi.currentState) return
        alwaysUi.updateState(newState)
    }

    private val swipeDownHideKeyboardCallback: ((GestureFrame.SwipeDirection) -> Unit) = { d ->
        if (d == GestureFrame.SwipeDirection.Down) {
            service.requestHideSelf(0)
        }
    }

    private val alwaysUi: AlwaysUi by lazy {
        AlwaysUi(context, theme) { action ->
            if (action != null) {
                commonKeyboardActionListener.listener.onAction(KeyActionManager.getAction(action))
            } else {
                windowManager.attachWindow(SwitchOptionWindow())
            }
        }.apply {
            hideKeyboardButton.apply {
                setOnClickListener { service.requestHideSelf(0) }
                onSwipeListener = swipeDownHideKeyboardCallback
            }
            clipboardUi.suggestionView.apply {
                setOnClickListener {
                    val content = ClipboardHelper.lastBean?.text
                    content?.let { service.commitText(it) }
                    clipboardTimeoutJob?.cancel()
                    clipboardTimeoutJob = null
                    isClipboardFresh = false
                    evalAlwaysUiState()
                }
                setOnLongClickListener {
                    ClipboardHelper.lastBean?.let {
                        AppUtils.launchClipEdit(context, it.id, ClipEditActivity.FROM_CLIPBOARD)
                    }
                    true
                }
            }
            switchesUi.setOnSwitchLongClick {
                windowManager.attachWindow(SwitchOptionWindow())
            }
            switchesUi.setOnSwitchClick({ entry ->
                InputFeedbackManager.keyPressVibrate(switchesUi.root)
                when (entry) {
                    is InlineSwitchEntry.ActionItem -> {
                        if (entry == emojiAction && !KeyboardWindow.isActiveKeyboardLocked) {
                            // Already in emoji/symbol area, toggle back to main keyboard
                            KeyboardWindow.switchToLastLock()
                        } else {
                            commonKeyboardActionListener.listener.onAction(
                                KeyActionManager.getAction(entry.action),
                            )
                        }
                    }
                    is InlineSwitchEntry.SwitchItem -> {
                        val sw = entry.switch
                        // Optimistic UI update
                        val newIndex = if (sw.options.isEmpty()) {
                            1 - entry.enabledIndex
                        } else {
                            (entry.enabledIndex + 1) % sw.states.size
                        }
                        val optimistic = entry.copy(enabledIndex = newIndex)
                        val currentList = switchesUi.root.adapter?.let {
                            (it as? SwitchesAdapter)?.items?.toMutableList()
                        } ?: mutableListOf()
                        val pos = currentList.indexOfFirst {
                            it is InlineSwitchEntry.SwitchItem && it.switch == sw
                        }
                        if (pos >= 0) {
                            currentList[pos] = optimistic
                            switchesUi.setSwitches(currentList)
                        }
                        // Async toggle
                        if (sw.options.isEmpty()) {
                            rime.launchOnReady { api ->
                                val oldValue = api.getRuntimeOption(sw.name)
                                api.setRuntimeOption(sw.name, !oldValue)
                                if (sw.name in saveOptions) {
                                    RimeConfig.openUserConfig("user").use {
                                        it.setBool("var/option/${sw.name}", !oldValue)
                                    }
                                }
                            }
                        } else {
                            rime.launchOnReady { api ->
                                val currentIdx = sw.options.indexOfFirst { api.getRuntimeOption(it) }
                                val safeIdx = if (currentIdx >= 0) currentIdx else 0
                                val newIdx = (safeIdx + 1) % sw.options.size
                                sw.options.forEachIndexed { i, opt ->
                                    api.setRuntimeOption(opt, i == newIdx)
                                    if (opt in saveOptions) {
                                        RimeConfig.openUserConfig("user").use {
                                            it.setBool("var/option/$opt", i == newIdx)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            })
        }
    }

    private val candidateUi by lazy {
        CandidateUi(context, candidate.view).apply {
            unrollButton.apply {
                onSwipeListener = swipeDownHideKeyboardCallback
            }
        }
    }

    private val tabUi by lazy {
        TabUi(context, theme)
    }

    private val barStateMachine =
        QuickBarStateMachine.new {
            switchUiByState(it)
        }

    val unrollButtonStateMachine =
        UnrollButtonStateMachine.new {
            when (it) {
                UnrollButtonStateMachine.State.ClickToAttachWindow -> {
                    setUnrollButtonToAttach()
                    setUnrollButtonEnabled(true)
                }
                UnrollButtonStateMachine.State.ClickToDetachWindow -> {
                    setUnrollButtonToDetach()
                    setUnrollButtonEnabled(true)
                    setUnrollWindowToAttach()
                }
                UnrollButtonStateMachine.State.Hidden -> {
                    setUnrollButtonEnabled(false)
                }
            }
        }

    private fun setUnrollButtonToAttach() {
        candidateUi.unrollButton.setOnClickListener {
            windowManager.attachWindow(FlexboxUnrolledCandidateWindow())
        }
        candidateUi.unrollButton.setIcon(R.drawable.ic_baseline_expand_more_24)
    }

    private fun setUnrollButtonToDetach() {
        candidateUi.unrollButton.setOnClickListener {
            windowManager.attachWindow(KeyboardWindow)
        }
        candidateUi.unrollButton.setIcon(R.drawable.ic_baseline_expand_less_24)
    }

    private fun setUnrollButtonEnabled(enabled: Boolean) {
        candidateUi.unrollButton.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    }

    private fun setUnrollWindowToAttach() {
        unrollButtonStateMachine.getBooleanState(
            UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesHighlighted,
        )?.let {
            if (!it) return@let
            windowManager.attachWindow(FlexboxUnrolledCandidateWindow())
        }
    }

    override fun onCandidateListUpdate(data: RimeMessage.CandidateListMessage.Data) {
        barStateMachine.push(
            QuickBarStateMachine.TransitionEvent.CandidatesUpdated,
            QuickBarStateMachine.BooleanKey.CandidateEmpty to data.candidates.isEmpty(),
        )
    }

    private fun switchUiByState(state: QuickBarStateMachine.State) {
        val index = state.ordinal
        if (view.displayedChild == index) return
        val new = view.getChildAt(index)
        if (new != tabUi.root) {
            tabUi.setBackButtonOnClickListener { }
            tabUi.removeExternal()
        }
        view.displayedChild = index
    }

    val view by lazy {
        ViewAnimator(context).apply {
            visibility =
                if (hideQuickBar) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
            background =
                ColorManager.getDecorDrawable(
                    "candidate_background",
                    "candidate_border_color",
                    dp(theme.generalStyle.candidateBorder),
                    dp(theme.generalStyle.candidateBorderRound),
                )
            add(alwaysUi.root, lParams(matchParent, matchParent))
            add(candidateUi.root, lParams(matchParent, matchParent))
            add(tabUi.root, lParams(matchParent, matchParent))

            evalAlwaysUiState()
            ClipboardHelper.addOnUpdateListener(onClipboardUpdateListener)
            loadSwitches()
        }
    }

    override fun onStartInput(info: EditorInfo) {
        evalAlwaysUiState()
    }

    override fun onWindowAttached(window: BoardWindow) {
        if (window is BoardWindow.BarBoardWindow) {
            window.onCreateBarView()?.let { tabUi.addExternal(it, window.showTitle) }
            tabUi.setBackButtonOnClickListener {
                windowManager.attachWindow(KeyboardWindow)
            }
            barStateMachine.push(QuickBarStateMachine.TransitionEvent.BarBoardWindowAttached)
        }
    }

    override fun onWindowDetached(window: BoardWindow) {
        barStateMachine.push(QuickBarStateMachine.TransitionEvent.WindowDetached)
    }

    private val suggestionSize by lazy {
        Size(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(themedHeight))
    }

    private val directExecutor by lazy {
        Executor { it.run() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) {
            isInlineSuggestionPresent = false
            return true
        }
        var pinned: InlineSuggestion? = null
        val scrollable = mutableListOf<InlineSuggestion>()
        var extraPinnedCount = 0
        suggestions.forEach {
            if (it.info.isPinned) {
                if (pinned == null) {
                    pinned = it
                } else {
                    scrollable.add(extraPinnedCount++, it)
                }
            } else {
                scrollable.add(it)
            }
        }
        service.lifecycleScope.launch {
            alwaysUi.inlineSuggestionsUi.setPinnedView(
                pinned?.let { inflateInlineContentView(it) },
            )
        }
        service.lifecycleScope.launch {
            val views = scrollable.map { s ->
                service.lifecycleScope.async {
                    inflateInlineContentView(s)
                }
            }.awaitAll()
            alwaysUi.inlineSuggestionsUi.setScrollableViews(views)
        }
        isInlineSuggestionPresent = true
        evalAlwaysUiState()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun inflateInlineContentView(suggestion: InlineSuggestion): InlineContentView? = suspendCoroutine { c ->
        // callback view might be null
        suggestion.inflate(context, suggestionSize, directExecutor) { v ->
            c.resume(v)
        }
    }

    override fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {
        alwaysUi.updateButtonsStyle()
        refreshSwitchStates()
        evalAlwaysUiState()
    }

    override fun onRimeSchemaUpdated(schema: SchemaItem) {
        loadSwitches()
    }
}
