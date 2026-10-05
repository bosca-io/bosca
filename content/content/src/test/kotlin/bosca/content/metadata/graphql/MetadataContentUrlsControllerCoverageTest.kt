package bosca.content.metadata.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataContentUrls
import bosca.content.metadata.model.MetadataType
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.SignedUrl
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetadataContentUrlsControllerCoverageTest {

    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val objects = mockk<ObjectStorageService>()

    private val controller = MetadataContentUrlsController(permissionEvaluator, objects)
    private val authentication = mockk<AuthenticationContext>()

    private fun metadata() = Metadata(
        id = UUID.random(),
        name = "Test",
        type = MetadataType.STANDARD,
        contentType = "application/json",
        contentLength = 42,
        languageTag = "en",
        workflowStateId = "published"
    )

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    // ---- upload ----

    @Test
    fun `upload returns null when edit not allowed`() = runTest {
        val urls = MetadataContentUrls(metadata())
        coEvery {
            permissionEvaluator.isContentAllowed(authentication, urls.metadata, PermissionAction.EDIT)
        } returns false

        assertNull(controller.upload(authentication, urls))

        coVerify(exactly = 0) { objects.getPath(any<Metadata>(), any()) }
    }

    @Test
    fun `upload returns null when authentication is null`() = runTest {
        val urls = MetadataContentUrls(metadata())
        coEvery {
            permissionEvaluator.isContentAllowed(null, urls.metadata, PermissionAction.EDIT)
        } returns true

        assertNull(controller.upload(null, urls))

        coVerify(exactly = 0) { objects.getPath(any<Metadata>(), any()) }
    }

    @Test
    fun `upload returns null when principal is null`() = runTest {
        val urls = MetadataContentUrls(metadata())
        coEvery {
            permissionEvaluator.isContentAllowed(authentication, urls.metadata, PermissionAction.EDIT)
        } returns true
        every { authentication.principal() } returns null

        assertNull(controller.upload(authentication, urls))

        coVerify(exactly = 0) { objects.getPath(any<Metadata>(), any()) }
    }

    @Test
    fun `upload returns signed url when allowed and principal present`() = runTest {
        val urls = MetadataContentUrls(metadata())
        val principal = mockk<AuthenticatedPrincipal>()
        val path = mockk<ObjectPath>()
        val signed = SignedUrl(url = "https://upload", headers = emptyList())

        coEvery {
            permissionEvaluator.isContentAllowed(authentication, urls.metadata, PermissionAction.EDIT)
        } returns true
        every { authentication.principal() } returns principal
        coEvery { objects.getPath(urls.metadata) } returns path
        coEvery { objects.getSignedUploadUrl(path, principal, urls.metadata, null) } returns signed

        assertEquals(signed, controller.upload(authentication, urls))
    }

    // ---- download ----

    @Test
    fun `download returns null when view not allowed`() = runTest {
        val urls = MetadataContentUrls(metadata())
        coEvery {
            permissionEvaluator.isContentAllowed(authentication, urls.metadata, PermissionAction.VIEW)
        } returns false

        assertNull(controller.download(authentication, urls, null))

        coVerify(exactly = 0) { objects.getPath(any<Metadata>(), any()) }
    }

    @Test
    fun `download returns signed url with null principal when authentication is null`() = runTest {
        val urls = MetadataContentUrls(metadata())
        val path = mockk<ObjectPath>()
        val signed = SignedUrl(url = "https://download", headers = emptyList())

        coEvery {
            permissionEvaluator.isContentAllowed(null, urls.metadata, PermissionAction.VIEW)
        } returns true
        coEvery { objects.getPath(urls.metadata) } returns path
        coEvery { objects.getSignedDownloadUrl(path, null, urls.metadata, null, true) } returns signed

        assertEquals(signed, controller.download(null, urls, null))
    }

    @Test
    fun `download passes filename true when filename is null`() = runTest {
        val urls = MetadataContentUrls(metadata())
        val principal = mockk<AuthenticatedPrincipal>()
        val path = mockk<ObjectPath>()
        val signed = SignedUrl(url = "https://download", headers = emptyList())

        coEvery {
            permissionEvaluator.isContentAllowed(authentication, urls.metadata, PermissionAction.VIEW)
        } returns true
        coEvery { objects.getPath(urls.metadata) } returns path
        every { authentication.principal() } returns principal
        coEvery { objects.getSignedDownloadUrl(path, principal, urls.metadata, null, true) } returns signed

        assertEquals(signed, controller.download(authentication, urls, null))

        coVerify { objects.getSignedDownloadUrl(path, principal, urls.metadata, null, true) }
    }

    @Test
    fun `download passes filename false when filename is false`() = runTest {
        val urls = MetadataContentUrls(metadata())
        val principal = mockk<AuthenticatedPrincipal>()
        val path = mockk<ObjectPath>()
        val signed = SignedUrl(url = "https://download", headers = emptyList())

        coEvery {
            permissionEvaluator.isContentAllowed(authentication, urls.metadata, PermissionAction.VIEW)
        } returns true
        coEvery { objects.getPath(urls.metadata) } returns path
        every { authentication.principal() } returns principal
        coEvery { objects.getSignedDownloadUrl(path, principal, urls.metadata, null, false) } returns signed

        assertEquals(signed, controller.download(authentication, urls, false))

        coVerify { objects.getSignedDownloadUrl(path, principal, urls.metadata, null, false) }
    }

    @Test
    fun `download passes filename true when filename is true`() = runTest {
        val urls = MetadataContentUrls(metadata())
        val principal = mockk<AuthenticatedPrincipal>()
        val path = mockk<ObjectPath>()
        val signed = SignedUrl(url = "https://download", headers = emptyList())

        coEvery {
            permissionEvaluator.isContentAllowed(authentication, urls.metadata, PermissionAction.VIEW)
        } returns true
        coEvery { objects.getPath(urls.metadata) } returns path
        every { authentication.principal() } returns principal
        coEvery { objects.getSignedDownloadUrl(path, principal, urls.metadata, null, true) } returns signed

        assertEquals(signed, controller.download(authentication, urls, true))

        coVerify { objects.getSignedDownloadUrl(path, principal, urls.metadata, null, true) }
    }
}
