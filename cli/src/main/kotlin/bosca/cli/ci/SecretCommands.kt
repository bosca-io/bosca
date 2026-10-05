package bosca.cli.ci

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlin.uuid.Uuid

class SecretSetCommand : CiSubcommand("set") {
    override fun help(context: Context) = "Set a pipeline secret"

    private val repoId by option("--repo-id", help = "Repository ID").required()
    private val name by option("--name", help = "Secret name").required()
    private val value by option("--value", help = "Secret value").required()

    override suspend fun execute(api: CiApi) {
        val secret = api.setPipelineSecret(Uuid.parse(repoId), name, value)
        echo("Secret '${secret.name}' set successfully.")
    }
}

class SecretListCommand : CiSubcommand("list") {
    override fun help(context: Context) = "List pipeline secret names"

    private val repoId by option("--repo-id", help = "Repository ID").required()

    override suspend fun execute(api: CiApi) {
        val secrets = api.listPipelineSecrets(Uuid.parse(repoId))
        if (secrets.isEmpty()) {
            echo("No secrets configured.")
            return
        }
        echo("%-30s  %-20s  %s".format("NAME", "CREATED", "UPDATED"))
        for (s in secrets) {
            echo("%-30s  %-20s  %s".format(
                s.name,
                s.created.toString().take(19),
                s.updated.toString().take(19),
            ))
        }
    }
}

class SecretDeleteCommand : CiSubcommand("delete") {
    override fun help(context: Context) = "Delete a pipeline secret"

    private val repoId by option("--repo-id", help = "Repository ID").required()
    private val name by option("--name", help = "Secret name").required()

    override suspend fun execute(api: CiApi) {
        api.deletePipelineSecret(Uuid.parse(repoId), name)
        echo("Secret '$name' deleted.")
    }
}
