package bosca.storage.service

import bosca.di.ObjectProvider
import bosca.security.service.SecurityService
import bosca.service.annotation.ServiceImplementation
import bosca.storage.configuration.StorageConfiguration
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException

/** Creates independent stores and closes operation-scoped stores when their work ends. */
@ServiceImplementation
class ObjectStorageServiceFactoryImpl(
    private val urlSigner: ObjectProvider<UrlSigner>,
    private val securityService: ObjectProvider<SecurityService>,
) : ObjectStorageServiceFactory {
    override suspend fun create(configuration: StorageConfiguration): ManagedObjectStorageService {
        var opened: ManagedObjectStorageService? = null
        try {
            return withContext(StorageDispatcher) {
                createStorage(configuration).also {
                    opened = it
                    currentCoroutineContext().ensureActive()
                }
            }
        } catch (failure: CancellationException) {
            // Cancellation can discard the result while handing it back to the caller.
            try {
                withContext(NonCancellable + StorageDispatcher) { opened?.close() }
            } catch (closeFailure: Exception) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    override suspend fun <T> withStorage(
        configuration: StorageConfiguration,
        block: suspend (ManagedObjectStorageService) -> T,
    ): T = withContext(StorageDispatcher) {
        create(configuration).use { storage ->
            currentCoroutineContext().ensureActive()
            block(storage)
        }
    }

    private fun createStorage(configuration: StorageConfiguration): ManagedObjectStorageService = when (configuration.type) {
        "s3" -> createS3Storage(configuration)
        "fs" -> ManagedFileSystemObjectStorageService(
            urlPrefix = configuration.urlPrefix,
            urlUploadPrefix = configuration.urlUploadPrefix ?: configuration.urlPrefix,
            urlSigner = urlSigner,
            basePath = requireNotNull(configuration.basePath) { "Object storage filesystem base path is required" },
            securityService = securityService,
        )
        "noop" -> ManagedNoOpObjectStorageService()
        else -> throw IllegalArgumentException("Unsupported storage type: ${configuration.type}")
    }

    private fun createS3Storage(configuration: StorageConfiguration): ManagedObjectStorageService {
        val bucket = requireNotNull(configuration.bucket) { "Object storage bucket is required" }
        val client = createS3Client(configuration)
        try {
            if (configuration.createBucket) {
                try {
                    client.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
                } catch (_: NoSuchBucketException) {
                    client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
                }
            }
            return ManagedS3ObjectStorageService(
                bucket = bucket,
                client = client,
                urlPrefix = configuration.urlPrefix,
                urlUploadPrefix = configuration.urlUploadPrefix ?: configuration.urlPrefix,
                urlSigner = urlSigner,
                securityService = securityService,
            )
        } catch (failure: Throwable) {
            try {
                client.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    private fun createS3Client(configuration: StorageConfiguration): S3Client {
        val builder = S3Client.builder()
            // Compatible stores do not consistently implement optional trailer checksums.
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        configuration.forcePathStyle?.let { builder.forcePathStyle(it) }
        configuration.region?.let { builder.region(Region.of(it)) }
        configuration.accessKeyId?.let { key ->
            val secret = requireNotNull(configuration.accessKeySecret) { "Object storage access key secret is required" }
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret)))
        }
        (configuration.endpoint ?: System.getenv("AWS_ENDPOINT"))?.let {
            builder.endpointOverride(URI.create(it))
        }
        return builder.build()
    }
}
