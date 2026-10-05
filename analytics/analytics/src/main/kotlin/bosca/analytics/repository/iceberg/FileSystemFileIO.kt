package bosca.analytics.repository.iceberg

import org.apache.iceberg.io.FileIO
import org.apache.iceberg.io.InputFile
import org.apache.iceberg.io.OutputFile
import java.nio.file.Files
import java.nio.file.Path

@Suppress("unused")
class FileSystemFileIO : FileIO {

    override fun newInputFile(path: String): InputFile {
        return org.apache.iceberg.Files.localInput(path.split("file:").last())
    }

    override fun newOutputFile(path: String): OutputFile {
        return org.apache.iceberg.Files.localOutput(path.split("file:").last())
    }

    override fun deleteFile(path: String) {
        Files.deleteIfExists(Path.of(path.split("file:").last()))
    }
}