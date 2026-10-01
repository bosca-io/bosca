package bosca.content.metadata.routes

import bosca.content.metadata.model.DocumentCollaboration
import bosca.content.metadata.model.DocumentCollaborationInput
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import bosca.server.ServerRequest
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import yks.utils.Doc
import yks.utils.encodeStateAsUpdate
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SetDocumentCollaborationCoverageTest {

    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val service = mockk<MetadataService>()
    private val documentService = mockk<DocumentService>()
    private val authenticationContext = mockk<AuthenticationContext>()

    private val route = SetDocumentCollaboration(metadataPermissionEvaluator, service, documentService)

    private val testId = UUID.parse("00000000-0000-0000-0000-000000000001")

    private fun metadata(version: Int = 1) = Metadata(
        id = testId,
        version = version,
        name = "Test Document",
        type = MetadataType.STANDARD,
        contentType = "bosca/v-document",
        contentLength = 0,
        languageTag = "en",
        workflowStateId = "published",
    )

    /** Produces a valid V1 CRDT update carrying a "text" field with the given value. */
    private fun docUpdate(text: String): ByteArray {
        val doc = Doc()
        doc.getText("text").insert(0, text)
        return encodeStateAsUpdate(doc)
    }

    private fun createCall(
        pathParams: Map<String, String> = mapOf("id" to testId.toString()),
        queryParams: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
    ): ServerCall {
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.queryParameters } returns Parameters(queryParams.mapValues { listOf(it.value) })
        coEvery { request.bodyBytes() } returns body
        val call = mockk<ServerCall>(relaxed = true)
        every { call.request } returns request
        every { call.pathParameters } returns Parameters(pathParams.mapValues { listOf(it.value) })
        return call
    }

    private suspend fun executeRoute(call: ServerCall, auth: AuthenticationContext): HttpStatusCode {
        val method = SetDocumentCollaboration::class.declaredMemberFunctions
            .first { it.name == "execute" && it.parameters.size == 3 }
        method.isAccessible = true
        try {
            return method.callSuspend(route, call, auth) as HttpStatusCode
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun `stores body directly when no existing collaboration`() = runTest {
        val body = docUpdate("hello")
        val call = createCall(body = body)
        coEvery { service.getById(testId, 1) } returns metadata()
        coEvery { documentService.getCollaboration(testId, 1) } returns null
        val captured = slot<DocumentCollaborationInput>()
        coEvery { documentService.setCollaboration(capture(captured)) } returns true

        val result = executeRoute(call, authenticationContext)

        assertSame(HttpStatusCode.Accepted, result)
        assertEquals(testId, captured.captured.metadataId)
        assertEquals(1, captured.captured.version)
        assertTrue(body.contentEquals(captured.captured.content))
        coVerify {
            metadataPermissionEvaluator.verifyContentAllowed(
                authenticationContext,
                any(),
                PermissionAction.EDIT,
            )
        }
    }

    @Test
    fun `stores body directly when existing collaboration content is empty`() = runTest {
        val body = docUpdate("world")
        val call = createCall(body = body)
        coEvery { service.getById(testId, 1) } returns metadata()
        coEvery { documentService.getCollaboration(testId, 1) } returns DocumentCollaboration(
            metadataId = testId,
            version = 1,
            content = ByteArray(0),
        )
        val captured = slot<DocumentCollaborationInput>()
        coEvery { documentService.setCollaboration(capture(captured)) } returns true

        val result = executeRoute(call, authenticationContext)

        assertSame(HttpStatusCode.Accepted, result)
        assertTrue(body.contentEquals(captured.captured.content))
    }

    @Test
    fun `merges existing collaboration content with body when both present`() = runTest {
        val existing = docUpdate("existing text ")
        val body = docUpdate("new text")
        val call = createCall(body = body)
        coEvery { service.getById(testId, 1) } returns metadata()
        coEvery { documentService.getCollaboration(testId, 1) } returns DocumentCollaboration(
            metadataId = testId,
            version = 1,
            content = existing,
        )
        val captured = slot<DocumentCollaborationInput>()
        coEvery { documentService.setCollaboration(capture(captured)) } returns true

        val result = executeRoute(call, authenticationContext)

        assertSame(HttpStatusCode.Accepted, result)
        // The merged output is a re-encoded state of applying both updates; it should not be
        // byte-identical to either input, and it must be non-empty.
        val merged = captured.captured.content
        assertTrue(merged != null && merged.isNotEmpty())
        assertTrue(!body.contentEquals(merged))
    }

    @Test
    fun `uses version query parameter when present`() = runTest {
        val body = docUpdate("v3")
        val call = createCall(queryParams = mapOf("version" to "3"), body = body)
        coEvery { service.getById(testId, 3) } returns metadata(version = 3)
        coEvery { documentService.getCollaboration(testId, 3) } returns null
        val captured = slot<DocumentCollaborationInput>()
        coEvery { documentService.setCollaboration(capture(captured)) } returns true

        val result = executeRoute(call, authenticationContext)

        assertSame(HttpStatusCode.Accepted, result)
        assertEquals(3, captured.captured.version)
        coVerify { service.getById(testId, 3) }
        coVerify { documentService.getCollaboration(testId, 3) }
    }

    @Test
    fun `defaults version to 1 when query parameter is not a number`() = runTest {
        val body = docUpdate("x")
        val call = createCall(queryParams = mapOf("version" to "not-a-number"), body = body)
        coEvery { service.getById(testId, 1) } returns metadata()
        coEvery { documentService.getCollaboration(testId, 1) } returns null
        coEvery { documentService.setCollaboration(any()) } returns true

        val result = executeRoute(call, authenticationContext)

        assertSame(HttpStatusCode.Accepted, result)
        coVerify { service.getById(testId, 1) }
    }

    @Test
    fun `throws IllegalArgumentException when id path parameter is missing`() = runTest {
        val call = createCall(pathParams = emptyMap())

        val ex = assertFailsWith<IllegalArgumentException> {
            executeRoute(call, authenticationContext)
        }
        assertEquals("id is required", ex.message)
    }

    @Test
    fun `throws NoSuchElementException when metadata not found`() = runTest {
        val call = createCall()
        coEvery { service.getById(testId, 1) } returns null

        val ex = assertFailsWith<NoSuchElementException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(ex.message?.contains("No metadata found") == true)
    }

    @Test
    fun `propagates permission denial from evaluator`() = runTest {
        val call = createCall(body = docUpdate("x"))
        val md = metadata()
        coEvery { service.getById(testId, 1) } returns md
        coEvery {
            metadataPermissionEvaluator.verifyContentAllowed(
                authenticationContext,
                md,
                PermissionAction.EDIT,
            )
        } throws IllegalStateException("unauthorized")

        val ex = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertEquals("unauthorized", ex.message)
        coVerify(exactly = 0) { documentService.setCollaboration(any()) }
    }
}
