package bosca.analytics.repository.iceberg

import org.apache.iceberg.Schema
import org.apache.iceberg.Table
import org.apache.iceberg.data.Record
import org.apache.iceberg.data.parquet.GenericParquetWriter
import org.apache.iceberg.io.FileAppender
import org.apache.iceberg.io.OutputFile
import org.apache.iceberg.parquet.Parquet
import org.apache.iceberg.parquet.ParquetSchemaUtil
import org.slf4j.LoggerFactory
import java.io.File

class ParquetWriter(
    private val table: Table,
    file: File
) {

    private var finished = false
    var recordsAdded: Int = 0
        private set
    var recordsFailed: Int = 0
        private set

    private val localOutputFile: OutputFile = org.apache.iceberg.Files.localOutput(file)

    private val schema: Schema = table.schema()

    private val writer: FileAppender<Record> = Parquet.write(localOutputFile)
        .schema(schema)
        .forTable(table)
        .createWriterFunc {
            val messageType = ParquetSchemaUtil.convert(schema, table.name())
            GenericParquetWriter.create(schema, messageType)
        }
        .build()

    val length: Long
        get() = writer.length()

    fun addAll(records: List<Record>) {
        for (record in records) {
            try {
                writer.add(record)
                recordsAdded += 1
            } catch (e: Exception) {
                recordsFailed += 1
                log.error("Failed to write record: $record", e)
            }
        }
        if (recordsFailed > 0) {
            log.error("$recordsFailed of ${records.size} records failed to write")
        }
    }

    fun finish(): OutputFile {
        finished = true
        writer.close()
        return localOutputFile
    }

    companion object {

        private val log = LoggerFactory.getLogger(ParquetWriter::class.java)
    }
}
