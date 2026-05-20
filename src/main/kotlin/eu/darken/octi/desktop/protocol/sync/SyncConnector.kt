package eu.darken.octi.desktop.protocol.sync

/**
 * Minimal connector abstraction shared by every sync backend (today: OctiServer; future:
 * GDrive). Kept deliberately small — the rich type-specific API (HTTP client, blob ops,
 * credentials, websocket session) stays on each concrete subclass and consumers cast to the
 * concrete type when they need those.
 *
 * Per-connector runtime state (websocket connection, device-list load state, last meta write
 * timestamp) lives in the existing per-connector flows keyed by [identifier]
 * (`OctiServerWebSocketClient.statesByConnector`, `DeviceListRepo.loadStateByConnector`,
 * etc.) — the Settings UI reads those by id rather than going through a fused state flow on
 * this interface. Once a second connector type lands and that pattern proves unwieldy we can
 * promote a [state] flow onto the interface.
 *
 * Implements [AutoCloseable] so AppGraph can tear down a connector uniformly on unlink.
 */
interface SyncConnector : AutoCloseable {

    /** Stable identity used to key per-connector state across the app. */
    val identifier: ConnectorId

    /**
     * Human-readable label shown in the Settings connector card. OctiServer returns the server
     * URL; a future GDrive impl would return the Google account email. Nullable so an
     * unauthenticated / preview connector can still satisfy the interface.
     */
    val accountLabel: String?
}
