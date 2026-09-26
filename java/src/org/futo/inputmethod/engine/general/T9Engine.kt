package org.futo.inputmethod.engine.general

/**
 * T9Engine — kopplar samman TT9:s (io.github.sspanak.tt9) SQLite-baserade
 * ordboks-/prediktionslager med FUTO:s IMEInterface.
 *
 * OMDESIGNAD (2026-09): multi-tap är nu DEFAULT-beteendet för varje
 * sifferknapp — ingen separat "läges-knapp" behövs. Varje knapptryckning:
 *   1. Sätter/cyklar direkt in en bokstav i texten (multi-tap: samma knapp
 *      igen inom kort tid = nästa bokstav på den knappen, t.ex. 4,4,3,3,5
 *      ger "hej": 4-4→h, 3-3→e, 5→j).
 *   2. Bygger SAMTIDIGT upp en siffersekvens som skickas till TT9:s
 *      prediktionsmotor, vars ordförslag visas i förslagsraden som
 *      ALTERNATIV till vad multi-tap redan skrivit. Trycker man på ett
 *      förslag ersätts de redan inskrivna bokstäverna med det valda ordet.
 *
 * Auto-caps: respekterar FUTO:s "Automatisk användning av stora bokstäver"
 * via getCurrentAutoCapsState() + helper.keyboardShiftMode. Efter mellanslag
 * / skiljetecken uppdateras shift-läget så nästa ord börjar med versal.
 *
 * '*' och '#' är egna, vanliga tecken (inga lägesväxlare):
 *   - Långtryck '*' öppnar FUTO:s emoji-panel (helper.triggerAction).
 *   - Långtryck '#' visar specialtecken via layoutfilen t9.yaml.
 */

import android.text.InputType
import android.text.TextUtils
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.github.sspanak.tt9.db.DataStore
import io.github.sspanak.tt9.ime.modes.predictions.WordPredictions
import io.github.sspanak.tt9.languages.Language
import io.github.sspanak.tt9.languages.LanguageCollection
import io.github.sspanak.tt9.preferences.settings.SettingsStore
import org.futo.inputmethod.engine.DefaultStateHint
import org.futo.inputmethod.engine.IMEHelper
import org.futo.inputmethod.engine.IMEInterface
import org.futo.inputmethod.engine.StateHint
import org.futo.inputmethod.event.Event
import org.futo.inputmethod.keyboard.KeyboardId
import org.futo.inputmethod.latin.SuggestedWords
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.futo.inputmethod.latin.WordComposer
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.common.InputPointers
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.v2keyboard.KeyboardLayoutSetV2
import java.util.Locale

class T9Engine(
    private val helper: IMEHelper
) : IMEInterface {

    private val connect get() = helper.getCurrentInputConnection()

    private lateinit var predictions: WordPredictions
    private lateinit var settingsStore: SettingsStore
    private var currentLanguage: Language? = null

    private val wordDigits = mutableListOf<Int>()
    private val digitSequence get() = wordDigits.joinToString("")

    private var lastMultiTapDigit = -1
    private var multiTapCycleIndex = 0
    private var lastMultiTapTimeMs = 0L
    private val MULTI_TAP_TIMEOUT_MS = 1200L

    private val EMOJI_ACTION_ID = 0

    private val loadingState: MutableState<Boolean> = mutableStateOf(false)
    override fun getLoadingState(): MutableState<Boolean> = loadingState

    // --- Livscykel -----------------------------------------------------

    override fun onCreate() {
        val context = helper.context
        DataStore.init(context)
        settingsStore = SettingsStore(context)
        LanguageCollection.init(context)

        predictions = WordPredictions(settingsStore).apply {
            setWordsChangedHandler { onPredictionsChanged() }
        }
    }

    private fun resolveLanguage(): Language? {
        val locale = try {
            org.futo.inputmethod.latin.RichInputMethodManager.getInstance()
                .getCurrentSubtypeLocale()
        } catch (e: Exception) {
            null
        } ?: return null

        return LanguageCollection.getByLocale(locale.toLanguageTag())
            ?: LanguageCollection.getByLanguageCode(locale.language)
    }

    private val checkedLanguageIds = mutableSetOf<Int>()

    private fun ensureDictionaryLoaded(language: Language) {
        val id = language.id
        if (!checkedLanguageIds.add(id)) return
        DataStore.exists({ existingIds ->
            if (!existingIds.contains(id)) {
                io.github.sspanak.tt9.db.words.DictionaryLoader.load(helper.context, settingsStore, language)
            }
        }, arrayListOf(language))
    }

    override fun onDestroy() {
        wordDigits.clear()
    }

    override fun onDeviceUnlocked() {}

    override fun onStartInput() {
        wordDigits.clear()
        lastMultiTapDigit = -1
        setNeutralSuggestionStrip()
        // Se till att shift-läge speglar auto-caps vid fältstart
        helper.keyboardSwitcher.requestUpdatingShiftState(getCurrentAutoCapsState())
    }

    override fun onFinishInput() {
        finalizeWord()
    }

    override fun onLayoutUpdated(layout: KeyboardLayoutSetV2) {}
    override fun onOrientationChanged() {}

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        composingSpanStart: Int, composingSpanEnd: Int
    ) {
        // Medvetet tom — nollställning här bröt flersiffriga sekvenser.
    }

    override fun isGestureHandlingAvailable(): Boolean = false
    override fun getStateHint(imeHint: String?): StateHint = DefaultStateHint

    // --- Auto-caps (FUTO "Automatisk användning av stora bokstäver") ----

    /**
     * Måste implementeras — default i IMEInterface är alltid CAP_MODE_OFF,
     * vilket gör att KeyboardState aldrig auto-shiftar och keyboardShiftMode
     * förblir OFF. Då blir multi-tap alltid små bokstäver.
     */
    override fun getCurrentAutoCapsState(): Int {
        val settings = try {
            Settings.getInstance().current
        } catch (_: Exception) {
            return Constants.TextUtils.CAP_MODE_OFF
        }
        if (!settings.mAutoCap) {
            return Constants.TextUtils.CAP_MODE_OFF
        }

        val editorInfo = helper.getCurrentEditorInfo() ?: return Constants.TextUtils.CAP_MODE_OFF
        val inputType = editorInfo.inputType
        // Fält som inte ska ha auto-caps (lösenord, e-post, URL, etc.)
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_URI
        ) {
            return Constants.TextUtils.CAP_MODE_OFF
        }

        val ic = connect ?: return Constants.TextUtils.CAP_MODE_OFF
        return try {
            // Enkel, stabil heuristik utifrån text före markören.
            // CAP_MODE_WORDS = stor bokstav i början av mening/ord.
            val before = ic.getTextBeforeCursor(4, 0)?.toString() ?: ""
            when {
                before.isEmpty() -> TextUtils.CAP_MODE_WORDS
                before.endsWith(". ") || before.endsWith("! ") || before.endsWith("? ") ->
                    TextUtils.CAP_MODE_WORDS
                before.endsWith(".\n") || before.endsWith("!\n") || before.endsWith("?\n") ->
                    TextUtils.CAP_MODE_WORDS
                before.endsWith("\n") -> TextUtils.CAP_MODE_WORDS
                // Efter skiljetecken utan mellanslag ännu (vanligt i vissa fält)
                before.length >= 1 && before.last() in ".!?" -> TextUtils.CAP_MODE_WORDS
                else -> Constants.TextUtils.CAP_MODE_OFF
            }
        } catch (_: Exception) {
            Constants.TextUtils.CAP_MODE_OFF
        }
    }

    /** True om manuell shift, caps lock eller auto-caps kräver versal. */
    private fun shouldUppercaseLetter(): Boolean {
        val shiftMode = helper.keyboardShiftMode
        if (shiftMode != WordComposer.CAPS_MODE_OFF) {
            return true
        }
        return getCurrentAutoCapsState() != Constants.TextUtils.CAP_MODE_OFF
    }

    private fun applyCase(letter: String): String {
        return if (shouldUppercaseLetter()) {
            letter.uppercase(Locale.getDefault())
        } else {
            letter
        }
    }

    private fun refreshShiftState() {
        try {
            helper.keyboardSwitcher.requestUpdatingShiftState(getCurrentAutoCapsState())
        } catch (_: Exception) {
            // ignore
        }
    }

    // --- Input -----------------------------------------------------------

    private fun handleFutoAction(event: Event): Boolean {
        val keyCode = event.mKeyCode

        if (keyCode in Constants.CODE_ACTION_0..Constants.CODE_ACTION_MAX) {
            finalizeWord()
            helper.triggerAction(keyCode - Constants.CODE_ACTION_0, false)
            return true
        }

        if (keyCode in Constants.CODE_ALT_ACTION_0..Constants.CODE_ALT_ACTION_MAX) {
            finalizeWord()
            helper.triggerAction(keyCode - Constants.CODE_ALT_ACTION_0, true)
            return true
        }

        return false
    }

    private fun isNumberRowPress(event: Event): Boolean {
        if (event.mCodePoint !in '0'.code..'9'.code) {
            return false
        }

        val keyboard = helper.keyboardSwitcher.keyboard
        if (keyboard?.mId?.mNumberRow != true) {
            return false
        }

        if (event.mY < 0) {
            return false
        }

        val keyboardHeight = helper.keyboardRect.height()
        if (keyboardHeight <= 0) {
            return false
        }

        return event.mY < keyboardHeight * 0.20f
    }

    private fun isOnNumberPanel(): Boolean {
        val keyboard = helper.keyboardSwitcher.keyboard ?: return false
        return keyboard.mId.mElementId == KeyboardId.ELEMENT_NUMBER
    }

    override fun onEvent(event: Event) {
        helper.requestCursorUpdate()

        when (event.eventType) {
            Event.EVENT_TYPE_INPUT_KEYPRESS,
            Event.EVENT_TYPE_INPUT_KEYPRESS_RESUMED -> {
                handleKeypress(event)
            }

            Event.EVENT_TYPE_SOFTWARE_GENERATED_STRING -> {
                handleSoftwareGeneratedText(event)
            }

            Event.EVENT_TYPE_SUGGESTION_PICKED -> {
                event.mSuggestedWordInfo?.let { replaceCurrentWordWith(it.word) }
            }

            Event.EVENT_TYPE_DOWN_UP_KEYEVENT -> {
                // Fysiska knappar
            }

            else -> {
            }
        }
    }

    private fun handleSoftwareGeneratedText(event: Event) {
        val text = event.mText ?: return
        if (text.isEmpty()) return

        finalizeWord()
        connect?.commitText(text, 1)
        refreshShiftState()
    }

    private fun isNumericInputField(): Boolean {
        val inputType = helper.getCurrentEditorInfo()?.inputType ?: return false
        val fieldClass = inputType and InputType.TYPE_MASK_CLASS
        return fieldClass == InputType.TYPE_CLASS_NUMBER ||
            fieldClass == InputType.TYPE_CLASS_PHONE ||
            fieldClass == InputType.TYPE_CLASS_DATETIME
    }

    private fun handleKeypress(event: Event) {
        if (isNumericInputField()) {
            finalizeWord()
            if (event.isFunctionalKeyEvent()) {
                if (event.mKeyCode == Constants.CODE_DELETE) {
                    connect?.deleteSurroundingText(1, 0)
                }
                return
            }
            connect?.commitText(String(Character.toChars(event.mCodePoint)), 1)
            return
        }

        if (handleFutoAction(event)) {
            return
        }

        if (isNumberRowPress(event)) {
            finalizeWord()
            connect?.commitText(event.mCodePoint.toChar().toString(), 1)
            refreshShiftState()
            return
        }

        if (isOnNumberPanel() && event.mCodePoint in '0'.code..'9'.code) {
            finalizeWord()
            connect?.commitText(event.mCodePoint.toChar().toString(), 1)
            refreshShiftState()
            return
        }

        val language = currentLanguage ?: resolveLanguage()?.also { currentLanguage = it } ?: return
        ensureDictionaryLoaded(language)

        when {
            event.mKeyCode == Constants.CODE_DELETE -> {
                if (wordDigits.isNotEmpty()) {
                    connect?.deleteSurroundingText(1, 0)
                    wordDigits.removeAt(wordDigits.size - 1)
                    lastMultiTapDigit = -1
                    reloadPredictions(language)
                } else {
                    connect?.deleteSurroundingText(1, 0)
                }
                refreshShiftState()
            }

            event.mCodePoint in '0'.code..'9'.code -> {
                handleMultiTapDigit(event.mCodePoint - '0'.code, language)
            }

            event.mCodePoint == '*'.code -> {
                finalizeWord()
                connect?.commitText("*", 1)
                refreshShiftState()
            }

            event.mCodePoint == '#'.code -> {
                finalizeWord()
                connect?.commitText("#", 1)
                refreshShiftState()
            }

            event.mCodePoint == ' '.code || event.mKeyCode == Constants.CODE_SPACE -> {
                finalizeWord()
                connect?.commitText(" ", 1)
                // Efter mellanslag: uppdatera auto-caps (t.ex. efter ". ")
                refreshShiftState()
            }

            event.mKeyCode == Constants.CODE_EMOJI -> {
                finalizeWord()
                helper.triggerAction(EMOJI_ACTION_ID, false)
            }

            else -> {
                if (event.isFunctionalKeyEvent()) {
                    return
                }
                finalizeWord()
                val ch = String(Character.toChars(event.mCodePoint))
                // Stor bokstav även för vanliga tecken om auto-caps gäller
                // (t.ex. bokstäver från other keys) — siffror/symboler oförändrade
                val out = if (ch.length == 1 && ch[0].isLetter()) applyCase(ch) else ch
                connect?.commitText(out, 1)
                refreshShiftState()
            }
        }
    }

    /**
     * Multi-tap + parallell prediktion.
     * Respekterar FUTO auto-caps + manuell shift/caps lock.
     */
    private fun handleMultiTapDigit(digit: Int, language: Language) {
        val letters = language.getKeyCharacters(digit)
        if (letters.isEmpty()) {
            finalizeWord()
            connect?.commitText(digit.toString(), 1)
            refreshShiftState()
            return
        }

        val now = System.currentTimeMillis()
        val isContinuingCycle = digit == lastMultiTapDigit &&
            (now - lastMultiTapTimeMs) < MULTI_TAP_TIMEOUT_MS

        if (isContinuingCycle) {
            multiTapCycleIndex = (multiTapCycleIndex + 1) % letters.size
            connect?.deleteSurroundingText(1, 0)
        } else {
            multiTapCycleIndex = 0
            wordDigits.add(digit)
        }

        val raw = letters[multiTapCycleIndex]
        // Vid cykling inom samma position behåll samma "ska versalisera"-beslut
        // som för första tecknet i den positionen: auto-caps gäller bara
        // första bokstaven i ordet (wordDigits.size == 1 efter add).
        val letter = if (wordDigits.size <= 1 || (isContinuingCycle && wordDigits.size == 1)) {
            applyCase(raw)
        } else {
            // Fortsättning av ord → alltid gemen (om inte manuell caps lock)
            val shiftMode = helper.keyboardShiftMode
            if (shiftMode == WordComposer.CAPS_MODE_MANUAL_SHIFT_LOCKED ||
                shiftMode == WordComposer.CAPS_MODE_AUTO_SHIFT_LOCKED
            ) {
                raw.uppercase(Locale.getDefault())
            } else {
                raw
            }
        }

        connect?.commitText(letter, 1)
        lastMultiTapDigit = digit
        lastMultiTapTimeMs = now

        reloadPredictions(language)
    }

    private fun reloadPredictions(language: Language) {
        if (wordDigits.isEmpty()) {
            setNeutralSuggestionStrip()
            return
        }
        predictions
            .setLanguage(language)
            .setDigitSequence(digitSequence)
            .load()
    }

    private fun onPredictionsChanged() {
        val words = predictions.getList()
        if (words.isEmpty()) {
            setNeutralSuggestionStrip()
            return
        }

        val infoList = ArrayList<SuggestedWordInfo>(words.size)
        words.forEachIndexed { index, word ->
            infoList.add(
                SuggestedWordInfo(
                    word,
                    "",
                    Int.MAX_VALUE - index,
                    SuggestedWordInfo.KIND_PREDICTION,
                    null,
                    SuggestedWordInfo.NOT_AN_INDEX,
                    SuggestedWordInfo.NOT_A_CONFIDENCE
                )
            )
        }

        helper.showSuggestionStrip(
            SuggestedWords(infoList, infoList, null, false, false, false, 0, 0)
        )
    }

    private fun replaceCurrentWordWith(word: String) {
        if (wordDigits.isNotEmpty()) {
            connect?.deleteSurroundingText(wordDigits.size, 0)
        }
        // Förslag från TT9 kan behålla sin egen casing; vid auto-caps
        // i början av mening → versalisera första bokstaven.
        val out = if (shouldUppercaseLetter() && word.isNotEmpty()) {
            word.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            }
        } else {
            word
        }
        connect?.commitText(out, 1)
        predictions.onAccept(word, digitSequence)
        wordDigits.clear()
        lastMultiTapDigit = -1
        setNeutralSuggestionStrip()
        refreshShiftState()
    }

    private fun finalizeWord() {
        wordDigits.clear()
        lastMultiTapDigit = -1
        setNeutralSuggestionStrip()
    }

    private fun setNeutralSuggestionStrip() {
        helper.setNeutralSuggestionStrip()
    }

    // --- IMEInterface stubs -----------------------------------------------

    override fun onStartBatchInput() {}
    override fun onUpdateBatchInput(batchPointers: InputPointers?) {}
    override fun onEndBatchInput(batchPointers: InputPointers?) {}
    override fun onCancelBatchInput() {}
    override fun onCancelInput() {}
    override fun onFinishSlidingInput() {}
    override fun onCustomRequest(requestCode: Int): Boolean = false
    override fun onMovePointer(steps: Int, stepOverWords: Boolean, select: Boolean?) {}
    override fun onMoveDeletePointer(steps: Int) {}
    override fun onUpWithDeletePointerActive() {}
    override fun onUpWithPointerActive() {}
    override fun onSwipeLanguage(direction: Int) {}
    override fun onMovingCursorLockEvent(canMoveCursor: Boolean) {}
    override fun clearUserHistoryDictionaries() {}
    override fun requestSuggestionRefresh() { onPredictionsChanged() }
}
