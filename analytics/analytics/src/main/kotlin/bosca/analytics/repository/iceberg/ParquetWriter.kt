package bosca.analytics.repository.iceberg

import org.apache.iceberg.Schema
import org.apache.iceberg.Table
import org.apache.iceberg.data.Record
import org.apache.iceberg.data.parquet.GenericParquetWriter
import org.apache.iceberg.io.FileAppender
import org.apache.iceberg.io.OutputFile
import org.apache.iceberg.parquet.Parquet
import org.apache.iceberg.parquet.ParquetSchemaUtil
import java.io.Closeable
import java.io.File

class ParquetWriter(
    private val table: Table,
    file: File
) : Closeable {

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
                throw e
            }
        }
    }

    fun finish(): OutputFile {
        check(recordsFailed == 0) { "Cannot commit a Parquet file with failed records" }
        close()
        return localOutputFile
    }

    override fun close() {
        if (!finished) {
            finished = true
            writer.close()
        }
    }
}
