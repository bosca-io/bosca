package bosca.cli.service

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context

class ServiceCommand : BoscaCliCommand(name = "service") {
    override fun help(context: Context) = "Install or uninstall bosca as a system service (systemd/launchd)"
    override fun run() = Unit
}
