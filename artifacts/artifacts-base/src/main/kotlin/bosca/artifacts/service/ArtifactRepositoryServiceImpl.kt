package bosca.artifacts.service

import bosca.artifacts.model.*
import bosca.artifacts.repository.*
import bosca.db.transaction
import bosca.db.afterCommit
import bosca.graphql.Batch
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.service.annotation.ServiceImplementation
import bosca.serialization.UUID
import bosca.storage.service.ObjectStorageService
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/** Maximum length for namespace and repository names. */
private const val MAX_NAME_LENGTH = 255

/** Characters allowed in namespace and repository names. */
private val VALID_NAME_PATTERN = Regex("""^[a-zA-Z0-9\-_.@]+$""")

/**
 * Validates that a namespace or repository name is well-formed.
 * Prevents path traversal, control characters, and excessively long names.
 */
private fun validateName(name: String, label: String = "name") {
    require(name.isNotBlank()) { "$label must not be blank" }
    require(name.length <= MAX_NAME_LENGTH) { "$label must not exceed $MAX_NAME_LENGTH characters" }
    require(!name.contains("..")) { "$label must not contain '..'" }
    require(VALID_NAME_PATTERN.matches(name)) { "$label contains invalid characters: only alphanumeric, '-', '_', '.', '@' are allowed" }
}

/**
 * Coordinates all artifact metadata operations across namespaces, repositories,
 * versions, tags, and upload sessions.
 *
 * This service delegates to the individual repositories for database access and
 * to [BlobStorageService] for reference count management when versions are created
 * or deleted.
 */
@ServiceImplementation
class ArtifactRepositoryServiceImpl(
    private val namespaceRepo: NamespaceRepository,
    private val namespacePermissionRepo: NamespacePermissionRepository,
    private val repoRepo: ArtifactRepoRepository,
    private val versionRepo: VersionRepository,
    private val tagRepo: TagRepository,
    private val uploadSessionRepo: UploadSessionRepository,
    private val blobStorage: BlobStorageService,
    private val objectStorage: ObjectStorageService,
    private val pubSubService: bosca.pubsub.PubSubService,
) : ArtifactRepositoryService {

    private val log = LoggerFactory.getLogger(ArtifactRepositoryServiceImpl::class.java)

    /**
     * Announces a landed version/tag on [ArtifactVersionPublished.CHANNEL] so
     * requirement-gated CI jobs re-evaluate promptly. Best-effort by design: the artifact write
     * already committed, and gated consumers have a sweep backstop — a publish failure is logged,
     * never propagated into the registry operation.
     */
    private suspend fun announcePublished(repositoryId: UUID, version: String) = afterCommit {
        try {
            val repository = repoRepo.findById(repositoryId) ?: return@afterCommit
            val namespace = namespaceRepo.findById(repository.namespaceId) ?: return@afterCommit
            pubSubService.publish(
                ArtifactVersionPublished.CHANNEL,
                ArtifactVersionPublished.serializer(),
                ArtifactVersionPublished(
                    namespace = namespace.name,
                    repository = repository.name,
                    type = repository.type,
                    version = version,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to announce published artifact {}:{} — gated consumers will rely on the sweep: {}", repositoryId, version, e.message)
        }
    }

    // -- Namespaces --

    override suspend fun getNamespaces(): List<ArtifactNamespace> = namespaceRepo.getAll()

    override suspend fun getNamespace(id: UUID): ArtifactNamespace? = namespaceRepo.findById(id)

    override suspend fun getNamespaceByName(name: String): ArtifactNamespace? = namespaceRepo.findByName(name)

    override suspend fun createNamespace(name: String, public: Boolean): ArtifactNamespace {
        validateName(name, "namespace name")
        return namespaceRepo.create(name, public)
    }

    override suspend fun updateNamespacePublic(id: UUID, public: Boolean): ArtifactNamespace? =
        namespaceRepo.updatePublic(id, public)

    // -- Repositories --

    override suspend fun getRepository(id: UUID): ArtifactRepository? = repoRepo.findById(id)

    override suspend fun findRepository(namespace: String, name: String, type: ArtifactType): ArtifactRepository? {
        val ns = namespaceRepo.findByName(namespace) ?: return null
        return repoRepo.findByCoordinates(ns.id, name, type.value)
    }

    override suspend fun findOrCreateRepository(namespace: String, name: String, type: ArtifactType): ArtifactRepository {
        validateName(namespace, "namespace name")
        validateName(name, "repository name")
        val ns = namespaceRepo.findByName(namespace) ?: try {
            namespaceRepo.create(namespace, false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Retry lookup — if this was a concurrent unique-constraint violation,
            // the namespace now exists. If the retry also fails, the original
            // exception was a real error (DB down, timeout, etc.) so we preserve it.
            log.debug("Namespace creation failed for '{}', retrying lookup", namespace, e)
            namespaceRepo.findByName(namespace)
                ?: throw IllegalStateException("namespace creation failed: $namespace", e)
        }
        return repoRepo.findByCoordinates(ns.id, name, type.value) ?: try {
            repoRepo.create(ns.id, name, type.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("Repository creation failed for '{}/{}', retrying lookup", namespace, name, e)
            repoRepo.findByCoordinates(ns.id, name, type.value)
                ?: throw IllegalStateException("repository creation failed: $namespace/$name", e)
        }
    }

    override suspend fun listRepositories(namespaceId: UUID, type: ArtifactType?): List<ArtifactRepository> {
        return if (type != null) {
            repoRepo.listByNamespaceAndType(namespaceId, type.value)
        } else {
            repoRepo.listByNamespace(namespaceId)
        }
    }

    override suspend fun listRepositories(namespaceId: UUID, type: ArtifactType?, limit: Int, offset: Long): List<ArtifactRepository> {
        return if (type != null) {
            repoRepo.listByNamespaceAndTypePaged(namespaceId, type.value, limit, offset)
        } else {
            repoRepo.listByNamespacePaged(namespaceId, limit, offset)
        }
    }

    override suspend fun countRepositories(namespaceId: UUID, type: ArtifactType?): Long {
        return if (type != null) {
            repoRepo.countByNamespaceAndType(namespaceId, type.value)
        } else {
            repoRepo.countByNamespace(namespaceId)
        }
    }

    override suspend fun listRepositoriesByType(type: ArtifactType, limit: Int, offset: Long): List<ArtifactRepository> {
        return repoRepo.listByType(type.value, limit, offset)
    }

    // -- Versions --

    override suspend fun getVersion(id: UUID): ArtifactVersion? = versionRepo.findById(id)

    override suspend fun findVersion(repositoryId: UUID, version: String): ArtifactVersion? {
        return versionRepo.findByRepositoryAndVersion(repositoryId, version)
    }

    override suspend fun findVersionByPrefix(repositoryId: UUID, prefix: String): ArtifactVersion? {
        return versionRepo.findByRepositoryAndVersionPrefix(repositoryId, prefix)
    }

    override suspend fun createVersion(repositoryId: UUID, version: String, metadata: JsonObject?): ArtifactVersion {
        val metadataStr = metadata?.toString()
        val created = versionRepo.create(repositoryId, version, metadataStr)
        announcePublished(repositoryId, version)
        return created
    }

    override suspend fun finalizeVersion(id: UUID): ArtifactVersion = transaction {
        val version = versionRepo.lockById(id) ?: throw NoSuchElementException("Artifact version not found")
        if (version.finalized) version else versionRepo.finalizeVersion(id) ?: error("Artifact version disappeared")
    }

    override suspend fun completeVersion(id: UUID, commitSha: String, jobIds: List<UUID>, principalId: UUID?) = transaction {
        val version = versionRepo.findById(id) ?: throw NoSuchElementException("Artifact version not found")
        require(versionRepo.getVersionBlobs(version.id).isNotEmpty()) { "An empty artifact version cannot complete" }
        ArtifactCompleted(UUID.random(), version.id, jobIds, commitSha, principalId).dispatch()
    }

    private suspend fun verifyBlobChange(versionId: UUID, digest: String, role: String, filename: String?, mediaType: String?) {
        val version = versionRepo.lockById(versionId) ?: throw NoSuchElementException("Artifact version not found")
        if (version.finalized) {
            val existing = versionRepo.findVersionBlob(versionId, digest, role)
            require(existing != null && existing.filename == filename && existing.mediaType == mediaType) {
                "A finalized artifact version cannot change its files"
            }
        }
    }

    override suspend fun listVersions(repositoryId: UUID, limit: Int, offset: Long): List<ArtifactVersion> {
        return versionRepo.listByRepositoryPaged(repositoryId, limit, offset)
    }

    override suspend fun countVersions(repositoryId: UUID): Long {
        return versionRepo.countByRepository(repositoryId)
    }

    // -- Version Blobs --

    override suspend fun addVersionBlob(versionId: UUID, digest: String, role: String, filename: String?, mediaType: String?) = transaction {
        verifyBlobChange(versionId, digest, role, filename, mediaType)
        // The INSERT ... ON CONFLICT DO NOTHING RETURNING * returns a row only when a new
        // association was actually created. This eliminates the TOCTOU race where two
        // concurrent calls could both increment the ref count for a single insertion.
        val result = versionRepo.addVersionBlob(versionId, digest, role, filename, mediaType)
        if (result != null) {
            blobStorage.incrementRefCount(digest)
        }
    }

    override suspend fun addOrReplaceVersionBlob(versionId: UUID, digest: String, role: String, filename: String, mediaType: String?) {
        // Identify (and detach) any existing blob sharing this (version, filename,
        // role) but with different bytes, then attach the new one. Done in a
        // single transaction so a concurrent reader never sees zero blobs for the
        // filename. Ref-count decrements for detached blobs run AFTER the commit
        // (mirroring deleteVersion) so blob GC can't observe an uncommitted state.
        val replaced = transaction {
            verifyBlobChange(versionId, digest, role, filename, mediaType)
            val stale = versionRepo.getVersionBlobs(versionId)
                .filter { it.filename == filename && it.role == role && it.digest != digest }
            if (stale.isNotEmpty()) {
                // Removes every association for this (version, filename, role); the
                // new digest is re-inserted immediately below, so the surviving set
                // is exactly {digest}.
                versionRepo.deleteVersionBlobsByFilenameAndRole(versionId, filename, role)
            }
            val inserted = versionRepo.addVersionBlob(versionId, digest, role, filename, mediaType)
            if (inserted != null) {
                blobStorage.incrementRefCount(digest)
            }
            stale
        }
        // Decrement replaced blobs' ref counts independently so one failure does
        // not strand the others; drift is logged for operational alerting.
        for (blob in replaced) {
            try {
                blobStorage.decrementRefCount(blob.digest)
                blobStorage.deleteIfUnreferenced(blob.digest)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to clean up replaced blob {} on version {}", blob.digest, versionId, e)
            }
        }
    }

    override suspend fun getVersionBlobs(versionId: UUID): List<ArtifactVersionBlob> {
        return versionRepo.getVersionBlobs(versionId)
    }

    override suspend fun findVersionBlob(versionId: UUID, digest: String, role: String): ArtifactVersionBlob? {
        return versionRepo.findVersionBlob(versionId, digest, role)
    }

    override suspend fun findVersionBlobByRepositoryFilenameAndRole(repositoryId: UUID, filename: String, role: String): ArtifactVersionBlob? {
        return versionRepo.findVersionBlobByRepositoryFilenameAndRole(repositoryId, filename, role)
    }

    // -- Tags --

    override suspend fun findTag(repositoryId: UUID, name: String): ArtifactTag? {
        return tagRepo.findByRepositoryAndName(repositoryId, name)
    }

    override suspend fun findTagByPrefix(repositoryId: UUID, prefix: String): ArtifactTag? {
        return tagRepo.findByRepositoryAndNamePrefix(repositoryId, prefix)
    }

    override suspend fun setTag(repositoryId: UUID, name: String, manifestDigest: String): ArtifactTag = transaction {
        val tag = tagRepo.upsert(repositoryId, name, manifestDigest)
        if (repoRepo.findById(repositoryId)?.type == ArtifactType.DOCKER.value) {
            versionRepo.findByRepositoryAndVersion(repositoryId, manifestDigest)?.let { version ->
                ArtifactTagPublished(UUID.random(), repositoryId, version.id, name, manifestDigest).dispatch()
            }
        }
        // Docker versions are digest-keyed; the human coordinate is the TAG — announce it as the
        // published "version" so requirement gates on docker coordinates release.
        announcePublished(repositoryId, name)
        tag
    }

    override suspend fun listTags(repositoryId: UUID, limit: Int, last: String?): List<ArtifactTag> {
        return if (last != null) {
            tagRepo.listByRepositoryAfter(repositoryId, last, limit)
        } else {
            tagRepo.listByRepository(repositoryId, limit)
        }
    }

    override suspend fun listTagsPaged(repositoryId: UUID, limit: Int, offset: Long): List<ArtifactTag> {
        return tagRepo.listByRepositoryPaged(repositoryId, limit, offset)
    }

    override suspend fun countTags(repositoryId: UUID): Long {
        return tagRepo.countByRepository(repositoryId)
    }

    override suspend fun deleteTag(repositoryId: UUID, name: String) {
        tagRepo.delete(repositoryId, name)
    }

    // -- Upload Sessions --

    override suspend fun createUploadSession(repositoryId: UUID): UploadSession {
        val session = uploadSessionRepo.create(repositoryId)
        val path = UploadSessionPath(session.id.toString())
        var storageUploadId: String? = null
        try {
            storageUploadId = objectStorage.createMultipartUpload(path)
            return uploadSessionRepo.initializeStorageUpload(session.id, storageUploadId)
                ?: error("Upload session ${session.id} became inactive while initializing object storage")
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                if (storageUploadId != null) {
                    try {
                        objectStorage.abortMultipartUpload(path, storageUploadId, 0)
                    } catch (cleanup: Throwable) {
                        failure.addSuppressed(cleanup)
                    }
                }
                try {
                    uploadSessionRepo.cancel(session.id)
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
    }

    override suspend fun getUploadSession(id: UUID): UploadSession? {
        return uploadSessionRepo.findActive(id)
    }

    override suspend fun updateUploadSessionOffset(id: UUID, newOffset: Long, digestState: ByteArray) {
        uploadSessionRepo.updateOffset(id, newOffset, digestState)
            ?: error("Upload session $id is no longer active")
    }

    override suspend fun completeUploadSession(id: UUID) {
        uploadSessionRepo.complete(id)
    }

    override suspend fun cancelUploadSession(id: UUID, uploadedPartCount: Int?) {
        val session = uploadSessionRepo.findActiveIncludingExpired(id) ?: return
        abortUploadSession(session, uploadedPartCount ?: session.chunkCount)
    }

    override suspend fun cleanupExpiredUploadSessions(): Int {
        val expired = uploadSessionRepo.findExpired()
        var cleaned = 0
        for (session in expired) {
            try {
                abortUploadSession(session, session.chunkCount)
                cleaned++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to clean up expired upload session {}", session.id, e)
            }
        }
        if (cleaned > 0) {
            log.info("Cleaned up {} expired upload sessions", cleaned)
        }
        return cleaned
    }

    private suspend fun abortUploadSession(session: UploadSession, uploadedPartCount: Int) {
        val sessionId = session.id.toString()
        val sessionPath = UploadSessionPath(sessionId)
        val uploadId = session.storageUploadId
        var failure: Exception? = null

        suspend fun attemptCleanup(block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val firstFailure = failure
                if (firstFailure == null) {
                    failure = e
                } else {
                    firstFailure.addSuppressed(e)
                }
            }
        }

        if (uploadId != null) {
            attemptCleanup {
                objectStorage.abortMultipartUpload(sessionPath, uploadId, uploadedPartCount)
            }

            // A lengthless request is staged at the next chunk index before the database offset
            // advances. Both its completed object key and any interrupted multipart upload are
            // therefore derivable from the pending session after a process crash.
            val stagingPath = UploadChunkPath(sessionId, session.chunkCount)
            attemptCleanup { objectStorage.abortMultipartUploadsAtPath(stagingPath) }
            attemptCleanup { objectStorage.delete(stagingPath) }
        } else {
            // Reconcile the crash window between creating the final storage multipart upload and
            // persisting its provider-generated upload identifier.
            attemptCleanup { objectStorage.abortMultipartUploadsAtPath(sessionPath) }

            // Sessions created before multipart support stored one object per HTTP chunk.
            // Retain their cleanup path while those sessions age out after deployment.
            for (chunkIndex in 0 until session.chunkCount) {
                attemptCleanup { objectStorage.delete(UploadChunkPath(sessionId, chunkIndex)) }
            }
        }
        failure?.let { throw it }
        uploadSessionRepo.cancel(session.id)
    }

    // -- Deletion --

    override suspend fun deleteVersion(versionId: UUID) {
        // Read the blob list inside the transaction to prevent a race where a new
        // blob association is added between the read and the delete, leaving its
        // ref count un-decremented.
        val blobs = transaction {
            versionRepo.lockById(versionId) ?: return@transaction emptyList()
            val blobList = versionRepo.getVersionBlobs(versionId)
            versionRepo.deleteVersionBlobs(versionId)
            versionRepo.delete(versionId)
            blobList
        }
        // Process each blob independently so a failure on one blob does not
        // prevent cleanup of the remaining blobs. Ref count drift is logged
        // as an error for operational alerting.
        for (blob in blobs) {
            try {
                blobStorage.decrementRefCount(blob.digest)
                blobStorage.deleteIfUnreferenced(blob.digest)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to clean up blob {} after deleting version {}", blob.digest, versionId, e)
            }
        }
    }

    override suspend fun deleteManifest(repositoryId: UUID, digest: String) {
        tagRepo.deleteByManifestDigest(repositoryId, digest)
        val versions = versionRepo.findVersionsByBlobDigestAndRole(repositoryId, digest, "manifest")
        for (version in versions) {
            deleteVersion(version.id)
        }
    }

    override suspend fun deleteRepository(repositoryId: UUID) {
        // Paginate version listing to avoid loading all versions into memory at once.
        // Always query at offset 0 because deleteVersion removes each version from the
        // database, shifting remaining rows down — advancing the offset would skip versions.
        val pageSize = 100
        while (true) {
            val versions = versionRepo.listByRepositoryPaged(repositoryId, pageSize, 0)
            if (versions.isEmpty()) break
            for (version in versions) {
                deleteVersion(version.id)
            }
        }
        transaction {
            repoRepo.delete(repositoryId)
        }
    }

    override suspend fun deleteNamespace(namespaceId: UUID) {
        val repos = repoRepo.listByNamespace(namespaceId)
        for (repo in repos) {
            deleteRepository(repo.id)
        }
        transaction {
            namespaceRepo.delete(namespaceId)
        }
    }

    // -- Namespace Permissions (PermissionService<ArtifactNamespace, UUID>) --

    override suspend fun getPermissions(entity: ArtifactNamespace): List<EntityPermission> {
        return namespacePermissionRepo.findByNamespace(entity.id)
    }

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        val ids = batch.keys
        val all = namespacePermissionRepo.findByNamespaces(ids)
        val grouped = all.groupBy { it.namespaceId }
        ids.forEach { key ->
            batch.setData(key, grouped[key]?.map { it as EntityPermission } ?: emptyList())
        }
    }

    override suspend fun addPermission(namespaceId: UUID, groupId: UUID, action: PermissionAction) {
        namespacePermissionRepo.grant(NamespacePermission(namespaceId, groupId, action))
    }

    override suspend fun removePermission(namespaceId: UUID, groupId: UUID, action: PermissionAction) {
        namespacePermissionRepo.revoke(namespaceId, groupId, action)
    }
}
