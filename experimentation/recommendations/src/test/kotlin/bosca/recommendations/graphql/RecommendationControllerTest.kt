@file:OptIn(ExperimentalUuidApi::class)

package bosca.recommendations.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionCacheKeyId
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.model.ICollection
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.Batch
import bosca.graphql.BatchContext
import bosca.graphql.BatchLoaderEnvironment
import bosca.recommendations.model.Recommendation
import bosca.recommendations.model.RecommendationBatchKey
import bosca.recommendations.model.RecommendationSource
import bosca.recommendations.model.RecommendationStrategy
import bosca.recommendations.model.RecommendationStrategyType
import bosca.recommendations.service.RecommendationServiceImpl
import bosca.recommendations.service.RecommendationStrategyService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

class RecommendationControllerTest {

    private val strategyService = mockk<RecommendationStrategyService>(relaxed = true)
    private val metadataService = mockk<MetadataService>(relaxed = true)
    private val collectionService = mockk<CollectionService>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val collectionPermissionEvaluator = mockk<CollectionPermissionEvaluator>(relaxed = true)
    private val recommendationService = mockk<bosca.recommendations.service.RecommendationService>()
    private val controller = RecommendationController(
        strategyService, metadataService, collectionService,
        groupEvaluator, metadataPermissionEvaluator, collectionPermissionEvaluator, recommendationService
    )

    private val auth = mockk<AuthenticationContext>()

    init {
        coEvery {
            collectionPermissionEvaluator.isAllowed(
                auth,
                any<List<ICollection>>(),
                PermissionAction.VIEW,
            )
        } answers { secondArg<List<ICollection>>().map { true } }
    }

    private suspend fun resolveCollections(vararg recommendations: Recommendation): List<Collection?> {
        val batch = Batch<RecommendationBatchKey, Collection>(recommendations.map(::RecommendationBatchKey))
        val environment = BatchLoaderEnvironment(
            recommendations.map { BatchContext(emptyMap(), it) },
        )
        controller.collection(auth, environment, batch)
        return batch.getResults()
    }

    // --- strategy ---

    @Test
    fun `strategy field requires admin`() = runTest {
        val strategyId = UUID.random()
        val recommendation = Recommendation(strategyId = strategyId, score = 0.5)
        val strategy = RecommendationStrategy(id = strategyId, name = "Test", type = RecommendationStrategyType.TRENDING)
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { strategyService.getById(strategyId) } returns strategy

        val result = controller.strategy(auth, recommendation)

        assertEquals("Test", result?.name)
        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
    }

    @Test
    fun `strategy field rejects non-admin`() = runTest {
        val recommendation = Recommendation(strategyId = UUID.random(), score = 0.5)
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.strategy(auth, recommendation)
        }
    }

    @Test
    fun `strategy is null without a lookup for content model recommendations`() = runTest {
        val recommendation = Recommendation(strategyId = UUID.NIL, score = 0.5)
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit

        assertNull(controller.strategy(auth, recommendation))

        coVerify(exactly = 0) { strategyService.getById(any()) }
    }

    // --- metadata ---

    @Test
    fun `metadata returns null when metadataId is null`() = runTest {
        val recommendation = Recommendation(strategyId = UUID.random(), metadataId = null, score = 0.5)

        val result = controller.metadata(auth, recommendation)

        assertNull(result)
    }

    @Test
    fun `metadata verifies VIEW permission`() = runTest {
        val metadataId = UUID.random()
        val recommendation = Recommendation(strategyId = UUID.random(), metadataId = metadataId, score = 0.5)
        val metadata = mockk<Metadata>()
        coEvery { metadataService.getById(metadataId) } returns metadata

        val result = controller.metadata(auth, recommendation)

        assertNotNull(result)
        coVerify { metadataPermissionEvaluator.verifyAllowed(auth, metadata, PermissionAction.VIEW) }
    }

    @Test
    fun `metadata throws when VIEW permission denied`() = runTest {
        val metadataId = UUID.random()
        val recommendation = Recommendation(strategyId = UUID.random(), metadataId = metadataId, score = 0.5)
        val metadata = mockk<Metadata>()
        coEvery { metadataService.getById(metadataId) } returns metadata
        coEvery { metadataPermissionEvaluator.verifyAllowed(auth, metadata, PermissionAction.VIEW) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.metadata(auth, recommendation)
        }
    }

    @Test
    fun `metadata returns null when metadata not found`() = runTest {
        val metadataId = UUID.random()
        val recommendation = Recommendation(strategyId = UUID.random(), metadataId = metadataId, score = 0.5)
        coEvery { metadataService.getById(metadataId) } returns null

        val result = controller.metadata(auth, recommendation)

        assertNull(result)
    }

    // --- collection ---

    @Test
    fun `collection returns null when collectionId is null`() = runTest {
        val recommendation = Recommendation(strategyId = UUID.random(), collectionId = null, score = 0.5)

        val result = resolveCollections(recommendation).single()

        assertNull(result)
    }

    @Test
    fun `collection verifies VIEW permission`() = runTest {
        val collectionId = UUID.random()
        val recommendation = Recommendation(strategyId = UUID.random(), collectionId = collectionId, score = 0.5)
        val collection = mockk<Collection>()
        every { collection.id } returns collectionId
        every { collection.languageTag } returns "en"
        coEvery { collectionService.getByIds(listOf(collectionId)) } returns listOf(collection)

        val result = resolveCollections(recommendation).single()

        assertNotNull(result)
        coVerify { collectionPermissionEvaluator.isAllowed(auth, listOf(collection), PermissionAction.VIEW) }
        coVerify(exactly = 0) { collectionService.addLanguageVariantsToBatch(any()) }
    }

    @Test
    fun `collection throws when VIEW permission denied`() = runTest {
        val collectionId = UUID.random()
        val recommendation = Recommendation(strategyId = UUID.random(), collectionId = collectionId, score = 0.5)
        val collection = mockk<Collection>()
        every { collection.id } returns collectionId
        every { collection.languageTag } returns "en"
        coEvery { collectionService.getByIds(listOf(collectionId)) } returns listOf(collection)
        coEvery {
            collectionPermissionEvaluator.isAllowed(auth, listOf(collection), PermissionAction.VIEW)
        } returns listOf(false)
        every { groupEvaluator.throwUnauthorized() } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            resolveCollections(recommendation)
        }
    }

    @Test
    fun `collection returns null when collection not found`() = runTest {
        val collectionId = UUID.random()
        val recommendation = Recommendation(strategyId = UUID.random(), collectionId = collectionId, score = 0.5)
        coEvery { collectionService.getByIds(listOf(collectionId)) } returns emptyList()

        val result = resolveCollections(recommendation).single()

        assertNull(result)
    }

    @Test
    fun `collection attaches and authorizes the selected language variant`() = runTest {
        val collectionId = UUID.random()
        val recommendation = Recommendation(
            strategyId = UUID.random(),
            collectionId = collectionId,
            collectionLanguageTag = "fr",
            score = 0.5,
        )
        val collection = Collection(
            id = collectionId,
            name = "English",
            languageTag = "en",
            workflowStateId = "published",
        )
        val variant = CollectionLanguageVariant(
            id = collectionId,
            languageTag = "fr",
            name = "Français",
        )
        coEvery { collectionService.getByIds(listOf(collectionId)) } returns listOf(collection)
        coEvery { collectionService.addLanguageVariantsToBatch(any()) } coAnswers {
            firstArg<Batch<CollectionCacheKeyId, List<CollectionLanguageVariant>>>()
                .setData(CollectionCacheKeyId(collectionId), listOf(variant))
        }

        val result = resolveCollections(recommendation).single()

        assertEquals(collection, result)
        assertNotSame(collection, result)
        assertEquals(variant, result?.defaultLanguageVariant)
        assertNull(collection.defaultLanguageVariant)
        coVerify { collectionPermissionEvaluator.isAllowed(auth, listOf(variant), PermissionAction.VIEW) }
    }

    @Test
    fun `collection omits a recommendation when its selected language variant is unavailable`() = runTest {
        val collectionId = UUID.random()
        val recommendation = Recommendation(
            strategyId = UUID.random(),
            collectionId = collectionId,
            collectionLanguageTag = "fr",
            score = 0.5,
        )
        val collection = Collection(
            id = collectionId,
            name = "English",
            languageTag = "en",
            workflowStateId = "published",
        )
        coEvery { collectionService.getByIds(listOf(collectionId)) } returns listOf(collection)

        val result = resolveCollections(recommendation).single()

        assertNull(result)
        coVerify(exactly = 0) {
            collectionPermissionEvaluator.isAllowed(auth, any<List<ICollection>>(), PermissionAction.VIEW)
        }
    }

    @Test
    fun `collection handles identity-less and valid recommendations in the same batch`() = runTest {
        val collectionId = UUID.random()
        val withoutCollection = Recommendation(strategyId = UUID.random(), score = 0.4)
        val withCollection = Recommendation(strategyId = UUID.random(), collectionId = collectionId, score = 0.5)
        val collection = Collection(
            id = collectionId,
            name = "English",
            languageTag = "en",
            workflowStateId = "published",
        )
        coEvery { collectionService.getByIds(listOf(collectionId)) } returns listOf(collection)

        val result = resolveCollections(withoutCollection, withCollection)

        assertNull(result[0])
        assertEquals(collection, result[1])
    }

    @Test
    fun `collection omits a translated recommendation when its source collection is unavailable`() = runTest {
        val collectionId = UUID.random()
        val recommendation = Recommendation(
            strategyId = UUID.random(),
            collectionId = collectionId,
            collectionLanguageTag = "fr",
            score = 0.5,
        )
        coEvery { collectionService.getByIds(listOf(collectionId)) } returns emptyList()

        val result = resolveCollections(recommendation).single()

        assertNull(result)
        coVerify(exactly = 0) { collectionService.addLanguageVariantsToBatch(any()) }
    }

    @Test
    fun `collection batches selected variants and keeps each result isolated`() = runTest {
        val collectionId = UUID.random()
        val englishRecommendation = Recommendation(
            strategyId = UUID.random(),
            collectionId = collectionId,
            collectionLanguageTag = "en",
            score = 0.7,
        )
        val frenchRecommendation = Recommendation(
            strategyId = UUID.random(),
            collectionId = collectionId,
            collectionLanguageTag = "fr",
            score = 0.6,
        )
        val collection = Collection(
            id = collectionId,
            name = "English",
            languageTag = "en",
            workflowStateId = "published",
        )
        val french = CollectionLanguageVariant(
            id = collectionId,
            languageTag = "fr",
            name = "Français",
        )
        coEvery { collectionService.getByIds(listOf(collectionId)) } returns listOf(collection)
        coEvery { collectionService.addLanguageVariantsToBatch(any()) } coAnswers {
            firstArg<Batch<CollectionCacheKeyId, List<CollectionLanguageVariant>>>()
                .setData(CollectionCacheKeyId(collectionId), listOf(french))
        }

        val results = resolveCollections(englishRecommendation, frenchRecommendation)

        assertEquals(collection, results[0])
        assertEquals(french, results[1]?.defaultLanguageVariant)
        assertNull(collection.defaultLanguageVariant)
        coVerify(exactly = 1) { collectionService.getByIds(listOf(collectionId)) }
        coVerify(exactly = 1) { collectionService.addLanguageVariantsToBatch(any()) }
        coVerify(exactly = 1) {
            collectionPermissionEvaluator.isAllowed(auth, listOf(collection, french), PermissionAction.VIEW)
        }
    }

    // --- scalar fields ---

    @Test
    fun `score returns recommendation score`() {
        val recommendation = Recommendation(strategyId = UUID.random(), score = 0.85)

        assertEquals(0.85, controller.score(recommendation))
    }

    @Test
    fun `sources resolves a batch including unavailable empty sources`() = runTest {
        val recommendations = listOf(Recommendation(strategyId = UUID.NIL), Recommendation(strategyId = UUID.random()))
        val expected = listOf(listOf(RecommendationSource.CONTENT_MODEL), emptyList())
        coEvery { recommendationService.getSources(recommendations) } returns expected
        val keys = recommendations.map(::RecommendationBatchKey)
        val batch = Batch<RecommendationBatchKey, List<RecommendationSource>>(keys)
        val environment = BatchLoaderEnvironment(recommendations.map { BatchContext(emptyMap(), it) })
        controller.sources(environment, batch)
        assertEquals(expected, keys.map { batch.getData(it) })
        coVerify(exactly = 1) { recommendationService.getSources(recommendations) }
    }

    @Test
    fun `reason returns recommendation reason`() {
        val recommendation = Recommendation(strategyId = UUID.random(), score = 0.5, reason = "Similar content")

        assertEquals("Similar content", controller.reason(recommendation))
    }

    // --- fallback ---

    private fun rec(context: kotlinx.serialization.json.JsonElement?) =
        Recommendation(strategyId = UUID.random(), score = 0.5, context = context)

    @Test
    fun `fallback is true when context carries the fallback marker the service writes`() {
        // Reads the exact marker the service sets, so the resolver stays wired to the producer.
        assertEquals(true, controller.fallback(rec(RecommendationServiceImpl.FALLBACK_CONTEXT)))
    }

    @Test
    fun `fallback is false for actual recommendations (no context, or unrelated context)`() {
        assertEquals(false, controller.fallback(rec(null)))
        assertEquals(false, controller.fallback(rec(JsonPrimitive("not-an-object")))) // context not a JsonObject
        assertEquals(false, controller.fallback(rec(JsonObject(mapOf("careFloor" to JsonPrimitive(0.5))))))
        assertEquals(false, controller.fallback(rec(JsonObject(mapOf("fallback" to JsonPrimitive(false))))))
        assertEquals(false, controller.fallback(rec(JsonObject(mapOf("fallback" to JsonPrimitive("yes")))))) // not a boolean
        assertEquals(false, controller.fallback(rec(JsonObject(mapOf("fallback" to JsonObject(emptyMap()))))))
    }

    // --- scalar field resolvers ---

    @Test
    fun `scalar field resolvers pass through from the model`() {
        val rec = Recommendation(
            id = UUID.random(),
            metadataId = UUID.random(),
            collectionId = UUID.random(),
            collectionLanguageTag = "fr",
            strategyId = UUID.random(),
            score = 0.5,
            context = JsonPrimitive("ctx"),
            expiresAt = bosca.serialization.OffsetDateTime.now(),
        )
        assertEquals(rec.id, controller.id(rec))
        assertEquals(rec.metadataId, controller.metadataId(rec))
        assertEquals(rec.collectionId, controller.collectionId(rec))
        assertEquals(rec.collectionLanguageTag, controller.collectionLanguageTag(rec))
        assertEquals(rec.context, controller.context(rec))
        assertEquals(rec.created, controller.created(rec))
        assertEquals(rec.expiresAt, controller.expiresAt(rec))
    }
}
