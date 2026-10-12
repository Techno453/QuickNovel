package com.lagradost.quicknovel.ui.setup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import com.lagradost.cloudstream4.rememberAppSettings
import com.lagradost.cloudstream4.theme.CloudStreamTheme
import com.lagradost.cloudstream4.theme.perfToColor
import com.lagradost.cloudstream4.theme.perfToMode
import com.mihon.presentation.settings.collectAsState

/**
 * Hosts [SetupWizardScreen]: opened by MainActivity on first run and from
 * Settings → AI Translation → "Set up translation…". Returns [Activity.RESULT_OK] when closed.
 */
class SetupActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_FIRST_RUN = "first_run"

        fun intent(context: Context, firstRun: Boolean): Intent =
            Intent(context, SetupActivity::class.java).putExtra(EXTRA_FIRST_RUN, firstRun)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val firstRun = intent?.getBooleanExtra(EXTRA_FIRST_RUN, false) ?: false
        setResult(Activity.RESULT_OK)
        setContent {
            val settings = rememberAppSettings()
            val mode by settings.ui.theme.collectAsState()
            val primaryColor by settings.ui.primaryColor.collectAsState()
            CloudStreamTheme(mode = perfToMode(mode), primaryColor = perfToColor(primaryColor)) {
                SetupWizardScreen(
                    onClose = { finish() },
                    // From Settings the full settings are right below; on first run open the tab.
                    onOpenSettings = {
                        if (firstRun) SetupWizard.openSettings(this) else finish()
                    },
                )
            }
        }
    }
}
