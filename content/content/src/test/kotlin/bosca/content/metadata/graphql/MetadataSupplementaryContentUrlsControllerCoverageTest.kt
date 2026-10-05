package bosca.content.metadata.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataSupplementary
import bosca.content.metadata.model.MetadataSupplementaryContentUrls
import bosca.content.metadata.model.MetadataType
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.SignedUrl
import io.mockk.coEvery
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class MetadataSupplementaryContentUrlsControllerCoverageTest {

    private val securityService = mockk<SecurityService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val storage = mockk<ObjectStorageService>()

    private val controller = MetadataSupplementaryContentUrlsController(securityService, permissionEvaluator, storage)

    private val authentication = mockk<AuthenticationContext>()
    private val principal = mockk<AuthenticatedPrincipal>()
    private val path = mockk<ObjectPath>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun createMetadata(id: UUID = UUID.random()) = Metadata(
        id = id,
        name = "Test",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "published"
    )

    private fun createSupplementary(id: UUID = UUID.random(), metadataId: UUID = UUID.random()) = MetadataSupplementary(
        id = id,
        metadataId = metadataId,
        key = "audio",
        name = "Audio File",
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        sourceId = null,
        sourceIdentifier = null
    )

    private fun createUrls(): MetadataSupplementaryContentUrls {
        val metadataId = UUID.random()
        return MetadataSupplementaryContentUrls(
            metadata = createMetadata(id = metadataId),
            supplementary = createSupplementary(metadataId = metadataId)
        )
    }

    // ---- download ----

    @Test
    fun `download returns null when view permission is denied`() = runTest {
        val urls = createUrls()
        coEvery {
            permissionEvaluator.isSupplementaryAllowed(authentication, urls.metadata, PermissionAction.VIEW)
        } returns false

        assertNull(controller.download(authentication, urls, null))
    }

    @Test
    fun `download returns null when authorization is null`() = runTest {
        val urls = createUrls()
        coEvery {
            permissionEvaluator.isSupplementaryAllowed(null, urls.metadata, PermissionAction.VIEW)
        } returns true

        assertNull(controller.download(null, urls, true))
    }

    @Test
    fun `download returns null when principal is null`() = runTest {
        val urls = createUrls()
        coEvery {
            permissionEvaluator.isSupplementaryAllowed(authentication, urls.metadata, PermissionAction.VIEW)
        } returns true
        every { authentication.principal() } returns null

        assertNull(controller.download(authentication, urls, true))
    }

    @Test
    fun `download returns signed url with explicit filename false`() = runTest {
        val urls = createUrls()
        val expected = SignedUrl(url = "https://download/false", headers = emptyList())
        coEvery {
            permissionEvaluator.isSupplementaryAllowed(authentication, urls.metadata, PermissionAction.VIEW)
        } returns true
        every { authentication.principal() } returns principal
        coEvery { storage.getPath(urls.metadata, urls.supplementary.id) } returns path
        coEvery {
            storage.getSignedDownloadUrl(path, principal, urls.metadata, urls.supplementary.id, false)
        } returns expected

        assertSame(expected, controller.download(authentication, urls, false))
    }

    @Test
    fun `download defaults filename to true when null`() = runTest {
        val urls = createUrls()
        val expected = SignedUrl(url = "https://download/default", headers = emptyList())
        coEvery {
            permissionEvaluator.isSupplementaryAllowed(authentication, urls.metadata, PermissionAction.VIEW)
        } returns true
        every { authentication.principal() } returns principal
        coEvery { storage.getPath(urls.metadata, urls.supplementary.id) } returns path
        coEvery {
            storage.getSignedDownloadUrl(path, principal, urls.metadata, urls.supplementary.id, true)
        } returns expected

        assertSame(expected, controller.download(authentication, urls, null))
    }

    // ---- upload ----

    @Test
    fun `upload returns null when edit permission is denied`() = runTest {
        val urls = createUrls()
        coEvery {
            permissionEvaluator.isSupplementaryAllowed(authentication, urls.metadata, PermissionAction.EDIT)
        } returns false

        assertNull(controller.upload(authentication, urls))
    }

    @Test
    fun `upload returns null when authorization is null`() = runTest {
        val urls = createUrls()
        coEvery {
            permissionEvaluator.isSupplementaryAllowed(null, urls.metadata, PermissionAction.EDIT)
        } returns true

        assertNull(controller.upload(null, urls))
    }

    @Test
    fun `upload returns null when principal is null`() = runTest {
        val urls = createUrls()
        coEvery {
            permissionEvaluator.isSupplementaryAllowed(authentication, urls.metadata, PermissionAction.EDIT)
        } returns true
        every { authentication.principal() } returns null

        assertNull(controller.upload(authentication, urls))
    }

    @Test
    fun `upload returns signed url on happy path`() = runTest {
        val urls = createUrls()
        val expected = SignedUrl(url = "https://upload", headers = emptyList())
        coEvery {
            permissionEvaluator.isSupplementaryAllowed(authentication, urls.metadata, PermissionAction.EDIT)
        } returns true
        every { authentication.principal() } returns principal
        coEvery { storage.getPath(urls.metadata, urls.supplementary.id) } returns path
        coEvery {
            storage.getSignedUploadUrl(path, principal, urls.metadata, urls.supplementary.id)
        } returns expected

        assertSame(expected, controller.upload(authentication, urls))
    }
}
