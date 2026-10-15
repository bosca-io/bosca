package bosca.cli.update

import bosca.cli.BoscaCliCommand
import bosca.cli.Version
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

/**
 * Prints the installed CLI version and, with `--check`, reports whether a newer
 * release is published in the selected GitHub or artifact repository.
 */
class VersionCommand : BoscaCliCommand(name = "version") {
    override fun help(context: Context) =
        "Show the installed bosca version and optionally check for a newer release"

    private val check by option(
        "--check",
        help = "Check GitHub releases or the configured artifact repository for a newer release",
    ).flag()

    private val artifactsUrl by option(
        "--artifacts-url",
        envvar = UpdateChecker.ARTIFACTS_URL_ENV,
        help = "Bosca raw repository download URL for release checks",
    )

    override fun run() {
        // This command reports update status itself, so suppress the automatic
        // post-run notice to avoid printing it twice.
        UpdateChecker.suppressPassive = true

        echo("bosca ${Version.current}")
        if (!check) return

        val source = try {
            UpdateChecker.releaseSource(artifactsUrl)
        } catch (e: IllegalArgumentException) {
            throw CliktError(e.message ?: "Invalid CLI release source.")
        }
        val release = UpdateChecker.fetchLatestRelease(source)
        if (release == null) {
            throw CliktError("Could not find a bosca release at ${source.listingUrl} to check for updates.")
        }
        if (UpdateChecker.isNewer(release.version, Version.current)) {
            echo("A new release is available: ${Version.current} → ${release.version}")
            echo("  Update: ${UpdateChecker.installCommand(release)}")
        } else {
            echo("No newer release is available (latest published: ${release.version}).")
        }
    }
}
