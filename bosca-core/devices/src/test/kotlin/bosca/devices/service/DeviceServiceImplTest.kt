package bosca.devices.service

import bosca.devices.model.Device
import bosca.devices.model.PlatformType
import bosca.devices.model.PushToken
import bosca.devices.model.PushProvider
import bosca.devices.repository.DeviceRepository
import bosca.devices.repository.PushTokenRemoval
import bosca.graphql.Batch
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/**
 * Verifies that [DeviceServiceImpl] correctly delegates all operations to
 * the underlying [DeviceRepository] and properly groups batch results by key.
 */
class DeviceServiceImplTest {

    private val repository = mockk<DeviceRepository>(relaxed = true)
    private val service = DeviceServiceImpl(repository)

    private val principalId1 = Uuid.random()
    private val principalId2 = Uuid.random()
    private val deviceId1 = Uuid.random()
    private val deviceId2 = Uuid.random()

    private val device1 = Device(
        id = deviceId1,
        principalId = principalId1,
        platform = PlatformType.ANDROID,
        installationId = "install-1"
    )
    private val device2 = Device(
        id = deviceId2,
        principalId = principalId2,
        platform = PlatformType.IOS,
        installationId = "install-2"
    )

    @Test
    fun `getById delegates to repository`() = runTest {
        coEvery { repository.getById(deviceId1) } returns device1
        assertEquals(device1, service.getById(deviceId1))
    }

    @Test
    fun `getById returns null for unknown id`() = runTest {
        coEvery { repository.getById(deviceId1) } returns null
        assertNull(service.getById(deviceId1))
    }

    @Test
    fun `getByPrincipal delegates to repository`() = runTest {
        coEvery { repository.getByPrincipal(principalId1) } returns listOf(device1)
        assertEquals(listOf(device1), service.getByPrincipal(principalId1))
    }

    @Test
    fun `register delegates to repository`() = runTest {
        coEvery { repository.register(device1) } returns device1
        assertEquals(device1, service.register(device1))
    }

    @Test
    fun `register requires an Analytics-issued installation id`() = runTest {
        for (installationId in listOf<String?>(null, "", " ")) {
            val error = assertFailsWith<IllegalArgumentException> {
                service.register(device1.copy(installationId = installationId))
            }
            assertEquals("Analytics installation ID is required", error.message)
        }
        coVerify(exactly = 0) { repository.register(any()) }
    }

    @Test
    fun `checkIn delegates to repository`() = runTest {
        coEvery { repository.checkIn(deviceId1) } returns device1
        assertEquals(device1, service.checkIn(deviceId1))
    }

    @Test
    fun `checkIn returns null for unknown device`() = runTest {
        coEvery { repository.checkIn(deviceId1) } returns null
        assertNull(service.checkIn(deviceId1))
    }

    @Test
    fun `installation checkIn delegates to the unique installation id`() = runTest {
        coEvery { repository.checkInByInstallationId("install-1") } returns device1

        assertEquals(device1, service.checkIn("install-1"))
    }

    @Test
    fun `installation checkIn rejects a blank installation id`() = runTest {
        assertFailsWith<IllegalArgumentException> { service.checkIn(" ") }
        coVerify(exactly = 0) { repository.checkInByInstallationId(any()) }
    }

    @Test
    fun `clearPrincipal clears only the requested principal association`() = runTest {
        val principalCleared = device1.copy(principalId = null)
        coEvery { repository.clearPrincipal(deviceId1, principalId1) } returns principalCleared

        assertEquals(principalCleared, service.clearPrincipal(deviceId1, principalId1))
    }

    @Test
    fun `getPushTokens delegates to repository`() = runTest {
        val token = PushToken(deviceId = deviceId1, token = "tok-1")
        coEvery { repository.getPushTokens(deviceId1) } returns listOf(token)
        assertEquals(listOf(token), service.getPushTokens(deviceId1))
    }

    @Test
    fun `addPushToken delegates to repository`() = runTest {
        val token = PushToken(deviceId = deviceId1, token = "tok-2")
        service.addPushToken(token)
        coVerify { repository.addPushToken(token) }
    }

    @Test
    fun `removePushToken delegates to repository`() = runTest {
        service.removePushToken(deviceId1, "tok-2")
        coVerify { repository.removePushToken(deviceId1, "fcm", "tok-2") }

        service.removePushToken(deviceId1, PushProvider.APNS, "apns-token")
        coVerify { repository.removePushToken(deviceId1, "apns", "apns-token") }
    }

    @Test
    fun `removePushTokens deduplicates tokens before repository deletion`() = runTest {
        service.removePushTokens(listOf("tok-1", "tok-2", "tok-1"))
        coVerify { repository.removePushTokens(PushTokenRemoval("fcm", listOf("tok-1", "tok-2"))) }

        service.removePushTokens(PushProvider.APNS, listOf("apns", "apns"))
        coVerify { repository.removePushTokens(PushTokenRemoval("apns", listOf("apns"))) }
    }

    @Test
    fun `removePushTokens skips repository for an empty collection`() = runTest {
        service.removePushTokens(emptyList())
        coVerify(exactly = 0) { repository.removePushTokens(any()) }
    }

    @Test
    fun `delete delegates to repository`() = runTest {
        service.delete(deviceId1)
        coVerify { repository.deleteById(deviceId1) }
    }

    @Test
    fun `addDevicesToBatch groups devices by principalId`() = runTest {
        coEvery { repository.getByPrincipals(listOf(principalId1, principalId2)) } returns listOf(device1, device2)
        val batch = Batch<Uuid, List<Device>>(listOf(principalId1, principalId2))

        service.addDevicesToBatch(batch)

        assertEquals(listOf(device1), batch.getData(principalId1))
        assertEquals(listOf(device2), batch.getData(principalId2))
    }

    @Test
    fun `addDevicesToBatch sets empty list for principals with no devices`() = runTest {
        coEvery { repository.getByPrincipals(listOf(principalId1, principalId2)) } returns listOf(device1)
        val batch = Batch<Uuid, List<Device>>(listOf(principalId1, principalId2))

        service.addDevicesToBatch(batch)

        assertEquals(listOf(device1), batch.getData(principalId1))
        assertEquals(emptyList(), batch.getData(principalId2))
    }

    @Test
    fun `addDevicesToBatch ignores devices without a principal`() = runTest {
        val deviceWithoutPrincipal = device1.copy(principalId = null)
        coEvery { repository.getByPrincipals(listOf(principalId1)) } returns listOf(deviceWithoutPrincipal)
        val batch = Batch<Uuid, List<Device>>(listOf(principalId1))

        service.addDevicesToBatch(batch)

        assertEquals(emptyList(), batch.getData(principalId1))
    }

    @Test
    fun `addPushTokensToBatch groups tokens by deviceId`() = runTest {
        val token1 = PushToken(deviceId = deviceId1, token = "tok-a")
        val token2 = PushToken(deviceId = deviceId2, token = "tok-b")
        coEvery { repository.getPushTokens(listOf(deviceId1, deviceId2)) } returns listOf(token1, token2)
        val batch = Batch<Uuid, List<PushToken>>(listOf(deviceId1, deviceId2))

        service.addPushTokensToBatch(batch)

        assertEquals(listOf(token1), batch.getData(deviceId1))
        assertEquals(listOf(token2), batch.getData(deviceId2))
    }

    @Test
    fun `addPushTokensToBatch sets empty list for devices with no tokens`() = runTest {
        coEvery { repository.getPushTokens(listOf(deviceId1, deviceId2)) } returns emptyList()
        val batch = Batch<Uuid, List<PushToken>>(listOf(deviceId1, deviceId2))

        service.addPushTokensToBatch(batch)

        assertEquals(emptyList(), batch.getData(deviceId1))
        assertEquals(emptyList(), batch.getData(deviceId2))
    }
}
