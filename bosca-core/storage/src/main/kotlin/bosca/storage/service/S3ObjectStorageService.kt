package bosca.storage.service

import bosca.di.ObjectProvider
import bosca.security.service.SecurityService
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListPartsRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.UploadPartCopyRequest
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import software.amazon.awssdk.utils.BinaryUtils
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.ZoneOffset

class S3ObjectStorageService(
    private val bucket: String,
    private val client: S3Client,
    urlPrefix: String,
    urlUploadPrefix: String,
    urlSigner: ObjectProvider<UrlSigner>,
    securityService: ObjectProvider<SecurityService>
) : AbstractObjectStorageService(urlPrefix, urlUploadPrefix, urlSigner, securityService) {

    override fun close() = client.close()

    override suspend fun listObjects(prefix: String, offset: Long, limit: Int): List<ObjectStorageEntry> {
        require(offset >= 0) { "Object listing offset must not be negative" }
        require(limit > 0) { "Object listing limit must be positive" }
        return withContext(StorageDispatcher) {
            val entries = mutableListOf<ObjectStorageEntry>()
            val seenTokens = mutableSetOf<String>()
            var token: String? = null
            var skip = offset
            while (true) {
                currentCoroutineContext().ensureActive()
                val pageSize = if (skip >= 1000) 1000 else minOf(1000L, skip + (limit - entries.size)).toInt()
                val request = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .continuationToken(token)
                    .maxKeys(pageSize)
                    .build()
                val page = client.listObjectsV2(request)
                for (entry in page.contents()) {
                    currentCoroutineContext().ensureActive()
                    if (skip > 0) {
                        skip--
                        continue
                    }
                    val key = checkNotNull(entry.key()) { "Listed object key is missing" }
                    require(key.startsWith(prefix)) { "Listed object is outside the requested prefix: $key" }
                    entries.add(
                        ObjectStorageEntry(
                            key,
                            ObjectStorageInfo(
                                length = checkNotNull(entry.size()) { "Listed object length is missing: $key" },
                                etag = entry.eTag()?.takeIf { it.isNotBlank() },
                                modified = entry.lastModified()?.atOffset(ZoneOffset.UTC),
                            ),
                        ),
                    )
                    if (entries.size == limit) return@withContext entries
                }
                if (page.isTruncated() != true) break
                val next = checkNotNull(page.nextContinuationToken()?.takeIf { it.isNotBlank() }) {
                    "Truncated object listing has no continuation token"
                }
                check(seenTokens.add(next)) { "Object listing repeated a continuation token" }
                token = next
            }
            entries
        }
    }

    override suspend fun getObjectInfo(path: ObjectPath): ObjectStorageInfo = withContext(StorageDispatcher) {
        val response = try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(path.toString()).build())
        } catch (failure: S3Exception) {
            val missingBucket = failure is NoSuchBucketException || failure.awsErrorDetails()?.errorCode() == "NoSuchBucket"
            if (failure.statusCode() == 404 && !missingBucket) throw ObjectNotFoundException(path, failure)
            throw failure
        }
        ObjectStorageInfo(
            length = checkNotNull(response.contentLength()) { "Object length is missing: $path" },
            etag = response.eTag()?.takeIf { it.isNotBlank() },
            version = response.versionId()?.takeIf { it.isNotBlank() && it != "null" },
            modified = response.lastModified()?.atOffset(ZoneOffset.UTC),
        )
    }

    override suspend fun getInputStream(path: ObjectPath): InputStream = openOnStorageDispatcher {
        try {
            AbortOnEarlyCloseInputStream(client.getObject(GetObjectRequest.builder().bucket(bucket).key(path.toString()).build()))
        } catch (e: NoSuchKeyException) {
            throw ObjectNotFoundException(path, e)
        }
    }

    override suspend fun getInputStream(path: ObjectPath, version: String): InputStream {
        require(version.isNotBlank() && version != "null") { "An immutable S3 object version is required" }
        return openOnStorageDispatcher {
            val response = try {
                client.getObject(
                    GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(path.toString())
                        .versionId(version)
                        .build(),
                )
            } catch (failure: S3Exception) {
                val missingBucket = failure is NoSuchBucketException ||
                    failure.awsErrorDetails()?.errorCode() == "NoSuchBucket"
                if (failure.statusCode() == 404 && !missingBucket) throw ObjectNotFoundException(path, failure)
                throw failure
            }
            try {
                check(response.response().versionId() == version) {
                    "Object response does not match the requested version"
                }
                AbortOnEarlyCloseInputStream(response)
            } catch (failure: Throwable) {
                try {
                    response.abort()
                } catch (abortFailure: Throwable) {
                    failure.addSuppressed(abortFailure)
                }
                throw failure
            }
        }
    }

    override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long =
        setInputStreamWithInfo(path, stream, length).length

    override suspend fun setInputStreamWithInfo(
        path: ObjectPath,
        stream: InputStream,
        length: Long?,
    ): ObjectStorageInfo = withContext(StorageDispatcher) {
        val key = path.toString()
        // Stream unknown-length input without buffering entire content using S3 Multipart Upload
        val create = client.createMultipartUpload(
            CreateMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType("application/octet-stream")
                .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                .build()
        )
        val uploadId = create.uploadId()
        val completedParts = mutableListOf<CompletedPart>()
        var partNumber = 1
        var total = 0L
        // 5 MiB minimum for all parts except the last one
        val partSize = 5 * 1024 * 1024
        val buffer = ByteArray(partSize)
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                var bytesReadTotal = 0
                // Read up to partSize bytes into buffer
                while (bytesReadTotal < partSize) {
                    currentCoroutineContext().ensureActive()
                    val read = stream.read(buffer, bytesReadTotal, partSize - bytesReadTotal)
                    if (read == -1) break
                    bytesReadTotal += read
                }
                if (bytesReadTotal <= 0) break

                // Hash only the bytes actually read into this part. The final part (and any
                // sub-partSize upload) fills less than the full buffer, so digesting the whole
                // 5 MiB `buffer` would checksum stale/zero trailing bytes and S3 would reject the
                // part body with a checksum mismatch.
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(buffer, 0, bytesReadTotal)
                val hashBytes = digest.digest()
                val base64Checksum = BinaryUtils.toBase64(hashBytes)

                val body = RequestBody.fromInputStream(ByteArrayInputStream(buffer, 0, bytesReadTotal), bytesReadTotal.toLong())
                val request = UploadPartRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .partNumber(partNumber)
                    .contentLength(bytesReadTotal.toLong())
                    .checksumSHA256(base64Checksum)
                    .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                    .build()
                val response = client.uploadPart(request, body)
                completedParts.add(
                    CompletedPart.builder()
                        .partNumber(partNumber)
                        .checksumSHA256(response.checksumSHA256())
                        .eTag(response.eTag())
                        .build()
                )
                total += bytesReadTotal
                partNumber += 1
            }

            currentCoroutineContext().ensureActive()
            val completed = client.completeMultipartUpload(
                CompleteMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(
                        CompletedMultipartUpload.builder()
                            .parts(completedParts)
                            .build()
                    )
                    .build()
            )
            writeInfo(total, completed)
        } catch (t: Throwable) {
            try {
                client.abortMultipartUpload(
                    AbortMultipartUploadRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .uploadId(uploadId)
                        .build()
                )
            } catch (cleanup: Throwable) {
                t.addSuppressed(cleanup)
            }
            throw t
        }
    }

    override suspend fun createMultipartUpload(path: ObjectPath): String = withContext(StorageDispatcher) {
        client.createMultipartUpload(
            CreateMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(path.toString())
                .contentType("application/octet-stream")
                .build()
        ).uploadId()
    }

    override suspend fun uploadMultipartPart(
        path: ObjectPath,
        uploadId: String,
        partNumber: Int,
        stream: InputStream,
        length: Long,
    ): Long = withContext(StorageDispatcher) {
        require(partNumber in 1..MAX_MULTIPART_PARTS) {
            "partNumber must be between 1 and $MAX_MULTIPART_PARTS"
        }
        require(length in 1..MAX_MULTIPART_PART_SIZE) {
            "multipart part length must be between 1 and $MAX_MULTIPART_PART_SIZE bytes"
        }
        client.uploadPart(
            UploadPartRequest.builder()
                .bucket(bucket)
                .key(path.toString())
                .uploadId(uploadId)
                .partNumber(partNumber)
                .contentLength(length)
                .build(),
            RequestBody.fromInputStream(stream, length),
        )
        length
    }

    override suspend fun copyToMultipartPart(
        sourcePath: ObjectPath,
        path: ObjectPath,
        uploadId: String,
        partNumber: Int,
        length: Long,
    ): Long = withContext(StorageDispatcher) {
        require(partNumber in 1..MAX_MULTIPART_PARTS) {
            "partNumber must be between 1 and $MAX_MULTIPART_PARTS"
        }
        require(length in 1..MAX_MULTIPART_PART_SIZE) {
            "multipart part length must be between 1 and $MAX_MULTIPART_PART_SIZE bytes"
        }
        client.uploadPartCopy(
            UploadPartCopyRequest.builder()
                .sourceBucket(bucket)
                .sourceKey(sourcePath.toString())
                .destinationBucket(bucket)
                .destinationKey(path.toString())
                .uploadId(uploadId)
                .partNumber(partNumber)
                .build()
        )
        length
    }

    override suspend fun completeMultipartUpload(
        path: ObjectPath,
        uploadId: String,
        partCount: Int,
        totalLength: Long,
    ) {
        completeMultipartUploadWithInfo(path, uploadId, partCount, totalLength)
    }

    override suspend fun completeMultipartUploadWithInfo(
        path: ObjectPath,
        uploadId: String,
        partCount: Int,
        totalLength: Long,
    ): ObjectStorageInfo = withContext(StorageDispatcher) {
        require(partCount in 1..MAX_MULTIPART_PARTS) {
            "partCount must be between 1 and $MAX_MULTIPART_PARTS"
        }
        require(totalLength > 0) { "multipart upload must not be empty" }

        val listedParts = mutableListOf<software.amazon.awssdk.services.s3.model.Part>()
        var partNumberMarker: Int? = null
        do {
            val response = client.listParts(
                ListPartsRequest.builder()
                    .bucket(bucket)
                    .key(path.toString())
                    .uploadId(uploadId)
                    .partNumberMarker(partNumberMarker)
                    .build()
            )
            listedParts += response.parts()
            partNumberMarker = response.nextPartNumberMarker()
        } while (response.isTruncated == true)

        listedParts.sortBy { it.partNumber() }
        val partNumbers = listedParts.map { it.partNumber() }
        require(partNumbers == (1..partCount).toList()) {
            "multipart upload $uploadId has parts $partNumbers, expected 1..$partCount"
        }
        val uploadedLength = listedParts.sumOf { it.size() }
        require(uploadedLength == totalLength) {
            "multipart upload $uploadId contains $uploadedLength bytes, expected $totalLength"
        }
        val completedParts = listedParts.map { part ->
            CompletedPart.builder()
                .partNumber(part.partNumber())
                .eTag(part.eTag())
                .build()
        }

        val completed = client.completeMultipartUpload(
            CompleteMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(path.toString())
                .uploadId(uploadId)
                .multipartUpload(
                    CompletedMultipartUpload.builder()
                        .parts(completedParts)
                        .build()
                )
                .build()
        )
        writeInfo(totalLength, completed)
    }

    private fun writeInfo(length: Long, response: CompleteMultipartUploadResponse) =
        ObjectStorageInfo(
            length = length,
            etag = response.eTag()?.takeIf { it.isNotBlank() },
            version = response.versionId()?.takeIf { it.isNotBlank() && it != "null" },
        )

    override suspend fun abortMultipartUpload(path: ObjectPath, uploadId: String, partCount: Int) = withContext(StorageDispatcher) {
        require(partCount in 0..MAX_MULTIPART_PARTS) {
            "partCount must be between 0 and $MAX_MULTIPART_PARTS"
        }
        abortMultipartUploadIfPresent(path.toString(), uploadId)
        Unit
    }

    override suspend fun abortMultipartUploadsAtPath(path: ObjectPath): Int = withContext(StorageDispatcher) {
        val key = path.toString()
        val uploadIds = mutableListOf<String>()
        var keyMarker: String? = null
        var uploadIdMarker: String? = null
        do {
            val response = client.listMultipartUploads(
                ListMultipartUploadsRequest.builder()
                    .bucket(bucket)
                    .prefix(key)
                    .keyMarker(keyMarker)
                    .uploadIdMarker(uploadIdMarker)
                    .build()
            )
            uploadIds += response.uploads()
                .asSequence()
                .filter { it.key() == key }
                .map { it.uploadId() }
                .toList()
            keyMarker = response.nextKeyMarker()
            uploadIdMarker = response.nextUploadIdMarker()
        } while (response.isTruncated == true)

        for (uploadId in uploadIds) {
            abortMultipartUploadIfPresent(key, uploadId)
        }
        uploadIds.size
    }

    private fun abortMultipartUploadIfPresent(key: String, uploadId: String) {
        try {
            client.abortMultipartUpload(
                AbortMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .build()
            )
        } catch (e: S3Exception) {
            if (e.awsErrorDetails()?.errorCode() != "NoSuchUpload") throw e
        }
    }

    override suspend fun getInputStreamRange(
        path: ObjectPath,
        range: LongRange
    ): InputStream = openOnStorageDispatcher {
        try {
            AbortOnEarlyCloseInputStream(
                client.getObject(
                    GetObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(path.toString())
                        .range("bytes=${range.first}-${range.last}")
                        .build()
                )
            )
        } catch (e: NoSuchKeyException) {
            throw ObjectNotFoundException(path, e)
        }
    }

    override suspend fun delete(path: ObjectPath) = withContext(StorageDispatcher) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(path.toString()).build())
        Unit
    }

    companion object {
        private const val MAX_MULTIPART_PARTS = 10_000
        private const val MAX_MULTIPART_PART_SIZE = 5L * 1024L * 1024L * 1024L
    }
}

/**
 * Closing an S3 body before it is fully read makes the SDK read the rest, so the connection can be
 * reused. For a client that disconnected early from a large download that is the whole remaining
 * object, read on the closing thread. This aborts the connection instead when the body was not
 * read to the end, and closes normally (keeping the connection) when it was: at end of stream, or
 * once the response's full content length has been read.
 */
internal class AbortOnEarlyCloseInputStream(
    private val response: ResponseInputStream<GetObjectResponse>,
) : FilterInputStream(response) {
    private val contentLength: Long? = response.response().contentLength()
    private var bytesRead = 0L
    private var endOfStream = false

    private val finished: Boolean
        get() = endOfStream || (contentLength != null && bytesRead >= contentLength)

    override fun read(): Int = super.read().also { if (it < 0) endOfStream = true else bytesRead++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it < 0) endOfStream = true else bytesRead += it }

    override fun skip(n: Long): Long = super.skip(n).also { bytesRead += it }

    override fun close() {
        if (!finished) response.abort()
        super.close()
    }
}
