package bosca.content.metadata.routes

import bosca.bible.Reference
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

class GetBibleLanguageVersesCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val bibleService = mockk<BibleService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val authenticationContext = mockk<AuthenticationContext>()

    // GetBibleLanguageVerses constructor order: (metadataService, metadataPermissionEvaluator, bibleService)
    private val route = GetBibleLanguageVerses(metadataService, metadataPermissionEvaluator, bibleService)

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

    private fun book(
        nameShort: String? = "Gen",
        nameLong: String? = "Genesis",
        abbreviation: String = "GEN",
    ) = BibleBook(
        metadataId = testId,
        version = 1,
        variant = "default",
        usfm = "GEN",
        nameShort = nameShort,
        nameLong = nameLong,
        abbreviation = abbreviation,
        sort = 1,
    )

    private val testComponents = bibleJson.encodeToJsonElement<IComponent>(Text("In the beginning", null))

    private fun chapter(components: kotlinx.serialization.json.JsonElement? = testComponents) = BibleChapter(
        metadataId = testId,
        version = 1,
        variant = "default",
        bookUsfm = "GEN",
        usfm = "GEN.1",
        components = components,
        sort = 1,
    )

    private fun createCall(
        queryParams: Map<String, String> = mapOf("language" to "en", "usfm" to "GEN.1.1"),
    ): ServerCall {
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.queryParameters } returns Parameters(queryParams.mapValues { listOf(it.value) })
        every { request.acceptLanguageItems() } returns emptyList<LanguageItem>()
        val call = mockk<ServerCall>(relaxed = true)
        every { call.request } returns request
        every { call.pathParameters } returns Parameters(emptyMap())
        return call
    }

    private fun stubLanguageBible() {
        coEvery { metadataService.find(any()) } returns listOf(testMetadata)
        coEvery { bibleService.getBible(testId, 1, null) } returns testBible
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeRoute(call: ServerCall, auth: AuthenticationContext): List<ChapterContent> {
        val method = GetBibleLanguageVerses::class.declaredMemberFunctions
            .first { it.name == "execute" && it.parameters.size == 3 }
        method.isAccessible = true
        try {
            return method.callSuspend(route, call, auth) as List<ChapterContent>
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
    fun `serializer describes a list of chapter contents`() {
        // Route.serializer() is protected, so we assert against the same serializer the route
        // builds: ListSerializer(ChapterContent.serializer()). ListSerializer produces a LIST-kind
        // descriptor whose single element descriptor is the ChapterContent serializer's descriptor.
        val serializer = ListSerializer(ChapterContent.serializer())
        val descriptor = serializer.descriptor
        assertEquals(kotlinx.serialization.descriptors.StructureKind.LIST, descriptor.kind)
        assertEquals(
            ChapterContent.serializer().descriptor.serialName,
            descriptor.getElementDescriptor(0).serialName,
        )
    }

    @Test
    fun `executes for a single verse and resolves human names via nameShort and nameLong`() = runTest {
        val call = createCall()
        stubLanguageBible()
        coEvery { bibleService.getBooks(testBible) } returns listOf(book())
        coEvery { bibleService.getChapter(testBible, Reference("GEN.1")) } returns chapter()

        val result = executeRoute(call, authenticationContext)

        coVerify {
            metadataPermissionEvaluator.verifyAllowed(authenticationContext, testMetadata, PermissionAction.VIEW)
        }
        assertEquals(1, result.size)
        assertEquals("GEN.1.1", result[0].usfm)
        // human uses nameShort ("Gen"); humanLong uses nameLong ("Genesis").
        assertEquals("Gen 1:1", result[0].human)
        assertEquals("Genesis 1:1", result[0].humanLong)
        // components are stripped off the returned chapter copy.
        assertNull(result[0].chapter.components)
        assertEquals(1, result[0].content.size)
    }

    @Test
    fun `replaces spaces with plus in the usfm query parameter`() = runTest {
        // "GEN.1.1 GEN.1.2" becomes "GEN.1.1+GEN.1.2" -> two references in the same chapter group.
        val call = createCall(queryParams = mapOf("language" to "en", "usfm" to "GEN.1.1 GEN.1.2"))
        stubLanguageBible()
        coEvery { bibleService.getBooks(testBible) } returns listOf(book())
        coEvery { bibleService.getChapter(testBible, Reference("GEN.1")) } returns chapter()

        val result = executeRoute(call, authenticationContext)

        assertEquals(1, result.size, "Both references share chapter GEN.1 and collapse into one result")
        assertTrue(result[0].usfm.contains("GEN.1.1"))
        assertTrue(result[0].usfm.contains("GEN.1.2"))
        assertEquals(2, result[0].content.size)
    }

    @Test
    fun `throws when usfm query parameter is missing`() = runTest {
        val call = createCall(queryParams = mapOf("language" to "en"))
        stubLanguageBible()

        val exception = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertEquals("missing usfm", exception.message)
    }

    @Test
    fun `skips chapters whose components are null`() = runTest {
        val call = createCall()
        stubLanguageBible()
        coEvery { bibleService.getBooks(testBible) } returns listOf(book())
        coEvery { bibleService.getChapter(testBible, Reference("GEN.1")) } returns chapter(components = null)

        val result = executeRoute(call, authenticationContext)

        assertTrue(result.isEmpty(), "A chapter with no components must be skipped via continue")
    }

    @Test
    fun `falls back to nameLong for human when nameShort is null`() = runTest {
        val call = createCall()
        stubLanguageBible()
        coEvery { bibleService.getBooks(testBible) } returns listOf(book(nameShort = null))
        coEvery { bibleService.getChapter(testBible, Reference("GEN.1")) } returns chapter()

        val result = executeRoute(call, authenticationContext)

        assertEquals(1, result.size)
        // human elvis chain: nameShort null -> nameLong "Genesis".
        assertEquals("Genesis 1:1", result[0].human)
        assertEquals("Genesis 1:1", result[0].humanLong)
    }

    @Test
    fun `falls back to abbreviation for human names when short and long are null`() = runTest {
        val call = createCall()
        stubLanguageBible()
        coEvery { bibleService.getBooks(testBible) } returns listOf(book(nameShort = null, nameLong = null))
        coEvery { bibleService.getChapter(testBible, Reference("GEN.1")) } returns chapter()

        val result = executeRoute(call, authenticationContext)

        assertEquals(1, result.size)
        // both human and humanLong fall all the way through to abbreviation "GEN".
        assertEquals("GEN 1:1", result[0].human)
        assertEquals("GEN 1:1", result[0].humanLong)
    }

    @Test
    fun `returns empty list when the reference maps to no chapters`() = runTest {
        // A bare book usfm ("GEN") has chapterUsfm == "" and produces a single group keyed by "";
        // the chapter for that empty reference has null components, so it is skipped -> empty result.
        val call = createCall(queryParams = mapOf("language" to "en", "usfm" to "GEN"))
        stubLanguageBible()
        coEvery { bibleService.getBooks(testBible) } returns listOf(book())
        coEvery { bibleService.getChapter(testBible, Reference("")) } returns chapter(components = null)

        val result = executeRoute(call, authenticationContext)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `propagates bible-not-found error from language resolution`() = runTest {
        val call = createCall()
        coEvery { metadataService.find(any()) } returns emptyList()

        val exception = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(exception.message?.contains("Bible not found") == true)
    }
}
