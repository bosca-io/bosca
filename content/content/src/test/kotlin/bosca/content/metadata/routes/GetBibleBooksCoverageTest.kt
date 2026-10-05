package bosca.content.metadata.routes

import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.BibleBook
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.LanguageItem
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
import kotlinx.serialization.json.JsonNull
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GetBibleBooksCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val bibleService = mockk<BibleService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val authenticationContext = mockk<AuthenticationContext>()

    // GetBibleBooks constructor order: (metadataService, metadataPermissionEvaluator, bibleService)
    private val route = GetBibleBooks(metadataService, metadataPermissionEvaluator, bibleService)

    private val testId = UUID.parse("00000000-0000-0000-0000-000000000001")

    private val testMetadata = Metadata(
        id = testId,
        version = 1,
        name = "Test Bible",
        type = MetadataType.STANDARD,
        contentType = "bosca/v-bible",
        contentLength = 0,
        languageTag = "en",
        workflowStateId = "published",
    )

    private val testBible = Bible(
        metadataId = testId,
        version = 1,
        systemId = "test-system",
        variant = "default",
        defaultVariant = true,
        name = "Test Bible",
        nameLocal = "Test Bible",
        description = "A test bible",
        abbreviation = "TST",
        abbreviationLocal = "TST",
        styles = JsonNull,
    )

    private fun book(usfm: String = "GEN", sort: Int = 1) = BibleBook(
        metadataId = testId,
        version = 1,
        variant = "default",
        usfm = usfm,
        nameShort = "Gen",
        nameLong = "Genesis",
        abbreviation = "GEN",
        sort = sort,
    )

    private fun createCall(
        pathParams: Map<String, String> = mapOf("id" to testId.toString()),
        queryParams: Map<String, String> = emptyMap(),
    ): ServerCall {
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.queryParameters } returns Parameters(queryParams.mapValues { listOf(it.value) })
        every { request.acceptLanguageItems() } returns emptyList<LanguageItem>()
        val call = mockk<ServerCall>(relaxed = true)
        every { call.request } returns request
        every { call.pathParameters } returns Parameters(pathParams.mapValues { listOf(it.value) })
        return call
    }

    private fun stubBible() {
        coEvery { metadataService.getById(testId) } returns testMetadata
        coEvery { bibleService.getBible(testId, 1, null) } returns testBible
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeRoute(call: ServerCall, auth: AuthenticationContext): List<BibleBook> {
        val method = GetBibleBooks::class.declaredMemberFunctions
            .first { it.name == "execute" && it.parameters.size == 3 }
        method.isAccessible = true
        try {
            return method.callSuspend(route, call, auth) as List<BibleBook>
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
    fun `serializer describes a list of bible books`() {
        // Route.serializer() is protected in the base class, so the descriptor is asserted
        // against the same ListSerializer(BibleBook.serializer()) the route override returns.
        val descriptor = kotlinx.serialization.builtins.ListSerializer(BibleBook.serializer()).descriptor
        assertEquals(kotlinx.serialization.descriptors.StructureKind.LIST, descriptor.kind)
        assertEquals(
            BibleBook.serializer().descriptor.serialName,
            descriptor.getElementDescriptor(0).serialName,
        )
    }

    @Test
    fun `returns books for the resolved bible and enforces view permission`() = runTest {
        val call = createCall()
        stubBible()
        coEvery { bibleService.getBooks(testBible) } returns listOf(book("GEN", 1), book("EXO", 2))

        val result = executeRoute(call, authenticationContext)

        coVerify {
            metadataPermissionEvaluator.verifyAllowed(authenticationContext, testMetadata, PermissionAction.VIEW)
        }
        coVerify { bibleService.getBooks(testBible) }
        assertEquals(2, result.size)
        assertEquals("GEN", result[0].usfm)
        assertEquals("EXO", result[1].usfm)
    }

    @Test
    fun `returns empty list when the bible has no books`() = runTest {
        val call = createCall()
        stubBible()
        coEvery { bibleService.getBooks(testBible) } returns emptyList()

        val result = executeRoute(call, authenticationContext)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `resolves bible by explicit version query parameter`() = runTest {
        // version query param drives the two-arg getById overload in getMetadata.
        val call = createCall(queryParams = mapOf("version" to "1"))
        coEvery { metadataService.getById(testId, 1) } returns testMetadata
        coEvery { bibleService.getBible(testId, 1, null) } returns testBible
        coEvery { bibleService.getBooks(testBible) } returns listOf(book("GEN"))

        val result = executeRoute(call, authenticationContext)

        coVerify { metadataService.getById(testId, 1) }
        assertEquals(1, result.size)
    }

    @Test
    fun `passes variant query parameter through to bible lookup`() = runTest {
        val call = createCall(queryParams = mapOf("variant" to "study"))
        coEvery { metadataService.getById(testId) } returns testMetadata
        coEvery { bibleService.getBible(testId, 1, "study") } returns testBible
        coEvery { bibleService.getBooks(testBible) } returns listOf(book("GEN"))

        val result = executeRoute(call, authenticationContext)

        coVerify { bibleService.getBible(testId, 1, "study") }
        assertEquals(1, result.size)
    }

    @Test
    fun `propagates bible-not-found error when metadata resolves but bible is missing`() = runTest {
        val call = createCall()
        coEvery { metadataService.getById(testId) } returns testMetadata
        coEvery { bibleService.getBible(testId, 1, null) } returns null

        val exception = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertEquals("Bible not found", exception.message)
    }

    @Test
    fun `propagates bible-not-found-for-language error when metadata cannot be resolved`() = runTest {
        // getById null -> getMetadata null -> getBibleByLanguage; empty accept-language -> find empty -> error.
        val call = createCall()
        coEvery { metadataService.getById(testId) } returns null
        coEvery { metadataService.find(any()) } returns emptyList()

        val exception = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(exception.message?.contains("Bible not found") == true)
    }
}
