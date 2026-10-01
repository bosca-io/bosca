package bosca.ai.kit.agents.strategy

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.HistoryCompressionStrategy
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMCompressHistory
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.nodeSetStructuredOutput
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.agents.ext.agent.HistoryCompressionConfig
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.structure.StructuredRequest
import ai.koog.prompt.structure.StructuredRequestConfig
import ai.koog.prompt.structure.json.JsonStructure
import bosca.ai.kit.configuration.KitJson
import kotlinx.serialization.KSerializer

/**
 * A `Native` structured-output config whose JSON schema matches the **provider** behind [model] — so Kit
 * is not pinned to one LLM vendor.
 *
 * The schema generator is asked from the [promptExecutor], which returns the dialect its client speaks:
 * for OpenAI that is the strict variant that forces *every* property into `required` (the generic
 * generator omits defaulted fields, and OpenAI rejects that with *"'required' … must include every key in
 * properties"*); for Anthropic its own variant; otherwise the generic JSON-Schema generator. Centralized
 * here so every structured Kit sub-agent picks its schema the same provider-aware way.
 *
 * Callers hand in the **explicit, KSP-generated** [serializer] (`XResponse.serializer()`) rather than a
 * reified type. Koog's `reified` `JsonStructure.create<T>()` resolves the serializer via `serializer<T>()`
 * → `typeOf<T>()`, which fails in the GraalVM native image (kotlin-reflect's `createType()` throws
 * "Unresolved class"). Passing the compiled serializer keeps schema generation reflection-free, mirroring
 * the same fix applied to `KitTool`.
 */
fun <Output> kitStructuredConfig(
    serializer: KSerializer<Output>,
    promptExecutor: PromptExecutor,
    model: LLModel,
    json: KitJson,
): StructuredRequestConfig<Output> = StructuredRequestConfig(
    default = StructuredRequest.Native(
        JsonStructure.create(
            id = serializer.descriptor.serialName.substringAfterLast("."),
            serializer = serializer,
            json = json.json,
            schemaGenerator = promptExecutor.getStandardJsonSchemaGenerator(model),
        ),
    ),
)

/**
 * Kit's default history compression: once the running conversation grows past [maxMessages], summarize
 * the WHOLE history into a TLDR so the message list (and the checkpoint that captures it) stays bounded.
 * Shared so every accumulating Kit sub-agent compresses the same way.
 */
fun wholeHistoryCompression(maxMessages: Int = 24): HistoryCompressionConfig = HistoryCompressionConfig(
    isHistoryTooBig = { prompt -> prompt.messages.size > maxMessages },
    compressionStrategy = HistoryCompressionStrategy.WholeHistory,
)

/**
 * A reusable [AIAgentGraphStrategy] for Kit's tool-using sub-agents: a tool-calling loop that ends in a
 * **structured** [Output] — the same shape as Koog's `structuredOutputWithToolsStrategy`, but with a
 * [compression] step woven into the loop. Whenever the history grows too big (per [compression]), it is
 * replaced with a TLDR before the next LLM turn, so an agent that loops over many tool calls (or, once
 * memory lands, a long conversation) keeps its history bounded. Reuse this for any accumulating sub-agent.
 */
//inline fun <reified Input, reified Output> compressingStructuredToolStrategy(
//    config: StructuredRequestConfig<Output>,
//    compression: HistoryCompressionConfig = wholeHistoryCompression(),
//    noinline transform: suspend (Input) -> String,
//): AIAgentGraphStrategy<Input, Output> = strategy("compressing_structured_tools") {
//    val setStructuredOutput by nodeSetStructuredOutput<Input, Output>(config = config)
//    val transformInput by node<Input, String> { transform(it) }
//    val callLLM by nodeLLMRequest()
//    val executeTools by nodeExecuteTools()
//    val sendToolResult by nodeLLMSendToolResults()
////    val compressHistory by nodeLLMCompressHistory<ReceivedToolResults>(
////        strategy = compression.compressionStrategy,
////        retrievalModel = compression.retrievalModel,
////    )
////    val sendCompressed by node<ReceivedToolResults, Message.Assistant> { llm.writeSession { requestLLM() } }
//    val toStructured by node<Message.Assistant, Output> { response ->
//        llm.writeSession { parseResponseToStructuredResponse(response, config, null).data }
//    }
//
//    // Set up structured output, render the input to a user message, then call the LLM.
//    nodeStart then setStructuredOutput then transformInput
//    edge(transformInput forwardTo callLLM)
//
//    // Tool calls → run them; a plain text answer → parse into the structured output.
//    edge(callLLM forwardTo executeTools onToolCalls { true })
//    edge(callLLM forwardTo toStructured onCondition { msg -> msg.parts.none { it is MessagePart.Tool.Call } })
//
//    // After tools: compress first if the history got too big, otherwise feed the results straight back.
////    edge(executeTools forwardTo compressHistory onCondition { llm.readSession { compression.isHistoryTooBig(prompt) } })
////    edge(executeTools forwardTo sendToolResult onCondition { llm.readSession { !compression.isHistoryTooBig(prompt) } })
////    edge(compressHistory forwardTo sendCompressed)
//
//    // Both the compressed and the normal paths loop on tool calls or finish on a text answer.
////    edge(sendCompressed forwardTo executeTools onToolCalls { true })
////    edge(sendCompressed forwardTo toStructured onCondition { msg -> msg.parts.none { it is MessagePart.Tool.Call } })
//    edge(sendToolResult forwardTo executeTools onToolCalls { true })
//    edge(sendToolResult forwardTo toStructured onCondition { msg -> msg.parts.none { it is MessagePart.Tool.Call } })
//
//    toStructured then nodeFinish
//}
