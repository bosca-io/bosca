package bosca.recommendations.graphql

import bosca.recommendations.model.RecommendationContext
import bosca.recommendations.model.RecommendationContextModel
import bosca.recommendations.model.RecommendationSimilarityWeights
import bosca.recommendations.model.RecommendationTrainingStatus
import bosca.recommendations.model.RecommendationTypePreference
import bosca.recommendations.model.RecommendationWeights
import bosca.recommendations.service.RecommendationContextService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecommendationContextModelsControllerTest {
    private val service = mockk<RecommendationContextService>(relaxed = true)
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val authentication = mockk<AuthenticationContext>(relaxed = true)
    private val context = RecommendationContext(id = UUID.random(), type = "reading", name = "Reading")
    private val model = RecommendationContextModel(
        version = 12, contextId = context.id, revision = 4, selectionRevision = 9, context = context,
    )

    @Test
    fun `model reads and mutations authorize before delegating exact versions and candidates`() = runTest {
        val query = RecommendationContextsController(service, groups, mockk())
        val mutation = RecommendationContextsMutationController(service, groups, mockk())
        val ids = listOf(UUID.random(), UUID.random())
        coEvery { service.getModels(context.id) } returns listOf(model)
        coEvery { service.getServingModels() } returns listOf(model)
        coEvery { service.trainModel(context.id) } returns model
        coEvery { service.activateModel(context.id, 12) } returns model
        coEvery { service.pinModel(context.id, 12, false) } returns model

        assertEquals(listOf(model), query.models(authentication, context.id))
        assertEquals(listOf(model), query.servingModels(authentication))
        assertTrue(mutation.recompute(authentication))
        assertEquals(model, mutation.trainModel(authentication, context.id))
        assertEquals(model, mutation.activateModel(authentication, context.id, 12))
        assertEquals(model, mutation.pinModel(authentication, context.id, 12, false))
        assertTrue(mutation.modelExported(authentication, 12, false))
        assertTrue(mutation.deleteModel(authentication, context.id, 12))

        coVerifyOrder {
            groups.verifyHasAdminGroup(authentication)
            service.getModels(context.id)
            groups.verifyHasAdminGroup(authentication)
            service.getServingModels()
            groups.verifyHasAdminGroup(authentication)
            service.queueRecompute()
            groups.verifyHasAdminGroup(authentication)
            service.trainModel(context.id)
            groups.verifyHasAdminGroup(authentication)
            service.activateModel(context.id, 12)
            groups.verifyHasAdminGroup(authentication)
            service.pinModel(context.id, 12, false)
            groups.verifyHasAdminGroup(authentication)
            service.exportModel(12, false)
            groups.verifyHasAdminGroup(authentication)
            service.deleteModel(context.id, 12)
        }
    }

    @Test
    fun `non administrators cannot read models or change their lifecycle`() = runTest {
        coEvery { groups.verifyHasAdminGroup(authentication) } throws IllegalStateException("Denied")
        val query = RecommendationContextsController(service, groups, mockk())
        val mutation = RecommendationContextsMutationController(service, groups, mockk())
        assertFailsWith<IllegalStateException> { query.models(authentication, context.id) }
        assertFailsWith<IllegalStateException> { query.servingModels(authentication) }
        assertFailsWith<IllegalStateException> { mutation.recompute(authentication) }
        assertFailsWith<IllegalStateException> { mutation.trainModel(authentication, context.id) }
        assertFailsWith<IllegalStateException> { mutation.activateModel(authentication, context.id, 12) }
        assertFailsWith<IllegalStateException> { mutation.pinModel(authentication, context.id, 12, true) }
        assertFailsWith<IllegalStateException> { mutation.modelExported(authentication, 12, true) }
        assertFailsWith<IllegalStateException> { mutation.deleteModel(authentication, context.id, 12) }
        coVerify(exactly = 0) { service.getModels(any()) }
        coVerify(exactly = 0) { service.getServingModels() }
        coVerify(exactly = 0) { service.queueRecompute() }
        coVerify(exactly = 0) { service.trainModel(any()) }
        coVerify(exactly = 0) { service.activateModel(any(), any()) }
        coVerify(exactly = 0) { service.pinModel(any(), any(), any()) }
        coVerify(exactly = 0) { service.exportModel(any(), any()) }
        coVerify(exactly = 0) { service.deleteModel(any(), any()) }
    }

    @Test
    fun `Studio model fields distinguish requested and active revisions and preserve lifecycle details`() {
        val controller = RecommendationContextModelController()
        val now = OffsetDateTime.now()
        val failed = model.copy(status = RecommendationTrainingStatus.FAILED, exported = true,
            personalized = true, pinned = true, failure = "Load failed", started = now, completed = now)
        assertEquals(failed.context, controller.context(failed))
        assertEquals(12L, controller.version(failed))
        assertEquals(context.id, controller.contextId(failed))
        assertEquals(4L, controller.revision(failed))
        assertEquals(9L, controller.selectionRevision(failed))
        assertEquals(RecommendationTrainingStatus.FAILED, controller.status(failed))
        assertTrue(controller.exported(failed))
        assertTrue(controller.personalized(failed))
        assertTrue(controller.pinned(failed))
        assertEquals("Load failed", controller.failure(failed))
        assertEquals(model.created, controller.created(failed))
        assertEquals(now, controller.started(failed))
        assertEquals(now, controller.completed(failed))
        assertNull(controller.failure(model))
        assertNull(controller.started(model))
        assertNull(controller.completed(model))
        assertEquals("recommender-${context.id}-content", controller.contentModelName(model))
        assertEquals("recommender-${context.id}-personalized", controller.personalizedModelName(model))
        val selected = context.copy(revision = 4, selectionRevision = 9, activeModelVersion = 11, requestedModelVersion = 12)
        val contexts = RecommendationContextController()
        assertEquals(4L, contexts.revision(selected))
        assertEquals(9L, contexts.selectionRevision(selected))
        assertEquals(11L, contexts.activeModelVersion(selected))
        assertEquals(12L, contexts.requestedModelVersion(selected))
        assertNull(contexts.activeModelVersion(context))
        assertNull(contexts.requestedModelVersion(context))
    }

    @Test
    fun `Studio exposes independent importance values including explicit zero`() {
        val similarity = RecommendationSimilarityWeights(semantic = 0.0, categories = 0.1, labels = 0.2,
            language = 0.3, mime = 0.4, type = 0.5, collections = 0.6)
        val preference = RecommendationTypePreference("devotional", 0.0)
        val weights = RecommendationWeights(similarity = similarity, typePreferences = listOf(preference),
            defaultTypePreference = 0.1, content = 0.2, coEngagement = 0.3, cohortCoEngagement = 0.4,
            learnedNeighbor = 0.5, personalization = 0.6, rating = 0.7)
        val fields = RecommendationWeightsController()
        assertEquals(weights, RecommendationContextController().weights(context.copy(weights = weights)))
        assertEquals(similarity, fields.similarity(weights))
        assertEquals(listOf(preference), fields.typePreferences(weights))
        assertEquals(listOf(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7), listOf(
            fields.defaultTypePreference(weights), fields.content(weights), fields.coEngagement(weights),
            fields.cohortCoEngagement(weights), fields.learnedNeighbor(weights), fields.personalization(weights), fields.rating(weights)))
        val signals = RecommendationSimilarityWeightsController()
        assertEquals(listOf(0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6), listOf(
            signals.semantic(similarity), signals.categories(similarity), signals.labels(similarity),
            signals.language(similarity), signals.mime(similarity), signals.type(similarity), signals.collections(similarity)))
        assertEquals("devotional", RecommendationTypePreferenceController().type(preference))
        assertEquals(0.0, RecommendationTypePreferenceController().weight(preference))
    }
}
