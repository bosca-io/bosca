package bosca.cli.service

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import java.io.File

class ServiceStatusCommand : BoscaCliCommand(name = "status") {
    override fun help(context: Context) = "Show the status of an installed bosca service"

    private val serviceName by option(
        "--name",
        help = "Service name identifier"
    ).default("bosca")

    private val system by option(
        "--system",
        help = "Check system-wide service (requires root/sudo)"
    )

    override fun run() {
        val os = System.getProperty("os.name").lowercase()

        when {
            os.contains("mac") || os.contains("darwin") -> statusLaunchd()
            os.contains("linux") -> statusSystemd()
            else -> throw CliktError("Unsupported platform: $os. Only macOS (launchd) and Linux (systemd) are supported.")
        }
    }

    private fun statusLaunchd() {
        val label = "io.bosca.$serviceName"
        val plistDir = if (system != null) {
            File("/Library/LaunchDaemons")
        } else {
            File(System.getProperty("user.home"), "Library/LaunchAgents")
        }
        val plistFile = File(plistDir, "$label.plist")

        if (!plistFile.exists()) {
            echo("Service not installed: $label")
            echo("  Expected: ${plistFile.absolutePath}")
            return
        }

        val result = ProcessBuilder("launchctl", "list")
            .redirectErrorStream(true)
            .start()
        val output = result.inputStream.bufferedReader().readText()
        result.waitFor()

        val matching = output.lines().filter { it.contains(label) }
        if (matching.isEmpty()) {
            echo("Service installed but not loaded: $label")
            echo("  Plist: ${plistFile.absolutePath}")
            echo("  Load:  launchctl load -w ${plistFile.absolutePath}")
        } else {
            echo("Service running: $label")
            for (line in matching) {
                echo("  $line")
            }
            echo("  Plist: ${plistFile.absolutePath}")
        }
    }

    private fun statusSystemd() {
        val isSystem = system != null
        val statusArgs = if (isSystem) {
            listOf("systemctl", "status", "$serviceName.service")
        } else {
            listOf("systemctl", "--user", "status", "$serviceName.service")
        }

        val result = ProcessBuilder(statusArgs)
            .redirectErrorStream(true)
            .start()
        val output = result.inputStream.bufferedReader().readText()
        result.waitFor()

        echo(output.trimEnd())
    }
}
