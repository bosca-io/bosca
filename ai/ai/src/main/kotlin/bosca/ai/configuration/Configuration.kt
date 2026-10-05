package bosca.ai.configuration

import bosca.ai.agents.git.AgentProjectContentValidator
import bosca.ai.agents.service.AgentService
import bosca.ai.agents.service.AgentToolService
import bosca.ai.agents.service.McpServerRegistrationService
import bosca.ai.chat.repository.AiMigration
import bosca.ai.installer.ModelInstaller
import bosca.ai.installer.PromptInstaller
import bosca.ai.models.service.ModelService
import bosca.ai.prompts.service.PromptService
import bosca.db.migrations.Migration
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.scripting.service.ScriptService
import kotlinx.serialization.json.Json

@Providers
class ProviderConfiguration {

    @Provider(name = "ai-migrations")
    fun migration(): Migration = AiMigration()

    @Provider(name = "models-installer")
    fun models(service: ModelService): PackageInstaller = ModelInstaller(service)

    @Provider(name = "prompts-installer")
    fun prompts(service: PromptService, json: Json): PackageInstaller = PromptInstaller(service, json)

    @Provider(singleton = true)
    fun agentProjectContentValidator(
        agentService: AgentService,
        agentToolService: AgentToolService,
        mcpServerService: McpServerRegistrationService,
        promptService: PromptService,
        modelService: ModelService,
        scriptService: ScriptService,
    ): AgentProjectContentValidator = AgentProjectContentValidator(
        agentService, agentToolService, mcpServerService,
        promptService, modelService, scriptService,
    )

    @Provider(name = "ai")
    fun aiPackage(): PackageInstallation = PackageInstallation(
        key = "ai",
        name = "AI",
        versions = listOf(
            PackageInstallationVersion(
                version = "1.0.0",
                installerNames = listOf("models-installer", "prompts-installer")
            )
        )
    )
}
