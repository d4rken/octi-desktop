package eu.darken.octi.desktop.protocol.octiserver

import eu.darken.octi.desktop.protocol.sync.ConnectorId
import eu.darken.octi.desktop.protocol.sync.ConnectorType
import eu.darken.octi.desktop.protocol.sync.DeviceId
import eu.darken.octi.desktop.protocol.sync.SyncConnector

/**
 * Cohesive bundle of everything a consumer needs to talk to one OctiServer account: the
 * authenticated [OctiServerHttpClient], the [credentials] (encryption keyset + accountId for
 * AAD), and the [identifier] derived from them. Lifetime is tied to the credentials — a
 * relink rebuilds a new connector rather than mutating the existing one (the http client
 * doesn't tolerate auth swap).
 *
 * Why a class instead of leaving the trio split across [AppGraph]? Three call sites
 * (ModuleReader, BlobUploader/Downloader, FileShareRepo) used to fetch `activeClient` and
 * `credentialsStore.load()` separately and then assume the two were consistent. With a
 * multi-connector future, the two would diverge silently if the activeConnectors list and the
 * keystore disagreed. Carrying both on the same object makes the per-account boundary
 * explicit.
 *
 * Implements [AutoCloseable] so the http client gets torn down on connector teardown — same
 * lifecycle the old `activeClient.close()` had.
 */
class OctiServerConnector(
    override val identifier: ConnectorId,
    val credentials: OctiServer.Credentials,
    val client: OctiServerHttpClient,
) : SyncConnector {

    /** OctiServer has no email/username — surface the server address so the Settings card has a recognizable line. */
    override val accountLabel: String?
        get() = credentials.serverAdress.address

    override fun close() {
        client.close()
    }

    companion object {

        /**
         * Build a connector for the given credentials. Wires the http client with the same args
         * AppGraph.buildClient used. [deviceMetadata] is captured at build time — relink rebuilds
         * the connector so a label change on the next link picks up the new metadata.
         */
        fun fromCredentials(
            deviceId: DeviceId,
            deviceMetadata: DeviceMetadata,
            credentials: OctiServer.Credentials,
        ): OctiServerConnector {
            val client = OctiServerHttpClient(
                address = credentials.serverAdress,
                deviceId = deviceId,
                deviceMetadata = deviceMetadata,
                credentials = credentials,
            )
            return OctiServerConnector(
                identifier = credentials.toConnectorId(),
                credentials = credentials,
                client = client,
            )
        }

        /**
         * Canonical mapping from [OctiServer.Credentials] to [ConnectorId]. Mirrors the
         * Android side ([app-main OctiServerHub.toConnectorId]) byte-for-byte —
         * `subtype = serverAdress.domain`, `account = accountId.id` — so any cache or settings
         * entry keyed by `idString` stays comparable across platforms (e.g. a future GDrive
         * hash cache that keys on connector id).
         */
        fun OctiServer.Credentials.toConnectorId(): ConnectorId = ConnectorId(
            type = ConnectorType.OCTISERVER,
            subtype = serverAdress.domain,
            account = accountId.id,
        )
    }
}
