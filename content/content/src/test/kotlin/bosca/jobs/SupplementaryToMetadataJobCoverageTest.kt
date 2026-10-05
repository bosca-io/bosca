@file:OptIn(bosca.di.annotation.InternalDI::class, bosca.core.annotations.Internal::class)

package bosca.jobs

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.model.MetadataRelationshipInput
import bosca.content.metadata.model.MetadataSupplementary
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers [SupplementaryToMetadataJobExecutor.execute]: resolving the supplementary id
 * (from the job definition and, when absent, from the parent job's context), downloading
 * the supplementary content, creating a new metadata entry, uploading it, optionally
 * wiring a relationship, and writing the result back into the job context — plus every
 * error arm (missing supplementary id, missing supplementary, missing metadata).
 */
class SupplementaryToMetadataJobCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val objectStorageService = mockk<ObjectStorageService>()
    private val slugService = mockk<SlugService>()
    private val queue = mockk<JobQueue>()
    private val json = Json { ignoreUnknownKeys = true }

    private val executor = SupplementaryToMetadataJobExecutor(metadataService, objectStorageService, slugService)

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        coEvery { queue.setJob(any()) } just Runs
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
        clearAllMocks()
        ProviderRegistry.clear()
    }

    private fun jobFor(config: SupplementaryToMetadataJob): Job = InternalJobConstructor(
        definition = json.encodeToJsonElement(SupplementaryToMetadataJob.serializer(), config),
        executor = SupplementaryToMetadataJobExecutor::class,
    )

    private fun supplementary(
        metadataId: UUID,
        contentType: String? = "audio/mpeg",
        key: String = "audio",
        name: String = "Narration",
    ) = MetadataSupplementary(
        metadataId = metadataId,
        key = key,
        name = name,
        contentType = contentType,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
    )

    private fun sourceMetadata(id: UUID): Metadata {
        val metadata = mockk<Metadata>()
        every { metadata.id } returns id
        every { metadata.languageTag } returns "en"
        every { metadata.type } returns MetadataType.STANDARD
        return metadata
    }

    private fun createdMetadata(id: UUID, version: Int = 1, contentType: String = "audio/mpeg"): Metadata {
        val metadata = mockk<Metadata>()
        every { metadata.id } returns id
        every { metadata.version } returns version
        every { metadata.contentType } returns contentType
        return metadata
    }

    private fun stubStorage(source: Metadata, supplementaryId: UUID, created: Metadata, bytes: ByteArray = "data".toByteArray()) {
        val downloadPath = mockk<ObjectPath>()
        val uploadPath = mockk<ObjectPath>()
        coEvery { objectStorageService.getPath(source, supplementaryId) } returns downloadPath
        coEvery { objectStorageService.getInputStream(downloadPath) } returns ByteArrayInputStream(bytes)
        coEvery { objectStorageService.getPath(created, null) } returns uploadPath
        coEvery { objectStorageService.setInputStream(uploadPath, any<InputStream>(), any()) } returns bytes.size.toLong()
    }

    @Test
    fun `uses supplementaryId from job, wires relationship and sets context`() = runTest {
        val supplementaryId = UUID.random()
        val sourceId = UUID.random()
        val newId = UUID.random()
        val source = sourceMetadata(sourceId)
        val created = createdMetadata(newId, version = 7, contentType = "audio/mpeg")

        coEvery { metadataService.getSupplementaryById(supplementaryId) } returns supplementary(sourceId)
        coEvery { metadataService.getById(sourceId) } returns source
        coEvery { slugService.getMetadataSlug(sourceId) } returns "narration-slug"
        val inputSlot = slot<MetadataInput>()
        coEvery { metadataService.add(null, null, capture(inputSlot)) } returns created
        stubStorage(source, supplementaryId, created)
        coEvery { metadataService.setUploaded(newId, "audio/mpeg", any()) } just Runs
        val relationshipSlot = slot<MetadataRelationshipInput>()
        coEvery { metadataService.addRelationship(capture(relationshipSlot)) } returns mockk(relaxed = true)

        val job = jobFor(SupplementaryToMetadataJob(supplementaryId = supplementaryId, relationship = "audio"))
        withContext(queue.asCoroutineContext(job)) {
            executor.execute()
        }

        // supplementary content type + slug composition
        assertEquals("audio/mpeg", inputSlot.captured.contentType)
        assertEquals("en", inputSlot.captured.languageTag)
        assertEquals(MetadataType.STANDARD, inputSlot.captured.metadataType)
        assertEquals("narration-slug-audio", inputSlot.captured.slug)
        assertEquals("Narration", inputSlot.captured.name)

        // relationship wired between source and new metadata
        assertEquals(sourceId, relationshipSlot.captured.id1)
        assertEquals(newId, relationshipSlot.captured.id2)
        assertEquals("audio", relationshipSlot.captured.relationship)

        // context persisted back onto the job
        coVerify(exactly = 1) { queue.setJob(job) }
        val context = job.getContext() as JsonObject
        assertEquals(JsonPrimitive(newId.toString()), context["id"])
        assertEquals(JsonPrimitive("7"), context["version"])
    }

    @Test
    fun `resolves supplementaryId from parent context, defaults content type and skips relationship`() = runTest {
        val supplementaryId = UUID.random()
        val parentId = UUID.random()
        val sourceId = UUID.random()
        val newId = UUID.random()
        val source = sourceMetadata(sourceId)
        val created = createdMetadata(newId, contentType = "application/octet-stream")

        coEvery { metadataService.getSupplementaryById(supplementaryId) } returns supplementary(sourceId, contentType = null)
        coEvery { metadataService.getById(sourceId) } returns source
        coEvery { slugService.getMetadataSlug(sourceId) } returns "slug"
        val inputSlot = slot<MetadataInput>()
        coEvery { metadataService.add(null, null, capture(inputSlot)) } returns created
        stubStorage(source, supplementaryId, created)
        coEvery { metadataService.setUploaded(newId, "application/octet-stream", any()) } just Runs

        val parent = jobFor(SupplementaryToMetadataJob())
        parent.setContext(JsonObject(mapOf("supplementaryId" to JsonPrimitive(supplementaryId.toString()))))
        coEvery { queue.getJob<Job?>(parentId, any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (it.invocation.args[1] as suspend (Job?) -> Job?).invoke(parent)
        }

        val job = jobFor(SupplementaryToMetadataJob(relationship = null))
        job.setParent(parentId)
        withContext(queue.asCoroutineContext(job)) {
            executor.execute()
        }

        // default content type applied when supplementary content type is null
        assertEquals("application/octet-stream", inputSlot.captured.contentType)
        // relationship omitted, so addRelationship is never called
        coVerify(exactly = 0) { metadataService.addRelationship(any<MetadataRelationshipInput>()) }
        coVerify(exactly = 1) { queue.setJob(job) }
    }

    @Test
    fun `errors when supplementaryId absent and parent has no id`() = runTest {
        val job = jobFor(SupplementaryToMetadataJob())
        val error = assertFailsWith<IllegalStateException> {
            withContext(queue.asCoroutineContext(job)) {
                executor.execute()
            }
        }
        assertTrue(error.message?.contains("Missing supplementaryId") == true)
    }

    @Test
    fun `errors when parent context lacks supplementaryId`() = runTest {
        val parentId = UUID.random()
        val parent = jobFor(SupplementaryToMetadataJob())
        parent.setContext(JsonObject(emptyMap()))
        coEvery { queue.getJob<Job?>(parentId, any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (it.invocation.args[1] as suspend (Job?) -> Job?).invoke(parent)
        }

        val job = jobFor(SupplementaryToMetadataJob())
        job.setParent(parentId)
        val error = assertFailsWith<IllegalStateException> {
            withContext(queue.asCoroutineContext(job)) {
                executor.execute()
            }
        }
        assertTrue(error.message?.contains("Missing supplementaryId") == true)
    }

    @Test
    fun `errors when supplementary not found`() = runTest {
        val supplementaryId = UUID.random()
        coEvery { metadataService.getSupplementaryById(supplementaryId) } returns null

        val job = jobFor(SupplementaryToMetadataJob(supplementaryId = supplementaryId))
        val error = assertFailsWith<IllegalStateException> {
            withContext(queue.asCoroutineContext(job)) {
                executor.execute()
            }
        }
        assertTrue(error.message?.contains("Missing supplementary") == true)
    }

    @Test
    fun `errors when source metadata missing`() = runTest {
        val supplementaryId = UUID.random()
        val sourceId = UUID.random()
        coEvery { metadataService.getSupplementaryById(supplementaryId) } returns supplementary(sourceId)
        coEvery { metadataService.getById(sourceId) } returns null

        val job = jobFor(SupplementaryToMetadataJob(supplementaryId = supplementaryId))
        val error = assertFailsWith<IllegalStateException> {
            withContext(queue.asCoroutineContext(job)) {
                executor.execute()
            }
        }
        assertTrue(error.message?.contains("Missing metadata") == true)
    }
}
