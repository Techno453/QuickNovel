package com.lagradost.quicknovel

import androidx.core.graphics.toColorInt
import androidx.lifecycle.MutableLiveData
import com.google.mlkit.nl.translate.TranslateLanguage
import com.lagradost.quicknovel.ui.OrientationType
import com.lagradost.quicknovel.util.translation.models.TranslatorAgents

/**
 * The reader's persisted display, TTS and translation preferences (each backed by DataStore,
 * the *Live variants also posted to LiveData). [ReadActivityViewModel] implements this interface
 * by delegating to [ReaderPreferenceStore], so callers keep using `viewModel.textSize` etc.
 * Preferences whose setter has side effects (reader type, TTS speed/pitch, reading mode) stay in
 * the view model.
 */
interface ReaderPreferences {
    val textFontLive: MutableLiveData<String>
    val textSizeLive: MutableLiveData<Int>
    val bionicReadingLive: MutableLiveData<Boolean>
    val isTextSelectableLive: MutableLiveData<Boolean>
    val orientationLive: MutableLiveData<Int>
    val textColorLive: MutableLiveData<Int>
    val textVerticalPaddingLive: MutableLiveData<Float>
    val backgroundColorLive: MutableLiveData<Int>
    val showBatteryLive: MutableLiveData<Boolean>
    val showTimeLive: MutableLiveData<Boolean>
    val paddingHorizontalLive: MutableLiveData<Int>
    val paddingVerticalLive: MutableLiveData<Int>
    val maxTextWidthLive: MutableLiveData<Int>
    val screenAwakeLive: MutableLiveData<Boolean>
    val ttsTimerLive: MutableLiveData<Long>
    val mlFromLanguageLive: MutableLiveData<String>
    val mlToLanguageLive: MutableLiveData<String>
    val mlTranslationAgentLive: MutableLiveData<String>
    var scrollWithVolume: Boolean
    var authorNotes: Boolean
    var ttsLock: Boolean
    var textFont: String
    var textSize: Int
    var bionicReading: Boolean
    var isTextSelectable: Boolean
    var orientation: Int
    var textColor: Int
    var textVerticalPadding: Float
    var backgroundColor: Int
    var showBattery: Boolean
    var showTime: Boolean
    var paddingHorizontal: Int
    var paddingVertical: Int
    var maxTextWidth: Int
    var screenAwake: Boolean
    var ttsTimer: Long
    var mlFromLanguage: String
    var mlToLanguage: String
    var mlTranslationAgent: String
}

class ReaderPreferenceStore : ReaderPreferences {
    override var scrollWithVolume by PreferenceDelegate(EPUB_SCROLL_VOL, true, Boolean::class)
    override var authorNotes by PreferenceDelegate(EPUB_AUTHOR_NOTES, true, Boolean::class)
    override var ttsLock by PreferenceDelegate(EPUB_TTS_LOCK, true, Boolean::class)
    //var ttsOSSpeed by PreferenceDelegate(EPUB_TTS_OS_SPEED, true, Boolean::class)

    override val textFontLive: MutableLiveData<String> = MutableLiveData(null)
    override var textFont by PreferenceDelegateLiveView(EPUB_FONT, "", String::class, textFontLive)
    override val textSizeLive: MutableLiveData<Int> = MutableLiveData(null)
    override var textSize by PreferenceDelegateLiveView(
        EPUB_TEXT_SIZE,
        DEF_FONT_SIZE,
        Int::class,
        textSizeLive
    )

    override val bionicReadingLive: MutableLiveData<Boolean> = MutableLiveData(null)
    override var bionicReading by PreferenceDelegateLiveView(
        EPUB_TEXT_BIONIC,
        false,
        Boolean::class,
        bionicReadingLive
    )

    override val isTextSelectableLive: MutableLiveData<Boolean> = MutableLiveData(null)
    override var isTextSelectable by PreferenceDelegateLiveView(
        EPUB_TEXT_SELECTABLE,
        false,
        Boolean::class,
        isTextSelectableLive
    )

    override val orientationLive: MutableLiveData<Int> = MutableLiveData(null)
    override var orientation by PreferenceDelegateLiveView(
        EPUB_LOCK_ROTATION,
        OrientationType.DEFAULT.prefValue,
        Int::class, orientationLive
    )

    override val textColorLive: MutableLiveData<Int> = MutableLiveData(null)
    override var textColor by PreferenceDelegateLiveView(
        EPUB_TEXT_COLOR, "#cccccc".toColorInt(), Int::class, textColorLive
    )

    override val textVerticalPaddingLive: MutableLiveData<Float> = MutableLiveData(null)
    override var textVerticalPadding by PreferenceDelegateLiveView(
        EPUB_TEXT_VERTICAL_PADDING, 7.5f, Float::class, textVerticalPaddingLive
    )

    override val backgroundColorLive: MutableLiveData<Int> = MutableLiveData(null)
    override var backgroundColor by PreferenceDelegateLiveView(
        EPUB_BG_COLOR, "#292832".toColorInt(), Int::class, backgroundColorLive
    )

    override val showBatteryLive: MutableLiveData<Boolean> = MutableLiveData(null)
    override var showBattery by PreferenceDelegateLiveView(
        EPUB_HAS_BATTERY, true, Boolean::class, showBatteryLive
    )

    override val showTimeLive: MutableLiveData<Boolean> = MutableLiveData(null)
    override var showTime by PreferenceDelegateLiveView(
        EPUB_HAS_TIME, true, Boolean::class, showTimeLive
    )

    override val paddingHorizontalLive: MutableLiveData<Int> = MutableLiveData(null)
    override var paddingHorizontal by PreferenceDelegateLiveView(
        EPUB_TEXT_PADDING, DEF_HORIZONTAL_PAD, Int::class, paddingHorizontalLive
    )

    override val paddingVerticalLive: MutableLiveData<Int> = MutableLiveData(null)
    override var paddingVertical by PreferenceDelegateLiveView(
        EPUB_TEXT_PADDING_TOP, DEF_VERTICAL_PAD, Int::class, paddingVerticalLive
    )

    /** Max width of the text column in dp, centred on wide windows (foldables, tablets). 0 = no limit. */
    override val maxTextWidthLive: MutableLiveData<Int> = MutableLiveData(null)
    override var maxTextWidth by PreferenceDelegateLiveView(
        EPUB_TEXT_MAX_WIDTH, DEF_MAX_TEXT_WIDTH, Int::class, maxTextWidthLive
    )

    //val time12HLive: MutableLiveData<Boolean> = MutableLiveData(null)

    override val screenAwakeLive: MutableLiveData<Boolean> = MutableLiveData(null)
    override var screenAwake by PreferenceDelegateLiveView(
        EPUB_KEEP_SCREEN_ACTIVE, true, Boolean::class, screenAwakeLive
    )

    // in milliseconds
    override val ttsTimerLive: MutableLiveData<Long> = MutableLiveData(null)
    override var ttsTimer by PreferenceDelegateLiveView(
        EPUB_SLEEP_TIMER, 0, Long::class, ttsTimerLive
    )

    override val mlFromLanguageLive: MutableLiveData<String> = MutableLiveData(null)
    override var mlFromLanguage by PreferenceDelegateLiveView(
        EPUB_ML_FROM_LANGUAGE,
        TranslateLanguage.ENGLISH,
        String::class,
        mlFromLanguageLive
    )

    override val mlToLanguageLive: MutableLiveData<String> = MutableLiveData(null)
    override var mlToLanguage by PreferenceDelegateLiveView(
        EPUB_ML_TO_LANGUAGE,
        TranslateLanguage.ENGLISH,
        String::class,
        mlToLanguageLive
    )

    override val mlTranslationAgentLive: MutableLiveData<String> = MutableLiveData(TranslatorAgents.OFFLINE.name)
    override var mlTranslationAgent by PreferenceDelegateLiveView(
        "ml_translation_agent",
        TranslatorAgents.OFFLINE.name,
        String::class,
        mlTranslationAgentLive
    )
}
