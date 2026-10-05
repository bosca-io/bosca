package bosca.git.service

import bosca.git.model.LfsObject
import bosca.git.model.LfsUploadValidationException
import bosca.git.repository.LfsObjectRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import bosca.storage.service.StorageDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.MessageDigest

/**
 * Coordinates LFS object storage between PostgreSQL metadata tracking and
 * the external object storage backend for streaming upload and download.
 */
@ServiceImplementation
class LfsObjectServiceImpl(
    private val lfsObjectRepository: LfsObjectRepository,
    private val objectStorage: ObjectStorageService
) : LfsObjectService {

    override suspend fun findByOid(repositoryId: UUID, oid: String): LfsObject? {
        return lfsObjectRepository.findByOid(repositoryId, oid)
    }

    override suspend fun recordUpload(repositoryId: UUID, oid: String, size: Long): LfsObject? {
        return lfsObjectRepository.create(
            LfsObject(repositoryId = repositoryId, oid = oid, size = size)
        )
    }

    override suspend fun getTotalSize(repositoryId: UUID): Long {
        return lfsObjectRepository.getTotalSize(repositoryId) ?: 0L
    }

    override fun getStoragePath(repositoryId: UUID, oid: String): StringObjectPath {
        return StringObjectPath("git-lfs/$repositoryId/$oid")
    }

    override suspend fun upload(repositoryId: UUID, oid: String, stream: InputStream, size: Long): LfsObject =
        upload(repositoryId, oid, size) { output -> stream.copyTo(output) }

    override suspend fun upload(
        repositoryId: UUID,
        oid: String,
        size: Long,
        writeBody: suspend (OutputStream) -> Long,
    ): LfsObject = withContext(StorageDispatcher) {
        if (size < 0 || oid.length != 64 || oid.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            throw LfsUploadValidationException()
        }
        val path = StringObjectPath("${getStoragePath(repositoryId, oid)}/${UUID.random()}")
        val uploadId = if (size > 0) objectStorage.createMultipartUpload(path) else null
        var partCount = 0
        try {
            coroutineScope {
                val input = PipedInputStream(PIPE_BUFFER_SIZE)
                val output = PipedOutputStream(input)
                // The reader blocks until the body writer makes progress, so it runs on
                // StorageDispatcher, which starts a thread per task and never runs out. On a bounded
                // pool shared with writers, enough concurrent uploads would leave every thread waiting
                // on a pipe and none free for the side that would unblock them. The reader keeps one
                // thread for the whole upload, which the pipe's liveness checks require. ATOMIC: its
                // finally must close the pipe even if the upload is cancelled before the reader starts,
                // or a writer blocked on the full pipe would wait forever.
                val upload = async(StorageDispatcher, start = CoroutineStart.ATOMIC) {
                    try {
                        input.use {
                            val digest = MessageDigest.getInstance("SHA-256")
                            val buffer = ByteArray(PART_SIZE)
                            var received = 0L
                            while (true) {
                                val length = input.readNBytes(buffer, 0, buffer.size)
                                if (length == 0) break
                                received += length
                                if (received > size) throw LfsUploadValidationException()
                                digest.update(buffer, 0, length)
                                val written = objectStorage.uploadMultipartPart(
                                    path, checkNotNull(uploadId), ++partCount,
                                    ByteArrayInputStream(buffer, 0, length), length.toLong(),
                                )
                                check(written == length.toLong()) { "LFS storage wrote $written bytes; expected $length" }
                            }
                            val actualOid = digest.digest().joinToString("") { "%02x".format(it) }
                            if (received != size || actualOid != oid) throw LfsUploadValidationException()
                        }
                    } catch (failure: Throwable) {
                        currentCoroutineContext().ensureActive()
                        throw failure
                    } finally {
                        output.close()
                    }
                }
                try {
                    val written = output.use { writeBody(it) }
                    upload.await()
                    if (written != size) throw LfsUploadValidationException()
                } finally {
                    upload.cancel()
                    output.close()
                    input.close()
                }
            }

            if (uploadId == null) {
                check(objectStorage.setInputStream(path, InputStream.nullInputStream(), 0) == 0L)
            } else {
                objectStorage.completeMultipartUpload(path, uploadId, partCount, size)
            }
            val inserted = lfsObjectRepository.create(
                LfsObject(repositoryId = repositoryId, oid = oid, size = size, storagePath = path.toString())
            )
            if (inserted != null) {
                return@withContext inserted
            }
            val existing = checkNotNull(lfsObjectRepository.findByOid(repositoryId, oid)) {
                "LFS object disappeared after a concurrent upload"
            }
            check(existing.size == size) { "Existing LFS object has a different size" }
            objectStorage.delete(path)
            existing
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                if (uploadId != null) {
                    try {
                        objectStorage.abortMultipartUpload(path, uploadId, partCount)
                    } catch (cleanup: Throwable) {
                        log.error("Failed to abort LFS upload {} at {}", uploadId, path, cleanup)
                        failure.addSuppressed(cleanup)
                    }
                }
                try {
                    objectStorage.delete(path)
                } catch (cleanup: Throwable) {
                    log.error("Failed to remove LFS upload at {}", path, cleanup)
                    failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
    }

    override suspend fun download(repositoryId: UUID, oid: String): InputStream {
        val obj = lfsObjectRepository.findByOid(repositoryId, oid)
            ?: throw NoSuchElementException("LFS object not found: $oid")
        // Opens off the caller's thread, and closes a stream it cannot hand back (see ObjectStorageService).
        return objectStorage.getInputStream(StringObjectPath(obj.storagePath))
    }

    private companion object {
        val log = LoggerFactory.getLogger(LfsObjectServiceImpl::class.java)
        const val PIPE_BUFFER_SIZE = 1024 * 1024
        const val PART_SIZE = 8 * 1024 * 1024
    }
}
