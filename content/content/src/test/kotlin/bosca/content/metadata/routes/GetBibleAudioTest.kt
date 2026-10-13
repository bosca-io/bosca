package bosca.content.metadata.routes

import bosca.bible.BibleAudioContent
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.BibleAudioPlayback
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.test.*
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible

class GetBibleAudioTest {
    private val metadata = mockk<MetadataService>()
    private val permissions = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val bibles = mockk<BibleService>()
    private val auth = mockk<AuthenticationContext>()
    private val route = GetBibleAudio(metadata, permissions, bibles)
    private val id = UUID.random()
    private val audioId = UUID.random()
    private val item = Metadata(id = id, name = "Bible", type = bosca.content.metadata.model.MetadataType.STANDARD, contentType = "bosca/v-bible", contentLength = 0, languageTag = "eng", workflowStateId = "published")
    private val bible = Bible(metadataId = id, version = 2, systemId = "publication", variant = "study", defaultVariant = true, name = "Bible", nameLocal = "Bible", description = "Description", abbreviation = "B", abbreviationLocal = "B", styles = JsonNull)
    private val bytes = "recording bytes".toByteArray()
    private val playback = mockk<BibleAudioPlayback>()

    @BeforeTest
    fun setup() {
        coEvery { metadata.getById(id) } returns item
        coEvery { bibles.getBible(id, 2, "study") } returns bible
        coEvery { bibles.getAudioPlayback(bible, audioId, "GEN.1") } returns playback
        every { playback.content } returns BibleAudioContent("audio/mpeg", bytes.size.toLong())
        coEvery { playback.openStream(any()) } coAnswers {
            val range = arg<LongRange?>(0)
            ByteArrayInputStream(if (range == null) bytes else bytes.copyOfRange(range.first.toInt(), range.last.toInt() + 1))
        }
    }

    private class Result {
        val headers = mutableMapOf<String, String>()
        var status: HttpStatusCode? = null
        val body = ByteArrayOutputStream()
    }

    private suspend fun execute(range: String? = null, recordingId: UUID? = null): Result {
        val result = Result()
        val request = mockk<ServerRequest>()
        every { request.queryParameters } returns Parameters(buildMap {
            put("id", listOf(id.toString()))
            put("version", listOf("2"))
            put("variant", listOf("study"))
            put("audioId", listOf(audioId.toString()))
            put("usfm", listOf("GEN.1"))
            recordingId?.let { put("recordingId", listOf(it.toString())) }
        })
        every { request.header(HttpHeaders.Range) } returns range
        val response = mockk<ServerResponse>(relaxed = true)
        every { response.header(any(), any()) } answers { result.headers[firstArg()] = secondArg() }
        val stream = mockk<StreamingResponse>()
        coEvery { stream.copyFrom(any(), any()) } coAnswers { firstArg<InputStream>().copyTo(result.body) }
        val call = mockk<ServerCall>()
        every { call.request } returns request
        every { call.response } returns response
        every { call.respond(any<HttpStatusCode>()) } answers { result.status = firstArg() }
        coEvery { call.respondStreaming(any(), any(), isNull(), any()) } coAnswers {
            result.status = secondArg()
            arg<suspend (StreamingResponse) -> Unit>(3).invoke(stream)
        }
        val method = GetBibleAudio::class.declaredMemberFunctions.first { it.name == "execute" && it.parameters.size == 3 }
        method.isAccessible = true
        try {
            withContext(mockk<ConnectionManager>(relaxed = true).asCoroutineContext()) { method.callSuspend(route, call, auth) }
        } catch (error: java.lang.reflect.InvocationTargetException) { throw error.cause ?: error }
        return result
    }

    @Test
    fun `serves full and ranged audio through content authorization`() = runTest {
        val full = execute()
        assertEquals(HttpStatusCode.OK, full.status)
        assertEquals(bytes.toList(), full.body.toByteArray().toList())
        assertEquals("private, no-store", full.headers[HttpHeaders.CacheControl])
        assertEquals("bytes", full.headers[HttpHeaders.AcceptRanges])
        assertEquals(bytes.size.toString(), full.headers[HttpHeaders.ContentLength])
        val partial = execute("bytes=2-5")
        assertEquals(HttpStatusCode.PartialContent, partial.status)
        assertEquals(bytes.slice(2..5), partial.body.toByteArray().toList())
        assertEquals("bytes 2-5/${bytes.size}", partial.headers[HttpHeaders.ContentRange])
        assertEquals("4", partial.headers[HttpHeaders.ContentLength])
        coVerify(exactly = 2) { permissions.verifyContentAllowed(auth, item, PermissionAction.VIEW) }
        coVerify(exactly = 2) { bibles.getAudioPlayback(bible, audioId, "GEN.1") }
        coVerify(exactly = 1) { playback.openStream(null) }
        coVerify(exactly = 1) { playback.openStream(2L..5L) }
    }

    @Test
    fun `rejects unsatisfiable ranges before opening media`() = runTest {
        for (range in listOf("bytes=100-", "bytes=3-1", "invalid", "bytes=-0")) {
            val result = execute(range)
            assertEquals(416, result.status?.value)
            assertEquals("bytes */${bytes.size}", result.headers[HttpHeaders.ContentRange])
            assertNull(result.headers[HttpHeaders.ContentLength])
        }
        coVerify(exactly = 0) { playback.openStream(any()) }
    }

    @Test
    fun `deleted or missing metadata and unavailable variants return not found without fetching audio`() = runTest {
        coEvery { metadata.getById(id) } returns item.copy(deleted = true)
        assertEquals(HttpStatusCode.NotFound, execute().status)
        coEvery { metadata.getById(id) } returns null
        assertEquals(HttpStatusCode.NotFound, execute().status)
        coEvery { metadata.getById(id) } returns item
        coEvery { bibles.getBible(id, 2, "study") } returns null
        assertEquals(HttpStatusCode.NotFound, execute().status)
        coVerify(exactly = 0) { bibles.getAudioPlayback(any(), any(), any(), any()) }
    }

    @Test
    fun `content denial and cancellation propagate before provider access`() = runTest {
        coEvery { permissions.verifyContentAllowed(auth, item, PermissionAction.VIEW) } throws IllegalStateException("denied")
        assertFailsWith<IllegalStateException> { execute() }
        coEvery { permissions.verifyContentAllowed(auth, item, PermissionAction.VIEW) } throws kotlinx.coroutines.CancellationException("cancelled")
        assertFailsWith<kotlinx.coroutines.CancellationException> { execute() }
        coVerify(exactly = 0) { bibles.getAudioPlayback(any(), any(), any(), any()) }
    }

    @Test
    fun `missing media returns not found without stale length headers`() = runTest {
        coEvery { playback.openStream(any()) } throws bosca.storage.service.ObjectNotFoundException(bosca.storage.service.StringObjectPath("missing"))
        val result = execute()
        assertEquals(HttpStatusCode.NotFound, result.status)
        assertNull(result.headers[HttpHeaders.ContentLength])
    }

    @Test
    fun `recording identifiers select the described file for range requests`() = runTest {
        val recordingId = UUID.random()
        coEvery { bibles.getAudioPlayback(bible, audioId, "GEN.1", recordingId) } returns playback
        val result = execute("bytes=2-5", recordingId)
        assertEquals(HttpStatusCode.PartialContent, result.status)
        assertEquals(bytes.slice(2..5), result.body.toByteArray().toList())
        coVerify(exactly = 1) { bibles.getAudioPlayback(bible, audioId, "GEN.1", recordingId) }
        coVerify(exactly = 0) { bibles.getAudioPlayback(bible, audioId, "GEN.1", null) }
    }

    @Test
    fun `unavailable recording identifiers return not found without falling back to current audio`() = runTest {
        val recordingId = UUID.random()
        coEvery { bibles.getAudioPlayback(bible, audioId, "GEN.1", recordingId) } throws
            bosca.storage.service.ObjectNotFoundException(bosca.storage.service.StringObjectPath("missing"))
        val result = execute(recordingId = recordingId)
        assertEquals(HttpStatusCode.NotFound, result.status)
        assertNull(result.headers[HttpHeaders.ContentLength])
        coVerify(exactly = 0) { bibles.getAudioPlayback(bible, audioId, "GEN.1", null); playback.openStream(any()) }
    }

    @Test
    fun `media failures after response headers propagate and close the stream`() = runTest {
        val failure = bosca.storage.service.ObjectNotFoundException(bosca.storage.service.StringObjectPath("missing"))
        var closed = false
        coEvery { playback.openStream(any()) } returns object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw failure
            override fun close() { closed = true; super.close() }
        }
        assertSame(failure, assertFailsWith<bosca.storage.service.ObjectNotFoundException> { execute() })
        assertTrue(closed)
    }
}
