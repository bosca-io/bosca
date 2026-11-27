package bosca.content.metadata.routes

import bosca.content.metadata.model.DocumentCollaborationInput
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.Parameters
import bosca.server.ServerCall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible

class MergeDocumentCollaborationTest {
    private val permissions = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val metadata = mockk<MetadataService>()
    private val documents = mockk<DocumentService>()
    private val auth = mockk<AuthenticationContext>()
    private val entity = mockk<Metadata>()
    private val id = UUID.random()
    private val route = MergeDocumentCollaboration(permissions, metadata, documents)

    private suspend fun execute(call: ServerCall): ByteArray? {
        val method = MergeDocumentCollaboration::class.declaredMemberFunctions.first { it.name == "execute" }
        method.isAccessible = true
        try { return method.callSuspend(route, call, auth) as ByteArray? }
        catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.cause ?: failure }
    }

    private fun call(body: ByteArray): ServerCall = mockk<ServerCall> {
        every { pathParameters } returns Parameters(mapOf("id" to listOf(id.toString())))
        every { request.queryParameters } returns Parameters(mapOf("version" to listOf("3")))
        coEvery { request.bodyBytes() } returns body
    }

    @Test
    fun `empty request initializes and returns binary state under Content edit permission`() = runTest {
        coEvery { metadata.getById(id, 3) } returns entity
        coEvery { documents.initializeCollaboration(id, 3) } returns byteArrayOf(0, 0)
        assertContentEquals(byteArrayOf(0, 0), execute(call(ByteArray(0))))
        coVerify { permissions.verifyContentAllowed(auth, entity, PermissionAction.EDIT) }
        coVerify(exactly = 0) { documents.mergeCollaboration(any()) }
    }

    @Test
    fun `update returns the merged durable state rather than echoing the input`() = runTest {
        coEvery { metadata.getById(id, 3) } returns entity
        val input = slot<DocumentCollaborationInput>()
        coEvery { documents.mergeCollaboration(capture(input)) } returns byteArrayOf(4, 5)
        assertContentEquals(byteArrayOf(4, 5), execute(call(byteArrayOf(1, 2))))
        assertEquals(id, input.captured.metadataId)
        assertEquals(3, input.captured.version)
        assertContentEquals(byteArrayOf(1, 2), input.captured.content)
        coVerify(exactly = 0) { documents.initializeCollaboration(any(), any()) }
    }

    @Test
    fun `denied access never initializes or persists collaboration`() = runTest {
        coEvery { metadata.getById(id, 3) } returns entity
        coEvery { permissions.verifyContentAllowed(auth, entity, PermissionAction.EDIT) } throws IllegalStateException("denied")
        assertFailsWith<IllegalStateException> { execute(call(ByteArray(0))) }
        coVerify(exactly = 0) {
            documents.initializeCollaboration(any(), any())
            documents.mergeCollaboration(any())
        }
    }

    @Test
    fun `missing metadata stays inaccessible without creating state`() = runTest {
        coEvery { metadata.getById(id, 3) } returns null
        assertNull(execute(call(ByteArray(0))))
        coVerify(exactly = 0) { permissions.verifyContentAllowed(any(), any(), any()) }
    }
}
