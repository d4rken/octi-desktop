package eu.darken.octi.desktop.protocol.octiserver

/**
 * Identification headers sent on every authenticated REST request and on the WebSocket upgrade.
 * The server records these on auth/touch — without them, peer cards show poor labels/platforms.
 *
 * @property version Desktop build version string, e.g. `"0.0.1-dev"`.
 * @property platform Wire string identifying the OS family — `"desktop-linux"`, `"desktop-macos"`, `"desktop-windows"`.
 * @property label Human-readable label, e.g. the hostname or a user-chosen name from Settings.
 */
data class DeviceMetadata(
    val version: String,
    val platform: String,
    val label: String,
) {
    companion object {
        const val HEADER_VERSION = "Octi-Device-Version"
        const val HEADER_PLATFORM = "Octi-Device-Platform"
        const val HEADER_LABEL = "Octi-Device-Label"
        const val HEADER_DEVICE_ID = "X-Device-ID"
    }
}
