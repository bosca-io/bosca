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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
    fun `failed append rejects additional staging until it can be committed`() = runTest {
        val root = root()
        val table = table(root)
        val local = Files.createDirectories(root.resolve("buffer"))
        var failing = true
        val records = IcebergEventsToRecordTransform(EventSchema)
        val transform = object : EventsTransform<List<Record>> {
            override suspend fun transform(item: Events): List<Record> {
                if (failing) throw java.io.IOException("Synthetic append failure")
                return records.transform(item)
            }
        }
        val repository = IcebergEventRepository(table, transform, local.toString(), ProtoBuf).also(repositories::add)
        repository.process(events("before", null, false))
        kotlin.test.assertFailsWith<java.io.IOException> { repository.flush() }
        kotlin.test.assertFailsWith<java.io.IOException> { repository.process(events("after", null, false)) }
        kotlin.test.assertFailsWith<java.io.IOException> { repository.flush(force = false) }
        assertEquals(0, table.snapshots().count())

        failing = false
        repository.process(events("after", null, false))
        repository.flush()
        assertEquals(setOf("client-before", "client-after"), IcebergGenerics.read(table).build().use { rows -> rows.map { it.getField("client_id") }.toSet() })
        assertEquals(2, table.snapshots().count())
        repository.flush()
        assertEquals(2, table.snapshots().count())
        assertTrue(local.toFile().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `persistent storage failure does not accumulate retry copies in staging`() = runTest {
        val root = root()
        val table = table(root)
        val local = Files.createDirectories(root.resolve("buffer"))
        var failing = true
        val records = IcebergEventsToRecordTransform(EventSchema)
        val transform = object : EventsTransform<List<Record>> {
            override suspend fun transform(item: Events): List<Record> {
                if (failing) throw java.io.IOException("Synthetic storage outage")
                return records.transform(item)
            }
        }
        val repository = IcebergEventRepository(table, transform, local.toString(), ProtoBuf).also(repositories::add)
        val delivery = events("retry", null, false)
        repository.process(delivery)
        kotlin.test.assertFailsWith<java.io.IOException> { repository.flush() }
        val acceptedFiles = local.toFile().listFiles().orEmpty().associate { it.name to it.readBytes().toList() }
        repeat(10) {
            kotlin.test.assertFailsWith<java.io.IOException> { repository.process(delivery) }
            assertEquals(acceptedFiles, local.toFile().listFiles().orEmpty().associate { it.name to it.readBytes().toList() })
        }
        failing = false
        repository.process(delivery)
        repository.flush()
        assertEquals(2, IcebergGenerics.read(table).build().use { it.count() }, "A retry after recovery retains the at-least-once contract")
        assertTrue(local.toFile().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `retry after an ambiguous commit preserves already committed data files`() = runTest {
        val root = root()
        val table = table(root)
        var ambiguous = true
        val unreliable = object : Table by table {
            override fun newAppend(): org.apache.iceberg.AppendFiles {
                val append = table.newAppend()
                return object : org.apache.iceberg.AppendFiles by append {
                    override fun commit() {
                        append.commit()
                        if (ambiguous) {
                            ambiguous = false
                            throw java.io.IOException("Synthetic lost commit response")
                        }
                    }
                }
            }
        }
        val repository = IcebergEventRepository(unreliable, IcebergEventsToRecordTransform(EventSchema), root.resolve("buffer").toString(), ProtoBuf)
            .also(repositories::add)
        repository.process(events("ambiguous", null, false))
        kotlin.test.assertFailsWith<java.io.IOException> { repository.flush() }
        val committedId = IcebergGenerics.read(table).build().use { it.single().getField("id") }
        repository.flush()
        val ids = IcebergGenerics.read(table).build().use { rows -> rows.map { it.getField("id") }.toSet() }
        assertTrue(committedId in ids)
        assertEquals(2, ids.size, "At-least-once retries may duplicate an ambiguous commit, but never replace its immutable file")
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
    fun `parquet writer propagates record failure and refuses to commit`() {
        val root = root()
        val table = table(root)
        val writer = ParquetWriter(table, root.resolve("bad.parquet").toFile())

        try {
            kotlin.test.assertFails { writer.addAll(listOf(mockk<Record>())) }
            kotlin.test.assertFailsWith<IllegalStateException> { writer.finish() }
            assertEquals(0, writer.recordsAdded)
            assertEquals(1, writer.recordsFailed)
        } finally { writer.close() }
    }

    @Test
    fun `repository cleans stale files before committing staged data`() = runTest {
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
        repository.flush()
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
    fun `repository creates its staging directory and propagates protobuf encoding failures`() = runTest {
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

        kotlin.test.assertFailsWith<IllegalStateException> { repository.process(invalid) }
    }

    private fun groupCommitRepository(
        root: Path,
        table: Table,
        transform: EventsTransform<List<Record>> = IcebergEventsToRecordTransform(EventSchema),
        commitInterval: Duration = 1.minutes,
        commitMaxRecords: Int = Int.MAX_VALUE,
        storeConcurrency: Int = Int.MAX_VALUE,
    ) = IcebergEventRepository(
        table, transform, Files.createDirectories(root.resolve("buffer")).toString(), ProtoBuf,
        commitInterval, commitMaxRecords, storeConcurrency,
    ).also(repositories::add)

    private fun rows(table: Table) = IcebergGenerics.read(table).build().use { it.count() }

    @Test
    fun `concurrent stores share one commit once every caller is waiting`() = runBlocking {
        val root = root()
        val table = table(root)
        val repository = groupCommitRepository(root, table, storeConcurrency = 3)
        val started = TimeSource.Monotonic.markNow()
        coroutineScope {
            listOf("a", "b", "c").map { session -> async(Dispatchers.IO) { repository.store(listOf(events(session, null))) } }.awaitAll()
        }
        assertTrue(started.elapsedNow() < 30.seconds, "A full group must not wait for the commit interval")
        assertEquals(1, table.snapshots().count())
        assertEquals(3, rows(table))
    }

    @Test
    fun `a lone store commits after the commit interval and the record limit commits early`() = runBlocking {
        val root = root()
        val table = table(root)
        val interval = groupCommitRepository(root, table, commitInterval = 200.milliseconds)
        val started = TimeSource.Monotonic.markNow()
        interval.store(listOf(events("lone", null)))
        assertTrue(started.elapsedNow() >= 200.milliseconds)
        assertEquals(1, table.snapshots().count())

        val limited = groupCommitRepository(root(), table, commitMaxRecords = 1)
        val early = TimeSource.Monotonic.markNow()
        limited.store(listOf(events("limited", null)))
        assertTrue(early.elapsedNow() < 30.seconds)
        assertEquals(2, table.snapshots().count())
        assertEquals(2, rows(table))
    }

    @Test
    fun `a transform failure fails only its own store in a shared commit`() = runBlocking {
        val root = root()
        val table = table(root)
        val records = IcebergEventsToRecordTransform(EventSchema)
        val transform = object : EventsTransform<List<Record>> {
            override suspend fun transform(item: Events): List<Record> =
                if (item.context?.sessionId == "bad") throw IllegalStateException("bad event") else records.transform(item)
        }
        val repository = groupCommitRepository(root, table, transform, storeConcurrency = 2)
        coroutineScope {
            val bad = async(Dispatchers.IO) { runCatching { repository.store(listOf(events("bad", null))) } }
            val good = async(Dispatchers.IO) { repository.store(listOf(events("good", null))) }
            assertIs<IllegalStateException>(bad.await().exceptionOrNull())
            good.await()
        }
        assertEquals(1, table.snapshots().count())
        assertEquals(1, rows(table))
    }

    @Test
    fun `shutdown fails waiting and later stores without committing them`() = runBlocking {
        val root = root()
        val table = table(root)
        val repository = groupCommitRepository(root, table)
        val waiting = async(Dispatchers.IO) { runCatching { repository.store(listOf(events("waiting", null))) } }
        delay(100)
        repository.shutdown()
        assertIs<IllegalStateException>(waiting.await().exceptionOrNull())
        assertFailsWith<IllegalStateException> { repository.store(listOf(events("late", null))) }
        assertEquals(0, table.snapshots().count())
    }
}
