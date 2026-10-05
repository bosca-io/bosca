package bosca.content.metadata.routes

import bosca.content.metadata.events.METADATA_UPLOAD_PROGRESS_CHANNEL
import bosca.content.metadata.events.UploadProgress
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.pubsub.PubSubService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.content.MultiPartData
import bosca.server.content.PartData
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.slf4j.Logger
import java.io.ByteArrayInputStream
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UploadCoverageTest {

    private val application = mockk<BoscaApplication>(relaxed = true)
    private val metadataService = mockk<MetadataService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val objectService = mockk<ObjectStorageService>()
    private val pubSubService = mockk<PubSubService>(relaxed = true)
    private val authenticationContext = mockk<AuthenticationContext>()

    private val route = Upload(
        application,
        metadataService,
        metadataPermissionEvaluator,
        objectService,
        pubSubService,
    )

    private val testId = UUID.parse("00000000-0000-0000-0000-000000000001")

    private val testMetadata = Metadata(
        id = testId,
        version = 1,
        name = "Test",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = 0,
        languageTag = "en",
        workflowStateId = "published",
    )

    /**
     * [PartData] is a `sealed class` declared in the `bosca-core` module, so it cannot be
     * subclassed from this test module. Instead mock the concrete [PartData.FileUploadItem]
     * (which satisfies the route's `p is PartData.FileItem` check) and stub the interface
     * members the route actually reads: [PartData.FileItem.contentType] and
     * [PartData.FileItem.streamProvider]. A relaxed mock supplies `name`, `dispose()`, etc.
     */
    private class FilePart(
        val mock: PartData.FileUploadItem,
        val disposed: () -> Boolean,
    )

    private fun fileItem(
        contentType: String? = "application/octet-stream",
        content: ByteArray = "hello world".toByteArray(),
    ): FilePart {
        val item = mockk<PartData.FileUploadItem>(relaxed = true)
        var disposed = false
        every { item.contentType } returns contentType
        every { item.streamProvider } answers { ByteArrayInputStream(content) }
        every { item.dispose() } answers { disposed = true }
        return FilePart(item) { disposed }
    }

    private fun formItem(): PartData.FormItem {
        val item = mockk<PartData.FormItem>(relaxed = true)
        // A FormItem is NOT a FileItem, so the route's `p is PartData.FileItem` branch is false.
        return item
    }

    private fun createCall(
        queryParams: Map<String, String> = mapOf("id" to testId.toString()),
        parts: List<PartData> = emptyList(),
    ): ServerCall {
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.queryParameters } returns Parameters(queryParams.mapValues { listOf(it.value) })
        val call = mockk<ServerCall>(relaxed = true)
        every { call.request } returns request
        coEvery { call.receiveMultipart() } returns MultiPartData(parts)
        return call
    }

    private fun objectPath() = mockk<ObjectPath>(relaxed = true)

    private suspend fun executeRoute(call: ServerCall, auth: AuthenticationContext): HttpStatusCode {
        val method = Upload::class.declaredMemberFunctions
            .first { it.name == "execute" && it.parameters.size == 3 }
        method.isAccessible = true
        try {
            return method.callSuspend(route, call, auth) as HttpStatusCode
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
    fun `throws when id query parameter is missing`() = runTest {
        val call = createCall(queryParams = emptyMap())

        val ex = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(ex.message?.contains("missing id") == true)
    }

    @Test
    fun `throws when metadata not found`() = runTest {
        val call = createCall()
        coEvery { metadataService.getById(testId) } returns null

        val ex = assertFailsWith<IllegalStateException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(ex.message?.contains("Metadata not found") == true)
    }

    @Test
    fun `uploads file part and returns Created`() = runTest {
        val part = fileItem(contentType = "application/octet-stream")
        val call = createCall(parts = listOf(part.mock))
        coEvery { metadataService.getById(testId) } returns testMetadata
        coEvery { objectService.getPath(testMetadata, null) } returns objectPath()
        coEvery { objectService.setInputStream(any(), any(), any()) } returns 11L
        coEvery { metadataService.setUploaded(any(), any(), any()) } returns Unit

        val result = executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.Created, result)
        coVerify {
            metadataPermissionEvaluator.verifyContentAllowed(authenticationContext, testMetadata, PermissionAction.EDIT)
        }
        // p.contentType ("application/octet-stream") is used when present.
        coVerify { metadataService.setUploaded(testId, "application/octet-stream", 11L) }
        assertTrue(part.disposed(), "the file part must be disposed in the finally block")
    }

    @Test
    fun `falls back to metadata content type when part content type is null`() = runTest {
        val part = fileItem(contentType = null)
        val call = createCall(parts = listOf(part.mock))
        coEvery { metadataService.getById(testId) } returns testMetadata
        coEvery { objectService.getPath(testMetadata, null) } returns objectPath()
        coEvery { objectService.setInputStream(any(), any(), any()) } returns 5L
        coEvery { metadataService.setUploaded(any(), any(), any()) } returns Unit

        val result = executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.Created, result)
        // Elvis fallback: metadata.contentType ("text/plain") is used when the part has none.
        coVerify { metadataService.setUploaded(testId, "text/plain", 5L) }
    }

    @Test
    fun `publishes upload progress with the final byte count`() = runTest {
        val part = fileItem(content = "hello world".toByteArray())
        val call = createCall(parts = listOf(part.mock))
        coEvery { metadataService.getById(testId) } returns testMetadata
        coEvery { objectService.getPath(testMetadata, null) } returns objectPath()
        coEvery { objectService.setInputStream(any(), any(), any()) } returns 42L
        coEvery { metadataService.setUploaded(any(), any(), any()) } returns Unit

        executeRoute(call, authenticationContext)

        // The upload extension invokes onProgress once with the final size (42), which the route
        // maps to an UploadProgress published on the progress channel.
        val progressSlot = slot<UploadProgress>()
        coVerify {
            pubSubService.publish(
                METADATA_UPLOAD_PROGRESS_CHANNEL,
                any(),
                capture(progressSlot),
            )
        }
        assertEquals(testId, progressSlot.captured.metadataId)
        assertEquals(42L, progressSlot.captured.bytesUploaded)
        assertEquals(0L, progressSlot.captured.totalBytes)
    }

    @Test
    fun `ignores non-file parts and does not upload`() = runTest {
        val form = formItem()
        val call = createCall(parts = listOf(form))
        coEvery { metadataService.getById(testId) } returns testMetadata

        val result = executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.Created, result)
        // No file item, so nothing is uploaded and no upload is recorded.
        coVerify(exactly = 0) { objectService.getPath(any<Metadata>(), any()) }
        coVerify(exactly = 0) { metadataService.setUploaded(any(), any(), any()) }
        // Every part is still disposed in the finally block.
        verify { form.dispose() }
    }

    @Test
    fun `disposes file part even when upload fails`() = runTest {
        val part = fileItem()
        val call = createCall(parts = listOf(part.mock))
        coEvery { metadataService.getById(testId) } returns testMetadata
        coEvery { objectService.getPath(testMetadata, null) } returns objectPath()
        coEvery { objectService.setInputStream(any(), any(), any()) } throws RuntimeException("boom")

        assertFailsWith<RuntimeException> {
            executeRoute(call, authenticationContext)
        }
        assertTrue(part.disposed(), "the finally block must dispose the part on failure")
    }

    @Test
    fun `processes multiple file parts`() = runTest {
        val first = fileItem(contentType = "text/a")
        val second = fileItem(contentType = "text/b")
        val call = createCall(parts = listOf(first.mock, second.mock))
        coEvery { metadataService.getById(testId) } returns testMetadata
        coEvery { objectService.getPath(testMetadata, null) } returns objectPath()
        coEvery { objectService.setInputStream(any(), any(), any()) } returns 3L
        coEvery { metadataService.setUploaded(any(), any(), any()) } returns Unit

        val result = executeRoute(call, authenticationContext)

        assertEquals(HttpStatusCode.Created, result)
        coVerify { metadataService.setUploaded(testId, "text/a", 3L) }
        coVerify { metadataService.setUploaded(testId, "text/b", 3L) }
        assertTrue(first.disposed() && second.disposed())
    }

    @Test
    fun `logs via application logger`() = runTest {
        val logger = mockk<Logger>(relaxed = true)
        every { application.log } returns logger
        val part = fileItem(contentType = "text/plain")
        val call = createCall(parts = listOf(part.mock))
        coEvery { metadataService.getById(testId) } returns testMetadata
        coEvery { objectService.getPath(testMetadata, null) } returns objectPath()
        coEvery { objectService.setInputStream(any(), any(), any()) } returns 1L
        coEvery { metadataService.setUploaded(any(), any(), any()) } returns Unit

        executeRoute(call, authenticationContext)

        verify { logger.info(match<String> { it.contains("Including part") }) }
        verify { logger.info("uploading metadata") }
        verify { logger.info("metadata uploaded") }
    }
}
