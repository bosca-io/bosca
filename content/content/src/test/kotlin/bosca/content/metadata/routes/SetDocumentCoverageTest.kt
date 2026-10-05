package bosca.content.metadata.routes

import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.CollaborationSyncMode
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.documents.Content
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.ContentType
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
import kotlinx.serialization.json.Json
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SetDocumentCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val documentService = mockk<DocumentService>(relaxed = true)
    private val authenticationContext = mockk<AuthenticationContext>()

    // The route's own injected Json (used only by the form-urlencoded branch). A plain default
    // Json instance is sufficient: DocumentInput serializes without any custom contextual serializers
    // except UUID/Content, which are only present when non-null. All fixtures here keep those null.
    private val routeJson = Json { ignoreUnknownKeys = true; isLenient = true }

    private val route = SetDocument(metadataService, metadataPermissionEvaluator, documentService, routeJson)

    private val testId = UUID.parse("00000000-0000-0000-0000-000000000001")

    private val testMetadata = Metadata(
        id = testId,
        version = 1,
        name = "Test Doc",
        type = MetadataType.STANDARD,
        contentType = "bosca/v-document",
        contentLength = 0,
        languageTag = "en",
        workflowStateId = "published",
    )

    private fun documentInput(title: String = "Hello") = DocumentInput(
        templateMetadataId = null,
        templateMetadataVersion = null,
        title = title,
        content = Content(),
    )

    private fun createCall(
        pathParams: Map<String, String> = mapOf("id" to testId.toString(), "version" to "1"),
        queryParams: Map<String, String> = emptyMap(),
        contentType: ContentType? = null,
        bodyText: String = "",
        formParameters: Parameters = Parameters.Empty,
    ): ServerCall {
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.contentType() } returns contentType
        every { request.queryParameters } returns Parameters(queryParams.mapValues { listOf(it.value) })
        // Read by the inline ServerCall.receive<T>() body (JSON branch only).
        coEvery { request.bodyText() } returns bodyText

        val application = mockk<BoscaApplication>()
        every { application.json } returns routeJson

        val call = mockk<ServerCall>(relaxed = true)
        every { call.request } returns request
        every { call.application } returns application
        every { call.pathParameters } returns Parameters(pathParams.mapValues { listOf(it.value) })
        // The route reaches the form-urlencoded body through ServerCall.receiveParameters(), a
        // non-inline method on the (mocked) call — so it never delegates to the real request.
        // Stub it directly with the supplied form parameters; the JSON branch never calls it.
        coEvery { call.receiveParameters() } returns formParameters
        return call
    }

    private val formContentType = ContentType("application", "x-www-form-urlencoded")

    // JSON body for a SetDocumentRequest wrapping a DocumentInput with the given title.
    private fun jsonBody(title: String = "Hello"): String =
        """{"document":{"title":"$title","content":{"document":{"type":"doc"}}}}"""

    // Form-parameter map carrying a serialized DocumentInput under the "document" key.
    private fun formParams(documentJson: String): Parameters =
        Parameters(mapOf("document" to listOf(documentJson)))

    private fun documentInputJson(title: String = "Hello"): String =
        """{"title":"$title","content":{"document":{"type":"doc"}}}"""

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeRoute(call: ServerCall, auth: AuthenticationContext) {
        val method = SetDocument::class.declaredMemberFunctions
            .first { it.name == "execute" && it.parameters.size == 3 }
        method.isAccessible = true
        try {
            method.callSuspend(route, call, auth)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    // ----- JSON body branch (call.receive<SetDocumentRequest>()) -----

    @Test
    fun `sets document from json body with no collaboration sync`() = runTest {
        val call = createCall(bodyText = jsonBody("Article"))
        coEvery { metadataService.getById(testId, 1) } returns testMetadata

        val metadataSlot = slot<Metadata>()
        val documentSlot = slot<DocumentInput>()
        val syncSlot = slot<CollaborationSyncMode>()
        coEvery {
            documentService.setDocument(capture(metadataSlot), capture(documentSlot), capture(syncSlot))
        } returns Unit

        executeRoute(call, authenticationContext)

        assertSame(testMetadata, metadataSlot.captured)
        assertEquals("Article", documentSlot.captured.title)
        assertEquals(CollaborationSyncMode.NONE, syncSlot.captured)
        coVerify {
            metadataPermissionEvaluator.verifyAllowed(authenticationContext, testMetadata, PermissionAction.EDIT)
        }
    }

    @Test
    fun `sets document from json body with json content type`() = runTest {
        val call = createCall(
            contentType = ContentType("application", "json"),
            bodyText = jsonBody("Typed"),
        )
        coEvery { metadataService.getById(testId, 1) } returns testMetadata

        val documentSlot = slot<DocumentInput>()
        coEvery { documentService.setDocument(any(), capture(documentSlot), any()) } returns Unit

        executeRoute(call, authenticationContext)

        assertEquals("Typed", documentSlot.captured.title)
    }

    @Test
    fun `passes RESET collaboration sync through from query parameter`() = runTest {
        val call = createCall(
            queryParams = mapOf("collaborationSync" to "RESET"),
            bodyText = jsonBody(),
        )
        coEvery { metadataService.getById(testId, 1) } returns testMetadata

        val syncSlot = slot<CollaborationSyncMode>()
        coEvery { documentService.setDocument(any(), any(), capture(syncSlot)) } returns Unit

        executeRoute(call, authenticationContext)

        assertEquals(CollaborationSyncMode.RESET, syncSlot.captured)
    }

    @Test
    fun `lowercases collaboration sync value before matching`() = runTest {
        val call = createCall(
            queryParams = mapOf("collaborationSync" to "merge"),
            bodyText = jsonBody(),
        )
        coEvery { metadataService.getById(testId, 1) } returns testMetadata

        val syncSlot = slot<CollaborationSyncMode>()
        coEvery { documentService.setDocument(any(), any(), capture(syncSlot)) } returns Unit

        executeRoute(call, authenticationContext)

        assertEquals(CollaborationSyncMode.MERGE, syncSlot.captured)
    }

    @Test
    fun `blank collaboration sync value defaults to NONE`() = runTest {
        val call = createCall(
            queryParams = mapOf("collaborationSync" to "   "),
            bodyText = jsonBody(),
        )
        coEvery { metadataService.getById(testId, 1) } returns testMetadata

        val syncSlot = slot<CollaborationSyncMode>()
        coEvery { documentService.setDocument(any(), any(), capture(syncSlot)) } returns Unit

        executeRoute(call, authenticationContext)

        assertEquals(CollaborationSyncMode.NONE, syncSlot.captured)
    }

    @Test
    fun `rejects unrecognized collaboration sync value`() = runTest {
        val call = createCall(
            queryParams = mapOf("collaborationSync" to "RSET"),
            bodyText = jsonBody(),
        )
        coEvery { metadataService.getById(testId, 1) } returns testMetadata

        val exception = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(exception.message?.contains("invalid collaborationSync value") == true)
        coVerify(exactly = 0) { documentService.setDocument(any(), any(), any()) }
    }

    // ----- form-urlencoded branch (receiveParameters + json.decodeFromString) -----

    @Test
    fun `sets document from form-urlencoded body`() = runTest {
        val call = createCall(
            contentType = formContentType,
            formParameters = formParams(documentInputJson("FromForm")),
        )
        coEvery { metadataService.getById(testId, 1) } returns testMetadata

        val documentSlot = slot<DocumentInput>()
        coEvery { documentService.setDocument(any(), capture(documentSlot), any()) } returns Unit

        executeRoute(call, authenticationContext)

        assertEquals("FromForm", documentSlot.captured.title)
        coVerify { documentService.setDocument(testMetadata, any(), CollaborationSyncMode.NONE) }
    }

    @Test
    fun `form-urlencoded branch honors collaboration sync query parameter`() = runTest {
        val call = createCall(
            queryParams = mapOf("collaborationSync" to "MERGE"),
            contentType = formContentType,
            formParameters = formParams(documentInputJson("FormMerge")),
        )
        coEvery { metadataService.getById(testId, 1) } returns testMetadata

        val syncSlot = slot<CollaborationSyncMode>()
        coEvery { documentService.setDocument(any(), any(), capture(syncSlot)) } returns Unit

        executeRoute(call, authenticationContext)

        assertEquals(CollaborationSyncMode.MERGE, syncSlot.captured)
    }

    @Test
    fun `form-urlencoded branch errors when document parameter is missing`() = runTest {
        val call = createCall(
            contentType = formContentType,
            formParameters = Parameters.Empty,
        )
        coEvery { metadataService.getById(testId, 1) } returns testMetadata

        val exception = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(exception.message?.contains("missing document") == true)
        coVerify(exactly = 0) { documentService.setDocument(any(), any(), any()) }
    }

    // ----- path/version/metadata guards -----

    @Test
    fun `throws when id path parameter is missing`() = runTest {
        val call = createCall(pathParams = mapOf("version" to "1"))

        val exception = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(exception.message?.contains("missing id") == true)
        coVerify(exactly = 0) { metadataService.getById(any<UUID>(), any<Int>()) }
    }

    @Test
    fun `throws when version path parameter is missing`() = runTest {
        val call = createCall(pathParams = mapOf("id" to testId.toString()))

        val exception = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(exception.message?.contains("missing version") == true)
    }

    @Test
    fun `throws when version path parameter is not numeric`() = runTest {
        val call = createCall(pathParams = mapOf("id" to testId.toString(), "version" to "abc"))

        val exception = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(exception.message?.contains("missing version") == true)
    }

    @Test
    fun `throws when metadata not found`() = runTest {
        val call = createCall(bodyText = jsonBody())
        coEvery { metadataService.getById(testId, 1) } returns null

        val exception = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(exception.message?.contains("Metadata not found") == true)
        coVerify(exactly = 0) { documentService.setDocument(any(), any(), any()) }
    }

    // ----- serializer() -----

    @Test
    fun `serializer is null`() {
        val method = SetDocument::class.declaredMemberFunctions.first { it.name == "serializer" }
        method.isAccessible = true
        assertNull(method.call(route))
    }

    // ----- SetDocumentRequest data class round-trip -----

    @Test
    fun `SetDocumentRequest holds its document`() {
        val input = documentInput("Payload")
        val request = SetDocumentRequest(input)
        assertSame(input, request.document)
    }

    @Test
    fun `SetDocumentRequest serializes round-trip through json`() {
        val json = jsonBody("RoundTrip")
        val decoded = routeJson.decodeFromString(SetDocumentRequest.serializer(), json)
        assertEquals("RoundTrip", decoded.document.title)
        val reencoded = routeJson.encodeToString(SetDocumentRequest.serializer(), decoded)
        val redecoded = routeJson.decodeFromString(SetDocumentRequest.serializer(), reencoded)
        assertEquals("RoundTrip", redecoded.document.title)
    }
}
