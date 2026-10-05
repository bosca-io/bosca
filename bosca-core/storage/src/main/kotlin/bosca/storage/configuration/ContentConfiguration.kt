package bosca.storage.configuration

import bosca.di.ObjectProvider
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.security.service.SecurityService
import bosca.storage.service.FileSystemObjectStorageService
import bosca.storage.service.NoOpObjectStorageService
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.S3ObjectStorageService
import bosca.storage.service.UrlSigner
import bosca.storage.service.UrlSignerImpl
import bosca.server.BoscaApplication
import kotlinx.serialization.Serializable
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import java.net.URI

@Serializable
data class StorageConfiguration(
    val type: String,
    val urlPrefix: String,
    val urlUploadPrefix: String? = null,
    val bucket: String? = null,
    val createBucket: Boolean = false,
    val basePath: String? = null,
    val forcePathStyle: Boolean? = null,
    val region: String? = null,
    val endpoint: String? = null,
    val accessKeyId: String? = null,
    val accessKeySecret: String? = null
)

@Providers
class ContentConfiguration {

    @Provider(singleton = true)
    fun urlSigner(application: BoscaApplication): UrlSigner {
        val secretKey = application.environment.config.propertyOrNull("content.urlSigner.secretKey")?.getString() ?: error("Missing 'content.urlSigner.secretKey' property")
        return UrlSignerImpl(secretKey)
    }

    @Provider(singleton = true)
    fun amazonS3(application: BoscaApplication): S3Client {
        val builder = S3Client
            .builder()
            // Optional trailer checksums are not implemented consistently by S3-compatible
            // stores. Required and explicitly requested checksums are still calculated.
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        val configuration = application.environment.config.propertyOrNull("content.storage")?.getAs<StorageConfiguration>() ?: StorageConfiguration("", "", null, "")
        configuration.forcePathStyle?.let { builder.forcePathStyle(it) }
        configuration.region?.let { builder.region(Region.of(it)) }
        configuration.accessKeyId?.let { builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(it, configuration.accessKeySecret ?: error("missing secret")))) }
        return builder.endpointOverride(URI.create(configuration.endpoint ?: System.getenv("AWS_ENDPOINT"))).build()
    }

    @Provider(singleton = true)
    suspend fun objectStorage(
        application: BoscaApplication,
        urlSigner: ObjectProvider<UrlSigner>,
        securityService: ObjectProvider<SecurityService>,
        client: ObjectProvider<S3Client>
    ): ObjectStorageService {
        val configuration = application.environment.config.propertyOrNull("content.storage")?.getAs<StorageConfiguration>() ?: StorageConfiguration("", "", null, "")
        return when (configuration.type) {
            "s3" -> {
                val bucket = configuration.bucket ?: error("Missing 'content.storage.bucket' property")
                val s3Client = client.get()
                if (configuration.createBucket) {
                    try {
                        s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
                    } catch (e: NoSuchBucketException) {
                        s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
                    }
                }
                S3ObjectStorageService(
                    bucket = bucket,
                    client = s3Client,
                    urlPrefix = configuration.urlPrefix,
                    urlUploadPrefix = configuration.urlUploadPrefix ?: configuration.urlPrefix,
                    urlSigner = urlSigner,
                    securityService = securityService
                )
            }

            "fs" -> FileSystemObjectStorageService(
                urlPrefix = configuration.urlPrefix,
                urlUploadPrefix = configuration.urlUploadPrefix ?: configuration.urlPrefix,
                urlSigner = urlSigner,
                basePath = configuration.basePath ?: error("Missing 'content.storage.basePath' property"),
                securityService = securityService
            )

            "noop" -> NoOpObjectStorageService()

            else -> throw IllegalArgumentException("Unsupported storage type: ${application.environment.config.propertyOrNull("content.storage")?.getString()}")
        }
    }
}
