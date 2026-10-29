package bosca.storage.service

import bosca.di.ObjectProvider
import bosca.security.service.SecurityService
import java.time.ZoneOffset
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.S3Exception

/** Adds inventory access and resource ownership to the existing storage implementation. */
class ManagedS3ObjectStorageService(
    private val bucket: String,
    private val client: S3Client,
    urlPrefix: String,
    urlUploadPrefix: String,
    urlSigner: ObjectProvider<UrlSigner>,
    securityService: ObjectProvider<SecurityService>,
) : ManagedObjectStorageService, ObjectStorageService by S3ObjectStorageService(bucket, client, urlPrefix, urlUploadPrefix, urlSigner, securityService) {
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

}
