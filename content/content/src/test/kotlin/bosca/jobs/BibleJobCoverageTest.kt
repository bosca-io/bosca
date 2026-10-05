@file:OptIn(bosca.core.annotations.Internal::class, bosca.di.annotation.InternalDI::class)

package bosca.jobs

import bosca.bible.Bible
import bosca.bible.BibleFactory
import bosca.bible.BibleIdentification
import bosca.bible.BibleLanguage
import bosca.bible.BibleMetadata
import bosca.bible.BiblePublication
import bosca.bible.BibleSystem
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.FailException
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BibleJobCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val objectStorageService = mockk<ObjectStorageService>()
    private val bibleFactory = mockk<BibleFactory>()
    private val securityService = mockk<SecurityService>()
    private val json = Json { ignoreUnknownKeys = true }

    private val executor = BibleProcessExecutor(
        metadataService,
        objectStorageService,
        bibleFactory,
        securityService,
    )

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { json }
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        ProviderRegistry.clear()
    }

    private fun jobFor(config: BibleProcessJob): Job = InternalJobConstructor(
        definition = json.encodeToJsonElement(BibleProcessJob.serializer(), config),
        executor = BibleProcessExecutor::class,
    )

    private fun metadata(id: UUID, version: Int = 1, languageTag: String = "und") = Metadata(
        id = id,
        version = version,
        name = "web.zip",
        type = MetadataType.STANDARD,
        contentType = "bosca/v-bible",
        contentLength = 12,
        languageTag = languageTag,
        workflowStateId = "pending",
    )

    private fun bible(iso: String = "eng", publicationId: String = "std") = Bible(
        metadata = BibleMetadata(
            identification = BibleIdentification(
                system = BibleSystem("sys-1"),
                name = "Holy Bible",
                nameLocal = "Holy Bible",
                description = "description",
                abbreviation = "HB",
                abbreviationLocal = "HB",
            ),
            publication = BiblePublication(
                id = publicationId,
                name = "Publication",
                nameLocal = "Publication",
                description = "description",
                descriptionLocal = "description",
                abbreviation = "PB",
                abbreviationLocal = "PB",
            ),
            language = BibleLanguage(
                iso = iso,
                name = "English",
                nameLocal = "English",
                script = "Latin",
                scriptCode = "Latn",
                scriptDirection = "ltr",
            ),
        ),
        books = emptyList(),
        styles = emptyList(),
    )

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private suspend fun execute(configuration: BibleProcessJob) {
        val queue = mockk<JobQueue>(relaxed = true)
        withContext(queue.asCoroutineContext(jobFor(configuration))) { executor.execute() }
    }

    @Test
    fun `throws when metadata is missing`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id, 3) } returns null

        val exception = assertFailsWith<FailException> {
            execute(BibleProcessJob(id = id, version = 3))
        }

        assertEquals("Metadata not found: $id", exception.message)
        coVerify(exactly = 0) { objectStorageService.getPath(any<Metadata>(), any()) }
    }

    @Test
    fun `fails an empty DBL bundle and closes its stream`() = runTest {
        val id = UUID.random()
        val metadata = metadata(id)
        val path = mockk<ObjectPath>()
        val stream = TrackingInputStream("usx".toByteArray())
        coEvery { metadataService.getById(id, 1) } returns metadata
        coEvery { objectStorageService.getPath(metadata) } returns path
        coEvery { objectStorageService.getInputStream(path) } returns stream
        coEvery { bibleFactory.getBibles(stream) } returns emptyList()

        val exception = assertFailsWith<FailException> {
            execute(BibleProcessJob(id = id, version = 1))
        }

        assertTrue(exception.message?.contains("No Bibles found") == true)
        assertTrue(stream.closed)
        coVerify(exactly = 0) { metadataService.setBible(any(), any()) }
    }

    @Test
    fun `persists every parsed Bible variant on the runner`() = runTest {
        val id = UUID.random()
        val metadata = metadata(id, version = 2, languageTag = "en")
        val path = mockk<ObjectPath>()
        val stream = TrackingInputStream("usx".toByteArray())
        coEvery { metadataService.getById(id, 2) } returns metadata
        coEvery { objectStorageService.getPath(metadata) } returns path
        coEvery { objectStorageService.getInputStream(path) } returns stream
        coEvery { bibleFactory.getBibles(stream) } returns listOf(bible(), bible(publicationId = "alt"))
        val mergedAttributes = slot<JsonElement>()
        coEvery { metadataService.mergeAttributes(metadata, capture(mergedAttributes)) } returns Unit
        coEvery { metadataService.setBible(metadata, any()) } returns Unit

        execute(BibleProcessJob(id = id, version = 2))

        coVerify(exactly = 2) { metadataService.setBible(metadata, any()) }
        assertEquals("Bible", mergedAttributes.captured.jsonObject["type"]?.jsonPrimitive?.content)
        assertTrue(stream.closed)
    }

    @Test
    fun `initial import derives DBL metadata and publishes as the caller`() = runTest {
        val id = UUID.random()
        val principal = Principal(id = UUID.random())
        val original = metadata(id)
        val initialized = metadata(id, languageTag = "eng")
        val ready = initialized.copy(ready = bosca.serialization.OffsetDateTime.now())
        val path = mockk<ObjectPath>()
        val stream = TrackingInputStream("usx".toByteArray())
        coEvery { metadataService.getById(id, 1) } returnsMany listOf(original, initialized)
        coEvery { objectStorageService.getPath(original) } returns path
        coEvery { objectStorageService.getInputStream(path) } returns stream
        coEvery { bibleFactory.getBibles(stream) } returns listOf(bible())
        val input = slot<MetadataInput>()
        coEvery { metadataService.edit(id, capture(input)) } returns initialized
        coEvery { metadataService.setBible(initialized, any()) } returns Unit
        coEvery { securityService.getPrincipalById(principal.id) } returns principal
        coEvery { securityService.getPrincipalGroups(principal.id) } returns emptyList()
        coEvery { metadataService.setReady(initialized, principal) } returns ready
        coEvery { metadataService.setState(ready, "published", "", principal) } returns ready.copy(workflowStateId = "published")

        execute(
            BibleProcessJob(
                id = id,
                version = 1,
                initializeMetadata = true,
                publish = true,
                principalId = principal.id,
            ),
        )

        assertEquals("eng", input.captured.languageTag)
        assertEquals("bosca/v-bible", input.captured.contentType)
        assertEquals("web.zip", input.captured.name)
        assertEquals("Bible", input.captured.attributes.jsonObject["type"]?.jsonPrimitive?.content)
        coVerify(exactly = 1) { metadataService.setBible(initialized, any()) }
        coVerify(exactly = 1) { metadataService.setReady(initialized, principal) }
        coVerify(exactly = 1) { metadataService.setState(ready, "published", "", principal) }
    }
}
