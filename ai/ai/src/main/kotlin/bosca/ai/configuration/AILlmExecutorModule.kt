package bosca.ai.configuration

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import bosca.di.provides
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule
import bosca.server.config.ApplicationConfig
import org.slf4j.LoggerFactory

/**
 * Configures the Koog prompt executor by reading LLM provider credentials
 * from the `koog` section of the application configuration and registering
 * a [MultiLLMPromptExecutor] into the DI container.
 *
 * Supported providers: openai, anthropic, google, mistral, openrouter, deepseek.
 * Each provider requires an `apikey` property under `koog.<provider>`.
 *
 * This is the LLM-executor half of the AI wiring, deliberately split out from
 * [AIModule] so composition roots that only need the [PromptExecutor] (e.g. the
 * analytics processor) can install it without pulling in the git-agent stack.
 * [AIModule] delegates here before doing its git/agent wiring.
 */
class AILlmExecutorModule : BoscaApplicationModule {

    private val log = LoggerFactory.getLogger(AILlmExecutorModule::class.java)

    override suspend fun install(application: BoscaApplication) {
        val config = application.environment.config
        val clients = buildLLMClients(config)
        if (clients.isNotEmpty()) {
            val executor: PromptExecutor = MultiLLMPromptExecutor(llmClients = clients)
            provides { executor }
            log.info("Registered Koog PromptExecutor with providers: {}", clients.keys.map { it.id })
        } else {
            log.warn("No Koog LLM providers configured under 'koog' — PromptExecutor will not be available")
        }
    }

    private fun buildLLMClients(config: ApplicationConfig): MutableMap<LLMProvider, LLMClient> {
        val clients = mutableMapOf<LLMProvider, LLMClient>()
        configureProvider(config, "koog.openai") { apiKey ->
            clients[LLMProvider.OpenAI] = OpenAILLMClient(apiKey)
        }
        configureProvider(config, "koog.anthropic") { apiKey ->
            clients[LLMProvider.Anthropic] = AnthropicLLMClient(apiKey)
        }
        configureProvider(config, "koog.google") { apiKey ->
            clients[LLMProvider.Google] = GoogleLLMClient(apiKey)
        }
        return clients
    }

    private inline fun configureProvider(
        config: ApplicationConfig,
        key: String,
        block: (String) -> Unit
    ) {
        config.propertyOrNull(key) ?: return
        val apiKey = config.propertyOrNull("$key.apikey")?.getString()
        if (apiKey == null) {
            log.warn("Found {} config section but 'apikey' is missing", key)
            return
        }
        block(apiKey)
    }
}
