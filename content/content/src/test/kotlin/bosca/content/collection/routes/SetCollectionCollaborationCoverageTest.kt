package bosca.content.collection.routes

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionCollaborationInput
import bosca.content.collection.service.CollectionService
import bosca.content.security.CollectionPermissionEvaluator
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
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SetCollectionCollaborationCoverageTest {

    private val collectionPermissionEvaluator = mockk<CollectionPermissionEvaluator>(relaxed = true)
    private val service = mockk<CollectionService>()
    private val collectionService = mockk<CollectionService>(relaxed = true)
    private val authenticationContext = mockk<AuthenticationContext>()

    private val route = SetCollectionCollaboration(collectionPermissionEvaluator, service, collectionService)

    private val testId = UUID.parse("00000000-0000-0000-0000-000000000001")

    private fun collection() = Collection(
        id = testId,
        name = "Test Collection",
        languageTag = "en",
        workflowStateId = "published",
    )

    private fun createCall(
        pathParams: Map<String, String> = mapOf("id" to testId.toString()),
        queryParams: Map<String, String> = mapOf("languageTag" to "en"),
        body: ByteArray = byteArrayOf(1, 2, 3, 4),
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
        val method = SetCollectionCollaboration::class.declaredMemberFunctions
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
    fun `returns Accepted and sets collaboration on success`() = runTest {
        val body = byteArrayOf(10, 20, 30)
        val call = createCall(body = body)
        val col = collection()
        coEvery { service.getById(testId) } returns col
        val inputSlot = slot<CollectionCollaborationInput>()
        coEvery { collectionService.setCollaboration(capture(inputSlot)) } returns Unit

        val result = executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.Accepted, result)
        coVerify {
            collectionPermissionEvaluator.verifyAllowed(
                authenticationContext,
                col,
                PermissionAction.EDIT,
            )
        }
        coVerify { service.getById(testId) }
        coVerify { collectionService.setCollaboration(any()) }
        val captured = inputSlot.captured
        assertEquals(testId, captured.collectionId)
        assertEquals("en", captured.languageTag)
        assertTrue(body.contentEquals(captured.content))
    }

    @Test
    fun `uses languageTag query parameter value`() = runTest {
        val call = createCall(queryParams = mapOf("languageTag" to "es"))
        coEvery { service.getById(testId) } returns collection()
        val inputSlot = slot<CollectionCollaborationInput>()
        coEvery { collectionService.setCollaboration(capture(inputSlot)) } returns Unit

        val result = executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.Accepted, result)
        assertEquals("es", inputSlot.captured.languageTag)
    }

    @Test
    fun `passes empty body through to collaboration input`() = runTest {
        val call = createCall(body = ByteArray(0))
        coEvery { service.getById(testId) } returns collection()
        val inputSlot = slot<CollectionCollaborationInput>()
        coEvery { collectionService.setCollaboration(capture(inputSlot)) } returns Unit

        val result = executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.Accepted, result)
        assertTrue(inputSlot.captured.content.isEmpty())
    }

    @Test
    fun `throws IllegalArgumentException when id path parameter is missing`() = runTest {
        val call = createCall(pathParams = emptyMap())

        val ex = assertFailsWith<IllegalArgumentException> {
            executeRoute(call, authenticationContext)
        }
        assertEquals("id is required", ex.message)
        coVerify(exactly = 0) { service.getById(any()) }
        coVerify(exactly = 0) { collectionService.setCollaboration(any()) }
    }

    @Test
    fun `throws IllegalArgumentException when languageTag query parameter is missing`() = runTest {
        val call = createCall(queryParams = emptyMap())

        val ex = assertFailsWith<IllegalArgumentException> {
            executeRoute(call, authenticationContext)
        }
        assertEquals("language tag is required", ex.message)
        coVerify(exactly = 0) { service.getById(any()) }
        coVerify(exactly = 0) { collectionService.setCollaboration(any()) }
    }

    @Test
    fun `throws NoSuchElementException when collection not found`() = runTest {
        val call = createCall()
        coEvery { service.getById(testId) } returns null

        val ex = assertFailsWith<NoSuchElementException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(ex.message?.contains("No collection found for id") == true)
        coVerify(exactly = 0) {
            collectionPermissionEvaluator.verifyAllowed(any(), any(), any())
        }
        coVerify(exactly = 0) { collectionService.setCollaboration(any()) }
    }

    @Test
    fun `propagates permission denial from evaluator`() = runTest {
        val call = createCall()
        val col = collection()
        coEvery { service.getById(testId) } returns col
        coEvery {
            collectionPermissionEvaluator.verifyAllowed(
                authenticationContext,
                col,
                PermissionAction.EDIT,
            )
        } throws IllegalStateException("unauthorized")

        val ex = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertEquals("unauthorized", ex.message)
        coVerify(exactly = 0) { collectionService.setCollaboration(any()) }
    }
}
