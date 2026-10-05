package bosca.hubspot

import bosca.communications.model.DeliveryChannel
import bosca.di.ObjectProvider
import bosca.hubspot.client.CommunicationSubscription
import bosca.hubspot.client.HubSpot
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HubSpotNotificationPreferenceProviderTest {

    private val profileId = UUID.random()
    private val hubspot = mockk<HubSpot>()
    private val hubspotProvider = object : ObjectProvider<HubSpot> {
        override val type: KClass<HubSpot> = HubSpot::class
        override suspend fun get(): HubSpot = hubspot
    }
    private val profiles = mockk<ProfileService> {
        coEvery { getAttributes(profileId) } returns listOf(emailAttribute("person@example.com"))
    }
    private val provider = HubSpotNotificationPreferenceProvider(hubspotProvider, profiles)

    @Test
    fun `provider key is stable`() {
        assertEquals("hubspot", provider.key)
    }

    @Test
    fun `getOptOuts reads all requested subscriptions in one HubSpot call`() = runBlocking {
        coEvery { hubspot.getSubscriptions("person@example.com") } returns listOf(
            CommunicationSubscription(1, subscribed = true),
            CommunicationSubscription(2, subscribed = false),
        )

        val result = provider.getOptOuts(
            profileId,
            DeliveryChannel.EMAIL,
            setOf("1", "2", "3"),
        )

        assertFalse(result.getValue("1"))
        assertTrue(result.getValue("2"))
        assertFalse(result.containsKey("3"))
        coVerify(exactly = 1) { hubspot.getSubscriptions("person@example.com") }
    }

    @Test
    fun `setOptOut translates Bosca opt-out state to HubSpot subscription state`() = runBlocking {
        coEvery { hubspot.setSubscription("person@example.com", 42, false) } returns "ok"
        coEvery { hubspot.setSubscription("person@example.com", 42, true) } returns "ok"

        provider.setOptOut(profileId, DeliveryChannel.EMAIL, "42", true)
        provider.setOptOut(profileId, DeliveryChannel.EMAIL, "42", false)

        coVerify(exactly = 1) { hubspot.setSubscription("person@example.com", 42, false) }
        coVerify(exactly = 1) { hubspot.setSubscription("person@example.com", 42, true) }
    }

    @Test
    fun `setOptOut fails when HubSpot does not confirm the write`() {
        coEvery { hubspot.setSubscription("person@example.com", 42, false) } returns "missing"

        assertFailsWith<IllegalStateException> {
            runBlocking {
                provider.setOptOut(profileId, DeliveryChannel.EMAIL, "42", true)
            }
        }
    }

    @Test
    fun `provider rejects unsupported channels and malformed subscription ids`() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                provider.getOptOuts(profileId, DeliveryChannel.PUSH, setOf("42"))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                provider.setOptOut(profileId, DeliveryChannel.EMAIL, "not-a-number", true)
            }
        }
    }

    @Test
    fun `empty read does not call HubSpot`() = runBlocking {
        assertEquals(
            emptyMap(),
            provider.getOptOuts(profileId, DeliveryChannel.EMAIL, emptySet()),
        )
        coVerify(exactly = 0) { hubspot.getSubscriptions(any()) }
    }

    @Test
    fun `provider requires a configured HubSpot client and profile email`() {
        val absentHubSpot = object : ObjectProvider<HubSpot> {
            override val type: KClass<HubSpot> = HubSpot::class
            override val exists: Boolean = false
            override suspend fun get(): HubSpot = error("must not be called")
        }
        val withoutHubSpot = HubSpotNotificationPreferenceProvider(absentHubSpot, profiles)
        assertFailsWith<IllegalStateException> {
            runBlocking {
                withoutHubSpot.getOptOuts(profileId, DeliveryChannel.EMAIL, setOf("42"))
            }
        }

        val noEmailProfiles = mockk<ProfileService> {
            coEvery { getAttributes(profileId) } returns emptyList()
        }
        val withoutEmail = HubSpotNotificationPreferenceProvider(hubspotProvider, noEmailProfiles)
        assertFailsWith<IllegalStateException> {
            runBlocking {
                withoutEmail.getOptOuts(profileId, DeliveryChannel.EMAIL, setOf("42"))
            }
        }

        val blankEmailProfiles = mockk<ProfileService> {
            coEvery { getAttributes(profileId) } returns listOf(emailAttribute("   "))
        }
        val withBlankEmail = HubSpotNotificationPreferenceProvider(hubspotProvider, blankEmailProfiles)
        assertFailsWith<IllegalStateException> {
            runBlocking {
                withBlankEmail.getOptOuts(profileId, DeliveryChannel.EMAIL, setOf("42"))
            }
        }
    }

    private fun emailAttribute(email: String) = ProfileAttribute(
        profile = profileId,
        typeId = "bosca.profiles.email",
        visibility = ProfileVisibility.SYSTEM,
        confidence = 100,
        priority = 100,
        source = "test",
        attributes = JsonObject(mapOf("email" to JsonPrimitive(email))),
    )
}
