package bosca.cli.compose

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context

/** Local Compose deployment commands; host nginx and certificates use the existing installation guide. */
internal class ComposeCommand : BoscaCliCommand("compose") {
    override fun help(context: Context) = "Install, render and run a local Docker Compose deployment"
    override fun run() = Unit
}
