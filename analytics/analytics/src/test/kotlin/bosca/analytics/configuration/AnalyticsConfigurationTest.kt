package bosca.analytics.configuration

import bosca.analytics.repository.CompositeEventRepository
import bosca.analytics.repository.EventRepository
import bosca.analytics.service.AnalyticsQueryService
import bosca.analytics.service.ErrorGroupService
import bosca.analytics.transform.EventsTransform
import bosca.di.asProvider
import bosca.lock.DistributedLockFactory
import bosca.nats.NatsConnectionPool
import bosca.observability.ErrorCapture
import bosca.security.service.SecurityService
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.apache.iceberg.CatalogUtil
import org.apache.iceberg.Schema
import org.apache.iceberg.Table
import org.apache.iceberg.UpdateSchema
import org.apache.iceberg.catalog.Catalog
import org.apache.iceberg.catalog.Namespace
import org.apache.iceberg.catalog.SupportsNamespaces
import org.apache.iceberg.catalog.TableIdentifier
import org.apache.iceberg.data.Record
import org.apache.iceberg.types.Types
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import io.mockk.verify

class AnalyticsConfigurationTest {
    private val configuration = AnalyticsConfiguration()

    @AfterTest
    fun tearDown() {
        runCatching { unmockkStatic(CatalogUtil::class) }
    }

    private fun application(yaml: String) =
        BoscaApplication(ApplicationConfig.load(yaml.trimIndent().byteInputStream()))

    private fun iceberg(s3: IcebergS3Configuration? = null) = IcebergConfiguration(
        catalog = "analytics",
        fileIO = "file-io",
        localPath = "/tmp/analytics",
        namespace = "events",
        table = "raw_events",
        database = IcebergDatabaseConfiguration("jdbc:test", "user", "password"),
        warehouseLocation = "s3://warehouse",
        s3 = s3,
    )

    @Test
    fun `basic providers expose migrations schemas transforms queues and installers`() {
        assertIs<AnalyticsMigration>(configuration.migration())
        assertSame(bosca.analytics.iceberg.EventSchema, configuration.eventSchema())
        assertIs<bosca.analytics.transform.iceberg.IcebergEventsToRecordTransform>(
            configuration.icebergEventTransform(configuration.eventSchema()),
        )

        val queue = mockk<JobQueue>()
        val factory = mockk<JobQueueFactory>()
        every { factory.create("analytics") } returns queue
        assertSame(queue, configuration.analyticsJobQueue(factory))
        assertIs<bosca.sharedqueue.jobs.JobRunner>(
            configuration.analyticsJobQueueRunner(
                queue,
                mockk<DistributedLockFactory>(),
                mockk<ErrorCapture>().asProvider(),
            ),
        )

        val queryService = mockk<AnalyticsQueryService>()
        val security = mockk<SecurityService>()
        val app = application("bosca: {}")
        assertIs<bosca.analytics.installer.QueryInstaller>(configuration.analyticsQueriesInstaller(queryService, security, app))
        assertIs<bosca.analytics.installer.RawEventsQueryInstaller>(configuration.analyticsRawEventsQueriesInstaller(queryService, security, app))
        val pkg = configuration.analyticsQueriesPackage()
        assertEquals("analytics-queries", pkg.key)
        assertEquals(listOf("1.0.0", "1.0.1", "1.0.2", "1.0.3", "1.0.4", "1.0.5", "1.0.6", "1.0.7", "1.0.8", "1.0.9"), pkg.versions.map { it.version })
        assertEquals(listOf("analytics-queries"), pkg.versions.last().installerNames)
    }

    @Test
    fun `application configuration providers support defaults and explicit values`() {
        val defaultApplication = application("bosca: {}")
        assertEquals(16, configuration.eventProcessingConfiguration(defaultApplication).workerCount)
        assertEquals(100, configuration.analyticsQueryCacheConfiguration(defaultApplication).maxEntriesPerQuery)

        val configured = application(
            """
            eventProcessing:
              workerCount: 2
              channelCapacity: 12
              natsBatchSize: 3
              natsFetchTimeoutSeconds: 4
              scriptServiceAccount: analytics-sa
            analyticsQueryCache:
              maxEntriesPerQuery: 12
              idleRetentionDays: 2
              maxStaleIntervals: 4
              refreshQueryBatchSize: 15
              pruneBatchSize: 20
            iceberg:
              catalog: analytics
              fileIO: file-io
              localPath: /tmp/analytics
              namespace: events
              table: raw_events
              database:
                uri: jdbc:test
                username: user
                password: password
              warehouseLocation: s3://warehouse
            """,
        )
        val processing = configuration.eventProcessingConfiguration(configured)
        assertEquals(2, processing.workerCount)
        assertEquals(12, processing.channelCapacity)
        assertEquals("analytics-sa", processing.scriptServiceAccount)
        val cache = configuration.analyticsQueryCacheConfiguration(configured)
        assertEquals(12, cache.maxEntriesPerQuery)
        assertEquals(15, cache.refreshQueryBatchSize)
        assertEquals("raw_events", configuration.icebergConfiguration(configured).table)

        val invalidCache = application(
            """
            analyticsQueryCache:
              maxEntriesPerQuery: 0
              idleRetentionDays: -1
              maxStaleIntervals: 0
              refreshQueryBatchSize: -3
              pruneBatchSize: -2
            """,
        )
        assertEquals(
            AnalyticsQueryCacheConfiguration(
                maxEntriesPerQuery = 1,
                idleRetentionDays = 1,
                maxStaleIntervals = 1,
                refreshQueryBatchSize = 1,
                pruneBatchSize = 1,
            ),
            configuration.analyticsQueryCacheConfiguration(invalidCache),
        )
    }

    @Test
    fun `catalog provider maps nullable and configured S3 properties`() {
        val catalog = mockk<Catalog>()
        mockkStatic(CatalogUtil::class)
        every { CatalogUtil.buildIcebergCatalog(any(), any(), any()) } returns catalog

        assertSame(catalog, configuration.icebergCatalog(iceberg()))
        assertSame(
            catalog,
            configuration.icebergCatalog(
                iceberg(IcebergS3Configuration("bucket", null, null, null, null, null)),
            ),
        )
        assertSame(
            catalog,
            configuration.icebergCatalog(
                iceberg(
                    IcebergS3Configuration(
                        bucket = "bucket",
                        endpoint = "http://s3",
                        pathStyleAccess = false,
                        accessKeyId = "key",
                        secretAccessKey = "secret",
                        region = "us-east-1",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `table provider creates a namespace and missing table`() {
        val catalog = mockk<Catalog>(relaxed = true, moreInterfaces = arrayOf(SupportsNamespaces::class))
        val namespaces = catalog as SupportsNamespaces
        val schema = Schema(Types.NestedField.required(1, "id", Types.StringType.get()))
        val table = mockk<Table>()
        every { namespaces.namespaceExists(Namespace.of("events")) } returns false
        every { catalog.tableExists(any()) } returns false
        every { catalog.createTable(any(), schema) } returns table

        assertSame(table, configuration.icebergEventTable(iceberg(), catalog, schema))
    }

    @Test
    fun `table provider preserves an existing namespace`() {
        val catalog = mockk<Catalog>(relaxed = true, moreInterfaces = arrayOf(SupportsNamespaces::class))
        val namespaces = catalog as SupportsNamespaces
        val schema = Schema(Types.NestedField.required(1, "id", Types.StringType.get()))
        val table = mockk<Table>()
        every { namespaces.namespaceExists(Namespace.of("events")) } returns true
        every { catalog.tableExists(any()) } returns false
        every { catalog.createTable(any(), schema) } returns table

        assertSame(table, configuration.icebergEventTable(iceberg(), catalog, schema))
        verify(exactly = 0) { namespaces.createNamespace(any()) }
    }

    @Test
    fun `table provider uses an unqualified identifier for catalogs without namespaces`() {
        val catalog = mockk<Catalog>()
        val schema = Schema(Types.NestedField.required(1, "id", Types.StringType.get()))
        val table = mockk<Table>()
        every { catalog.tableExists(TableIdentifier.of("raw_events")) } returns false
        every { catalog.createTable(TableIdentifier.of("raw_events"), schema) } returns table

        assertSame(table, configuration.icebergEventTable(iceberg(), catalog, schema))
    }

    @Test
    fun `existing table evolves missing top-level and nested fields`() {
        val current = Schema(
            Types.NestedField.optional(
                1,
                "details",
                Types.StructType.of(
                    Types.NestedField.optional(2, "deep", Types.StructType.of()),
                    Types.NestedField.optional(3, "existing", Types.StringType.get()),
                ),
            ),
        )
        val expected = Schema(
            Types.NestedField.optional(
                1,
                "details",
                Types.StructType.of(
                    Types.NestedField.optional(
                        2,
                        "deep",
                        Types.StructType.of(Types.NestedField.required(4, "leaf", Types.LongType.get())),
                    ),
                    Types.NestedField.optional(3, "existing", Types.StringType.get()),
                    Types.NestedField.required(5, "required_nested", Types.IntegerType.get()),
                    Types.NestedField.optional(6, "optional_nested", Types.StringType.get()),
                ),
            ),
            Types.NestedField.required(7, "required_top", Types.LongType.get()),
            Types.NestedField.optional(8, "optional_top", Types.StringType.get()),
        )
        val update = mockk<UpdateSchema>(relaxed = true)
        val table = mockk<Table>()
        every { table.schema() } returns current
        every { table.updateSchema() } returns update
        val catalog = mockk<Catalog>()
        every { catalog.tableExists(TableIdentifier.of("raw_events")) } returns true
        every { catalog.loadTable(TableIdentifier.of("raw_events")) } returns table

        assertSame(table, configuration.icebergEventTable(iceberg(), catalog, expected))
        io.mockk.verify(exactly = 1) { update.commit() }
    }

    @Test
    fun `existing matching table does not commit a schema update`() {
        val schema = Schema(Types.NestedField.optional(1, "id", Types.StringType.get()))
        val update = mockk<UpdateSchema>(relaxed = true)
        val table = mockk<Table>()
        every { table.schema() } returns schema
        every { table.updateSchema() } returns update
        val catalog = mockk<Catalog>()
        every { catalog.tableExists(TableIdentifier.of("raw_events")) } returns true
        every { catalog.loadTable(TableIdentifier.of("raw_events")) } returns table

        assertSame(table, configuration.icebergEventTable(iceberg(), catalog, schema))
        io.mockk.verify(exactly = 0) { update.commit() }
    }

    @Test
    fun `schema evolution ignores incompatible struct shapes`() {
        val current = Schema(
            Types.NestedField.optional(1, "details", Types.StringType.get()),
            Types.NestedField.optional(
                2,
                "outer",
                Types.StructType.of(Types.NestedField.optional(3, "inner", Types.StringType.get())),
            ),
        )
        val expected = Schema(
            Types.NestedField.optional(1, "details", Types.StructType.of()),
            Types.NestedField.optional(
                2,
                "outer",
                Types.StructType.of(Types.NestedField.optional(3, "inner", Types.StructType.of())),
            ),
        )
        val update = mockk<UpdateSchema>(relaxed = true)
        val table = mockk<Table>()
        every { table.schema() } returns current
        every { table.updateSchema() } returns update
        val catalog = mockk<Catalog>()
        every { catalog.tableExists(TableIdentifier.of("raw_events")) } returns true
        every { catalog.loadTable(TableIdentifier.of("raw_events")) } returns table

        assertSame(table, configuration.icebergEventTable(iceberg(), catalog, expected))
        verify(exactly = 0) { update.commit() }
    }

    @Test
    fun `repository provider selects NATS and Iceberg backends`() = runTest {
        val natsPool = mockk<NatsConnectionPool>()
        val json = Json { ignoreUnknownKeys = true }
        val table = mockk<Table>()
        val transform = mockk<EventsTransform<List<Record>>>()
        val protoBuf = ProtoBuf
        val errors = mockk<ErrorGroupService>()
        val natsApplication = application(
            """
            eventRepository:
              type: nats
            """,
        )
        val icebergApplication = application("bosca: {}")

        assertIs<CompositeEventRepository>(
            configuration.eventRepository(
                natsApplication,
                natsPool.asProvider(),
                json.asProvider(),
                iceberg().asProvider(),
                table.asProvider(),
                transform.asProvider(),
                protoBuf.asProvider(),
                errors,
            ),
        )
        assertIs<CompositeEventRepository>(
            configuration.eventRepository(
                icebergApplication,
                natsPool.asProvider(),
                json.asProvider(),
                iceberg().asProvider(),
                table.asProvider(),
                transform.asProvider(),
                protoBuf.asProvider(),
                errors,
            ),
        )
        icebergApplication.shutdown()
    }
}
