package bosca.ai.kit.configuration

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import bosca.ai.chat.service.ChatDispatcher
import bosca.ai.kit.agents.KitModels
import bosca.ai.kit.agents.image.KitImageClient
import bosca.ai.kit.agents.koogJson
import bosca.ai.kit.chat.KitChatDispatcher
import bosca.db.migrations.Migration
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.server.BoscaApplication
import kotlinx.serialization.json.Json

@Providers
class Configuration {

    @Provider(singleton = true)
    fun json(json: Json) = KitJson(koogJson(json))

    @Provider(name = "kit-migrations")
    fun migration(): Migration = KitMigration()

    /**
     * Kit requires native structured output throughout its agent graph. Keep those capabilities
     * explicit because the provider supports them even when Koog's model catalog lags behind. Keep the
     * definition local so construction is independent of Koog's provider and model catalog initialization.
     */
    @Provider(singleton = true)
    fun kitModels() = KitModels(
        default = LLModel(
            provider = LLMProvider(id = "openai", display = "OpenAI"),
            id = KIT_DEFAULT_MODEL_ID,
            capabilities = listOf(
                LLMCapability.Completion,
                LLMCapability.Speculation,
                LLMCapability.Tools,
                LLMCapability.ToolChoice,
                LLMCapability.Vision.Image,
                LLMCapability.Document,
                LLMCapability.MultipleChoices,
                LLMCapability.OpenAIEndpoint.Responses,
                LLMCapability.Thinking,
                LLMCapability.Schema.JSON.Basic,
                LLMCapability.Schema.JSON.Standard,
            ),
            contextLength = 400_000,
            maxOutputTokens = 128_000,
        )
    )

    /**
     * The Gemini image client + configured model behind [bosca.ai.kit.image.KitImageGenerationService].
     * Reads the service-account JSON and model id from app config; when the account is absent the client
     * is left unconfigured and image requests fail (the platform still starts without image credentials).
     */
    @Provider(singleton = true)
    fun kitImageClient(application: BoscaApplication): KitImageClient {
        val config = application.environment.config
        val account = config.propertyOrNull("google.genai.account")?.getString()
        val model = config.propertyOrNull("google.genai.image.model")?.getString() ?: DEFAULT_IMAGE_MODEL
        return KitImageClient(account, model)
    }

    @Provider(singleton = true, name = "kit-chat-dispatcher")
    fun chatDispatcher(harness: bosca.ai.kit.harness.KitHarnessService): ChatDispatcher = KitChatDispatcher(harness)

    private companion object {
        const val KIT_DEFAULT_MODEL_ID = "gpt-5.3-codex"

        /** Default Gemini image-generation model when `google.genai.image.model` isn't configured. */
        const val DEFAULT_IMAGE_MODEL = "gemini-3.1-flash-image-preview"
    }
}
