package bosca.content.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.find.FindAttributeInput
import bosca.content.find.FindAttributesInput
import bosca.content.find.FindQueryInput
import bosca.content.security.CollectionPermissionEvaluator
import bosca.di.asProvider
import bosca.graphql.Batch
import bosca.security.model.*
import bosca.security.service.*
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** The actual API controller/evaluator retain scope, group and public-read checks on filtered results. */
class CollectionFindPermissionTest {
    private val service = mockk<CollectionService>()
    private val security = mockk<SecurityService>(relaxed = true)
    private val groups = GroupEvaluator(security)
    private val evaluator = CollectionPermissionEvaluator(service, security, groups)
    private val controller = ContentController(mockk(), mockk(), mockk(), service.asProvider(), evaluator.asProvider(), mockk(), mockk(), mockk(), groups)
    private val group = Group(UUID.random(), "topic-readers", "", GroupType.PRINCIPAL)
    private val allowed = Collection(UUID.random(), "Allowed topic", "en", workflowStateId = "published")
    private val hidden = allowed.copy(id = UUID.random(), name = "Hidden topic")
    private val public = allowed.copy(id = UUID.random(), name = "Public topic", public = true)
    private val query = FindQueryInput(attributes = listOf(FindAttributesInput(listOf(FindAttributeInput("type", "Topic")))), offset = 20, limit = 10)

    @BeforeTest fun setup() {
        coEvery { service.find(query) } returns listOf(allowed, hidden, public)
        val grant = mockk<EntityPermission> {
            every { entityId } returns allowed.id; every { groupId } returns group.id; every { action } returns PermissionAction.VIEW
        }
        coEvery { service.addPermissionsToBatch(any()) } coAnswers {
            val batch = firstArg<Batch<UUID, List<EntityPermission>>>()
            for (key in batch.keys) batch.setData(key, if (key == allowed.id) listOf(grant) else emptyList())
        }
        coEvery { service.isParentAllowed(any(), any(), any()) } returns false
    }

    private fun authentication(scopes: List<String>, current: List<Group> = listOf(group), ceiling: Set<UUID>? = null) =
        object : AuthenticationContext(null, null) {
            override fun principal() = ScopedAuthenticatedPrincipal(Principal(id = UUID.random(), anonymous = false), current, scopes, ceiling, 1)
        }

    @Test fun `ordinary scoped callers get only permitted matching collections`(): Unit = runTest {
        assertEquals(listOf(allowed, public), controller.findCollections(authentication(listOf("collections:view")), query))
        coVerify(exactly = 1) { service.find(query) }
    }

    @Test fun `scope and current membership ceilings continue to constrain discovery`(): Unit = runTest {
        assertEquals(listOf(public), controller.findCollections(authentication(listOf("content:view")), query))
        assertEquals(listOf(public), controller.findCollections(authentication(listOf("collections:view"), current = emptyList()), query))
        assertEquals(listOf(public), controller.findCollections(authentication(listOf("collections:view"), ceiling = emptySet()), query))
    }
}
