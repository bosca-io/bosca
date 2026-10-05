package bosca.content.collection.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionSupplementary
import bosca.content.collection.model.CollectionSupplementaryContentUrls
import bosca.content.collection.model.CollectionType
import bosca.content.security.CollectionPermissionEvaluator
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.SignedUrl
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectionSupplementaryContentUrlsControllerCoverageTest {

    private val permissionEvaluator = mockk<CollectionPermissionEvaluator>()
    private val storage = mockk<ObjectStorageService>()

    private val controller = CollectionSupplementaryContentUrlsController(permissionEvaluator, storage)

    private val authentication = mockk<AuthenticationContext>()

    private fun createCollection(id: UUID = UUID.random()) = Collection(
        id = id,
        name = "Test Collection",
        languageTag = "en",
        type = CollectionType.STANDARD,
        workflowStateId = "published"
    )

    private fun createSupplementary(
        id: UUID = UUID.random(),
        collectionId: UUID = UUID.random()
    ) = CollectionSupplementary(
        id = id,
        collectionId = collectionId,
        key = "test-key",
        name = "Test Supplementary",
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        sourceId = null,
        sourceIdentifier = null
    )

    private fun createUrls(
        collection: Collection = createCollection(),
        supplementary: CollectionSupplementary = createSupplementary()
    ) = CollectionSupplementaryContentUrls(collection = collection, supplementary = supplementary)

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    // ---- download ----

    @Test
    fun `download returns null when permission denied`() = runTest {
        val urls = createUrls()
        coEvery {
            permissionEvaluator.isSupplementaryAllowed(authentication, urls.collection, PermissionAction.VIEW)
        } returns false

        assertNull(controller.download(authentication, urls))
    }

    @Test
    fun `download returns signed url when allowed with principal`() = runTest {
        val urls = createUrls()
        val principal = mockk<AuthenticatedPrincipal>()
        val path = mockk<ObjectPath>()
        val signed = SignedUrl(url = "https://download", headers = emptyList())

        coEvery {
            permissionEvaluator.isSupplementaryAllowed(authentication, urls.collection, PermissionAction.VIEW)
        } returns true
        every { authentication.principal() } returns principal
        coEvery { storage.getPath(urls.collection, urls.supplementary.id) } returns path
        coEvery {
            storage.getSignedDownloadUrl(path, principal, urls.collection, urls.supplementary.id)
        } returns signed

        assertEquals(signed, controller.download(authentication, urls))
    }

    @Test
    fun `download returns signed url when allowed with null authorization`() = runTest {
        val urls = createUrls()
        val path = mockk<ObjectPath>()
        val signed = SignedUrl(url = "https://download-anon", headers = emptyList())

        coEvery {
            permissionEvaluator.isSupplementaryAllowed(null, urls.collection, PermissionAction.VIEW)
        } returns true
        coEvery { storage.getPath(urls.collection, urls.supplementary.id) } returns path
        coEvery {
            storage.getSignedDownloadUrl(path, null, urls.collection, urls.supplementary.id)
        } returns signed

        assertEquals(signed, controller.download(null, urls))
    }

    // ---- upload ----

    @Test
    fun `upload returns null when permission denied`() = runTest {
        val urls = createUrls()
        coEvery {
            permissionEvaluator.isSupplementaryAllowed(authentication, urls.collection, PermissionAction.EDIT)
        } returns false

        assertNull(controller.upload(authentication, urls))
    }

    @Test
    fun `upload returns null when allowed but principal is null`() = runTest {
        val urls = createUrls()
        coEvery {
            permissionEvaluator.isSupplementaryAllowed(authentication, urls.collection, PermissionAction.EDIT)
        } returns true
        every { authentication.principal() } returns null

        assertNull(controller.upload(authentication, urls))
    }

    @Test
    fun `upload returns null when allowed but authorization is null`() = runTest {
        val urls = createUrls()
        coEvery {
            permissionEvaluator.isSupplementaryAllowed(null, urls.collection, PermissionAction.EDIT)
        } returns true

        assertNull(controller.upload(null, urls))
    }

    @Test
    fun `upload returns signed url when allowed with principal`() = runTest {
        val urls = createUrls()
        val principal = mockk<AuthenticatedPrincipal>()
        val path = mockk<ObjectPath>()
        val signed = SignedUrl(url = "https://upload", headers = emptyList())

        coEvery {
            permissionEvaluator.isSupplementaryAllowed(authentication, urls.collection, PermissionAction.EDIT)
        } returns true
        every { authentication.principal() } returns principal
        coEvery { storage.getPath(urls.collection, urls.supplementary.id) } returns path
        coEvery {
            storage.getSignedUploadUrl(path, principal, urls.collection, urls.supplementary.id)
        } returns signed

        assertEquals(signed, controller.upload(authentication, urls))
    }
}
