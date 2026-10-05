package bosca.analytics.configuration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IcebergConfigurationModelsTest {

    // --- IcebergDatabaseConfiguration ---

    @Test
    fun `IcebergDatabaseConfiguration stores all fields`() {
        val config = IcebergDatabaseConfiguration(
            uri = "jdbc:postgresql://localhost:5433/iceberg",
            username = "admin",
            password = "secret"
        )
        assertEquals("jdbc:postgresql://localhost:5433/iceberg", config.uri)
        assertEquals("admin", config.username)
        assertEquals("secret", config.password)
    }

    // --- IcebergS3Configuration ---

    @Test
    fun `IcebergS3Configuration stores all fields`() {
        val config = IcebergS3Configuration(
            bucket = "my-bucket",
            endpoint = "http://localhost:9000",
            pathStyleAccess = true,
            accessKeyId = "AKID",
            secretAccessKey = "SECRET",
            region = "us-east-1"
        )
        assertEquals("my-bucket", config.bucket)
        assertEquals("http://localhost:9000", config.endpoint)
        assertEquals(true, config.pathStyleAccess)
        assertEquals("AKID", config.accessKeyId)
        assertEquals("SECRET", config.secretAccessKey)
        assertEquals("us-east-1", config.region)
    }

    @Test
    fun `IcebergS3Configuration allows all nullable fields to be null`() {
        val config = IcebergS3Configuration(
            bucket = null,
            endpoint = null,
            pathStyleAccess = null,
            accessKeyId = null,
            secretAccessKey = null,
            region = null
        )
        assertNull(config.bucket)
        assertNull(config.endpoint)
        assertNull(config.pathStyleAccess)
        assertNull(config.accessKeyId)
        assertNull(config.secretAccessKey)
        assertNull(config.region)
    }

    // --- IcebergConfiguration ---

    @Test
    fun `IcebergConfiguration stores all required fields`() {
        val dbConfig = IcebergDatabaseConfiguration(
            uri = "jdbc:postgresql://localhost/iceberg",
            username = "user",
            password = "pass"
        )
        val config = IcebergConfiguration(
            catalog = "main",
            fileIO = "org.apache.iceberg.io.FileIO",
            localPath = "/tmp/iceberg",
            namespace = "analytics",
            table = "events",
            database = dbConfig,
            warehouseLocation = "s3://warehouse"
        )
        assertEquals("main", config.catalog)
        assertEquals("org.apache.iceberg.io.FileIO", config.fileIO)
        assertEquals("/tmp/iceberg", config.localPath)
        assertEquals("analytics", config.namespace)
        assertEquals("events", config.table)
        assertEquals(dbConfig, config.database)
        assertEquals("s3://warehouse", config.warehouseLocation)
    }

    @Test
    fun `IcebergConfiguration s3 defaults to null`() {
        val dbConfig = IcebergDatabaseConfiguration(
            uri = "uri",
            username = "user",
            password = "pass"
        )
        val config = IcebergConfiguration(
            catalog = "cat",
            fileIO = "fio",
            localPath = "/tmp",
            namespace = "ns",
            table = "tbl",
            database = dbConfig,
            warehouseLocation = null
        )
        assertNull(config.s3)
    }

    @Test
    fun `IcebergConfiguration warehouseLocation can be null`() {
        val dbConfig = IcebergDatabaseConfiguration(
            uri = "uri",
            username = "user",
            password = "pass"
        )
        val config = IcebergConfiguration(
            catalog = "cat",
            fileIO = "fio",
            localPath = "/tmp",
            namespace = "ns",
            table = "tbl",
            database = dbConfig,
            warehouseLocation = null
        )
        assertNull(config.warehouseLocation)
    }

    @Test
    fun `IcebergConfiguration with S3 configuration`() {
        val dbConfig = IcebergDatabaseConfiguration(
            uri = "uri",
            username = "user",
            password = "pass"
        )
        val s3Config = IcebergS3Configuration(
            bucket = "bucket",
            endpoint = "http://s3:9000",
            pathStyleAccess = true,
            accessKeyId = "key",
            secretAccessKey = "secret",
            region = "us-west-2"
        )
        val config = IcebergConfiguration(
            catalog = "cat",
            fileIO = "fio",
            localPath = "/tmp",
            namespace = "ns",
            table = "tbl",
            database = dbConfig,
            warehouseLocation = "s3://warehouse",
            s3 = s3Config
        )
        assertEquals(s3Config, config.s3)
        assertEquals("bucket", config.s3?.bucket)
    }
}
