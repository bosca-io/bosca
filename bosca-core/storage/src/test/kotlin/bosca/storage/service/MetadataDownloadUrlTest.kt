package bosca.storage.service

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.di.asProvider
import bosca.serialization.UUID
import io.mockk.mockk
import java.net.URI
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetadataDownloadUrlTest {
    private val signer = UrlSignerImpl("local-download-test-signing-key")
    private val storage = FileSystemObjectStorageService(
        "https://recovered.invalid", "https://recovered.invalid", signer.asProvider(), "/unused", mockk(),
    )
    private val metadata = Metadata(
        id = UUID.random(), version = 6, name = "Earlier image", type = MetadataType.STANDARD,
        contentType = "image/png", contentLength = 7, languageTag = "en", workflowStateId = "published",
    )

    private fun query(url: String) = URI.create(url).rawQuery.split('&').associate {
        it.substringBefore('=') to it.substringAfter('=')
    }

    @Test
    fun `primary download links pin the selected logical version`() = runTest {
        for (version in listOf(6, 7)) {
            val selected = metadata.copy(version = version)
            val signed = storage.getSignedDownloadUrl(storage.getPath(selected), null, selected, null, false)
            assertEquals(metadata.id.toString(), query(signed.url)["id"])
            assertEquals(version.toString(), query(signed.url)["version"])
            assertTrue(signer.verify(URI.create(signed.url)))
        }
    }

    @Test
    fun `supplementary download links retain logical version and filename selection`() = runTest {
        val supplementary = UUID.random()
        val signed = storage.getSignedDownloadUrl(storage.getPath(metadata, supplementary), null, metadata, supplementary, true)
        assertEquals("6", query(signed.url)["version"])
        assertEquals(supplementary.toString(), query(signed.url)["supplementaryId"])
        assertEquals("true", query(signed.url)["filename"])
        assertTrue(signer.verify(URI.create(signed.url)))
    }

    @Test
    fun `download signatures reject changed or removed logical versions`() = runTest {
        val signed = storage.getSignedDownloadUrl(storage.getPath(metadata), null, metadata, null, false)
        assertTrue(signer.verify(URI.create(signed.url)))
        assertFalse(signer.verify(URI.create(signed.url.replace("version=6", "version=7"))))
        assertFalse(signer.verify(URI.create(signed.url.replace("&version=6", ""))))
    }
}
