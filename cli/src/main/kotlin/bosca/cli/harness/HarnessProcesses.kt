package bosca.cli.harness

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import java.nio.file.Files
import kotlin.io.path.*

/** Stops the process and its writers before a checkpoint can observe their files. */
internal suspend fun stopHarnessProcess(process: Process, privateNamespace: Boolean) = withContext(Dispatchers.IO) {
    val descendants = process.descendants().use { it.toList() }
    fun writers(): List<ProcessHandle> {
        if (!privateNamespace) return (descendants + process.toHandle()).filter(ProcessHandle::isAlive)
        check(Path.of("/proc/1/comm").readText().trim() in listOf("tini", "docker-init")) { "Checkpoint requires a private PID namespace and init" }
        val uid = Files.getAttribute(Path.of("/proc/self"), "unix:uid")
        return Path.of("/proc").listDirectoryEntries().mapNotNull { path ->
            val pid = path.fileName.toString().toLongOrNull() ?: return@mapNotNull null
            if (pid == 1L || pid == ProcessHandle.current().pid()) return@mapNotNull null
            try {
                if (Files.getAttribute(path, "unix:uid") != uid || path.resolve("stat").readText().substringAfterLast(')').trim().startsWith("Z")) null
                else ProcessHandle.of(pid).orElse(null)
            } catch (_: java.nio.file.NoSuchFileException) { null }
        }
    }
    writers().forEach { it.destroy() }
    val graceful = kotlinx.coroutines.withTimeoutOrNull(20_000) { while (writers().isNotEmpty()) delay(50); true }
    if (graceful != true) writers().forEach { it.destroyForcibly() }
    withTimeout(5_000) {
        while (writers().isNotEmpty()) { writers().forEach { it.destroyForcibly() }; delay(50) }
    }
    process.waitFor()
}
