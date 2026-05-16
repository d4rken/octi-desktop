package eu.darken.octi.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import eu.darken.octi.desktop.common.log.Logging
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.storage.ThemeMode
import eu.darken.octi.desktop.ui.nav.Navigator
import eu.darken.octi.desktop.ui.nav.Screen
import eu.darken.octi.desktop.ui.theme.OctiTheme

private val TAG = logTag("Main")

fun main() = application {
    Logging.install()
    log(TAG) { "Octi Desktop starting" }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Octi",
        state = rememberWindowState(width = 1024.dp, height = 720.dp),
    ) {
        OctiDesktopApp()
    }
}

@Composable
private fun OctiDesktopApp() {
    val navigator = remember { Navigator() }

    OctiTheme(themeMode = ThemeMode.SYSTEM) {
        Surface(modifier = Modifier.fillMaxSize()) {
            val current by navigator.current.collectAsState()
            ScreenPlaceholder(screen = current, navigator = navigator)
        }
    }
}

@Composable
private fun ScreenPlaceholder(screen: Screen, navigator: Navigator) {
    // Placeholder UI — every real screen will replace its branch in the upcoming UI phases.
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Screen: $screen")
            Button(onClick = { navigator.navigateTo(Screen.Dashboard) }) { Text("Dashboard") }
            Button(onClick = { navigator.navigateTo(Screen.Linking) }) { Text("Linking") }
            Button(onClick = { navigator.navigateTo(Screen.Settings) }) { Text("Settings") }
            Button(onClick = { navigator.pop() }) { Text("Back") }
        }
    }
}
