package bosca.cli.config

import bosca.cli.BoscaCliCommand
import bosca.cli.git.GitCredentialStore
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import java.io.File

/**
 * Views or updates persistent CLI configuration (endpoint URL).
 * With no options, prints the current configuration.
 */
class ConfigCommand : BoscaCliCommand(name = "config") {
    override fun help(context: Context) = "View or update CLI configuration"

    private val endpoint by option("--url", "-u", help = "Set the selected profile's Bosca GraphQL endpoint URL")

    override fun run() {
        val config = cliConfig
        val selectedName = selectedProfileName
        val directoryProfile = if (CliInvocation.profileOverride == null) {
            CliConfigStore.directoryProfileSelection(config)
                ?.takeIf { it.profileName == selectedName }
        } else {
            null
        }
        val resolved = findProfile()

        val newEndpoint = endpoint
        if (newEndpoint != null) {
            var endpointChanged = false
            var credentialsCleared = false
            CliConfigStore.update { current ->
                val profile = current.profiles[selectedName] ?: ProfileConfig()
                endpointChanged = !isSameEndpoint(profile.endpoint, newEndpoint)
                credentialsCleared = endpointChanged && profile.auth != null
                current.withProfile(
                    selectedName,
                    profile.copy(
                        endpoint = newEndpoint,
                        auth = if (endpointChanged) null else profile.auth,
                    ),
                    makeActive = current.activeProfile == null,
                )
            }
            if (endpointChanged) {
                GitCredentialStore.removeProfile(selectedName)
            }
            if (directoryProfile != null) {
                CliConfigStore.saveDirectoryProfile(
                    selectedName,
                    ProfileConfig(endpoint = newEndpoint),
                    File(directoryProfile.directory),
                )
            }
            echo("Endpoint for profile '$selectedName' set to: $newEndpoint")
            if (credentialsCleared) {
                echo("Stored credentials were cleared because they belong to the previous server.")
            }
            return
        }

        val selected = resolved
            ?: throw CliktError("Profile '$selectedName' does not exist.")
        echo("Config: ${CliConfigStore.configPath()}")
        echo("Profile:  ${selected.name}${if (selected.name == config.activeProfile) " (active)" else ""}")
        echo("Endpoint: ${selected.profile.endpoint}")
        echo("Auth:     ${if (selected.profile.auth != null) "configured" else "not configured"}")
        if (selected.profile.auth?.principalId != null) {
            echo("Principal: ${selected.profile.auth.principalId}")
        }
    }
}
