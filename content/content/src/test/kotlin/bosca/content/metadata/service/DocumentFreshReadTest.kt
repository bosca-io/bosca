@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.content.metadata.service

import bosca.cache.CacheManager
import bosca.content.metadata.model.Document
import bosca.content.metadata.repository.DocumentCollaborationRepository
import bosca.content.metadata.repository.DocumentRepository
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DocumentFreshReadTest {
    private val repository = mockk<DocumentRepository>()
    private val metadata = mockk<ObjectProvider<MetadataService>>()
    private val collaboration = mockk<DocumentCollaborationRepository>()
    private val links = mockk<ContentEntityLinkService>()

    @BeforeTest fun prepareRegistry() = ProviderRegistry.clear()
    @AfterTest fun clearRegistry() = ProviderRegistry.clear()

    private fun service(): DocumentServiceImpl {
        provides<CacheManager> { mockk(relaxed = true) }
        return DocumentServiceImpl(metadata, repository, collaboration, links, Json)
    }

    @Test
    fun `fresh reads preserve decoding failures when rows disappear or cannot be recovered`() = runTest {
        val id = UUID.random()
        val failure = SerializationException("Unrecoverable document")
        coEvery { repository.getByMetadataIdAndVersion(id, 1) } throws failure
        coEvery { repository.getByMetadataIdAndVersionWithoutContent(id, 1) } returns null
        val service = service()
        assertSame(failure, assertFailsWith<SerializationException> { service.getDocumentFresh(id, 1) })

        coEvery { repository.getByMetadataIdAndVersionWithoutContent(id, 1) } returns Document(id, 1, title = "Current")
        coEvery { repository.getRawContent(id, 1) } returns null
        assertSame(failure, assertFailsWith<SerializationException> { service.getDocumentFresh(id, 1) })

        coEvery { repository.getRawContent(id, 1) } returns Json.parseToJsonElement("""{"document":{"type":"doc","content":[{"type":"unknown-node"}]}}""")
        assertSame(failure, assertFailsWith<SerializationException> { service.getDocumentFresh(id, 1) })
    }

    @Test
    fun `fresh reads propagate cancellation and repository failures unchanged`() = runTest {
        val id = UUID.random()
        val service = service()
        val cancellation = CancellationException("Cancelled read")
        coEvery { repository.getByMetadataIdAndVersion(id, 1) } throws cancellation
        assertSame(cancellation, assertFailsWith<CancellationException> { service.getDocumentFresh(id, 1) })
        val failure = IllegalStateException("Repository unavailable")
        coEvery { repository.getByMetadataIdAndVersion(id, 1) } throws failure
        assertSame(failure, assertFailsWith<IllegalStateException> { service.getDocumentFresh(id, 1) })
    }
}
