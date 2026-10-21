package bosca.backup

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Publishes caller-owned local evidence without replacing an existing destination. */
internal class PostgresBackupContentReportFile(private val json: Json) {
    fun destination(output: Path): Path = output.toAbsolutePath().normalize().also {
        require(Files.isDirectory(it.parent)) { "Content report parent directory must exist" }
        require(!Files.exists(it, NOFOLLOW_LINKS)) { "Content report already exists: $it" }
    }

    suspend fun write(output: Path, report: PostgresBackupContentReport) {
        report.validate()
        val selected = destination(output)
        currentCoroutineContext().ensureActive()
        val temporary = Files.createTempFile(selected.parent, ".content-report-", ".tmp")
        var failure: Throwable? = null
        try {
            Files.newBufferedWriter(temporary).use {
                it.write(json.encodeToString(PostgresBackupContentReport.serializer(), report))
            }
            currentCoroutineContext().ensureActive()
            Files.move(temporary, selected)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    Files.deleteIfExists(temporary)
                } catch (cleanup: Throwable) {
                    failure?.addSuppressed(cleanup) ?: throw cleanup
                }
            }
        }
    }

    fun read(file: Path): PostgresBackupContentReport = Files.newBufferedReader(file).use {
        json.decodeFromString(PostgresBackupContentReport.serializer(), it.readText()).also { report -> report.validate() }
    }
}
