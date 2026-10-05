package bosca.content.metadata.routes

import bosca.bible.Reference
import bosca.bible.bibleJson
import bosca.bible.components.Text
import bosca.content.find.FindQueryInput
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
import bosca.server.Parameters
import bosca.server.ServerCall
import bosca.server.ServerRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import bosca.bible.components.IComponent
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FindBibleReferencesTest {

    private val metadataService = mockk<MetadataService>()
    private val bibleService = mockk<BibleService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val authenticationContext = mockk<AuthenticationContext>()

    private val route = FindBibleReferences(metadataService, bibleService, metadataPermissionEvaluator)

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

    private val testComponents = bibleJson.encodeToJsonElement<IComponent>(Text("In the beginning", null))

    private val testChapter = BibleChapter(
        metadataId = testId,
        version = 1,
        variant = "default",
        bookUsfm = "GEN",
        usfm = "GEN.1",
        components = testComponents,
        sort = 1,
    )

    private fun createCall(
        pathParams: Map<String, String> = mapOf("id" to testId.toString()),
        queryParams: Map<String, String> = mapOf("human" to "Genesis 1:1"),
    ): ServerCall {
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.queryParameters } returns Parameters(queryParams.mapValues { listOf(it.value) })
        val call = mockk<ServerCall>(relaxed = true)
        every { call.request } returns request
        every { call.pathParameters } returns Parameters(pathParams.mapValues { listOf(it.value) })
        return call
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeRoute(call: ServerCall, auth: AuthenticationContext): List<ChapterContent> {
        val method = FindBibleReferences::class.declaredMemberFunctions
            .first { it.name == "execute" && it.parameters.size == 3 }
        method.isAccessible = true
        try {
            return method.callSuspend(route, call, auth) as List<ChapterContent>
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    @Test
    fun `executes with metadata found by id`() = runTest {
        val call = createCall()
        coEvery { metadataService.getById(testId) } returns testMetadata
        coEvery { bibleService.getBible(testId, 1, null) } returns testBible
        coEvery { bibleService.getReferences(testBible, "Genesis 1:1") } returns listOf(Reference("GEN.1.1"))
        coEvery { bibleService.getBooks(testBible) } returns listOf(testBook)
        coEvery { bibleService.getChapter(testBible, Reference("GEN.1")) } returns testChapter

        val result = executeRoute(call, authenticationContext)

        coVerify { metadataPermissionEvaluator.verifyAllowed(authenticationContext, testMetadata, PermissionAction.VIEW) }
        assertEquals(1, result.size)
        assertEquals("GEN.1.1", result[0].usfm)
        assertEquals("Gen 1:1", result[0].human)
        assertEquals("Genesis 1:1", result[0].humanLong)
    }

    @Test
    fun `falls back to find when getById returns null`() = runTest {
        val call = createCall()
        coEvery { metadataService.getById(testId) } returns null
        coEvery { metadataService.find(FindQueryInput(contentTypes = listOf("bosca/v-bible"))) } returns listOf(testMetadata)
        coEvery { bibleService.getBible(testId, 1, null) } returns testBible
        coEvery { bibleService.getReferences(testBible, "Genesis 1:1") } returns listOf(Reference("GEN.1.1"))
        coEvery { bibleService.getBooks(testBible) } returns listOf(testBook)
        coEvery { bibleService.getChapter(testBible, Reference("GEN.1")) } returns testChapter

        val result = executeRoute(call, authenticationContext)

        coVerify { metadataService.find(FindQueryInput(contentTypes = listOf("bosca/v-bible"))) }
        assertEquals(1, result.size)
    }

    @Test
    fun `returns empty content list when content parameter is false`() = runTest {
        val call = createCall(queryParams = mapOf("human" to "Genesis 1:1", "content" to "false"))
        coEvery { metadataService.getById(testId) } returns testMetadata
        coEvery { bibleService.getBible(testId, 1, null) } returns testBible
        coEvery { bibleService.getReferences(testBible, "Genesis 1:1") } returns listOf(Reference("GEN.1.1"))
        coEvery { bibleService.getBooks(testBible) } returns listOf(testBook)
        coEvery { bibleService.getChapter(testBible, Reference("GEN.1")) } returns testChapter

        val result = executeRoute(call, authenticationContext)

        assertEquals(1, result.size)
        assertTrue(result[0].content.isEmpty())
    }

    @Test
    fun `throws when id path parameter is missing`() = runTest {
        val call = createCall(pathParams = emptyMap())

        val exception = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(exception.message!!.contains("missing id"))
    }

    @Test
    fun `throws when human query parameter is missing`() = runTest {
        val call = createCall(queryParams = emptyMap())
        coEvery { metadataService.getById(testId) } returns testMetadata

        val exception = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(exception.message!!.contains("missing human"))
    }

    @Test
    fun `throws when metadata not found by id or find`() = runTest {
        val call = createCall()
        coEvery { metadataService.getById(testId) } returns null
        coEvery { metadataService.find(any()) } returns emptyList()

        val exception = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(exception.message!!.contains("Metadata not found"))
    }

    @Test
    fun `throws when bible not found`() = runTest {
        val call = createCall()
        coEvery { metadataService.getById(testId) } returns testMetadata
        coEvery { bibleService.getBible(testId, 1, null) } returns null

        val exception = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(exception.message!!.contains("Bible not found"))
    }

    @Test
    fun `content defaults to true when parameter not provided`() = runTest {
        val call = createCall(queryParams = mapOf("human" to "Genesis 1:1"))
        coEvery { metadataService.getById(testId) } returns testMetadata
        coEvery { bibleService.getBible(testId, 1, null) } returns testBible
        coEvery { bibleService.getReferences(testBible, "Genesis 1:1") } returns listOf(Reference("GEN.1.1"))
        coEvery { bibleService.getBooks(testBible) } returns listOf(testBook)
        coEvery { bibleService.getChapter(testBible, Reference("GEN.1")) } returns testChapter

        val result = executeRoute(call, authenticationContext)

        assertEquals(1, result.size)
    }

    @Test
    fun `handles multiple references from a single human string`() = runTest {
        val call = createCall(queryParams = mapOf("human" to "Genesis 1:1-3"))
        coEvery { metadataService.getById(testId) } returns testMetadata
        coEvery { bibleService.getBible(testId, 1, null) } returns testBible
        coEvery { bibleService.getReferences(testBible, "Genesis 1:1-3") } returns listOf(
            Reference("GEN.1.1"),
            Reference("GEN.1.2"),
            Reference("GEN.1.3"),
        )
        coEvery { bibleService.getBooks(testBible) } returns listOf(testBook)
        coEvery { bibleService.getChapter(testBible, any()) } returns testChapter

        val result = executeRoute(call, authenticationContext)

        assertEquals(1, result.size, "All references in the same chapter should be grouped into one result")
        assertTrue(result[0].usfm.contains("GEN.1"), "Result should reference Genesis chapter 1")
    }
}
