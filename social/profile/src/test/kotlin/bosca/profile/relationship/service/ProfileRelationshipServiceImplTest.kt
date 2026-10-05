package bosca.profile.relationship.service

import bosca.cache.Cache
import bosca.cache.CacheManager
import bosca.cache.CacheValue
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.withRequestCache
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.events.Event
import bosca.pipelines.PipelineEventDispatcher
import bosca.profile.relationship.events.ProfileRelationshipAdded
import bosca.profile.relationship.cache.ProfileRelationshipCacheKeyId
import bosca.profile.relationship.cache.ProfileRelationshipCacheKeySerializer
import bosca.profile.relationship.model.ProfileRelationship
import bosca.profile.relationship.repository.ProfileRelationshipRepository
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class ProfileRelationshipServiceImplTest {

    private val repository = mockk<ProfileRelationshipRepository>()
    private val cacheManager = mockk<CacheManager>(relaxed = true)
    private val relationshipCache = mockk<Cache<ProfileRelationshipCacheKeyId>>(relaxed = true)
    private val pipelineEvents = mutableListOf<Event>()
    private val testJson = Json {
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
        }
    }

    private lateinit var service: ProfileRelationshipServiceImpl

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        pipelineEvents.clear()
        every { relationshipCache.keySerializer } returns ProfileRelationshipCacheKeySerializer
        coEvery { relationshipCache.get(any()) } returns MissingCacheValue
        coEvery {
            cacheManager.maybeAddCache<ProfileRelationshipCacheKeyId>(any(), any(), any())
        } returns relationshipCache
        coEvery {
            cacheManager.getCache<ProfileRelationshipCacheKeyId>(any())
        } returns relationshipCache
        provides<CacheManager> { cacheManager }
        provides<RequestCacheSerializer> { RequestCacheSerializerImpl(testJson) }
        provides<Json> { testJson }
        provides<PipelineEventDispatcher> {
            object : PipelineEventDispatcher {
                override suspend fun <T : Event> dispatch(
                    eventName: String,
                    event: T,
                    serializer: KSerializer<T>,
                ) {
                    pipelineEvents += event
                }
            }
        }
        service = ProfileRelationshipServiceImpl(repository)
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        clearMocks(repository)
    }

    private object MissingCacheValue : CacheValue {
        override val value: String? = null
        override val exists: Boolean = false
    }

    @Test
    fun `getRelationship returns the exact directional relationship`() = runTest {
        val profileId1 = UUID.random()
        val profileId2 = UUID.random()
        val relationship = ProfileRelationship(profileId1, profileId2, "friend")
        coEvery { repository.getRelationship(profileId1, profileId2, "friend") } returns relationship

        withRequestCache {
            assertEquals(relationship, service.getRelationship(profileId1, profileId2, "friend"))
            assertEquals(relationship, service.getRelationship(profileId1, profileId2, "friend"))
        }

        coVerify(exactly = 1) { repository.getRelationship(profileId1, profileId2, "friend") }
    }

    @Test
    fun `getRelationship negatively caches a missing relationship`() = runTest {
        val profileId1 = UUID.random()
        val profileId2 = UUID.random()
        coEvery { repository.getRelationship(profileId1, profileId2, "friend") } returns null

        withRequestCache {
            assertNull(service.getRelationship(profileId1, profileId2, "friend"))
            assertNull(service.getRelationship(profileId1, profileId2, "friend"))
        }

        coVerify(exactly = 1) { repository.getRelationship(profileId1, profileId2, "friend") }
    }

    @Test
    fun `getRelationships returns relationships from repository`() = runTest {
        val profileId = UUID.random()
        val relationships = listOf(
            ProfileRelationship(profileId1 = profileId, profileId2 = UUID.random(), type = "friend"),
            ProfileRelationship(profileId1 = profileId, profileId2 = UUID.random(), type = "colleague")
        )

        coEvery { repository.getRelationships(profileId, 0, Int.MAX_VALUE) } returns relationships

        val result = service.getRelationships(profileId, null)

        assertEquals(2, result.size)
    }

    @Test
    fun `getRelationships with type filters by type`() = runTest {
        val profileId = UUID.random()
        val relationships = listOf(
            ProfileRelationship(profileId1 = profileId, profileId2 = UUID.random(), type = "friend")
        )

        coEvery { repository.getRelationshipsByType(profileId, "friend", 0, Int.MAX_VALUE) } returns relationships

        val result = service.getRelationships(profileId, "friend")

        assertEquals(1, result.size)
        assertEquals("friend", result[0].type)
    }

    @Test
    fun `getRelationships returns empty list when none found`() = runTest {
        val profileId = UUID.random()

        coEvery { repository.getRelationships(profileId, 0, Int.MAX_VALUE) } returns emptyList()

        val result = service.getRelationships(profileId, null)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getRelationships requests the specified page`() = runTest {
        val profileId = UUID.random()
        val relationships = listOf(
            ProfileRelationship(profileId1 = profileId, profileId2 = UUID.random(), type = "friend")
        )
        coEvery { repository.getRelationshipsByType(profileId, "friend", 12, 25) } returns relationships

        val result = service.getRelationships(profileId, "friend", offset = 12, limit = 25)

        assertEquals(relationships, result)
        coVerify(exactly = 1) { repository.getRelationshipsByType(profileId, "friend", 12, 25) }
    }

    @Test
    fun `getRelationships rejects a negative offset`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.getRelationships(UUID.random(), null, offset = -1, limit = 25)
        }
    }

    @Test
    fun `getRelationships rejects a negative limit`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.getRelationships(UUID.random(), null, offset = 0, limit = -1)
        }
    }

    @Test
    fun `addRelationship delegates to repository`() = runTest {
        val profileId1 = UUID.random()
        val profileId2 = UUID.random()
        val attrs = buildJsonObject { put("since", "2024") }

        coEvery { repository.addRelationship(profileId1, profileId2, "friend", attrs) } returns 1

        withRequestCache {
            service.addRelationship(profileId1, profileId2, "friend", attrs)
        }

        coVerify { repository.addRelationship(profileId1, profileId2, "friend", attrs) }
        assertEquals(
            listOf(profileId1 to profileId2),
            pipelineEvents.filterIsInstance<ProfileRelationshipAdded>().map { it.profileId1 to it.profileId2 },
        )
    }

    @Test
    fun `addRelationship with null attributes delegates to repository`() = runTest {
        val profileId1 = UUID.random()
        val profileId2 = UUID.random()

        coEvery { repository.addRelationship(profileId1, profileId2, "colleague", null) } returns 1

        withRequestCache {
            service.addRelationship(profileId1, profileId2, "colleague", null)
        }

        coVerify { repository.addRelationship(profileId1, profileId2, "colleague", null) }
    }

    @Test
    fun `addRelationship rejects a relationship with the same profile`() = runTest {
        val profileId = UUID.random()

        val exception = assertFailsWith<IllegalArgumentException> {
            service.addRelationship(profileId, profileId, "friend")
        }

        assertEquals("a profile cannot have a relationship with itself", exception.message)
        coVerify(exactly = 0) { repository.addRelationship(any(), any(), any(), any()) }
    }

    @Test
    fun `addRelationship updates attributes when the relationship already exists`() = runTest {
        val profileId1 = UUID.random()
        val profileId2 = UUID.random()
        val attrs = buildJsonObject { put("since", "2025") }
        coEvery { repository.addRelationship(profileId1, profileId2, "friend", attrs) } returns 0
        coEvery { repository.updateRelationship(profileId1, profileId2, "friend", attrs) } returns 1

        withRequestCache {
            service.addRelationship(profileId1, profileId2, "friend", attrs)
        }

        coVerify(exactly = 1) { repository.updateRelationship(profileId1, profileId2, "friend", attrs) }
    }

    @Test
    fun `removeRelationship delegates to repository`() = runTest {
        val profileId1 = UUID.random()
        val profileId2 = UUID.random()

        coEvery { repository.removeRelationship(profileId1, profileId2, "friend") } returns Unit

        withRequestCache {
            service.removeRelationship(profileId1, profileId2, "friend")
        }

        coVerify { repository.removeRelationship(profileId1, profileId2, "friend") }
    }

    @Test
    fun `addRelationship invalidates a negatively cached relationship`() = runTest {
        val profileId1 = UUID.random()
        val profileId2 = UUID.random()
        val relationship = ProfileRelationship(profileId1, profileId2, "friend")
        coEvery { repository.getRelationship(profileId1, profileId2, "friend") } returnsMany listOf(null, relationship)
        coEvery { repository.addRelationship(profileId1, profileId2, "friend", null) } returns 1

        withRequestCache {
            assertNull(service.getRelationship(profileId1, profileId2, "friend"))
            service.addRelationship(profileId1, profileId2, "friend", null)
            assertEquals(relationship, service.getRelationship(profileId1, profileId2, "friend"))
        }

        coVerify(exactly = 2) { repository.getRelationship(profileId1, profileId2, "friend") }
    }

    @Test
    fun `removeRelationship invalidates a positively cached relationship`() = runTest {
        val profileId1 = UUID.random()
        val profileId2 = UUID.random()
        val relationship = ProfileRelationship(profileId1, profileId2, "friend")
        coEvery { repository.getRelationship(profileId1, profileId2, "friend") } returnsMany listOf(relationship, null)
        coEvery { repository.removeRelationship(profileId1, profileId2, "friend") } returns Unit

        withRequestCache {
            assertEquals(relationship, service.getRelationship(profileId1, profileId2, "friend"))
            service.removeRelationship(profileId1, profileId2, "friend")
            assertNull(service.getRelationship(profileId1, profileId2, "friend"))
        }

        coVerify(exactly = 2) { repository.getRelationship(profileId1, profileId2, "friend") }
    }
}
