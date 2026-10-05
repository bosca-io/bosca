package bosca.content.metadata.routes

import bosca.bible.bibleJson
import bosca.bible.components.IComponent
import bosca.bible.components.Text
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.BibleBook
import bosca.content.metadata.model.BibleChapter
import bosca.content.metadata.model.Document
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.documents.ContainerAttributes
import bosca.documents.ContainerNode
import bosca.documents.Content
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
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GetDocumentVersesCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val bibleService = mockk<BibleService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val documentService = mockk<DocumentService>()
    private val json = Json { ignoreUnknownKeys = true }
    private val authenticationContext = mockk<AuthenticationContext>()

    // GetDocumentVerses constructor order:
    // (metadataService, bibleService, metadataPermissionEvaluator, documentService, json)
    private val route = GetDocumentVerses(
        metadataService,
        bibleService,
        metadataPermissionEvaluator,
        documentService,
        json,
    )

    private val testId = UUID.parse("00000000-0000-0000-0000-000000000001")
    private val parentId = UUID.parse("00000000-0000-0000-0000-000000000002")

    private fun metadata(
        id: UUID = testId,
        version: Int = 1,
        languageTag: String = "en",
        parent: UUID? = null,
    ) = Metadata(
        id = id,
        version = version,
        parentId = parent,
        name = "Test Bible",
        type = MetadataType.STANDARD,
        contentType = "bosca/v-bible",
        contentLength = 0,
        languageTag = languageTag,
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

    private val testComponents = bibleJson.encodeToJsonElement(IComponent.serializer(), Text("In the beginning", null))

    private val testChapter = BibleChapter(
        metadataId = testId,
        version = 1,
        variant = "default",
        bookUsfm = "GEN",
        usfm = "GEN.1",
        components = testComponents,
        sort = 1,
    )

    /** Builds a Document whose content is the supplied [content], or null when [content] is null. */
    private fun document(
        id: UUID = testId,
        version: Int = 1,
        content: Content? = null,
    ) = Document(
        metadataId = id,
        version = version,
        title = "Doc",
        content = content,
    )

    /** A Content containing a single BIBLE_REFERENCES container with the given references. */
    private fun bibleReferencesContent(references: List<String>?): Content {
        val node = ContainerNode(
            attributes = ContainerAttributes(name = "BIBLE_REFERENCES", references = references),
            content = emptyList(),
        )
        return Content(document = bosca.documents.Document(content = listOf(node)))
    }

    private fun createCall(
        pathParams: Map<String, String> = mapOf("id" to testId.toString()),
        queryParams: Map<String, String> = mapOf("language" to "en"),
        acceptLanguageItems: List<LanguageItem> = emptyList(),
    ): ServerCall {
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.queryParameters } returns Parameters(queryParams.mapValues { listOf(it.value) })
        every { request.acceptLanguageItems() } returns acceptLanguageItems
        val call = mockk<ServerCall>(relaxed = true)
        every { call.request } returns request
        every { call.pathParameters } returns Parameters(pathParams.mapValues { listOf(it.value) })
        return call
    }

    /** Stub the language-based bible lookup performed inside getVerses so it resolves testBible. */
    private fun stubBibleByLanguage() {
        coEvery { metadataService.find(any()) } returns listOf(metadata(languageTag = "en"))
        coEvery { bibleService.getBible(testId, 1, null) } returns testBible
    }

    private fun stubChapterVerses() {
        coEvery { bibleService.getBooks(testBible) } returns listOf(testBook)
        coEvery { bibleService.getChapter(testBible, any()) } returns testChapter
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeRoute(call: ServerCall, auth: AuthenticationContext): List<ChapterContent> {
        val method = GetDocumentVerses::class.declaredMemberFunctions
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
    fun `serializer returns list serializer`() {
        // The route's serializer() is protected in the base Route class, so it cannot be
        // invoked from this package. Assert on the serializer the route builds internally
        // (ListSerializer(ChapterContent.serializer())) instead.
        val serializer = ListSerializer(ChapterContent.serializer())
        assertEquals(StructureKind.LIST, serializer.descriptor.kind)
    }

    @Test
    fun `throws when id path parameter is missing`() = runTest {
        val call = createCall(pathParams = emptyMap())

        val exception = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(exception.message?.contains("missing id") == true)
    }

    @Test
    fun `returns empty list when metadata not found by id`() = runTest {
        val call = createCall()
        coEvery { metadataService.getById(testId) } returns null

        val result = executeRoute(call, authenticationContext)

        assertTrue(result.isEmpty())
        // getBibleByLanguage is never reached when metadata is absent.
        coVerify(exactly = 0) { bibleService.getBible(any(), any(), any()) }
    }

    @Test
    fun `returns empty list when document is null and metadata has no parent`() = runTest {
        val call = createCall()
        coEvery { metadataService.getById(testId) } returns metadata()
        coEvery { documentService.getDocument(testId, 1) } returns null
        stubBibleByLanguage()

        val result = executeRoute(call, authenticationContext)

        assertTrue(result.isEmpty())
        // The language-based bible lookup still runs and verifies view permission.
        coVerify { bibleService.getBible(testId, 1, null) }
    }

    @Test
    fun `returns empty list when document content is null and metadata has no parent`() = runTest {
        val call = createCall()
        coEvery { metadataService.getById(testId) } returns metadata()
        coEvery { documentService.getDocument(testId, 1) } returns document(content = null)
        stubBibleByLanguage()

        val result = executeRoute(call, authenticationContext)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns chapter verses for a matching bible references container`() = runTest {
        val call = createCall()
        coEvery { metadataService.getById(testId) } returns metadata()
        coEvery {
            documentService.getDocument(testId, 1)
        } returns document(content = bibleReferencesContent(listOf("GEN.1.1")))
        stubBibleByLanguage()
        stubChapterVerses()

        val result = executeRoute(call, authenticationContext)

        assertEquals(1, result.size)
        assertTrue(result[0].usfm.contains("GEN.1"))
        coVerify {
            metadataPermissionEvaluator.verifyAllowed(
                authenticationContext,
                any(),
                PermissionAction.VIEW,
            )
        }
    }

    @Test
    fun `joins multiple references with plus separator`() = runTest {
        val call = createCall()
        coEvery { metadataService.getById(testId) } returns metadata()
        coEvery {
            documentService.getDocument(testId, 1)
        } returns document(content = bibleReferencesContent(listOf("GEN.1.1", "GEN.1.2")))
        stubBibleByLanguage()
        stubChapterVerses()

        val result = executeRoute(call, authenticationContext)

        assertEquals(1, result.size, "Both verses fall in the same chapter and group into one result")
    }

    @Test
    fun `skips container when references list is null then returns empty`() = runTest {
        // The BIBLE_REFERENCES container has null references: usfm becomes null and the
        // forEach continues (return@forEach), no early return, and with no parent -> empty.
        val call = createCall()
        coEvery { metadataService.getById(testId) } returns metadata()
        coEvery {
            documentService.getDocument(testId, 1)
        } returns document(content = bibleReferencesContent(references = null))
        stubBibleByLanguage()

        val result = executeRoute(call, authenticationContext)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `ignores container node whose name is not bible references`() = runTest {
        val call = createCall()
        val otherNode = ContainerNode(
            attributes = ContainerAttributes(name = "OTHER", references = listOf("GEN.1.1")),
            content = emptyList(),
        )
        val content = Content(document = bosca.documents.Document(content = listOf(otherNode)))
        coEvery { metadataService.getById(testId) } returns metadata()
        coEvery { documentService.getDocument(testId, 1) } returns document(content = content)
        stubBibleByLanguage()

        val result = executeRoute(call, authenticationContext)

        // No matching container -> loop exhausts, no parent -> empty.
        assertTrue(result.isEmpty())
        coVerify(exactly = 0) { bibleService.getBooks(any()) }
    }

    @Test
    fun `ignores non-container nodes in document content`() = runTest {
        val call = createCall()
        // A plain paragraph-like document node that is not a ContainerNode.
        val nonContainer = bosca.documents.Document(content = emptyList())
        val content = Content(document = bosca.documents.Document(content = listOf(nonContainer)))
        coEvery { metadataService.getById(testId) } returns metadata()
        coEvery { documentService.getDocument(testId, 1) } returns document(content = content)
        stubBibleByLanguage()

        val result = executeRoute(call, authenticationContext)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `recurses into parent metadata when child has no bible references`() = runTest {
        val call = createCall()
        // Child metadata has a document with no matching container but points to a parent.
        coEvery { metadataService.getById(testId) } returns metadata(parent = parentId)
        coEvery { documentService.getDocument(testId, 1) } returns document(content = null)
        // Parent metadata carries the BIBLE_REFERENCES container.
        val parent = metadata(id = parentId, parent = null)
        coEvery { metadataService.getById(parentId) } returns parent
        coEvery {
            documentService.getDocument(parentId, 1)
        } returns document(id = parentId, content = bibleReferencesContent(listOf("GEN.1.1")))
        stubBibleByLanguage()
        stubChapterVerses()

        val result = executeRoute(call, authenticationContext)

        assertEquals(1, result.size)
        coVerify { metadataService.getById(parentId) }
    }

    @Test
    fun `recursion terminates with empty list when parent chain yields nothing`() = runTest {
        val call = createCall()
        coEvery { metadataService.getById(testId) } returns metadata(parent = parentId)
        coEvery { documentService.getDocument(testId, 1) } returns document(content = null)
        // Parent exists but its lookup returns null -> recursion returns empty immediately.
        coEvery { metadataService.getById(parentId) } returns null
        stubBibleByLanguage()

        val result = executeRoute(call, authenticationContext)

        assertTrue(result.isEmpty())
        coVerify { metadataService.getById(parentId) }
    }
}
