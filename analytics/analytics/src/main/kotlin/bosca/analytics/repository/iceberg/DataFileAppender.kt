package bosca.analytics.repository.iceberg

import org.apache.iceberg.DataFiles
import org.apache.iceberg.FileFormat
import org.apache.iceberg.Table
import org.apache.iceberg.io.OutputFile

class DataFileAppender(private val table: Table) {

    private fun copyToLocation(writer: ParquetWriter, filename: String): OutputFile {
        val localOutputFile = writer.finish()
        val remoteOutputFile = table.io().newOutputFile(table.locationProvider().newDataLocation(filename))
        val localInputFile = localOutputFile.toInputFile()
        remoteOutputFile.createOrOverwrite().use { output ->
            localInputFile.newStream().use { input ->
                input.copyTo(output)
            }
            output.flush()
        }
        return remoteOutputFile
    }

    fun append(writer: ParquetWriter, filename: String) {
        val file = copyToLocation(writer, filename)
        val appendFile = DataFiles.builder(table.spec())
            .withPath(file.location())
            .withInputFile(file.toInputFile())
            .withFileSizeInBytes(writer.length)
            .withFormat(FileFormat.PARQUET)
            .withRecordCount(writer.recordsAdded.toLong())
            .build()
        val append = table.newAppend()
        append.appendFile(appendFile)
        append.commit()
    }
}