package bosca.content.metadata.routes

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.EntityPermission
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityException
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentCollaborationAccessTest {
    private val service = mockk<MetadataService>()
    private val security = mockk<SecurityService>()
    private val group = Group(id = UUID.random(), name = "document-editors", description = "", type = GroupType.PRINCIPAL)
    private val metadata = Metadata(
        id = UUID.random(), version = 3, name = "Private draft", type = MetadataType.STANDARD,
        contentType = "bosca/v-document", contentLength = 0, languageTag = "en", workflowStateId = "draft",
    )
    private val evaluator = MetadataPermissionEvaluator(service, security, GroupEvaluator(security))
    private val route = DocumentCollaborationAccess(evaluator, service)

    private fun authentication(scopes: List<String>) = mockk<AuthenticationContext> {
        every { principal() } returns ScopedAuthenticatedPrincipal(
            Principal(id = UUID.random(), anonymous = false), listOf(group), scopes, null, 1,
        )
    }

    private fun call(version: String? = "3", id: String? = metadata.id.toString()) = mockk<ServerCall> {
        every { pathParameters } returns Parameters(id?.let { mapOf("id" to listOf(it)) } ?: emptyMap())
        every { request.queryParameters } returns Parameters(version?.let { mapOf("version" to listOf(it)) } ?: emptyMap())
    }

    private fun grantEdit() {
        coEvery { service.getById(metadata.id, 3) } returns metadata
        val permission = mockk<EntityPermission> {
            every { action } returns PermissionAction.EDIT
            every { groupId } returns group.id
        }
        coEvery { service.getPermissions(metadata) } returns listOf(permission)
        coEvery { service.isParentAllowed(any(), any(), any()) } returns false
    }

    private suspend fun execute(authentication: AuthenticationContext, request: ServerCall = call()): HttpStatusCode {
        val method = DocumentCollaborationAccess::class.declaredMemberFunctions.single { it.name == "execute" }
        method.isAccessible = true
        try {
            return method.callSuspend(route, request, authentication) as HttpStatusCode
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    @Test
    fun `an explicit document edit grant does not allow a read-only token to collaborate`() = runTest {
        grantEdit()
        assertFailsWith<SecurityException> { execute(authentication(listOf("content:view"))) }
    }

    @Test
    fun `a permitted editor can authorize the requested version without downloading state`() = runTest {
        grantEdit()
        assertEquals(HttpStatusCode.OK, execute(authentication(listOf("content:edit"))))
        coVerify(exactly = 1) { service.getById(metadata.id, 3) }
    }

    @Test
    fun `a matching token scope still requires document permission`() = runTest {
        coEvery { service.getById(metadata.id, 3) } returns metadata
        coEvery { service.getPermissions(metadata) } returns emptyList()
        coEvery { service.isParentAllowed(any(), any(), any()) } returns false
        assertFailsWith<SecurityException> { execute(authentication(listOf("content:edit"))) }
    }

    @Test
    fun `missing metadata returns not found`() = runTest {
        coEvery { service.getById(metadata.id, 3) } returns null
        assertEquals(HttpStatusCode.NotFound, execute(authentication(listOf("content:edit"))))
        coVerify(exactly = 0) { service.getPermissions(any()) }
    }

    @Test
    fun `an omitted or malformed version uses the default document version`() = runTest {
        grantEdit()
        coEvery { service.getById(metadata.id, 1) } returns metadata
        for (version in listOf(null, "invalid", "2147483648")) {
            assertEquals(HttpStatusCode.OK, execute(authentication(listOf("content:edit")), call(version)))
        }
        coVerify(exactly = 3) { service.getById(metadata.id, 1) }
    }

    @Test
    fun `an absent document identifier fails before reading metadata`() = runTest {
        assertFailsWith<IllegalArgumentException> { execute(authentication(listOf("content:edit")), call(id = null)) }
        coVerify(exactly = 0) { service.getById(any(), any()) }
    }
}
