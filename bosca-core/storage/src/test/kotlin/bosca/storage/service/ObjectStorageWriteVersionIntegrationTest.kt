package bosca.storage.service

import bosca.storage.configuration.StorageConfiguration
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest
import software.amazon.awssdk.services.s3.model.VersioningConfiguration
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Real SDK writes and retained-version reads against an isolated S3-compatible service. */
class ObjectStorageWriteVersionIntegrationTest {
    private lateinit var container: GenericContainer<Nothing>
    private lateinit var client: S3Client
    private lateinit var storage: ObjectStorageService
    private val path = StringObjectPath("asset")

    @Before
    fun start() {
        container = GenericContainer<Nothing>("minio/minio:RELEASE.2022-02-07T08-17-33Z").apply {
            withEnv("MINIO_ROOT_USER", "storage-test")
            withEnv("MINIO_ROOT_PASSWORD", "storage-test-password")
            withExposedPorts(9000)
            withCommand("server", "/data{1...4}")
            waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000))
            start()
        }
        client = S3Client.builder()
            .endpointOverride(URI("http://${container.host}:${container.getMappedPort(9000)}"))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("storage-test", "storage-test-password")))
            .forcePathStyle(true)
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .build()
        client.createBucket(CreateBucketRequest.builder().bucket("write-test").build())
        storage = runBlocking {
            ObjectStorageServiceFactoryImpl(mockk(), mockk()).create(StorageConfiguration(
                type = "s3",
                urlPrefix = "",
                bucket = "write-test",
                endpoint = "http://${container.host}:${container.getMappedPort(9000)}",
                region = "us-east-1",
                forcePathStyle = true,
                accessKeyId = "storage-test",
                accessKeySecret = "storage-test-password",
            ))
        }
    }

    @After
    fun stop() {
        try {
            try {
                if (::storage.isInitialized) storage.close()
            } finally {
                if (::client.isInitialized) client.close()
            }
        } finally {
            if (::container.isInitialized) container.stop()
        }
    }

    @Test
    fun `streamed completion retains its exact bytes after overwrite and current deletion`(): Unit = runBlocking {
        enableVersioning()
        val original = ByteArray(6_500_003) { it.toByte() }
        val first = storage.setInputStreamWithInfo(path, original.inputStream())
        val second = storage.setInputStreamWithInfo(path, "replacement".byteInputStream(), 11)
        val selected = assertNotNull(first.version)
        assertNotEquals(selected, second.version)
        assertEquals(original.size.toLong(), first.length)
        assertNotNull(first.etag)
        assertEquals(second.version, storage.getObjectInfo(path).version)
        assertEquals("replacement", storage.getString(path))
        client.deleteObject(DeleteObjectRequest.builder().bucket("write-test").key("asset").build())
        assertContentEquals(original, storage.getInputStream(path, selected).use { it.readAllBytes() })
    }

    @Test
    fun `explicit multipart result and existing Unit API select different retained revisions`(): Unit = runBlocking {
        enableVersioning()
        val original = ByteArray(5 * 1024 * 1024) { 9 }
        val upload = storage.createMultipartUpload(path)
        storage.uploadMultipartPart(path, upload, 1, original.inputStream(), original.size.toLong())
        storage.uploadMultipartPart(path, upload, 2, "end".byteInputStream(), 3)
        val first = storage.completeMultipartUploadWithInfo(path, upload, 2, original.size + 3L)
        val replacement = storage.createMultipartUpload(path)
        storage.uploadMultipartPart(path, replacement, 1, "replacement".byteInputStream(), 11)
        storage.completeMultipartUpload(path, replacement, 1, 11)
        val selected = assertNotNull(first.version)
        assertNotEquals(selected, storage.getObjectInfo(path).version)
        assertEquals(original.size + 3L, first.length)
        assertContentEquals(original + "end".toByteArray(), storage.getInputStream(path, selected).use { it.readAllBytes() })
        assertEquals("replacement", storage.getString(path))
    }

    @Test
    fun `unversioned writes report no retained identity and existing count API still works`(): Unit = runBlocking {
        val info = storage.setInputStreamWithInfo(path, "data".byteInputStream(), 4)
        assertEquals(4, info.length)
        assertNull(info.version)
        assertEquals(11, storage.setInputStream(path, "replacement".byteInputStream()))
        assertEquals("replacement", storage.getString(path))
    }

    private fun enableVersioning() {
        client.putBucketVersioning(PutBucketVersioningRequest.builder().bucket("write-test")
            .versioningConfiguration(VersioningConfiguration.builder().status(BucketVersioningStatus.ENABLED).build())
            .build())
    }
}
