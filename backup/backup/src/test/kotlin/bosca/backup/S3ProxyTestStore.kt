package bosca.backup

import bosca.storage.configuration.StorageConfiguration
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

/** An isolated S3Proxy with the same storage backend and authentication as the development stack. */
internal class S3ProxyTestStore : AutoCloseable {
    private val container = GenericContainer<Nothing>("andrewgaul/s3proxy:3.0.0").apply {
        withEnv("S3PROXY_AUTHORIZATION", "aws-v2-or-v4")
        withEnv("S3PROXY_IDENTITY", "bosca-s3")
        withEnv("S3PROXY_CREDENTIAL", "bosca-s3")
        withEnv("S3PROXY_ENDPOINT", "http://0.0.0.0:8000")
        withEnv("S3PROXY_IGNORE_UNKNOWN_HEADERS", "true")
        withEnv("JCLOUDS_PROVIDER", "filesystem-nio2")
        withEnv("JCLOUDS_FILESYSTEM_NIO2_BASEDIR", "/tmp/s3proxy")
        withExposedPorts(8000)
        waitingFor(Wait.forListeningPort())
        start()
    }

    fun configuration(bucket: String) = StorageConfiguration(
        type = "s3",
        urlPrefix = "",
        bucket = bucket,
        createBucket = true,
        forcePathStyle = true,
        region = "us-east-1",
        endpoint = "http://${container.host}:${container.getMappedPort(8000)}",
        accessKeyId = "bosca-s3",
        accessKeySecret = "bosca-s3",
    )

    override fun close() = container.stop()
}
