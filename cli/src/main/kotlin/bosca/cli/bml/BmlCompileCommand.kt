package bosca.cli.bml

import bosca.cli.BoscaCliCommand
import bosca.bml.codegen.BmlProjectGenerator
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * ``bosca bml compile <project>`` — generate a Kotlin render `object` for every
 * `.bml` under the project's source dir, via the BML project
 * generator. Side-effect-free: reads `.bml`, writes `.kt`.
 */
class BmlCompileCommand : BoscaCliCommand(name = "compile") {
    override fun help(context: Context) =
        "Generate Kotlin from a project's .bml sources."

    private val projectDir by argument("project", help = "Project directory.")
        .path(mustExist = true, canBeFile = false, mustBeReadable = true)

    private val sourceDir by option("--source", help = "BML source dir (default: <project>/src/main/bml).").path()
    private val outputDir by option("--out", help = "Output dir (default: <project>/build/generated/bml/kotlin).").path()
    private val packageName by option("--package", help = "Generated package name.").default("bml.generated")

    override fun run() {
        val src = (sourceDir ?: projectDir.resolve("src/main/bml"))
        val out = (outputDir ?: projectDir.resolve("build/generated/bml/kotlin"))
        if (!src.exists()) {
            echo("No BML source directory: $src", err = true)
            throw ProgramResult(1)
        }
        val result = BmlProjectGenerator(packageName).generateAll(src.toFile(), out.toFile())
        result.errors.forEach { echo("error: ${it.message} (${it.span})", err = true) }
        echo("Generated ${result.written.size} file(s) into $out")
        if (result.errors.isNotEmpty()) throw ProgramResult(1)
    }
}
