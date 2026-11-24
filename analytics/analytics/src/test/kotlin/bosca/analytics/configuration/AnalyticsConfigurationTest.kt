@file:OptIn(bosca.di.annotation.InternalDI::class)

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
        assertEquals(listOf("1.0.0", "1.0.1", "1.0.2", "1.0.3", "1.0.4", "1.0.5", "1.0.6", "1.0.7", "1.0.8", "1.0.9", "1.0.10"), pkg.versions.map { it.version })
        assertEquals(listOf("analytics-queries"), pkg.versions.last().installerNames)
    }

    @Test
    fun `application configuration providers support defaults and explicit values`() {
        val defaultApplication = application("bosca: {}")
        assertEquals(16, configuration.eventProcessingConfiguration(defaultApplication).workerCount)
        assertEquals(false, configuration.eventProcessingConfiguration(defaultApplication).fumsEnabled)
        assertEquals(100, configuration.analyticsQueryCacheConfiguration(defaultApplication).maxEntriesPerQuery)

        val configured = application(
            """
            eventProcessing:
              fumsEnabled: true
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
        assertEquals(true, processing.fumsEnabled)
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
    fun `repository provider selects queue forwarding or the shared Iceberg storage instance`() = runTest {
        val errors = mockk<ErrorGroupService>()
        val health = configuration.eventProcessingHealth()
        val natsApplication = application(
            """
            eventRepository:
              type: nats
            """,
        )
        val icebergApplication = application("bosca: {}")
        val storage = mockk<EventRepository>()
        val unusedStorage = mockk<bosca.di.ObjectProvider<EventRepository>>()
        coEvery { unusedStorage.get() } throws AssertionError("Queue forwarding must not resolve Iceberg storage")

        assertIs<CompositeEventRepository>(
            configuration.eventRepository(
                natsApplication,
                mockk<bosca.analytics.queue.AnalyticsEventQueue>(relaxed = true).asProvider(),
                unusedStorage,
                errors,
                health,
            ),
        )
        assertSame(
            storage,
            configuration.eventRepository(
                icebergApplication,
                bosca.di.MissingObjectProvider(bosca.analytics.queue.AnalyticsEventQueue::class, null),
                storage.asProvider(),
                errors,
                health,
            ),
        )
        natsApplication.shutdown()
        icebergApplication.shutdown()
    }
    @Test
    fun `disk queue configuration and named final storage resolve without NATS`(): Unit = runTest {
        val directory = java.nio.file.Files.createTempDirectory("analytics-config-spool")
        val app = application("""
            analyticsEventQueue:
              type: disk
              path: "$directory"
              maxBytes: 1024
              segmentMaxEvents: 2
              segmentMaxAgeMillis: 60000
        """)
        try {
            val queue = configuration.analyticsEventQueue(app, bosca.di.MissingObjectProvider(NatsConnectionPool::class, null), Json, configuration.eventProcessingHealth())
            queue.checkReadiness()
            assertEquals(1024L, queue.status().capacityBytes)
            val events = bosca.analytics.model.Events(events = listOf(bosca.analytics.model.Event(1, type = bosca.analytics.model.EventType.Session)), sent = 1, sentMicros = 0)
            queue.enqueue(events)
            kotlin.test.assertNull(queue.receive(kotlin.time.Duration.ZERO))
            queue.enqueue(events)
            val deliveries = queue.receiveBatch(10, kotlin.time.Duration.ZERO)
            assertEquals(1, deliveries.size)
            queue.acknowledgeBatch(deliveries)
            assertIs<CompositeEventRepository>(configuration.analyticsEventStorage(app, iceberg(), mockk<Table>(), mockk<EventsTransform<List<Record>>>(), ProtoBuf, mockk<ErrorGroupService>(), EventProcessingConfiguration()))
        } finally { app.shutdown(); directory.toFile().deleteRecursively() }
        val invalid = application("analyticsEventQueue:\n  type: postgres")
        kotlin.test.assertFailsWith<IllegalStateException> {
            configuration.analyticsEventQueue(invalid, bosca.di.MissingObjectProvider(NatsConnectionPool::class, null), Json, configuration.eventProcessingHealth())
        }
        invalid.shutdown()
    }

    @Test
    fun `collector startup recovers accepted active data while processor construction leaves producer ownership alone`(): Unit = kotlinx.coroutines.runBlocking {
        for (repository in listOf("queue", "nats")) {
            val directory = java.nio.file.Files.createTempDirectory("analytics-idle-startup")
            val original = bosca.analytics.queue.DiskAnalyticsEventQueue(directory, Json)
            val events = bosca.analytics.model.Events(events = listOf(bosca.analytics.model.Event(1, type = bosca.analytics.model.EventType.Session)), sent = 1, sentMicros = 0)
            original.enqueue(events); original.shutdown()
            val sealed = directory.resolve("00000000000000000001.segment")
            val active = directory.resolve("00000000000000000001.open")
            java.nio.file.Files.move(sealed, active)
            java.io.RandomAccessFile(active.toFile(), "rw").use { it.setLength(it.length() - 24) }
            fun yaml(type: String) = "eventRepository:\n  type: $type\nanalyticsEventQueue:\n  type: disk\n  path: '$directory'"
            val collector = application(yaml(repository))
            val processor = application(yaml("iceberg"))
            try {
                val producer = configuration.analyticsEventQueue(collector, bosca.di.MissingObjectProvider(NatsConnectionPool::class, null), Json, configuration.eventProcessingHealth())
                producer.checkReadiness()
                val consumer = configuration.analyticsEventQueue(processor, bosca.di.MissingObjectProvider(NatsConnectionPool::class, null), Json, configuration.eventProcessingHealth())
                consumer.checkReadiness()
                kotlin.test.assertFalse(java.nio.file.Files.exists(active))
                val recovered = kotlin.test.assertNotNull(consumer.receive(kotlin.time.Duration.ZERO))
                assertEquals(listOf(events), recovered.batches)
                recovered.acknowledge()
                producer.enqueue(events)
            } finally { collector.shutdown(); processor.shutdown(); directory.toFile().deleteRecursively() }
        }
    }


    @Test
    fun `default disk queue limits and idle rollover work without broker providers`(): Unit = kotlinx.coroutines.runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("analytics-default-spool")
        val app = application("analyticsEventQueue:\n  type: disk\n  path: '$directory'")
        try {
            val queue = configuration.analyticsEventQueue(app, bosca.di.MissingObjectProvider(NatsConnectionPool::class, null), Json, configuration.eventProcessingHealth())
            val events = bosca.analytics.model.Events(events = listOf(bosca.analytics.model.Event(1, type = bosca.analytics.model.EventType.Session)), sent = 1, sentMicros = 0)
            queue.enqueue(events)
            assertEquals(1_073_741_824L, queue.status().capacityBytes)
            kotlin.test.assertNull(queue.receive(kotlin.time.Duration.ZERO))
            val delivery = kotlin.test.assertNotNull(queue.receive(kotlin.time.Duration.parse("3s")))
            assertEquals(listOf(events), delivery.batches); delivery.acknowledge()
            queue.checkReadiness()
        } finally { app.shutdown(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun `default and explicit NATS queue bindings remain lazy until use`(): Unit = runTest {
        val nats = mockk<NatsConnectionPool>()
        for (yaml in listOf("bosca: {}", "analyticsEventQueue:\n  type: nats")) {
            val app = application(yaml)
            try {
                assertIs<bosca.analytics.queue.NatsAnalyticsEventQueue>(configuration.analyticsEventQueue(app, nats.asProvider(), Json, configuration.eventProcessingHealth()))
                io.mockk.coVerify(exactly = 0) { nats.systemConnection() }
            } finally { app.shutdown() }
        }
    }
    @Test
    fun `unused disk binding keeps default path lazy without creating storage`(): Unit = runTest {
        val app = application("analyticsEventQueue:\n  type: disk")
        try {
            assertIs<bosca.analytics.queue.DiskAnalyticsEventQueue>(configuration.analyticsEventQueue(app, bosca.di.MissingObjectProvider(NatsConnectionPool::class, null), Json, configuration.eventProcessingHealth()))
        } finally { app.shutdown() }
    }


    @Test
    fun `fully specified processing and cache settings reach their configuration providers`(): Unit = runTest {
        val app = application("""
            eventProcessing:
              workerCount: 2
              channelCapacity: 3
              natsBatchSize: 4
              natsFetchTimeoutSeconds: 6
              scriptServiceAccount: analytics-test
              fumsEnabled: true
            analyticsQueryCache:
              maxEntriesPerQuery: 9
              idleRetentionDays: 8
              maxStaleIntervals: 7
              refreshQueryBatchSize: 6
              pruneBatchSize: 5
        """)
        try {
            val processing = configuration.eventProcessingConfiguration(app)
            assertEquals(2, processing.workerCount)
            assertEquals(3, processing.channelCapacity)
            assertEquals(4, processing.natsBatchSize)
            assertEquals(6L, processing.natsFetchTimeoutSeconds)
            assertEquals("analytics-test", processing.scriptServiceAccount)
            kotlin.test.assertTrue(processing.fumsEnabled)
            val cache = configuration.analyticsQueryCacheConfiguration(app)
            assertEquals(9, cache.maxEntriesPerQuery)
            assertEquals(8, cache.idleRetentionDays)
            assertEquals(7, cache.maxStaleIntervals)
            assertEquals(6, cache.refreshQueryBatchSize)
            assertEquals(5, cache.pruneBatchSize)
        } finally { app.shutdown() }
    }

    @Test
    fun `queue forwarding repository resolves without any broker or Iceberg binding`(): Unit = runTest {
        val app = application("eventRepository:\n  type: queue")
        val queue = mockk<bosca.analytics.queue.AnalyticsEventQueue>(relaxed = true)
        val unusedStorage = mockk<bosca.di.ObjectProvider<EventRepository>>()
        coEvery { unusedStorage.get() } throws AssertionError("Queue forwarding must not resolve Iceberg storage")
        try {
            val repository = configuration.eventRepository(app, queue.asProvider(), unusedStorage,
                mockk<ErrorGroupService>(relaxed = true), configuration.eventProcessingHealth())
            val events = bosca.analytics.model.Events(events = listOf(bosca.analytics.model.Event(1, type = bosca.analytics.model.EventType.Session)), sent = 1, sentMicros = 0)
            repository.process(events); repository.flush()
            io.mockk.coVerify(exactly = 1) { queue.enqueue(events) }
        } finally { app.shutdown() }
    }
}
