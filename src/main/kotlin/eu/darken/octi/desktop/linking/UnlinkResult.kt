package eu.darken.octi.desktop.linking

/**
 * Outcome of [eu.darken.octi.desktop.di.AppGraph.unlink]. Unlinking always calls the server's
 * `DELETE /v1/devices/{self}` first so the deviceId is freed for reuse; local state is only
 * cleared after the server confirms. If the server call fails (offline, etc.) we keep local
 * state untouched and surface [NetworkError] so the user can retry.
 */
sealed class UnlinkResult {
    /** Server confirmed deletion and local credentials were cleared. */
    data object Success : UnlinkResult()

    /**
     * The server call failed (network, 401, 5xx, etc.). Local credentials were NOT cleared —
     * the user can retry when online, or contact an admin to clean up the orphaned device.
     */
    data class NetworkError(val cause: Throwable) : UnlinkResult()

    /**
     * The server confirmed deletion (device is gone server-side) but clearing local credentials
     * failed. Boot-time logic will load the stale credentials again, so the UI must surface this
     * loudly: the user needs to retry the clear (e.g. by restarting the app and unlinking again,
     * or by manually deleting the credentials file). Distinct from [NetworkError] because the
     * server side is already committed.
     */
    data class LocalCleanupFailed(val cause: Throwable) : UnlinkResult()

    /** No credentials were present — there was nothing to unlink. Treated as success by the UI. */
    data object NotLinked : UnlinkResult()
}
