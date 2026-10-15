@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.content.metadata.service

import bosca.cache.CacheManager
import bosca.cache.Cache
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.content.metadata.model.Document
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.model.MetadataCacheKeySerializer
import bosca.content.metadata.repository.DocumentCollaborationRepository
import bosca.content.metadata.repository.DocumentRepository
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DocumentReadFailureTest {
    private val repository = mockk<DocumentRepository>()
    private val metadata = mockk<ObjectProvider<MetadataService>>()
    private val collaboration = mockk<DocumentCollaborationRepository>()
    private val links = mockk<ContentEntityLinkService>()
    private val cacheManager = mockk<CacheManager>(relaxed = true)

    @BeforeTest fun prepareRegistry() = ProviderRegistry.clear()
    @AfterTest fun clearRegistry() = ProviderRegistry.clear()

    private fun service(): DocumentServiceImpl {
        val cache = mockk<Cache<MetadataCacheKeyId>>()
        every { cache.keySerializer } returns MetadataCacheKeySerializer
        coEvery { cache.get(any()) } returns mockk {
            every { value } returns null
            every { exists } returns false
        }
        coEvery { cacheManager.getCache<MetadataCacheKeyId>("documents") } returns cache
        provides<CacheManager> { cacheManager }
        return DocumentServiceImpl(metadata, repository, collaboration, links, Json)
    }

    private suspend fun <T> withRequest(block: suspend () -> T): T =
        withContext(RequestCache(cacheManager, RequestCacheSerializerImpl(Json)).asCoroutineContext()) {
            block()
        }

    @Test
    fun `document reads preserve decoding failures when rows disappear or cannot be recovered`() = runTest {
        val id = UUID.random()
        val failure = SerializationException("Unrecoverable document")
        coEvery { repository.getByMetadataIdAndVersion(id, 1) } throws failure
        coEvery { repository.getByMetadataIdAndVersionWithoutContent(id, 1) } returns null
        val service = service()
        withRequest {
            assertSame(failure, assertFailsWith<SerializationException> { service.getDocument(id, 1) })

            coEvery { repository.getByMetadataIdAndVersionWithoutContent(id, 1) } returns Document(id, 1, title = "Current")
            coEvery { repository.getRawContent(id, 1) } returns null
            assertSame(failure, assertFailsWith<SerializationException> { service.getDocument(id, 1) })

            coEvery { repository.getRawContent(id, 1) } returns Json.parseToJsonElement("""{"document":{"type":"doc","content":[{"type":"unknown-node"}]}}""")
            assertSame(failure, assertFailsWith<SerializationException> { service.getDocument(id, 1) })
        }
    }

    @Test
    fun `document reads propagate cancellation and repository failures unchanged`() = runTest {
        val id = UUID.random()
        val service = service()
        withRequest {
            val cancellation = CancellationException("Cancelled read")
            coEvery { repository.getByMetadataIdAndVersion(id, 1) } throws cancellation
            assertSame(cancellation, assertFailsWith<CancellationException> { service.getDocument(id, 1) })
            val failure = IllegalStateException("Repository unavailable")
            coEvery { repository.getByMetadataIdAndVersion(id, 1) } throws failure
            assertSame(failure, assertFailsWith<IllegalStateException> { service.getDocument(id, 1) })
        }
    }
}
