package bosca.content.metadata.routes

import bosca.content.metadata.model.DataCollaboration
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.DataService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.Parameters
import bosca.server.ServerCall
import bosca.server.ServerRequest
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GetDataCollaborationCoverageTest {

    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val service = mockk<MetadataService>()
    private val dataService = mockk<DataService>()
    private val authenticationContext = mockk<AuthenticationContext>()

    private val route = GetDataCollaboration(metadataPermissionEvaluator, service, dataService)

    private val testId = UUID.parse("00000000-0000-0000-0000-000000000001")

    private fun metadata(version: Int = 1) = Metadata(
        id = testId,
        version = version,
        name = "Test Data",
        type = MetadataType.STANDARD,
        contentType = "bosca/v-data",
        contentLength = 0,
        languageTag = "en",
        workflowStateId = "published",
    )

    private fun createCall(
        pathParams: Map<String, String> = mapOf("id" to testId.toString()),
        queryParams: Map<String, String> = emptyMap(),
    ): ServerCall {
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.queryParameters } returns Parameters(queryParams.mapValues { listOf(it.value) })
        val call = mockk<ServerCall>(relaxed = true)
        every { call.request } returns request
        every { call.pathParameters } returns Parameters(pathParams.mapValues { listOf(it.value) })
        return call
    }

    private suspend fun executeRoute(call: ServerCall, auth: AuthenticationContext): ByteArray? {
        val method = GetDataCollaboration::class.declaredMemberFunctions
            .first { it.name == "execute" && it.parameters.size == 3 }
        method.isAccessible = true
        try {
            return method.callSuspend(route, call, auth) as ByteArray?
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
    fun `serializer returns null`() {
        val method = GetDataCollaboration::class.declaredMemberFunctions
            .first { it.name == "serializer" }
        method.isAccessible = true
        assertNull(method.call(route))
    }

    @Test
    fun `returns collaboration content on success with default version`() = runTest {
        val call = createCall()
        val md = metadata()
        val content = byteArrayOf(1, 2, 3, 4)
        coEvery { service.getById(testId, 1) } returns md
        coEvery { dataService.getCollaboration(testId, 1) } returns DataCollaboration(
            metadataId = testId,
            version = 1,
            content = content,
        )

        val result = executeRoute(call, authenticationContext)

        assertTrue(content.contentEquals(result))
        coVerify {
            metadataPermissionEvaluator.verifyContentAllowed(
                authenticationContext,
                md,
                PermissionAction.EDIT,
            )
        }
        coVerify { service.getById(testId, 1) }
        coVerify { dataService.getCollaboration(testId, 1) }
    }

    @Test
    fun `uses version query parameter when present`() = runTest {
        val call = createCall(queryParams = mapOf("version" to "3"))
        val md = metadata(version = 3)
        val content = byteArrayOf(9, 8, 7)
        coEvery { service.getById(testId, 3) } returns md
        coEvery { dataService.getCollaboration(testId, 3) } returns DataCollaboration(
            metadataId = testId,
            version = 3,
            content = content,
        )

        val result = executeRoute(call, authenticationContext)

        assertTrue(content.contentEquals(result))
        coVerify { service.getById(testId, 3) }
        coVerify { dataService.getCollaboration(testId, 3) }
    }

    @Test
    fun `defaults version to 1 when query parameter is not a number`() = runTest {
        val call = createCall(queryParams = mapOf("version" to "not-a-number"))
        coEvery { service.getById(testId, 1) } returns metadata()
        coEvery { dataService.getCollaboration(testId, 1) } returns DataCollaboration(
            metadataId = testId,
            version = 1,
            content = byteArrayOf(5),
        )

        val result = executeRoute(call, authenticationContext)

        assertTrue(byteArrayOf(5).contentEquals(result))
        coVerify { service.getById(testId, 1) }
    }

    @Test
    fun `returns null when metadata not found`() = runTest {
        val call = createCall()
        coEvery { service.getById(testId, 1) } returns null

        val result = executeRoute(call, authenticationContext)

        assertNull(result)
        coVerify(exactly = 0) {
            metadataPermissionEvaluator.verifyContentAllowed(any(), any(), any())
        }
        coVerify(exactly = 0) { dataService.getCollaboration(any(), any()) }
    }

    @Test
    fun `returns null when collaboration not found`() = runTest {
        val call = createCall()
        coEvery { service.getById(testId, 1) } returns metadata()
        coEvery { dataService.getCollaboration(testId, 1) } returns null

        val result = executeRoute(call, authenticationContext)

        assertNull(result)
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
    fun `propagates permission denial from evaluator`() = runTest {
        val call = createCall()
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
        coVerify(exactly = 0) { dataService.getCollaboration(any(), any()) }
    }
}
