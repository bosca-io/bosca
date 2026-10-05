package bosca.analytics.repository.iceberg

import bosca.analytics.iceberg.EventSchema
import bosca.analytics.model.Content
import bosca.analytics.model.Device
import bosca.analytics.model.Element
import bosca.analytics.model.Event
import bosca.analytics.model.EventContext
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.model.Page
import bosca.analytics.transform.iceberg.IcebergEventsToRecordTransform
import bosca.analytics.transform.EventsTransform
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.protobuf.ProtoBuf
import org.apache.iceberg.Table
import org.apache.iceberg.data.Record
import org.apache.iceberg.data.IcebergGenerics
import org.apache.iceberg.hadoop.HadoopTables
import org.apache.iceberg.io.CloseableIterable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IcebergRepositoryIntegrationTest {
    private val roots = mutableListOf<Path>()
    private val repositories = mutableListOf<IcebergEventRepository>()

    @AfterTest
    fun tearDown() {
        runBlocking { repositories.forEach { it.shutdown() } }
        repositories.clear()
        roots.forEach { it.toFile().deleteRecursively() }
        roots.clear()
    }

    private fun root(): Path = Files.createTempDirectory("analytics-iceberg-test").also(roots::add)

    private fun table(root: Path): Table = HadoopTables().create(EventSchema, root.resolve("table").toString())

    private fun context(session: String) = EventContext(
        appId = "app",
        appVersion = "1.0",
        device = Device(
            installationId = "installation",
            manufacturer = "Bosca",
            model = "server",
            platform = "JVM",
            primaryLocale = "en",
            systemName = "Linux",
            timezone = "UTC",
            type = "server",
            version = "25",
        ),
        sessionId = session,
        userId = "user",
    )

    private fun events(session: String, page: Page?, includeElement: Boolean = true) = Events(
        context = context(session),
        events = listOf(
            Event(
                created = 1_700_000_000_000,
                createdMicros = 12,
                type = EventType.Impression,
                clientId = "client-$session",
                element = if (includeElement) {
                    Element(
                        id = "element-$session",
                        type = "page",
                        content = listOf(Content("content", "article", 1L, 0.5)),
                        extras = null,
                    )
                } else {
                    null
                },
                page = page,
            ),
        ),
        sent = 1_700_000_000_100,
        sentMicros = 13,
        received = 1_700_000_000_200,
        receivedMicros = 14,
    )

    @Test
    fun `repository persists local protobuf batches to parquet and reads grouped events`() = runTest {
        val root = root()
        val table = table(root)
        val local = Files.createDirectories(root.resolve("buffer"))
        val repository = IcebergEventRepository(
            table,
            IcebergEventsToRecordTransform(EventSchema),
            local.toString(),
            ProtoBuf,
        ).also(repositories::add)

        repository.process(events("first", Page("/first", "https://example.test/first", "First"), includeElement = false))
        repository.process(events("second", null, includeElement = false))
        repository.flush()

        val persisted = IcebergGenerics.read(table).build().use { rows -> rows.count() }
        assertEquals(2, persisted)
        assertEquals(listOf("first", "second"), repository.getEvents().toList().map { it.context?.sessionId })
        assertTrue(local.toFile().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `reader groups transformed records by envelope and handles optional page`() = runTest {
        val transform = IcebergEventsToRecordTransform(EventSchema)
        val records = transform.transform(events("first", Page("/first", "https://example.test/first", "First"))) +
            transform.transform(events("second", null))
        val reader = Reader(CloseableIterable.withNoopClose(records))
        val read = buildList {
            while (reader.hasNext()) add(reader.next())
        }

        assertEquals(listOf("first", "second"), read.map { it.context?.sessionId })
        assertEquals("/first", read.first().events.single().page?.path)
        assertEquals(null, read.last().events.single().page)
    }

    @Test
    fun `reader emits a same-envelope batch once without duplicating its final group`() = runTest {
        val transform = IcebergEventsToRecordTransform(EventSchema)
        val batch = events("same", null).let { original ->
            original.copy(events = original.events + original.events.single().copy(clientId = "second"))
        }
        val reader = Reader(CloseableIterable.withNoopClose(transform.transform(batch)))

        assertTrue(reader.hasNext())
        val result = reader.next()
        assertEquals(listOf("client-same", "second"), result.events.map { it.clientId })
        assertFalse(reader.hasNext())
        kotlin.test.assertFailsWith<NoSuchElementException> { reader.next() }
    }

    @Test
    fun `reader separates every envelope timestamp component`() = runTest {
        val transform = IcebergEventsToRecordTransform(EventSchema)
        val base = events("same", null)
        val batches = listOf(
            base,
            base.copy(sent = base.sent + 1),
            base.copy(sent = base.sent + 1, sentMicros = base.sentMicros + 1),
            base.copy(sent = base.sent + 1, sentMicros = base.sentMicros + 1, received = base.received?.plus(1)),
        )
        val reader = Reader(CloseableIterable.withNoopClose(batches.flatMap { transform.transform(it) }))
        val read = buildList { while (reader.hasNext()) add(reader.next()) }
        assertEquals(4, read.size)
    }

    @Test
    fun `local file append policy covers force count and age boundaries`() = runTest {
        val root = root()
        val table = table(root)
        val local = Files.createDirectories(root.resolve("buffer"))
        val file = LocalEventsFile(
            table,
            DataFileAppender(table),
            IcebergEventsToRecordTransform(EventSchema),
            local.toString(),
            ProtoBuf,
        )

        assertFalse(file.shouldAppend(force = false))
        assertFalse(file.shouldAppend(force = true))
        file.addEvents(events("one", null, includeElement = false))
        assertFalse(file.shouldAppend(force = false))
        assertTrue(file.shouldAppend(force = true))

        val recordsAdded = LocalEventsFile::class.java.getDeclaredField("recordsAdded").apply { isAccessible = true }
        recordsAdded.setInt(file, 100_001)
        assertTrue(file.shouldAppend(force = false))
        recordsAdded.setInt(file, 1)
        val created = LocalEventsFile::class.java.getDeclaredField("created").apply { isAccessible = true }
        created.setLong(file, System.currentTimeMillis() - 901_000)
        assertTrue(file.shouldAppend(force = false))
    }

    @Test
    fun `filesystem file IO accepts file URIs and plain paths`() {
        val root = root()
        val io = FileSystemFileIO()
        val first = root.resolve("first.bin")
        val second = root.resolve("second.bin")

        io.newOutputFile("file:$first").createOrOverwrite().use { it.write(byteArrayOf(1, 2, 3)) }
        io.newOutputFile(second.toString()).createOrOverwrite().use { it.write(byteArrayOf(4)) }
        assertEquals(3, io.newInputFile("file:$first").newStream().use { it.readBytes().size })
        assertEquals(1, io.newInputFile(second.toString()).newStream().use { it.readBytes().size })

        io.deleteFile("file:$first")
        io.deleteFile(second.toString())
        assertFalse(first.exists())
        assertFalse(second.exists())
    }

    @Test
    fun `parquet writer counts rejected records and still finishes`() {
        val root = root()
        val table = table(root)
        val writer = ParquetWriter(table, root.resolve("bad.parquet").toFile())

        writer.addAll(listOf(mockk<Record>()))
        writer.finish()

        assertEquals(0, writer.recordsAdded)
        assertEquals(1, writer.recordsFailed)
    }

    @Test
    fun `repository cleans stale files and supports asynchronous rolling`() = runTest {
        val root = root()
        val table = table(root)
        val local = Files.createDirectories(root.resolve("buffer"))
        val repository = IcebergEventRepository(table, IcebergEventsToRecordTransform(EventSchema), local.toString(), ProtoBuf)
            .also(repositories::add)
        val parquet = Files.createFile(local.resolve("stale.parquet")).toFile()
        val invalid = Files.createFile(local.resolve("invalid.events")).toFile()

        repository.appendPreviousFiles(listOf(parquet, invalid))
        assertFalse(parquet.exists())

        repository.process(events("async", null, includeElement = false))
        repository.maybeAppend(sync = false, force = true)?.join()
        assertEquals(1, IcebergGenerics.read(table).build().use { rows -> rows.count() })
    }

    @Test
    fun `repository preserves cancellation and contains ordinary transform failures`() = runTest {
        val root = root()
        val table = table(root)
        val local = Files.createDirectories(root.resolve("buffer"))
        val cancellation = IcebergEventRepository(
            table,
            object : EventsTransform<List<Record>> {
                override suspend fun transform(item: Events): List<Record> = throw CancellationException("cancel")
            },
            local.toString(),
            ProtoBuf,
        ).also(repositories::add)
        cancellation.process(events("cancel", null, false))
        kotlin.test.assertFailsWith<CancellationException> { cancellation.flush() }

        val failure = IcebergEventRepository(
            table,
            object : EventsTransform<List<Record>> {
                override suspend fun transform(item: Events): List<Record> = throw IllegalStateException("bad event")
            },
            local.toString(),
            ProtoBuf,
        ).also(repositories::add)
        failure.process(events("failure", null, false))
        kotlin.test.assertFailsWith<IllegalStateException> { failure.flush() }
    }

    @Test
    fun `repository creates its staging directory and contains protobuf encoding failures`() = runTest {
        val root = root()
        val table = table(root)
        val local = root.resolve("missing").resolve("buffer")
        val repository = IcebergEventRepository(
            table,
            IcebergEventsToRecordTransform(EventSchema),
            local.toString(),
            ProtoBuf,
        ).also(repositories::add)
        assertTrue(local.exists())
        val invalid = events("invalid", null).let { batch ->
            batch.copy(
                events = listOf(
                    batch.events.single().copy(
                        element = Element(
                            id = "element",
                            type = "test",
                            extras = JsonObject(mapOf("unsupported" to JsonPrimitive(true))),
                        ),
                    ),
                ),
            )
        }

        repository.process(invalid)
    }
}
