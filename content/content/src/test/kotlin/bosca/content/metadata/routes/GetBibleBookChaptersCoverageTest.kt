package bosca.content.metadata.routes

import bosca.bible.bibleJson
import bosca.bible.components.IComponent
import bosca.bible.components.Text
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.BibleBook
import bosca.content.metadata.model.BibleChapter
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GetBibleBookChaptersCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val bibleService = mockk<BibleService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val authenticationContext = mockk<AuthenticationContext>()

    // GetBibleBookChapters constructor order: (metadataService, metadataPermissionEvaluator, bibleService)
    private val route = GetBibleBookChapters(metadataService, metadataPermissionEvaluator, bibleService)

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

    private fun book(usfm: String = "GEN") = BibleBook(
        metadataId = testId,
        version = 1,
        variant = "default",
        usfm = usfm,
        nameShort = "Gen",
        nameLong = "Genesis",
        abbreviation = "GEN",
        sort = 1,
    )

    private val testComponents = bibleJson.encodeToJsonElement<IComponent>(Text("In the beginning", null))

    private fun chapter(usfm: String = "GEN.1") = BibleChapter(
        metadataId = testId,
        version = 1,
        variant = "default",
        bookUsfm = "GEN",
        usfm = usfm,
        components = testComponents,
        sort = 1,
    )

    private fun createCall(
        pathParams: Map<String, String> = mapOf("id" to testId.toString(), "usfm" to "GEN"),
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
    private suspend fun executeRoute(call: ServerCall, auth: AuthenticationContext): List<BibleChapter> {
        val method = GetBibleBookChapters::class.declaredMemberFunctions
            .first { it.name == "execute" && it.parameters.size == 3 }
        method.isAccessible = true
        try {
            return method.callSuspend(route, call, auth) as List<BibleChapter>
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
    fun `serializer describes a list of bible chapters`() {
        // route.serializer() is protected in BaseBibleRoute; rebuild the identical
        // serializer the route returns (ListSerializer(BibleChapter.serializer())) and
        // assert its descriptor shape.
        val descriptor = ListSerializer(BibleChapter.serializer()).descriptor
        assertEquals(kotlinx.serialization.descriptors.StructureKind.LIST, descriptor.kind)
        assertEquals(
            BibleChapter.serializer().descriptor.serialName,
            descriptor.getElementDescriptor(0).serialName,
        )
    }

    @Test
    fun `returns chapters for the matched book with components stripped`() = runTest {
        val call = createCall()
        stubBible()
        coEvery { bibleService.getBooks(testBible) } returns listOf(book("GEN"))
        coEvery { bibleService.getChapters(book("GEN")) } returns listOf(chapter("GEN.1"), chapter("GEN.2"))

        val result = executeRoute(call, authenticationContext)

        coVerify {
            metadataPermissionEvaluator.verifyAllowed(authenticationContext, testMetadata, PermissionAction.VIEW)
        }
        assertEquals(2, result.size)
        assertEquals("GEN.1", result[0].usfm)
        assertEquals("GEN.2", result[1].usfm)
        // components are stripped to null on every returned chapter copy.
        assertNull(result[0].components)
        assertNull(result[1].components)
    }

    @Test
    fun `returns empty list when no book matches the usfm path parameter`() = runTest {
        // Book list only contains GEN but the requested usfm is EXO -> find() returns null -> emptyList.
        val call = createCall(pathParams = mapOf("id" to testId.toString(), "usfm" to "EXO"))
        stubBible()
        coEvery { bibleService.getBooks(testBible) } returns listOf(book("GEN"))

        val result = executeRoute(call, authenticationContext)

        assertTrue(result.isEmpty())
        // getChapters must never be called when no book matches.
        coVerify(exactly = 0) { bibleService.getChapters(any()) }
    }

    @Test
    fun `returns empty list when usfm path parameter is absent`() = runTest {
        // No usfm path parameter -> pathParameters["usfm"] is null -> no book.usfm equals null -> emptyList.
        val call = createCall(pathParams = mapOf("id" to testId.toString()))
        stubBible()
        coEvery { bibleService.getBooks(testBible) } returns listOf(book("GEN"))

        val result = executeRoute(call, authenticationContext)

        assertTrue(result.isEmpty())
        coVerify(exactly = 0) { bibleService.getChapters(any()) }
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
    fun `returns empty chapter list when matched book has no chapters`() = runTest {
        val call = createCall()
        stubBible()
        coEvery { bibleService.getBooks(testBible) } returns listOf(book("GEN"))
        coEvery { bibleService.getChapters(book("GEN")) } returns emptyList()

        val result = executeRoute(call, authenticationContext)

        assertTrue(result.isEmpty())
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
        // getMetadata returns null (getById null) -> getBibleByLanguage; no language + empty header -> find empty.
        val call = createCall()
        coEvery { metadataService.getById(testId) } returns null
        coEvery { metadataService.find(any()) } returns emptyList()

        val exception = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(exception.message?.contains("Bible not found") == true)
    }
}
