package bosca.ai.kit.agents.writer

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.GraphAIAgentService
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.structure.StructuredResponse
import ai.koog.serialization.kotlinx.KotlinxSerializer
import bosca.ai.kit.agents.KitSerializer
import bosca.ai.kit.agents.KitSubAgent
import bosca.ai.kit.agents.session.BoscaChatMemoryProvider
import bosca.ai.kit.agents.session.BoscaPersistenceStorageProvider
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.agents.strategy.kitStructuredConfig
import bosca.ai.kit.configuration.KitJson
import bosca.content.metadata.service.BibleService
import bosca.documents.Content
import bosca.documents.HtmlNode
import bosca.documents.NodeConverter
import kotlinx.serialization.json.Json

/**
 * Kit's document writer sub-agent. It returns a **structured** [WriterResponse] — either the finished
 * tiptap [Content], or a request for Scripture it was not given ([WriterResponse.needsScripture]).
 *
 * Its Koog graph separates the control-flow choice from the document body, because the body is large
 * free-form HTML and **must not travel through a JSON string field**: when it did, the model's
 * structured response was truncated mid-`html` (the closing quote never arrived) and the whole turn was
 * lost to a JSON parse error. So the graph:
 *
 * - renders the [WriterRequest] (task + any Scripture chapters) into the user message (**object → text**),
 * - `decide` asks the LLM for a tiny structured [WriterDecision] (does it still need Scripture?),
 * - if it needs Scripture, one edge returns `WriterResponse(needsScripture = true, references)`,
 * - otherwise `write` asks for the document as a **plain** assistant message (raw HTML — full output
 *   budget, no JSON escaping), which [NodeConverter] turns into the tiptap [Content] (**text → object**).
 *
 * The LLM thus only ever emits HTML as free text — never inside JSON, and never the recursive tiptap
 * tree, which is built by code.
 */
class WriterAgent(
    promptExecutor: PromptExecutor,
    model: LLModel,
    private val bibleService: BibleService,
    json: KitJson,
    sessionService: KitSessionService?,
) : KitSubAgent<WriterRequest, WriterResponse>() {

    private val systemPrompt = """
        You are Kit's writer. You work in two steps.

        Step 1 — decide: if the task requires quoting Scripture that is NOT provided below, set
        needsScripture=true and list the references you need; otherwise set needsScripture=false.

        Step 2 — write (only when you did NOT ask for Scripture): when you are asked for the document,
        reply with the COMPLETE document as semantic HTML and nothing else — no Markdown, no code
        fences, no commentary. Use a single <h1> title, then <p>, <blockquote> for quoted Scripture,
        <ul>/<ol>, and <sup> where useful, quoting any provided Scripture verbatim.
    """.trimIndent()

    private val decisionConfig = kitStructuredConfig(WriterDecision.serializer(), promptExecutor, model, json)

    override val service: GraphAIAgentService<WriterRequest, WriterResponse> = AIAgentService(
        promptExecutor = promptExecutor,
        agentConfig = AIAgentConfig(
            prompt = prompt("writer") {
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

    private fun strategy(): AIAgentGraphStrategy<WriterRequest, WriterResponse> = strategy("writer") {
        val decide by nodeLLMRequestStructured(config = decisionConfig)
        val plan by node<Result<StructuredResponse<WriterDecision>>, WriterDecision> { it.getOrThrow().data }
        val write by nodeLLMRequest()
        val toContent by node<Message.Assistant, WriterResponse> { response ->
            WriterResponse(content = htmlToContent(stripCodeFences(response.textContent())))
        }

        // object → text on the way in
        edge(nodeStart forwardTo decide transformed { request -> renderDecisionMessage(request) })
        edge(decide forwardTo plan)

        // branch: the writer needs Scripture it wasn't given → stop so the planner can fetch it…
        edge(
            plan forwardTo nodeFinish
                    onCondition { it.needsScripture }
                    transformed { WriterResponse(needsScripture = true, references = it.references) },
        )
        // …or it has everything → ask for the body as PLAIN HTML, then convert it (text → object).
        edge(plan forwardTo write onCondition { !it.needsScripture } transformed { WRITE_INSTRUCTION })
        edge(write forwardTo toContent)
        toContent then nodeFinish
    }

    private fun renderDecisionMessage(request: WriterRequest): String = buildString {
        append("Writing task — first decide whether you need Scripture you were not given.\n\nTask: ")
        append(request.task)
        val scriptureText = request.scripture.joinToString("\n\n") { "${it.usfm}\n${it.getChapterComponents()}" }
        if (scriptureText.isNotEmpty()) {
            append("\n\nScripture provided (quote verbatim; do not ask for these):\n")
            append(scriptureText)
        }
    }

    private fun htmlToContent(html: String): Content = Content(NodeConverter(bibleService).convertDocument(HtmlNode(html = html)))

    /** Strip a Markdown ```` ``` ```` code fence if the model wrapped the document in one despite instructions. */
    private fun stripCodeFences(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val afterOpen = trimmed.removePrefix("```")
        val body = if (afterOpen.contains('\n')) afterOpen.substringAfter('\n') else afterOpen
        return body.substringBeforeLast("```").trim()
    }

    private companion object {
        /** The follow-up that asks the writer for the document body as plain HTML (never JSON). */
        private const val WRITE_INSTRUCTION =
            "Now write the complete document as semantic HTML. Output only the HTML — no Markdown, no code fences, no commentary."
    }
}
