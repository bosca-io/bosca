package bosca.ai.kit.agents.routing

import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.GraphAIAgentService
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.structure.StructuredResponse
import ai.koog.serialization.kotlinx.KotlinxSerializer
import bosca.ai.kit.agents.KitSerializer
import bosca.ai.kit.agents.KitSubAgent
import bosca.ai.kit.agents.session.BoscaChatMemoryProvider
import bosca.ai.kit.agents.session.BoscaPersistenceStorageProvider
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.agents.strategy.kitStructuredConfig
import bosca.ai.kit.configuration.KitJson
import kotlinx.serialization.json.Json

/**
 * Kit's **router** as a sub-agent. Routing is the entry of every request, and a single hard-coded
 * classification is not enough — making it an agent is what lets routing take *multiple looks* at a
 * request (today a single structured decision; the graph can grow a verify/refine pass without
 * changing the [bosca.ai.kit.agents.KitSubAgent] contract). It returns a structured [RouteResponse]
 * (route + the WRITE path's title/references + a rationale), not a bare enum.
 */
class RouteAgent(
    promptExecutor: PromptExecutor,
    model: LLModel,
    json: KitJson,
    sessionService: KitSessionService?,
) : KitSubAgent<RouteRequest, RouteResponse>() {

    private val systemPrompt = """
        You are Kit's router — the first step in handling every request. Kit is the Bosca platform's
        AI assistant for creating biblically-grounded content and exploring a content library. Choose
        exactly one route for the user's request:
        • WRITE — the user clearly wants a document authored or edited (an article, devotional, study,
          or page). Provide a concise title and list any Scripture references it should quote verbatim.
        • SCRIPTURE — the user wants Bible verse text, a passage looked up, or passage recommendations
          for a topic, feeling, or situation. Choose relevant references when the user asks for
          recommendations. Put only references in references; NEVER write, recall, paraphrase, complete,
          translate, or summarize Bible text yourself. If the user names a translation, put its name or
          abbreviation in translation; otherwise leave translation empty so Bosca uses its installed default.
        • QUERY — the user is asking a data/analytics question answerable from the data warehouse
          (counts, totals, trends about content or usage), OR wants to manage an analytics artifact:
          save/update/execute a query, create/update a visualization, or build/update a dashboard.
        • DESCRIBE — the user wants a short description/summary generated FOR the document they're
          working on (e.g. a meta description). Only choose this when a document is in context.
        • TOPICS — the user wants relevant topics suggested for the document they're working on,
          drawn from the platform's topic list. Only choose this when a document is in context.
        • READING_TIME — the user wants an estimated reading time for the document they're working on.
          Only choose this when a document is in context.
        • SCRIPT — the user wants to manage server-side Kotlin scripts:
          create/edit/delete/list/get a script, enable/disable or run one, validate source, or
          add/list/remove a trigger that runs a script when a system event fires.
        • PIPELINE — the user wants to create, edit, inspect, validate, dry-run, activate, or manually
          run a pipeline/automation/workflow (for example "build a pipeline that…", "automate…", or
          "when X happens do Y"). Pipeline authoring owns orchestration; SCRIPT only owns script source.
        • IMAGE — the user wants to generate a new image from a description, or edit an existing image.
        • GRAPHQL — the user wants to inspect or manage platform data through the API in a way the other
          routes don't cover: look up, list, create, edit, or delete content/collections/metadata/
          templates/etc. via GraphQL. This INCLUDES any question about what actually exists or is
          available on the platform — "what content templates are available?", "what collections do I
          have?", "list my metadata", "what fields does Y have" — because those are real lookups, NOT
          things Kit knows from memory. Also covers "create a collection called X", "add metadata to …".
          Prefer WRITE for authoring document bodies and QUERY for analytics; use GRAPHQL for general
          read/list/create/update/delete of platform entities.
        • CHAT — conversation, guidance, small talk, or a question about Kit itself (what Kit can do).
          CHAT is only for things Kit can answer from general knowledge. It is NOT for enumerating or
          describing the platform's actual data: anything about what content/collections/templates/
          metadata exist or are available is a lookup (GRAPHQL), never a CHAT answer — Kit must not
          invent platform data. Scripture text and verse recommendations are also never CHAT; they must
          use SCRIPTURE so the Bible service, not model memory, supplies every word.
        • CLARIFY — the request is ambiguous, underspecified, or you would have to ASSUME what the user
          wants. Do NOT guess: choose CLARIFY and provide a specific, concise question that would let
          you route confidently next turn.
        When in doubt, CLARIFY — never assume. Always include a short rationale for why you need clarification.
    """.trimIndent()

    private val config = kitStructuredConfig(RouteResponse.serializer(), promptExecutor, model, json)

    override val service: GraphAIAgentService<RouteRequest, RouteResponse> = AIAgentService(
        promptExecutor = promptExecutor,
        agentConfig = AIAgentConfig(
            prompt = prompt("route") {
                system(systemPrompt)
            },
            model = model,
            maxAgentIterations = 50,
            serializer = KitSerializer(KotlinxSerializer(json.json)),
        ),
        strategy = strategy(),
        toolRegistry = ToolRegistry { },
        installFeatures = {
            sessionService?.let {
                install(Persistence) { storage = BoscaPersistenceStorageProvider(it) }
                install(ChatMemory) { chatHistoryProvider = BoscaChatMemoryProvider(it) }
            }
        },
    )

    private fun strategy(): AIAgentGraphStrategy<RouteRequest, RouteResponse> = strategy("route") {
        val decide by nodeLLMRequestStructured(config = config)
        val plan by node<Result<StructuredResponse<RouteResponse>>, RouteResponse> { it.getOrThrow().data }
        edge(nodeStart forwardTo decide transformed { request -> "Decide how to handle this request:\n\n${request.message}" })
        edge(decide forwardTo plan)
        edge(plan forwardTo nodeFinish)
    }
}
