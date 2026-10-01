package bosca.content.metadata.routes

import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.BibleBook
import bosca.content.metadata.model.BibleChapter
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GetBibleBookChapterCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val bibleService = mockk<BibleService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val authenticationContext = mockk<AuthenticationContext>()

    // Constructor order for GetBibleBookChapter is (metadataService, metadataPermissionEvaluator, bibleService).
    private val route = GetBibleBookChapter(metadataService, metadataPermissionEvaluator, bibleService)

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

    private val testBook = BibleBook(
        metadataId = testId,
        version = 1,
        variant = "default",
        usfm = "GEN",
        nameShort = "Gen",
        nameLong = "Genesis",
        abbreviation = "GEN",
        sort = 1,
    )

    private val testChapter = BibleChapter(
        metadataId = testId,
        version = 1,
        variant = "default",
        bookUsfm = "GEN",
        usfm = "GEN.1",
        components = null,
        sort = 1,
    )

    private fun createCall(
        pathParams: Map<String, String> = mapOf(
            "id" to testId.toString(),
            "bookUsfm" to "GEN",
            "chapterUsfm" to "GEN.1",
        ),
        queryParams: Map<String, String> = emptyMap(),
    ): ServerCall {
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.queryParameters } returns Parameters(queryParams.mapValues { listOf(it.value) })
        val call = mockk<ServerCall>(relaxed = true)
        every { call.request } returns request
        every { call.pathParameters } returns Parameters(pathParams.mapValues { listOf(it.value) })
        return call
    }

    // execute is a protected suspend member; invoke reflectively as (route, call, auth).
    private suspend fun executeRoute(call: ServerCall, auth: AuthenticationContext): BibleChapter? {
        val method = GetBibleBookChapter::class.declaredMemberFunctions
            .first { it.name == "execute" && it.parameters.size == 3 }
        method.isAccessible = true
        return try {
            @Suppress("UNCHECKED_CAST")
            method.callSuspend(route, call, auth) as BibleChapter?
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    // serializer() is protected; invoke it reflectively.
    private fun invokeSerializer(): KSerializer<*>? {
        val method = GetBibleBookChapter::class.declaredMemberFunctions
            .first { it.name == "serializer" }
        method.isAccessible = true
        return method.call(route) as KSerializer<*>?
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun `serializer returns BibleChapter serializer`() {
        val serializer = invokeSerializer()
        assertEquals(BibleChapter.serializer().descriptor, serializer?.descriptor)
    }

    @Test
    fun `execute returns chapter when book matches by usfm`() = runTest {
        val call = createCall()
        coEvery { metadataService.getById(testId) } returns testMetadata
        coEvery { bibleService.getBible(testId, 1, null) } returns testBible
        coEvery { bibleService.getBooks(testBible) } returns listOf(testBook)
        coEvery { bibleService.getChapter(testBook, "GEN.1") } returns testChapter

        val result = executeRoute(call, authenticationContext)

        assertSame(testChapter, result)
        coVerify { bibleService.getBooks(testBible) }
        coVerify { bibleService.getChapter(testBook, "GEN.1") }
    }

    @Test
    fun `execute returns null when no book matches the requested usfm`() = runTest {
        val call = createCall(
            pathParams = mapOf(
                "id" to testId.toString(),
                "bookUsfm" to "EXO",
                "chapterUsfm" to "EXO.1",
            ),
        )
        coEvery { metadataService.getById(testId) } returns testMetadata
        coEvery { bibleService.getBible(testId, 1, null) } returns testBible
        coEvery { bibleService.getBooks(testBible) } returns listOf(testBook)

        val result = executeRoute(call, authenticationContext)

        assertNull(result)
        coVerify(exactly = 0) { bibleService.getChapter(any<BibleBook>(), any<String>()) }
    }

    @Test
    fun `execute returns null when book list is empty`() = runTest {
        val call = createCall()
        coEvery { metadataService.getById(testId) } returns testMetadata
        coEvery { bibleService.getBible(testId, 1, null) } returns testBible
        coEvery { bibleService.getBooks(testBible) } returns emptyList()

        val result = executeRoute(call, authenticationContext)

        assertNull(result)
    }

    @Test
    fun `execute treats missing bookUsfm path param as literal null string and finds no match`() = runTest {
        // pathParameters["bookUsfm"] is null; .toString() yields "null", which cannot match "GEN".
        val call = createCall(
            pathParams = mapOf(
                "id" to testId.toString(),
                "chapterUsfm" to "GEN.1",
            ),
        )
        coEvery { metadataService.getById(testId) } returns testMetadata
        coEvery { bibleService.getBible(testId, 1, null) } returns testBible
        coEvery { bibleService.getBooks(testBible) } returns listOf(testBook)

        val result = executeRoute(call, authenticationContext)

        assertNull(result)
    }

    @Test
    fun `execute passes missing chapterUsfm as literal null string to getChapter`() = runTest {
        // Book matches, but chapterUsfm path param is absent; .toString() yields "null".
        val call = createCall(
            pathParams = mapOf(
                "id" to testId.toString(),
                "bookUsfm" to "GEN",
            ),
        )
        coEvery { metadataService.getById(testId) } returns testMetadata
        coEvery { bibleService.getBible(testId, 1, null) } returns testBible
        coEvery { bibleService.getBooks(testBible) } returns listOf(testBook)
        coEvery { bibleService.getChapter(testBook, "null") } returns testChapter

        val result = executeRoute(call, authenticationContext)

        assertSame(testChapter, result)
        coVerify { bibleService.getChapter(testBook, "null") }
    }

    @Test
    fun `execute selects the matching book among several`() = runTest {
        val exodus = testBook.copy(usfm = "EXO", nameShort = "Exo", nameLong = "Exodus", abbreviation = "EXO", sort = 2)
        val call = createCall(
            pathParams = mapOf(
                "id" to testId.toString(),
                "bookUsfm" to "EXO",
                "chapterUsfm" to "EXO.1",
            ),
        )
        val exodusChapter = testChapter.copy(bookUsfm = "EXO", usfm = "EXO.1")
        coEvery { metadataService.getById(testId) } returns testMetadata
        coEvery { bibleService.getBible(testId, 1, null) } returns testBible
        coEvery { bibleService.getBooks(testBible) } returns listOf(testBook, exodus)
        coEvery { bibleService.getChapter(exodus, "EXO.1") } returns exodusChapter

        val result = executeRoute(call, authenticationContext)

        assertSame(exodusChapter, result)
        assertTrue(result?.usfm == "EXO.1")
    }
}
