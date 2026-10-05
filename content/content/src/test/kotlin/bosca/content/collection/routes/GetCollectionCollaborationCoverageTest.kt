package bosca.content.collection.routes

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionCollaboration
import bosca.content.collection.service.CollectionService
import bosca.content.security.CollectionPermissionEvaluator
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

class GetCollectionCollaborationCoverageTest {

    private val collectionPermissionEvaluator = mockk<CollectionPermissionEvaluator>(relaxed = true)
    private val service = mockk<CollectionService>()
    private val authenticationContext = mockk<AuthenticationContext>()

    private val route = GetCollectionCollaboration(collectionPermissionEvaluator, service)

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
    ): ServerCall {
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.queryParameters } returns Parameters(queryParams.mapValues { listOf(it.value) })
        val call = mockk<ServerCall>(relaxed = true)
        every { call.request } returns request
        every { call.pathParameters } returns Parameters(pathParams.mapValues { listOf(it.value) })
        return call
    }

    private suspend fun executeRoute(call: ServerCall, auth: AuthenticationContext): ByteArray? {
        val method = GetCollectionCollaboration::class.declaredMemberFunctions
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
        val method = GetCollectionCollaboration::class.declaredMemberFunctions
            .first { it.name == "serializer" }
        method.isAccessible = true
        assertNull(method.call(route))
    }

    @Test
    fun `returns collaboration content on success`() = runTest {
        val call = createCall()
        val col = collection()
        val content = byteArrayOf(1, 2, 3, 4)
        coEvery { service.getById(testId) } returns col
        coEvery { service.getCollaboration(testId, "en") } returns CollectionCollaboration(
            collectionId = testId,
            languageTag = "en",
            content = content,
        )

        val result = executeRoute(call, authenticationContext)

        assertTrue(content.contentEquals(result))
        coVerify {
            collectionPermissionEvaluator.verifyAllowed(
                authenticationContext,
                col,
                PermissionAction.EDIT,
            )
        }
        coVerify { service.getById(testId) }
        coVerify { service.getCollaboration(testId, "en") }
    }

    @Test
    fun `uses languageTag query parameter value`() = runTest {
        val call = createCall(queryParams = mapOf("languageTag" to "es"))
        val col = collection()
        val content = byteArrayOf(9, 8, 7)
        coEvery { service.getById(testId) } returns col
        coEvery { service.getCollaboration(testId, "es") } returns CollectionCollaboration(
            collectionId = testId,
            languageTag = "es",
            content = content,
        )

        val result = executeRoute(call, authenticationContext)

        assertTrue(content.contentEquals(result))
        coVerify { service.getCollaboration(testId, "es") }
    }

    @Test
    fun `returns null when collection not found`() = runTest {
        val call = createCall()
        coEvery { service.getById(testId) } returns null

        val result = executeRoute(call, authenticationContext)

        assertNull(result)
        coVerify(exactly = 0) {
            collectionPermissionEvaluator.verifyAllowed(any(), any(), any())
        }
        coVerify(exactly = 0) { service.getCollaboration(any(), any()) }
    }

    @Test
    fun `returns null when collaboration not found`() = runTest {
        val call = createCall()
        coEvery { service.getById(testId) } returns collection()
        coEvery { service.getCollaboration(testId, "en") } returns null

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
    fun `throws IllegalArgumentException when languageTag query parameter is missing`() = runTest {
        val call = createCall(queryParams = emptyMap())

        val ex = assertFailsWith<IllegalArgumentException> {
            executeRoute(call, authenticationContext)
        }
        assertEquals("language tag is required", ex.message)
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
        coVerify(exactly = 0) { service.getCollaboration(any(), any()) }
    }
}
