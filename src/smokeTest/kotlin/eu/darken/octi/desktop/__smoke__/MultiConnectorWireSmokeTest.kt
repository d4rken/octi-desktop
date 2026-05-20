package eu.darken.octi.desktop.__smoke__

import eu.darken.octi.desktop.protocol.octiserver.OctiServerConnector.Companion.toConnectorId
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * Two-server wire smoke for the foundation PR. Exercises the baseline that everything
 * downstream (read merge, write fan-out, blob multi-source) builds on:
 *
 *  1. The same desktop deviceId can register against two independent sync-server instances
 *     without collision.
 *  2. Each server's `/v1/devices` returns the device only in its own account view — no
 *     cross-server bleed.
 *  3. The two registrations produce DIFFERENT [eu.darken.octi.desktop.protocol.sync.ConnectorId]s
 *     (subtype differs because the server hostnames differ, even though the deviceId matches).
 *
 * Skips cleanly without `SMOKE_SERVER_URL_B`. Local devs see only one container by default;
 * CI's code-checks workflow runs two. Richer multi-connector scenarios (offline fallback,
 * blob partial failure, unlink-one-keeps-other) come in later PRs as the corresponding code
 * paths land.
 */
class MultiConnectorWireSmokeTest {

    @Test
    fun `same device registers independently on two servers and is visible from each`() = smokeTest {
        SmokeFixture.withTwoServerAccounts { accountA, accountB ->
            // Self-device must be in each account's device list — confirming each server has
            // the registration. Cross-server isolation is implicit: each call only sees its
            // own server's view.
            val devicesOnA = accountA.client.getDeviceList().devices.map { it.id }
            val devicesOnB = accountB.client.getDeviceList().devices.map { it.id }
            devicesOnA shouldContain accountA.deviceId.id
            devicesOnB shouldContain accountB.deviceId.id

            // Same desktop deviceId across both registrations — this is the property that lets
            // `MergedDevice.mergeDeviceLists` group by `device.id` and produce a single card.
            accountA.deviceId shouldBe accountB.deviceId

            // ConnectorIds differ because subtype = domain and the two servers run on different
            // hosts/ports. Locking this so a future toConnectorId change that drops subtype
            // (and would silently collide two distinct servers into one connector) fails here.
            val connectorIdA = accountA.credentials.toConnectorId()
            val connectorIdB = accountB.credentials.toConnectorId()
            connectorIdA shouldNotBe connectorIdB
            connectorIdA.idString shouldNotBe connectorIdB.idString
        }
    }

    @Test
    fun `deleting account on one server leaves the other intact`() = smokeTest {
        SmokeFixture.withTwoServerAccounts { accountA, accountB ->
            // Delete A's account; B's account must still return our device. The fixture's
            // teardown will try to delete A again — that's an idempotent best-effort call so
            // the resulting 404 is swallowed by its runCatching.
            accountA.client.deleteAccount()
            val devicesOnB = accountB.client.getDeviceList().devices.map { it.id }
            devicesOnB shouldContain accountB.deviceId.id
        }
    }
}
