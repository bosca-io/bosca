package bosca.content.metadata.routes

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.Parameters
import bosca.server.ServerCall
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DeleteBibleCoverageTest {

    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val metadataService = mockk<MetadataService>()
    private val authenticationContext = mockk<AuthenticationContext>()

    private val route = DeleteBible(groups, metadataService)

    private val testId = UUID.parse("00000000-0000-0000-0000-000000000001")

    private fun metadata() = Metadata(
        id = testId,
        version = 1,
        name = "Test Bible",
        type = MetadataType.STANDARD,
        contentType = "bosca/v-bible",
        contentLength = 0,
        languageTag = "en",
        workflowStateId = "published",
    )

    private fun createCall(
        pathParams: Map<String, String> = mapOf("id" to testId.toString()),
    ): ServerCall {
        val call = mockk<ServerCall>(relaxed = true)
        every { call.pathParameters } returns Parameters(pathParams.mapValues { listOf(it.value) })
        return call
    }

    private suspend fun executeRoute(call: ServerCall, auth: AuthenticationContext) {
        val method = DeleteBible::class.declaredMemberFunctions
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

    @Test
    fun `serializer returns null`() {
        // serializer() is protected in Route<T>; invoke it reflectively (like execute)
        // to verify DeleteBible's override returns null (the Unit/no-content path).
        val method = DeleteBible::class.declaredMemberFunctions
            .first { it.name == "serializer" }
        method.isAccessible = true
        assertNull(method.call(route))
    }

    @Test
    fun `deletes metadata after verifying admin group on success`() = runTest {
        val call = createCall()
        val md = metadata()
        every { groups.verifyHasAdminGroup(authenticationContext) } just Runs
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataService.delete(md) } just Runs

        executeRoute(call, authenticationContext)

        verify { groups.verifyHasAdminGroup(authenticationContext) }
        coVerify { metadataService.getById(testId) }
        coVerify { metadataService.delete(md) }
    }

    @Test
    fun `propagates unauthorized when admin group verification fails`() = runTest {
        val call = createCall()
        every { groups.verifyHasAdminGroup(authenticationContext) } throws IllegalStateException("unauthorized")

        val ex = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertEquals("unauthorized", ex.message)
        coVerify(exactly = 0) { metadataService.getById(any<UUID>()) }
        coVerify(exactly = 0) { metadataService.delete(any()) }
    }

    @Test
    fun `throws when id path parameter is missing`() = runTest {
        val call = createCall(pathParams = emptyMap())
        every { groups.verifyHasAdminGroup(authenticationContext) } just Runs

        val ex = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertEquals("missing id", ex.message)
        coVerify(exactly = 0) { metadataService.getById(any<UUID>()) }
        coVerify(exactly = 0) { metadataService.delete(any()) }
    }

    @Test
    fun `throws when metadata not found`() = runTest {
        val call = createCall()
        every { groups.verifyHasAdminGroup(authenticationContext) } just Runs
        coEvery { metadataService.getById(testId) } returns null

        val ex = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertEquals("Metadata not found", ex.message)
        coVerify { metadataService.getById(testId) }
        coVerify(exactly = 0) { metadataService.delete(any()) }
    }

    @Test
    fun `throws when id path parameter is not a valid uuid`() = runTest {
        val call = createCall(pathParams = mapOf("id" to "not-a-uuid"))
        every { groups.verifyHasAdminGroup(authenticationContext) } just Runs

        assertFailsWith<IllegalArgumentException> {
            executeRoute(call, authenticationContext)
        }
        coVerify(exactly = 0) { metadataService.delete(any()) }
    }
}
