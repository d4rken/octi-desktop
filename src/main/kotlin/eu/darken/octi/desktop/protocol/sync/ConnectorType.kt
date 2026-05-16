package eu.darken.octi.desktop.protocol.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire enum identifying which sync backend a peer uses. Wire-spelling note: the OctiServer value
 * is `"kserver"` on the wire — historical name from when the project called the server "kserver".
 * Must be preserved exactly or the server rejects the device.
 */
@Serializable
enum class ConnectorType(val typeId: String) {
    @SerialName("gdrive") GDRIVE("gdrive"),
    @SerialName("kserver") OCTISERVER("kserver"),
}
