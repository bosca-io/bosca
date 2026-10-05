package bosca.analytics.configuration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class EventProcessingConfigurationTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `defaults and explicit fields serialize and decode`() {
        val constructedDefaults = EventProcessingConfiguration()
        assertEquals(16, constructedDefaults.workerCount)
        val defaults = json.decodeFromString(EventProcessingConfiguration.serializer(), "{}")
        assertEquals(16, defaults.workerCount)
        assertEquals(5_000, defaults.channelCapacity)
        assertEquals(100, defaults.natsBatchSize)
        assertEquals(5L, defaults.natsFetchTimeoutSeconds)
        assertEquals("sa", defaults.scriptServiceAccount)

        val explicit = EventProcessingConfiguration(1, 2, 3, 4, "analytics-sa")
        assertEquals(
            explicit.scriptServiceAccount,
            json.decodeFromString(
                EventProcessingConfiguration.serializer(),
                json.encodeToString(EventProcessingConfiguration.serializer(), explicit),
            ).scriptServiceAccount,
        )

        val omitDefaults = Json { encodeDefaults = false }
        assertEquals(
            JsonObject(emptyMap()),
            omitDefaults.encodeToJsonElement(EventProcessingConfiguration.serializer(), constructedDefaults),
        )
        assertEquals(
            5,
            (omitDefaults.encodeToJsonElement(EventProcessingConfiguration.serializer(), explicit) as JsonObject).size,
        )
        assertEquals(
            16,
            Json { ignoreUnknownKeys = true }
                .decodeFromString(EventProcessingConfiguration.serializer(), "{\"unknown\":1}")
                .workerCount,
        )
    }
}

class AnalyticsQueryCacheConfigurationTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `defaults and explicit cache limits serialize and decode`() {
        val defaults = AnalyticsQueryCacheConfiguration()
        assertEquals(100, defaults.maxEntriesPerQuery)
        assertEquals(7, defaults.idleRetentionDays)
        assertEquals(3, defaults.maxStaleIntervals)
        assertEquals(500, defaults.refreshQueryBatchSize)
        assertEquals(500, defaults.pruneBatchSize)

        val decodedDefaults = json.decodeFromString(AnalyticsQueryCacheConfiguration.serializer(), "{}")
        assertEquals(defaults, decodedDefaults)

        val explicit = AnalyticsQueryCacheConfiguration(
            maxEntriesPerQuery = 11,
            idleRetentionDays = 12,
            maxStaleIntervals = 13,
            refreshQueryBatchSize = 14,
            pruneBatchSize = 15,
        )
        assertEquals(
            explicit,
            json.decodeFromString(
                AnalyticsQueryCacheConfiguration.serializer(),
                json.encodeToString(AnalyticsQueryCacheConfiguration.serializer(), explicit),
            ),
        )

        val omitDefaults = Json { encodeDefaults = false }
        assertEquals(
            JsonObject(emptyMap()),
            omitDefaults.encodeToJsonElement(AnalyticsQueryCacheConfiguration.serializer(), defaults),
        )
        assertEquals(
            5,
            (omitDefaults.encodeToJsonElement(AnalyticsQueryCacheConfiguration.serializer(), explicit) as JsonObject).size,
        )
        assertEquals(
            defaults,
            Json { ignoreUnknownKeys = true }
                .decodeFromString(AnalyticsQueryCacheConfiguration.serializer(), "{\"futureLimit\":1}"),
        )
    }
}

class IcebergDatabaseConfigurationTest {

    @Test
    fun `IcebergDatabaseConfiguration stores all fields`() {
        val cfg = IcebergDatabaseConfiguration(
            uri = "jdbc:postgresql://localhost:5432/iceberg",
            username = "admin",
            password = "secret"
        )
        assertEquals("jdbc:postgresql://localhost:5432/iceberg", cfg.uri)
        assertEquals("admin", cfg.username)
        assertEquals("secret", cfg.password)
        assertEquals(
            cfg.username,
            Json.decodeFromString(
                IcebergDatabaseConfiguration.serializer(),
                Json.encodeToString(IcebergDatabaseConfiguration.serializer(), cfg),
            ).username,
        )
        assertFailsWith<SerializationException> {
            Json.decodeFromString(IcebergDatabaseConfiguration.serializer(), "{}")
        }
    }
}

class IcebergS3ConfigurationTest {

    @Test
    fun `IcebergS3Configuration stores all fields`() {
        val cfg = IcebergS3Configuration(
            bucket = "my-bucket",
            endpoint = "http://minio:9000",
            pathStyleAccess = true,
            accessKeyId = "access-key",
            secretAccessKey = "secret-key",
            region = "us-east-1"
        )
        assertEquals("my-bucket", cfg.bucket)
        assertEquals("http://minio:9000", cfg.endpoint)
        assertEquals(true, cfg.pathStyleAccess)
        assertEquals("access-key", cfg.accessKeyId)
        assertEquals("secret-key", cfg.secretAccessKey)
        assertEquals("us-east-1", cfg.region)
    }

    @Test
    fun `IcebergS3Configuration allows null fields`() {
        val cfg = IcebergS3Configuration(
            bucket = null,
            endpoint = null,
            pathStyleAccess = null,
            accessKeyId = null,
            secretAccessKey = null,
            region = null
        )
        assertNull(cfg.bucket)
        assertNull(cfg.endpoint)
        assertNull(cfg.pathStyleAccess)
        assertNull(
            Json.decodeFromString(
                IcebergS3Configuration.serializer(),
                Json.encodeToString(IcebergS3Configuration.serializer(), cfg),
            ).endpoint,
        )
        assertFailsWith<SerializationException> {
            Json.decodeFromString(IcebergS3Configuration.serializer(), "{}")
        }
    }
}

class IcebergConfigurationTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `IcebergConfiguration stores all required fields`() {
        val db = IcebergDatabaseConfiguration(
            uri = "jdbc:postgresql://localhost/iceberg",
            username = "u",
            password = "p"
        )
        val cfg = IcebergConfiguration(
            catalog = "bosca",
            fileIO = "org.apache.iceberg.aws.s3.S3FileIO",
            localPath = "/tmp/iceberg",
            namespace = "analytics",
            table = "events",
            database = db,
            warehouseLocation = "s3://warehouse/iceberg"
        )
        assertEquals("bosca", cfg.catalog)
        assertEquals("org.apache.iceberg.aws.s3.S3FileIO", cfg.fileIO)
        assertEquals("/tmp/iceberg", cfg.localPath)
        assertEquals("analytics", cfg.namespace)
        assertEquals("events", cfg.table)
        assertEquals("s3://warehouse/iceberg", cfg.warehouseLocation)
        assertNull(cfg.s3)
        val omitDefaults = Json { encodeDefaults = false }
        assertEquals(
            false,
            "s3" in (omitDefaults.encodeToJsonElement(IcebergConfiguration.serializer(), cfg) as JsonObject),
        )
        assertEquals(
            true,
            "s3" in (json.encodeToJsonElement(IcebergConfiguration.serializer(), cfg) as JsonObject),
        )
    }

    @Test
    fun `IcebergConfiguration includes optional S3 config`() {
        val db = IcebergDatabaseConfiguration(uri = "jdbc:pg", username = "u", password = "p")
        val s3 = IcebergS3Configuration(
            bucket = "b", endpoint = "http://minio:9000", pathStyleAccess = true,
            accessKeyId = "a", secretAccessKey = "s", region = "us-east-1"
        )
        val cfg = IcebergConfiguration(
            catalog = "c", fileIO = "f", localPath = "/tmp", namespace = "n",
            table = "t", database = db, warehouseLocation = null, s3 = s3
        )
        assertEquals("b", cfg.s3?.bucket)
        assertNull(cfg.warehouseLocation)
        assertEquals(
            "b",
            json.decodeFromString(
                IcebergConfiguration.serializer(),
                json.encodeToString(IcebergConfiguration.serializer(), cfg),
            ).s3?.bucket,
        )
        val omitDefaults = Json { encodeDefaults = false }
        val defaultEncoded = omitDefaults.encodeToJsonElement(IcebergConfiguration.serializer(), cfg) as JsonObject
        assertEquals(true, "s3" in defaultEncoded)
        assertFailsWith<SerializationException> {
            Json.decodeFromString(IcebergConfiguration.serializer(), "{}")
        }
        val withUnknown = Json { ignoreUnknownKeys = true }.decodeFromString(
            IcebergConfiguration.serializer(),
            """{"catalog":"c","fileIO":"f","localPath":"l","namespace":"n","table":"t","database":{"uri":"u","username":"n","password":"p"},"warehouseLocation":null,"unknown":1}""",
        )
        assertNull(withUnknown.s3)
    }

    @Test
    fun `Iceberg configuration serializer covers omitted and present optional S3`() {
        val withoutS3 =
            """{"catalog":"c","fileIO":"f","localPath":"/tmp","namespace":"n","table":"t","database":{"uri":"u","username":"n","password":"p"},"warehouseLocation":null}"""
        assertNull(json.decodeFromString(IcebergConfiguration.serializer(), withoutS3).s3)

        val config = IcebergConfiguration(
            catalog = "c",
            fileIO = "f",
            localPath = "/tmp",
            namespace = "n",
            table = "t",
            database = IcebergDatabaseConfiguration("u", "n", "p"),
            warehouseLocation = null,
            s3 = IcebergS3Configuration("b", "e", true, "a", "s", "r"),
        )
        assertEquals(
            "b",
            json.decodeFromString(
                IcebergConfiguration.serializer(),
                json.encodeToString(IcebergConfiguration.serializer(), config),
            ).s3?.bucket,
        )
    }
}
