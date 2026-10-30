package bosca.cli.compose

import com.github.ajalt.clikt.core.Context

internal class ComposeUpCommand(execute: ComposeProcess = ::runComposeProcess) : ComposeAction("up", execute) {
    override fun help(context: Context) = "Start selected containers and remove superseded containers without deleting data"
    override fun run() = compose("up", "-d", "--wait", "--wait-timeout", "300", "--remove-orphans")
}
