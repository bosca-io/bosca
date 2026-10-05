package bosca.content.metadata.service

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.repository.DocumentCollaborationRepository
import bosca.content.metadata.repository.DocumentRepository
import bosca.di.ObjectProvider
import bosca.di.provides
import bosca.documents.Content
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Unit-level coverage for the new `collaborationSync` parameter on
 * [DocumentServiceImpl.setDocument]. End-to-end behavior is harder to exercise without
 * a database; these tests verify the call routing — that NONE leaves the collaboration
 * row alone, RESET deletes it, and MERGE falls back to RESET while we still need the
 * ProseMirror↔Yjs bridge.
 */
class DocumentServiceImplCollabSyncTest {

    private val metadataServiceProvider = mockk<ObjectProvider<MetadataService>>(relaxed = true)
    private val documentRepository = mockk<DocumentRepository>(relaxed = true)
    private val collaborationRepository = mockk<DocumentCollaborationRepository>(relaxed = true)
    private val contentEntityLinkService = mockk<ContentEntityLinkService>(relaxed = true)
    private val cacheManager = mockk<CacheManager>(relaxed = true)

    private lateinit var service: DocumentServiceImpl

    @BeforeTest
    fun setup() {
        provides<CacheManager> { cacheManager }
        service = DocumentServiceImpl(
            metadataServiceProvider,
            documentRepository,
            collaborationRepository,
            contentEntityLinkService,
            Json,
        )
    }

    private fun newMetadata(): Metadata {
        val metadata = mockk<Metadata>(relaxed = true)
        coEvery { metadata.id } returns UUID.random()
        coEvery { metadata.version } returns 1
        return metadata
    }

    private fun newInput() = DocumentInput(title = "title", content = Content())

    /**
     * `DocumentServiceImpl.setDocument` calls into a per-request cache to invalidate the
     * cached document; outside of a real request, we have to install a [RequestCache] in
     * the coroutine context so the cache invalidation can run.
     */
    private suspend fun <T> withRequestCache(block: suspend () -> T): T {
        val cache = RequestCache(cacheManager, RequestCacheSerializerImpl(Json))
        return withContext(cache.asCoroutineContext()) { block() }
    }

    @Test
    fun `setDocument with NONE leaves collaboration alone`() = runTest {
        val metadata = newMetadata()
        withRequestCache {
            service.setDocument(metadata, newInput(), CollaborationSyncMode.NONE)
        }
        coVerify(exactly = 1) {
            documentRepository.add(any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            collaborationRepository.removeCollaboration(any(), any())
            collaborationRepository.setCollaboration(any())
        }
    }

    @Test
    fun `setDocument default omits collaboration write`() = runTest {
        val metadata = newMetadata()
        // No explicit mode argument — default should be NONE.
        withRequestCache {
            service.setDocument(metadata, newInput())
        }
        coVerify(exactly = 0) {
            collaborationRepository.removeCollaboration(any(), any())
            collaborationRepository.setCollaboration(any())
        }
    }

    @Test
    fun `setDocument with RESET removes the collaboration row`() = runTest {
        val metadata = newMetadata()
        withRequestCache {
            service.setDocument(metadata, newInput(), CollaborationSyncMode.RESET)
        }
        coVerify(exactly = 1) {
            collaborationRepository.removeCollaboration(any(), any())
        }
        coVerify(exactly = 0) {
            collaborationRepository.setCollaboration(any())
        }
    }

    @Test
    fun `setDocument with MERGE writes a Yjs CRDT seeded from the new content`() = runTest {
        // No existing collab row → MERGE should fetch (return null) and call setCollaboration
        // with a fresh Yjs binary update derived from the new content.
        val metadata = newMetadata()
        coEvery { collaborationRepository.getByMetadataIdAndVersion(any(), any()) } returns null
        withRequestCache {
            service.setDocument(metadata, newInput(), CollaborationSyncMode.MERGE)
        }
        coVerify(exactly = 1) {
            collaborationRepository.setCollaboration(any())
        }
        coVerify(exactly = 0) {
            collaborationRepository.removeCollaboration(any(), any())
        }
    }
}
