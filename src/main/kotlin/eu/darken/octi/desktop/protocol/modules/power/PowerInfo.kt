package eu.darken.octi.desktop.protocol.modules.power

import eu.darken.octi.desktop.protocol.serialization.serializer.InstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Battery / charging state reported by an Octi peer.
 *
 * Wire-spelling quirk preserved: the Kotlin property is named `currenAvg` (typo), but the wire
 * key is `currentAvg` (correct). Android has the same mismatch — must stay byte-identical.
 *
 * `Status.value` uses Android's [BatteryManager] integer constants. We inline the values so
 * desktop doesn't need an Android dependency:
 * - `BATTERY_STATUS_FULL` = 5
 * - `BATTERY_STATUS_CHARGING` = 2
 * - `BATTERY_STATUS_DISCHARGING` = 3
 * - `BATTERY_STATUS_UNKNOWN` = 1
 */
@Serializable
data class PowerInfo(
    @SerialName("status") val status: Status,
    @SerialName("battery") val battery: Battery,
    @SerialName("chargeIO") val chargeIO: ChargeIO,
) {
    val isCharging: Boolean
        get() = setOf(Status.FULL, Status.CHARGING).contains(status)

    @Serializable
    data class ChargeIO(
        @SerialName("currentNow") val currentNow: Int?,
        @SerialName("currentAvg") val currenAvg: Int?,
        @Serializable(with = InstantSerializer::class) @SerialName("fullSince") val fullSince: Instant?,
        @Serializable(with = InstantSerializer::class) @SerialName("fullAt") val fullAt: Instant?,
        @Serializable(with = InstantSerializer::class) @SerialName("emptyAt") val emptyAt: Instant?,
    ) {
        val speed: Speed
            get() = when {
                currentNow == null -> Speed.NORMAL
                currentNow > 0 -> when {
                    currentNow > (2.5 * 1_000_000) -> Speed.FAST
                    currentNow > (1.0 * 1_000_000) -> Speed.NORMAL
                    else -> Speed.SLOW
                }
                else -> Speed.NORMAL
            }

        @Serializable
        enum class Speed {
            @SerialName("SLOW") SLOW,
            @SerialName("NORMAL") NORMAL,
            @SerialName("FAST") FAST,
        }
    }

    @Serializable
    data class Battery(
        @SerialName("level") val level: Int,
        @SerialName("scale") val scale: Int,
        @SerialName("health") val health: Int?,
        @SerialName("temp") val temp: Float?,
    ) {
        val percent: Float
            get() = level / scale.toFloat()
    }

    @Serializable
    enum class Status(val value: Int) {
        @SerialName("FULL") FULL(5),
        @SerialName("CHARGING") CHARGING(2),
        @SerialName("DISCHARGING") DISCHARGING(3),
        @SerialName("UNKNOWN") UNKNOWN(1),
    }
}
