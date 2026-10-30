package bosca.cli.compose

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import java.nio.file.Files
import java.nio.file.Path

internal abstract class ComposeAction(name: String, protected val execute: ComposeProcess) : BoscaCliCommand(name) {
    private val location by option("--directory", help = "Compose installation directory").default("/opt/bosca")
    protected fun directory(): Path = Path.of(location).toAbsolutePath().normalize()

    protected fun render() {
        val directory = directory()
        if (!Files.isRegularFile(directory.resolve(".bosca-compose-install.json"))) {
            throw CliktError("Run bosca compose init for $directory first")
        }
        ComposePackage.install(directory, listOf("--render"), execute)
    }

    protected fun compose(vararg arguments: String) {
        render()
        execute(listOf("sh", directory().resolve("compose.sh").toString()) + arguments, directory())
    }
}
