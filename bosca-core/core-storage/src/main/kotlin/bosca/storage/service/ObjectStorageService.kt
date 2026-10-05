package bosca.storage.service

import bosca.content.collection.model.Collection
import bosca.content.metadata.model.Metadata
import bosca.security.model.AuthenticatedPrincipal
import bosca.serialization.UUID
import bosca.server.content.*
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.UUID as JavaUUID
import java.util.concurrent.Executors

/**
 * Marker interface representing an opaque path to an object in storage.
 *
 * Implementations encapsulate the storage-specific addressing scheme (e.g., S3 bucket/key,
 * filesystem path) so that [ObjectStorageService] consumers do not need to know the
 * underlying storage topology.
 */
interface ObjectPath

/**
 * Simple [ObjectPath] implementation backed by a string path.
 *
 * Used to address objects in storage by arbitrary string keys (e.g., temporary
 * script results, backup archives) without requiring a [Metadata] or [Collection] entity.
 */
open class StringObjectPath(
    private val path: String
) : ObjectPath {

    override fun toString() = path
}

/**
 * Service for reading, writing, and managing binary objects in the platform's storage backend.
 *
 * Objects are addressed via [ObjectPath] instances, which are resolved from content [Metadata]
 * or [Collection] entities. This service also provides pre-signed URL generation for
 * direct client uploads and downloads, enabling efficient large-file transfers that bypass
 * the application server.
 */
interface ObjectStorageService {

    /**
     * Resolves the storage path for a metadata item's primary or supplementary content.
     *
     * @param metadata the metadata entity whose content path should be resolved
     * @param supplementaryId an optional identifier for a supplementary asset (e.g., thumbnail, transcript);
     *   `null` indicates the primary content
     * @return the [ObjectPath] addressing the object in storage
     */
    suspend fun getPath(
        metadata: Metadata,
        supplementaryId: UUID? = null,
    ): ObjectPath

    /**
     * Resolves the storage path for a collection's primary or supplementary content.
     *
     * @param collection the collection entity whose content path should be resolved
     * @param supplementaryId an optional identifier for a supplementary asset;
     *   `null` indicates the primary content
     * @return the [ObjectPath] addressing the object in storage
     */
    suspend fun getPath(
        collection: Collection,
        supplementaryId: UUID? = null,
    ): ObjectPath

    /**
     * Reads the entire content at the given path as a UTF-8 string.
     *
     * @param path the storage path to read from
     * @return the content as a string
     * @throws ObjectNotFoundException when nothing is stored at [path]
     */
    suspend fun getString(path: ObjectPath): String

    /**
     * Opens an [InputStream] for reading the entire content at the given path.
     * The caller is responsible for closing the returned stream.
     *
     * @param path the storage path to read from
     * @return an input stream over the object's content
     * @throws ObjectNotFoundException when nothing is stored at [path]
     */
    suspend fun getInputStream(path: ObjectPath): InputStream

    /**
     * Opens an [InputStream] for reading a byte range of the content at the given path.
     * The caller is responsible for closing the returned stream.
     *
     * @param path the storage path to read from
     * @param range the inclusive byte range to read (e.g., `0L..1023L` for the first 1024 bytes)
     * @return an input stream over the specified range of the object's content
     * @throws ObjectNotFoundException when nothing is stored at [path]
     */
    suspend fun getInputStreamRange(path: ObjectPath, range: LongRange): InputStream

    /**
     * Writes the content from the given [InputStream] to the specified storage path,
     * replacing any existing content at that path.
     *
     * @param path the storage path to write to
     * @param stream the input stream providing the content to store
     * @param length the known content length in bytes, or `null` if unknown (some backends
     *   may require this for efficient uploads)
     * @return the number of bytes written
     */
    suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long? = null): Long

    /**
     * Starts a multipart upload whose completed object will be stored at [path].
     *
     * The returned identifier is opaque and must be supplied to the remaining multipart methods.
     * The default implementation stores parts as temporary objects; storage backends with native
     * multipart support should override this method and the corresponding part lifecycle methods.
     */
    suspend fun createMultipartUpload(path: ObjectPath): String = JavaUUID.randomUUID().toString()

    /**
     * Uploads one complete part to an existing multipart upload.
     *
     * Part numbers are one-based and must be consecutive. Except for the final part, callers must
     * honor any minimum part size imposed by the backing object store.
     *
     * @return the number of bytes stored for the part
     */
    suspend fun uploadMultipartPart(
        path: ObjectPath,
        uploadId: String,
        partNumber: Int,
        stream: InputStream,
        length: Long,
    ): Long {
        require(partNumber > 0) { "partNumber must be positive" }
        require(length > 0) { "multipart parts must not be empty" }
        return setInputStream(MultipartPartPath(path, uploadId, partNumber), stream, length)
    }

    /**
     * Copies an existing object into one part of an active multipart upload.
     *
     * This is useful when a request body has no declared length: callers can stream it to a
     * temporary object, discover its actual length, and then add it to a multipart upload without
     * buffering the request in memory. Backends with native server-side copy support should
     * override this method; the default implementation streams the source object back through
     * [uploadMultipartPart].
     *
     * @param sourcePath path of the complete object to copy
     * @param path destination path associated with the active multipart upload
     * @param uploadId opaque identifier returned by [createMultipartUpload]
     * @param partNumber one-based destination part number
     * @param length exact size of the source object in bytes
     * @return the number of bytes copied into the multipart part
     */
    suspend fun copyToMultipartPart(
        sourcePath: ObjectPath,
        path: ObjectPath,
        uploadId: String,
        partNumber: Int,
        length: Long,
    ): Long = getInputStream(sourcePath).use { input ->
        uploadMultipartPart(path, uploadId, partNumber, input, length)
    }

    /**
     * Completes a multipart upload and makes the assembled object available at [path].
     *
     * @param partCount number of consecutively uploaded parts
     * @param totalLength total object size in bytes
     */
    suspend fun completeMultipartUpload(
        path: ObjectPath,
        uploadId: String,
        partCount: Int,
        totalLength: Long,
    ) {
        require(partCount > 0) { "partCount must be positive" }
        require(totalLength > 0) { "multipart upload must not be empty" }
        val tempFile = withContext(Dispatchers.IO) { File.createTempFile("object-multipart-", ".tmp") }
        try {
            withContext(Dispatchers.IO) {
                var copied = 0L
                BufferedOutputStream(tempFile.outputStream()).use { output ->
                    for (partNumber in 1..partCount) {
                        getInputStream(MultipartPartPath(path, uploadId, partNumber)).use { input ->
                            copied += input.copyTo(output)
                        }
                    }
                }
                check(copied == totalLength) {
                    "multipart upload $uploadId contains $copied bytes, expected $totalLength"
                }
                tempFile.inputStream().use { input ->
                    setInputStream(path, input, totalLength)
                }
            }
        } finally {
            try {
                abortMultipartUpload(path, uploadId, partCount)
            } finally {
                withContext(Dispatchers.IO) { tempFile.delete() }
            }
        }
    }

    /**
     * Aborts a multipart upload and removes all uploaded parts.
     *
     * @param partCount number of parts that may require cleanup
     */
    suspend fun abortMultipartUpload(path: ObjectPath, uploadId: String, partCount: Int) {
        require(partCount >= 0) { "partCount must be non-negative" }
        for (partNumber in 1..partCount) {
            delete(MultipartPartPath(path, uploadId, partNumber))
        }
    }

    /**
     * Discovers and aborts every in-progress multipart upload whose object key exactly matches
     * [path].
     *
     * This reconciles uploads whose storage-generated identifier could not be persisted before a
     * process stopped. Backends without discoverable multipart uploads return zero.
     *
     * @return the number of multipart uploads aborted
     */
    suspend fun abortMultipartUploadsAtPath(path: ObjectPath): Int = 0

    /**
     * Deletes the object at the specified storage path.
     *
     * @param path the storage path of the object to delete
     */
    suspend fun delete(path: ObjectPath)

    /**
     * Generates a pre-signed download URL for a metadata item's content, allowing
     * direct client downloads without proxying through the application server.
     *
     * @param path the storage path of the object to download
     * @param principal the authenticated principal requesting the download, or `null` for anonymous access
     * @param metadata the metadata entity associated with the content
     * @param supplementaryId the supplementary asset identifier, or `null` for primary content
     * @param filename whether to include a Content-Disposition header with the original filename
     * @return a [SignedUrl] containing the pre-signed URL and any required headers
     */
    suspend fun getSignedDownloadUrl(
        path: ObjectPath,
        principal: AuthenticatedPrincipal?,
        metadata: Metadata,
        supplementaryId: UUID?,
        filename: Boolean = false,
    ): SignedUrl

    /**
     * Generates a pre-signed upload URL for a metadata item's content, allowing
     * direct client uploads without proxying through the application server.
     *
     * @param path the storage path where the upload should be stored
     * @param principal the authenticated principal performing the upload
     * @param metadata the metadata entity associated with the content
     * @param supplementaryId the supplementary asset identifier, or `null` for primary content
     * @return a [SignedUrl] containing the pre-signed URL and any required headers
     */
    suspend fun getSignedUploadUrl(
        path: ObjectPath,
        principal: AuthenticatedPrincipal,
        metadata: Metadata,
        supplementaryId: UUID?
    ): SignedUrl

    /**
     * Generates a pre-signed download URL for a collection's content, allowing
     * direct client downloads without proxying through the application server.
     *
     * @param path the storage path of the object to download
     * @param principal the authenticated principal requesting the download, or `null` for anonymous access
     * @param collection the collection entity associated with the content
     * @param supplementaryId the supplementary asset identifier, or `null` for primary content
     * @param filename whether to include a Content-Disposition header with the original filename
     * @return a [SignedUrl] containing the pre-signed URL and any required headers
     */
    suspend fun getSignedDownloadUrl(
        path: ObjectPath,
        principal: AuthenticatedPrincipal?,
        collection: Collection,
        supplementaryId: UUID?,
        filename: Boolean = false,
    ): SignedUrl

    /**
     * Generates a pre-signed upload URL for a collection's content, allowing
     * direct client uploads without proxying through the application server.
     *
     * @param path the storage path where the upload should be stored
     * @param principal the authenticated principal performing the upload
     * @param collection the collection entity associated with the content
     * @param supplementaryId the supplementary asset identifier, or `null` for primary content
     * @return a [SignedUrl] containing the pre-signed URL and any required headers
     */
    suspend fun getSignedUploadUrl(
        path: ObjectPath,
        principal: AuthenticatedPrincipal,
        collection: Collection,
        supplementaryId: UUID?
    ): SignedUrl
}

private class MultipartPartPath(
    path: ObjectPath,
    uploadId: String,
    partNumber: Int,
) : StringObjectPath("$path.multipart/$uploadId/$partNumber")

suspend fun ObjectStorageService.upload(
    metadata: Metadata,
    supplementaryId: UUID?,
    file: PartData.FileItem
): Long = withContext(StorageDispatcher) {
    val path = getPath(metadata, supplementaryId)
    file.streamProvider.use { setInputStream(path, it) }
}

/**
 * Uploads a multipart file item to storage while reporting the number of bytes
 * written via [onProgress]. The callback is invoked every 512KB during the
 * transfer and once more with the final byte count when the upload completes.
 */
suspend fun ObjectStorageService.upload(
    metadata: Metadata,
    supplementaryId: UUID?,
    file: PartData.FileItem,
    onProgress: suspend (bytesRead: Long) -> Unit
): Long = withContext(StorageDispatcher) {
    val path = getPath(metadata, supplementaryId)
    file.streamProvider.use { stream ->
        val channel = Channel<Long>()
        val job = launch {
            channel.consumeAsFlow().collect {
                onProgress(it)
            }
        }
        try {
            val progress = ProgressInputStream(stream) {
                channel.trySend(it)
            }
            val size = setInputStream(path, progress)
            onProgress(size)
            size
        } finally {
            job.cancel()
            channel.close()
        }
    }
}

suspend fun ObjectStorageService.upload(
    metadata: Metadata,
    supplementaryId: UUID?,
    file: File
): Long = withContext(StorageDispatcher) {
    val path = getPath(metadata, supplementaryId)
    file.inputStream().use { setInputStream(path, it, file.length()) }
}

suspend fun ObjectStorageService.upload(
    metadata: Metadata,
    supplementaryId: UUID?,
    content: String
): Long = withContext(StorageDispatcher) {
    val path = getPath(metadata, supplementaryId)
    setInputStream(path, content.byteInputStream())
}

suspend fun ObjectStorageService.upload(
    collection: Collection,
    supplementaryId: UUID?,
    content: String
) = withContext(StorageDispatcher) {
    val path = getPath(collection, supplementaryId)
    setInputStream(path, content.byteInputStream())
}

suspend fun ObjectStorageService.upload(
    collection: Collection,
    supplementaryId: UUID?,
    file: PartData.FileItem
): Long = withContext(StorageDispatcher) {
    val path = getPath(collection, supplementaryId)
    file.streamProvider.use { setInputStream(path, it) }
}

suspend fun ObjectStorageService.download(
    metadata: Metadata,
    supplementaryId: UUID? = null,
    range: LongRange? = null,
): InputStream {
    val path = getPath(metadata, supplementaryId)
    return range?.let { getInputStreamRange(path, range) } ?: getInputStream(path)
}

suspend fun ObjectStorageService.download(
    collection: Collection,
    supplementaryId: UUID? = null,
): InputStream {
    val path = getPath(collection, supplementaryId)
    return getInputStream(path)
}

val StorageDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()

private const val PROGRESS_INTERVAL = 512L * 1024L // 512KB

/**
 * An [InputStream] wrapper that invokes [onProgress] with the cumulative byte count
 * every [PROGRESS_INTERVAL] bytes read through the stream.
 */
private class ProgressInputStream(
    delegate: InputStream,
    private val onProgress: (bytesRead: Long) -> Unit
) : FilterInputStream(delegate) {

    private var total = 0L
    private var lastReported = 0L

    override fun read(): Int {
        val b = super.read()
        if (b != -1) {
            total++
            maybeReport()
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) {
            total += n
            maybeReport()
        }
        return n
    }

    private fun maybeReport() {
        if (total - lastReported >= PROGRESS_INTERVAL) {
            lastReported = total
            onProgress(total)
        }
    }
}
