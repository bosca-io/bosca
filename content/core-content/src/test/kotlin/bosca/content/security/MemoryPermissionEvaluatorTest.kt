package bosca.content.security

import bosca.content.metadata.model.EmptyMetadata
import bosca.content.metadata.model.MemoryAttributes
import bosca.content.metadata.model.MemoryScope
import bosca.content.metadata.model.MetadataPermission
import bosca.content.metadata.service.MetadataService
import bosca.graphql.Batch
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.EntityPermission
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryPermissionEvaluatorTest {
    private val metadata = mockk<MetadataService>(relaxed = true)
    private val security = mockk<SecurityService>()
    private val group = Group(UUID.random(), "readers", "", GroupType.PRINCIPAL)
    private val principal = Principal(id = UUID.random(), anonymous = false)
    private val auth = object : AuthenticationContext(null, null) {
        override fun principal() = ScopedAuthenticatedPrincipal(principal, listOf(group), listOf("content:view"), null, 1)
    }
    private val evaluator = MetadataPermissionEvaluator(metadata, security, GroupEvaluator(security))
    private val memory = EmptyMetadata.copy(
        id = UUID.random(), contentType = MemoryAttributes.CONTENT_TYPE,
        systemAttributes = Json.encodeToJsonElement(MemoryAttributes.serializer(), MemoryAttributes(
            MemoryScope.COLLECTION, principal.id, UUID.random(), UUID.random(),
            expires = OffsetDateTime.now().minusDays(1),
        )),
    )

    @Test
    fun `memory attributes do not override administrator content access`() = runTest {
        val admin = Group(UUID.random(), "administrators", "", GroupType.SYSTEM)
        val authentication = object : AuthenticationContext(null, null) {
            override fun principal() = AuthenticatedPrincipal(principal, listOf(admin))
        }
        for (item in listOf(memory, memory.copy(systemAttributes = null), memory.copy(deleted = true))) {
            assertTrue(evaluator.isAllowed(authentication, item, PermissionAction.VIEW))
            assertTrue(evaluator.isContentAllowed(authentication, item, PermissionAction.VIEW))
        }
        coVerify(exactly = 0) { metadata.getById(any()) }
    }

    @Test
    fun `memory content and batch reads use ordinary group grants`() = runTest {
        val grant = MetadataPermission(memory.id, group.id, PermissionAction.VIEW)
        coEvery { metadata.getPermissions(memory) } returns listOf(grant)
        coEvery { metadata.addPermissionsToBatch(any()) } coAnswers {
            firstArg<Batch<UUID, List<EntityPermission>>>().setData(memory.id, listOf(grant))
        }
        assertTrue(evaluator.isAllowed(auth, memory, PermissionAction.VIEW))
        assertTrue(evaluator.isContentAllowed(auth, memory, PermissionAction.VIEW))
        assertEquals(listOf(memory), evaluator.filterAllowed(auth, listOf(memory), PermissionAction.VIEW))
        coEvery { metadata.getPermissions(memory) } returns emptyList()
        assertFalse(evaluator.isAllowed(auth, memory, PermissionAction.VIEW))
        coVerify(exactly = 0) { metadata.getById(any()) }
    }

    @Test
    fun `published public memories retain ordinary public visibility`() = runTest {
        val published = memory.copy(public = true, publicContent = true, workflowStateId = "published")
        assertTrue(evaluator.isAllowed(null, published, PermissionAction.VIEW))
        assertFalse(evaluator.isAllowed(null, published, PermissionAction.LIST))
        assertTrue(evaluator.isContentAllowed(null, published, PermissionAction.VIEW))
        coVerify(exactly = 0) { metadata.getById(any()) }
    }
}
