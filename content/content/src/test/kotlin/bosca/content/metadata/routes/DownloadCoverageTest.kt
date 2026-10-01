package bosca.content.metadata.routes

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataSupplementary
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.ContentType
import bosca.server.HttpHeaders
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import bosca.server.StreamingResponse
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.UrlSigner
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.time.OffsetDateTime
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val objectService = mockk<ObjectStorageService>()
    private val urlSigner = mockk<UrlSigner>()
    private val authenticationContext = mockk<AuthenticationContext>()

    private val route = Download(metadataService, metadataPermissionEvaluator, objectService, urlSigner)

    private val testId = UUID.parse("00000000-0000-0000-0000-000000000001")
    private val supplementaryId = UUID.parse("00000000-0000-0000-0000-000000000002")

    private fun metadata(
        etag: String? = null,
        contentLength: Long? = 100L,
        contentType: String = "text/plain",
        name: String = "file.txt",
    ) = Metadata(
        id = testId,
        version = 1,
        name = name,
        type = MetadataType.STANDARD,
        contentType = contentType,
        contentLength = contentLength,
        languageTag = "en",
        workflowStateId = "published",
        etag = etag,
    )

    private fun supplementary(
        contentLength: Long? = 50L,
        contentType: String? = "application/pdf",
        name: String = "supp.pdf",
    ) = MetadataSupplementary(
        id = supplementaryId,
        metadataId = testId,
        key = "thumb",
        name = name,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        contentType = contentType,
        contentLength = contentLength,
    )

    /** Captures everything a route writes to the mocked [ServerCall]/[ServerResponse]. */
    private class Recorded {
        var status: HttpStatusCode? = null
        var committed = false
        var contentType: ContentType? = null
        val headers = mutableMapOf<String, String>()
        val streamed = ByteArrayOutputStream()
    }

    private fun recordedCall(
        queryParameters: Map<String, String> = mapOf("id" to testId.toString()),
        headers: Map<String, String> = emptyMap(),
        uri: String = "/api/v1/content/metadata/download?id=$testId",
    ): Pair<ServerCall, Recorded> {
        val recorded = Recorded()

        val request = mockk<ServerRequest>(relaxed = true)
        every { request.queryParameters } returns Parameters(queryParameters.mapValues { listOf(it.value) })
        every { request.header(any()) } answers { headers[firstArg()] }
        every { request.uri } returns uri

        val response = mockk<ServerResponse>(relaxed = true)
        every { response.isCommitted } answers { recorded.committed }
        every { response.status() } answers { recorded.status }
        every { response.header(any(), any()) } answers {
            recorded.headers[firstArg()] = secondArg()
        }
        every { response.status(any<HttpStatusCode>()) } answers { recorded.status = firstArg() }
        every { response.commit(any()) } answers { recorded.committed = true }
        every { response.respondText(any(), any(), any()) } answers {
            if (!recorded.committed) {
                recorded.status = thirdArg()
                recorded.committed = true
            }
        }

        val streaming = mockk<StreamingResponse>(relaxed = true)
        coEvery { streaming.copyFrom(any(), any()) } coAnswers {
            firstArg<InputStream>().copyTo(recorded.streamed)
        }

        val call = mockk<ServerCall>(relaxed = true)
        every { call.request } returns request
        every { call.response } returns response
        // Single-arg respond(HttpStatusCode) is a non-inline member — safe to stub directly.
        every { call.respond(any<HttpStatusCode>()) } answers {
            if (!recorded.committed) {
                recorded.status = firstArg()
                recorded.committed = true
            }
        }
        // The 2-arg respond(status, message) is a `suspend inline reified` function and MUST NOT be
        // stubbed — its inlined body would run inside the recorder. It delegates to
        // ServerResponse.respondText, captured above.
        // Downloads stream with no time limit (null); a limited call would not match.
        coEvery { call.respondStreaming(any(), any(), isNull(), any()) } coAnswers {
            recorded.contentType = firstArg()
            recorded.status = secondArg()
            recorded.committed = true
            arg<suspend (StreamingResponse) -> Unit>(3).invoke(streaming)
        }
        return call to recorded
    }

    private fun objectPath() = mockk<ObjectPath>(relaxed = true)

    /**
     * Drives the route's protected `execute(call, auth)` directly (bypassing the DI-heavy public
     * wrapper), supplying a mocked [ConnectionManager] in the coroutine context so the route's
     * `connection().release()` call resolves.
     */
    private suspend fun executeRoute(call: ServerCall, auth: AuthenticationContext, download: Download = route) {
        val manager = mockk<ConnectionManager>(relaxed = true)
        val method = Download::class.declaredMemberFunctions
            .first { it.name == "execute" && it.parameters.size == 3 }
        method.isAccessible = true
        try {
            withContext(manager.asCoroutineContext()) {
                method.callSuspend(download, call, auth)
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    // ---------------------------------------------------------------------------------------------
    // parseRangeHeader — pure function, all branches
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `parseRangeHeader returns null for null header`() {
        assertNull(Download.parseRangeHeader(null, 100))
    }

    @Test
    fun `parseRangeHeader returns null when prefix is not bytes`() {
        assertNull(Download.parseRangeHeader("items=0-10", 100))
    }

    @Test
    fun `parseRangeHeader returns null when spec has no dash`() {
        assertNull(Download.parseRangeHeader("bytes=500", 100))
    }

    @Test
    fun `parseRangeHeader parses explicit range`() {
        assertEquals(0L..499L, Download.parseRangeHeader("bytes=0-499", 1000))
    }

    @Test
    fun `parseRangeHeader clamps explicit end to content length`() {
        assertEquals(0L..99L, Download.parseRangeHeader("bytes=0-999", 100))
    }

    @Test
    fun `parseRangeHeader parses open-ended range`() {
        assertEquals(500L..<1000L, Download.parseRangeHeader("bytes=500-", 1000))
    }

    @Test
    fun `parseRangeHeader parses suffix range`() {
        assertEquals(500L..<1000L, Download.parseRangeHeader("bytes=-500", 1000))
    }

    @Test
    fun `parseRangeHeader suffix larger than content coerces start to zero`() {
        assertEquals(0L..<100L, Download.parseRangeHeader("bytes=-500", 100))
    }

    @Test
    fun `parseRangeHeader takes only the first range when multiple provided`() {
        assertEquals(0L..99L, Download.parseRangeHeader("bytes=0-99,200-299", 1000))
    }

    @Test
    fun `parseRangeHeader returns null for non-numeric suffix`() {
        assertNull(Download.parseRangeHeader("bytes=-abc", 1000))
    }

    @Test
    fun `parseRangeHeader returns null for non-numeric open-ended start`() {
        assertNull(Download.parseRangeHeader("bytes=abc-", 1000))
    }

    @Test
    fun `parseRangeHeader returns null when open-ended start beyond content`() {
        assertNull(Download.parseRangeHeader("bytes=1000-", 1000))
    }

    @Test
    fun `parseRangeHeader returns null for non-numeric explicit start`() {
        assertNull(Download.parseRangeHeader("bytes=x-10", 1000))
    }

    @Test
    fun `parseRangeHeader returns null for non-numeric explicit end`() {
        assertNull(Download.parseRangeHeader("bytes=0-x", 1000))
    }

    @Test
    fun `parseRangeHeader returns null when start greater than end`() {
        assertNull(Download.parseRangeHeader("bytes=500-100", 1000))
    }

    @Test
    fun `parseRangeHeader returns null when explicit start beyond content`() {
        assertNull(Download.parseRangeHeader("bytes=1000-1500", 1000))
    }

    @Test
    fun `parseRangeHeader returns null for empty spec`() {
        assertNull(Download.parseRangeHeader("bytes=-", 1000))
    }

    @Test
    fun `parseRangeHeader trims surrounding whitespace`() {
        assertEquals(0L..10L, Download.parseRangeHeader("  bytes=0-10  ", 1000))
    }

    // ---------------------------------------------------------------------------------------------
    // execute — error / not-found paths
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `execute throws when id query parameter is missing`() = runTest {
        val (call, _) = recordedCall(queryParameters = emptyMap())
        val ex = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(ex.message?.contains("missing id") == true)
    }

    @Test
    fun `execute throws when metadata not found`() = runTest {
        val (call, _) = recordedCall()
        coEvery { metadataService.getById(testId) } returns null
        val ex = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(ex.message?.contains("Metadata not found") == true)
    }

    @Test
    fun `execute responds Forbidden when not allowed and url not signed`() = runTest {
        val (call, recorded) = recordedCall()
        val md = metadata()
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataPermissionEvaluator.isContentAllowed(authenticationContext, md, PermissionAction.VIEW) } returns false
        every { urlSigner.verify(any()) } returns false

        executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.Forbidden, recorded.status)
    }

    // ---------------------------------------------------------------------------------------------
    // execute — happy paths
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `execute streams primary content when allowed`() = runTest {
        val (call, recorded) = recordedCall()
        val md = metadata(contentType = "text/plain", contentLength = 5L)
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataPermissionEvaluator.isContentAllowed(authenticationContext, md, PermissionAction.VIEW) } returns true
        coEvery { objectService.getPath(md, null) } returns objectPath()
        coEvery { objectService.getInputStream(any()) } returns ByteArrayInputStream("hello".toByteArray())

        executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.OK, recorded.status)
        assertEquals("hello", recorded.streamed.toByteArray().decodeToString())
        assertEquals("bytes", recorded.headers[HttpHeaders.AcceptRanges])
        assertEquals("5", recorded.headers[HttpHeaders.ContentLength])
    }

    @Test
    fun `execute proceeds when url is signed even though not allowed`() = runTest {
        val (call, recorded) = recordedCall()
        val md = metadata(contentLength = 3L)
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataPermissionEvaluator.isContentAllowed(authenticationContext, md, PermissionAction.VIEW) } returns false
        every { urlSigner.verify(any()) } returns true
        coEvery { objectService.getPath(md, null) } returns objectPath()
        coEvery { objectService.getInputStream(any()) } returns ByteArrayInputStream("abc".toByteArray())

        executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.OK, recorded.status)
        // verifySupplementaryAllowed must NOT run when urlSigned=true and no supplementary requested.
        coVerify(exactly = 0) {
            metadataPermissionEvaluator.verifySupplementaryAllowed(any(), any(), any())
        }
    }

    @Test
    fun `execute serves supplementary content and verifies supplementary permission`() = runTest {
        val (call, recorded) = recordedCall(
            queryParameters = mapOf(
                "id" to testId.toString(),
                "supplementaryId" to supplementaryId.toString(),
            ),
        )
        val md = metadata()
        val supp = supplementary(contentType = "application/pdf", contentLength = 4L)
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataPermissionEvaluator.isContentAllowed(authenticationContext, md, PermissionAction.VIEW) } returns true
        coEvery { metadataService.getSupplementaryById(supplementaryId) } returns supp
        coEvery { objectService.getPath(md, supplementaryId) } returns objectPath()
        coEvery { objectService.getInputStream(any()) } returns ByteArrayInputStream("pdfx".toByteArray())

        executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.OK, recorded.status)
        coVerify {
            metadataPermissionEvaluator.verifySupplementaryAllowed(authenticationContext, md, PermissionAction.VIEW)
        }
        assertEquals("4", recorded.headers[HttpHeaders.ContentLength])
    }

    @Test
    fun `execute throws when supplementary not found`() = runTest {
        val (call, _) = recordedCall(
            queryParameters = mapOf(
                "id" to testId.toString(),
                "supplementaryId" to supplementaryId.toString(),
            ),
        )
        val md = metadata()
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataPermissionEvaluator.isContentAllowed(authenticationContext, md, PermissionAction.VIEW) } returns true
        coEvery { metadataService.getSupplementaryById(supplementaryId) } returns null

        assertFailsWith<NoSuchElementException> {
            executeRoute(call, authenticationContext)
        }
    }

    @Test
    fun `file route streams attachment when download requested`() = runTest {
        val (call, recorded) = recordedCall(
            queryParameters = mapOf("id" to testId.toString(), "download" to "true"),
            uri = "/content/file?id=$testId&download=true",
        )
        val md = metadata(name = "audio.mp3", contentLength = 2L)
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataPermissionEvaluator.isContentAllowed(authenticationContext, md, PermissionAction.VIEW) } returns true
        coEvery { objectService.getPath(md, null) } returns objectPath()
        coEvery { objectService.getInputStream(any()) } returns ByteArrayInputStream("ok".toByteArray())

        executeRoute(call, authenticationContext, DownloadFile(metadataService, metadataPermissionEvaluator, objectService, urlSigner))

        assertEquals(HttpStatusCode.OK, recorded.status)
        assertEquals("attachment; filename=\"audio.mp3\"", recorded.headers[HttpHeaders.ContentDisposition])
        assertEquals("ok", recorded.streamed.toString())
    }

    @Test
    fun `execute sets content disposition header when filename requested`() = runTest {
        val (call, recorded) = recordedCall(
            queryParameters = mapOf(
                "id" to testId.toString(),
                "filename" to "true",
            ),
        )
        val md = metadata(name = "my \"quoted\"\nfile.txt", contentLength = 2L)
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataPermissionEvaluator.isContentAllowed(authenticationContext, md, PermissionAction.VIEW) } returns true
        coEvery { objectService.getPath(md, null) } returns objectPath()
        coEvery { objectService.getInputStream(any()) } returns ByteArrayInputStream("ok".toByteArray())

        executeRoute(call, authenticationContext)

        val disposition = recorded.headers[HttpHeaders.ContentDisposition]
        assertTrue(disposition?.startsWith("attachment; filename=") == true)
        // Quotes escaped, newlines replaced with spaces.
        assertTrue(disposition?.contains("\\\"quoted\\\"") == true)
    }

    @Test
    fun `execute uses supplementary name for content disposition`() = runTest {
        val (call, recorded) = recordedCall(
            queryParameters = mapOf(
                "id" to testId.toString(),
                "supplementaryId" to supplementaryId.toString(),
                "filename" to "true",
            ),
        )
        val md = metadata()
        val supp = supplementary(name = "supplementary-name.pdf", contentLength = 2L)
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataPermissionEvaluator.isContentAllowed(authenticationContext, md, PermissionAction.VIEW) } returns true
        coEvery { metadataService.getSupplementaryById(supplementaryId) } returns supp
        coEvery { objectService.getPath(md, supplementaryId) } returns objectPath()
        coEvery { objectService.getInputStream(any()) } returns ByteArrayInputStream("ok".toByteArray())

        executeRoute(call, authenticationContext)

        assertTrue(recorded.headers[HttpHeaders.ContentDisposition]?.contains("supplementary-name.pdf") == true)
    }

    // ---------------------------------------------------------------------------------------------
    // execute — ETag / conditional requests
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `execute writes etag and cache control headers`() = runTest {
        val (call, recorded) = recordedCall()
        val md = metadata(etag = "abc123", contentLength = 2L)
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataPermissionEvaluator.isContentAllowed(authenticationContext, md, PermissionAction.VIEW) } returns true
        coEvery { objectService.getPath(md, null) } returns objectPath()
        coEvery { objectService.getInputStream(any()) } returns ByteArrayInputStream("ok".toByteArray())

        executeRoute(call, authenticationContext)

        assertEquals("W/\"abc123\"", recorded.headers[HttpHeaders.ETag])
        assertEquals("no-cache", recorded.headers[HttpHeaders.CacheControl])
        assertEquals(HttpStatusCode.OK, recorded.status)
    }

    @Test
    fun `execute returns NotModified for star if-none-match`() = runTest {
        val (call, recorded) = recordedCall(headers = mapOf(HttpHeaders.IfNoneMatch to "*"))
        val md = metadata(etag = "abc123")
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataPermissionEvaluator.isContentAllowed(authenticationContext, md, PermissionAction.VIEW) } returns true

        executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.NotModified, recorded.status)
        coVerify(exactly = 0) { objectService.getInputStream(any()) }
    }

    @Test
    fun `execute returns NotModified when weak etag matches`() = runTest {
        val (call, recorded) = recordedCall(headers = mapOf(HttpHeaders.IfNoneMatch to "W/\"abc123\""))
        val md = metadata(etag = "abc123")
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataPermissionEvaluator.isContentAllowed(authenticationContext, md, PermissionAction.VIEW) } returns true

        executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.NotModified, recorded.status)
    }

    @Test
    fun `execute returns NotModified when strong etag in list matches`() = runTest {
        val (call, recorded) = recordedCall(headers = mapOf(HttpHeaders.IfNoneMatch to "\"other\", \"abc123\""))
        val md = metadata(etag = "abc123")
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataPermissionEvaluator.isContentAllowed(authenticationContext, md, PermissionAction.VIEW) } returns true

        executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.NotModified, recorded.status)
    }

    @Test
    fun `execute continues to stream when if-none-match does not match`() = runTest {
        val (call, recorded) = recordedCall(headers = mapOf(HttpHeaders.IfNoneMatch to "\"different\""))
        val md = metadata(etag = "abc123", contentLength = 2L)
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataPermissionEvaluator.isContentAllowed(authenticationContext, md, PermissionAction.VIEW) } returns true
        coEvery { objectService.getPath(md, null) } returns objectPath()
        coEvery { objectService.getInputStream(any()) } returns ByteArrayInputStream("ok".toByteArray())

        executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.OK, recorded.status)
    }

    // ---------------------------------------------------------------------------------------------
    // execute — Range requests
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `execute serves partial content for range request`() = runTest {
        val (call, recorded) = recordedCall(headers = mapOf(HttpHeaders.Range to "bytes=0-3"))
        val md = metadata(contentLength = 100L)
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataPermissionEvaluator.isContentAllowed(authenticationContext, md, PermissionAction.VIEW) } returns true
        coEvery { objectService.getPath(md, null) } returns objectPath()
        coEvery { objectService.getInputStreamRange(any(), any()) } returns ByteArrayInputStream("data".toByteArray())

        executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.PartialContent, recorded.status)
        assertEquals("bytes 0-3/100", recorded.headers[HttpHeaders.ContentRange])
        assertEquals("4", recorded.headers[HttpHeaders.ContentLength])
        coVerify { objectService.getInputStreamRange(any(), any()) }
    }

    @Test
    fun `execute caps oversized range to max chunk size`() = runTest {
        // 300 MB total; request the whole thing so the chunk exceeds the 200 MB cap.
        val total = 300L * 1024 * 1024
        val max = 200L * 1024 * 1024
        val (call, recorded) = recordedCall(headers = mapOf(HttpHeaders.Range to "bytes=0-"))
        val md = metadata(contentLength = total)
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataPermissionEvaluator.isContentAllowed(authenticationContext, md, PermissionAction.VIEW) } returns true
        coEvery { objectService.getPath(md, null) } returns objectPath()
        coEvery { objectService.getInputStreamRange(any(), any()) } returns ByteArrayInputStream("data".toByteArray())

        executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.PartialContent, recorded.status)
        // Capped chunk is [0, max) so last index is max-1 and content length is max.
        assertEquals("bytes 0-${max - 1}/$total", recorded.headers[HttpHeaders.ContentRange])
        assertEquals("$max", recorded.headers[HttpHeaders.ContentLength])
    }

    @Test
    fun `execute uses supplementary content length for range and content range header`() = runTest {
        val (call, recorded) = recordedCall(
            queryParameters = mapOf(
                "id" to testId.toString(),
                "supplementaryId" to supplementaryId.toString(),
            ),
            headers = mapOf(HttpHeaders.Range to "bytes=0-9"),
        )
        val md = metadata()
        val supp = supplementary(contentLength = 40L)
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataPermissionEvaluator.isContentAllowed(authenticationContext, md, PermissionAction.VIEW) } returns true
        coEvery { metadataService.getSupplementaryById(supplementaryId) } returns supp
        coEvery { objectService.getPath(md, supplementaryId) } returns objectPath()
        coEvery { objectService.getInputStreamRange(any(), any()) } returns ByteArrayInputStream("data".toByteArray())

        executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.PartialContent, recorded.status)
        assertEquals("bytes 0-9/40", recorded.headers[HttpHeaders.ContentRange])
    }

    @Test
    fun `execute streams full content when metadata content length is null`() = runTest {
        val (call, recorded) = recordedCall(headers = mapOf(HttpHeaders.Range to "bytes=0-3"))
        val md = metadata(contentLength = null)
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataPermissionEvaluator.isContentAllowed(authenticationContext, md, PermissionAction.VIEW) } returns true
        coEvery { objectService.getPath(md, null) } returns objectPath()
        coEvery { objectService.getInputStream(any()) } returns ByteArrayInputStream("full".toByteArray())

        executeRoute(call, authenticationContext)

        // With no content length, range parsing is skipped entirely — full 200 response.
        assertEquals(HttpStatusCode.OK, recorded.status)
        assertNull(recorded.headers[HttpHeaders.ContentRange])
        assertNull(recorded.headers[HttpHeaders.ContentLength])
        coVerify { objectService.getInputStream(any()) }
    }

    // ---------------------------------------------------------------------------------------------
    // execute — missing and unreadable objects
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `execute responds NotFound without the object's headers when the object is missing`() = runTest {
        val (call, recorded) = recordedCall(
            queryParameters = mapOf("id" to testId.toString(), "download" to "true"),
        )
        val md = metadata(contentLength = 10L, etag = "abc123")
        val path = objectPath()
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataPermissionEvaluator.isContentAllowed(authenticationContext, md, PermissionAction.VIEW) } returns true
        coEvery { objectService.getPath(md, null) } returns path
        coEvery { objectService.getInputStream(any()) } throws ObjectNotFoundException(path)

        executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.NotFound, recorded.status)
        // The 404 must not claim the missing object's size, or the client waits for bytes never sent.
        assertEquals(null, recorded.headers[HttpHeaders.ContentLength])
        assertEquals(null, recorded.headers[HttpHeaders.ETag])
        assertEquals(null, recorded.headers[HttpHeaders.ContentDisposition])
        assertEquals(null, recorded.headers[HttpHeaders.AcceptRanges])
    }

    @Test
    fun `execute fails the response when a lazy backend reports the object missing mid-stream`() = runTest {
        val (call, recorded) = recordedCall()
        val md = metadata(contentLength = 10L)
        val path = objectPath()
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataPermissionEvaluator.isContentAllowed(authenticationContext, md, PermissionAction.VIEW) } returns true
        coEvery { objectService.getPath(md, null) } returns path
        // GCS opens lazily: the object turns out to be missing only on the first read, once the
        // 200 and its Content-Length are committed.
        coEvery { objectService.getInputStream(any()) } returns object : InputStream() {
            override fun read(): Int = throw ObjectNotFoundException(path)
        }

        assertFailsWith<ObjectNotFoundException> { executeRoute(call, authenticationContext) }
        assertEquals(HttpStatusCode.OK, recorded.status, "A committed response cannot become a 404")
    }

    @Test
    fun `execute treats an unreadable object as a server error, not a not-found that reveals its path`() = runTest {
        val (call, _) = recordedCall()
        val md = metadata(contentLength = 10L)
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataPermissionEvaluator.isContentAllowed(authenticationContext, md, PermissionAction.VIEW) } returns true
        coEvery { objectService.getPath(md, null) } returns objectPath()
        coEvery { objectService.getInputStream(any()) } throws FileNotFoundException("/data/objects/x (Permission denied)")

        assertFailsWith<FileNotFoundException> { executeRoute(call, authenticationContext) }
    }
    @Test
    fun `supplementary downloads fall back to the metadata MIME type and length`() = runTest {
        for (range in listOf(null, "bytes=0-3")) {
            val (call, recorded) = recordedCall(
                queryParameters = mapOf("id" to testId.toString(), "supplementaryId" to supplementaryId.toString(), "download" to "true"),
                headers = range?.let { mapOf(HttpHeaders.Range to it) } ?: emptyMap(),
            )
            val md = metadata(contentLength = 20L, contentType = "text/plain")
            val supp = supplementary(contentLength = null, contentType = null)
            coEvery { metadataService.getById(testId) } returns md
            coEvery { metadataPermissionEvaluator.isContentAllowed(authenticationContext, md, PermissionAction.VIEW) } returns true
            coEvery { metadataService.getSupplementaryById(supplementaryId) } returns supp
            coEvery { objectService.getPath(md, supplementaryId) } returns objectPath()
            coEvery { objectService.getInputStream(any()) } returns ByteArrayInputStream("data".toByteArray())
            coEvery { objectService.getInputStreamRange(any(), any()) } returns ByteArrayInputStream("data".toByteArray())
            executeRoute(call, authenticationContext)
            assertEquals(ContentType.parse("text/plain"), recorded.contentType)
            assertEquals(if (range == null) "20" else "4", recorded.headers[HttpHeaders.ContentLength])
            assertEquals("attachment; filename=\"supp.pdf\"", recorded.headers[HttpHeaders.ContentDisposition])
            if (range != null) assertEquals("bytes 0-3/20", recorded.headers[HttpHeaders.ContentRange])
        }
    }
}
