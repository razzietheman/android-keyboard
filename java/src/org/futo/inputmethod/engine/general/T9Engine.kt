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
 * '*' och '#' är egna, vanliga tecken (inga lägesväxlare):
 *   - Långtryck '*' öppnar FUTO:s emoji-panel (helper.triggerAction, se
 *     Registry.kt — EmojiAction ligger på index 0 i AllActionsMap).
 *   - Långtryck '#' visar en liten meny med vanliga specialtecken
 *     (definierat i layoutfilen t9.yaml, inte i denna fil) — FUTO har
 *     ingen enkel, programmatiskt trigger-bar "öppna hela teckenpanelen"-
 *     krok utan att byta hela tangentbordslayouten, vilket är
 *     arkitektoniskt oprövat ihop med hur T9-interceptionen fungerar.
 *
 * Strukturen och FUTO-kopplingarna (IMEHelper, Event, SuggestedWordInfo,
 * triggerAction) är verifierade mot faktisk källkod.
 */

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
import org.futo.inputmethod.latin.SuggestedWords
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.common.InputPointers
import org.futo.inputmethod.v2keyboard.KeyboardLayoutSetV2
import android.text.InputType   // <-- behövs för numeric override

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

    override fun onStartInput() {
        wordDigits.clear()
        lastMultiTapDigit = -1
        setNeutralSuggestionStrip()
    }

    override fun onFinishInput() {
        finalizeWord()
    }

    override fun onEvent(event: Event) {
        helper.requestCursorUpdate()

        when (event.eventType) {
            Event.EVENT_TYPE_INPUT_KEYPRESS,
            Event.EVENT_TYPE_INPUT_KEYPRESS_RESUMED -> handleKeypress(event)
            Event.EVENT_TYPE_SOFTWARE_GENERATED_STRING -> handleSoftwareGeneratedText(event)
            Event.EVENT_TYPE_SUGGESTION_PICKED -> event.mSuggestedWordInfo?.let { replaceCurrentWordWith(it.word) }
            else -> {}
        }
    }

    private fun handleSoftwareGeneratedText(event: Event) {
        val text = event.mText ?: return
        if (text.isEmpty()) return
        finalizeWord()
        connect?.commitText(text, 1)
    }

    private fun handleKeypress(event: Event) {
        if (handleFutoAction(event)) return

        // ============================================================
        // 🔥 NUMERIC FIELD OVERRIDE (fixar OTP/PIN/telefonnummer)
        // ============================================================
        val editorInfo = helper.editorInfo
        if (editorInfo != null) {
            val inputType = editorInfo.inputType

            val isNumericField =
                (inputType and InputType.TYPE_CLASS_NUMBER) != 0 ||
                (inputType and InputType.TYPE_CLASS_PHONE) != 0 ||
                (inputType and InputType.TYPE_NUMBER_VARIATION_PASSWORD) != 0

            if (isNumericField) {
                finalizeWord()
                connect?.commitText(event.mCodePoint.toChar().toString(), 1)
                return
            }
        }
        // ============================================================

        if (isNumberRowPress(event)) {
            finalizeWord()
            connect?.commitText(event.mCodePoint.toChar().toString(), 1)
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
            }

            event.mCodePoint in '0'.code..'9'.code -> {
                handleMultiTapDigit(event.mCodePoint - '0'.code, language)
            }

            event.mCodePoint == '*'.code -> {
                finalizeWord()
                connect?.commitText("*", 1)
            }

            event.mCodePoint == '#'.code -> {
                finalizeWord()
                connect?.commitText("#", 1)
            }

            event.mCodePoint == ' '.code || event.mKeyCode == Constants.CODE_SPACE -> {
                finalizeWord()
                connect?.commitText(" ", 1)
            }

            event.mKeyCode == Constants.CODE_EMOJI -> {
                finalizeWord()
                helper.triggerAction(EMOJI_ACTION_ID, false)
            }

            else -> {
                if (event.isFunctionalKeyEvent()) return
                finalizeWord()
                connect?.commitText(String(Character.toChars(event.mCodePoint)), 1)
            }
        }
    }

    private fun handleMultiTapDigit(digit: Int, language: Language) {
        val letters = language.getKeyCharacters(digit)
        if (letters.isEmpty()) {
            finalizeWord()
            connect?.commitText(digit.toString(), 1)
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

        connect?.commitText(letters[multiTapCycleIndex], 1)
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
        connect?.commitText(word, 1)
        predictions.onAccept(word, digitSequence)
        wordDigits.clear()
        lastMultiTapDigit = -1
        setNeutralSuggestionStrip()
    }

    private fun finalizeWord() {
        wordDigits.clear()
        lastMultiTapDigit = -1
        setNeutralSuggestionStrip()
    }

    private fun setNeutralSuggestionStrip() {
        helper.setNeutralSuggestionStrip()
    }

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
