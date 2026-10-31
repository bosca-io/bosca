package bosca.cli.compose

import com.github.ajalt.clikt.core.Context

internal class ComposeDownCommand(execute: ComposeProcess = ::runComposeProcess) : ComposeAction("down", execute) {
    override fun help(context: Context) = "Stop this installation's containers, retaining credentials and data"
    override fun run() = compose("down", "--remove-orphans")
}
