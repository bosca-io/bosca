package bosca.communications.graphql

import bosca.communications.model.DeliveryChannel
import bosca.communications.model.NotificationPreference
import bosca.communications.model.NotificationType
import bosca.communications.service.BmlMessageRegistryService
import bosca.communications.service.BmlMessageTemplateRendererService
import bosca.communications.service.DeliveryTrackingService
import bosca.communications.service.NotificationPreferenceMappingService
import bosca.communications.service.NotificationPreferenceService
import bosca.communications.service.NotificationTypeService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationTypeGraphQLTest {

    private val type = NotificationType(
        key = "internal-updates",
        name = "Internal updates",
        hidden = true,
    )

    @Test
    fun `type controller exposes notification type fields`() {
        val updated = OffsetDateTime.parse("2026-07-29T12:00:00Z")
        val notificationType = type.copy(
            description = "Internal messages",
            optional = false,
            system = true,
            displayOrder = 12,
            updatedAt = updated,
        )
        val controller = NotificationTypeController()

        assertEquals(type.key, controller.key(notificationType))
        assertEquals(type.name, controller.name(notificationType))
        assertEquals("Internal messages", controller.description(notificationType))
        assertFalse(controller.optional(notificationType))
        assertTrue(controller.system(notificationType))
        assertTrue(controller.defaultEmailEnabled(notificationType))
        assertTrue(controller.defaultPushEnabled(notificationType))
        assertTrue(controller.hidden(notificationType))
        assertEquals(12, controller.displayOrder(notificationType))
        assertEquals(updated, controller.updatedAt(notificationType))
    }

    @Test
    fun `admin mutation persists hidden`() = runBlocking {
        val authentication = mockk<AuthenticationContext>()
        val groups = mockk<GroupEvaluator>(relaxed = true)
        val types = mockk<NotificationTypeService>()
        coEvery {
            types.set("internal-updates", "Internal updates", null, true, false, true, 10, true)
        } returns type.copy(defaultEmailEnabled = false)
        val controller = CommunicationsMutationsController(
            bmlMessageRegistry = mockk<BmlMessageRegistryService>(),
            notificationPreferences = mockk<NotificationPreferenceService>(),
            notificationPreferenceMappings = mockk<NotificationPreferenceMappingService>(),
            notificationTypes = types,
            groupEvaluator = groups,
            profileService = mockk<ProfileService>(),
            profilePermissionEvaluator = mockk<ProfilePermissionEvaluator>(),
        )

        val result = controller.setNotificationType(
            authentication = authentication,
            key = "internal-updates",
            name = "Internal updates",
            description = null,
            optional = true,
            defaultEmailEnabled = false,
            defaultPushEnabled = true,
            displayOrder = 10,
            hidden = true,
        )

        assertTrue(result.hidden)
        assertFalse(result.defaultEmailEnabled)
        assertTrue(result.defaultPushEnabled)
        coVerify(exactly = 1) { groups.verifyHasAdminGroup(authentication) }
        coVerify(exactly = 1) {
            types.set("internal-updates", "Internal updates", null, true, false, true, 10, true)
        }
    }

    @Test
    fun `admin mutation persists visible state explicitly`() = runBlocking {
        val authentication = mockk<AuthenticationContext>()
        val groups = mockk<GroupEvaluator>(relaxed = true)
        val types = mockk<NotificationTypeService>()
        val visible = type.copy(hidden = false)
        coEvery {
            types.set("internal-updates", "Internal updates", null, true, true, false, 10, false)
        } returns visible
        val controller = CommunicationsMutationsController(
            bmlMessageRegistry = mockk<BmlMessageRegistryService>(),
            notificationPreferences = mockk<NotificationPreferenceService>(),
            notificationPreferenceMappings = mockk<NotificationPreferenceMappingService>(),
            notificationTypes = types,
            groupEvaluator = groups,
            profileService = mockk<ProfileService>(),
            profilePermissionEvaluator = mockk<ProfilePermissionEvaluator>(),
        )

        val result = controller.setNotificationType(
            authentication = authentication,
            key = "internal-updates",
            name = "Internal updates",
            description = null,
            optional = true,
            defaultEmailEnabled = true,
            defaultPushEnabled = false,
            displayOrder = 10,
            hidden = false,
        )

        assertFalse(result.hidden)
        coVerify(exactly = 1) {
            types.set("internal-updates", "Internal updates", null, true, true, false, 10, false)
        }
    }

    @Test
    fun `public notification type query omits hidden types`() = runBlocking {
        val groups = mockk<GroupEvaluator>()
        val types = mockk<NotificationTypeService>()
        val visible = NotificationType(key = "marketing", name = "Marketing")
        coEvery { types.list() } returns listOf(visible, type)
        every { groups.hasAdminGroup(null) } returns false

        val result = queries(types, groups).notificationTypes(null)

        assertEquals(listOf(visible), result)
    }

    @Test
    fun `admin notification type query includes hidden types`() = runBlocking {
        val authentication = mockk<AuthenticationContext>()
        val groups = mockk<GroupEvaluator>()
        val types = mockk<NotificationTypeService>()
        val visible = NotificationType(key = "marketing", name = "Marketing")
        coEvery { types.list() } returns listOf(visible, type)
        every { groups.hasAdminGroup(authentication) } returns true

        val result = queries(types, groups).notificationTypes(authentication)

        assertEquals(listOf(visible, type), result)
    }

    @Test
    fun `public token preferences do not expose hidden type keys`() = runBlocking {
        val profileId = UUID.random()
        val groups = mockk<GroupEvaluator>()
        val types = mockk<NotificationTypeService>()
        val preferences = mockk<NotificationPreferenceService>()
        val visible = NotificationType(key = "marketing", name = "Marketing")
        val visiblePreference = NotificationPreference(profileId, DeliveryChannel.EMAIL, visible.key)
        val hiddenPreference = NotificationPreference(profileId, DeliveryChannel.EMAIL, type.key)
        coEvery { preferences.profileIdForToken("token") } returns profileId
        coEvery { preferences.getPreferences(profileId) } returns listOf(visiblePreference, hiddenPreference)
        coEvery { types.list() } returns listOf(visible, type)
        every { groups.hasAdminGroup(null) } returns false

        val result = queries(types, groups, preferences).tokenNotificationPreferences(null, "token")

        assertEquals(listOf(visiblePreference), result)
    }

    @Test
    fun `public signed-in preferences do not expose hidden type keys`() = runBlocking {
        val profileId = UUID.random()
        val authentication = authentication(profileId)
        val groups = mockk<GroupEvaluator>()
        val types = mockk<NotificationTypeService>()
        val preferences = mockk<NotificationPreferenceService>()
        val visible = NotificationType(key = "marketing", name = "Marketing")
        val visiblePreference = NotificationPreference(profileId, DeliveryChannel.EMAIL, visible.key)
        val hiddenPreference = NotificationPreference(profileId, DeliveryChannel.EMAIL, type.key)
        coEvery { preferences.getPreferences(profileId) } returns listOf(visiblePreference, hiddenPreference)
        coEvery { types.list() } returns listOf(visible, type)
        every { groups.hasAdminGroup(authentication) } returns false

        val result = queries(types, groups, preferences).myNotificationPreferences(authentication)

        assertEquals(listOf(visiblePreference), result)
    }

    @Test
    fun `admin token preferences retain hidden type keys`() = runBlocking {
        val profileId = UUID.random()
        val authentication = authentication(profileId)
        val groups = mockk<GroupEvaluator>()
        val types = mockk<NotificationTypeService>()
        val preferences = mockk<NotificationPreferenceService>()
        val hiddenPreference = NotificationPreference(profileId, DeliveryChannel.EMAIL, type.key)
        coEvery { preferences.profileIdForToken("token") } returns profileId
        coEvery { preferences.getPreferences(profileId) } returns listOf(hiddenPreference)
        every { groups.hasAdminGroup(authentication) } returns true

        val result = queries(types, groups, preferences)
            .tokenNotificationPreferences(authentication, "token")

        assertEquals(listOf(hiddenPreference), result)
        coVerify(exactly = 0) { types.list() }
    }

    @Test
    fun `public token mutation cannot probe or update a hidden type`() = runBlocking {
        val profileId = UUID.random()
        val groups = mockk<GroupEvaluator>()
        val types = mockk<NotificationTypeService>()
        val preferences = mockk<NotificationPreferenceService>()
        coEvery { preferences.profileIdForToken("token") } returns profileId
        coEvery { types.get(type.key) } returns type
        every { groups.hasAdminGroup(null) } returns false

        val error = assertFailsWith<IllegalArgumentException> {
            mutations(types, groups, preferences).updateTokenNotificationPreference(
                authentication = null,
                token = "token",
                type = type.key,
                optedOut = true,
                channel = DeliveryChannel.EMAIL,
            )
        }

        assertEquals("unknown notification type: ${type.key}", error.message)
        coVerify(exactly = 0) {
            preferences.setOptOut(any(), any(), any(), any())
        }
    }

    @Test
    fun `admin token mutation can update a hidden type`() = runBlocking {
        val authentication = mockk<AuthenticationContext>()
        val profileId = UUID.random()
        val groups = mockk<GroupEvaluator>()
        val types = mockk<NotificationTypeService>()
        val preferences = mockk<NotificationPreferenceService>()
        coEvery { preferences.profileIdForToken("token") } returns profileId
        coEvery {
            preferences.setOptOut(profileId, DeliveryChannel.EMAIL, type.key, true)
        } returns NotificationPreference(profileId, DeliveryChannel.EMAIL, type.key, true)
        every { groups.hasAdminGroup(authentication) } returns true

        val updated = mutations(types, groups, preferences).updateTokenNotificationPreference(
            authentication = authentication,
            token = "token",
            type = type.key,
            optedOut = true,
            channel = DeliveryChannel.EMAIL,
        )

        assertTrue(updated)
        coVerify(exactly = 1) {
            preferences.setOptOut(profileId, DeliveryChannel.EMAIL, type.key, true)
        }
        coVerify(exactly = 0) { types.get(any()) }
    }

    @Test
    fun `signed-in user cannot update a hidden type`() = runBlocking {
        val profileId = UUID.random()
        val authentication = authentication(profileId)
        val groups = mockk<GroupEvaluator>()
        val types = mockk<NotificationTypeService>()
        val preferences = mockk<NotificationPreferenceService>()
        coEvery { types.get(type.key) } returns type
        every { groups.hasAdminGroup(authentication) } returns false

        assertFailsWith<IllegalArgumentException> {
            mutations(types, groups, preferences).updateMyNotificationPreference(
                authentication = authentication,
                channel = DeliveryChannel.EMAIL,
                type = type.key,
                optedOut = true,
            )
        }

        coVerify(exactly = 0) {
            preferences.setOptOut(any(), any(), any(), any())
        }
    }

    @Test
    fun `non-admin profile editor cannot update a hidden type`() = runBlocking {
        val profileId = UUID.random()
        val authentication = authentication(UUID.random())
        val groups = mockk<GroupEvaluator>()
        val types = mockk<NotificationTypeService>()
        val preferences = mockk<NotificationPreferenceService>()
        val profiles = mockk<ProfileService>()
        val permissions = mockk<ProfilePermissionEvaluator>()
        val profile = Profile(
            id = profileId,
            type = ProfileType.GENERIC,
            name = "Recipient",
            visibility = ProfileVisibility.USER,
        )
        coEvery { profiles.getById(profileId) } returns profile
        coEvery {
            permissions.verifyAllowed(authentication, profile, PermissionAction.EDIT)
        } returns Unit
        coEvery { types.get(type.key) } returns type
        every { groups.hasAdminGroup(authentication) } returns false
        val controller = CommunicationsMutationsController(
            bmlMessageRegistry = mockk<BmlMessageRegistryService>(),
            notificationPreferences = preferences,
            notificationPreferenceMappings = mockk<NotificationPreferenceMappingService>(),
            notificationTypes = types,
            groupEvaluator = groups,
            profileService = profiles,
            profilePermissionEvaluator = permissions,
        )

        assertFailsWith<IllegalArgumentException> {
            controller.setNotificationPreference(
                authentication = authentication,
                profileId = profileId,
                channel = DeliveryChannel.EMAIL,
                type = type.key,
                optedOut = true,
            )
        }

        coVerify(exactly = 0) {
            preferences.setOptOut(any(), any(), any(), any())
        }
    }

    private fun authentication(profileId: UUID): AuthenticationContext =
        ImpersonatedAuthenticationContext(
            Principal(primaryProfileId = profileId),
            emptyList(),
        )

    private fun queries(
        types: NotificationTypeService,
        groups: GroupEvaluator,
        preferences: NotificationPreferenceService = mockk(),
    ) = CommunicationsQueriesController(
        json = Json,
        bmlMessageRegistry = mockk<BmlMessageRegistryService>(),
        bmlMessageRenderer = mockk<BmlMessageTemplateRendererService>(),
        deliveryTracking = mockk<DeliveryTrackingService>(),
        notificationPreferences = preferences,
        notificationPreferenceMappings = mockk<NotificationPreferenceMappingService>(),
        notificationTypes = types,
        groupEvaluator = groups,
        profileService = mockk<ProfileService>(),
    )

    private fun mutations(
        types: NotificationTypeService,
        groups: GroupEvaluator,
        preferences: NotificationPreferenceService,
    ) = CommunicationsMutationsController(
        bmlMessageRegistry = mockk<BmlMessageRegistryService>(),
        notificationPreferences = preferences,
        notificationPreferenceMappings = mockk<NotificationPreferenceMappingService>(),
        notificationTypes = types,
        groupEvaluator = groups,
        profileService = mockk<ProfileService>(),
        profilePermissionEvaluator = mockk<ProfilePermissionEvaluator>(),
    )
}
