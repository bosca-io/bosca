package bosca.artifacts.service

import bosca.artifacts.model.ArtifactBlob
import bosca.service.Service
import bosca.storage.service.ObjectPath
import java.io.InputStream

/**
 * Manages content-addressable blob storage for the artifact registry.
 *
 * Blobs are identified by their cryptographic digest and stored in the platform's
 * object storage backend. Reference counting enables safe garbage collection —
 * a blob is only eligible for deletion when no artifact versions reference it.
 */
interface BlobStorageService : Service {

    /**
     * Checks whether a blob with the given digest exists in storage.
     *
     * @param digest the content digest in `algorithm:hex` format
     * @return true if a blob with this digest is stored
     */
    suspend fun exists(digest: String): Boolean

    /**
     * Returns metadata for a blob, or null if it does not exist.
     *
     * @param digest the content digest in `algorithm:hex` format
     */
    suspend fun get(digest: String): ArtifactBlob?

    /**
     * Opens an input stream for reading the blob content.
     * The caller is responsible for closing the returned stream.
     *
     * @param digest the content digest in `algorithm:hex` format
     * @throws NoSuchElementException if the blob does not exist
     */
    suspend fun getInputStream(digest: String): InputStream

    /**
     * Stores a blob with the given digest. If a blob with this digest already exists,
     * its reference count is incremented and the input stream is not consumed.
     *
     * @param digest the expected content digest in `algorithm:hex` format
     * @param input the blob content stream
     * @param size the content length in bytes
     * @return the stored blob metadata
     */
    suspend fun store(digest: String, input: InputStream, size: Long): ArtifactBlob

    /**
     * Registers and completes an object-storage multipart upload as a content-addressable blob.
     * If [digest] already exists, the redundant multipart upload is aborted and the existing blob
     * is returned.
     *
     * @param digest verified content digest for the completed object
     * @param path permanent object-storage path associated with this upload
     * @param uploadId opaque multipart upload identifier returned by object storage
     * @param partCount number of consecutively uploaded parts
     * @param size total object size in bytes
     */
    suspend fun completeMultipartUpload(
        digest: String,
        path: ObjectPath,
        uploadId: String,
        partCount: Int,
        size: Long,
    ): ArtifactBlob

    /**
     * Increments the reference count for a blob, indicating that an additional
     * artifact version now references it.
     *
     * @param digest the content digest in `algorithm:hex` format
     */
    suspend fun incrementRefCount(digest: String)

    /**
     * Decrements the reference count for a blob. When the count reaches zero,
     * the blob becomes eligible for garbage collection.
     *
     * @param digest the content digest in `algorithm:hex` format
     */
    suspend fun decrementRefCount(digest: String)

    /**
     * Deletes a blob from both the database and object storage.
     * Should only be called by garbage collection after verifying the reference count is zero.
     *
     * @param digest the content digest in `algorithm:hex` format
     */
    suspend fun delete(digest: String)

    /**
     * Atomically deletes a blob only if its reference count has reached zero.
     * Combines the check and delete into a single SQL operation to prevent
     * race conditions between concurrent deletions.
     *
     * @param digest the content digest in `algorithm:hex` format
     * @return true if the blob was deleted, false if it still has references
     */
    suspend fun deleteIfUnreferenced(digest: String): Boolean
}
