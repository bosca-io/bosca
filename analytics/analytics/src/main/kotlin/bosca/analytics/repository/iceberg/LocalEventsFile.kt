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
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

internal class LocalEventsFile(
    private val table: Table,
    private val appender: DataFileAppender,
    private val transform: EventsTransform<List<Record>>,
    private val localPath: String,
    private val protoBuf: ProtoBuf,
    filename: String? = null
) {

    private val localBinFilename: String = filename ?: "events-${UUID.random()}.parquet.bin"
    private val localBinFile = File("$localPath/$localBinFilename")
    private var recordsAdded: Int = 0
    private val created: Long = System.currentTimeMillis()
    private var output: RandomAccessFile? = null
    private var incompleteWrite: Long? = null

    fun shouldAppend(force: Boolean): Boolean {
        if (force && recordsAdded > 0) return true

        if (recordsAdded == 0) return false

        // TODO: fine tune this
        return recordsAdded > 100_000 || System.currentTimeMillis() - created > 900000 // 1 million events or 15 minutes
    }

    suspend fun addEvents(events: Events) = withContext(Dispatchers.IO) {
        val data = protoBuf.encodeToByteArray(events)
        repairIncompleteWrite()
        var output = output
        if (output == null) {
            output = RandomAccessFile(localBinFile, "rw").also { it.seek(it.length()) }
            this@LocalEventsFile.output = output
        }
        incompleteWrite = output.filePointer
        output.writeInt(data.size)
        output.write(data)
        incompleteWrite = null
        recordsAdded += events.events.size
    }

    private fun repairIncompleteWrite() {
        val position = incompleteWrite ?: return
        val current = checkNotNull(output)
        current.setLength(position)
        current.seek(position)
        incompleteWrite = null
    }

    suspend fun append() = withContext(Dispatchers.IO) {
        // An ambiguous commit must never let a retry overwrite an already referenced data file.
        val localParquetFilename = "events-${UUID.random()}.parquet"
        val localParquetFile = File("$localPath/$localParquetFilename")
        log.info("Appending $localBinFilename -> $localParquetFilename")

        repairIncompleteWrite()
        output?.let {
            it.close()
            output = null
        }

        try {
            ParquetWriter(table, localParquetFile).use { writer ->
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
            }
        } finally {
            localParquetFile.delete()
        }

        localBinFile.delete()

        log.info("Appended $localBinFilename -> $localParquetFilename")
    }

    companion object {

        private val log = LoggerFactory.getLogger(LocalEventsFile::class.java)
    }
}
