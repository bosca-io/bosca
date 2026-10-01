package bosca.content.metadata.routes

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionType
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.jobs.BibleProcessJob
import bosca.jobs.enqueue
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import bosca.server.content.MultiPartData
import bosca.server.content.PartData
import bosca.sharedqueue.jobs.Job
import bosca.slug.model.Slug
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AddBibleCoverageTest {

    private val application = mockk<BoscaApplication>(relaxed = true)
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val slugService = mockk<SlugService>()
    private val collectionService = mockk<CollectionService>()
    private val metadataService = mockk<MetadataService>()
    private val objectService = mockk<ObjectStorageService>()
    private val connectionManager = mockk<ConnectionManager>(relaxed = true)

    private val route = AddBible(
        application,
        groups,
        slugService,
        collectionService,
        metadataService,
        objectService,
    )

    private val parentSlugId = UUID.parse("00000000-0000-0000-0000-000000000010")
    private val metadataId = UUID.parse("00000000-0000-0000-0000-000000000020")
    private val principal = Principal(id = UUID.parse("00000000-0000-0000-0000-000000000030"))

    private val biblesCollection = Collection(
        id = parentSlugId,
        name = "Bibles",
        languageTag = "en",
        type = CollectionType.STANDARD,
        workflowStateId = "published",
    )

    private val metadata = Metadata(
        id = metadataId,
        version = 1,
        name = "Test Bible",
        type = MetadataType.STANDARD,
        contentType = "bosca/v-bible",
        contentLength = 0,
        languageTag = "und",
        workflowStateId = "pending",
    )

    private val objectPath = mockk<ObjectPath>()

    init {
        every { application.log } returns LoggerFactory.getLogger("test.AddBible")
    }

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.jobs.BibleProcessExecutorExecutorKt")
        coEvery { any<BibleProcessJob>().enqueue() } returns mockk<Job>()
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    private fun fileItem(bytes: ByteArray, originalFileName: String?): PartData.FileUploadItem {
        val part = mockk<PartData.FileUploadItem>(relaxed = true)
        every { part.name } returns "file-upload"
        every { part.originalFileName } returns originalFileName
        every { part.streamProvider } returns ByteArrayInputStream(bytes)
        return part
    }

    private fun call(parts: List<PartData>): ServerCall {
        val call = mockk<ServerCall>(relaxed = true)
        coEvery { call.receiveMultipart() } returns MultiPartData(parts)
        return call
    }

    private fun authContext(withPrincipal: Boolean): AuthenticationContext {
        val auth = mockk<AuthenticationContext>()
        if (withPrincipal) {
            val authenticated = mockk<AuthenticatedPrincipal>()
            every { auth.principal() } returns authenticated
            every { authenticated.id } returns principal.id
        } else {
            every { auth.principal() } returns null
        }
        return auth
    }

    private fun stubServicesForSuccess(uploadedSize: Long = 12L) {
        coEvery { slugService.get("bibles") } returns Slug(slug = "bibles", collectionId = parentSlugId)
        coEvery { collectionService.getById(parentSlugId) } returns biblesCollection
        coEvery { metadataService.add(any(), any(), any()) } returns metadata
        coEvery { objectService.getPath(metadata, null) } returns objectPath
        coEvery { objectService.setInputStream(objectPath, any(), any()) } returns uploadedSize
        coEvery { metadataService.setUploaded(metadataId, "bosca/v-bible", uploadedSize) } returns Unit
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeRoute(call: ServerCall, auth: AuthenticationContext): HttpStatusCode {
        val method = AddBible::class.declaredMemberFunctions
            .first { it.name == "execute" && it.parameters.size == 3 }
        method.isAccessible = true
        return withContext(connectionManager.asCoroutineContext()) {
            try {
                method.callSuspend(route, call, auth) as HttpStatusCode
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.cause ?: e
            }
        }
    }

    @Test
    fun `stores the bundle and queues runner processing`() = runTest {
        stubServicesForSuccess()
        val part = fileItem("bible-bytes".toByteArray(), "web.zip")
        val auth = authContext(withPrincipal = true)

        val result = executeRoute(call(listOf(part)), auth)

        assertEquals(HttpStatusCode.Created, result)
        coVerify { groups.verifyHasAdminGroup(auth) }
        val input = slot<MetadataInput>()
        coVerify { metadataService.add(biblesCollection, null, capture(input)) }
        assertEquals("web.zip", input.captured.name)
        assertEquals("und", input.captured.languageTag)
        assertEquals("bosca/v-bible", input.captured.contentType)
        coVerify { objectService.setInputStream(objectPath, any(), any()) }
        coVerify { metadataService.setUploaded(metadataId, "bosca/v-bible", 12L) }

        val job = slot<BibleProcessJob>()
        coVerify { capture(job).enqueue() }
        assertEquals(metadataId, job.captured.id)
        assertEquals(1, job.captured.version)
        assertTrue(job.captured.initializeMetadata)
        assertTrue(job.captured.publish)
        assertEquals(principal.id, job.captured.principalId)
        coVerify(exactly = 0) { metadataService.setBible(any(), any()) }
    }

    @Test
    fun `uses empty name when the original file name is null`() = runTest {
        stubServicesForSuccess(uploadedSize = 4L)
        val input = slot<MetadataInput>()

        val result = executeRoute(call(listOf(fileItem("data".toByteArray(), null))), authContext(true))

        assertEquals(HttpStatusCode.Created, result)
        coVerify { metadataService.add(any(), any(), capture(input)) }
        assertEquals("", input.captured.name)
    }

    @Test
    fun `ignores non file parts`() = runTest {
        val part = mockk<PartData.FormItem>(relaxed = true)
        val result = executeRoute(call(listOf(part)), authContext(true))

        assertEquals(HttpStatusCode.Created, result)
        coVerify(exactly = 0) { metadataService.add(any(), any(), any()) }
        coVerify(exactly = 0) { any<BibleProcessJob>().enqueue() }
    }

    @Test
    fun `returns Created without processing when there are no parts`() = runTest {
        val result = executeRoute(call(emptyList()), authContext(true))

        assertEquals(HttpStatusCode.Created, result)
        coVerify(exactly = 0) { metadataService.add(any(), any(), any()) }
        coVerify(exactly = 0) { any<BibleProcessJob>().enqueue() }
    }

    @Test
    fun `throws when the caller lacks the admin group`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws IllegalStateException("unauthorized")
        val auth = authContext(true)

        assertFailsWith<IllegalStateException> {
            executeRoute(call(listOf(fileItem("data".toByteArray(), "x.zip"))), auth)
        }
        coVerify(exactly = 0) { metadataService.add(any(), any(), any()) }
    }

    @Test
    fun `errors when no parent slug is found`() = runTest {
        coEvery { slugService.get("bibles") } returns null

        val exception = assertFailsWith<IllegalStateException> {
            executeRoute(call(listOf(fileItem("data".toByteArray(), "x.zip"))), authContext(true))
        }
        assertTrue(exception.message?.contains("No parent slug found") == true)
    }

    @Test
    fun `errors when the principal is missing`() = runTest {
        val exception = assertFailsWith<IllegalStateException> {
            executeRoute(call(listOf(fileItem("data".toByteArray(), "x.zip"))), authContext(false))
        }
        assertTrue(exception.message?.contains("missing principal") == true)
        coVerify(exactly = 0) { metadataService.add(any(), any(), any()) }
    }
}
