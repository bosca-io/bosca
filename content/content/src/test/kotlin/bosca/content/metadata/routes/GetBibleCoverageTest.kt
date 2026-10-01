package bosca.content.metadata.routes

import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.BibleService
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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonNull
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class GetBibleCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val bibleService = mockk<BibleService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val authenticationContext = mockk<AuthenticationContext>()

    // GetBible constructor order is (metadataService, metadataPermissionEvaluator, bibleService).
    private val route = GetBible(metadataService, metadataPermissionEvaluator, bibleService)

    private val testId = UUID.parse("00000000-0000-0000-0000-000000000001")

    private fun metadata(
        id: UUID = testId,
        version: Int = 1,
        deleted: Boolean = false,
    ) = Metadata(
        id = id,
        version = version,
        name = "Test Bible",
        type = MetadataType.STANDARD,
        contentType = "bosca/v-bible",
        contentLength = 0,
        languageTag = "en",
        deleted = deleted,
        workflowStateId = "published",
    )

    private fun bible(
        id: UUID = testId,
        version: Int = 1,
        variant: String = "default",
    ) = Bible(
        metadataId = id,
        version = version,
        systemId = "test-system",
        variant = variant,
        defaultVariant = true,
        name = "Test Bible",
        nameLocal = "Test Bible",
        description = "A test bible",
        abbreviation = "TST",
        abbreviationLocal = "TST",
        styles = JsonNull,
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

    // GetBible.execute(call, auth) is a protected suspend member; invoke it reflectively,
    // binding positionally as (route, call, auth).
    private suspend fun executeRoute(call: ServerCall, auth: AuthenticationContext): Bible {
        val method = GetBible::class.declaredMemberFunctions
            .first { it.name == "execute" && it.parameters.size == 3 }
        method.isAccessible = true
        return try {
            method.callSuspend(route, call, auth) as Bible
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    // GetBible.serializer() overrides a protected member of Route; invoke it reflectively.
    @Suppress("UNCHECKED_CAST")
    private fun invokeSerializer(): KSerializer<Bible> {
        val method = GetBible::class.declaredMemberFunctions
            .first { it.name == "serializer" }
        method.isAccessible = true
        return method.call(route) as KSerializer<Bible>
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun `serializer returns the Bible serializer`() {
        assertSame(Bible.serializer(), invokeSerializer())
    }

    @Test
    fun `execute delegates to getBible and returns bible resolved by id`() = runTest {
        val call = createCall()
        val md = metadata()
        val b = bible()
        coEvery { metadataService.getById(testId) } returns md
        coEvery { bibleService.getBible(testId, 1, null) } returns b

        val result = executeRoute(call, authenticationContext)

        assertSame(b, result)
        coVerify { bibleService.getBible(testId, 1, null) }
        coVerify {
            metadataPermissionEvaluator.verifyAllowed(authenticationContext, md, PermissionAction.VIEW)
        }
    }

    @Test
    fun `execute propagates error when bible not found`() = runTest {
        val call = createCall()
        coEvery { metadataService.getById(testId) } returns metadata()
        coEvery { bibleService.getBible(testId, 1, null) } returns null

        val ex = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertEquals("Bible not found", ex.message)
    }
}
