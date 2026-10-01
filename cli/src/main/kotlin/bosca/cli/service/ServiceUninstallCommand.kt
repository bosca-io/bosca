package bosca.cli.service

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import java.io.File

class ServiceUninstallCommand : BoscaCliCommand(name = "uninstall") {
    override fun help(context: Context) = "Uninstall a bosca system service"

    private val serviceName by option(
        "--name",
        help = "Service name identifier"
    ).default("bosca")

    private val system by option(
        "--system",
        help = "Uninstall system-wide service (requires root/sudo)"
    )

    override fun run() {
        val os = System.getProperty("os.name").lowercase()

        when {
            os.contains("mac") || os.contains("darwin") -> uninstallLaunchd()
            os.contains("linux") -> uninstallSystemd()
            else -> throw CliktError("Unsupported platform: $os. Only macOS (launchd) and Linux (systemd) are supported.")
        }
    }

    private fun uninstallLaunchd() {
        val label = "io.bosca.$serviceName"
        val plistDir = if (system != null) {
            File("/Library/LaunchDaemons")
        } else {
            File(System.getProperty("user.home"), "Library/LaunchAgents")
        }
        val plistFile = File(plistDir, "$label.plist")

        if (!plistFile.exists()) {
            echo("Service not found: ${plistFile.absolutePath}")
            return
        }

        val unloadResult = ProcessBuilder("launchctl", "unload", "-w", plistFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = unloadResult.inputStream.bufferedReader().readText().trim()
        val exitCode = unloadResult.waitFor()

        if (exitCode != 0) {
            echo("Warning: launchctl unload returned $exitCode: $output", err = true)
        } else {
            echo("Service stopped.")
        }

        plistFile.delete()
        echo("Removed: ${plistFile.absolutePath}")
        echo("Service uninstalled: $label")
    }

    private fun uninstallSystemd() {
        val isSystem = system != null
        val unitDir = if (isSystem) {
            File("/etc/systemd/system")
        } else {
            val configDir = System.getenv("XDG_CONFIG_HOME")
                ?: "${System.getProperty("user.home")}/.config"
            File(configDir, "systemd/user")
        }
        val unitFile = File(unitDir, "$serviceName.service")

        if (!unitFile.exists()) {
            echo("Service not found: ${unitFile.absolutePath}")
            return
        }

        val disableArgs = if (isSystem) {
            listOf("systemctl", "disable", "--now", "$serviceName.service")
        } else {
            listOf("systemctl", "--user", "disable", "--now", "$serviceName.service")
        }
        val disableResult = ProcessBuilder(disableArgs)
            .redirectErrorStream(true)
            .start()
        val output = disableResult.inputStream.bufferedReader().readText().trim()
        val exitCode = disableResult.waitFor()

        if (exitCode != 0) {
            echo("Warning: systemctl disable returned $exitCode: $output", err = true)
        } else {
            echo("Service stopped and disabled.")
        }

        unitFile.delete()
        echo("Removed: ${unitFile.absolutePath}")

        val reloadArgs = if (isSystem) {
            listOf("systemctl", "daemon-reload")
        } else {
            listOf("systemctl", "--user", "daemon-reload")
        }
        ProcessBuilder(reloadArgs).redirectErrorStream(true).start().waitFor()

        echo("Service uninstalled: $serviceName.service")
    }
}
