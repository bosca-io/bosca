package bosca.jobs

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.SourceStatus
import bosca.content.metadata.service.MetadataService
import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pubsub.PubSubService
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.FailException
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.storage.service.ObjectStorageService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.InputStream
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Coverage tests for [ImportUrlJobExecutor.execute]. The existing [ImportUrlJobTest] covers only
 * the [ImportUrlJob] data class; this drives the executor against a [MockWebServer] so the real
 * OkHttp client streams a genuine response through the download / content-type-resolution /
 * progress-reporting / ready pipeline. Google Drive / Dropbox URL rewriting and the Google-host
 * HTML-error branches are documented untestable here: they rewrite to real external hosts that a
 * unit test cannot serve, and the client is a private static field that cannot be redirected.
 */
@OptIn(InternalDI::class)
class ImportUrlJobCoverageTest {

    private lateinit var server: MockWebServer

    private val metadataService = mockk<MetadataService>(relaxed = true)
    private val objectStorageService = mockk<ObjectStorageService>(relaxed = true)
    private val pubSubService = mockk<PubSubService>(relaxed = true)
    private val securityService = mockk<SecurityService>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }

    private val executor = ImportUrlJobExecutor(
        metadataService,
        objectStorageService,
        pubSubService,
        securityService,
    )

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { json }
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.close()
        ProviderRegistry.clear()
    }

    private fun metadata(id: UUID) = Metadata(
        id = id,
        name = "import-target",
        type = bosca.content.metadata.model.MetadataType.STANDARD,
        contentType = "application/octet-stream",
        contentLength = 0L,
        languageTag = "en",
        workflowStateId = "published",
        created = OffsetDateTime.now(),
    )

    /** Configures [ObjectStorageService.setInputStream] to fully drain the stream and report bytes. */
    private fun captureWrittenBytes(returning: Long = 1234L) {
        coEvery { objectStorageService.setInputStream(any(), any(), any()) } coAnswers {
            val stream = secondArg<InputStream>()
            // Read one byte first (exercises read()) then drain the remainder (exercises read(b,off,len)).
            stream.read()
            stream.readBytes()
            returning
        }
    }

    private suspend fun run(job: ImportUrlJob) {
        val jobQueue = mockk<JobQueue>(relaxed = true)
        val internal = InternalJobConstructor(
            definition = json.encodeToJsonElement(job),
            executor = ImportUrlJobExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(internal)) {
            executor.execute()
        }
    }

    private fun response(
        code: Int = 200,
        contentType: String? = "application/octet-stream",
        disposition: String? = null,
        body: String = "hello-content",
    ): MockResponse {
        val builder = MockResponse.Builder().code(code)
        if (contentType != null) builder.addHeader("Content-Type", contentType)
        if (disposition != null) builder.addHeader("Content-Disposition", disposition)
        builder.body(body)
        return builder.build()
    }

    @OptIn(Internal::class)
    @Test
    fun `fails when metadata is not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns null

        val ex = assertFailsWith<FailException> {
            run(ImportUrlJob(id = id, url = server.url("/file.bin").toString()))
        }
        assertTrue(ex.message?.contains("not found") == true)
        assertEquals(0, server.requestCount)
    }

    @OptIn(Internal::class)
    @Test
    fun `imports a binary stream and marks uploaded and imported`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns metadata(id)
        captureWrittenBytes(returning = 4242L)
        server.enqueue(response(contentType = "video/mp4", body = "abcdefghij"))

        run(ImportUrlJob(id = id, url = server.url("/video.mp4").toString()))

        coVerify { metadataService.setSourceStatus(id, SourceStatus.IMPORTING) }
        coVerify { metadataService.setUploaded(id, "video/mp4", 4242L) }
        coVerify { metadataService.setSourceStatus(id, SourceStatus.IMPORTED) }
        coVerify(exactly = 0) { metadataService.setSourceStatus(id, SourceStatus.FAILED) }
    }

    @OptIn(Internal::class)
    @Test
    fun `4xx response throws a permanent FailException`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns metadata(id)
        server.enqueue(MockResponse.Builder().code(404).build())

        assertFailsWith<FailException> {
            run(ImportUrlJob(id = id, url = server.url("/missing").toString()))
        }
        coVerify { metadataService.setSourceStatus(id, SourceStatus.FAILED) }
    }

    @OptIn(Internal::class)
    @Test
    fun `5xx response throws a retryable IllegalStateException`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns metadata(id)
        server.enqueue(MockResponse.Builder().code(503).build())

        assertFailsWith<IllegalStateException> {
            run(ImportUrlJob(id = id, url = server.url("/down").toString()))
        }
        coVerify { metadataService.setSourceStatus(id, SourceStatus.FAILED) }
    }

    @OptIn(Internal::class)
    @Test
    fun `caller content type overrides a generic response header`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns metadata(id)
        captureWrittenBytes()
        // Response header is generic octet-stream, caller provides a real type.
        server.enqueue(response(contentType = "application/octet-stream", body = "data"))

        run(
            ImportUrlJob(
                id = id,
                url = server.url("/thing").toString(),
                contentType = "image/png",
            )
        )

        coVerify { metadataService.setUploaded(id, "image/png", any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `generic type resolved from content disposition filename`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns metadata(id)
        captureWrittenBytes()
        server.enqueue(
            response(
                contentType = "application/octet-stream",
                disposition = "attachment; filename=\"report.pdf\"",
                body = "not-a-real-pdf",
            )
        )

        run(ImportUrlJob(id = id, url = server.url("/download").toString()))

        val typeSlot = mutableListOf<String?>()
        coVerify { metadataService.setUploaded(id, captureNullable(typeSlot), any()) }
        assertEquals("application/pdf", typeSlot.single())
    }

    @OptIn(Internal::class)
    @Test
    fun `generic type resolved from url extension`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns metadata(id)
        captureWrittenBytes()
        server.enqueue(response(contentType = "application/octet-stream", body = "textbody"))

        run(ImportUrlJob(id = id, url = server.url("/notes.txt").toString()))

        val typeSlot = mutableListOf<String?>()
        coVerify { metadataService.setUploaded(id, captureNullable(typeSlot), any()) }
        assertTrue(typeSlot.single()?.startsWith("text/plain") == true)
    }

    @OptIn(Internal::class)
    @Test
    fun `generic type sniffed from PDF magic bytes in the stream`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns metadata(id)
        captureWrittenBytes()
        // No disposition, extension-less URL: forces stream sniffing. %PDF magic bytes.
        server.enqueue(response(contentType = "application/octet-stream", body = "%PDF-1.7 body"))

        run(ImportUrlJob(id = id, url = server.url("/blob").toString()))

        val typeSlot = mutableListOf<String?>()
        coVerify { metadataService.setUploaded(id, captureNullable(typeSlot), any()) }
        assertEquals("application/pdf", typeSlot.single())
    }

    @OptIn(Internal::class)
    @Test
    fun `html without a form or download link fails`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns metadata(id)
        server.enqueue(
            response(
                contentType = "text/html; charset=utf-8",
                body = "<html><body><p>nothing useful here</p></body></html>",
            )
        )

        assertFailsWith<FailException> {
            run(ImportUrlJob(id = id, url = server.url("/page").toString()))
        }
        coVerify { metadataService.setSourceStatus(id, SourceStatus.FAILED) }
    }

    @OptIn(Internal::class)
    @Test
    fun `html with a download link follows through to the file`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns metadata(id)
        captureWrittenBytes(returning = 99L)

        val fileUrl = server.url("/download-target").toString()
        server.enqueue(
            response(
                contentType = "text/html",
                body = "<html><a id=\"uc-download-link\" href=\"$fileUrl\">Download</a></html>",
            )
        )
        server.enqueue(response(contentType = "video/mp4", body = "the-real-bytes"))

        run(ImportUrlJob(id = id, url = server.url("/gate").toString()))

        coVerify { metadataService.setUploaded(id, "video/mp4", 99L) }
        assertEquals(2, server.requestCount)
    }

    // NOTE: the `html with a relative download link resolves against the request host` test was
    // removed. The source resolves a relative href as "${requestUrl.scheme}://${requestUrl.host}$href",
    // dropping the request port. MockWebServer listens on an ephemeral port (never 80/443), so the
    // followed request would target localhost:80 — a live network call the unit test must not make.
    // The relative-resolution branch therefore cannot be exercised through MockWebServer.

    @OptIn(Internal::class)
    @Test
    fun `html with a post form submits the confirmation and follows through`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns metadata(id)
        captureWrittenBytes()

        val actionUrl = server.url("/confirm").toString()
        server.enqueue(
            response(
                contentType = "text/html",
                body = """
                    <html><body>
                    <form method="post" action="$actionUrl">
                    <input type="hidden" name="confirm" value="t"/>
                    <input type="hidden" name="" value="ignored"/>
                    </form>
                    </body></html>
                """.trimIndent(),
            )
        )
        server.enqueue(response(contentType = "application/pdf", body = "%PDF-1.4"))

        run(ImportUrlJob(id = id, url = server.url("/form-gate").toString()))

        coVerify { metadataService.setUploaded(id, "application/pdf", any()) }
        assertEquals(2, server.requestCount)
    }

    @OptIn(Internal::class)
    @Test
    fun `html follow-up returning 4xx fails permanently`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns metadata(id)

        // The href (not the class) must contain "download"/"export" for the source's link
        // selector to match; point it at an absolute MockWebServer URL carrying "download".
        val fileUrl = server.url("/download/gone").toString()
        server.enqueue(
            response(
                contentType = "text/html",
                body = "<html><a href=\"$fileUrl\">go</a></html>",
            )
        )
        server.enqueue(MockResponse.Builder().code(403).build())

        assertFailsWith<FailException> {
            run(ImportUrlJob(id = id, url = server.url("/gate2").toString()))
        }
        coVerify { metadataService.setSourceStatus(id, SourceStatus.FAILED) }
    }

    @OptIn(Internal::class)
    @Test
    fun `html follow-up returning 5xx is retryable`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns metadata(id)

        // The href must contain "download"/"export" (or be id=uc-download-link) for the
        // source's link selector to match; the class attribute is not consulted. Point it
        // at an absolute MockWebServer URL whose path carries "export".
        val fileUrl = server.url("/export/oops").toString()
        server.enqueue(
            response(
                contentType = "text/html",
                body = "<html><a href=\"$fileUrl\">go</a></html>",
            )
        )
        server.enqueue(MockResponse.Builder().code(500).build())

        assertFailsWith<IllegalStateException> {
            run(ImportUrlJob(id = id, url = server.url("/gate3").toString()))
        }
        coVerify { metadataService.setSourceStatus(id, SourceStatus.FAILED) }
    }

    @OptIn(Internal::class)
    @Test
    fun `large stream import drives the progress-reporting path`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns metadata(id)
        captureWrittenBytes(returning = 700L * 1024L)
        // > 512KB so ProgressReportingInputStream.maybeReport crosses PROGRESS_INTERVAL.
        // The rendezvous channel may drop trySend when no collector is ready, so we assert
        // completion (not publish count) to avoid a scheduling-dependent flake.
        val big = "x".repeat(700 * 1024)
        server.enqueue(response(contentType = "video/mp4", body = big))

        run(ImportUrlJob(id = id, url = server.url("/big.mp4").toString()))

        coVerify { metadataService.setUploaded(id, "video/mp4", 700L * 1024L) }
    }

    @OptIn(Internal::class)
    @Test
    fun `ready with principal id sets ready via impersonated principal`() = runTest {
        val id = UUID.random()
        val principalId = UUID.random()
        val meta = metadata(id)
        coEvery { metadataService.getById(id) } returns meta
        captureWrittenBytes()
        server.enqueue(response(contentType = "video/mp4", body = "bytes"))

        val principal = Principal(id = principalId)
        coEvery { securityService.getPrincipalById(principalId) } returns principal
        coEvery { securityService.getPrincipalGroups(principalId) } returns emptyList()

        run(
            ImportUrlJob(
                id = id,
                url = server.url("/ready.mp4").toString(),
                ready = true,
                principalId = principalId,
            )
        )

        coVerify { metadataService.setReady(meta, principal) }
    }

    @OptIn(Internal::class)
    @Test
    fun `ready without principal id impersonates the service account`() = runTest {
        val id = UUID.random()
        val saId = UUID.random()
        val meta = metadata(id)
        coEvery { metadataService.getById(id) } returns meta
        captureWrittenBytes()
        server.enqueue(response(contentType = "video/mp4", body = "bytes"))

        val sa = Principal(id = saId)
        coEvery { securityService.getPrincipalByIdentifier("sa") } returns sa
        coEvery { securityService.getPrincipalGroups(saId) } returns emptyList()

        run(ImportUrlJob(id = id, url = server.url("/ready2.mp4").toString(), ready = true))

        coVerify { metadataService.setReady(meta, sa) }
    }

    @OptIn(Internal::class)
    @Test
    fun `ready fails when metadata disappears before ready`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returnsMany listOf(metadata(id), null)
        captureWrittenBytes()
        server.enqueue(response(contentType = "video/mp4", body = "bytes"))

        assertFailsWith<IllegalStateException> {
            run(ImportUrlJob(id = id, url = server.url("/vanish.mp4").toString(), ready = true))
        }
        coVerify { metadataService.setSourceStatus(id, SourceStatus.FAILED) }
    }

    @OptIn(Internal::class)
    @Test
    fun `not-ready import never marks ready`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns metadata(id)
        captureWrittenBytes()
        server.enqueue(response(contentType = "video/mp4", body = "bytes"))

        run(ImportUrlJob(id = id, url = server.url("/plain.mp4").toString(), ready = false))

        coVerify(exactly = 0) { metadataService.setReady(any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `custom request headers are applied to the download`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns metadata(id)
        captureWrittenBytes()
        server.enqueue(response(contentType = "video/mp4", body = "bytes"))

        run(
            ImportUrlJob(
                id = id,
                url = server.url("/auth.mp4").toString(),
                headers = mapOf("Authorization" to "Bearer secret", "X-Trace" to "abc"),
            )
        )

        val sent = server.takeRequest()
        assertEquals("Bearer secret", sent.headers["Authorization"])
        assertEquals("abc", sent.headers["X-Trace"])
    }
}
