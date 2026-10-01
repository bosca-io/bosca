package bosca.cli.bml

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context

/**
 * BML (Bosca Markup Language) subcommand group — scaffold, compile, and run
 * `.bml` projects. Application development delegates to the
 * Gradle plugin's process-preserving `bmlDev` lifecycle.
 */
class BmlCommand : BoscaCliCommand(name = "bml") {
    override fun help(context: Context) =
        "Bosca Markup Language CLI — scaffold and compile .bml projects."

    override fun run() = Unit
}
