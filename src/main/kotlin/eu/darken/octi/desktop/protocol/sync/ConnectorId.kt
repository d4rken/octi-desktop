package eu.darken.octi.desktop.protocol.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Identifies a specific configured sync connector (e.g. one OctiServer account on one URL). */
@Serializable
data class ConnectorId(
    @SerialName("type") val type: ConnectorType,
    @SerialName("subtype") val subtype: String,
    @SerialName("account") val account: String,
) {

    val idString: String
        get() = "${type.typeId}-$subtype-$account"

    val logLabel: String
        get() = "$type:$subtype"
}
