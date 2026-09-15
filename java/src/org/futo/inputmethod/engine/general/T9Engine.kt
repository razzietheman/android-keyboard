package org.futo.inputmethod.engine.general

/**
 * T9Engine — kopplar samman TT9:s (io.github.sspanak.tt9) SQLite-baserade
 * ordboks-/prediktionslager med FUTO:s IMEInterface.
 *
 * FÖRUTSÄTTNINGAR / TODO innan detta kompilerar:
 *   1. Kopiera in TT9:s paket io.github.sspanak.tt9.db.* och
 *      io.github.sspanak.tt9.ime.modes.predictions.* (Predictions,
 *      WordPredictions, Sequences) som ett eget källträd eller Gradle-modul.
 *   2. TT9:s SettingsStore/Language/NullLanguage-klasser refereras av
 *      Predictions/DataStore. Antingen porta dem rakt av (de är fristående
 *      Java utan View-beroenden), eller skriv tunna egna motsvarigheter.
 *   3. DataStore.init(context) måste anropas en gång (t.ex. i onCreate) med
 *      tillgång till TT9:s ordboks-SQLite-filer (kopiera dem till FUTO:s
 *      assets/filesDir vid första körning).
 *   4. Layouten (fysiska 0-9, stjärna och fyrkant) definieras separat som en
 *      v2keyboard-YAML och är inte del av denna fil.
 *
 * Detta är ett arbetsutkast — service-livscykeln, trådning och felhantering
 * behöver härdas innan produktion, men strukturen och FUTO-kopplingarna
 * (IMEHelper, Event, SuggestedWordInfo) är verifierade mot faktisk källkod.
 */

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.github.sspanak.tt9.db.DataStore
import io.github.sspanak.tt9.ime.modes.predictions.WordPredictions
import io.github.sspanak.tt9.languages.Language
import io.github.sspanak.tt9.languages.LanguageCollection
import io.github.sspanak.tt9.preferences.settings.SettingsStore
import org.futo.inputmethod.engine.DefaultStateHint
import org.futo.inputmethod.engine.ExpandableSuggestionBarConfiguration
import org.futo.inputmethod.engine.IMEHelper
import org.futo.inputmethod.engine.IMEInterface
import org.futo.inputmethod.engine.NonExpandableSuggestionBar
import org.futo.inputmethod.engine.StateHint
import org.futo.inputmethod.event.Event
import org.futo.inputmethod.latin.SuggestedWords
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.common.InputPointers
import org.futo.inputmethod.v2keyboard.KeyboardLayoutSetV2

class T9Engine(
    private val helper: IMEHelper
) : IMEInterface {

    private val connect get() = helper.getCurrentInputConnection()

    // TT9:s prediktionsmotor (SQLite-baserad, se Predictions.java / WordPredictions.java)
    private lateinit var predictions: WordPredictions
    private lateinit var settingsStore: SettingsStore
    private var currentLanguage: Language? = null

    // Bufferten med siffror användaren tryckt för aktuellt (ännu inte bekräftade) ord
    private var digitSequence: String = ""

    // Förenklad version av TT9:s InputMode.CASE_* cykel (se ime/modes/InputMode.java).
    // TT9:s egen variant har även CASE_DICTIONARY och auto-detektion via AutoTextCase.java
    // (versalisera efter punkt, tomt fält, etc.) — utelämnat här för enkelhetens skull,
    // men portar man vidare fidelity är AutoTextCase.java rätt fil att titta på.
    private enum class TextCase { LOWER, CAPITALIZE, UPPER }
    private var textCase: TextCase = TextCase.LOWER

    private fun applyTextCase(word: String): String = when (textCase) {
        TextCase.LOWER -> word.lowercase(java.util.Locale.getDefault())
        TextCase.CAPITALIZE -> word.replaceFirstChar { it.titlecase(java.util.Locale.getDefault()) }
        TextCase.UPPER -> word.uppercase(java.util.Locale.getDefault())
    }

    /** TT9-konvention: '*' växlar lower → Capitalize → UPPER → lower ... */
    private fun cycleTextCase() {
        textCase = when (textCase) {
            TextCase.LOWER -> TextCase.CAPITALIZE
            TextCase.CAPITALIZE -> TextCase.UPPER
            TextCase.UPPER -> TextCase.LOWER
        }
        // Uppdatera förslagsraden direkt så användaren ser den nya skiftlägesformen
        // utan att behöva trycka en sifferknapp igen.
        onPredictionsChanged()
    }

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

    /**
     * Slår upp TT9:s Language-objekt utifrån FUTO:s aktiva subtyp/locale, motsvarande
     * mönstret i HeliBoard-integrationens T9InputHandler.kt. Körs lazy (inte i onCreate)
     * eftersom RichInputMethodManager kan sakna en aktiv subtyp vid tidig livscykel.
     */
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

    // Språk-ID:n vi redan trigga en ordboks-koll för denna process — DictionaryLoader.load()
    // raderar och laddar om ordboken varje gång den anropas, så det här måste bara hända en
    // gång per språk, inte vid varje knapptryckning. Samma resonemang som i HeliBoard-versionen.
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
        digitSequence = ""
    }

    override fun onDeviceUnlocked() {}

    override fun onStartInput() {
        digitSequence = ""
        setNeutralSuggestionStrip()
    }

    override fun onFinishInput() {
        commitPendingSequenceIfAny(addTrailingSpace = false)
        digitSequence = ""
    }

    override fun onLayoutUpdated(layout: KeyboardLayoutSetV2) {}
    override fun onOrientationChanged() {}

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        composingSpanStart: Int, composingSpanEnd: Int
    ) {
        // Om markören flyttas utanför vår komposition (t.ex. användaren
        // trycker någon annanstans i texten) bör den öppna sifferbufferten
        // committas/nollställas. Enklast: nollställ alltid här.
        if (digitSequence.isNotEmpty()) {
            digitSequence = ""
            setNeutralSuggestionStrip()
        }
    }

    override fun isGestureHandlingAvailable(): Boolean = false
    override fun getStateHint(imeHint: String?): StateHint = DefaultStateHint

    // --- Input -----------------------------------------------------------

    override fun onEvent(event: Event) {
        helper.requestCursorUpdate()

        when (event.eventType) {
            Event.EVENT_TYPE_INPUT_KEYPRESS,
            Event.EVENT_TYPE_INPUT_KEYPRESS_RESUMED -> handleKeypress(event)

            Event.EVENT_TYPE_SUGGESTION_PICKED -> {
                event.mSuggestedWordInfo?.let { commitWord(it.word) }
            }

            Event.EVENT_TYPE_DOWN_UP_KEYEVENT -> {
                // Fysiska knappar (hårdvarunumpad) hamnar ofta här istället för
                // som INPUT_KEYPRESS — mappa vidare vid behov.
            }

            else -> { /* ignorera resten (gester, batch-input etc. används inte i T9) */ }
        }
    }

    private fun handleKeypress(event: Event) {
        val language = currentLanguage ?: resolveLanguage()?.also { currentLanguage = it } ?: return
        ensureDictionaryLoaded(language)

        when {
            event.mKeyCode == Constants.CODE_DELETE -> {
                if (digitSequence.isNotEmpty()) {
                    // Ta bort sista siffran och kör om prediktionen
                    digitSequence = digitSequence.dropLast(1)
                    reloadPredictions(language)
                } else {
                    // Ingen aktiv komposition — vanlig backsteg i texten
                    connect?.deleteSurroundingText(1, 0)
                }
            }

            event.mCodePoint in '0'.code..'9'.code -> {
                digitSequence += (event.mCodePoint - '0'.code).toString()
                reloadPredictions(language)
            }

            event.mCodePoint == '*'.code -> cycleTextCase()

            event.mCodePoint == ' '.code || event.mKeyCode == Constants.CODE_SPACE -> {
                commitPendingSequenceIfAny(addTrailingSpace = true)
            }

            else -> {
                // Okänd knapp i T9-läge — committa ev. pågående ord först,
                // skriv sedan ut tecknet rakt av.
                commitPendingSequenceIfAny(addTrailingSpace = false)
                connect?.commitText(String(Character.toChars(event.mCodePoint)), 1)
            }
        }
    }

    private fun reloadPredictions(language: Language) {
        if (digitSequence.isEmpty()) {
            setNeutralSuggestionStrip()
            return
        }
        predictions
            .setLanguage(language)
            .setDigitSequence(digitSequence)
            .load()
    }

    /** Callback från WordPredictions när den asynkrona DB-frågan är klar. */
    private fun onPredictionsChanged() {
        val words = predictions.getList()
        if (words.isEmpty()) {
            setNeutralSuggestionStrip()
            return
        }

        val infoList = ArrayList<SuggestedWordInfo>(words.size)
        words.forEachIndexed { index, rawWord ->
            val word = applyTextCase(rawWord)
            infoList.add(
                SuggestedWordInfo(
                    word,
                    "",
                    Int.MAX_VALUE - index,           // enkel rangordning: DB-ordningen avgör
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

    private fun commitWord(word: String) {
        connect?.commitText(word, 1)
        predictions.onAccept(word, digitSequence)   // låt TT9 lära sig/toppa ordet
        digitSequence = ""
        // Förenklad regel: gemener efter varje ord. TT9:s AutoTextCase.java
        // återställer istället till versal efter meningsslutstecken (. ! ?) —
        // värt att portera hit om du vill ha exakt samma känsla.
        textCase = TextCase.LOWER
        setNeutralSuggestionStrip()
    }

    private fun commitPendingSequenceIfAny(addTrailingSpace: Boolean) {
        val words = predictions.getList()
        if (digitSequence.isNotEmpty() && words.isNotEmpty()) {
            commitWord(applyTextCase(words.first()))
        }
        if (addTrailingSpace) {
            connect?.commitText(" ", 1)
        }
    }

    private fun setNeutralSuggestionStrip() {
        helper.setNeutralSuggestionStrip()
    }

    // --- Funktioner T9 inte använder, men som IMEInterface kräver --------

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
