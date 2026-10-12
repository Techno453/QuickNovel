package com.lagradost.quicknovel.ui.reader

import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.ReadActivityViewModel
import com.lagradost.quicknovel.TTSHelper
import com.lagradost.quicknovel.TTSNotifications.TTS_NOTIFICATION_ID
import com.lagradost.quicknovel.databinding.ReadMainBinding
import com.lagradost.quicknovel.mvvm.observe
import com.lagradost.quicknovel.mvvm.observeNullable
import com.lagradost.quicknovel.util.divCeil

/**
 * TTS controls in the reader: the play button in the bottom bar, the TTS bar (back, stop,
 * play/pause, forward) that replaces it while reading aloud, the sleep-timer label, media/headset
 * actions, and forwarding the line being read to the text display ([onLine]).
 */
class ReaderTtsController(
    private val activity: AppCompatActivity,
    private val binding: ReadMainBinding,
    private val viewModel: ReadActivityViewModel,
    private val onLine: (TTSHelper.TTSLine?) -> Unit,
) {
    fun bind() {
        binding.ttsActionPausePlay.setOnClickListener {
            viewModel.pausePlayTTS()
        }

        binding.ttsActionStop.setOnClickListener {
            viewModel.stopTTS()
        }

        binding.readActionTts.setOnClickListener {
            viewModel.startTTS()
        }

        binding.ttsActionForward.setOnClickListener {
            viewModel.forwardsTTS()
        }

        binding.ttsActionBack.setOnClickListener {
            viewModel.backwardsTTS()
        }

        activity.observeNullable(viewModel.ttsLine) { line ->
            onLine(line)
        }

        activity.observe(viewModel.ttsStatus) { status ->
            val isTTSRunning = status != TTSHelper.TTSStatus.IsStopped

            binding.readerBottomView.isGone = isTTSRunning
            binding.readerBottomViewTts.isVisible = isTTSRunning
            binding.ttsActionPausePlay.setImageResource(
                when (status) {
                    TTSHelper.TTSStatus.IsPaused -> R.drawable.ic_baseline_play_arrow_24
                    TTSHelper.TTSStatus.IsRunning -> R.drawable.ic_baseline_pause_24
                    TTSHelper.TTSStatus.IsStopped -> R.drawable.ic_baseline_play_arrow_24
                }
            )
        }

        activity.observeNullable(viewModel.ttsTimeRemaining) { time ->
            if (time == null) {
                binding.ttsStopTime.isVisible = false
            } else {
                binding.ttsStopTime.isVisible = true
                binding.ttsStopTime.text =
                    binding.ttsStopTime.context.getString(R.string.sleep_format_stop)
                        .format(time.divCeil(60_000L))
            }
        }
    }

    /** Media button / headset / notification action. */
    fun parseAction(input: TTSHelper.TTSActionType): Boolean = viewModel.parseAction(input)

    /** Volume keys skip lines while TTS is running. @return true if handled */
    fun onVolumeKey(down: Boolean): Boolean {
        if (!viewModel.isTTSRunning()) return false
        if (down) viewModel.forwardsTTS() else viewModel.backwardsTTS()
        return true
    }

    /** Leaving the reader with back: removes the TTS notification. */
    fun cancelNotification() {
        with(NotificationManagerCompat.from(activity)) {
            cancel(TTS_NOTIFICATION_ID)
        }
    }

    fun onDestroy() {
        viewModel.stopTTS()
    }
}
