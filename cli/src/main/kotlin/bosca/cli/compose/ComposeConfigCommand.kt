package bosca.cli.compose

import com.github.ajalt.clikt.core.Context

internal class ComposeConfigCommand(execute: ComposeProcess = ::runComposeProcess) : ComposeAction("config", execute) {
    override fun help(context: Context) = "Validate selected services and image settings without printing secrets"
    override fun run() {
        render()
        compose("config", "--quiet")
    }
}
