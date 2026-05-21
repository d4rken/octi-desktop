package eu.darken.octi.desktop.ui.files

import eu.darken.octi.desktop.protocol.sync.ConnectorId
import eu.darken.octi.desktop.protocol.sync.RemoteBlobRef

/**
 * Pick + order blob download sources for a [eu.darken.octi.desktop.protocol.modules.files.FileShareInfo.SharedFile].
 *
 * The resulting map preserves iteration order — [eu.darken.octi.desktop.blob.BlobDownloader.download]
 * tries sources in order — and follows two rules:
 *  1. **Drop sources we can't reach**: any `connectorRefs` entry whose key isn't in
 *     [activeConnectorIds] is filtered out. The peer published the blob to a connector we no
 *     longer have linked.
 *  2. **Prefer running over paused**: running connectors are tried first. Paused connectors
 *     are not removed entirely — the blob really is there and pause only silences sync
 *     activity, not blob serving — they're just demoted to last-resort sources.
 *  3. **Deterministic tiebreak**: within each preference bucket, sort by lex-smallest
 *     `idString` so the chosen order is stable across runs and easy to assert on.
 *
 * Pure function — extracted out of [FilesViewModel] so the multi-connector source-routing
 * logic can be unit-tested without standing up an [eu.darken.octi.desktop.di.AppGraph].
 */
internal fun orderBlobDownloadSources(
    connectorRefs: Map<String, RemoteBlobRef>,
    activeConnectorIds: Collection<ConnectorId>,
    runningConnectorIds: Collection<ConnectorId>,
): Map<ConnectorId, String> {
    val activeByIdString = activeConnectorIds.associateBy { it.idString }
    val runningSet = runningConnectorIds.toSet()
    val matched: List<Pair<ConnectorId, RemoteBlobRef>> = connectorRefs.mapNotNull { (idString, ref) ->
        val id = activeByIdString[idString] ?: return@mapNotNull null
        id to ref
    }
    val ordered = matched.sortedWith(
        compareByDescending<Pair<ConnectorId, RemoteBlobRef>> { it.first in runningSet }
            .thenBy { it.first.idString },
    )
    return ordered.associate { (id, ref) -> id to ref.value }
}
