package eu.darken.octi.desktop.protocol.module

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModuleId(@SerialName("id") val id: String) {

    val logLabel: String
        get() = id.removePrefix("eu.darken.octi.module.core.")
}
