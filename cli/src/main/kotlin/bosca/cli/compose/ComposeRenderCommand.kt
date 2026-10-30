package bosca.cli.compose

import com.github.ajalt.clikt.core.Context

internal class ComposeRenderCommand(execute: ComposeProcess = ::runComposeProcess) : ComposeAction("render", execute) {
    override fun help(context: Context) = "Render Compose and nginx from the installation's public .env settings"
    override fun run() = render()
}
