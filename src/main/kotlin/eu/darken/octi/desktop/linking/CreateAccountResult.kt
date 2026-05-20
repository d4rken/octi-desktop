package eu.darken.octi.desktop.linking

/**
 * Outcome of a [LinkController.createAccount] call. Distinct from [LinkResult] because the
 * rollback target differs (`DELETE /v1/account` versus `DELETE /v1/devices/{id}` for the link
 * flow) and the failure space is different (no share-code-expired path; instead a server-side
 * "device already registered" path).
 */
sealed class CreateAccountResult {
    /** Account created on the server and credentials persisted locally. */
    data object Success : CreateAccountResult()

    /**
     * Server returned HTTP 400 "Device is already registered" — this desktop's deviceId is
     * known to the server (a prior link/create wasn't cleaned up). User must clear the orphan
     * before retrying.
     */
    data object DeviceAlreadyRegistered : CreateAccountResult()

    /** Network or other server-side failure during the register call itself. */
    data class NetworkError(val cause: Throwable) : CreateAccountResult()

    /**
     * Server created the account, but persisting the credentials locally failed. The controller
     * has already issued an authenticated `DELETE /v1/account` rollback, so no orphaned account
     * remains on the server.
     */
    data class KeystoreFailureRolledBack(val cause: Throwable) : CreateAccountResult()

    /**
     * Server created the account AND the rollback DELETE itself failed. The account exists on
     * the server with no local credentials to access it. UI must surface this with a manual
     * cleanup affordance ("contact server admin / use another linked device").
     */
    data class OrphanedAccount(
        val keystoreCause: Throwable,
        val rollbackCause: Throwable,
    ) : CreateAccountResult()
}
