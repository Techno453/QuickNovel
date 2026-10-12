package com.lagradost.quicknovel

import android.animation.ObjectAnimator
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.widget.AbsListView
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import com.lagradost.quicknovel.databinding.ReadMainBinding
import com.lagradost.quicknovel.mvvm.Resource
import com.lagradost.quicknovel.mvvm.observe
import com.lagradost.quicknovel.translator.SecurePrefs
import com.lagradost.quicknovel.ui.CONFIG_COLOR
import com.lagradost.quicknovel.ui.CONFIG_FONT
import com.lagradost.quicknovel.ui.CONFIG_FONT_BOLD
import com.lagradost.quicknovel.ui.TextAdapter
import com.lagradost.quicknovel.ui.TextConfig
import com.lagradost.quicknovel.ui.TextInteraction
import com.lagradost.quicknovel.ui.UiText
import com.lagradost.quicknovel.ui.reader.ReaderChromeController
import com.lagradost.quicknovel.ui.reader.ReaderGlossaryController
import com.lagradost.quicknovel.ui.reader.ReaderLayoutController
import com.lagradost.quicknovel.ui.reader.ReaderScrollController
import com.lagradost.quicknovel.ui.reader.ReaderSettingsSheet
import com.lagradost.quicknovel.ui.reader.ReaderTtsController
import com.lagradost.quicknovel.util.GoogleTranslate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * The reader. This class only wires the collaborators together (see docs/reader.md):
 * - [ReaderChromeController]: system bars, cutout, toolbar/bottom bar, clock/battery, orientation
 * - [ReaderLayoutController]: text column padding/cap, density changes, tabletop posture
 * - [ReaderScrollController]: the scrolling text list, position, TTS lock, overscroll, volume paging
 * - [ReaderTtsController]: TTS buttons, status, sleep timer, media actions
 * - [ReaderSettingsSheet]: the settings bottom sheet
 * - [ReaderGlossaryController]: long-press / selection glossary lookup
 */
class ReadActivity2 : AppCompatActivity(), ColorPickerDialogListener, ReaderLayoutController.Host {
    companion object {
        private var _readActivity: WeakReference<ReadActivity2>? = null
        var readActivity
            get() = _readActivity?.get()
            private set(value) {
                _readActivity = WeakReference(value)
            }
    }

    lateinit var binding: ReadMainBinding
    private val viewModel: ReadActivityViewModel by viewModels()

    private lateinit var chrome: ReaderChromeController
    private lateinit var layout: ReaderLayoutController
    private lateinit var scroll: ReaderScrollController
    private lateinit var tts: ReaderTtsController
    private lateinit var settingsSheet: ReaderSettingsSheet
    private lateinit var glossary: ReaderGlossaryController

    private val textAdapter: TextAdapter get() = scroll.textAdapter

    override fun onColorSelected(dialog: Int, color: Int) {
        settingsSheet.onColorSelected(dialog, color)
    }

    override fun onDialogDismissed(dialog: Int) {
        settingsSheet.onColorDialogDismissed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            kill()
            return true
        }
        if ((keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP)) return false

        // if we have the bottom bar up then we ignore the override functionality
        if (viewModel.bottomVisibility.isInitialized && viewModel.bottomVisibility.value == true) return false

        val down = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (tts.onVolumeKey(down)) return true
        if (viewModel.scrollWithVolume) {
            if (down) scroll.pageDown() else scroll.pageUp()
            return true
        }
        return false
    }

    private fun kill() {
        tts.cancelNotification()
        finish()
    }

    fun parseAction(input: TTSHelper.TTSActionType): Boolean {
        return tts.parseAction(input)
    }

    fun onScroll() {
        scroll.onScroll()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // we save this just in case the user fucks it up somehow
        scroll.postDesired()
        super.onConfigurationChanged(newConfig)
        layout.onConfigurationChanged(newConfig)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Safety net for the rare case the activity is still recreated (process death, a config
        // change we do not handle): make sure the exact position is on disk, init() resumes from it.
        viewModel.persistPosition()
        super.onSaveInstanceState(outState)
    }

    // ReaderLayoutController.Host
    override fun onTextAreaChanged() {
        scroll.scrollToDesired()
    }

    override fun onDensityChanged() {
        updateTextAdapterConfig()
    }

    override fun keepPositionAfterLayout() {
        scroll.postDesired()
    }

    private fun updateOtherTextConfig(config: TextConfig) {
        config.setArgs(binding.loadingText, CONFIG_FONT or CONFIG_COLOR)
        config.setArgs(binding.readBattery, CONFIG_FONT or CONFIG_COLOR or CONFIG_FONT_BOLD)
        config.setArgs(binding.readTimeClock, CONFIG_FONT or CONFIG_COLOR or CONFIG_FONT_BOLD)
        config.setArgs(binding.readLoadingBar)
    }

    private fun updateTextAdapterConfig() {
        // this did not work so I just rebind everything, it does not happend often so idc
        scroll.rebindAll()
        updateOtherTextConfig(textAdapter.config)
    }

    override fun onResume() {
        viewModel.resumedApp()
        super.onResume()
    }

    override fun onPause() {
        viewModel.leftApp()
        super.onPause()
    }

    override fun onDestroy() {
        tts.onDestroy()
        chrome.onDestroy()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CommonActivity.loadThemes(this)
        super.onCreate(savedInstanceState)
        readActivity = this
        binding = ReadMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        chrome = ReaderChromeController(this, binding, viewModel)
        chrome.onCreate()

        viewModel.init(intent, this)
        chrome.bind()

        val textAdapter = TextAdapter(
            viewModel,
            TextConfig(
                toolbarHeight = chrome.topBarHeight,
                defaultFont = binding.readText.typeface,
                textColor = viewModel.textColor,
                textSize = viewModel.textSize,
                textFont = viewModel.textFont,
                backgroundColor = viewModel.backgroundColor,
                bionicReading = viewModel.bionicReading,
                isTextSelectable = viewModel.isTextSelectable,
                verticalPadding = viewModel.textVerticalPadding,
            ).also { config ->
                updateOtherTextConfig(config)
            }
        ).apply {
            setHasStableIds(true)
        }
        scroll = ReaderScrollController(this, binding, viewModel, textAdapter) { chrome.topBarHeight }
        layout = ReaderLayoutController(this, binding, viewModel, this)
        tts = ReaderTtsController(this, binding, viewModel) { line -> scroll.updateTTSLine(line) }
        settingsSheet = ReaderSettingsSheet(this, viewModel) { layout.tabletopLowerHeight }
        glossary = ReaderGlossaryController(this, viewModel)
        textAdapter.interaction = object : TextInteraction {
            override fun onLongPress(span: TextSpan, offset: Int): Boolean =
                glossary.lookupAt(span.text, offset, span.index)

            override val selectionLookupEnabled: Boolean get() = glossary.isAvailable

            override fun onSelectionLookup(span: TextSpan, start: Int, end: Int) =
                glossary.lookupSelection(span.text, start, end, span.index)
        }

        layout.bind()
        bindTextConfig()
        tts.bind()
        bindToolbar()
        bindVisibility()
        bindLoadingStatus()
        scroll.bind()

        //here inserted novel chapter text into recyclerview
        observe(viewModel.chapter) { chapter ->
            scroll.submit(chapter)
        }

        binding.readActionSettings.setOnClickListener {
            settingsSheet.show()
        }
    }

    /** Font, size, colours, spacing: update the adapter's TextConfig and rebind. */
    private fun bindTextConfig() {
        observe(viewModel.backgroundColorLive) { color ->
            binding.root.setBackgroundColor(color)
            binding.readOverlay.setBackgroundColor(color)
            if (textAdapter.changeBackgroundColor(color)) {
                updateTextAdapterConfig()
            }
        }

        observe(viewModel.textVerticalPaddingLive) { padding ->
            if (textAdapter.changeTextVerticalPadding(padding)) {
                updateTextAdapterConfig()
            }
        }

        observe(viewModel.bionicReadingLive) { color ->
            if (textAdapter.changeBionicReading(color)) {
                updateTextAdapterConfig()
            }
        }
        observe(viewModel.isTextSelectableLive) { isTextSelectable ->
            if (textAdapter.changeTextSelectable(isTextSelectable)) {
                updateTextAdapterConfig()
            }
        }

        observe(viewModel.textSizeLive) { size ->
            if (textAdapter.changeSize(size)) {
                updateTextAdapterConfig()
                scroll.postDesired()
            }
        }

        observe(viewModel.textColorLive) { color ->
            if (textAdapter.changeColor(color)) {
                updateTextAdapterConfig()
            }
        }

        observe(viewModel.textFontLive) { font ->
            if (textAdapter.changeFont(font)) {
                updateTextAdapterConfig()
            }
        }
    }

    /** Title, chapter subtitle and the chapter list button. */
    private fun bindToolbar() {
        observe(viewModel.title) { title ->
            binding.readToolbar.title = title
            // the book is loaded: load its Story Bible for the long-press lookup
            glossary.prepare()
        }

        observe(viewModel.chapterTile) { title ->
            binding.readToolbar.subtitle = title.asString(binding.readToolbar.context)
        }

        observe(viewModel.chaptersTitles) { titles ->
            binding.readActionChapters.setOnClickListener {
                showChapterList(titles)
            }
        }
    }

    private fun showChapterList(titles: List<UiText>) {
        val shouldTranslate = viewModel.book.isTranslatableSource() &&
                SecurePrefs.isGoogleTranslateTitlesEnabled(this)

        fun showChapterDialog(displayTitles: List<String>) {
            val builderSingle: AlertDialog.Builder = AlertDialog.Builder(this)
            val currentChapter = viewModel.desiredIndex?.index
            val validChapter =
                currentChapter != null && currentChapter >= 0 && currentChapter < displayTitles.size
            if (validChapter) {
                builderSingle.setTitle(displayTitles[currentChapter])
            } else {
                builderSingle.setTitle(R.string.select_chapter)
            }

            val arrayAdapter = ArrayAdapter<String>(this, R.layout.chapter_select_dialog)
            arrayAdapter.addAll(displayTitles)

            builderSingle.setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            builderSingle.setAdapter(arrayAdapter) { _, which ->
                viewModel.seekToChapter(which)
            }

            val dialog = builderSingle.create()
            dialog.show()

            dialog.listView.choiceMode = AbsListView.CHOICE_MODE_SINGLE
            if (validChapter) {
                dialog.listView.setSelection(currentChapter)
                dialog.listView.setItemChecked(currentChapter, true)
            }
        }

        if (shouldTranslate) {
            lifecycleScope.launch {
                val translated = withContext(Dispatchers.IO) {
                    titles.map { title ->
                        try {
                            GoogleTranslate.translate(title.asString(this@ReadActivity2))
                        } catch (_: Exception) {
                            title.asString(this@ReadActivity2)
                        }
                    }
                }
                showChapterDialog(translated)
            }
        } else {
            showChapterDialog(titles.map { it.asString(this) })
        }
    }

    /** Tap to toggle the bars; the bars follow [ReadActivityViewModel.bottomVisibility]. */
    private fun bindVisibility() {
        binding.apply {
            realText.setOnClickListener {
                viewModel.switchVisibility()
            }
            readToolbar.setOnClickListener {
                viewModel.switchVisibility()
            }
            readerLinContainer.setOnClickListener {
                viewModel.switchVisibility()
            }
        }

        observe(viewModel.bottomVisibility) { visibility ->
            if (visibility) {
                chrome.showSystemUI()
                // here we actually do not want to fix the tts bug, as it will cause a bad behavior
                // when the text is very low
            } else {
                chrome.hideSystemUI()
                // otherwise we have a shitty bug with tts locking range
                binding.root.post {
                    scroll.updateTTSLine(viewModel.ttsLine.value)
                }
            }
        }
    }

    private fun bindLoadingStatus() {
        var last: Resource<Boolean> = Resource.Loading() // very dirty
        observe(viewModel.loadingStatus) { loading ->
            val different = last != loading
            last = loading
            when (loading) {
                is Resource.Success -> {
                    binding.readLoading.isVisible = false
                    binding.readFail.isVisible = false

                    binding.readNormalLayout.isVisible = true

                    if (different) {
                        binding.readNormalLayout.alpha = 0.01f

                        ObjectAnimator.ofFloat(binding.readNormalLayout, "alpha", 1f).apply {
                            duration = 300
                            start()
                        }
                    } else {
                        binding.readNormalLayout.alpha = 1.0f
                    }
                }

                is Resource.Loading -> {
                    binding.readNormalLayout.isVisible = false
                    binding.readFail.isVisible = false
                    binding.readLoading.isVisible = true
                    binding.loadingText.apply {
                        isGone = loading.url.isNullOrBlank()
                        text = loading.url ?: ""
                    }
                }

                is Resource.Failure -> {
                    binding.readLoading.isVisible = false
                    binding.readFail.isVisible = true
                    binding.failText.text = loading.errorString
                    binding.readNormalLayout.isVisible = false
                }
            }
        }
    }
}
