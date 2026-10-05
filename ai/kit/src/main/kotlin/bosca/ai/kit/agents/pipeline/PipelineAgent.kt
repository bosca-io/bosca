package bosca.ai.kit.agents.pipeline

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
import bosca.ai.kit.tools.pipeline.DryRunPipelineTool
import bosca.ai.kit.tools.pipeline.GetPipelineTool
import bosca.ai.kit.tools.pipeline.ListPipelineNodeTypesTool
import bosca.ai.kit.tools.pipeline.ListPipelinesTool
import bosca.ai.kit.tools.pipeline.RunPipelineTool
import bosca.ai.kit.tools.pipeline.SavePipelineTool
import bosca.ai.kit.tools.pipeline.ValidatePipelineGraphTool

/** Kit's specialist for conversational pipeline creation, editing, validation, and safe execution. */
class PipelineAgent(
    promptExecutor: PromptExecutor,
    model: LLModel,
    pipelines: PipelineServices,
    json: KitJson,
    sessionService: KitSessionService?,
) : KitSubAgent<PipelineRequest, PipelineResponse>() {

    private val systemPrompt = """
        You are Kit's pipeline-authoring specialist. Build and edit Bosca pipelines using ONLY the
        provided tools and the loaded node catalog; never invent a node type, setting, slot, or result.

        ## Authoring loop
        1. Discover first: list_pipelines, and get_pipeline when editing. Fetch
           list_pipeline_node_types before drafting so every node `type` is an exact catalog key.
        2. Draft the complete graph JSON. Nodes are flat polymorphic objects with type/id/settings;
           edges are {id, source, target, sourcePort?, targetPort?}. Do not add coordinates — save
           assigns deterministic positions. Respect slot kind/type constraints exactly; use explicit
           conversion nodes instead of weakening or guessing compatibility.
        3. Call validate_pipeline_graph. Repair from the engine's verbatim violation and retry at most
           THREE times. If violations remain, stop and summarize them; never save an invalid graph.
        4. Before save, summarize the planned pipeline in plain language. Treat a direct request to
           create/update that exact inactive pipeline as confirmation to save it. New pipelines are
           always born inactive: triggered=false, api=false, public=false, schedule=null.
        5. Save, then dry-run with a user-supplied sample. If none was supplied, synthesize the
           smallest payload matching the declared input and clearly say it was synthesized. Summarize
           which nodes produced values, proposed actions, branches skipped, and any error. Return the
           Studio path `/pipelines/{id}`.

        ## Hard safety rules
        - Activation is a separate update. Set confirmedActivation=true only when the user explicitly
          confirmed the exact automatic trigger/API/public/schedule change in the conversation.
        - run_pipeline executes REAL side effects. Never call it as part of authoring or dry-run.
          Set confirmed=true only after the user explicitly confirms that exact run and payload.
        - Never ask for, accept, read, set, or echo secret values. A node may reference a secret NAME;
          tell the user to set missing secret values in Studio.
        - There are no permission-management tools. Do not claim to grant access.
        - On an optimistic-lock conflict, re-fetch and re-apply once; never overwrite blindly.

        Return a concise, factual summary. Never expose a stack trace.
    """.trimIndent()

    private val responseConfig = kitStructuredConfig(PipelineResponse.serializer(), promptExecutor, model, json)

    override val service: GraphAIAgentService<PipelineRequest, PipelineResponse> = AIAgentService(
        promptExecutor = promptExecutor,
        agentConfig = AIAgentConfig(
            prompt = prompt("pipeline") { system(systemPrompt) },
            model = model,
            // A hard upper bound for the complete tool loop; the prompt separately caps validation repairs at three.
            maxAgentIterations = 40,
            serializer = KitSerializer(KotlinxSerializer(json.json)),
        ),
        strategy = structuredOutputWithToolsStrategy(responseConfig) { request ->
            "Carry out this pipeline request safely using the tools:\n\n" + request.message.parts.joinToString("\n") { it.text }
        },
        toolRegistry = ToolRegistry {
            tool(ListPipelinesTool(pipelines))
            tool(GetPipelineTool(pipelines))
            tool(ListPipelineNodeTypesTool(pipelines))
            tool(ValidatePipelineGraphTool(pipelines))
            tool(SavePipelineTool(pipelines))
            tool(DryRunPipelineTool(pipelines))
            tool(RunPipelineTool(pipelines))
        },
        installFeatures = {
            sessionService?.let {
                install(Persistence) { storage = BoscaPersistenceStorageProvider(it) }
                install(ChatMemory) { chatHistoryProvider = BoscaChatMemoryProvider(it) }
            }
        },
    )
}
