package bosca.devices.graphql

import bosca.devices.model.Device
import bosca.devices.model.DeviceInput
import bosca.devices.model.PlatformType
import bosca.devices.model.PushProvider
import bosca.devices.service.DeviceService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/**
 * Validates the authorization and delegation behaviour of [DevicesMutationController].
 *
 * Each mutation requires an authenticated principal whose ID is not [Uuid.NIL].
 * These tests verify that unauthenticated callers, callers with a NIL principal,
 * and callers who do not own the target device are all rejected with the
 * appropriate exception before any service call is made.
 */
class DevicesMutationControllerTest {

    private val deviceService = mockk<DeviceService>()
    private val controller = DevicesMutationController(deviceService)

    private val validPrincipalId = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val otherPrincipalId = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val deviceId = Uuid.parse("00000000-0000-0000-0000-000000000099")

    /**
     * Builds a mock [AuthenticationContext] that returns the given principal, or
     * null when [principalId] is null.
     */
    private fun authContext(principalId: Uuid?): AuthenticationContext {
        val ctx = mockk<AuthenticationContext>()
        if (principalId == null) {
            every { ctx.principal() } returns null
        } else {
            val principal = Principal(id = principalId)
            val authenticatedPrincipal = AuthenticatedPrincipal(
                principal,
                listOf(Group(name = "users", description = "default group", type = GroupType.PRINCIPAL))
            )
            every { ctx.principal() } returns authenticatedPrincipal
        }
        return ctx
    }

    // ---- register ----

    /**
     * Verifies that calling register without any authenticated principal
     * results in a [SecurityException].
     */
    @Test
    fun `register throws SecurityException when principal is null`() = runTest {
        val auth = authContext(null)
        val input = DeviceInput(platform = PlatformType.ANDROID, installationId = "install-1")
        assertFailsWith<SecurityException> {
            controller.register(auth, input)
        }
    }

    /**
     * Verifies that a principal with the NIL UUID is treated as unauthenticated
     * and causes a [SecurityException].
     */
    @Test
    fun `register throws SecurityException when principal ID is NIL`() = runTest {
        val auth = authContext(Uuid.NIL)
        val input = DeviceInput(platform = PlatformType.IOS, installationId = "install-1")
        assertFailsWith<SecurityException> {
            controller.register(auth, input)
        }
    }

    /**
     * Verifies that a valid registration delegates to the service and returns
     * the persisted device.
     */
    @Test
    fun `register delegates to service for authenticated principal`() = runTest {
        val auth = authContext(validPrincipalId)
        val input = DeviceInput(platform = PlatformType.WEB, installationId = "install-1")
        val expected = Device(
            id = deviceId,
            principalId = validPrincipalId,
            platform = PlatformType.WEB,
            installationId = "install-1"
        )
        coEvery { deviceService.register(any()) } returns expected

        val result = controller.register(auth, input)

        assertEquals(expected, result)
        coVerify { deviceService.register(match { it.principalId == validPrincipalId && it.platform == PlatformType.WEB }) }
    }

    // ---- addPushToken ----

    /**
     * Verifies that an empty push token string is rejected by the length validation.
     */
    @Test
    fun `addPushToken rejects empty token`() = runTest {
        val auth = authContext(validPrincipalId)
        assertFailsWith<IllegalArgumentException> {
            controller.addPushToken(auth, deviceId, PushProvider.FCM, "")
        }
    }

    /**
     * Verifies that a push token exceeding 2048 characters is rejected.
     */
    @Test
    fun `addPushToken rejects token longer than 2048 chars`() = runTest {
        val auth = authContext(validPrincipalId)
        val longToken = "x".repeat(2049)
        assertFailsWith<IllegalArgumentException> {
            controller.addPushToken(auth, deviceId, PushProvider.FCM, longToken)
        }
    }

    /**
     * Verifies that an unauthenticated caller cannot add a push token.
     */
    @Test
    fun `addPushToken throws SecurityException when not authenticated`() = runTest {
        val auth = authContext(null)
        assertFailsWith<SecurityException> {
            controller.addPushToken(auth, deviceId, PushProvider.FCM, "valid-token")
        }
    }

    @Test
    fun `addPushToken succeeds for owning principal`() = runTest {
        val auth = authContext(validPrincipalId)
        val ownedDevice = Device(id = deviceId, principalId = validPrincipalId, platform = PlatformType.ANDROID)
        coEvery { deviceService.getById(deviceId) } returns ownedDevice
        coEvery { deviceService.addPushToken(any()) } returns Unit

        assertTrue(controller.addPushToken(auth, deviceId, PushProvider.APNS, "valid-token"))

        coVerify {
            deviceService.addPushToken(match {
                it.deviceId == deviceId && it.provider == PushProvider.APNS && it.token == "valid-token"
            })
        }
    }

    @Test
    fun `addPushToken rejects a principal who does not own the device`() = runTest {
        val auth = authContext(validPrincipalId)
        coEvery { deviceService.getById(deviceId) } returns
            Device(id = deviceId, principalId = otherPrincipalId, platform = PlatformType.ANDROID)

        assertFailsWith<SecurityException> {
            controller.addPushToken(auth, deviceId, PushProvider.FCM, "valid-token")
        }
    }

    // ---- removePushToken ----

    @Test
    fun `removePushToken rejects an empty token`() = runTest {
        val auth = authContext(validPrincipalId)

        assertFailsWith<IllegalArgumentException> {
            controller.removePushToken(auth, deviceId, PushProvider.FCM, "")
        }
    }

    @Test
    fun `removePushToken rejects unauthenticated callers`() = runTest {
        val auth = authContext(null)

        assertFailsWith<SecurityException> {
            controller.removePushToken(auth, deviceId, PushProvider.FCM, "valid-token")
        }
    }

    @Test
    fun `removePushToken rejects a principal who does not own the device`() = runTest {
        val auth = authContext(validPrincipalId)
        coEvery { deviceService.getById(deviceId) } returns
            Device(id = deviceId, principalId = otherPrincipalId, platform = PlatformType.IOS)

        assertFailsWith<SecurityException> {
            controller.removePushToken(auth, deviceId, PushProvider.FCM, "valid-token")
        }
    }

    @Test
    fun `removePushToken succeeds for owning principal`() = runTest {
        val auth = authContext(validPrincipalId)
        coEvery { deviceService.getById(deviceId) } returns
            Device(id = deviceId, principalId = validPrincipalId, platform = PlatformType.IOS)
        coEvery { deviceService.removePushToken(deviceId, PushProvider.APNS, "valid-token") } returns Unit

        assertTrue(controller.removePushToken(auth, deviceId, PushProvider.APNS, "valid-token"))

        coVerify { deviceService.removePushToken(deviceId, PushProvider.APNS, "valid-token") }
    }

    // ---- checkIn ----

    /**
     * Verifies that an unauthenticated caller is rejected when attempting check-in.
     */
    @Test
    fun `checkIn throws SecurityException for unauthenticated user`() = runTest {
        val auth = authContext(null)
        assertFailsWith<SecurityException> {
            controller.checkIn(auth, deviceId)
        }
    }

    /**
     * Verifies that checking in with a device ID that does not exist
     * raises a [NoSuchElementException].
     */
    @Test
    fun `checkIn throws NoSuchElementException for nonexistent device`() = runTest {
        val auth = authContext(validPrincipalId)
        coEvery { deviceService.getById(deviceId) } returns null

        assertFailsWith<NoSuchElementException> {
            controller.checkIn(auth, deviceId)
        }
    }

    /**
     * Verifies that a principal who does not own the device is denied check-in
     * with a [SecurityException].
     */
    @Test
    fun `checkIn throws SecurityException when principal does not own device`() = runTest {
        val auth = authContext(validPrincipalId)
        val foreignDevice = Device(id = deviceId, principalId = otherPrincipalId, platform = PlatformType.ANDROID)
        coEvery { deviceService.getById(deviceId) } returns foreignDevice

        assertFailsWith<SecurityException> {
            controller.checkIn(auth, deviceId)
        }
    }

    @Test
    fun `installation checkIn updates a registered installation without authentication`() = runTest {
        coEvery { deviceService.checkIn("install-1") } returns
            Device(id = deviceId, platform = PlatformType.ANDROID, installationId = "install-1")

        assertTrue(controller.checkInInstallation("install-1"))
        coVerify { deviceService.checkIn("install-1") }
    }

    @Test
    fun `installation checkIn returns false when installation is unknown`() = runTest {
        coEvery { deviceService.checkIn("install-1") } returns null

        assertEquals(false, controller.checkInInstallation("install-1"))
    }

    @Test
    fun `installation checkIn rejects a blank installation id`() = runTest {
        assertFailsWith<IllegalArgumentException> { controller.checkInInstallation(" ") }
        coVerify(exactly = 0) { deviceService.checkIn(any<String>()) }
    }

    // ---- clearPrincipal ----

    @Test
    fun `clearPrincipal clears the owner without deleting the device`() = runTest {
        val auth = authContext(validPrincipalId)
        val owned = Device(id = deviceId, principalId = validPrincipalId, platform = PlatformType.IOS)
        coEvery { deviceService.getById(deviceId) } returns owned
        coEvery { deviceService.clearPrincipal(deviceId, validPrincipalId) } returns owned.copy(principalId = null)

        assertTrue(controller.clearPrincipal(auth, deviceId))
        coVerify { deviceService.clearPrincipal(deviceId, validPrincipalId) }
        coVerify(exactly = 0) { deviceService.delete(any()) }
        coVerify(exactly = 0) { deviceService.removePushToken(any(), any<String>()) }
    }

    @Test
    fun `clearPrincipal rejects a principal who does not own the device`() = runTest {
        val auth = authContext(validPrincipalId)
        coEvery { deviceService.getById(deviceId) } returns
            Device(id = deviceId, principalId = otherPrincipalId, platform = PlatformType.ANDROID)

        assertFailsWith<SecurityException> { controller.clearPrincipal(auth, deviceId) }
        coVerify(exactly = 0) { deviceService.clearPrincipal(any(), any()) }
    }

    // ---- delete ----

    /**
     * Verifies that a principal who does not own the device cannot delete it.
     */
    @Test
    fun `delete throws SecurityException for wrong owner`() = runTest {
        val auth = authContext(validPrincipalId)
        val foreignDevice = Device(id = deviceId, principalId = otherPrincipalId, platform = PlatformType.DESKTOP)
        coEvery { deviceService.getById(deviceId) } returns foreignDevice

        assertFailsWith<SecurityException> {
            controller.delete(auth, deviceId)
        }
    }

    /**
     * Verifies that an unauthenticated caller cannot delete a device.
     */
    @Test
    fun `delete throws SecurityException when not authenticated`() = runTest {
        val auth = authContext(null)
        assertFailsWith<SecurityException> {
            controller.delete(auth, deviceId)
        }
    }

    /**
     * Verifies that a successful delete by the owning principal returns true.
     */
    @Test
    fun `delete succeeds for owning principal`() = runTest {
        val auth = authContext(validPrincipalId)
        val ownedDevice = Device(id = deviceId, principalId = validPrincipalId, platform = PlatformType.IOS)
        coEvery { deviceService.getById(deviceId) } returns ownedDevice
        coEvery { deviceService.delete(deviceId) } returns Unit

        val result = controller.delete(auth, deviceId)

        assertTrue(result)
        coVerify { deviceService.delete(deviceId) }
    }
}
