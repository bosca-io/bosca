package bosca.artifacts.service

import bosca.artifacts.model.ArtifactBlob
import bosca.artifacts.model.ArtifactBlobPath
import bosca.artifacts.repository.BlobRepository
import bosca.service.annotation.ServiceImplementation
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.ObjectPath
import bosca.storage.service.StringObjectPath
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Content-addressable blob storage backed by the platform's [ObjectStorageService]
 * with metadata tracking in PostgreSQL via [BlobRepository].
 *
 * Deduplication is enforced by the blob digest database primary key. Ordinary writes
 * use a digest-derived object path; multipart uploads remain at their session path and
 * record that path with the blob. When a digest already exists, redundant upload data
 * is discarded and reference counting is handled separately by
 * [ArtifactRepositoryServiceImpl.addVersionBlob].
 *
 * Blobs are created with `ref_count=0`. The reference count is incremented only when
 * a version blob association is created via [addVersionBlob], ensuring a 1:1 correspondence
 * between version-blob rows and the reference count.
 */
@ServiceImplementation
class BlobStorageServiceImpl(
    private val objectStorage: ObjectStorageService,
    private val blobRepository: BlobRepository,
) : BlobStorageService {

    override suspend fun exists(digest: String): Boolean {
        return blobRepository.findByDigest(digest) != null
    }

    override suspend fun get(digest: String): ArtifactBlob? {
        return blobRepository.findByDigest(digest)
    }

    override suspend fun getInputStream(digest: String): InputStream {
        val blob = blobRepository.findByDigest(digest) ?: throw NoSuchElementException("Blob not found: $digest")
        return objectStorage.getInputStream(blob.storagePath())
    }

    override suspend fun store(digest: String, input: InputStream, size: Long): ArtifactBlob {
        // Attempt INSERT with ON CONFLICT DO NOTHING. If the row already exists,
        // insert returns null — we simply look it up. This avoids the TOCTOU race
        // of check-then-insert patterns.
        val inserted = blobRepository.insert(digest, size, null)
        if (inserted != null) {
            // New blob — write bytes to object storage
            val path = ArtifactBlobPath(digest)
            try {
                objectStorage.setInputStream(path, input, size)
            } catch (e: Exception) {
                // Clean up the database row if the object storage write fails
                try {
                    blobRepository.delete(digest)
                } catch (cleanup: Exception) {
                    log.error("Failed to clean up blob {} after object storage write failure — blob row is orphaned", digest, cleanup)
                    throw IllegalStateException(
                        "Object storage write failed and DB cleanup also failed for blob $digest — row is orphaned", e
                    ).also { it.addSuppressed(cleanup) }
                }
                throw e
            }
            return inserted
        }
        // Blob already existed — no bytes to write
        return blobRepository.getByDigest(digest)
    }

    override suspend fun completeMultipartUpload(
        digest: String,
        path: ObjectPath,
        uploadId: String,
        partCount: Int,
        size: Long,
    ): ArtifactBlob {
        val storagePath = path.toString()
        val existing = blobRepository.findByDigest(digest)
        if (existing != null) {
            if (existing.storagePath != storagePath) {
                abortRedundantMultipartUpload(digest, path, uploadId, partCount)
            }
            return existing
        }

        try {
            objectStorage.completeMultipartUpload(path, uploadId, partCount, size)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try {
                    objectStorage.abortMultipartUpload(path, uploadId, partCount)
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }

        try {
            val inserted = blobRepository.insert(digest, size, storagePath)
            if (inserted != null) return inserted

            val concurrentlyInserted = blobRepository.getByDigest(digest)
            if (concurrentlyInserted.storagePath != storagePath) {
                deleteRedundantCompletedObject(digest, path)
            }
            return concurrentlyInserted
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try {
                    objectStorage.delete(path)
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
    }

    override suspend fun incrementRefCount(digest: String) {
        blobRepository.incrementRefCount(digest)
            ?: throw NoSuchElementException("Cannot increment ref count: blob not found: $digest")
    }

    override suspend fun decrementRefCount(digest: String) {
        blobRepository.decrementRefCount(digest)
            ?: throw NoSuchElementException("Cannot decrement ref count: blob not found: $digest")
    }

    override suspend fun delete(digest: String) {
        val blob = blobRepository.findByDigest(digest) ?: return
        // Delete DB record first — an orphaned blob in object storage is benign,
        // but a DB record pointing to missing bytes causes read-path errors.
        blobRepository.delete(digest)
        objectStorage.delete(blob.storagePath())
    }

    override suspend fun deleteIfUnreferenced(digest: String): Boolean {
        val deleted = blobRepository.deleteIfUnreferenced(digest)
        if (deleted != null) {
            try {
                objectStorage.delete(deleted.storagePath())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to delete blob {} from object storage after DB removal; re-inserting DB record for future cleanup", digest, e)
                try {
                    blobRepository.insert(deleted.digest, deleted.size, deleted.storagePath)
                } catch (reinsertCancellation: CancellationException) {
                    throw reinsertCancellation
                } catch (reinsertEx: Exception) {
                    log.error("Failed to re-insert blob {} record after storage delete failure — blob is orphaned in object storage", digest, reinsertEx)
                }
                return false
            }
            return true
        }
        return false
    }

    companion object {
        private val log = LoggerFactory.getLogger(BlobStorageServiceImpl::class.java)
    }

    private suspend fun abortRedundantMultipartUpload(
        digest: String,
        path: ObjectPath,
        uploadId: String,
        partCount: Int,
    ) {
        try {
            objectStorage.abortMultipartUpload(path, uploadId, partCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to abort redundant multipart upload {} for blob {}", uploadId, digest, e)
        }
    }

    private suspend fun deleteRedundantCompletedObject(digest: String, path: ObjectPath) {
        try {
            objectStorage.delete(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to delete redundant completed object {} for blob {}", path, digest, e)
        }
    }
}

private fun ArtifactBlob.storagePath(): ObjectPath =
    storagePath?.let(::StringObjectPath) ?: ArtifactBlobPath(digest)
