package bosca.cli.compose

import com.github.ajalt.clikt.core.Context

internal class ComposeStatusCommand(execute: ComposeProcess = ::runComposeProcess) : ComposeAction("status", execute) {
    override fun help(context: Context) = "Show this installation's Compose services"
    override fun run() = compose("ps")
}
