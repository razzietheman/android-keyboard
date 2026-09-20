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

class T9Engine(
    private val helper: IMEHelper
) : IMEInterface {

    private val connect get() = helper.getCurrentInputConnection()

    // TT9:s prediktionsmotor (SQLite-baserad, se Predictions.java / WordPredictions.java)
    private lateinit var predictions: WordPredictions
    private lateinit var settingsStore: SettingsStore
    private var currentLanguage: Language? = null

    // En siffra per BOKSTAVSPOSITION i det pågående ordet (inte en per
    // knapptryckning — cyklande tryck på samma knapp lägger INTE till en ny
    // post, de byter bara ut bokstaven på den redan existerande positionen).
    // Används både för TT9:s prediktiva uppslag (joinToString) och för att
    // veta hur många tecken som ska tas bort om ett förslag väljs istället.
    private val wordDigits = mutableListOf<Int>()
    private val digitSequence get() = wordDigits.joinToString("")

    // Multi-tap-cykling
    private var lastMultiTapDigit = -1
    private var multiTapCycleIndex = 0
    private var lastMultiTapTimeMs = 0L
    private val MULTI_TAP_TIMEOUT_MS = 1200L

    // T9-i-FUTO-patch: EmojiAction ligger på index 0 i AllActionsMap
    // (Registry.kt — "Note: indices must stay stable"). Verifiera mot
    // Registry.kt igen om FUTO:s actionlista någonsin ändras.
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

    /**
     * Slår upp TT9:s Language-objekt utifrån FUTO:s aktiva subtyp/locale.
     * Körs lazy (inte i onCreate) eftersom RichInputMethodManager kan sakna
     * en aktiv subtyp vid tidig livscykel.
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

    // Språk-ID:n vi redan triggat en ordboks-koll för denna process — DictionaryLoader.load()
    // raderar och laddar om ordboken varje gång den anropas, så det här måste bara hända en
    // gång per språk, inte vid varje knapptryckning.
    //
    // OBS: multi-tap-bokstäverna beror INTE på den nedladdade ordboken (de kommer från
    // språkDEFINITIONEN, redan bundlad som asset) — bara de PREDIKTIVA ordförslagen gör det.
    // Multi-tap fungerar alltså direkt även om nedladdningen inte hunnit klart.
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
    }

    override fun onFinishInput() {
        // Bokstäverna är redan skrivna via multi-tap — bara nollställ tillståndet,
        // inget att committa/ta bort.
        finalizeWord()
    }

    override fun onLayoutUpdated(layout: KeyboardLayoutSetV2) {}
    override fun onOrientationChanged() {}

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        composingSpanStart: Int, composingSpanEnd: Int
    ) {
        // Medvetet tom — se tidigare buggfix-kommentar i historiken: att
        // nollställa tillstånd här bröt flersiffriga sekvenser eftersom
        // callbacken triggas efter i princip varje knapptryckning.
    }

    override fun isGestureHandlingAvailable(): Boolean = false
    override fun getStateHint(imeHint: String?): StateHint = DefaultStateHint

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

    /**
     * FUTO:s extra sifferrad (överst i tangentbordet) ska vara rena siffror.
     * Dessa tryck ska inte gå igenom T9 multi-tap-logiken.
     */
    private fun isNumberRowPress(event: Event): Boolean {
        if (event.mCodePoint !in '0'.code..'9'.code) return false

        // Undvik att felaktigt behandla alla nummer som "number row" om Y-värdet inte finns.
        if (event.mY < 0) return false

        val keyboardHeight = helper.keyboardRect.height
        if (keyboardHeight <= 0) return false

        // T9-layouten har en extra top-row med siffror i FUTO.
        // För att skilja den från T9-raderna räcker det att kolla om
        // trycket ligger i den övre ~20 % av tangentbordet.
        return event.mY < keyboardHeight * 0.20f
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
                // Fysiska knappar (hårdvarunumpad) hamnar ofta här istället för
                // som INPUT_KEYPRESS — mappa vidare vid behov.
            }

            else -> {
                // ignorera resten (gester, batch-input etc. används inte i T9)
            }
        }
    }

    private fun handleSoftwareGeneratedText(event: Event) {
        val text = event.mText ?: return

        if (text.isEmpty()) {
            return
        }

        finalizeWord()
        connect?.commitText(text, 1)
    }

    private fun handleKeypress(event: Event) {
        if (handleFutoAction(event)) {
            return
        }

        // FUTO:s extra sifferrad ska alltid skriva siffror direkt.
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

            // T9-i-FUTO-patch: trigger-kod för "öppna emoji-panelen", skickad
            // från en moreKeys-post i t9.yaml (långtryck på '*'). Se
            // klasskommentaren högst upp för var EMOJI_ACTION_ID kommer ifrån.
            event.mKeyCode == Constants.CODE_EMOJI -> {
                finalizeWord()
                helper.triggerAction(EMOJI_ACTION_ID, false)
            }

            else -> {
                // T9-i-FUTO-patch (kraschfix): funktionsknappar utan ett skrivbart
                // tecken (t.ex. tryck på FUTO:s övriga åtgärdsknappar i raden ovanför
                // tangentbordet — inställningar, urklipp, röstinmatning osv., eller
                // andra !code/-koder vi inte har en egen gren för) har
                // event.mCodePoint == Event.NOT_A_CODE_POINT (-1). Character.toChars(-1)
                // kastar IllegalArgumentException och kraschade hela tangentbordet.
                // Ignorera dem säkert istället för att gissa att allt är skrivbar text.
                if (event.isFunctionalKeyEvent()) {
                    return
                }
                finalizeWord()
                connect?.commitText(String(Character.toChars(event.mCodePoint)), 1)
            }
        }
    }

    /**
     * Multi-tap + parallell prediktion. Samma sifferknapp igen inom
     * MULTI_TAP_TIMEOUT_MS cyklar till nästa bokstav på den knappen och
     * ERSÄTTER föregående (samma position i ordet). En ny knapp, eller
     * samma knapp efter timeout, låser föregående bokstav och börjar en
     * ny position.
     */
    private fun handleMultiTapDigit(digit: Int, language: Language) {
        val letters = language.getKeyCharacters(digit)
        if (letters.isEmpty()) {
            // Ingen bokstavsmappning för den här knappen (0/1 är
            // SPECIAL/PUNCTUATION i TT9:s layoutdefinitioner) — avsluta
            // ev. pågående ord och skriv siffran rakt av.
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
            // Samma position i ordet — wordDigits ändras inte.
        } else {
            multiTapCycleIndex = 0
            wordDigits.add(digit)
        }

        connect?.commitText(letters[multiTapCycleIndex], 1)
        lastMultiTapDigit = digit
        lastMultiTapTimeMs = now

        // Kör parallellt de prediktiva förslagen för hela ordet hittills,
        // så de finns som alternativ i förslagsraden.
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

    /** Callback från WordPredictions när den asynkrona DB-frågan är klar. */
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

    /**
     * Användaren valde ett PREDIKTIVT förslag istället för det multi-tap
     * redan skrivit. Ta bort de redan inskrivna bokstäverna (en per post i
     * wordDigits) och skriv in det valda ordet istället.
     */
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

    /** Nollställer ord-tillståndet UTAN att röra redan skriven text (den är redan korrekt). */
    private fun finalizeWord() {
        wordDigits.clear()
        lastMultiTapDigit = -1
        setNeutralSuggestionStrip()
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
