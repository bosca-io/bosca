package bosca.cli.localization

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context

/**
 * Localization subcommand group — upload source strings, download
 * translations, check coverage status, and trigger external syncs.
 */
class LocalizationCommand : BoscaCliCommand(name = "localization") {
    override fun help(context: Context) =
        "Localization workflows — upload source strings, download translations, check status, and sync."
    override fun run() = Unit
}
