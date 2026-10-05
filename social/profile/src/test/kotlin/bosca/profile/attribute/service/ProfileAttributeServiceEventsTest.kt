package bosca.profile.attribute.service

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.withRequestCache
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.profile.attribute.events.ProfileAttributeDeleted
import bosca.profile.attribute.events.ProfileAttributesAdded
import bosca.profile.attribute.events.ProfileAttributesUpdated
import bosca.profile.attribute.events.ProfileAttributesVerified
import bosca.profile.attribute.events.dispatch
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.attribute.model.ProfileAttributeInput
import bosca.profile.attribute.model.ProfileAttributeType
import bosca.profile.attribute.repository.ProfileAttributeRepository
import bosca.profile.attribute.repository.ProfileAttributeTypeRepository
import bosca.profile.model.ProfileVisibility
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(InternalDI::class)
class ProfileAttributeServiceEventsTest {

    private val attributeRepository = mockk<ProfileAttributeRepository>()
    private val attributeTypeRepository = mockk<ProfileAttributeTypeRepository>()
    private val cacheManager = mockk<CacheManager>(relaxed = true)

    private val testJson = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(OffsetDateTimeSerializer())
            contextual(UUIDSerializer())
        }
    }

    private lateinit var service: ProfileAttributeServiceImpl

    private val addedEvents = mutableListOf<ProfileAttributesAdded>()
    private val updatedEvents = mutableListOf<ProfileAttributesUpdated>()
    private val verifiedEvents = mutableListOf<ProfileAttributesVerified>()
    private val deletedEvents = mutableListOf<ProfileAttributeDeleted>()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<CacheManager> { cacheManager }
        provides<RequestCacheSerializer> { RequestCacheSerializerImpl(testJson) }

        mockkStatic("bosca.profile.attribute.events.ProfileAttributesAddedExtKt")
        mockkStatic("bosca.profile.attribute.events.ProfileAttributesUpdatedExtKt")
        mockkStatic("bosca.profile.attribute.events.ProfileAttributesVerifiedExtKt")
        mockkStatic("bosca.profile.attribute.events.ProfileAttributeDeletedExtKt")
        coEvery { any<ProfileAttributesAdded>().dispatch() } coAnswers { addedEvents += firstArg<ProfileAttributesAdded>() }
        coEvery { any<ProfileAttributesUpdated>().dispatch() } coAnswers { updatedEvents += firstArg<ProfileAttributesUpdated>() }
        coEvery { any<ProfileAttributesVerified>().dispatch() } coAnswers { verifiedEvents += firstArg<ProfileAttributesVerified>() }
        coEvery { any<ProfileAttributeDeleted>().dispatch() } coAnswers { deletedEvents += firstArg<ProfileAttributeDeleted>() }

        service = ProfileAttributeServiceImpl(attributeRepository, attributeTypeRepository)
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.profile.attribute.events.ProfileAttributesAddedExtKt")
        unmockkStatic("bosca.profile.attribute.events.ProfileAttributesUpdatedExtKt")
        unmockkStatic("bosca.profile.attribute.events.ProfileAttributesVerifiedExtKt")
        unmockkStatic("bosca.profile.attribute.events.ProfileAttributeDeletedExtKt")
        ProviderRegistry.clear()
    }

    private fun type(id: String) = ProfileAttributeType(
        id = id,
        name = id,
        description = "",
        visibility = ProfileVisibility.USER,
        protected = false,
    )

    private fun input(typeId: String, id: UUID = UUID.NIL) = ProfileAttributeInput(
        id = id,
        typeId = typeId,
        visibility = ProfileVisibility.USER,
        confidence = 100,
        priority = 1,
        source = "test",
    )

    private fun attribute(id: UUID, profileId: UUID, typeId: String, verified: Boolean = false) = ProfileAttribute(
        id = id,
        profile = profileId,
        typeId = typeId,
        visibility = ProfileVisibility.USER,
        confidence = 100,
        priority = 1,
        source = "test",
        verified = verified,
    )

    @Test
    fun `addAttributes partitions new and existing inputs into Added and Updated events`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val existingId = UUID.random()
            val newId = UUID.random()

            coEvery { attributeTypeRepository.getById("bosca.test.a") } returns type("bosca.test.a")
            coEvery { attributeTypeRepository.getById("bosca.test.b") } returns type("bosca.test.b")
            coEvery { attributeRepository.add(any()) } returns attribute(newId, profileId, "bosca.test.a")
            coEvery { attributeRepository.getById(existingId) } returns attribute(existingId, profileId, "bosca.test.b")
            coEvery { attributeRepository.edit(any()) } returns attribute(existingId, profileId, "bosca.test.b")

            service.addAttributes(profileId, listOf(input("bosca.test.a"), input("bosca.test.b", id = existingId)))

            assertEquals(1, addedEvents.size)
            assertEquals(profileId, addedEvents.single().profileId)
            assertEquals(listOf(newId), addedEvents.single().attributeIds)
            assertEquals(listOf("bosca.test.a"), addedEvents.single().typeIds)

            assertEquals(1, updatedEvents.size)
            assertEquals(listOf(existingId), updatedEvents.single().attributeIds)
            assertEquals(listOf("bosca.test.b"), updatedEvents.single().typeIds)
        }
    }

    @Test
    fun `addAttributes with only new inputs emits no Updated event`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            coEvery { attributeTypeRepository.getById("bosca.test.a") } returns type("bosca.test.a")
            coEvery { attributeRepository.add(any()) } returns attribute(UUID.random(), profileId, "bosca.test.a")

            service.addAttributes(profileId, listOf(input("bosca.test.a")))

            assertEquals(1, addedEvents.size)
            assertEquals(0, updatedEvents.size)
        }
    }

    @Test
    fun `markVerified emits one Verified event per distinct profile`() = runTest {
        withRequestCache {
            val profile1 = UUID.random()
            val profile2 = UUID.random()
            coEvery { attributeRepository.markVerified("bosca.profiles.email", profile1, "email", any(), "email") } returns
                listOf(attribute(UUID.random(), profile1, "bosca.profiles.email", verified = true))
            coEvery { attributeRepository.markVerified("bosca.profiles.email", profile2, "email", any(), "email") } returns
                listOf(attribute(UUID.random(), profile2, "bosca.profiles.email", verified = true))

            service.markVerified("bosca.profiles.email", listOf(profile1, profile2), "email", "User@Example.com", "email")

            assertEquals(2, verifiedEvents.size)
            assertEquals(setOf(profile1, profile2), verifiedEvents.map { it.profileId }.toSet())
            assertEquals(setOf("email"), verifiedEvents.map { it.source }.toSet())
        }
    }

    @Test
    fun `verifyByToken groups Verified events by profile`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            coEvery { attributeRepository.verifyByToken("token", "email") } returns listOf(
                attribute(UUID.random(), profileId, "bosca.profiles.email", verified = true),
                attribute(UUID.random(), profileId, "bosca.profiles.email", verified = true),
            )

            service.verifyByToken("token", "email")

            assertEquals(1, verifiedEvents.size)
            assertEquals(profileId, verifiedEvents.single().profileId)
            assertEquals("bosca.profiles.email", verifiedEvents.single().typeId)
        }
    }

    @Test
    fun `verifyByToken emits one Verified event per attribute type`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            coEvery { attributeRepository.verifyByToken("token", "email") } returns listOf(
                attribute(UUID.random(), profileId, "bosca.profiles.email", verified = true),
                attribute(UUID.random(), profileId, "bosca.profiles.phone", verified = true),
            )

            service.verifyByToken("token", "email")

            assertEquals(2, verifiedEvents.size)
            assertEquals(setOf("bosca.profiles.email", "bosca.profiles.phone"), verifiedEvents.map { it.typeId }.toSet())
            assertEquals(setOf(profileId), verifiedEvents.map { it.profileId }.toSet())
        }
    }

    @Test
    fun `deleteAttribute emits Deleted with the owning profile and type`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val attributeId = UUID.random()
            coEvery { attributeRepository.deleteById(attributeId) } returns attribute(attributeId, profileId, "bosca.test.a")

            service.deleteAttribute(attributeId)

            assertEquals(1, deletedEvents.size)
            assertEquals(profileId, deletedEvents.single().profileId)
            assertEquals(attributeId, deletedEvents.single().attributeId)
            assertEquals("bosca.test.a", deletedEvents.single().typeId)
        }
    }

    @Test
    fun `deleteAttribute of a missing attribute emits nothing`() = runTest {
        withRequestCache {
            val attributeId = UUID.random()
            coEvery { attributeRepository.deleteById(attributeId) } returns null

            service.deleteAttribute(attributeId)

            assertEquals(0, deletedEvents.size)
            coVerify { attributeRepository.deleteById(attributeId) }
        }
    }
}
