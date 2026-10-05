package bosca.content.collection.service

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.asCoroutineContext
import bosca.content.collection.events.CollectionUpdated
import bosca.content.collection.events.dispatch
import bosca.content.collection.model.*
import bosca.content.collection.repository.*
import bosca.content.metadata.repository.CollectionTemplateAttributeRepository
import bosca.content.metadata.repository.CollectionTemplateRepository
import bosca.content.metadata.repository.MetadataRepository
import bosca.content.transition.history.service.TransitionHistoryService
import bosca.content.transition.service.Transitioner
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.slug.model.Slug
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectStorageService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test

class CollectionServiceImplTest {

    private val repository = mockk<CollectionRepository>()
    private val items = mockk<CollectionItemRepository>()
    private val find = mockk<CollectionFindRepository>()
    private val categories = mockk<CollectionCategoryRepository>()
    private val traits = mockk<CollectionTraitRepository>()
    private val relationships = mockk<CollectionMetadataRelationshipRepository>()
    private val supplementary = mockk<CollectionSupplementaryRepository>()
    private val plans = mockk<CollectionWorkflowPlanRepository>()
    private val permissions = mockk<CollectionPermissionRepository>()
    private val transitionHistory = mockk<TransitionHistoryService>()
    private val objectService = mockk<ObjectStorageService>()
    private val json = Json
    private val slugService = mockk<SlugService>()
    private val collaborations = mockk<CollectionCollaborationRepository>()
    private val variants = mockk<CollectionLanguageVariantRepository>()
    private val collectionTemplates = mockk<CollectionTemplateRepository>()
    private val collectionTemplateAttributes = mockk<CollectionTemplateAttributeRepository>(relaxed = true)
    private val metadataRepository = mockk<MetadataRepository>(relaxed = true)
    private val transitioner = mockk<ObjectProvider<Transitioner>>()
    private val securityService = mockk<ObjectProvider<SecurityService>>()
    private val cacheManager = mockk<CacheManager>(relaxed = true)
    private val requestCacheSerializer = mockk<RequestCacheSerializer>(relaxed = true)

    private val service by lazy {
        CollectionServiceImpl(
            repository,
            items,
            find,
            categories,
            traits,
            relationships,
            supplementary,
            plans,
            permissions,
            transitionHistory,
            objectService,
            json,
            slugService,
            collaborations,
            variants,
            collectionTemplates,
            collectionTemplateAttributes,
            metadataRepository,
            transitioner,
            securityService,
        )
    }

    @OptIn(InternalDI::class)
    @BeforeTest
    fun setup() {
        mockkObject(ProviderRegistry)
        every { ProviderRegistry.get(CacheManager::class) } returns object : ObjectProvider<CacheManager> {
            override val type = CacheManager::class
            override suspend fun get() = cacheManager
        }

        mockkStatic("bosca.db.ConnectionManagerKt")
        mockkStatic("bosca.content.collection.events.CollectionUpdatedExtKt")
        coEvery { any<CollectionUpdated>().dispatch() } just Runs
        coEvery {
            bosca.db.transaction<Any?>(any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[0] as suspend () -> Any?
            block()
        }
    }

    private suspend fun <T> withRequestCache(block: suspend () -> T): T {
        val cache = RequestCache(cacheManager, requestCacheSerializer)
        return withContext(cache.asCoroutineContext()) {
            block()
        }
    }

    @Test
    fun `addLanguageVariant prefixes english slug for new variant`() = runTest {
        withRequestCache {
            val collectionId = UUID.random()
            val languageTag = "fr"
            val variantInput = CollectionLanguageVariantInput(
                id = collectionId,
                languageTag = languageTag,
                name = "French Collection"
            )
            val variant = variantInput.toVariant()
            val englishSlug = "english-slug"

            coEvery { variants.getLanguageVariant(collectionId, languageTag) } returns null
            coEvery { variants.add(any()) } returns variant
            coEvery { slugService.getCollectionSlug(collectionId, null) } returns englishSlug
            coEvery { slugService.add(any()) } returnsArgument 0

            service.addLanguageVariant(variantInput)

            coVerify {
                slugService.add(
                    Slug(
                        slug = "fr-english-slug",
                        collectionId = collectionId,
                        languageTag = languageTag
                    )
                )
            }
        }
    }

    @Test
    fun `addLanguageVariant does not prefix slug for existing variant`() = runTest {
        withRequestCache {
            val collectionId = UUID.random()
            val languageTag = "fr"
            val variantInput = CollectionLanguageVariantInput(
                id = collectionId,
                languageTag = languageTag,
                name = "French Collection Updated"
            )
            val existingVariant = CollectionLanguageVariant(
                id = collectionId,
                languageTag = languageTag,
                name = "French Collection"
            )
            val updatedVariant = variantInput.toVariant(existingVariant)

            coEvery { variants.getLanguageVariant(collectionId, languageTag) } returns existingVariant
            coEvery { variants.add(any()) } returns updatedVariant

            service.addLanguageVariant(variantInput)

            coVerify(exactly = 0) {
                slugService.add(any())
            }
        }
    }
}
