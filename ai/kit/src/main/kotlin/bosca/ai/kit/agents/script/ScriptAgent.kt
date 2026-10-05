package bosca.ai.kit.agents.script

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.GraphAIAgentService
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.agent.structuredOutputWithToolsStrategy
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.serialization.kotlinx.KotlinxSerializer
import bosca.ai.kit.agents.KitSerializer
import bosca.ai.kit.agents.KitSubAgent
import bosca.ai.kit.agents.session.BoscaChatMemoryProvider
import bosca.ai.kit.agents.session.BoscaPersistenceStorageProvider
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.agents.strategy.kitStructuredConfig
import bosca.ai.kit.configuration.KitJson
import bosca.ai.kit.tools.script.CreateScriptTool
import bosca.ai.kit.tools.script.DeleteScriptTool
import bosca.ai.kit.tools.script.DisableScriptTool
import bosca.ai.kit.tools.script.EditScriptTool
import bosca.ai.kit.tools.script.EnableScriptTool
import bosca.ai.kit.tools.script.ExecuteScriptTool
import bosca.ai.kit.tools.script.GetScriptTool
import bosca.ai.kit.tools.script.ListScriptsTool
import bosca.ai.kit.tools.script.ValidateScriptTool

/**
 * Kit's scripting specialist. It owns the script tools and runs Koog's
 * **structured-output-with-tools** strategy: a tool-calling loop that lists/creates/edits/validates/
 * runs server-side Kotlin scripts, ending by producing a structured [ScriptResponse] summary.
 * Identity flows through the ambient `KitToolContext` the tools resolve, so every operation is
 * performed as the calling user (permission checks stay honest).
 */
class ScriptAgent(
    promptExecutor: PromptExecutor,
    model: LLModel,
    scripts: ScriptServices,
    json: KitJson,
    sessionService: KitSessionService?,
) : KitSubAgent<ScriptRequest, ScriptResponse>() {

    private val systemPrompt = """
        You are Kit's scripting specialist. You manage Bosca's server-side Kotlin scripts,
        using ONLY the provided tools — never invent results.

        ## Script types
        - GENERAL — a standalone script run on demand.
        - TRIGGER — invoked by a platform pipeline's Execute Script node when its event fires.
        - TOOL — exposed as an agent tool.
        - EPHEMERAL — a transient one-off, soft-deleted when done.

        ## How to work
        - DISCOVER first. Use list_scripts / get_script to see what exists before creating or
          editing. Don't assume a key exists.
        - VALIDATE before saving. Always validate_script (and prefer create/edit with dryRun=true) to
          preview security/compile issues, then create_script or edit_script for real.
        - EDIT is a merge — only pass the fields you want to change; blank fields keep their values.
        - RUN with execute_script only enabled scripts, passing input that matches the input schema.
        - DESTRUCTIVE ops (delete_script) — confirm the target by key first; delete reports
          dependencies and needs force=true to cascade.

        Return a concise summary of exactly what you did and the outcome — key names, ids, version
        numbers, or the error if something failed.
    """.trimIndent()

    private val responseConfig = kitStructuredConfig(ScriptResponse.serializer(), promptExecutor, model, json)

    override val service: GraphAIAgentService<ScriptRequest, ScriptResponse> = AIAgentService(
        promptExecutor = promptExecutor,
        agentConfig = AIAgentConfig(
            prompt = prompt("script") {
                system(systemPrompt)
            },
            model = model,
            maxAgentIterations = 100,
            serializer = KitSerializer(KotlinxSerializer(json.json)),
        ),
        strategy = structuredOutputWithToolsStrategy(responseConfig) { request ->
            "Carry out this scripting request using the tools:\n\n" + request.message.parts.joinToString("\n") { it.text }
        },
        toolRegistry = ToolRegistry {
            tool(ListScriptsTool(scripts.scriptService))
            tool(GetScriptTool(scripts.scriptService, scripts.agentToolService))
            tool(CreateScriptTool(scripts.scriptService, json.json, scripts.agentToolService, scripts.agentService, scripts.engine))
            tool(EditScriptTool(scripts.scriptService, json.json, scripts.agentToolService, scripts.agentService, scripts.engine))
            tool(DeleteScriptTool(scripts.scriptService, scripts.agentToolService))
            tool(EnableScriptTool(scripts.scriptService))
            tool(DisableScriptTool(scripts.scriptService))
            tool(ValidateScriptTool(scripts.engine))
            tool(ExecuteScriptTool(scripts.scriptService, scripts.scriptExecutionService, json.json))
        },
        installFeatures = {
            sessionService?.let {
                install(Persistence) { storage = BoscaPersistenceStorageProvider(it) }
                install(ChatMemory) { chatHistoryProvider = BoscaChatMemoryProvider(it) }
            }
        },
    )
}
