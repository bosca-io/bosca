package bosca.artifacts.service

import bosca.artifacts.model.*
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionService
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonObject

/**
 * Manages artifact namespaces, repositories, versions, tags, and their blob associations.
 *
 * This is the central coordination service for artifact metadata. Protocol-specific
 * route handlers delegate to this service for all CRUD operations on the artifact
 * data model, while blob bytes are managed by [BlobStorageService].
 */
interface ArtifactRepositoryService : PermissionService<ArtifactNamespace, UUID> {

    // -- Namespaces --

    /** Returns all registered namespaces. */
    suspend fun getNamespaces(): List<ArtifactNamespace>

    /** Returns a namespace by ID, or null if not found. */
    suspend fun getNamespace(id: UUID): ArtifactNamespace?

    /** Returns a namespace by name, or null if not found. */
    suspend fun getNamespaceByName(name: String): ArtifactNamespace?

    /** Creates a new namespace with the given name. Returns the created entity. */
    suspend fun createNamespace(name: String, public: Boolean = false): ArtifactNamespace

    /**
     * Updates a namespace's `public` (anonymous-pull) flag. Returns the updated
     * entity, or null if no namespace has the given id.
     */
    suspend fun updateNamespacePublic(id: UUID, public: Boolean): ArtifactNamespace?

    // -- Repositories --

    /** Returns a repository by ID, or null if not found. */
    suspend fun getRepository(id: UUID): ArtifactRepository?

    /** Finds a repository by namespace name, repository name, and protocol type. */
    suspend fun findRepository(namespace: String, name: String, type: ArtifactType): ArtifactRepository?

    /**
     * Finds or creates a repository for the given coordinates. Used during push
     * operations where the repository is implicitly created on first use.
     */
    suspend fun findOrCreateRepository(namespace: String, name: String, type: ArtifactType): ArtifactRepository

    /** Lists all repositories in a namespace, optionally filtered by type. */
    suspend fun listRepositories(namespaceId: UUID, type: ArtifactType? = null): List<ArtifactRepository>

    /** Lists repositories in a namespace with pagination, optionally filtered by type. */
    suspend fun listRepositories(namespaceId: UUID, type: ArtifactType?, limit: Int, offset: Long): List<ArtifactRepository>

    /** Counts repositories in a namespace, optionally filtered by type. */
    suspend fun countRepositories(namespaceId: UUID, type: ArtifactType? = null): Long

    /** Lists all repositories of a given type, with pagination. */
    suspend fun listRepositoriesByType(type: ArtifactType, limit: Int = 100, offset: Long = 0): List<ArtifactRepository>

    // -- Versions --

    /** Returns a version by ID, or null if not found. */
    suspend fun getVersion(id: UUID): ArtifactVersion?

    /** Finds a specific version of a repository. */
    suspend fun findVersion(repositoryId: UUID, version: String): ArtifactVersion?

    /**
     * Finds the newest version of a repository starting with [prefix] —prefix
     * constraints (`6.0.*` matches any published `6.0.x`). Null when nothing matches.
     */
    suspend fun findVersionByPrefix(repositoryId: UUID, prefix: String): ArtifactVersion?

    /** Creates a new version for a repository. */
    suspend fun createVersion(repositoryId: UUID, version: String, metadata: JsonObject? = null): ArtifactVersion

    /**
     * Announces [ArtifactCompleted] after the producer has finished every file upload for this version.
     * CI calls this when all associated producer jobs succeed; no publication is prepared here.
     * Dispatch participates in the caller's transaction and ordinary pipelines own processing/retries.
     */
    suspend fun completeVersion(id: UUID, commitSha: String, jobIds: List<UUID>, principalId: UUID?)

    /**
     * Locks and finalizes a completed version in the caller's transaction. Further blob changes fail;
     * identical upload redelivery remains valid. The lock also serializes publication with deletion.
     */
    suspend fun finalizeVersion(id: UUID): ArtifactVersion

    /** Lists versions of a repository with optional pagination. */
    suspend fun listVersions(repositoryId: UUID, limit: Int = 100, offset: Long = 0): List<ArtifactVersion>

    /** Counts versions in a repository. */
    suspend fun countVersions(repositoryId: UUID): Long

    // -- Version Blobs --

    /** Associates a blob with a version in a specific role. */
    suspend fun addVersionBlob(versionId: UUID, digest: String, role: String, filename: String? = null, mediaType: String? = null)

    /**
     * Associates a blob with a version, replacing any existing blob that has the
     * same [filename] and [role] but a different digest (decrementing the
     * replaced blob's reference count). Use this for re-pushable, mutable files —
     * e.g. a fixed-path installer or a "latest" pointer — where pushing new
     * content to the same path must supersede the old bytes rather than leave two
     * blobs sharing a filename (which the order-less blob lookup would then
     * resolve arbitrarily). A no-op replacement (identical digest) is idempotent.
     */
    suspend fun addOrReplaceVersionBlob(versionId: UUID, digest: String, role: String, filename: String, mediaType: String? = null)

    /** Returns all blob associations for a version. */
    suspend fun getVersionBlobs(versionId: UUID): List<ArtifactVersionBlob>

    /** Finds a version blob by digest and role. */
    suspend fun findVersionBlob(versionId: UUID, digest: String, role: String): ArtifactVersionBlob?

    /** Finds a version blob by filename and role across all versions of a repository. */
    suspend fun findVersionBlobByRepositoryFilenameAndRole(repositoryId: UUID, filename: String, role: String): ArtifactVersionBlob?

    // -- Tags (Docker-specific) --

    /** Finds a tag by repository and name, or null if not found. */
    suspend fun findTag(repositoryId: UUID, name: String): ArtifactTag?

    /**
     * Finds the most recently moved tag starting with [prefix] —prefix constraints for
     * docker coordinates, whose human-readable version is the tag. Null when nothing matches.
     */
    suspend fun findTagByPrefix(repositoryId: UUID, prefix: String): ArtifactTag?

    /**
     * Sets a tag to point at a manifest digest. Creates the tag if it does not exist,
     * or updates the existing tag's digest if it does.
     * Stored Docker manifests dispatch [bosca.artifacts.model.ArtifactTagPublished] in the tag's
     * transaction; durable pipeline delivery and pub/sub announcements wait for commit.
     */
    suspend fun setTag(repositoryId: UUID, name: String, manifestDigest: String): ArtifactTag

    /** Lists all tags for a repository, with pagination. */
    suspend fun listTags(repositoryId: UUID, limit: Int = 100, last: String? = null): List<ArtifactTag>

    /**
     * Lists tags for a repository with offset pagination, for admin browsing.
     * The registry protocol itself pages tags with the keyset-based [listTags].
     */
    suspend fun listTagsPaged(repositoryId: UUID, limit: Int = 100, offset: Long = 0): List<ArtifactTag>

    /** Counts tags in a repository. */
    suspend fun countTags(repositoryId: UUID): Long

    /** Deletes a tag by repository and name. */
    suspend fun deleteTag(repositoryId: UUID, name: String)

    // -- Upload Sessions --

    /** Creates a new upload session for chunked blob uploads. */
    suspend fun createUploadSession(repositoryId: UUID): UploadSession

    /** Returns an upload session by ID, or null if not found or expired. */
    suspend fun getUploadSession(id: UUID): UploadSession?

    /** Updates the byte offset and resumable digest state after a multipart part is stored. */
    suspend fun updateUploadSessionOffset(id: UUID, newOffset: Long, digestState: ByteArray)

    /** Marks an upload session as completed. */
    suspend fun completeUploadSession(id: UUID)

    /** Marks an upload session as cancelled and cleans up its temporary storage. */
    suspend fun cancelUploadSession(id: UUID, uploadedPartCount: Int? = null)

    /**
     * Finds and cancels all expired upload sessions, cleaning up any temporary
     * chunk files left behind. Should be called periodically by a scheduled job.
     *
     * @return the number of sessions cleaned up
     */
    suspend fun cleanupExpiredUploadSessions(): Int

    // -- Deletion --

    /** Deletes a version and all its blob associations, decrementing blob reference counts. */
    suspend fun deleteVersion(versionId: UUID)

    /** Deletes a manifest by digest from a repository, including tags pointing to it. */
    suspend fun deleteManifest(repositoryId: UUID, digest: String)

    /** Deletes a repository and all its versions, tags, and blob associations. */
    suspend fun deleteRepository(repositoryId: UUID)

    /** Deletes a namespace and all its repositories (cascading through versions, tags, blobs). */
    suspend fun deleteNamespace(namespaceId: UUID)

    // -- Namespace Permissions --

    /** Grants a permission action to a security group on a namespace. */
    suspend fun addPermission(namespaceId: UUID, groupId: UUID, action: PermissionAction)

    /** Revokes a permission action from a security group on a namespace. */
    suspend fun removePermission(namespaceId: UUID, groupId: UUID, action: PermissionAction)
}
