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
import bosca.mux.client.MuxClient
import bosca.mux.configuration.MuxConfiguration
import bosca.mux.jobs.DeleteFromMuxExecutor
import bosca.mux.jobs.DeleteFromMuxJob
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.uuid.Uuid

class DeleteFromMuxJobTest {

    private val metadataService = mockk<MetadataService>(relaxed = true)
    private val configurationService = mockk<ConfigurationService>(relaxed = true)
    private val mediaService = mockk<MediaService>(relaxed = true)
    private val muxClient = mockk<MuxClient>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }

    private val executor = DeleteFromMuxExecutor(
        metadataService,
        configurationService,
        mediaService,
        muxClient,
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
    fun `does nothing when no media record exists`() = runTest {
        val metadataId = Uuid.random()
        val metadata = createMetadata(metadataId)

        coEvery { metadataService.getById(metadataId, null) } returns metadata
        coEvery { mediaService.getMedia(metadataId) } returns null

        setupMuxConfig()

        val jobDefinition = DeleteFromMuxJob(metadataId)
        val jobQueue = mockk<JobQueue>(relaxed = true)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(jobDefinition),
            executor = DeleteFromMuxExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify(exactly = 0) { muxClient.deleteAsset(any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `deletes mux asset and removes media record`() = runTest {
        val metadataId = Uuid.random()
        val metadata = createMetadata(metadataId)

        val media = Media(
            metadataId = metadataId,
            status = "ready",
            providerAttributes = buildJsonObject {
                put(MediaConstants.ATTR_PROVIDER, MediaConstants.PROVIDER_MUX)
                put(MediaConstants.ATTR_ASSET_ID, "mux-asset-456")
            },
        )

        coEvery { metadataService.getById(metadataId, null) } returns metadata
        coEvery { mediaService.getMedia(metadataId) } returns media

        setupMuxConfig()

        val jobDefinition = DeleteFromMuxJob(metadataId)
        val jobQueue = mockk<JobQueue>(relaxed = true)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(jobDefinition),
            executor = DeleteFromMuxExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify { muxClient.deleteAsset(any(), "mux-asset-456") }
        coVerify { mediaService.deleteMedia(metadataId) }
    }

    @OptIn(Internal::class)
    @Test
    fun `deletes mux asset using assetId from job when metadata already deleted`() = runTest {
        val metadataId = Uuid.random()

        coEvery { metadataService.getById(metadataId, null) } returns null
        coEvery { mediaService.getMedia(metadataId) } returns null

        setupMuxConfig()

        val jobDefinition = DeleteFromMuxJob(metadataId, assetId = "pre-resolved-asset-789")
        val jobQueue = mockk<JobQueue>(relaxed = true)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(jobDefinition),
            executor = DeleteFromMuxExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify { muxClient.deleteAsset(any(), "pre-resolved-asset-789") }
        coVerify { mediaService.deleteMedia(metadataId) }
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

    private fun createMetadata(id: Uuid) = Metadata(
        id = id,
        name = "test-video",
        type = MetadataType.STANDARD,
        contentType = "video/mp4",
        contentLength = 1024L,
        languageTag = "en",
        workflowStateId = "published",
        created = OffsetDateTime.now(),
    )
}
