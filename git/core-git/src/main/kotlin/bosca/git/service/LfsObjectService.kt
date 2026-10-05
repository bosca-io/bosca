package bosca.git.service

import bosca.git.model.LfsObject
import bosca.serialization.UUID
import bosca.service.Service
import bosca.storage.service.StringObjectPath
import java.io.InputStream
import java.io.OutputStream

/**
 * Manages Git LFS objects by coordinating between the database metadata layer
 * and the external object storage service. Provides streaming upload/download
 * via Basic Transfer and tracks object sizes for quota enforcement.
 */
interface LfsObjectService : Service {

    /**
     * Looks up an LFS object by its content-addressable OID within a repository.
     */
    suspend fun findByOid(repositoryId: UUID, oid: String): LfsObject?

    /**
     * Records a new LFS object upload in the metadata store.
     */
    suspend fun recordUpload(repositoryId: UUID, oid: String, size: Long): LfsObject?

    /**
     * Returns the total storage consumed by LFS objects in a repository.
     */
    suspend fun getTotalSize(repositoryId: UUID): Long

    /**
     * Computes the object storage path for an LFS object identified by repository and OID.
     */
    fun getStoragePath(repositoryId: UUID, oid: String): StringObjectPath

    /**
     * Streams an LFS object into multipart storage, verifies its SHA-256 OID and size,
     * then records its metadata. Failed uploads are removed without replacing existing objects.
     */
    suspend fun upload(repositoryId: UUID, oid: String, stream: InputStream, size: Long): LfsObject?

    /**
     * Receives an asynchronous body through [writeBody] with bounded buffering and verifies it
     * before completing storage. The callback returns the number of bytes it wrote.
     */
    suspend fun upload(repositoryId: UUID, oid: String, size: Long, writeBody: suspend (OutputStream) -> Long): LfsObject

    /**
     * Downloads an LFS object from external storage as a byte stream.
     */
    suspend fun download(repositoryId: UUID, oid: String): InputStream
}
