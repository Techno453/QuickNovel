package com.lagradost.quicknovel.ui.reader

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.speech.tts.Voice
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.DEF_FONT_SIZE
import com.lagradost.quicknovel.DEF_HORIZONTAL_PAD
import com.lagradost.quicknovel.DEF_MAX_TEXT_WIDTH
import com.lagradost.quicknovel.DEF_VERTICAL_PAD
import com.lagradost.quicknovel.DataStore.getKey
import com.lagradost.quicknovel.EPUB_FONT
import com.lagradost.quicknovel.FontAdapter
import com.lagradost.quicknovel.FontFile
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.ReadActivityViewModel
import com.lagradost.quicknovel.ReadActivityViewModel.MLSettings.Companion.AUTO_LANG
import com.lagradost.quicknovel.databinding.ColorRoundCheckmarkBinding
import com.lagradost.quicknovel.databinding.ReadBottomSettingsBinding
import com.lagradost.quicknovel.ui.ReadingType
import com.lagradost.quicknovel.READING_MODE_PAGES
import com.lagradost.quicknovel.READING_MODE_SCROLL
import com.lagradost.quicknovel.util.AdaptiveLayout.adaptToWindow
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.SingleSelectionHelper.showDialog
import com.lagradost.quicknovel.util.SubtitleHelper
import com.lagradost.quicknovel.util.UIHelper.colorFromAttribute
import com.lagradost.quicknovel.util.UIHelper.popupMenu
import com.lagradost.quicknovel.util.UIHelper.systemFonts
import com.lagradost.quicknovel.util.translation.models.TranslatorAgents
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The reader settings bottom sheet (tabs: Settings, Translate, Voice, Text). Capped at the sheet
 * width and opened expanded on wide or short windows; in tabletop posture it lives below the
 * hinge ([tabletopLowerHeight]). The colour picker reports back through the activity's
 * ColorPickerDialogListener, which forwards to [onColorSelected] / [onColorDialogDismissed].
 */
class ReaderSettingsSheet(
    private val activity: AppCompatActivity,
    private val viewModel: ReadActivityViewModel,
    private val tabletopLowerHeight: () -> Int,
) {
    private var _imageHolder: WeakReference<LinearLayout>? = null
    private var imageHolder
        get() = _imageHolder?.get()
        set(value) {
            _imageHolder = WeakReference(value)
        }

    fun onColorSelected(dialog: Int, color: Int) {
        when (dialog) {
            0 -> viewModel.backgroundColor = color
            1 -> viewModel.textColor = color
        }
    }

    fun onColorDialogDismissed() {
        updateImages()
    }

    private fun updateImages() {
        val bgColors = activity.resources.getIntArray(R.array.readerBgColors)
        val textColors = activity.resources.getIntArray(R.array.readerTextColors)
        val color = viewModel.backgroundColor
        val colorPrimary = activity.colorFromAttribute(R.attr.colorPrimary)
        val colorPrim = ColorStateList.valueOf(colorPrimary)
        val colorTrans = ColorStateList.valueOf(Color.TRANSPARENT)
        var foundCurrentColor = false
        val fullAlpha = 200
        val fadedAlpha = 50

        for ((index, imgHolder) in imageHolder?.children?.withIndex() ?: return) {
            val img = imgHolder.findViewById<ImageView>(R.id.image1) ?: return

            if (index == bgColors.size) { // CUSTOM COLOR
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    img.foregroundTintList = colorPrim
                    img.foreground = ContextCompat.getDrawable(
                        activity,
                        if (foundCurrentColor) R.drawable.ic_baseline_add_24 else R.drawable.ic_baseline_check_24
                    )
                }
                img.imageAlpha = if (foundCurrentColor) fadedAlpha else fullAlpha
                img.backgroundTintList =
                    ColorStateList.valueOf(if (foundCurrentColor) Color.parseColor("#161616") else color)
                continue
            }

            if ((color == bgColors[index] && viewModel.textColor == textColors[index])) {
                foundCurrentColor = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    img.foregroundTintList = colorPrim
                }
                img.imageAlpha = fullAlpha
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    img.foregroundTintList = colorTrans
                }
                img.imageAlpha = fadedAlpha
            }
        }
    }

    private fun showFonts() {
        val builder =
            AlertDialog.Builder(activity, R.style.AlertDialogCustom).setView(R.layout.font_bottom_sheet)

        val dialog = builder.create()
        dialog.show()

        val res = dialog.findViewById<RecyclerView>(R.id.sort_click)!!

        val fonts = systemFonts
        val items = listOf(FontFile(null)) + fonts.map { FontFile(it) }

        val currentName = activity.getKey<String>(EPUB_FONT) ?: ""
        val storingIndex = items.indexOfFirst { (it.file?.name ?: "") == currentName }

        val adapter = FontAdapter(activity, storingIndex) { file ->
            viewModel.textFont = file.file?.name ?: ""
            dialog.dismiss()
        }
        res.adapter = adapter
        adapter.submitIncomparableList(items)
        res.scrollToPosition(storingIndex)
    }

    private fun Slider.setValueRounded(value: Float) {
        this.value = (value.coerceIn(this.valueFrom, this.valueTo) / this.stepSize).roundToInt()
            .toFloat() * this.stepSize
    }

    @SuppressLint("SetTextI18n")
    fun show() {
        val bottomSheetDialog = BottomSheetDialog(activity)

        val binding = ReadBottomSettingsBinding.inflate(activity.layoutInflater, null, false)
        bottomSheetDialog.setContentView(binding.root)
        // max width + expanded on wide/short windows (foldable inner screen, landscape)
        bottomSheetDialog.adaptToWindow()
        if (tabletopLowerHeight() > 0) {
            // tabletop: the sheet lives below the hinge
            bottomSheetDialog.behavior.maxHeight = tabletopLowerHeight()
            bottomSheetDialog.behavior.skipCollapsed = true
            bottomSheetDialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        //Create and Order tabs
        val tabsToViews = listOf(
            R.string.read_display_settings to binding.tabSettingsContainer,     // Tab 0 — Settings
            R.string.tab_translation to binding.tabTranslationContainer,  // Tab 1 — Translation
            R.string.tts_voice to binding.tabVoiceContainer,        // Tab 2 — Voice
            R.string.read_text_size to binding.tabTextContainer,         // Tab 3 — Text
        )
        tabsToViews.forEachIndexed { index, (resId, view) ->
            binding.readSettingsTabs.addTab(binding.readSettingsTabs.newTab().setText(resId))
            view.isVisible = index == 0
        }

        // Show specific tab
        binding.readSettingsTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                tabsToViews.forEachIndexed { index, (_, view) ->
                    view.isVisible = index == tab.position
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        fun readingModeName(mode: Int) =
            if (mode == READING_MODE_PAGES) R.string.reading_mode_pages else R.string.reading_mode_scroll
        binding.readReadingMode.setText(readingModeName(viewModel.readingMode))
        // the scroll types (infinite, buttons, overscroll) only apply to the scroll view
        binding.readReadingType.isEnabled = !viewModel.pagedMode
        binding.readReadingMode.setOnClickListener {
            it.popupMenu(
                items = listOf(
                    READING_MODE_SCROLL to R.string.reading_mode_scroll,
                    READING_MODE_PAGES to R.string.reading_mode_pages
                ),
                selectedItemId = viewModel.readingMode
            ) {
                binding.readReadingMode.setText(readingModeName(itemId))
                viewModel.readingMode = itemId
                binding.readReadingType.isEnabled = !viewModel.pagedMode
            }
        }

        binding.readReadingType.setText(viewModel.readerType.stringRes)
        binding.readReadingType.setOnLongClickListener {
            it.popupMenu(items = listOf(1 to R.string.reset_value), selectedItemId = null) {
                if (itemId == 1) {
                    binding.readReadingType.setText(ReadingType.DEFAULT.stringRes)
                    viewModel.readerType = ReadingType.DEFAULT
                }
            }
            return@setOnLongClickListener true
        }
        binding.readReadingType.setOnClickListener {
            it.popupMenu(
                items = ReadingType.entries.map { v -> v.prefValue to v.stringRes },
                selectedItemId = viewModel.readerType.prefValue
            ) {
                val set = ReadingType.fromSpinner(itemId)
                binding.readReadingType.setText(set.stringRes)
                viewModel.readerType = set
            }
        }

        binding.readSettingsTextSizeText.setOnClickListener {
            it.popupMenu(items = listOf(1 to R.string.reset_value), selectedItemId = null) {
                if (itemId == 1) {
                    viewModel.textSize = DEF_FONT_SIZE
                    binding.readSettingsTextSize.setValueRounded(
                        DEF_FONT_SIZE.toFloat()
                    )
                }
            }
        }

        binding.readSettingsTextSize.apply {
            valueTo = 30.0f
            valueFrom = 10.0f
            setValueRounded((viewModel.textSize).toFloat())
            addOnChangeListener { slider, value, fromUser ->
                viewModel.textSize = value.roundToInt()
            }
        }

        binding.readSettingsTtsPitchText.setOnClickListener {
            it.popupMenu(
                items = listOf(1 to R.string.reset_value),
                selectedItemId = null
            ) {
                if (itemId == 1) {
                    viewModel.ttsPitch = 1.0f
                    binding.readSettingsTtsPitch.setValueRounded(viewModel.ttsPitch)
                }
            }
        }

        binding.readSettingsTtsSpeedText.setOnClickListener {
            it.popupMenu(
                items = listOf(1 to R.string.reset_value),
                selectedItemId = null
            ) {
                if (itemId == 1) {
                    viewModel.ttsSpeed = 1.0f
                    binding.readSettingsTtsSpeed.setValueRounded(viewModel.ttsSpeed)
                }
            }
        }

        binding.readSettingsTextVerticalPaddingText.setOnClickListener {
            it.popupMenu(
                items = listOf(1 to R.string.reset_value),
                selectedItemId = null
            ) {
                if (itemId == 1) {
                    viewModel.textVerticalPadding = 7.5f
                    binding.readSettingsTextVerticalPadding.setValueRounded(viewModel.textVerticalPadding)
                }
            }
        }

        binding.readSettingsTtsPitch.apply {
            setValueRounded(viewModel.ttsPitch)
            addOnChangeListener { slider, value, fromUser ->
                viewModel.ttsPitch = value
            }
        }

        binding.readSettingsTtsSpeed.apply {
            setValueRounded(viewModel.ttsSpeed)
            addOnChangeListener { slider, value, fromUser ->
                viewModel.ttsSpeed = value
            }
        }

        binding.readSettingsTextPaddingText.setOnClickListener {
            it.popupMenu(
                items = listOf(1 to R.string.reset_value),
                selectedItemId = null
            ) {
                if (itemId == 1) {
                    viewModel.paddingHorizontal = DEF_HORIZONTAL_PAD
                    binding.readSettingsTextPadding.setValueRounded(DEF_HORIZONTAL_PAD.toFloat())
                }
            }
        }

        binding.readSettingsTextPaddingTextTop.setOnClickListener {
            it.popupMenu(
                items = listOf(1 to R.string.reset_value),
                selectedItemId = null
            ) {
                if (itemId == 1) {
                    viewModel.paddingVertical = DEF_VERTICAL_PAD
                    binding.readSettingsTextPaddingTop.setValueRounded(DEF_VERTICAL_PAD.toFloat())
                }
            }
        }

        binding.readSettingsTextPadding.apply {
            valueTo = 50.0f
            setValueRounded(viewModel.paddingHorizontal.toFloat())
            addOnChangeListener { slider, value, fromUser ->
                viewModel.paddingHorizontal = value.roundToInt()
            }
        }

        binding.readSettingsTextMaxWidth.apply {
            fun label(value: Int) {
                binding.readSettingsTextMaxWidthText.text = activity.getString(
                    R.string.text_max_width_value,
                    activity.getString(R.string.text_max_width),
                    if (value <= 0) activity.getString(R.string.no_limit)
                    else activity.getString(R.string.text_max_width_dp, value)
                )
            }
            // right end of the slider = no limit (stored as 0)
            setValueRounded(
                viewModel.maxTextWidth.takeIf { it > 0 }?.toFloat() ?: valueTo
            )
            label(viewModel.maxTextWidth)
            setLabelFormatter { value ->
                if (value >= valueTo) activity.getString(R.string.no_limit) else "${value.roundToInt()}"
            }
            addOnChangeListener { slider, value, fromUser ->
                val newWidth = if (value >= slider.valueTo) 0 else value.roundToInt()
                viewModel.maxTextWidth = newWidth
                label(newWidth)
            }
        }

        binding.readSettingsTextMaxWidthText.setOnClickListener {
            it.popupMenu(
                items = listOf(1 to R.string.reset_value),
                selectedItemId = null
            ) {
                if (itemId == 1) {
                    binding.readSettingsTextMaxWidth.setValueRounded(DEF_MAX_TEXT_WIDTH.toFloat())
                }
            }
        }

        binding.readSettingsTextPaddingTop.apply {
            valueTo = 50.0f
            setValueRounded(viewModel.paddingVertical.toFloat())
            addOnChangeListener { slider, value, fromUser ->
                viewModel.paddingVertical = value.roundToInt()
            }
        }

        binding.readSettingsTextVerticalPadding.apply {
            setValueRounded(viewModel.textVerticalPadding)
            addOnChangeListener { slider, value, fromUser ->
                viewModel.textVerticalPadding = value
            }
        }


        binding.readShowFonts.apply {
            //text = UIHelper.parseFontFileName(getKey(EPUB_FONT))
            setOnClickListener {
                showFonts()
            }
        }

        binding.readSettingsTextFontText.setOnClickListener {
            it.popupMenu(items = listOf(1 to R.string.reset_value), selectedItemId = null) {
                if (itemId == 1) {
                    viewModel.textFont = ""
                }
            }
        }

        binding.readMlTo.setOnClickListener { view ->
            if (view == null) return@setOnClickListener
            val context = view.context

            val items = ReadActivityViewModel.MLSettings.mapList

            context.showDialog(
                items.map { (key,value) ->
                    "${SubtitleHelper.getFlagFromIso(key)} $value"
                },
                items.map { it.first }.indexOf(viewModel.mlToLanguage),
                context.getString(R.string.translate_to), false, {}
            ) { index ->
                viewModel.mlToLanguage = items[index].first
                binding.readMlTo.text =
                    ReadActivityViewModel.MLSettings.fromShortToDisplay(viewModel.mlToLanguage)
            }
        }

        binding.readOnlineTranslationSwitch.isChecked = viewModel.currentAgent != TranslatorAgents.OFFLINE
        binding.readOnlineTranslationSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.mlTranslationAgent = if (isChecked) TranslatorAgents.ONLINE.name
            else TranslatorAgents.OFFLINE.name

            //Do not allow automatic detection of the target language; the user should know that themselves (they should know the name of their own language).
            //It could probably be automated, but I have no idea.
            if (isChecked == false && viewModel.mlFromLanguage == AUTO_LANG) {
                viewModel.mlFromLanguage = "en"
                binding.readMlFrom.text = ReadActivityViewModel.MLSettings.fromShortToDisplay("en")
            }
        }

        binding.readMlFrom.setOnClickListener { view ->
            if (view == null) return@setOnClickListener
            val context = view.context

            val items = if (viewModel.currentAgent != TranslatorAgents.OFFLINE) {
                ReadActivityViewModel.MLSettings.mapOnlineList
            } else {
                ReadActivityViewModel.MLSettings.mapList
            }


            context.showDialog(
                items.map { (key,value) ->
                    "${SubtitleHelper.getFlagFromIso(key)} $value"
                },
                items.map { item -> item.first }.indexOf(viewModel.mlFromLanguage),
                context.getString(R.string.translate_from), false, {}
            ) { index ->
                viewModel.mlFromLanguage = items[index].first
                binding.readMlFrom.text =
                    ReadActivityViewModel.MLSettings.fromShortToDisplay(viewModel.mlFromLanguage)
            }
        }

        binding.readApplyTranslation.setOnClickListener { view ->
            if (view == null) return@setOnClickListener
            ioSafe {
                try {
                    if (!viewModel.requireMLDownload()) {
                        viewModel.applyMLSettings(false)
                        activity.runOnUiThread { bottomSheetDialog.dismiss() }
                    } else {
                        activity.runOnUiThread {
                            AlertDialog.Builder(view.context, R.style.AlertDialogCustom)
                                .setTitle(R.string.download_ml)
                                .setMessage(R.string.download_ml_long)
                                .setPositiveButton(R.string.download) { _, _ ->
                                    viewModel.applyMLSettings(true)
                                    bottomSheetDialog.dismiss()
                                }
                                .setNegativeButton(R.string.cancel, null)
                                .show()
                        }
                    }
                } catch (t: Throwable) {
                    showToast(t.message ?: t.toString())
                }
            }
        }

        binding.readMlTo.text =
            ReadActivityViewModel.MLSettings.fromShortToDisplay(viewModel.mlToLanguage)
        binding.readMlFrom.text =
            ReadActivityViewModel.MLSettings.fromShortToDisplay(viewModel.mlFromLanguage)

        val mlSettings = viewModel.mlSettings

        if (mlSettings.isInvalid()) {
            binding.readMlTitle.setText(R.string.google_translate)
        } else {
            binding.readMlTitle.text =
                "${binding.readMlTitle.context.getString(R.string.google_translate)} (${mlSettings.fromDisplay} -> ${mlSettings.toDisplay})"
        }

        binding.readLanguage.setOnClickListener { _ ->
            ioSafe {
                val tts = viewModel.ttsSession?.requireTTS() ?: return@ioSafe

                activity.runOnUiThread {
                    val languages = mutableListOf<Locale?>(null).apply {
                        addAll(tts.availableLanguages?.filterNotNull() ?: emptySet())
                    }
                    val ctx = binding.readLanguage.context ?: return@runOnUiThread
                    ctx.showDialog(
                        languages.map {
                            it?.displayName ?: ctx.getString(R.string.default_text)
                        },
                        languages.indexOf(tts.voice?.locale),
                        ctx.getString(R.string.tts_locale), false, {}
                    ) { index ->
                        viewModel.setTTSLanguage(languages.getOrNull(index))
                    }
                }
            }
        }

        binding.readLanguage.setOnLongClickListener {
            it.popupMenu(items = listOf(1 to R.string.reset_value), selectedItemId = null) {
                if (itemId == 1) {
                    viewModel.setTTSLanguage(null)
                }
            }

            return@setOnLongClickListener true
        }

        binding.readVoice.setOnLongClickListener {
            it.popupMenu(items = listOf(1 to R.string.reset_value), selectedItemId = null) {
                if (itemId == 1) {
                    viewModel.setTTSVoice(null)
                }
            }

            return@setOnLongClickListener true
        }

        binding.readSleepTimer.setOnClickListener { view ->
            if (view == null) return@setOnClickListener
            val context = view.context

            val items =
                mutableListOf(
                    context.getString(R.string.default_text) to 0L,
                )

            for (i in 1L..120L) {
                items.add(
                    context.getString(R.string.sleep_format).format(i.toInt()) to (i * 60000L)
                )
            }

            context.showDialog(
                items.map {
                    it.first
                },
                items.map { it.second }.indexOf(viewModel.ttsTimer),
                context.getString(R.string.sleep_timer), false, {}
            ) { index ->
                viewModel.ttsTimer = items[index].second
            }
        }

        binding.readVoice.setOnClickListener {
            ioSafe {
                val tts = viewModel.ttsSession?.requireTTS() ?: return@ioSafe

                activity.runOnUiThread {
                    val matchAgainst = tts.voice.locale
                    val ctx = binding.readLanguage.context ?: return@runOnUiThread
                    val voices =
                        mutableListOf<Pair<String, Voice?>>(ctx.getString(R.string.default_text) to null).apply {
                            val voices =
                                tts.voices.filter { it != null && it.locale == matchAgainst }
                                    .map {
                                        // ${"★".repeat(it.quality / 100) }
                                        ("${it.name} ${
                                            if (it.isNetworkConnectionRequired) {
                                                "(☁)"
                                            } else {
                                                ""
                                            }
                                        }") to it
                                    }

                            addAll(voices.sortedBy { (name, _) -> name })
                        }

                    ctx.showDialog(
                        voices.map { it.first },
                        voices.map { it.second }.indexOf(tts.voice),
                        ctx.getString(R.string.tts_locale), false, {}
                    ) { index ->
                        val voice = voices.getOrNull(index)?.second
                        viewModel.setTTSVoice(voice)
                    }
                }
            }
        }

        binding.apply {
            hardResetStream.isVisible = viewModel.canReload()
            hardResetStream.setOnClickListener {
                showToast(activity.getString(R.string.reload_chapter_format).format(""))
                viewModel.reloadChapter()
            }

            readSettingsScrollVol.isChecked = viewModel.scrollWithVolume
            readSettingsScrollVol.setOnCheckedChangeListener { _, isChecked ->
                viewModel.scrollWithVolume = isChecked
            }

            readSettingsAuthorNotes.isChecked = viewModel.authorNotes
            readSettingsAuthorNotes.setOnCheckedChangeListener { _, isChecked ->
                viewModel.authorNotes = isChecked
                viewModel.refreshChapters()
            }

            readSettingsShowBionic.isChecked = viewModel.bionicReading
            readSettingsShowBionic.setOnCheckedChangeListener { _, isChecked ->
                viewModel.bionicReading = isChecked
            }

            readSettingsIsTextSelectable.isChecked = viewModel.isTextSelectable
            readSettingsIsTextSelectable.setOnCheckedChangeListener { _, isChecked ->
                viewModel.isTextSelectable = isChecked
            }

            readSettingsLockTts.isChecked = viewModel.ttsLock
            readSettingsLockTts.setOnCheckedChangeListener { _, isChecked ->
                viewModel.ttsLock = isChecked
            }

            readSettingsShowTime.isChecked = viewModel.showTime
            readSettingsShowTime.setOnCheckedChangeListener { _, isChecked ->
                viewModel.showTime = isChecked
            }

            //readSettingsTwelveHourTime.isChecked = viewModel.time12H
            //readSettingsTwelveHourTime.setOnCheckedChangeListener { _, isChecked ->
            //    viewModel.time12H = isChecked
            //}

            readSettingsShowBattery.isChecked = viewModel.showBattery
            readSettingsShowBattery.setOnCheckedChangeListener { _, isChecked ->
                viewModel.showBattery = isChecked
            }

            readSettingsKeepScreenActive.isChecked = viewModel.screenAwake
            readSettingsKeepScreenActive.setOnCheckedChangeListener { _, isChecked ->
                viewModel.screenAwake = isChecked
            }

        }

        val bgColors = activity.resources.getIntArray(R.array.readerBgColors)
        val textColors = activity.resources.getIntArray(R.array.readerTextColors)

        imageHolder = binding.readSettingsColors
        for ((newBgColor, newTextColor) in bgColors zip textColors) {
            ColorRoundCheckmarkBinding.inflate(
                activity.layoutInflater,
                binding.readSettingsColors,
                true
            ).image1.apply {
                backgroundTintList = ColorStateList.valueOf(newBgColor)
                //foregroundTintList = ColorStateList.valueOf(newTextColor)
                setOnClickListener {
                    viewModel.backgroundColor = newBgColor
                    viewModel.textColor = newTextColor
                    updateImages()
                }
            }
        }

        ColorRoundCheckmarkBinding.inflate(
            activity.layoutInflater,
            binding.readSettingsColors,
            true
        ).image1.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                foreground =
                    ContextCompat.getDrawable(this.context, R.drawable.ic_baseline_add_24)
            }

            setOnClickListener {
                val builder: AlertDialog.Builder = AlertDialog.Builder(this.context)
                builder.setTitle(activity.getString(R.string.reading_color))

                val colorAdapter =
                    ArrayAdapter<String>(this.context, R.layout.chapter_select_dialog)
                val array = arrayListOf(
                    activity.getString(R.string.background_color),
                    activity.getString(R.string.text_color)
                )
                colorAdapter.addAll(array)

                builder.setPositiveButton(R.string.ok) { dialog, _ ->
                    dialog.dismiss()
                    updateImages()
                }

                builder.setAdapter(colorAdapter) { _, which ->
                    ColorPickerDialog.newBuilder()
                        .setDialogId(which)
                        .setColor(
                            when (which) {
                                0 -> viewModel.backgroundColor
                                1 -> viewModel.textColor
                                else -> 0
                            }
                        )
                        .show(activity)
                }

                builder.show()
                updateImages()
            }
        }
        updateImages()

        bottomSheetDialog.show()    }
}
