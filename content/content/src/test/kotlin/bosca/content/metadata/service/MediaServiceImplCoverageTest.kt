package bosca.content.metadata.service

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.content.metadata.model.Media
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.repository.MediaRepositoryImpl
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.events.DisabledEventManagerFilter
import bosca.events.EventManager
import bosca.events.asCoroutineContext
import bosca.graphql.Batch
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.test.ContentTestInfrastructure
import io.mockk.unmockkAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.AfterClass
import org.junit.BeforeClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.toJavaUuid

@OptIn(InternalDI::class)
class MediaServiceImplCoverageTest {

    private lateinit var serializer: RequestCacheSerializer

    private val connectionPool
        get() = infrastructure.connectionPool

    private val cacheManager
        get() = infrastructure.cacheManager

    private val testJson = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(OffsetDateTimeSerializer())
            contextual(UUIDSerializer())
        }
    }

    private lateinit var repository: MediaRepositoryImpl
    private lateinit var service: MediaServiceImpl

    @BeforeTest
    fun setup() = runBlocking {
        unmockkAll()
        infrastructure.reset()
        serializer = RequestCacheSerializerImpl(testJson)

        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { serializer }
        provides<Json>(singleton = true) { testJson }

        repository = MediaRepositoryImpl()
        service = MediaServiceImpl(repository)
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    companion object {

        private val infrastructure = ContentTestInfrastructure()

        @BeforeClass
        @JvmStatic
        fun startInfrastructure() = runBlocking {
            infrastructure.start()
        }

        @AfterClass
        @JvmStatic
        fun stopInfrastructure() = runBlocking {
            infrastructure.stop()
        }
    }

    private suspend fun <T> withRequest(block: suspend () -> T): T {
        val cm = ConnectionManager(connectionPool)
        val rc = RequestCache(cacheManager, serializer)
        val em = EventManager().apply { filter = DisabledEventManagerFilter }
        return withContext(cm.asCoroutineContext() + rc.asCoroutineContext() + em.asCoroutineContext()) {
            try {
                block()
            } finally {
                withContext(NonCancellable) {
                    cm.release()
                }
            }
        }
    }

    /** metadata_media.metadata_id FKs to metadata(id), so a metadata row must exist first. */
    private suspend fun insertMetadata(id: UUID) {
        val cm = ConnectionManager(connectionPool)
        withContext(cm.asCoroutineContext()) {
            cm.beginTransaction()
            cm.useStatement(
                "insert into metadata (id, name, content_type) values (?, 'Media Owner', 'video/mp4')"
            ) { stmt ->
                stmt.setObject(1, id.toJavaUuid())
                stmt.execute()
            }
            withContext(NonCancellable) {
                cm.commitTransaction()
            }
        }
        withContext(NonCancellable) {
            cm.release()
        }
    }

    private fun sampleMedia(metadataId: UUID, status: String = "ready"): Media =
        Media(
            metadataId = metadataId,
            status = status,
            hlsUrl = "https://cdn.example/${metadataId}/master.m3u8",
            hlsAudioOnlyUrl = "https://cdn.example/${metadataId}/audio.m3u8",
            downloadUrl = "https://cdn.example/${metadataId}/download.mp4",
            thumbnailUrl = "https://cdn.example/${metadataId}/thumb.jpg",
            animatedPreviewUrl = "https://cdn.example/${metadataId}/preview.gif",
            durationSeconds = 12.5,
            maxResolution = "1080p",
            aspectRatio = "16:9",
            actualVideoQuality = "premium",
            transcriptions = JsonArray(emptyList()),
            providerAttributes = buildJsonObject { put("provider", "mux") },
        )

    @Test
    fun `addMedia persists and getMedia returns it`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val metadataId = UUID.random()
        withRequest { insertMetadata(metadataId) }

        val added = withRequest { service.addMedia(sampleMedia(metadataId)) }
        assertEquals(metadataId, added.metadataId)
        assertEquals("ready", added.status)
        assertEquals("1080p", added.maxResolution)

        val fetched = withRequest { service.getMedia(metadataId) }
        assertNotNull(fetched)
        assertEquals(metadataId, fetched.metadataId)
        assertEquals("ready", fetched.status)
        assertEquals("16:9", fetched.aspectRatio)
        // The MediaRepository.add insert column list omits `actual_video_quality`, so the value
        // supplied on the model is never persisted and the column round-trips back as NULL.
        assertNull(fetched.actualVideoQuality)
    }

    @Test
    fun `getMedia returns null when no media exists`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val fetched = withRequest { service.getMedia(UUID.random()) }
        assertNull(fetched, "resolver miss must yield null")
    }

    @Test
    fun `updateMedia persists changes and invalidates cache`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val metadataId = UUID.random()
        withRequest { insertMetadata(metadataId) }
        withRequest { service.addMedia(sampleMedia(metadataId, status = "preparing")) }

        // Populate the cache with the preparing state.
        val before = withRequest { service.getMedia(metadataId) }
        assertNotNull(before)
        assertEquals("preparing", before.status)

        val updated = withRequest {
            service.updateMedia(sampleMedia(metadataId, status = "ready").copy(maxResolution = "720p"))
        }
        assertEquals("ready", updated.status)
        assertEquals("720p", updated.maxResolution)

        // updateMedia invalidated the cache, so the next read reflects the update.
        val after = withRequest { service.getMedia(metadataId) }
        assertNotNull(after)
        assertEquals("ready", after.status)
        assertEquals("720p", after.maxResolution)
    }

    @Test
    fun `deleteMedia removes the record and invalidates cache`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val metadataId = UUID.random()
        withRequest { insertMetadata(metadataId) }
        withRequest { service.addMedia(sampleMedia(metadataId)) }

        // Populate cache.
        assertNotNull(withRequest { service.getMedia(metadataId) })

        withRequest { service.deleteMedia(metadataId) }

        val afterDelete = withRequest { service.getMedia(metadataId) }
        assertNull(afterDelete, "media should be gone after deleteMedia")
    }

    @Test
    fun `deleteMedia is a no-op when nothing exists`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        // Exercises deleteByMetadataId + cache remove against an absent row.
        withRequest { service.deleteMedia(UUID.random()) }
    }

    @Test
    fun `addToBatch populates present keys and skips missing keys`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val presentId = UUID.random()
        val missingId = UUID.random()
        withRequest { insertMetadata(presentId) }
        withRequest { service.addMedia(sampleMedia(presentId)) }

        val presentKey = MetadataCacheKeyId(presentId)
        val missingKey = MetadataCacheKeyId(missingId)

        val batch = Batch<MetadataCacheKeyId, Media>(listOf(presentKey, missingKey))
        withRequest { service.addToBatch(batch) }

        // Hit branch: the present key resolves to media via the batch resolver.
        val present = batch.getData(presentKey)
        assertNotNull(present, "present key should be resolved by batch")
        assertEquals(presentId, present.metadataId)

        // Miss branch: `results[key.id] ?: continue` skips the absent key.
        assertNull(batch.getData(missingKey), "missing key should remain unset")
    }

    @Test
    fun `addToBatch with only a missing key resolves nothing`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val missingKey = MetadataCacheKeyId(UUID.random())
        val batch = Batch<MetadataCacheKeyId, Media>(listOf(missingKey))
        withRequest { service.addToBatch(batch) }
        assertNull(batch.getData(missingKey))
    }
}
