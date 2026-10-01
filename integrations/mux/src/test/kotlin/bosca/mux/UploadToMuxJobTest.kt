package bosca.mux

import bosca.configuration.model.Configuration
import bosca.configuration.service.ConfigurationService
import bosca.content.metadata.model.Media
import bosca.content.metadata.model.MediaConstants
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MediaService
import bosca.content.metadata.service.MetadataService
import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.mux.client.MuxAssetStatus
import bosca.mux.client.MuxClient
import bosca.mux.client.MuxDirectUpload
import bosca.mux.client.MuxPlaybackId
import bosca.mux.client.MuxUploadStatus
import bosca.mux.configuration.MuxConfiguration
import bosca.mux.jobs.UploadToMuxExecutor
import bosca.mux.jobs.UploadToMuxJob
import bosca.sharedqueue.jobs.DelayException
import bosca.sharedqueue.jobs.FailException
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class UploadToMuxJobTest {

    private val metadataService = mockk<MetadataService>(relaxed = true)
    private val configurationService = mockk<ConfigurationService>(relaxed = true)
    private val storageService = mockk<ObjectStorageService>(relaxed = true)
    private val muxClient = mockk<MuxClient>(relaxed = true)
    private val mediaService = mockk<MediaService>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }

    private val executor = UploadToMuxExecutor(
        metadataService,
        configurationService,
        storageService,
        muxClient,
        mediaService,
        json,
    )

    @OptIn(InternalDI::class)
    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { json }
    }

    @OptIn(InternalDI::class)
    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @OptIn(Internal::class)
    @Test
    fun `skips non-video content types`() = runTest {
        val metadataId = Uuid.random()
        val metadata = createMetadata(metadataId, contentType = "image/png")

        coEvery { metadataService.getById(metadataId, null) } returns metadata
        setupMuxConfig()

        executeJob(metadataId)

        coVerify(exactly = 0) { muxClient.createDirectUpload(any(), any(), any()) }
        coVerify(exactly = 0) { muxClient.putResumableUploadChunk(any(), any(), any(), any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `first entry creates direct upload streams chunked bytes and records upload id`() = runTest {
        val metadataId = Uuid.random()
        // Use a length slightly above a single chunk to force a multi-chunk loop.
        val totalLength = (8L * 1024 * 1024) + 500
        val metadata = createMetadata(metadataId, contentType = "video/mp4", contentLength = totalLength)
        val mockPath = mockk<ObjectPath>()

        val existingMedia = Media(
            metadataId = metadataId,
            status = "queued",
            providerAttributes = buildJsonObject {
                put(MediaConstants.ATTR_PROVIDER, MediaConstants.PROVIDER_MUX)
                put(MediaConstants.ATTR_CREATED_AT_MS, 1234567890L)
            },
        )

        coEvery { metadataService.getById(metadataId, null) } returns metadata
        coEvery { mediaService.getMedia(metadataId) } returns existingMedia
        coEvery { storageService.getPath(metadata, null) } returns mockPath
        coEvery { storageService.getInputStreamRange(mockPath, any()) } returns
            ByteArrayInputStream(ByteArray(0))
        coEvery { muxClient.createDirectUpload(any(), any(), any()) } returns MuxDirectUpload(
            id = "upload-123",
            url = "https://storage.googleapis.com/gcs-resumable",
        )
        coEvery {
            muxClient.getResumableUploadOffset("https://storage.googleapis.com/gcs-resumable", any())
        } returns 0L

        // Return the advancing offset Mux/GCS would report after each PUT.
        val offsetsSeen = mutableListOf<Long>()
        coEvery {
            muxClient.putResumableUploadChunk(
                uploadUrl = "https://storage.googleapis.com/gcs-resumable",
                offset = any(),
                chunkLength = any(),
                totalLength = any(),
                inputStream = any(),
            )
        } answers {
            val offsetArg = secondArg<Long>()
            val chunkLen = thirdArg<Long>()
            offsetsSeen += offsetArg
            offsetArg + chunkLen
        }

        val mediaSlots = mutableListOf<Media>()
        coEvery { mediaService.updateMedia(capture(mediaSlots)) } returns mockk()

        setupMuxConfig()

        assertFailsWith<DelayException> {
            executeJob(metadataId)
        }

        // 8 MiB first chunk, then 500 bytes, so two PATCHes starting at 0 and 8 MiB.
        assertEquals(listOf(0L, 8L * 1024 * 1024), offsetsSeen)

        // First media update: "uploading" WITHOUT uploadId — uploadId lives in job
        // context until streaming finishes, so that retries re-enter the upload
        // branch instead of jumping to pollUpload.
        val uploading = mediaSlots.first()
        assertEquals("uploading", uploading.status)
        val uploadingAttrs = uploading.providerAttributes.jsonObject
        assertEquals(null, uploadingAttrs[MediaConstants.ATTR_UPLOAD_ID]?.jsonPrimitive?.content)
        assertEquals(null, uploadingAttrs[MediaConstants.ATTR_ASSET_ID]?.jsonPrimitive?.content)
        assertEquals(1234567890L, uploadingAttrs[MediaConstants.ATTR_CREATED_AT_MS]?.jsonPrimitive?.content?.toLong())

        // Last media update: "preparing" with uploadId, once all bytes are delivered.
        val preparing = mediaSlots.last()
        assertEquals("preparing", preparing.status)
        val preparingAttrs = preparing.providerAttributes.jsonObject
        assertEquals("upload-123", preparingAttrs[MediaConstants.ATTR_UPLOAD_ID]?.jsonPrimitive?.content)
        assertEquals(1234567890L, preparingAttrs[MediaConstants.ATTR_CREATED_AT_MS]?.jsonPrimitive?.content?.toLong())
    }

    @OptIn(Internal::class)
    @Test
    fun `resumes from Mux-reported offset without recreating upload`() = runTest {
        val metadataId = Uuid.random()
        val totalLength = 10_000L
        val metadata = createMetadata(metadataId, contentType = "video/mp4", contentLength = totalLength)
        val mockPath = mockk<ObjectPath>()

        // The media record carries no uploadId yet — it's still in context until
        // streaming finishes. Status reflects the uploading phase.
        val media = Media(
            metadataId = metadataId,
            status = "uploading",
            providerAttributes = buildJsonObject {
                put(MediaConstants.ATTR_PROVIDER, MediaConstants.PROVIDER_MUX)
                put(MediaConstants.ATTR_CREATED_AT_MS, System.currentTimeMillis())
            },
        )

        coEvery { metadataService.getById(metadataId, null) } returns metadata
        coEvery { mediaService.getMedia(metadataId) } returns media
        coEvery { storageService.getPath(metadata, null) } returns mockPath
        // GCS already accepted 6000 bytes before the previous attempt died.
        coEvery {
            muxClient.getResumableUploadOffset("https://storage.googleapis.com/gcs-resumable", any())
        } returns 6000L

        val rangeSlot = slot<LongRange>()
        coEvery { storageService.getInputStreamRange(mockPath, capture(rangeSlot)) } returns
            ByteArrayInputStream(ByteArray(0))

        coEvery {
            muxClient.putResumableUploadChunk(
                uploadUrl = any(),
                offset = any(),
                chunkLength = any(),
                totalLength = any(),
                inputStream = any(),
            )
        } answers {
            val offsetArg = secondArg<Long>()
            val chunkLen = thirdArg<Long>()
            offsetArg + chunkLen
        }

        coEvery { mediaService.updateMedia(any()) } returns mockk()

        setupMuxConfig()

        // Seed the job context so the executor finds an existing tus URL + upload id.
        assertFailsWith<DelayException> {
            executeJobWithContext(
                metadataId,
                buildJsonObject {
                    put("tusUrl", "https://storage.googleapis.com/gcs-resumable")
                    put("uploadId", "upload-123")
                    put("uploadOffset", 0L)
                },
            )
        }

        // Must not have created a new upload — the resume branch should short-circuit.
        coVerify(exactly = 0) { muxClient.createDirectUpload(any(), any(), any()) }
        // Must have resumed from GCS's committed offset, not from 0.
        coVerify {
            muxClient.putResumableUploadChunk(
                uploadUrl = "https://storage.googleapis.com/gcs-resumable",
                offset = 6000L,
                chunkLength = 4000L,
                totalLength = 10_000L,
                inputStream = any(),
            )
        }
        // Ranged read must start at GCS's offset, not at zero.
        assertEquals(6000L, rangeSlot.captured.first)
    }

    @OptIn(Internal::class)
    @Test
    fun `fails when metadata has no content length`() = runTest {
        val metadataId = Uuid.random()
        val metadata = createMetadata(metadataId, contentType = "video/mp4", contentLength = null)

        val media = Media(
            metadataId = metadataId,
            status = "queued",
            providerAttributes = buildJsonObject {
                put(MediaConstants.ATTR_PROVIDER, MediaConstants.PROVIDER_MUX)
                put(MediaConstants.ATTR_CREATED_AT_MS, System.currentTimeMillis())
            },
        )

        coEvery { metadataService.getById(metadataId, null) } returns metadata
        coEvery { mediaService.getMedia(metadataId) } returns media
        setupMuxConfig()

        assertFailsWith<FailException> {
            executeJob(metadataId)
        }

        coVerify(exactly = 0) { muxClient.createDirectUpload(any(), any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `pollUpload persists asset id when Mux reports asset_created`() = runTest {
        val metadataId = Uuid.random()
        val metadata = createMetadata(metadataId)
        val media = Media(
            metadataId = metadataId,
            status = "preparing",
            providerAttributes = buildJsonObject {
                put(MediaConstants.ATTR_PROVIDER, MediaConstants.PROVIDER_MUX)
                put(MediaConstants.ATTR_UPLOAD_ID, "upload-123")
                put(MediaConstants.ATTR_CREATED_AT_MS, System.currentTimeMillis())
            },
        )

        coEvery { metadataService.getById(metadataId, null) } returns metadata
        coEvery { mediaService.getMedia(metadataId) } returns media
        coEvery { muxClient.getUpload(any(), "upload-123") } returns MuxUploadStatus(
            status = "asset_created",
            assetId = "mux-asset-456",
            errorMessage = null,
        )

        val mediaSlot = slot<Media>()
        coEvery { mediaService.updateMedia(capture(mediaSlot)) } returns mockk()

        setupMuxConfig()

        assertFailsWith<DelayException> {
            executeJob(metadataId)
        }

        val attrs = mediaSlot.captured.providerAttributes.jsonObject
        assertEquals("upload-123", attrs[MediaConstants.ATTR_UPLOAD_ID]?.jsonPrimitive?.content)
        assertEquals("mux-asset-456", attrs[MediaConstants.ATTR_ASSET_ID]?.jsonPrimitive?.content)
    }

    @OptIn(Internal::class)
    @Test
    fun `pollUpload delays while upload still waiting`() = runTest {
        val metadataId = Uuid.random()
        val metadata = createMetadata(metadataId)
        val media = Media(
            metadataId = metadataId,
            status = "preparing",
            providerAttributes = buildJsonObject {
                put(MediaConstants.ATTR_PROVIDER, MediaConstants.PROVIDER_MUX)
                put(MediaConstants.ATTR_UPLOAD_ID, "upload-123")
                put(MediaConstants.ATTR_CREATED_AT_MS, System.currentTimeMillis())
            },
        )

        coEvery { metadataService.getById(metadataId, null) } returns metadata
        coEvery { mediaService.getMedia(metadataId) } returns media
        coEvery { muxClient.getUpload(any(), "upload-123") } returns MuxUploadStatus(
            status = "waiting",
            assetId = null,
            errorMessage = null,
        )

        setupMuxConfig()

        assertFailsWith<DelayException> {
            executeJob(metadataId)
        }

        coVerify(exactly = 0) { mediaService.updateMedia(any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `pollUpload fails when upload errors`() = runTest {
        val metadataId = Uuid.random()
        val metadata = createMetadata(metadataId)
        val media = Media(
            metadataId = metadataId,
            status = "preparing",
            providerAttributes = buildJsonObject {
                put(MediaConstants.ATTR_PROVIDER, MediaConstants.PROVIDER_MUX)
                put(MediaConstants.ATTR_UPLOAD_ID, "upload-123")
                put(MediaConstants.ATTR_CREATED_AT_MS, System.currentTimeMillis())
            },
        )

        coEvery { metadataService.getById(metadataId, null) } returns metadata
        coEvery { mediaService.getMedia(metadataId) } returns media
        coEvery { muxClient.getUpload(any(), "upload-123") } returns MuxUploadStatus(
            status = "errored",
            assetId = null,
            errorMessage = "bad input",
        )

        val mediaSlot = slot<Media>()
        coEvery { mediaService.updateMedia(capture(mediaSlot)) } returns mockk()

        setupMuxConfig()

        assertFailsWith<FailException> {
            executeJob(metadataId)
        }

        assertEquals("errored", mediaSlot.captured.status)
    }

    @OptIn(Internal::class)
    @Test
    fun `pollAssetReadiness finalizes media when asset is ready`() = runTest {
        val metadataId = Uuid.random()
        val metadata = createMetadata(metadataId)
        val media = Media(
            metadataId = metadataId,
            status = "preparing",
            providerAttributes = buildJsonObject {
                put(MediaConstants.ATTR_PROVIDER, MediaConstants.PROVIDER_MUX)
                put(MediaConstants.ATTR_UPLOAD_ID, "upload-123")
                put(MediaConstants.ATTR_ASSET_ID, "mux-asset-456")
                put(MediaConstants.ATTR_CREATED_AT_MS, System.currentTimeMillis())
            },
        )

        coEvery { metadataService.getById(metadataId, null) } returns metadata
        coEvery { mediaService.getMedia(metadataId) } returns media
        coEvery { muxClient.getAssetStatus(any(), "mux-asset-456") } returns MuxAssetStatus(
            status = "ready",
            assetId = "mux-asset-456",
            playbackIds = listOf(MuxPlaybackId(id = "playback-1", policy = "public")),
            duration = 120.5,
            resolutionTier = "1080p",
            aspectRatio = "16:9",
            tracks = emptyList(),
            staticRenditions = emptyList(),
            videoQuality = "plus",
        )

        val mediaSlot = slot<Media>()
        coEvery { mediaService.updateMedia(capture(mediaSlot)) } returns mockk()

        setupMuxConfig()

        executeJob(metadataId)

        val captured = mediaSlot.captured
        assertEquals("ready", captured.status)
        assertEquals("https://stream.mux.com/playback-1.m3u8", captured.hlsUrl)
        assertEquals("https://image.mux.com/playback-1/thumbnail.jpg", captured.thumbnailUrl)
        assertEquals(120.5, captured.durationSeconds)
        assertEquals("1080p", captured.maxResolution)
        assertEquals("16:9", captured.aspectRatio)
        assertEquals("plus", captured.actualVideoQuality)
        assertNotNull(captured.transcriptions)
    }

    @OptIn(Internal::class)
    @Test
    fun `pollAssetReadiness fails when asset errors`() = runTest {
        val metadataId = Uuid.random()
        val metadata = createMetadata(metadataId)
        val media = Media(
            metadataId = metadataId,
            status = "preparing",
            providerAttributes = buildJsonObject {
                put(MediaConstants.ATTR_PROVIDER, MediaConstants.PROVIDER_MUX)
                put(MediaConstants.ATTR_ASSET_ID, "mux-asset-456")
                put(MediaConstants.ATTR_CREATED_AT_MS, System.currentTimeMillis())
            },
        )

        coEvery { metadataService.getById(metadataId, null) } returns metadata
        coEvery { mediaService.getMedia(metadataId) } returns media
        coEvery { muxClient.getAssetStatus(any(), "mux-asset-456") } returns MuxAssetStatus(
            status = "errored",
            assetId = "mux-asset-456",
            playbackIds = emptyList(),
        )

        val mediaSlot = slot<Media>()
        coEvery { mediaService.updateMedia(capture(mediaSlot)) } returns mockk()

        setupMuxConfig()

        assertFailsWith<FailException> {
            executeJob(metadataId)
        }

        assertEquals("errored", mediaSlot.captured.status)
    }

    @OptIn(Internal::class)
    @Test
    fun `pollAssetReadiness delays while asset still preparing`() = runTest {
        val metadataId = Uuid.random()
        val metadata = createMetadata(metadataId)
        val media = Media(
            metadataId = metadataId,
            status = "preparing",
            providerAttributes = buildJsonObject {
                put(MediaConstants.ATTR_PROVIDER, MediaConstants.PROVIDER_MUX)
                put(MediaConstants.ATTR_ASSET_ID, "mux-asset-456")
                put(MediaConstants.ATTR_CREATED_AT_MS, System.currentTimeMillis())
            },
        )

        coEvery { metadataService.getById(metadataId, null) } returns metadata
        coEvery { mediaService.getMedia(metadataId) } returns media
        coEvery { muxClient.getAssetStatus(any(), "mux-asset-456") } returns MuxAssetStatus(
            status = "preparing",
            assetId = "mux-asset-456",
            playbackIds = emptyList(),
        )

        setupMuxConfig()

        assertFailsWith<DelayException> {
            executeJob(metadataId)
        }

        coVerify(exactly = 0) { mediaService.updateMedia(any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `pollAssetReadiness times out after max wait`() = runTest {
        val metadataId = Uuid.random()
        val metadata = createMetadata(metadataId)
        val media = Media(
            metadataId = metadataId,
            status = "preparing",
            providerAttributes = buildJsonObject {
                put(MediaConstants.ATTR_PROVIDER, MediaConstants.PROVIDER_MUX)
                put(MediaConstants.ATTR_ASSET_ID, "mux-asset-456")
                put(MediaConstants.ATTR_CREATED_AT_MS, System.currentTimeMillis() - 7_200_000L)
            },
        )

        coEvery { metadataService.getById(metadataId, null) } returns metadata
        coEvery { mediaService.getMedia(metadataId) } returns media
        coEvery { muxClient.getAssetStatus(any(), "mux-asset-456") } returns MuxAssetStatus(
            status = "preparing",
            assetId = "mux-asset-456",
            playbackIds = emptyList(),
        )

        val mediaSlot = slot<Media>()
        coEvery { mediaService.updateMedia(capture(mediaSlot)) } returns mockk()

        setupMuxConfig()

        val ex = assertFailsWith<FailException> {
            executeJob(metadataId)
        }

        assertEquals("errored", mediaSlot.captured.status)
        assertTrue(ex.message!!.contains("timed out"))
    }

    @OptIn(Internal::class)
    private suspend fun executeJob(metadataId: Uuid) {
        executeJobWithContext(metadataId, null)
    }

    @OptIn(Internal::class)
    private suspend fun executeJobWithContext(metadataId: Uuid, initialContext: JsonObject?) {
        val jobDefinition = UploadToMuxJob(metadataId)
        val jobQueue = mockk<JobQueue>(relaxed = true)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(jobDefinition),
            executor = UploadToMuxExecutor::class
        )
        if (initialContext != null) {
            job.setContext(initialContext)
        }

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }
    }

    private suspend fun setupMuxConfig() {
        val configId = Uuid.random()
        val config = mockk<Configuration> {
            every { id } returns configId
        }
        val muxConfig = MuxConfiguration(
            tokenId = "test-token-id",
            tokenSecret = "test-token-secret"
        )
        coEvery { configurationService.getByKey("mux") } returns config
        coEvery { configurationService.getValue(configId) } returns json.encodeToJsonElement(muxConfig)
    }

    private fun createMetadata(
        id: Uuid,
        contentType: String = "video/mp4",
        contentLength: Long? = 1024L,
    ) = Metadata(
        id = id,
        name = "test-video",
        type = MetadataType.STANDARD,
        contentType = contentType,
        contentLength = contentLength,
        languageTag = "en",
        workflowStateId = "published",
        created = OffsetDateTime.now(),
    )
}
