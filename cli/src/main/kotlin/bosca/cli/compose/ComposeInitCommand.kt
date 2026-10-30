package bosca.cli.compose

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

internal class ComposeInitCommand(execute: ComposeProcess = ::runComposeProcess) : ComposeAction("init", execute) {
    private val domain by option("--domain", help = "Shared cookie domain; required for a fresh installation")
    private val studioHost by option("--studio-domain")
    private val gitHost by option("--git-domain")
    private val atlasHost by option("--atlas-domain")
    private val registryHost by option("--artifacts-domain")
    private val separateGit by option("--separate-git").flag()
    private val separateArtifacts by option("--separate-artifacts").flag()
    private val separateUi by option("--separate-ui").flag()
    private val runner by option("--atlas-runner-enabled").flag()

    override fun help(context: Context) = "Prepare private credentials and selected Atlas/Git/registry/UI services"
    override fun run() {
        val arguments = buildList {
            listOf("--domain" to domain, "--studio-domain" to studioHost, "--git-domain" to gitHost,
                "--atlas-domain" to atlasHost, "--artifacts-domain" to registryHost).forEach { (flag, value) ->
                if (value != null) addAll(listOf(flag, value))
            }
            if (separateGit) add("--separate-git")
            if (separateArtifacts) add("--separate-artifacts")
            if (separateUi) add("--separate-ui")
            if (runner) add("--atlas-runner-enabled")
        }
        ComposePackage.install(directory(), arguments, execute)
    }
}
