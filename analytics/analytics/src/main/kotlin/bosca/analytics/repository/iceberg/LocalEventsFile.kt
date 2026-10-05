package bosca.analytics.repository.iceberg

import bosca.analytics.model.Events
import bosca.analytics.transform.EventsTransform
import bosca.serialization.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.apache.iceberg.Table
import org.apache.iceberg.data.Record
import org.slf4j.LoggerFactory
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

internal class LocalEventsFile(
    private val table: Table,
    private val appender: DataFileAppender,
    private val transform: EventsTransform<List<Record>>,
    localPath: String,
    private val protoBuf: ProtoBuf,
    filename: String? = null
) {

    private val localParquetFilename: String = filename?.replace(".bin", "") ?: "events-${UUID.random()}.parquet"
    private val localBinFilename: String = filename ?: "$localParquetFilename.bin"
    private val localParquetFile = File("$localPath/$localParquetFilename")
    private val localBinFile = File("$localPath/$localBinFilename")
    private var recordsAdded: Int = 0
    private val created: Long = System.currentTimeMillis()
    private var output: DataOutputStream? = null

    fun shouldAppend(force: Boolean): Boolean {
        if (force && recordsAdded > 0) return true

        if (recordsAdded == 0) return false

        // TODO: fine tune this
        return recordsAdded > 100_000 || System.currentTimeMillis() - created > 900000 // 1 million events or 15 minutes
    }

    suspend fun addEvents(events: Events) = withContext(Dispatchers.IO) {
        recordsAdded += events.events.size
        val data = protoBuf.encodeToByteArray(events)
        var output = output
        if (output == null) {
            output = DataOutputStream(FileOutputStream(localBinFile))
            this@LocalEventsFile.output = output
        }
        output.writeInt(data.size)
        output.write(data)
    }

    suspend fun append() = withContext(Dispatchers.IO) {
        log.info("Appending $localBinFilename -> $localParquetFilename")

        output?.let {
            it.flush()
            it.close()
            output = null
        }

        val writer = ParquetWriter(table, localParquetFile)
        FileInputStream(localBinFile).use {
            DataInputStream(it).use { input ->
                while (input.available() > 0) {
                    val size = input.readInt()
                    val data = ByteArray(size)
                    input.readFully(data)
                    val events = protoBuf.decodeFromByteArray(Events.serializer(), data)
                    val records = transform.transform(events)
                    writer.addAll(records)
                }
            }
        }
        appender.append(writer, localParquetFilename)

        localBinFile.delete()
        localParquetFile.delete()

        log.info("Appended $localBinFilename -> $localParquetFilename")
    }

    companion object {

        private val log = LoggerFactory.getLogger(LocalEventsFile::class.java)
    }
}