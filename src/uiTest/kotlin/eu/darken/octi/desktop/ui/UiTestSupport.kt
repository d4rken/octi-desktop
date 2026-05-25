@file:OptIn(ExperimentalTestApi::class)

package eu.darken.octi.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import eu.darken.octi.desktop.ui.theme.OctiTheme

/**
 * Tiles and screens read `MaterialTheme.colorScheme` / typography and throw without a theme
 * ancestor, so prefer this over a bare `setContent` — it mounts [content] inside [OctiTheme].
 */
fun ComposeUiTest.setOctiContent(content: @Composable () -> Unit) {
    setContent {
        OctiTheme { content() }
    }
}
