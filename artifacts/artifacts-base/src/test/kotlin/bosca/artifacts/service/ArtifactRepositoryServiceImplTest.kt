package bosca.artifacts.service

import bosca.artifacts.model.ArtifactNamespace
import bosca.artifacts.model.ArtifactRepository
import bosca.artifacts.model.ArtifactType
import bosca.artifacts.model.ArtifactVersion
import bosca.artifacts.model.ArtifactVersionBlob
import bosca.artifacts.model.UploadSession
import bosca.artifacts.model.UploadChunkPath
import bosca.artifacts.model.UploadSessionPath
import bosca.artifacts.repository.ArtifactRepoRepository
import bosca.artifacts.repository.NamespacePermissionRepository
import bosca.artifacts.repository.NamespaceRepository
import bosca.artifacts.repository.TagRepository
import bosca.artifacts.repository.UploadSessionRepository
import bosca.artifacts.repository.VersionRepository
import bosca.db.transaction
import bosca.storage.service.ObjectStorageService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class ArtifactRepositoryServiceImplTest {

    private val namespaceRepo = mockk<NamespaceRepository>()
    private val namespacePermissionRepo = mockk<NamespacePermissionRepository>()
    private val repoRepo = mockk<ArtifactRepoRepository>()
    private val versionRepo = mockk<VersionRepository>()
    private val tagRepo = mockk<TagRepository>()
    private val uploadSessionRepo = mockk<UploadSessionRepository>()
    private val blobStorage = mockk<BlobStorageService>()
    private val objectStorage = mockk<ObjectStorageService>()

    private val pubSubService = mockk<bosca.pubsub.PubSubService>(relaxed = true)

    private val service = ArtifactRepositoryServiceImpl(
        namespaceRepo, namespacePermissionRepo, repoRepo, versionRepo, tagRepo, uploadSessionRepo, blobStorage,
        objectStorage, pubSubService,
    )

    private val now = OffsetDateTime.now()
    private val nsId = Uuid.random()
    private val repoId = Uuid.random()
    private val versionId = Uuid.random()

    private val namespace = ArtifactNamespace(id = nsId, name = "my-namespace", public = false, created = now)
    private val repo = ArtifactRepository(id = repoId, namespaceId = nsId, name = "my-repo", type = "docker", created = now, modified = now)
    private val version = ArtifactVersion(id = versionId, repositoryId = repoId, version = "1.0.0", created = now)

    @BeforeTest
    fun setUp() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    // -- findOrCreateRepository: creates namespace and repo on first call --

    @Test
    fun `findOrCreateRepository creates namespace and repo when neither exists`() = runTest {
        coEvery { namespaceRepo.findByName("my-namespace") } returns null
        coEvery { namespaceRepo.create("my-namespace", false) } returns namespace
        coEvery { repoRepo.findByCoordinates(nsId, "my-repo", "docker") } returns null
        coEvery { repoRepo.create(nsId, "my-repo", "docker") } returns repo

        val result = service.findOrCreateRepository("my-namespace", "my-repo", ArtifactType.DOCKER)

        assertEquals(repo, result)
        coVerify(exactly = 1) { namespaceRepo.create("my-namespace", false) }
        coVerify(exactly = 1) { repoRepo.create(nsId, "my-repo", "docker") }
    }

    // -- findOrCreateRepository: returns existing on retry after concurrent creation --

    @Test
    fun `findOrCreateRepository returns existing repo after namespace creation conflict`() = runTest {
        coEvery { namespaceRepo.findByName("my-namespace") } returns null andThen namespace
        coEvery { namespaceRepo.create("my-namespace", false) } throws RuntimeException("unique constraint violation")
        coEvery { repoRepo.findByCoordinates(nsId, "my-repo", "docker") } returns repo

        val result = service.findOrCreateRepository("my-namespace", "my-repo", ArtifactType.DOCKER)

        assertEquals(repo, result)
    }

    // -- findOrCreateRepository: preserves original exception when retry also fails --

    @Test
    fun `findOrCreateRepository preserves original exception when retry lookup also fails`() = runTest {
        val original = RuntimeException("DB connection lost")
        coEvery { namespaceRepo.findByName("my-namespace") } returns null andThen null
        coEvery { namespaceRepo.create("my-namespace", false) } throws original

        val thrown = assertFailsWith<IllegalStateException> {
            service.findOrCreateRepository("my-namespace", "my-repo", ArtifactType.DOCKER)
        }

        assertEquals(original, thrown.cause)
    }

    // -- addVersionBlob: increments ref count only on first association --

    @Test
    fun `addVersionBlob increments ref count when association is new`() = runTest {
        val digest = "sha256:abc123"
        val newBlob = ArtifactVersionBlob(versionId = versionId, digest = digest, role = "layer")
        coEvery { versionRepo.addVersionBlob(versionId, digest, "layer", null, null) } returns newBlob
        coEvery { blobStorage.incrementRefCount(digest) } returns mockk()

        service.addVersionBlob(versionId, digest, "layer", null, null)

        coVerify(exactly = 1) { blobStorage.incrementRefCount(digest) }
    }

    // -- addVersionBlob: does NOT increment ref count on duplicate --

    @Test
    fun `addVersionBlob does not increment ref count when association already exists`() = runTest {
        val digest = "sha256:abc123"
        // ON CONFLICT DO NOTHING RETURNING * returns null when the row already exists
        coEvery { versionRepo.addVersionBlob(versionId, digest, "layer", "file.tar", "application/gzip") } returns null

        service.addVersionBlob(versionId, digest, "layer", "file.tar", "application/gzip")

        coVerify(exactly = 0) { blobStorage.incrementRefCount(any()) }
    }

    // -- addOrReplaceVersionBlob: replaces a same-filename blob with new content --

    @Test
    fun `addOrReplaceVersionBlob detaches the stale same-filename blob and attaches the new one`() = runTest {
        val oldDigest = "sha256:old"
        val newDigest = "sha256:new"
        val stale = ArtifactVersionBlob(versionId = versionId, digest = oldDigest, role = "file", filename = "install.sh")
        val inserted = ArtifactVersionBlob(versionId = versionId, digest = newDigest, role = "file", filename = "install.sh")
        coEvery { versionRepo.getVersionBlobs(versionId) } returns listOf(stale)
        coEvery { versionRepo.deleteVersionBlobsByFilenameAndRole(versionId, "install.sh", "file") } returns Unit
        coEvery { versionRepo.addVersionBlob(versionId, newDigest, "file", "install.sh", "text/x-shellscript") } returns inserted
        coEvery { blobStorage.incrementRefCount(newDigest) } returns mockk()
        coEvery { blobStorage.decrementRefCount(oldDigest) } returns mockk()
        coEvery { blobStorage.deleteIfUnreferenced(oldDigest) } returns true

        service.addOrReplaceVersionBlob(versionId, newDigest, "file", "install.sh", "text/x-shellscript")

        coVerify(exactly = 1) { versionRepo.deleteVersionBlobsByFilenameAndRole(versionId, "install.sh", "file") }
        coVerify(exactly = 1) { blobStorage.incrementRefCount(newDigest) }
        coVerify(exactly = 1) { blobStorage.decrementRefCount(oldDigest) }
        coVerify(exactly = 1) { blobStorage.deleteIfUnreferenced(oldDigest) }
    }

    @Test
    fun `addOrReplaceVersionBlob does not delete or decrement when no stale blob exists`() = runTest {
        val digest = "sha256:new"
        val inserted = ArtifactVersionBlob(versionId = versionId, digest = digest, role = "file", filename = "install.sh")
        coEvery { versionRepo.getVersionBlobs(versionId) } returns emptyList()
        coEvery { versionRepo.addVersionBlob(versionId, digest, "file", "install.sh", "text/x-shellscript") } returns inserted
        coEvery { blobStorage.incrementRefCount(digest) } returns mockk()

        service.addOrReplaceVersionBlob(versionId, digest, "file", "install.sh", "text/x-shellscript")

        coVerify(exactly = 0) { versionRepo.deleteVersionBlobsByFilenameAndRole(any(), any(), any()) }
        coVerify(exactly = 0) { blobStorage.decrementRefCount(any()) }
        coVerify(exactly = 1) { blobStorage.incrementRefCount(digest) }
    }

    @Test
    fun `addOrReplaceVersionBlob is idempotent when re-pushing identical content`() = runTest {
        val digest = "sha256:same"
        val existing = ArtifactVersionBlob(versionId = versionId, digest = digest, role = "file", filename = "install.sh")
        coEvery { versionRepo.getVersionBlobs(versionId) } returns listOf(existing)
        // Same digest is not "stale" (only a different digest is), so no delete; and
        // the re-insert no-ops via ON CONFLICT, returning null → no ref-count change.
        coEvery { versionRepo.addVersionBlob(versionId, digest, "file", "install.sh", "text/x-shellscript") } returns null

        service.addOrReplaceVersionBlob(versionId, digest, "file", "install.sh", "text/x-shellscript")

        coVerify(exactly = 0) { versionRepo.deleteVersionBlobsByFilenameAndRole(any(), any(), any()) }
        coVerify(exactly = 0) { blobStorage.incrementRefCount(any()) }
        coVerify(exactly = 0) { blobStorage.decrementRefCount(any()) }
    }

    // -- updateNamespacePublic: delegates to the repository --

    @Test
    fun `updateNamespacePublic flips the public flag and returns the updated namespace`() = runTest {
        val updated = namespace.copy(public = true)
        coEvery { namespaceRepo.updatePublic(nsId, true) } returns updated

        val result = service.updateNamespacePublic(nsId, true)

        assertEquals(true, result?.public)
        coVerify(exactly = 1) { namespaceRepo.updatePublic(nsId, true) }
    }

    @Test
    fun `updateNamespacePublic returns null when the namespace does not exist`() = runTest {
        coEvery { namespaceRepo.updatePublic(nsId, true) } returns null

        assertEquals(null, service.updateNamespacePublic(nsId, true))
    }

    // -- deleteVersion: decrements ref counts and deletes unreferenced blobs --

    @Test
    fun `deleteVersion decrements ref counts and deletes unreferenced blobs`() = runTest {
        val blob1 = ArtifactVersionBlob(versionId = versionId, digest = "sha256:aaa", role = "layer")
        val blob2 = ArtifactVersionBlob(versionId = versionId, digest = "sha256:bbb", role = "config")
        coEvery { versionRepo.getVersionBlobs(versionId) } returns listOf(blob1, blob2)
        coEvery { versionRepo.deleteVersionBlobs(versionId) } returns Unit
        coEvery { versionRepo.delete(versionId) } returns Unit
        coEvery { blobStorage.decrementRefCount("sha256:aaa") } returns mockk()
        coEvery { blobStorage.deleteIfUnreferenced("sha256:aaa") } returns true
        coEvery { blobStorage.decrementRefCount("sha256:bbb") } returns mockk()
        coEvery { blobStorage.deleteIfUnreferenced("sha256:bbb") } returns true

        service.deleteVersion(versionId)

        coVerify(exactly = 1) { blobStorage.decrementRefCount("sha256:aaa") }
        coVerify(exactly = 1) { blobStorage.deleteIfUnreferenced("sha256:aaa") }
        coVerify(exactly = 1) { blobStorage.decrementRefCount("sha256:bbb") }
        coVerify(exactly = 1) { blobStorage.deleteIfUnreferenced("sha256:bbb") }
    }

    // -- deleteVersion: continues processing remaining blobs when one fails --

    @Test
    fun `deleteVersion continues processing remaining blobs when one fails`() = runTest {
        val blob1 = ArtifactVersionBlob(versionId = versionId, digest = "sha256:aaa", role = "layer")
        val blob2 = ArtifactVersionBlob(versionId = versionId, digest = "sha256:bbb", role = "config")
        coEvery { versionRepo.getVersionBlobs(versionId) } returns listOf(blob1, blob2)
        coEvery { versionRepo.deleteVersionBlobs(versionId) } returns Unit
        coEvery { versionRepo.delete(versionId) } returns Unit
        coEvery { blobStorage.decrementRefCount("sha256:aaa") } throws RuntimeException("storage error")
        coEvery { blobStorage.decrementRefCount("sha256:bbb") } returns mockk()
        coEvery { blobStorage.deleteIfUnreferenced("sha256:bbb") } returns true

        service.deleteVersion(versionId)

        // First blob failed, but second blob was still processed
        coVerify(exactly = 1) { blobStorage.decrementRefCount("sha256:bbb") }
        coVerify(exactly = 1) { blobStorage.deleteIfUnreferenced("sha256:bbb") }
    }

    // -- createNamespace: validates name --

    @Test
    fun `createNamespace rejects blank name`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.createNamespace("   ", false)
        }
    }

    @Test
    fun `createNamespace rejects name with path traversal`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.createNamespace("my..namespace", false)
        }
    }

    @Test
    fun `createNamespace rejects name with invalid characters`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.createNamespace("my namespace!", false)
        }
    }

    @Test
    fun `findOrCreateRepository rejects blank namespace name`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.findOrCreateRepository("", "my-repo", ArtifactType.DOCKER)
        }
    }

    @Test
    fun `findOrCreateRepository rejects blank repository name`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.findOrCreateRepository("my-namespace", "", ArtifactType.DOCKER)
        }
    }

    // -- deleteRepository: cascades through all versions before deleting repo --

    @Test
    fun `deleteRepository deletes all versions then deletes the repository`() = runTest {
        val v1Id = Uuid.random()
        val v2Id = Uuid.random()
        val v1 = ArtifactVersion(id = v1Id, repositoryId = repoId, version = "1.0.0", created = now)
        val v2 = ArtifactVersion(id = v2Id, repositoryId = repoId, version = "2.0.0", created = now)
        // After deleting all versions, the second call at offset 0 returns empty
        coEvery { versionRepo.listByRepositoryPaged(repoId, 100, 0) } returnsMany listOf(listOf(v1, v2), emptyList())
        coEvery { versionRepo.getVersionBlobs(v1Id) } returns emptyList()
        coEvery { versionRepo.getVersionBlobs(v2Id) } returns emptyList()
        coEvery { versionRepo.deleteVersionBlobs(v1Id) } returns Unit
        coEvery { versionRepo.deleteVersionBlobs(v2Id) } returns Unit
        coEvery { versionRepo.delete(v1Id) } returns Unit
        coEvery { versionRepo.delete(v2Id) } returns Unit
        coEvery { repoRepo.delete(repoId) } returns Unit

        service.deleteRepository(repoId)

        coVerify(exactly = 1) { versionRepo.delete(v1Id) }
        coVerify(exactly = 1) { versionRepo.delete(v2Id) }
        coVerify(exactly = 1) { repoRepo.delete(repoId) }
    }

    // -- deleteNamespace: cascades through all repos --

    @Test
    fun `deleteNamespace deletes all repositories then deletes the namespace`() = runTest {
        val repo1Id = Uuid.random()
        val repo2Id = Uuid.random()
        val r1 = ArtifactRepository(id = repo1Id, namespaceId = nsId, name = "repo-a", type = "docker", created = now, modified = now)
        val r2 = ArtifactRepository(id = repo2Id, namespaceId = nsId, name = "repo-b", type = "maven", created = now, modified = now)
        coEvery { repoRepo.listByNamespace(nsId) } returns listOf(r1, r2)
        coEvery { versionRepo.listByRepositoryPaged(repo1Id, 100, 0) } returns emptyList()
        coEvery { versionRepo.listByRepositoryPaged(repo2Id, 100, 0) } returns emptyList()
        coEvery { repoRepo.delete(repo1Id) } returns Unit
        coEvery { repoRepo.delete(repo2Id) } returns Unit
        coEvery { namespaceRepo.delete(nsId) } returns Unit

        service.deleteNamespace(nsId)

        coVerify(exactly = 1) { repoRepo.delete(repo1Id) }
        coVerify(exactly = 1) { repoRepo.delete(repo2Id) }
        coVerify(exactly = 1) { namespaceRepo.delete(nsId) }
    }

    // -- paged listings and counts --

    @Test
    fun `paged listRepositories without type uses the namespace-wide paged query`() = runTest {
        coEvery { repoRepo.listByNamespacePaged(nsId, 25, 50) } returns listOf(repo)

        val result = service.listRepositories(nsId, null, 25, 50)

        assertEquals(listOf(repo), result)
    }

    @Test
    fun `paged listRepositories with type uses the type-filtered paged query`() = runTest {
        coEvery { repoRepo.listByNamespaceAndTypePaged(nsId, "docker", 25, 0) } returns listOf(repo)

        val result = service.listRepositories(nsId, ArtifactType.DOCKER, 25, 0)

        assertEquals(listOf(repo), result)
    }

    @Test
    fun `countRepositories without type counts the whole namespace`() = runTest {
        coEvery { repoRepo.countByNamespace(nsId) } returns 7L

        assertEquals(7L, service.countRepositories(nsId))
    }

    @Test
    fun `countRepositories with type counts only that type`() = runTest {
        coEvery { repoRepo.countByNamespaceAndType(nsId, "ml") } returns 2L

        assertEquals(2L, service.countRepositories(nsId, ArtifactType.ML))
    }

    @Test
    fun `countVersions delegates to the version repository`() = runTest {
        coEvery { versionRepo.countByRepository(repoId) } returns 3L

        assertEquals(3L, service.countVersions(repoId))
    }

    @Test
    fun `listTagsPaged uses offset pagination`() = runTest {
        val tag = bosca.artifacts.model.ArtifactTag(
            id = Uuid.random(), repositoryId = repoId, name = "latest",
            manifestDigest = "sha256:abc", created = now, modified = now,
        )
        coEvery { tagRepo.listByRepositoryPaged(repoId, 25, 25) } returns listOf(tag)

        assertEquals(listOf(tag), service.listTagsPaged(repoId, 25, 25))
    }

    @Test
    fun `countTags delegates to the tag repository`() = runTest {
        coEvery { tagRepo.countByRepository(repoId) } returns 4L

        assertEquals(4L, service.countTags(repoId))
    }

    @Test
    fun `createUploadSession initializes an object storage multipart upload`() = runTest {
        val session = UploadSession(id = Uuid.random(), repositoryId = repoId)
        val initialized = session.copy(storageUploadId = "storage-upload")
        coEvery { uploadSessionRepo.create(repoId) } returns session
        coEvery { objectStorage.createMultipartUpload(any<UploadSessionPath>()) } returns "storage-upload"
        coEvery { uploadSessionRepo.initializeStorageUpload(session.id, "storage-upload") } returns initialized

        assertEquals(initialized, service.createUploadSession(repoId))

        coVerifyOrder {
            uploadSessionRepo.create(repoId)
            objectStorage.createMultipartUpload(match { it.toString() == "artifacts/uploads/${session.id}" })
            uploadSessionRepo.initializeStorageUpload(session.id, "storage-upload")
        }
    }

    @Test
    fun `createUploadSession cancels database session when object storage initialization fails`() = runTest {
        val session = UploadSession(id = Uuid.random(), repositoryId = repoId)
        val failure = IllegalStateException("storage unavailable")
        coEvery { uploadSessionRepo.create(repoId) } returns session
        coEvery { objectStorage.createMultipartUpload(any<UploadSessionPath>()) } throws failure
        coEvery { uploadSessionRepo.cancel(session.id) } returns session

        assertEquals(failure, assertFailsWith<IllegalStateException> { service.createUploadSession(repoId) })

        coVerify(exactly = 1) { uploadSessionRepo.cancel(session.id) }
        coVerify(exactly = 0) { uploadSessionRepo.initializeStorageUpload(any(), any()) }
    }

    @Test
    fun `updateUploadSessionOffset persists byte offset and digest state`() = runTest {
        val sessionId = Uuid.random()
        val digestState = byteArrayOf(1, 2, 3)
        val session = UploadSession(id = sessionId, repositoryId = repoId, byteOffset = 42, digestState = digestState)
        coEvery { uploadSessionRepo.updateOffset(sessionId, 42, digestState) } returns session

        service.updateUploadSessionOffset(sessionId, 42, digestState)

        coVerify(exactly = 1) { uploadSessionRepo.updateOffset(sessionId, 42, digestState) }
    }

    @Test
    fun `cancelUploadSession aborts multipart data before cancelling database session`() = runTest {
        val sessionId = Uuid.random()
        val session = UploadSession(
            id = sessionId,
            repositoryId = repoId,
            chunkCount = 2,
            storageUploadId = "storage-upload",
        )
        coEvery { uploadSessionRepo.findActiveIncludingExpired(sessionId) } returns session
        coEvery { objectStorage.abortMultipartUpload(any<UploadSessionPath>(), "storage-upload", 2) } returns Unit
        coEvery { objectStorage.abortMultipartUploadsAtPath(any<UploadChunkPath>()) } returns 1
        coEvery { objectStorage.delete(any<UploadChunkPath>()) } returns Unit
        coEvery { uploadSessionRepo.cancel(sessionId) } returns session.copy(state = bosca.artifacts.model.UploadSessionState.CANCELLED)

        service.cancelUploadSession(sessionId)

        coVerifyOrder {
            objectStorage.abortMultipartUpload(match { it.toString() == "artifacts/uploads/$sessionId" }, "storage-upload", 2)
            objectStorage.abortMultipartUploadsAtPath(match { it.toString() == "artifacts/uploads/$sessionId/2" })
            objectStorage.delete(match { it.toString() == "artifacts/uploads/$sessionId/2" })
            uploadSessionRepo.cancel(sessionId)
        }
    }

    @Test
    fun `cancelUploadSession discovers final multipart upload when its id was not persisted`() = runTest {
        val sessionId = Uuid.random()
        val session = UploadSession(id = sessionId, repositoryId = repoId)
        coEvery { uploadSessionRepo.findActiveIncludingExpired(sessionId) } returns session
        coEvery { objectStorage.abortMultipartUploadsAtPath(any<UploadSessionPath>()) } returns 1
        coEvery { uploadSessionRepo.cancel(sessionId) } returns
            session.copy(state = bosca.artifacts.model.UploadSessionState.CANCELLED)

        service.cancelUploadSession(sessionId)

        coVerifyOrder {
            objectStorage.abortMultipartUploadsAtPath(match { it.toString() == "artifacts/uploads/$sessionId" })
            uploadSessionRepo.cancel(sessionId)
        }
    }
}
