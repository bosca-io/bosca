@file:OptIn(bosca.core.annotations.Internal::class, bosca.di.annotation.InternalDI::class)

package bosca.jobs

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataSupplementary
import bosca.content.metadata.model.MetadataSupplementaryInput
import bosca.content.metadata.service.MetadataService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume.assumeTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers [WavToMp3JobExecutor.execute]: the missing-metadata guard, parent-supplementary-id
 * resolution (both the null-parent short-circuit and the block that reads the parent job's
 * context), the `job.supplementaryId ?: parentSupplementaryId` selection, the ffmpeg subprocess
 * (both the non-zero-exit failure arm and, when ffmpeg is installed, the success arm that adds the
 * supplementary, uploads it, marks it uploaded, and sets the job context), and the `finally` that
 * deletes temp files.
 */
class WavToMp3JobExecutorCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val objectStorageService = mockk<ObjectStorageService>()
    private val json = Json { ignoreUnknownKeys = true }

    private val executor = WavToMp3JobExecutor(metadataService, objectStorageService)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        // AbstractJobExecutor.getJobDefinition() decodes the definition via the DI-provided Json.
        provides<Json> { json }
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        ProviderRegistry.clear()
    }

    private fun jobFor(config: WavToMp3Job): Job = InternalJobConstructor(
        definition = json.encodeToJsonElement(WavToMp3Job.serializer(), config),
        executor = WavToMp3JobExecutor::class,
    )

    /** A minimal, valid single-channel 16-bit PCM WAV that ffmpeg can decode. */
    private fun validWavBytes(): ByteArray {
        val sampleRate = 44100
        val channels = 1
        val bits = 16
        val numSamples = sampleRate // one second
        val dataSize = numSamples * channels * (bits / 8)
        val out = ByteArrayOutputStream()
        val o = DataOutputStream(out)
        fun le4(v: Int) {
            o.write(v and 0xff); o.write((v shr 8) and 0xff); o.write((v shr 16) and 0xff); o.write((v shr 24) and 0xff)
        }
        fun le2(v: Int) {
            o.write(v and 0xff); o.write((v shr 8) and 0xff)
        }
        o.writeBytes("RIFF"); le4(36 + dataSize); o.writeBytes("WAVE")
        o.writeBytes("fmt "); le4(16); le2(1); le2(channels); le4(sampleRate)
        le4(sampleRate * channels * (bits / 8)); le2(channels * (bits / 8)); le2(bits)
        o.writeBytes("data"); le4(dataSize)
        for (i in 0 until numSamples) {
            val s = (Math.sin(2 * Math.PI * 440.0 * i / sampleRate) * 10000).toInt().toShort().toInt()
            o.write(s and 0xff); o.write((s shr 8) and 0xff)
        }
        o.flush()
        return out.toByteArray()
    }

    private fun ffmpegAvailable(): Boolean = try {
        ProcessBuilder("ffmpeg", "-version")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    } catch (_: Exception) {
        false
    }

    @Test
    fun `throws when metadata is missing`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id, 3) } returns null

        val job = jobFor(WavToMp3Job(id = id, version = 3))
        val queue = mockk<JobQueue>(relaxed = true)

        val ex = assertFailsWith<IllegalStateException> {
            withContext(queue.asCoroutineContext(job)) { executor.execute() }
        }
        assertEquals("Missing metadata", ex.message)

        // Guard fires before any storage interaction.
        coVerify(exactly = 0) { objectStorageService.getInputStream(any()) }
    }

    @Test
    fun `uses the job supplementaryId and fails to convert an invalid wav`() = runTest {
        val id = UUID.random()
        val suppId = UUID.random()
        val metadata = mockk<Metadata>()
        val path = mockk<ObjectPath>()

        coEvery { metadataService.getById(id, 1) } returns metadata
        // job.supplementaryId is non-null, so the parent-id lookup is never used for the path.
        coEvery { objectStorageService.getPath(metadata, suppId) } returns path
        coEvery { objectStorageService.getInputStream(path) } returns ByteArrayInputStream("not a wav".toByteArray())

        val job = jobFor(WavToMp3Job(id = id, version = 1, supplementaryId = suppId))
        val queue = mockk<JobQueue>(relaxed = true)

        // Garbage input drives ffmpeg to a non-zero exit (RuntimeException from the exitCode != 0 arm);
        // if ffmpeg is not installed, ProcessBuilder.start() throws IOException. Either way the download
        // path is exercised and the finally-block cleans up the temp files.
        assertFailsWith<Exception> {
            withContext(queue.asCoroutineContext(job)) { executor.execute() }
        }

        // The job supplementaryId was resolved directly; no supplementary is ever added on failure.
        coVerify(exactly = 1) { objectStorageService.getPath(metadata, suppId) }
        coVerify(exactly = 0) { metadataService.addSupplementary(any()) }
    }

    @Test
    fun `resolves the parent supplementaryId when the job has none`() = runTest {
        val id = UUID.random()
        val parentId = UUID.random()
        val parentSuppId = UUID.random()
        val metadata = mockk<Metadata>()
        val path = mockk<ObjectPath>()

        coEvery { metadataService.getById(id, 2) } returns metadata
        // The resolved parent supplementary id must be the one used to build the download path.
        coEvery { objectStorageService.getPath(metadata, parentSuppId) } returns path
        coEvery { objectStorageService.getInputStream(path) } returns ByteArrayInputStream("garbage".toByteArray())

        // The job has no supplementaryId, but a parent job whose context carries one.
        val job = jobFor(WavToMp3Job(id = id, version = 2, supplementaryId = null))
        job.setParent(parentId)

        val parentJob = jobFor(WavToMp3Job(id = id, version = 2))
        parentJob.setContext(JsonObject(mapOf("supplementaryId" to JsonPrimitive(parentSuppId.toString()))))

        val queue = mockk<JobQueue>(relaxed = true)
        coEvery { queue.getJob<UUID?>(parentId, any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (it.invocation.args[1] as suspend (Job?) -> UUID?)(parentJob)
        }

        assertFailsWith<Exception> {
            withContext(queue.asCoroutineContext(job)) { executor.execute() }
        }

        coVerify(exactly = 1) { queue.getJob<UUID?>(parentId, any()) }
        coVerify(exactly = 1) { objectStorageService.getPath(metadata, parentSuppId) }
    }

    @Test
    fun `parent job without a supplementaryId in context resolves to null`() = runTest {
        val id = UUID.random()
        val parentId = UUID.random()
        val metadata = mockk<Metadata>()
        val path = mockk<ObjectPath>()

        coEvery { metadataService.getById(id, 5) } returns metadata
        // With no parent supplementary id resolved, the path is built for the primary content (null).
        coEvery { objectStorageService.getPath(metadata, null) } returns path
        coEvery { objectStorageService.getInputStream(path) } returns ByteArrayInputStream("garbage".toByteArray())

        val job = jobFor(WavToMp3Job(id = id, version = 5, supplementaryId = null))
        job.setParent(parentId)

        // Parent job context has no "supplementaryId" key -> the block returns null.
        val parentJob = jobFor(WavToMp3Job(id = id, version = 5))
        parentJob.setContext(JsonObject(mapOf("other" to JsonPrimitive("value"))))

        val queue = mockk<JobQueue>(relaxed = true)
        coEvery { queue.getJob<UUID?>(parentId, any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (it.invocation.args[1] as suspend (Job?) -> UUID?)(parentJob)
        }

        assertFailsWith<Exception> {
            withContext(queue.asCoroutineContext(job)) { executor.execute() }
        }

        coVerify(exactly = 1) { objectStorageService.getPath(metadata, null) }
    }

    @Test
    fun `parent lookup returning a null job resolves to null`() = runTest {
        val id = UUID.random()
        val parentId = UUID.random()
        val metadata = mockk<Metadata>()
        val path = mockk<ObjectPath>()

        coEvery { metadataService.getById(id, 8) } returns metadata
        coEvery { objectStorageService.getPath(metadata, null) } returns path
        coEvery { objectStorageService.getInputStream(path) } returns ByteArrayInputStream("garbage".toByteArray())

        val job = jobFor(WavToMp3Job(id = id, version = 8, supplementaryId = null))
        job.setParent(parentId)

        val queue = mockk<JobQueue>(relaxed = true)
        // The parent job is gone -> the block's `it?.` short-circuits to null.
        coEvery { queue.getJob<UUID?>(parentId, any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (it.invocation.args[1] as suspend (Job?) -> UUID?)(null)
        }

        assertFailsWith<Exception> {
            withContext(queue.asCoroutineContext(job)) { executor.execute() }
        }

        coVerify(exactly = 1) { objectStorageService.getPath(metadata, null) }
    }

    @Test
    fun `converts wav to mp3 and records the supplementary on success`() = runTest {
        assumeTrue("ffmpeg is required to exercise the success arm", ffmpegAvailable())
        val id = UUID.random()
        val newSuppId = UUID.random()
        val metadata = mockk<Metadata>()
        val downloadPath = mockk<ObjectPath>()
        val uploadPath = mockk<ObjectPath>()

        coEvery { metadataService.getById(id, 4) } returns metadata
        // Primary-content download (no supplementaryId on the job, no parent).
        coEvery { objectStorageService.getPath(metadata, null) } returns downloadPath
        coEvery { objectStorageService.getInputStream(downloadPath) } returns ByteArrayInputStream(validWavBytes())

        val addedInput = slot<MetadataSupplementaryInput>()
        val supplementary = MetadataSupplementary(
            id = newSuppId,
            metadataId = id,
            key = "mp3-$id",
            name = "MP3 File",
            created = OffsetDateTime.now(),
            modified = OffsetDateTime.now(),
        )
        coEvery { metadataService.addSupplementary(capture(addedInput)) } returns supplementary

        // upload(metadata, supplementary.id, file) resolves the path then writes it.
        coEvery { objectStorageService.getPath(metadata, newSuppId) } returns uploadPath
        coEvery { objectStorageService.setInputStream(uploadPath, any(), any()) } returns 123L
        coEvery { metadataService.setSupplementaryUploaded(newSuppId, "audio/mp3", any()) } returns Unit

        val job = jobFor(WavToMp3Job(id = id, version = 4))
        val queue = mockk<JobQueue>(relaxed = true)

        withContext(queue.asCoroutineContext(job)) { executor.execute() }

        // The supplementary input is built from the job/output metadata.
        assertEquals(id, addedInput.captured.metadataId)
        assertEquals("mp3-$id", addedInput.captured.key)
        assertEquals("MP3 File", addedInput.captured.name)
        assertEquals("audio/mp3", addedInput.captured.contentType)
        assertTrue((addedInput.captured.contentLength ?: 0L) > 0L)

        coVerify(exactly = 1) { metadataService.addSupplementary(any()) }
        coVerify(exactly = 1) { objectStorageService.setInputStream(uploadPath, any(), any()) }
        coVerify(exactly = 1) { metadataService.setSupplementaryUploaded(newSuppId, "audio/mp3", any()) }
        // setContext(...) persists the new supplementary id on the job via the queue.
        coVerify(exactly = 1) { queue.setJob(job) }
    }
}
