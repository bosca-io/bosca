package bosca.backup

import bosca.storage.service.StorageDispatcher
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/** Runs a local native client, preserving failures while stopping its process on cancellation. */
internal suspend fun runPostgresBackupProcess(
    name: String,
    executable: String,
    arguments: List<String>,
    serviceFile: String?,
    errors: Path,
) {
    currentCoroutineContext().ensureActive()
    val process = ProcessBuilder(listOf(executable) + arguments).apply {
        redirectOutput(ProcessBuilder.Redirect.DISCARD)
        redirectError(errors.toFile())
        if (serviceFile != null) environment()["PGSERVICEFILE"] = serviceFile
    }.start()
    var failure: Throwable? = null
    try {
        process.outputStream.close()
        val exit = runInterruptible(StorageDispatcher) { process.waitFor() }
        val diagnostics = Files.newInputStream(errors).use { it.readNBytes(8192).toString(Charsets.UTF_8) }.trim()
        check(exit == 0) { "$name exited with status $exit: $diagnostics" }
        if (diagnostics.isNotEmpty()) {
            LoggerFactory.getLogger("bosca.backup.PostgresBackupProcess")
                .warn("{} completed with diagnostics: {}", name, diagnostics)
        }
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        withContext(NonCancellable) {
            try {
                if (process.isAlive) {
                    process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                    process.destroyForcibly().waitFor()
                }
                process.inputStream.close()
                process.errorStream.close()
                Files.deleteIfExists(errors)
            } catch (cleanup: Throwable) {
                failure?.addSuppressed(cleanup) ?: throw cleanup
            }
        }
    }
}
