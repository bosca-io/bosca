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
import bosca.slug.model.Slug
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
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
import kotlin.test.assertTrue

class ImageCoverageTest {

    private val slugService = mockk<SlugService>()
    private val metadataService = mockk<MetadataService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val objectService = mockk<ObjectStorageService>()
    private val authenticationContext = mockk<AuthenticationContext>()

    private val route = Image(slugService, metadataService, metadataPermissionEvaluator, objectService)

    private val testId = UUID.parse("00000000-0000-0000-0000-000000000001")
    private val supplementaryId = UUID.parse("00000000-0000-0000-0000-000000000002")

    private fun metadata(etag: String? = null, contentType: String = "image/png") = Metadata(
        id = testId,
        version = 1,
        name = "Test Image",
        type = MetadataType.STANDARD,
        contentType = contentType,
        contentLength = 10L,
        languageTag = "en",
        workflowStateId = "published",
        etag = etag,
    )

    private fun supplementary(contentType: String? = "image/jpeg") = MetadataSupplementary(
        id = supplementaryId,
        metadataId = testId,
        key = "thumbnail",
        name = "Thumbnail",
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        contentType = contentType,
    )

    /** Captures everything the route writes to the mocked [ServerCall]/[ServerResponse]. */
    private class Recorded {
        var status: HttpStatusCode? = null
        var committed = false
        var contentType: ContentType? = null
        val headers = mutableMapOf<String, String>()
        val streamed = ByteArrayOutputStream()
    }

    private fun recordedCall(
        pathParameters: Map<String, String> = mapOf("id" to testId.toString()),
        queryParameters: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): Pair<ServerCall, Recorded> {
        val recorded = Recorded()

        val request = mockk<ServerRequest>(relaxed = true)
        every { request.queryParameters } returns Parameters(queryParameters.mapValues { listOf(it.value) })
        every { request.header(any()) } answers { headers[firstArg()] }

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
        // stubbed — its inlined body runs at the call site and delegates to
        // ServerResponse.respondText, captured above.
        // Downloads stream with no time limit (null); a limited call would not match.
        coEvery { call.respondStreaming(any(), any(), isNull(), any()) } coAnswers {
            recorded.contentType = firstArg()
            recorded.status = secondArg()
            recorded.committed = true
            arg<suspend (StreamingResponse) -> Unit>(3).invoke(streaming)
        }
        every { call.pathParameters } returns Parameters(pathParameters.mapValues { listOf(it.value) })
        return call to recorded
    }

    private fun objectPath() = mockk<ObjectPath>(relaxed = true)

    /**
     * Drives the route's protected `execute(call, auth)` directly (bypassing the DI-heavy public
     * wrapper), supplying a mocked [ConnectionManager] in the coroutine context so the route's
     * `connection().release()` call resolves.
     */
    private suspend fun executeRoute(call: ServerCall, auth: AuthenticationContext) {
        val manager = mockk<ConnectionManager>(relaxed = true)
        val method = Image::class.declaredMemberFunctions
            .first { it.name == "execute" && it.parameters.size == 3 }
        method.isAccessible = true
        try {
            withContext(manager.asCoroutineContext()) {
                method.callSuspend(route, call, auth)
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
    // id resolution
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `throws when id path parameter is missing`() = runTest {
        val (call, _) = recordedCall(pathParameters = emptyMap())

        val ex = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(ex.message?.contains("missing id") == true)
    }

    @Test
    fun `streams primary content when metadata found by uuid id`() = runTest {
        val (call, recorded) = recordedCall()
        val md = metadata()
        coEvery { metadataService.getById(testId) } returns md
        coEvery { objectService.getPath(md, null) } returns objectPath()
        coEvery { objectService.getInputStream(any()) } returns ByteArrayInputStream("png-bytes".toByteArray())

        executeRoute(call, authenticationContext)

        coVerify { metadataPermissionEvaluator.verifyContentAllowed(authenticationContext, md, PermissionAction.VIEW) }
        assertEquals(HttpStatusCode.OK, recorded.status)
        assertEquals("png-bytes", recorded.streamed.toByteArray().decodeToString())
        assertEquals(ContentType.parse("image/png"), recorded.contentType)
    }

    @Test
    fun `strips extension from uuid id before parsing`() = runTest {
        val (call, recorded) = recordedCall(pathParameters = mapOf("id" to "$testId.png"))
        val md = metadata()
        coEvery { metadataService.getById(testId) } returns md
        coEvery { objectService.getPath(md, null) } returns objectPath()
        coEvery { objectService.getInputStream(any()) } returns ByteArrayInputStream("x".toByteArray())

        executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.OK, recorded.status)
    }

    @Test
    fun `throws not found when metadata missing by id`() = runTest {
        val (call, _) = recordedCall()
        coEvery { metadataService.getById(testId) } returns null

        assertFailsWith<NoSuchElementException> {
            executeRoute(call, authenticationContext)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // slug resolution
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `resolves metadata by slug when id is not a uuid`() = runTest {
        val (call, recorded) = recordedCall(pathParameters = mapOf("id" to "my-slug.png"))
        val md = metadata()
        coEvery { slugService.get("my-slug") } returns Slug(slug = "my-slug", metadataId = testId)
        coEvery { metadataService.getById(testId) } returns md
        coEvery { objectService.getPath(md, null) } returns objectPath()
        coEvery { objectService.getInputStream(any()) } returns ByteArrayInputStream("y".toByteArray())

        executeRoute(call, authenticationContext)

        coVerify { slugService.get("my-slug") }
        assertEquals(HttpStatusCode.OK, recorded.status)
    }

    @Test
    fun `throws not found when slug is unknown`() = runTest {
        val (call, _) = recordedCall(pathParameters = mapOf("id" to "missing-slug"))
        coEvery { slugService.get("missing-slug") } returns null

        assertFailsWith<NoSuchElementException> {
            executeRoute(call, authenticationContext)
        }
    }

    @Test
    fun `throws when slug has no metadata id`() = runTest {
        val (call, _) = recordedCall(pathParameters = mapOf("id" to "no-meta-slug"))
        coEvery { slugService.get("no-meta-slug") } returns Slug(slug = "no-meta-slug", metadataId = null)

        val ex = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(ex.message?.contains("missing id") == true)
    }

    @Test
    fun `throws not found with slug in message when slug metadata id does not resolve`() = runTest {
        val (call, _) = recordedCall(pathParameters = mapOf("id" to "dangling-slug"))
        coEvery { slugService.get("dangling-slug") } returns Slug(slug = "dangling-slug", metadataId = testId)
        coEvery { metadataService.getById(testId) } returns null

        val ex = assertFailsWith<NoSuchElementException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(ex.message?.contains("dangling-slug") == true)
    }

    // ---------------------------------------------------------------------------------------------
    // supplementary resolution
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `streams supplementary content by supplementaryId`() = runTest {
        val (call, recorded) = recordedCall(
            queryParameters = mapOf("supplementaryId" to supplementaryId.toString()),
        )
        val md = metadata()
        val supp = supplementary()
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataService.getSupplementaryById(supplementaryId) } returns supp
        coEvery { objectService.getPath(md, supplementaryId) } returns objectPath()
        coEvery { objectService.getInputStream(any()) } returns ByteArrayInputStream("jpg".toByteArray())

        executeRoute(call, authenticationContext)

        coVerify { metadataPermissionEvaluator.verifySupplementaryAllowed(authenticationContext, md, PermissionAction.VIEW) }
        assertEquals(HttpStatusCode.OK, recorded.status)
        // supplementary content type wins over the metadata content type
        assertEquals(ContentType.parse("image/jpeg"), recorded.contentType)
    }

    @Test
    fun `throws not found when supplementary by id is missing`() = runTest {
        val (call, _) = recordedCall(
            queryParameters = mapOf("supplementaryId" to supplementaryId.toString()),
        )
        coEvery { metadataService.getById(testId) } returns metadata()
        coEvery { metadataService.getSupplementaryById(supplementaryId) } returns null

        assertFailsWith<NoSuchElementException> {
            executeRoute(call, authenticationContext)
        }
    }

    @Test
    fun `streams supplementary content by key`() = runTest {
        val (call, recorded) = recordedCall(queryParameters = mapOf("key" to "thumbnail"))
        val md = metadata()
        val supp = supplementary()
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataService.getSupplementaryByMetadataAndKey(testId, "thumbnail") } returns supp
        coEvery { objectService.getPath(md, supplementaryId) } returns objectPath()
        coEvery { objectService.getInputStream(any()) } returns ByteArrayInputStream("jpg".toByteArray())

        executeRoute(call, authenticationContext)

        coVerify { metadataPermissionEvaluator.verifySupplementaryAllowed(authenticationContext, md, PermissionAction.VIEW) }
        assertEquals(HttpStatusCode.OK, recorded.status)
        assertEquals(ContentType.parse("image/jpeg"), recorded.contentType)
    }

    @Test
    fun `streams primary content when key lookup returns null`() = runTest {
        val (call, recorded) = recordedCall(queryParameters = mapOf("key" to "missing-key"))
        val md = metadata()
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataService.getSupplementaryByMetadataAndKey(testId, "missing-key") } returns null
        // supplementary is null, so getPath is called with null and the metadata content type is used
        coEvery { objectService.getPath(md, null) } returns objectPath()
        coEvery { objectService.getInputStream(any()) } returns ByteArrayInputStream("png".toByteArray())

        executeRoute(call, authenticationContext)

        coVerify { metadataPermissionEvaluator.verifyContentAllowed(authenticationContext, md, PermissionAction.VIEW) }
        assertEquals(HttpStatusCode.OK, recorded.status)
        assertEquals(ContentType.parse("image/png"), recorded.contentType)
    }

    // ---------------------------------------------------------------------------------------------
    // ETag / conditional requests
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `sets etag and cache control headers then streams when if-none-match does not match`() = runTest {
        val (call, recorded) = recordedCall(headers = mapOf(HttpHeaders.IfNoneMatch to "\"someothertag\""))
        val md = metadata(etag = "abc123")
        coEvery { metadataService.getById(testId) } returns md
        coEvery { objectService.getPath(md, null) } returns objectPath()
        coEvery { objectService.getInputStream(any()) } returns ByteArrayInputStream("ok".toByteArray())

        executeRoute(call, authenticationContext)

        assertEquals("W/\"abc123\"", recorded.headers[HttpHeaders.ETag])
        assertEquals("no-cache", recorded.headers[HttpHeaders.CacheControl])
        assertEquals(HttpStatusCode.OK, recorded.status)
    }

    @Test
    fun `returns not modified when if-none-match is wildcard`() = runTest {
        val (call, recorded) = recordedCall(headers = mapOf(HttpHeaders.IfNoneMatch to "*"))
        val md = metadata(etag = "abc123")
        coEvery { metadataService.getById(testId) } returns md

        executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.NotModified, recorded.status)
        coVerify(exactly = 0) { objectService.getInputStream(any()) }
    }

    @Test
    fun `returns not modified when weak tag matches`() = runTest {
        val (call, recorded) = recordedCall(headers = mapOf(HttpHeaders.IfNoneMatch to "\"nope\", W/\"abc123\""))
        val md = metadata(etag = "abc123")
        coEvery { metadataService.getById(testId) } returns md

        executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.NotModified, recorded.status)
    }

    @Test
    fun `returns not modified when strong tag matches`() = runTest {
        val (call, recorded) = recordedCall(headers = mapOf(HttpHeaders.IfNoneMatch to "\"abc123\""))
        val md = metadata(etag = "abc123")
        coEvery { metadataService.getById(testId) } returns md

        executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.NotModified, recorded.status)
    }

    @Test
    fun `streams when etag present but if-none-match header absent`() = runTest {
        val (call, recorded) = recordedCall()
        val md = metadata(etag = "abc123")
        coEvery { metadataService.getById(testId) } returns md
        coEvery { objectService.getPath(md, null) } returns objectPath()
        coEvery { objectService.getInputStream(any()) } returns ByteArrayInputStream("ok".toByteArray())

        executeRoute(call, authenticationContext)

        assertEquals("W/\"abc123\"", recorded.headers[HttpHeaders.ETag])
        assertEquals(HttpStatusCode.OK, recorded.status)
    }

    // ---------------------------------------------------------------------------------------------
    // Missing and unreadable objects
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `responds not found without the object's headers when the object is missing`() = runTest {
        val (call, recorded) = recordedCall()
        val md = metadata(etag = "abc123")
        val path = objectPath()
        coEvery { metadataService.getById(testId) } returns md
        coEvery { objectService.getPath(md, null) } returns path
        coEvery { objectService.getInputStream(any()) } throws ObjectNotFoundException(path)

        executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.NotFound, recorded.status)
        assertEquals(null, recorded.headers[HttpHeaders.ETag], "A 404 must not carry the missing object's tag")
    }

    @Test
    fun `an unreadable object is a server error, not a not-found that reveals its path`() = runTest {
        val (call, _) = recordedCall()
        val md = metadata()
        coEvery { metadataService.getById(testId) } returns md
        coEvery { objectService.getPath(md, null) } returns objectPath()
        coEvery { objectService.getInputStream(any()) } throws FileNotFoundException("/data/objects/x (Permission denied)")

        assertFailsWith<FileNotFoundException> { executeRoute(call, authenticationContext) }
    }

    @Test
    fun `supplementary images without a MIME type use the metadata MIME type`() = runTest {
        val (call, recorded) = recordedCall(queryParameters = mapOf("supplementaryId" to supplementaryId.toString()))
        val md = metadata()
        coEvery { metadataService.getById(testId) } returns md
        coEvery { metadataService.getSupplementaryById(supplementaryId) } returns supplementary(contentType = null)
        coEvery { objectService.getPath(md, supplementaryId) } returns objectPath()
        coEvery { objectService.getInputStream(any()) } returns ByteArrayInputStream("image".toByteArray())
        executeRoute(call, authenticationContext)
        assertEquals(ContentType.parse("image/png"), recorded.contentType)
    }

    @Test
    fun `an object disappearing during image streaming fails the committed response`() = runTest {
        val (call, recorded) = recordedCall()
        val md = metadata()
        val path = objectPath()
        coEvery { metadataService.getById(testId) } returns md
        coEvery { objectService.getPath(md, null) } returns path
        coEvery { objectService.getInputStream(path) } returns object : InputStream() {
            override fun read(): Int = throw ObjectNotFoundException(path)
        }
        assertFailsWith<ObjectNotFoundException> { executeRoute(call, authenticationContext) }
        assertTrue(recorded.committed)
        assertEquals(HttpStatusCode.OK, recorded.status)
    }
}
