package bosca.cli.update

import bosca.cli.BoscaCliCommand
import bosca.cli.Version
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

/**
 * Prints the installed CLI version and, with `--check`, reports whether a newer
 * release is published on GitHub.
 */
class VersionCommand : BoscaCliCommand(name = "version") {
    override fun help(context: Context) =
        "Show the installed bosca version and optionally check for a newer release"

    private val check by option(
        "--check",
        help = "Check GitHub Releases for a newer release",
    ).flag()

    override fun run() {
        // This command reports update status itself, so suppress the automatic
        // post-run notice to avoid printing it twice.
        UpdateChecker.suppressPassive = true

        echo("bosca ${Version.current}")
        if (!check) return

        val repository = UpdateChecker.releasesRepository()
        val latest = UpdateChecker.fetchLatestVersion(repository)
        if (latest == null) {
            echo("Could not find a bosca release on github.com/$repository to check for updates.", err = true)
            return
        }
        if (UpdateChecker.isNewer(latest, Version.current)) {
            echo("A new release is available: ${Version.current} → $latest")
            echo("  Update: ${UpdateChecker.installCommand(repository)}")
        } else {
            echo("You are on the latest release ($latest).")
        }
    }
}
