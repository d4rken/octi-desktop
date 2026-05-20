package eu.darken.octi.desktop.storage

import eu.darken.octi.desktop.protocol.module.ModuleIds
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import eu.darken.octi.desktop.protocol.sync.ConnectorType
import eu.darken.octi.desktop.ui.dashboard.layout.TileLayoutConfig
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * SettingsData JSON contract — verifies that adding `tileLayouts` and `defaultTileLayout` to
 * the schema doesn't break the load path for existing installs (which have JSON without those
 * fields) and that the new fields round-trip correctly.
 *
 * No `SCHEMA_VERSION` bump was needed because `ignoreUnknownKeys = true` already tolerates
 * missing/extra fields. These tests pin that behavior so a future refactor can't quietly
 * remove the laxity. Uses [Settings.defaultJson] so test config tracks production config.
 */
class SettingsDataTest {

    private val json = Settings.defaultJson

    @Test
    fun `legacy JSON without tile fields decodes with default empty layouts`() {
        val legacy = """
            {
              "schemaVersion": 1,
              "deviceId": "abc",
              "deviceLabel": "Laptop",
              "themeMode": "SYSTEM",
              "syncIntervalSeconds": 300,
              "clipboardAutoSync": false
            }
        """.trimIndent()
        val data = json.decodeFromString(SettingsData.serializer(), legacy)
        data.deviceId shouldBe "abc"
        data.tileLayouts shouldBe emptyMap()
        data.defaultTileLayout shouldBe TileLayoutConfig()
    }

    @Test
    fun `fresh JSON with tile fields round-trips`() {
        val original = SettingsData(
            deviceId = "abc",
            tileLayouts = mapOf(
                "peer-1" to TileLayoutConfig(
                    order = listOf(ModuleIds.WIFI.id, ModuleIds.POWER.id),
                    wideModules = setOf(ModuleIds.WIFI.id),
                ),
            ),
            defaultTileLayout = TileLayoutConfig(
                wideModules = setOf(ModuleIds.META.id),
            ),
        )
        val encoded = json.encodeToString(SettingsData.serializer(), original)
        val decoded = json.decodeFromString(SettingsData.serializer(), encoded)
        decoded shouldBe original
    }

    @Test
    fun `unknown extra fields don't break decode`() {
        val withGhosts = """
            {
              "schemaVersion": 1,
              "deviceId": "abc",
              "ghostField": "from the future",
              "tileLayouts": {},
              "futureNested": { "x": 1 }
            }
        """.trimIndent()
        val data = json.decodeFromString(SettingsData.serializer(), withGhosts)
        data.deviceId shouldBe "abc"
        data.tileLayouts shouldBe emptyMap()
    }

    @Test
    fun `empty tileLayouts is encoded explicitly when encodeDefaults is on`() {
        val data = SettingsData(deviceId = "abc")
        val encoded = json.encodeToString(SettingsData.serializer(), data)
        // The 'tileLayouts' key must be present so a future read-modify-write doesn't drop it
        // (encodeDefaults = true is the file's defensive default).
        encoded.contains("\"tileLayouts\"") shouldBe true
        encoded.contains("\"defaultTileLayout\"") shouldBe true
        // 'connectors' is the discovery index for CredentialsStore — must always serialize so a
        // future read-modify-write of an unrelated field doesn't drop the configured connectors.
        encoded.contains("\"connectors\"") shouldBe true
    }

    @Test
    fun `connectors map round-trips and is keyed by ConnectorId idString`() {
        val connectorId = ConnectorId(
            type = ConnectorType.OCTISERVER,
            subtype = "prod.kserver.octi.darken.eu",
            account = "11111111-2222-3333-4444-555555555555",
        )
        val original = SettingsData(
            deviceId = "abc",
            connectors = mapOf(connectorId.idString to ConnectorConfig(connectorId = connectorId)),
        )
        val encoded = json.encodeToString(SettingsData.serializer(), original)
        // The opaque idString is the map key — assert it's present verbatim so a future change
        // to ConnectorId.idString shape is visible here.
        encoded.contains("\"kserver-prod.kserver.octi.darken.eu-11111111-2222-3333-4444-555555555555\"") shouldBe true
        val decoded = json.decodeFromString(SettingsData.serializer(), encoded)
        decoded shouldBe original
    }

    @Test
    fun `ConnectorConfig defaults paused to false`() {
        val connectorId = ConnectorId(
            type = ConnectorType.OCTISERVER,
            subtype = "prod.kserver.octi.darken.eu",
            account = "abc",
        )
        ConnectorConfig(connectorId = connectorId).paused shouldBe false
    }

    @Test
    fun `legacy ConnectorConfig JSON without paused field decodes to paused=false`() {
        // Existing installs (and the multi-connector-ready-shapes PR's released format) never
        // wrote `paused`. The new field must default to false on read so users who linked under
        // the old format don't suddenly find every connector "paused" after upgrading.
        val legacy = """
            {
              "schemaVersion": 2,
              "deviceId": "abc",
              "connectors": {
                "kserver-host-xyz": {
                  "connectorId": {
                    "type": "kserver",
                    "subtype": "host",
                    "account": "xyz"
                  }
                }
              }
            }
        """.trimIndent()
        val data = json.decodeFromString(SettingsData.serializer(), legacy)
        val cfg = data.connectors["kserver-host-xyz"]!!
        cfg.paused shouldBe false
    }

    @Test
    fun `paused=true round-trips`() {
        val connectorId = ConnectorId(
            type = ConnectorType.OCTISERVER,
            subtype = "prod.kserver.octi.darken.eu",
            account = "abc",
        )
        val original = SettingsData(
            deviceId = "dev",
            connectors = mapOf(
                connectorId.idString to ConnectorConfig(connectorId = connectorId, paused = true),
            ),
        )
        val encoded = json.encodeToString(SettingsData.serializer(), original)
        // encodeDefaults = true → false values also emit; pin both states are explicit on disk.
        // Note: defaultJson uses prettyPrint=true, so the formatting is `"paused": true`.
        encoded.contains("\"paused\": true") shouldBe true
        val decoded = json.decodeFromString(SettingsData.serializer(), encoded)
        decoded shouldBe original
    }
}
