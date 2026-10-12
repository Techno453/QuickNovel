package com.lagradost.quicknovel.ui.reader

import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.ReadActivityViewModel
import com.lagradost.quicknovel.databinding.ReadMainBinding
import com.lagradost.quicknovel.mvvm.observe
import com.lagradost.quicknovel.ui.OrientationType
import com.lagradost.quicknovel.util.UIHelper.fixPaddingStatusbar
import com.lagradost.quicknovel.util.UIHelper.fixSystemBarsPadding
import com.lagradost.quicknovel.util.UIHelper.getStatusBarHeight
import com.lagradost.quicknovel.util.UIHelper.popupMenu

/**
 * The reader's "chrome": system bars, display cutout, the toolbar and bottom action bar
 * (slid in and out together with the system bars), the clock/battery overlay, keep-screen-on and
 * the rotation lock button.
 */
class ReaderChromeController(
    private val activity: AppCompatActivity,
    private val binding: ReadMainBinding,
    private val viewModel: ReadActivityViewModel,
) {
    /** Toolbar height plus the status bar; the loading/chapter rows use it as a minimum height. */
    var topBarHeight: Int = 0
        private set

    private val batteryReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctxt: Context?, intent: Intent) {
            val batteryPct: Float = run {
                val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                level * 100 / scale.toFloat()
            }
            binding.readBattery.text =
                binding.readBattery.context?.getString(R.string.battery_format)
                    ?.format(batteryPct.toInt())
        }
    }

    /** Insets and the battery receiver; call right after setContentView. */
    fun onCreate() {
        fixSystemBarsPadding(binding.readerBottomViewHolder, padTop = false, overlayCutout = false)
        fixSystemBarsPadding(binding.readToolbarHolder, padBottom = false, overlayCutout = false)
        activity.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    /** Toolbar sizing and the observers; call once the view model is initialised. */
    fun bind() {
        topBarHeight = binding.readToolbarHolder.minimumHeight + activity.getStatusBarHeight()
        binding.readToolbarHolder.minimumHeight = topBarHeight

        binding.readToolbar.apply {
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
            setNavigationOnClickListener {
                activity.onBackPressed()
            }
        }
        activity.fixPaddingStatusbar(binding.readToolbarHolder)

        activity.observe(viewModel.showBatteryLive) { show ->
            binding.readBattery.isVisible = show
            binding.readOverlay.isVisible = show && viewModel.showTime
        }

        activity.observe(viewModel.showTimeLive) { show ->
            binding.readTimeClock.isVisible = show
            binding.readOverlay.isVisible = show && viewModel.showBattery
        }

        activity.observe(viewModel.screenAwakeLive) { awake ->
            if (awake)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        activity.observe(viewModel.orientationLive) { position ->
            val org = OrientationType.fromSpinner(position)
            activity.requestedOrientation = org.flag
            binding.readActionRotate.setImageResource(org.iconRes)

            binding.readActionRotate.apply {
                setOnClickListener {
                    popupMenu(
                        items = OrientationType.entries.map { it.prefValue to it.stringRes },
                        selectedItemId = org.prefValue
                    ) {
                        viewModel.orientation = itemId
                    }
                }
            }
        }
    }

    fun onDestroy() {
        activity.unregisterReceiver(batteryReceiver)
    }

    /** Hides the system bars (immersive, content under the cutout) and slides the bars out. */
    fun hideSystemUI() {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.readerContainer).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        fun lowerBottomNav(v: View) {
            v.translationY = 0f
            ObjectAnimator.ofFloat(v, "translationY", v.height.toFloat()).apply {
                duration = 200
                start()
            }.doOnEnd {
                v.isVisible = false
            }
        }

        lowerBottomNav(binding.readerBottomViewHolder)

        binding.readToolbarHolder.translationY = 0f
        ObjectAnimator.ofFloat(
            binding.readToolbarHolder,
            "translationY",
            -binding.readToolbarHolder.height.toFloat()
        ).apply {
            duration = 200
            start()
        }.doOnEnd {
            binding.readToolbarHolder.isVisible = false
        }
    }

    /** Shows the system bars and slides the toolbar and bottom bar in. */
    fun showSystemUI() {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(
            window,
            binding.readerContainer
        ).show(WindowInsetsCompat.Type.systemBars())

        binding.readToolbarHolder.isVisible = true

        fun higherBottomNavView(v: View) {
            v.isVisible = true
            v.translationY = v.height.toFloat()
            ObjectAnimator.ofFloat(v, "translationY", 0f).apply {
                duration = 200
                start()
            }
        }

        higherBottomNavView(binding.readerBottomViewHolder)

        binding.readToolbarHolder.translationY = -binding.readToolbarHolder.height.toFloat()

        ObjectAnimator.ofFloat(binding.readToolbarHolder, "translationY", 0f).apply {
            duration = 200
            start()
        }
    }
}
