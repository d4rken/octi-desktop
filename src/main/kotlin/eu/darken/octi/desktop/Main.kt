package eu.darken.octi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.ui.LocalAppGraph
import eu.darken.octi.desktop.ui.clipboard.ClipboardScreen
import eu.darken.octi.desktop.ui.dashboard.DashboardScreen
import eu.darken.octi.desktop.ui.device.DeviceDetailScreen
import eu.darken.octi.desktop.ui.linking.LinkingScreen
import eu.darken.octi.desktop.ui.nav.Screen
import eu.darken.octi.desktop.ui.settings.SettingsScreen
import eu.darken.octi.desktop.ui.theme.OctiTheme

private val TAG = logTag("Main")

fun main() = application {
    val graph = AppGraph.create(
        passphrasePrompt = {
            // TODO Phase A3 follow-up: real passphrase dialog. For MVP boot on hosts with a
            // working OS keystore (macOS/Windows/most Linux desktops), this lambda is never
            // invoked. Headless Linux without libsecret currently fails fast — surfaces as a
            // friendly error in a later phase.
            error("Passphrase fallback not yet wired with a UI prompt")
        },
    )
    log(TAG) { "Octi Desktop ready (deviceId=${graph.deviceId.logLabel})" }
    graph.webSocketClient.start()
    graph.metaWriter.start()
    graph.clipboardSync.start()

    Window(
        onCloseRequest = ::exitApplication,
        title = "Octi",
        state = rememberWindowState(width = 1024.dp, height = 720.dp),
    ) {
        CompositionLocalProvider(LocalAppGraph provides graph) {
            OctiDesktopApp()
        }
    }
}

@Composable
private fun OctiDesktopApp() {
    val graph = LocalAppGraph.current
    val settings by graph.settings.flow.collectAsState()
    OctiTheme(themeMode = settings.themeMode) {
        Surface(modifier = Modifier.fillMaxSize()) {
            val current by graph.navigator.current.collectAsState()
            ScreenRouter(screen = current)
        }
    }
}

@Composable
private fun ScreenRouter(screen: Screen) {
    when (screen) {
        Screen.Linking -> LinkingScreen()
        Screen.Dashboard -> DashboardScreen()
        is Screen.DeviceDetail -> DeviceDetailScreen(deviceId = screen.deviceId)
        Screen.Clipboard -> ClipboardScreen()
        Screen.Settings -> SettingsScreen()
        is Screen.Files -> {
            // Files screen lands in Phase G (blob R/W).
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Files screen — Phase G")
            }
        }
    }
}
