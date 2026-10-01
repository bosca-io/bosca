package bosca.cli.bml

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * ``bosca bml init <dir>`` — scaffold a new BML project: a `build.gradle.kts`
 * applying the `io.bosca.bml` plugin and a starter `src/main/bml/home.bml`.
 */
class BmlInitCommand : BoscaCliCommand(name = "init") {
    override fun help(context: Context) = "Scaffold a new BML project."

    private val dir by argument("dir", help = "Target project directory.")
        .path(canBeFile = false)

    override fun run() {
        val bml = dir.resolve("src/main/bml")
        bml.createDirectories()

        val home = bml.resolve("home.bml")
        if (!home.exists()) home.writeText(STARTER_PAGE)

        val build = dir.resolve("build.gradle.kts")
        if (!build.exists()) build.writeText(STARTER_BUILD)

        echo("Initialized BML project at $dir")
        echo("  - src/main/bml/home.bml")
        echo("  - build.gradle.kts")
    }

    private companion object {
        val STARTER_PAGE = """
            <page route="/">
              <script server provides="greeting">"Hello, world"</script>
              <h1>{ greeting }</h1>
            </page>
        """.trimIndent() + "\n"

        val STARTER_BUILD = """
            plugins {
                kotlin("jvm")
                id("io.bosca.bml")
            }

            bml {
                sourceDir.set(layout.projectDirectory.dir("src/main/bml"))
                packageName.set("site.generated")
            }

            dependencies {
                implementation("io.bosca:core-bml")
            }
        """.trimIndent() + "\n"
    }
}
