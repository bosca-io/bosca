package bosca.recommendations.graphql

import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.recommendations.model.RecommendationContext
import bosca.recommendations.model.RecommendationContextInput
import bosca.recommendations.model.RecommendationContextModel
import bosca.recommendations.service.RecommendationContextService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Exercises the real group and artifact evaluators with restricted API-token identities. */
class RecommendationModelExportAuthorizationTest {
    private val service = mockk<RecommendationContextService>(relaxed = true)
    private val controller = RecommendationContextsMutationController(
        service, GroupEvaluator(mockk()), ArtifactPermissionEvaluator(mockk()),
    )
    private val query = RecommendationContextsController(
        service, GroupEvaluator(mockk()), ArtifactPermissionEvaluator(mockk()),
    )
    private val context = RecommendationContext(id = UUID.random(), type = "reading", name = "Reading")
    private val model = RecommendationContextModel(
        version = 12, contextId = context.id, revision = 4, selectionRevision = 9, context = context,
    )
    private val admin = Group(id = UUID.random(), name = "administrators", description = "", type = GroupType.SYSTEM)

    private fun authentication(principal: AuthenticatedPrincipal?) = object : AuthenticationContext(null, null) {
        override fun principal() = principal
    }

    private fun token(scopes: List<String>?, groups: List<Group> = emptyList(), allowedGroups: Set<UUID>? = null) =
        authentication(ScopedAuthenticatedPrincipal(Principal(), groups, scopes, allowedGroups, 119))

    @Test
    fun `existing ML artifact scopes report exports without administrator membership`() = runTest {
        coEvery { service.getModel(12) } returns model
        assertTrue(controller.modelExported(token(listOf("artifacts:pull", "artifacts:push")), 12, true))
        coVerify(exactly = 1) { service.exportModel(12, true) }
    }

    @Test
    fun `exact content permission permits content export and both permissions permit personalized export`() = runTest {
        coEvery { service.getModel(12) } returns model
        val content = "artifacts:ml:model/${model.contentModelName}:12:push"
        val personalized = "artifacts:ml:model/${model.personalizedModelName}:12:push"
        assertTrue(controller.modelExported(token(listOf(content)), 12, false))
        assertTrue(controller.modelExported(token(listOf(content, personalized)), 12, true))
        coVerify(exactly = 1) { service.exportModel(12, false) }
        coVerify(exactly = 1) { service.exportModel(12, true) }
    }

    @Test
    fun `pull analytics and unrelated artifact permissions cannot report an export`() = runTest {
        coEvery { service.getModel(12) } returns model
        val denied = listOf(
            emptyList(), null, listOf("artifacts:pull"), listOf("analytics:execute"),
            listOf("artifacts:ml:model/${model.contentModelName}:12:pull"),
            listOf("artifacts:ml:other/${model.contentModelName}:12:push"),
            listOf("artifacts:ml:model/recommender-other-content:12:push"),
            listOf("artifacts:ml:model/${model.contentModelName}:13:push"),
            listOf("artifacts:docker:model/${model.contentModelName}:12:push"),
            listOf("artifacts:ml:model/${model.personalizedModelName}:12:push"),
        )
        for (scopes in denied) {
            assertFailsWith<java.lang.SecurityException> { controller.modelExported(token(scopes), 12, false) }
        }
        coVerify(exactly = 0) { service.exportModel(any(), any()) }
    }

    @Test
    fun `content push alone cannot claim a personalized export`() = runTest {
        coEvery { service.getModel(12) } returns model
        val auth = token(listOf("artifacts:ml:model/${model.contentModelName}:12:push"))
        assertFailsWith<java.lang.SecurityException> { controller.modelExported(auth, 12, true) }
        coVerify(exactly = 0) { service.exportModel(any(), any()) }
    }

    @Test
    fun `artifact tokens cannot edit delete train activate or pin contexts`() = runTest {
        val auth = token(listOf("artifacts:pull", "artifacts:push"), listOf(admin))
        val input = RecommendationContextInput(type = "reading", name = "Reading")
        assertFailsWith<bosca.security.service.SecurityException> { controller.add(auth, input) }
        assertFailsWith<bosca.security.service.SecurityException> { controller.edit(auth, context.id, input) }
        assertFailsWith<bosca.security.service.SecurityException> { controller.trainModel(auth, context.id) }
        assertFailsWith<bosca.security.service.SecurityException> { controller.recompute(auth) }
        assertFailsWith<bosca.security.service.SecurityException> { controller.delete(auth, context.id) }
        assertFailsWith<bosca.security.service.SecurityException> { controller.activateModel(auth, context.id, 12) }
        assertFailsWith<bosca.security.service.SecurityException> { controller.pinModel(auth, context.id, 12, true) }
        coVerify(exactly = 0) { service.add(any()) }
        coVerify(exactly = 0) { service.edit(any(), any()) }
        coVerify(exactly = 0) { service.trainModel(any()) }
        coVerify(exactly = 0) { service.queueRecompute() }
        coVerify(exactly = 0) { service.delete(any()) }
        coVerify(exactly = 0) { service.activateModel(any(), any()) }
        coVerify(exactly = 0) { service.pinModel(any(), any(), any()) }
    }

    @Test
    fun `anonymous and ordinary sessions cannot report exports`() = runTest {
        for (principal in listOf(null, AuthenticatedPrincipal(Principal(), emptyList()))) {
            assertFailsWith<bosca.security.service.SecurityException> {
                controller.modelExported(authentication(principal), 12, false)
            }
        }
        coVerify(exactly = 0) { service.getModel(any()) }
        coVerify(exactly = 0) { service.exportModel(any(), any()) }
    }

    @Test
    fun `existing administrator access is preserved but token group restrictions still apply`() = runTest {
        coEvery { service.getModel(12) } returns model
        assertTrue(controller.modelExported(authentication(AuthenticatedPrincipal(Principal(), listOf(admin))), 12, false))
        assertTrue(controller.modelExported(token(listOf("security:manage"), listOf(admin)), 12, false))
        assertFailsWith<java.lang.SecurityException> {
            controller.modelExported(token(listOf("security:manage"), listOf(admin), emptySet()), 12, false)
        }
        coVerify(exactly = 2) { service.exportModel(12, false) }
    }

    @Test
    fun `missing models and service failures do not report success`() = runTest {
        val auth = token(listOf("artifacts:push"))
        coEvery { service.getModel(12) } returns null
        assertFailsWith<NoSuchElementException> { controller.modelExported(auth, 12, false) }
        coVerify(exactly = 0) { service.exportModel(any(), any()) }
        coEvery { service.getModel(12) } returns model
        coEvery { service.exportModel(12, false) } throws IllegalArgumentException("Model is not running")
        assertFailsWith<IllegalArgumentException> { controller.modelExported(auth, 12, false) }
    }

    @Test
    fun `loader discovery filters contexts and versions by exact artifact pull permission`() = runTest {
        val otherContext = context.copy(id = UUID.random(), type = "other")
        val otherModel = model.copy(version = 14, contextId = otherContext.id, context = otherContext)
        val history = listOf(model, model.copy(version = 13))
        coEvery { service.getAll() } returns listOf(context, otherContext)
        coEvery { service.getModels(context.id) } returns history
        coEvery { service.getModels(otherContext.id) } returns listOf(otherModel)
        coEvery { service.getServingModels() } returns history + otherModel
        val auth = token(listOf("artifacts:ml:model/${model.contentModelName}:12:pull"))
        kotlin.test.assertEquals(listOf(context), query.all(auth))
        kotlin.test.assertEquals(listOf(model), query.models(auth, context.id))
        kotlin.test.assertEquals(emptyList(), query.models(auth, otherContext.id))
        kotlin.test.assertEquals(listOf(model), query.servingModels(auth))
        assertFailsWith<bosca.security.service.SecurityException> { query.context(auth, context.id) }
        assertFailsWith<bosca.security.service.SecurityException> { query.contextByType(auth, context.type) }
    }

    @Test
    fun `personalized model discovery requires access to both exports`() = runTest {
        val personalized = model.copy(personalized = true)
        coEvery { service.getAll() } returns listOf(context)
        coEvery { service.getModels(context.id) } returns listOf(personalized)
        coEvery { service.getServingModels() } returns listOf(personalized)
        val contentOnly = token(listOf("artifacts:ml:model/${model.contentModelName}:12:pull"))
        kotlin.test.assertEquals(emptyList(), query.all(contentOnly))
        kotlin.test.assertEquals(emptyList(), query.models(contentOnly, context.id))
        kotlin.test.assertEquals(emptyList(), query.servingModels(contentOnly))
        val loader = token(listOf("artifacts:pull"))
        kotlin.test.assertEquals(listOf(context), query.all(loader))
        kotlin.test.assertEquals(listOf(personalized), query.models(loader, context.id))
        kotlin.test.assertEquals(listOf(personalized), query.servingModels(loader))
        for (scopes in listOf(null, emptyList(), listOf("analytics:execute"), listOf("artifacts:docker:*:*:pull"))) {
            kotlin.test.assertEquals(emptyList(), query.servingModels(token(scopes)))
        }
    }

    @Test
    fun `anonymous sessions cannot discover models and administrator access is preserved`() = runTest {
        for (principal in listOf(null, AuthenticatedPrincipal(Principal(), emptyList()))) {
            val auth = authentication(principal)
            assertFailsWith<bosca.security.service.SecurityException> { query.all(auth) }
            assertFailsWith<bosca.security.service.SecurityException> { query.models(auth, context.id) }
            assertFailsWith<bosca.security.service.SecurityException> { query.servingModels(auth) }
        }
        coVerify(exactly = 0) { service.getAll() }
        coVerify(exactly = 0) { service.getModels(any()) }
        coVerify(exactly = 0) { service.getServingModels() }
        coEvery { service.getAll() } returns listOf(context)
        coEvery { service.getModels(context.id) } returns listOf(model)
        coEvery { service.getServingModels() } returns listOf(model)
        val auth = token(listOf("security:manage"), listOf(admin))
        kotlin.test.assertEquals(listOf(context), query.all(auth))
        kotlin.test.assertEquals(listOf(model), query.models(auth, context.id))
        kotlin.test.assertEquals(listOf(model), query.servingModels(auth))
    }
}
