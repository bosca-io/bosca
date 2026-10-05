package bosca.slug.service

import bosca.cache.Cache
import bosca.cache.CacheValue
import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.requestCache
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.slug.model.Slug
import bosca.slug.repository.SlugRepository
import bosca.cache.serializers.StringKeySerializer
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import bosca.cache.asCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import bosca.di.annotation.InternalDI
import bosca.serialization.UUID

@OptIn(ExperimentalCoroutinesApi::class, InternalDI::class)
class SlugServiceTest {

    private val repository = mockk<SlugRepository>(relaxed = true)
    private val cacheManager = mockk<CacheManager>(relaxed = true)
    private val requestCacheSerializer = mockk<RequestCacheSerializer>(relaxed = true)
    private val remoteCache = mockk<Cache<Any>>(relaxed = true)

    private lateinit var service: SlugServiceImpl

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        
        // Setup cache manager mock
        coEvery { cacheManager.maybeAddCache<Any>(any(), any()) } returns remoteCache
        coEvery { cacheManager.getCache<Any>(any()) } returns remoteCache
        every { remoteCache.keySerializer } returns mockk(relaxed = true)
        
        // Register providers
        provides { cacheManager }
        provides { requestCacheSerializer }
        
        // Create service
        service = SlugServiceImpl(repository)
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    private suspend fun <T> withRequestCache(block: suspend () -> T): T {
         val cache = RequestCache(cacheManager, requestCacheSerializer)
         return withContext(cache.asCoroutineContext()) {
             block()
         }
    }

    @Test
    fun `test add slug invalidates cache`() = runTest {
        withRequestCache {
            val slugStr = "new-slug"
            val collectionId = UUID.random()
            val slug = Slug(collectionId = collectionId, slug = slugStr)

            // 1. Get slug, it should be null (simulate negative cache)
            coEvery { repository.get(slugStr) } returns null
            // Also ensure remote cache returns empty/null to trigger lookup
            coEvery { remoteCache.get(any()) } returns mockk<CacheValue> {
                every { exists } returns false
                every { value } returns null
            }

            val initialResult = service.get(slugStr)
            assertNull(initialResult, "Should be null initially")

            // Now "new-slug" is cached as null in local RequestCache

            // 2. Add slug (repository.get still returns null so add() takes the simple path)
            coEvery { repository.add(any()) } returns slug

            service.add(slug)

            // Now make repository return the slug for subsequent lookups
            coEvery { repository.get(slugStr) } returns slug

            // 3. Get slug again. It SHOULD return the new slug.
            val finalResult = service.get(slugStr)
            assertEquals(slug, finalResult, "Should return the added slug after cache invalidation")
        }
    }

    @Test
    fun `test delete slug invalidates cache`() = runTest {
        withRequestCache {
            val slugStr = "slug-to-delete"
            val collectionId = UUID.random()
            val slug = Slug(collectionId = collectionId, slug = slugStr)

            // Setup repository and cache
            coEvery { repository.get(slugStr) } returns slug
            coEvery { remoteCache.get(any()) } returns mockk { 
                every { exists } returns true
                every { value } returns slugStr // Simplified
            }

            // Call delete
            service.delete(slugStr)

            coVerify { remoteCache.removeBatch(match { it.isNotEmpty() }, any()) }
        }
    }

    @Test
    fun `test delete metadata slug invalidates cache`() = runTest {
        withRequestCache {
            val metadataId = UUID.random()
            val slug = Slug(metadataId = metadataId, slug = "meta-slug")

            // Setup repository
            coEvery { repository.getSlugByMetadataId(metadataId) } returns slug
            coEvery { remoteCache.get(any()) } returns mockk {
                 every { exists } returns true
                 every { value } returns "meta-slug"
            }

            service.deleteMetadataSlug(metadataId)

            coVerify { remoteCache.removeBatch(any(), match { it.isNotEmpty() }) }
        }
    }

    @Test
    fun `test delete collection slug invalidates cache`() = runTest {
        withRequestCache {
            val collectionId = UUID.random()
            val slug = Slug(collectionId = collectionId, slug = "collection-slug")

            coEvery { repository.getSlugByCollectionId(collectionId) } returns slug
            coEvery { remoteCache.get(any()) } returns mockk {
                 every { exists } returns true
                 every { value } returns "collection-slug"
            }

            service.deleteCollectionSlug(collectionId)

            coVerify { remoteCache.removeBatch(match { it.isNotEmpty() }, any()) }
        }
    }

    @Test
    fun `test delete profile slug invalidates cache`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val slug = Slug(profileId = profileId, slug = "profile-slug")

            coEvery { repository.getSlugByProfileId(listOf(profileId)) } returns listOf(slug)
            coEvery { remoteCache.get(any()) } returns mockk {
                 every { exists } returns true
                 every { value } returns "profile-slug"
            }

            service.deleteProfileSlug(profileId)

            coVerify { remoteCache.removeBatch(match { it.isNotEmpty() }, any()) }
        }
    }

    @Test
    fun `deleting profile slugs invalidates every previously cached alias`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            val aliases = listOf("example", "example123", "example124").map { Slug(slug = it, profileId = profileId) }
            coEvery { remoteCache.get(any()) } returns mockk<CacheValue> {
                every { exists } returns false
                every { value } returns null
            }
            coEvery { repository.getSlugByProfileId(listOf(profileId)) } returns aliases
            for (slug in aliases) {
                coEvery { repository.get(slug.slug) } returns slug
                assertEquals(slug, service.get(slug.slug))
            }

            service.deleteProfileSlug(profileId)

            for (slug in aliases) {
                coEvery { repository.get(slug.slug) } returns null
                assertNull(service.get(slug.slug))
            }
            coVerify(exactly = 1) { repository.deleteSlugByProfileId(profileId) }
        }
    }

    @Test
    fun `test delete metadata slug invalidates cache when missing in repo`() = runTest {
        withRequestCache {
            val metadataId = UUID.random()

            // Simulate slug missing in repo
            coEvery { repository.getSlugByMetadataId(metadataId) } returns null
            
            // Service should still try to remove from cache
            service.deleteMetadataSlug(metadataId)

            coVerify { remoteCache.removeBatch(any(), match { it.isNotEmpty() }) }
        }
    }

    @Test
    fun `test delete collection slug invalidates cache when missing in repo`() = runTest {
        withRequestCache {
            val collectionId = UUID.random()

            // Simulate slug missing in repo
            coEvery { repository.getSlugByCollectionId(collectionId) } returns null
            
            // Service should still try to remove from cache
            service.deleteCollectionSlug(collectionId)

            coVerify { remoteCache.removeBatch(match { it.isNotEmpty() }, any()) }
        }
    }

    @Test
    fun `test collection slug with language tag`() = runTest {
        withRequestCache {
            val collectionId = UUID.random()
            val slugEn = Slug(collectionId = collectionId, slug = "slug-en", languageTag = "en")
            val slugFr = Slug(collectionId = collectionId, slug = "slug-fr", languageTag = "fr")

            coEvery { repository.getSlugByCollectionId(collectionId, "en") } returns slugEn
            coEvery { repository.getSlugByCollectionId(collectionId, "fr") } returns slugFr

            assertEquals("slug-en", service.getCollectionSlug(collectionId, "en"))
            assertEquals("slug-fr", service.getCollectionSlug(collectionId, "fr"))

            service.deleteCollectionSlug(collectionId, "en")
            coVerify { repository.deleteSlugByCollectionId(collectionId, "en") }
        }
    }
}
