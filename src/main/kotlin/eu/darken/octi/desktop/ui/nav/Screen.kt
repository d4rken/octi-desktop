package eu.darken.octi.desktop.ui.nav

/**
 * Sealed hierarchy of all desktop screens. State-machine navigation: the current screen is held
 * in a [Navigator]'s `MutableStateFlow<Screen>` and Compose recomposes when it changes.
 *
 * Deliberately flat — the desktop UI is shallow (≤10 screens) and a graph-based router would be
 * over-engineering. The Android app uses Navigation3; the value of porting that here is low.
 */
sealed class Screen {

    /** Pre-link onboarding flow. Shown when no credentials exist. */
    data object Linking : Screen()

    /** Grid of all known devices. Default landing screen post-link. */
    data object Dashboard : Screen()

    /** Detailed module view for a single device. */
    data class DeviceDetail(val deviceId: String) : Screen()

    /** File sharing view (list, download, upload) scoped to one peer. */
    data class Files(val deviceId: String) : Screen()

    /** Synced clipboard inspector. */
    data object Clipboard : Screen()

    /** App settings (sync interval, theme, account info, unlink). */
    data object Settings : Screen()
}
