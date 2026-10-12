package com.mihon.presentation.settings

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component1
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component2
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.lagradost.quicknovel.compose.centeredGutter
import com.mihon.material.AppBar
import com.mihon.material.Scaffold

@Composable
fun PreferenceScaffold(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    onBackPressed: (() -> Unit)? = null,
    itemsProvider: @Composable () -> List<Preference>,
) {
    val (top, bottom) = remember { FocusRequester.createRefs() }

    Scaffold(
        // @WindowInsets remove this when we have converted everything to compose
        //contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AppBar(
                modifier = Modifier.focusRequester(top).focusProperties {
                    down = bottom
                },
                title = title,
                navigateUp = onBackPressed,
                actions = actions,
                scrollBehavior = it,
            )
        },
        content = { contentPadding ->
            // Centre the list and cap it at MaxContentWidth on wide windows (foldables, tablets),
            // keeping the scrollable area full width.
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val gutter = centeredGutter(0.dp)
                val direction = LocalLayoutDirection.current
                PreferenceScreen(
                    modifier = Modifier.focusRequester(bottom).focusProperties {
                        up = top
                    },
                    items = itemsProvider(),
                    contentPadding = PaddingValues(
                        start = contentPadding.calculateStartPadding(direction) + gutter,
                        top = contentPadding.calculateTopPadding(),
                        end = contentPadding.calculateEndPadding(direction) + gutter,
                        bottom = contentPadding.calculateBottomPadding(),
                    ),
                )
            }
        },
    )
}
