package bosca.cli.config

import bosca.cli.BoscaCliCommand
import bosca.cli.git.GitCredentialStore
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

class ProfileCommand : BoscaCliCommand(name = "profile") {
    override fun help(context: Context) =
        "List, inspect, and select saved server/account profiles globally or by directory"

    override fun run() = Unit
}

class ProfileListCommand : BoscaCliCommand(name = "list") {
    override fun help(context: Context) = "List saved profiles without displaying credentials"

    override fun run() {
        val config = cliConfig
        if (config.profiles.isEmpty()) {
            echo("No profiles saved. Run 'bosca login --profile <name> ...' to create one.")
            return
        }

        val selected = selectedProfileName
        val directorySelection = if (CliInvocation.profileOverride == null) {
            CliConfigStore.directoryProfileSelection(config)
        } else {
            null
        }
        val nameWidth = maxOf("NAME".length, config.profiles.keys.maxOf(String::length))
        echo("   ${"NAME".padEnd(nameWidth)}  ENDPOINT  ACCOUNT")
        for ((name, profile) in config.profiles.toSortedMap()) {
            val markers = buildString {
                append(if (name == config.activeProfile) '*' else ' ')
                append(if (name == selected) '>' else ' ')
            }
            val account = profile.auth?.principalId ?: if (profile.auth != null) "configured" else "-"
            echo("$markers ${name.padEnd(nameWidth)}  ${profile.endpoint}  $account")
        }
        echo("* active; > selected for this invocation")
        directorySelection?.let {
            echo("Directory selection: ${it.profileName} (${it.directory})")
        }
    }
}

class ProfileShowCommand : BoscaCliCommand(name = "show") {
    override fun help(context: Context) = "Show a profile without displaying credentials"

    private val name by argument("name", help = "Profile name; defaults to the selected profile").optional()

    override fun run() {
        val config = cliConfig
        val resolved = requireProfile(name)

        echo("Name:      ${resolved.name}")
        echo("Active:    ${if (resolved.name == config.activeProfile) "yes" else "no"}")
        echo("Selected:  ${if (resolved.name == selectedProfileName) "yes" else "no"}")
        if (resolved.name == selectedProfileName) {
            val selectionSource = when {
                CliInvocation.profileOverride != null -> "invocation override"
                else -> CliConfigStore.directoryProfileSelection(config)
                    ?.takeIf { it.profileName == resolved.name }
                    ?.let { "directory ${it.directory}" }
                    ?: if (resolved.name == config.activeProfile) "global active profile" else "implicit default"
            }
            echo("Selected by: $selectionSource")
        }
        echo("Endpoint:  ${resolved.profile.endpoint}")
        echo("Auth:      ${if (resolved.profile.auth != null) "configured" else "not configured"}")
        resolved.profile.auth?.principalId?.let { echo("Principal: $it") }
    }
}

class ProfileUseCommand : BoscaCliCommand(name = "use") {
    override fun help(context: Context) = "Select the profile used globally or for the current directory"

    private val name by argument("name", help = "Saved profile name")

    private val local by option(
        "--local",
        help = "Use this profile in the current directory and its descendants without changing the global active profile",
    ).flag()

    override fun run() {
        requireValidProfileName(name)
        if (local) {
            val config = cliConfig
            val profile = config.profiles[name]
                ?: throw CliktError("Profile '$name' does not exist.")
            val matchingNames = config.profiles
                .filterValues { isSameEndpoint(profile.endpoint, it.endpoint) }
                .keys
                .sorted()
            if (matchingNames.size > 1 && config.activeProfile != name) {
                throw CliktError(
                    "Profile '$name' shares endpoint '${profile.endpoint}' with " +
                        "${(matchingNames - name).joinToString()}. A URL-only $DIRECTORY_CONFIG_FILE_NAME " +
                        "cannot distinguish them; make '$name' active first or use '--profile $name'.",
                )
            }
            val selection = CliConfigStore.saveDirectoryProfile(name, profile)
            echo("Profile '$name' selected in ${selection.file.absolutePath}.")
            echo("URL: ${selection.endpoint}")
            return
        }

        CliConfigStore.update { current ->
            if (name !in current.profiles) throw CliktError("Profile '$name' does not exist.")
            current.copy(activeProfile = name)
        }
        echo("Active profile: $name")
    }
}

class ProfileUnsetCommand : BoscaCliCommand(name = "unset") {
    override fun help(context: Context) = "Remove the nearest directory-specific profile selection applying here"

    override fun run() {
        val file = CliConfigStore.findDirectoryConfigFile()
            ?: throw CliktError(
                "No $DIRECTORY_CONFIG_FILE_NAME applies to ${CliConfigStore.directoryPath()}.",
            )
        if (!file.isFile) {
            throw CliktError("Directory profile configuration '${file.absolutePath}' is not a file.")
        }
        if (!file.delete()) {
            throw CliktError("Could not remove directory profile configuration '${file.absolutePath}'.")
        }
        echo("Removed directory profile configuration ${file.absolutePath}.")
    }
}

class ProfileRemoveCommand : BoscaCliCommand(name = "remove") {
    override fun help(context: Context) = "Remove a saved profile and its local credentials"

    private val name by argument("name", help = "Saved profile name")

    override fun run() {
        val config = cliConfig
        val profile = config.profiles[name]
        val directoryEndpoint = CliConfigStore.readDirectoryEndpoint()
        val matchingProfiles = directoryEndpoint?.let { local ->
            config.profiles.filterValues { isSameEndpoint(local.endpoint, it.endpoint) }
        }.orEmpty()
        if (
            profile != null &&
            directoryEndpoint != null &&
            isSameEndpoint(directoryEndpoint.endpoint, profile.endpoint) &&
            matchingProfiles.size == 1
        ) {
            throw CliktError(
                "Profile '$name' is the only saved profile matching '${directoryEndpoint.file.absolutePath}'. " +
                    "Run 'bosca profile unset' before removing it.",
            )
        }
        val updated = CliConfigStore.update { current ->
            if (name !in current.profiles) throw CliktError("Profile '$name' does not exist.")
            current.withoutProfile(name)
        }
        GitCredentialStore.removeProfile(name)
        echo("Removed profile '$name'.")
        updated.activeProfile?.let { echo("Active profile: $it") }
    }
}

internal fun requireValidProfileName(name: String) {
    if (!isValidProfileName(name)) {
        throw CliktError(
            "Invalid profile name '$name'. Use 1-64 letters, digits, dots, underscores, or hyphens; " +
                "the first character must be a letter or digit.",
        )
    }
}
