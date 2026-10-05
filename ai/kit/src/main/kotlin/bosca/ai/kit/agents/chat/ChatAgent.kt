package bosca.ai.kit.agents.chat

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
import bosca.ai.kit.agents.session.KitSessionContext
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.agents.strategy.kitStructuredConfig
import bosca.ai.kit.configuration.KitJson
import kotlinx.coroutines.currentCoroutineContext

/**
 * Kit's conversational sub-agent — the fallback when a request isn't a document or a data question. It
 * is a real agent (not an inline LLM call) so it can grow tools and memory; it returns a structured
 * [ChatResponse], and its running history is compressed (WholeHistory TLDR) as the conversation grows,
 * so the context — and the checkpoint that captures it — stays bounded.
 */
class ChatAgent(
    promptExecutor: PromptExecutor,
    model: LLModel,
    json: KitJson,
    private val sessionService: KitSessionService,
) : KitSubAgent<ChatRequest, ChatResponse>() {

    private val systemPrompt = """
        You are Kit, the Bosca platform's AI assistant — the conversational voice users talk to. Reply
        conversationally: concise, concrete, and genuinely helpful, grounded in what you actually know
        rather than guesses.

        You may be given a <conversation_context> block recapping the broader session so far — including
        work other Kit capabilities handled (documents written, data answered, topics suggested). Use it
        as background to stay consistent and avoid re-asking for things already established; it is
        reference material, NOT a new message to answer. Answer only the user's actual message.

        Bible wording is NEVER general knowledge. Never generate, recall, paraphrase, complete,
        translate, summarize, or quote Bible text from model memory. When the user asks for Bible verse
        text, a passage lookup, or verse recommendations for a topic or feeling, set handOffTo =
        SCRIPTURE, put only the references to retrieve in references, and carry any requested translation
        name or abbreviation in translation so Bosca retrieves the exact text from an installed source. If
        the user asks where an earlier Scripture response came from, answer only from an explicit source
        label already present in conversation_context; when that attribution is absent, hand off to
        SCRIPTURE instead of guessing.

        You do NOT do specialized work yourself, and you do NOT know the platform's actual data. When a
        request needs another Kit capability to EXECUTE it — authoring or editing a document, a
        data/analytics question about the content library or usage, or a lookup of what actually exists
        on the platform — do NOT attempt it, do NOT guess, and do NOT merely offer to do it: HAND IT OFF
        so the right agent runs it. Set handOffTo = WRITE for a document, QUERY for a data/analytics
        question, or GRAPHQL to look up or manage real platform data — including any "what
        content/templates/collections/metadata exist or are available?" question — with a brief reply
        acknowledging it. NEVER fabricate platform data (templates, collections, content, fields): if you
        don't actually know it, hand off to GRAPHQL. For genuine conversation — questions about what Kit
        can do, guidance, small talk — reply normally and leave handOffTo null.
    """.trimIndent()

    private val responseConfig = kitStructuredConfig(ChatResponse.serializer(), promptExecutor, model, json)

    override val service: GraphAIAgentService<ChatRequest, ChatResponse> = AIAgentService(
        promptExecutor = promptExecutor,
        agentConfig = AIAgentConfig(
            prompt = prompt("chat") {
                system(systemPrompt)
            },
            model = model,
            maxAgentIterations = 50,
            serializer = KitSerializer(KotlinxSerializer(json.json)),
        ),
        strategy = structuredOutputWithToolsStrategy(responseConfig) { request ->
            val message = request.message.parts.joinToString("\n") { it.text }
            // The <conversation_context> block (when present) precedes the message, so label the actual
            // message to keep the two distinct for the model — answer THIS, ground it in the context above.
            conversationContext(message) + "Reply to the user's message:\n\n" + message
        },
        toolRegistry = ToolRegistry { },
        installFeatures = {
            install(Persistence) { storage = BoscaPersistenceStorageProvider(sessionService) }
            install(ChatMemory) {
                chatHistoryProvider = BoscaChatMemoryProvider(sessionService)
                // Seeded context is for the LLM to read THIS turn only; strip it before it's persisted
                // so the agent's own memory thread stays the clean exchange and never re-seeds itself.
                addPreProcessor(ConversationContextPreProcessor())
            }
        },
    )

    /**
     * The broader session transcript (every user turn + Kit reply across ALL routes) as a
     * <conversation_context> block to seed THIS chat turn — so the ChatAgent has the full picture, not
     * just its own chat-routed turns. Resolved from the ambient [KitSessionContext] (the planner's action
     * sets it around the sub-agent run); returns "" when there's no session or nothing prior.
     */
    private suspend fun conversationContext(currentText: String): String {
        val sessionId = currentCoroutineContext()[KitSessionContext]?.parentSessionId ?: return ""
        return renderConversationContext(sessionService.getMessages(sessionId), currentText)
    }
}
