package bosca.storage.service

import bosca.di.ObjectProvider
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageException
import bosca.security.service.SecurityService
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels

class GoogleObjectStorageService(
    private val bucket: String,
    private val storage: Storage,
    urlPrefix: String,
    urlUploadPrefix: String,
    urlSigner: ObjectProvider<UrlSigner>,
    securityService: ObjectProvider<SecurityService>
) : AbstractObjectStorageService(urlPrefix, urlUploadPrefix, urlSigner, securityService) {

    override suspend fun getInputStream(path: ObjectPath): InputStream = openOnStorageDispatcher {
        val id = BlobId.of(bucket, path.toString())
        if (storage.get(id) == null) throw ObjectNotFoundException(path)
        // Read by name, not through the looked-up Blob, which pins its generation: an object rewritten
        // after the lookup reads its current content instead of reporting the old generation missing.
        val reader = storage.reader(id)
        NotFoundMappingInputStream(path, Channels.newInputStream(reader))
    }

    private fun getOutputStream(path: ObjectPath): OutputStream {
        val id = BlobId.of(bucket, path.toString())
        // Replacing an object keeps its settings (content type, custom metadata, cache control), as
        // writing through the existing blob always did; a new object starts from a bare BlobInfo.
        val writer = storage.writer(storage.get(id) ?: BlobInfo.newBuilder(id).build())
        return Channels.newOutputStream(writer)
    }

    override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?) = withContext(StorageDispatcher) {
        getOutputStream(path).use { stream.copyTo(it) }
    }

    override suspend fun getInputStreamRange(
        path: ObjectPath,
        range: LongRange
    ): InputStream = openOnStorageDispatcher {
        val id = BlobId.of(bucket, path.toString())
        if (storage.get(id) == null) throw ObjectNotFoundException(path)
        // Read by name (see getInputStream) so a rewrite between lookup and read is not a 404.
        val reader = storage.reader(id)
        reader.seek(range.first)
        // limit() is an exclusive end offset, not a length: [seek, limit). It may return a new
        // channel, which is the one to read from. An open-ended range (last = Long.MAX_VALUE) has
        // no end to set, and its end offset would overflow.
        val limited = if (range.last == Long.MAX_VALUE) reader else reader.limit(range.last + 1)
        NotFoundMappingInputStream(path, Channels.newInputStream(limited))
    }

    override suspend fun delete(path: ObjectPath) = withContext(StorageDispatcher) {
        val id = BlobId.of(bucket, path.toString())
        storage.delete(id)
        Unit
    }
}

/**
 * Blob readers are lazy: the object is only fetched on the first read. A blob deleted after the
 * lookup therefore fails there, as an [IOException] caused by a 404 [StorageException]; this
 * reports it as [ObjectNotFoundException], the same as a blob that was never there.
 */
internal class NotFoundMappingInputStream(private val path: ObjectPath, input: InputStream) : FilterInputStream(input) {
    override fun read(): Int = mapNotFound { super.read() }

    override fun read(b: ByteArray, off: Int, len: Int): Int = mapNotFound { super.read(b, off, len) }

    override fun skip(n: Long): Long = mapNotFound { super.skip(n) }

    private inline fun <T> mapNotFound(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        val notFound = generateSequence<Throwable>(e) { it.cause }.any { it is StorageException && it.code == 404 }
        if (notFound) throw ObjectNotFoundException(path, e)
        throw e
    }
}
