package bosca.content.metadata.routes

import bosca.content.metadata.service.MetadataService
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.Parameters
import bosca.server.ServerCall
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.callSuspend
import kotlin.reflect.jvm.isAccessible
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguageVariantCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val authenticationContext = mockk<AuthenticationContext>()

    private val route = LanguageVariant(metadataService)
    private val legacyRoute = LanguageVariantLegacy(metadataService)

    private val testId = UUID.parse("00000000-0000-0000-0000-000000000001")
    private val variantId = UUID.parse("00000000-0000-0000-0000-000000000002")

    private fun createCall(
        pathParams: Map<String, String> = mapOf("id" to testId.toString(), "language" to "es"),
    ): ServerCall {
        val call = mockk<ServerCall>(relaxed = true)
        every { call.pathParameters } returns Parameters(pathParams.mapValues { listOf(it.value) })
        return call
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeRoute(
        target: LanguageVariant,
        call: ServerCall,
        auth: AuthenticationContext,
    ): IdResponse? {
        val method = LanguageVariant::class.declaredMemberFunctions
            .first { it.name == "execute" && it.parameters.size == 3 }
        method.isAccessible = true
        try {
            return method.callSuspend(target, call, auth) as IdResponse?
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
    fun `serializer returns the IdResponse serializer`() {
        val method = LanguageVariant::class.declaredMemberFunctions
            .first { it.name == "serializer" }
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val serializer = method.call(route) as KSerializer<IdResponse>
        assertEquals(IdResponse.serializer().descriptor.serialName, serializer.descriptor.serialName)
    }

    @Test
    fun `IdResponse round-trips through json and equals holds`() {
        val response = IdResponse(variantId.toString())
        val encoded = Json.encodeToString(IdResponse.serializer(), response)
        val decoded = Json.decodeFromString(IdResponse.serializer(), encoded)
        assertEquals(response, decoded)
        assertEquals(response.hashCode(), decoded.hashCode())
        // copy produces an equal instance; a changed field yields an unequal one.
        assertEquals(response, response.copy())
        assertNotEquals(response, response.copy(id = "other"))
        assertEquals(variantId.toString(), response.id)
    }

    @Test
    fun `execute returns the language variant id when one exists`() = runTest {
        val call = createCall()
        coEvery { metadataService.getLanguageVariantById(testId, "es") } returns variantId

        val result = executeRoute(route, call, authenticationContext)

        coVerify { metadataService.getLanguageVariantById(testId, "es") }
        assertEquals(variantId.toString(), result?.id)
    }

    @Test
    fun `execute returns null when no language variant exists`() = runTest {
        val call = createCall()
        coEvery { metadataService.getLanguageVariantById(testId, "es") } returns null

        val result = executeRoute(route, call, authenticationContext)

        assertNull(result)
    }

    @Test
    fun `execute throws when id path parameter is missing`() = runTest {
        val call = createCall(pathParams = mapOf("language" to "es"))

        val exception = assertFailsWith<IllegalStateException> {
            executeRoute(route, call, authenticationContext)
        }
        assertEquals("missing id", exception.message)
    }

    @Test
    fun `execute throws when language path parameter is missing`() = runTest {
        val call = createCall(pathParams = mapOf("id" to testId.toString()))

        val exception = assertFailsWith<IllegalStateException> {
            executeRoute(route, call, authenticationContext)
        }
        assertEquals("missing language", exception.message)
    }

    @Test
    fun `legacy route resolves the language variant id through the inherited execute`() = runTest {
        val call = createCall()
        coEvery { metadataService.getLanguageVariantById(testId, "es") } returns variantId

        val result = executeRoute(legacyRoute, call, authenticationContext)

        assertTrue(legacyRoute is LanguageVariant)
        assertEquals(variantId.toString(), result?.id)
    }
}
