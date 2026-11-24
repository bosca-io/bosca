package bosca.analytics.repository.iceberg

import bosca.analytics.iceberg.EventSchema
import bosca.analytics.model.Event
import bosca.analytics.model.Events
import bosca.analytics.model.EventType
import bosca.analytics.transform.iceberg.IcebergEventsToRecordTransform
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import org.apache.iceberg.data.IcebergGenerics
import org.apache.iceberg.hadoop.HadoopTables
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LocalEventsFileIoFailureTest {
    private fun batch(id: Long) = Events(events = listOf(Event(id, type = EventType.Session, clientId = "event-$id")), sent = id, sentMicros = 0, received = id, receivedMicros = 0)

    @Test fun `incomplete staging frames roll back before new writes or flush with accepted data intact`(): Unit = runBlocking {
        for (flushBeforeRetry in listOf(false, true)) {
            val root = Files.createTempDirectory("analytics-staging-write-failure")
            val table = HadoopTables().create(EventSchema, root.resolve("table").toString())
            val storage = IcebergEventRepository(table, IcebergEventsToRecordTransform(EventSchema), root.resolve("staging").toString(), ProtoBuf)
            var failWrite = false
            var failRepair = false
            mockkConstructor(RandomAccessFile::class)
            try {
                every { anyConstructed<RandomAccessFile>().write(any<ByteArray>()) } answers {
                    if (failWrite) { failWrite = false; throw IOException("Synthetic failure after frame length") }
                    callOriginal()
                }
                every { anyConstructed<RandomAccessFile>().setLength(any()) } answers {
                    if (failRepair) throw IOException("Synthetic rollback failure")
                    callOriginal()
                }
                storage.process(batch(1))
                failWrite = true
                assertFailsWith<IOException> { storage.process(batch(2)) }
                failRepair = true
                assertFailsWith<IOException> { storage.process(batch(2)) }
                assertFailsWith<IOException> { storage.flush() }
                failRepair = false
                if (flushBeforeRetry) storage.flush()
                storage.process(batch(2))
                storage.process(batch(3))
                storage.flush()
                assertEquals(listOf("event-1", "event-2", "event-3"), IcebergGenerics.read(table).build().use { rows -> rows.map { it.getField("client_id").toString() }.sorted() })
            } finally {
                storage.shutdown()
                unmockkConstructor(RandomAccessFile::class)
                root.toFile().deleteRecursively()
            }
        }
    }
}
