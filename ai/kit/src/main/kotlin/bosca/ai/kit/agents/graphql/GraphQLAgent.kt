package bosca.ai.kit.agents.graphql

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.GraphAIAgentService
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.nodeSetStructuredOutput
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.kotlinx.KotlinxSerializer
import bosca.ai.kit.agents.KitSerializer
import bosca.ai.kit.agents.KitSubAgent
import bosca.ai.kit.agents.session.BoscaChatMemoryProvider
import bosca.ai.kit.agents.session.BoscaPersistenceStorageProvider
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.agents.strategy.kitStructuredConfig
import bosca.ai.kit.configuration.KitJson
import bosca.ai.kit.tools.graphql.GraphQLGetTypeDefinitionTool
import bosca.ai.kit.tools.graphql.GraphQLListRootFieldsTool
import bosca.ai.kit.tools.graphql.GraphQLQueryTool
import bosca.ai.kit.tools.graphql.GraphQLSearchSchemaTool
import bosca.graphql.GraphQLService
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Kit's GraphQL specialist — Kit's general-purpose way to **introspect and manage Bosca** through the
 * platform's own GraphQL API. It owns the schema-discovery tools plus an executor and runs a
 * tool-calling loop: it discovers the relevant fields/types, runs the query or mutation with
 * `graphql_query`, and — having **seen the result** — answers the user from it (so "list my collections"
 * actually lists them). The agent's final node assembles the rich [GraphQLResponse]: the answer, the
 * **exact** returned data (read verbatim from the tool result, not re-typed by the model), the data's
 * type, and that type's SDL (resolved from the live schema). Every operation runs as the calling user
 * (identity flows through the ambient `KitToolContext`), so resolver permission checks apply.
 */
class GraphQLAgent(
    promptExecutor: PromptExecutor,
    model: LLModel,
    private val graphQLService: GraphQLService,
    private val json: KitJson,
    sessionService: KitSessionService?,
) : KitSubAgent<GraphQLRequest, GraphQLResponse>() {

    private val systemPrompt = """
        You are Kit's GraphQL specialist. You introspect and manage the Bosca platform through its own
        GraphQL API — the same API the UI uses — using ONLY the provided tools. Never invent fields,
        types, ids, or results.

        ## How to work
        - DISCOVER, but MINIMALLY. Use graphql_list_root_fields to find the right query/mutation, and
          graphql_get_type_definition / graphql_search_schema only for the specific type or arguments you
          actually need. Don't exhaustively explore unrelated types — find what you need and move on.
        - RUN the operation with graphql_query (a single query or mutation, with variables as a JSON
          object string). It runs as the current user, so you can only do what they're permitted to.
        - ANSWER from the RESULT. Read what graphql_query returned and answer the user's request directly
          from it — if it's a list, list the items (names/ids); if it failed, explain the error. Do not
          answer before you've run the operation and seen its result.
        - MUTATE deliberately. Mutations create/edit/delete real content; read back what you changed
          (e.g. its id/name) so you can report it.

        ## Final answer
        - message: your direct answer to the user, grounded in the actual result.
        - type: the GraphQL type name of the data the operation returned (e.g. `Metadata`, `[Collection]`).
        You don't need to repeat the raw data or its schema — the system attaches the exact result and the
        type's SDL automatically.
    """.trimIndent()

    private val summaryConfig = kitStructuredConfig(GraphQLSummary.serializer(), promptExecutor, model, json)

    override val service: GraphAIAgentService<GraphQLRequest, GraphQLResponse> = AIAgentService(
        promptExecutor = promptExecutor,
        agentConfig = AIAgentConfig(
            prompt = prompt("graphql") {
                system(systemPrompt)
            },
            model = model,
            maxAgentIterations = 100,
            serializer = KitSerializer(KotlinxSerializer(json.json)),
        ),
        strategy = strategy(),
        toolRegistry = ToolRegistry {
            tool(GraphQLListRootFieldsTool(graphQLService))
            tool(GraphQLSearchSchemaTool(graphQLService))
            tool(GraphQLGetTypeDefinitionTool(graphQLService))
            tool(GraphQLQueryTool(graphQLService, json.json))
        },
        installFeatures = {
            sessionService?.let {
                install(Persistence) { storage = BoscaPersistenceStorageProvider(it) }
                install(ChatMemory) { chatHistoryProvider = BoscaChatMemoryProvider(it) }
            }
        },
    )

    /**
     * The tool-calling loop (discover → run → answer-from-result), ending at a node that turns the
     * model's [GraphQLSummary] into the rich [GraphQLResponse]: it lifts the **exact** data out of the
     * last `graphql_query` tool result in the conversation and resolves the type's SDL from the schema.
     * (The standard structured-output-with-tools graph, with that one final node swapped in.)
     */
    private fun strategy(): AIAgentGraphStrategy<GraphQLRequest, GraphQLResponse> = strategy("graphql") {
        val setStructuredOutput by nodeSetStructuredOutput<GraphQLRequest, GraphQLSummary>(config = summaryConfig)
        val transformInput by node<GraphQLRequest, String> { request ->
            "Handle this request against the Bosca GraphQL API:\n\n" +
                request.message.parts.joinToString("\n") { it.text }
        }
        val callLLM by nodeLLMRequest()
        val executeTools by nodeExecuteTools()
        val sendToolResult by nodeLLMSendToolResults()
        val toResponse by node<Message.Assistant, GraphQLResponse> { response ->
            llm.writeSession {
                val summary = parseResponseToStructuredResponse(response, summaryConfig, null).data
                val data = lastQueryData(prompt.messages)
                GraphQLResponse(
                    message = summary.message,
                    data = data,
                    type = summary.type,
                    sdl = graphQLTypeSdl(graphQLService, summary.type),
                )
            }
        }

        nodeStart then setStructuredOutput then transformInput
        edge(transformInput forwardTo callLLM)
        edge(callLLM forwardTo executeTools onToolCalls { true })
        edge(executeTools forwardTo sendToolResult)
        edge(callLLM forwardTo toResponse onCondition { msg -> msg.parts.none { it is MessagePart.Tool.Call } })
        edge(sendToolResult forwardTo executeTools onToolCalls { true })
        edge(sendToolResult forwardTo toResponse onCondition { msg -> msg.parts.none { it is MessagePart.Tool.Call } })
        toResponse then nodeFinish
    }

    /**
     * The exact data the agent returns — the verbatim `data` from the most recent `graphql_query` tool
     * result in the conversation, deserialized straight from the tool result Koog already recorded. No
     * side channel: the result is right there in the message history.
     */
    private fun lastQueryData(messages: List<Message>): JsonElement =
        messages.asReversed()
            .firstNotNullOfOrNull { message ->
                message.parts.filterIsInstance<MessagePart.Tool.Result>().lastOrNull { it.tool == "graphql_query" }
            }
            ?.let { runCatching { json.json.decodeFromString(GraphQLQueryTool.Output.serializer(), it.output).data }.getOrNull() }
            ?: JsonNull
}
