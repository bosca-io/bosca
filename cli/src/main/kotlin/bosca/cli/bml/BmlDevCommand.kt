package bosca.cli.bml

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.isRegularFile

/**
 * ``bosca bml dev <project>`` — run a Gradle BML application's `bmlDev` lifecycle.
 * Gradle owns source watching, compilation, client bundling, the persistent server JVM,
 * and child-classloader hot swap, so the CLI and IntelliJ have identical behavior.
 */
class BmlDevCommand : BoscaCliCommand(name = "dev") {
    override fun help(context: Context) =
        "Run a BML application's Gradle hot-swap development lifecycle."

    private val projectDir by argument("project", help = "Project directory.")
        .path(mustExist = true, canBeFile = false, mustBeReadable = true)

    private val gradleTask by option(
        "--gradle-task",
        help = "Gradle development task to run (default: bmlDev).",
    )

    override fun run() {
        runGradleDevelopment(projectDir, gradleTask ?: "bmlDev")
    }
}

/** Command line used for the delegated lifecycle; exposed internally for native-safe unit tests. */
internal fun gradleBmlDevCommand(
    projectDir: Path,
    task: String,
    osName: String = System.getProperty("os.name"),
): List<String> {
    val windows = osName.startsWith("Windows", ignoreCase = true)
    val wrapper = projectDir.resolve(if (windows) "gradlew.bat" else "gradlew")
    val executable = if (wrapper.isRegularFile()) wrapper.toAbsolutePath().toString() else "gradle"
    return listOf(executable, task, "--console=plain")
}

private fun BoscaCliCommand.runGradleDevelopment(projectDir: Path, task: String) {
    val command = gradleBmlDevCommand(projectDir, task)
    echo("bml: delegating to ${command.joinToString(" ")}")
    val child = try {
        ProcessBuilder(command)
            .directory(projectDir.toFile())
            .inheritIO()
            .start()
    } catch (e: java.io.IOException) {
        echo("bml: unable to start Gradle: ${e.message}", err = true)
        throw ProgramResult(1)
    }
    val shutdownHook = Thread {
        if (child.isAlive) {
            child.destroy()
            if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly()
        }
    }
    Runtime.getRuntime().addShutdownHook(shutdownHook)
    val status = try {
        child.waitFor()
    } finally {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (_: IllegalStateException) {
            // JVM shutdown is already running; the hook owns child termination.
        }
    }
    if (status != 0) throw ProgramResult(status)
}
