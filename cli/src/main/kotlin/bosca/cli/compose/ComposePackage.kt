package bosca.cli.compose

import com.github.ajalt.clikt.core.CliktError
import java.nio.file.Files
import java.nio.file.Path

internal typealias ComposeProcess = (List<String>, Path) -> Unit

/** Uses the same public package and installer as the standalone Compose distribution. */
internal object ComposePackage {
    private const val prefix = "/bosca/cli/compose/"

    private fun bytes(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream(prefix + if (name == ".gitignore") "package-gitignore" else name)) { "Missing Compose package resource: $name" }
            .use { it.readAllBytes() }

    fun install(directory: Path, arguments: List<String>, execute: ComposeProcess) {
        val staging = Files.createTempDirectory("bosca-compose-package-")
        try {
            bytes("package-files.txt").decodeToString().lineSequence().filter(String::isNotBlank).forEach { name ->
                val target = staging.resolve(name)
                Files.createDirectories(target.parent)
                Files.write(target, bytes(name))
            }
            execute(listOf("python3", staging.resolve("installer.py").toString(), "--directory", directory.toString()) + arguments, staging)
        } finally {
            staging.toFile().deleteRecursively()
        }
    }
}

internal fun runComposeProcess(arguments: List<String>, directory: Path) {
    val process = try {
        ProcessBuilder(arguments).directory(directory.toFile()).inheritIO().start()
    } catch (error: java.io.IOException) {
        throw CliktError(error.message ?: "Could not start ${arguments.first()}")
    }
    val code = process.waitFor()
    if (code != 0) throw CliktError("${arguments.first()} exited with status $code")
}
